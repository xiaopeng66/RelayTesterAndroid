package com.relaytester.app

import com.relaytester.app.core.fingerprint.sha256Hex
import com.relaytester.app.core.update.DownloadExpectation
import com.relaytester.app.core.update.DownloadProgress
import com.relaytester.app.core.update.OkHttpFetcher
import com.relaytester.app.core.update.UpdateHosts
import com.relaytester.app.core.update.UpdateNetworkException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real fetcher against a socket that writes exactly the bytes a test asks for.
 *
 * The bank arrives over the network, so the transport is part of the feature: a body that
 * stops early, a non-2xx reply and a body over the ceiling all have to reach the panel as
 * a sentence a user can act on. The fake fetcher the other tests use cannot say anything
 * about that — a truncated download reached a device showing OkHttp's own wording
 * ("unexpected end of stream"), which is what put this class here.
 */
class OkHttpFetcherTest {
    private val fetcher = OkHttpFetcher()

    /** A one-shot HTTP server; [respond] writes the reply once the request line has arrived. */
    private class WireServer(respond: (OutputStream) -> Unit) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort

        private val thread = Thread {
            try {
                socket.accept().use { client ->
                    client.getInputStream().readRequestHead()
                    respond(client.getOutputStream())
                    client.getOutputStream().flush()
                }
            } catch (_: IOException) {
                // A client that hangs up mid-body is a case these tests provoke on purpose.
            }
        }.apply {
            isDaemon = true
            start()
        }

        override fun close() {
            socket.close()
            thread.interrupt()
        }
    }

    private fun reply(status: String, length: Long?, body: ByteArray): (OutputStream) -> Unit = { out ->
        out.write((status + "\r\n").toByteArray())
        if (length != null) out.write("Content-Length: $length\r\n".toByteArray())
        out.write("Content-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
    }

    private fun failureOf(server: WireServer, path: String, maxBytes: Int): UpdateNetworkException? {
        val failure = runCatching {
            runBlocking { fetcher.fetch("http://127.0.0.1:${server.port}$path", maxBytes) }
        }.exceptionOrNull()
        return failure as? UpdateNetworkException
    }

    @Test
    fun `a whole body is returned as it was sent`() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        WireServer(reply("HTTP/1.1 200 OK", payload.size.toLong(), payload)).use { server ->
            val bytes = runBlocking { fetcher.fetch(url(server, "/bank.bin"), 4 * 1024 * 1024) }

            assertArrayEquals(payload, bytes)
        }
    }

    @Test
    fun `the manifest bytes are handed over untouched`() {
        val payload = "{\"formatVersion\":1}".toByteArray()
        WireServer(reply("HTTP/1.1 200 OK", payload.size.toLong(), payload)).use { server ->
            val bytes = runBlocking { fetcher.fetch(url(server, "/latest.json"), 1024) }

            assertEquals(String(payload), String(bytes))
        }
    }

    @Test
    fun `a non 2xx reply names the status`() {
        WireServer(reply("HTTP/1.1 404 Not Found", 0, ByteArray(0))).use { server ->
            val failure = failureOf(server, "/latest.json", 1024)

            assertNotNull("expected an UpdateNetworkException", failure)
            assertTrue(failure!!.message!!.contains("404"))
        }
    }

    @Test
    fun `a body that stops early is reported as an interrupted download`() {
        // Length says 50 KB, the socket hands over 100 bytes and hangs up: exactly what a
        // dropped connection looks like, and what OkHttp words "unexpected end of stream".
        WireServer { out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 50000\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(ByteArray(100) { 7 })
        }.use { server ->
            val failure = failureOf(server, "/half.bin", 4 * 1024 * 1024)

            assertNotNull("expected an UpdateNetworkException", failure)
            assertTrue(
                "截断的下载要以中文说明开头：${failure!!.message}",
                failure.message!!.startsWith("下载中断"),
            )
            assertNotNull("底层原因不能丢掉", failure.cause)
        }
    }

    @Test
    fun `a declared length over the ceiling is refused without reading it`() {
        WireServer(reply("HTTP/1.1 200 OK", 10L * 1024 * 1024, ByteArray(1024))).use { server ->
            val failure = failureOf(server, "/huge.bin", 1024 * 1024)

            assertNotNull("expected an UpdateNetworkException", failure)
            assertTrue(failure!!.message!!.contains("文件过大"))
        }
    }

    @Test
    fun `a body longer than the ceiling is refused while it is arriving`() {
        // No declared length, so the streaming ceiling is the only defence.
        val chunk = ByteArray(64 * 1024) { 3 }
        WireServer { out ->
            out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
            repeat(20) { out.write(chunk) }
        }.use { server ->
            val failure = failureOf(server, "/flood.bin", 256 * 1024)

            assertNotNull("expected an UpdateNetworkException", failure)
            assertTrue(failure!!.message!!.contains("文件过大"))
        }
    }

    private fun url(server: WireServer, path: String) = "http://127.0.0.1:${server.port}$path"

    // ---- the APK leg: streaming to disk ------------------------------------

    private fun target(name: String) = File(tempDirectory("fetcher-$name"), "RelayTester.apk")

    @Test
    fun `a downloaded body lands in the file it was asked for`() {
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        val target = target("whole")
        WireServer(reply("HTTP/1.1 200 OK", payload.size.toLong(), payload)).use { server ->
            val written = runBlocking {
                fetcher.downloadTo(url(server, "/app.apk"), target, 4L * 1024 * 1024)
            }

            assertEquals(target.absolutePath, written.absolutePath)
            assertArrayEquals(payload, written.readBytes())
            // The target name is what gets handed to the system installer, so the part
            // file must not be left behind next to it.
            assertEquals(emptyList<String>(), written.parentFile!!.list()!!.filter { it.endsWith(".part") })
        }
    }

    @Test
    fun `a download over the ceiling leaves neither a target nor a part file`() {
        val chunk = ByteArray(64 * 1024) { 3 }
        val target = target("flood")
        WireServer { out ->
            // No declared length, so the streaming ceiling is the only defence.
            out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
            repeat(20) { out.write(chunk) }
        }.use { server ->
            val failure = runCatching {
                runBlocking { fetcher.downloadTo(url(server, "/flood.apk"), target, 256L * 1024) }
            }.exceptionOrNull() as? UpdateNetworkException

            assertNotNull("expected an UpdateNetworkException", failure)
            // The APK leg's own wording, not the in-memory one: the production ceiling is
            // 64 MB and this message is stated in megabytes.
            assertTrue("没有说明是安装包过大：${failure!!.message}", failure.message!!.contains("安装包过大"))
            assertFalse("被拒绝的下载留下了文件", target.exists())
            assertFalse("被拒绝的下载留下了半截文件", File(target.parentFile, "${target.name}.part").exists())
        }
    }

    @Test
    fun `a download that stops early leaves neither a target nor a part file`() {
        val target = target("truncated")
        WireServer { out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 500000\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(ByteArray(100) { 7 })
        }.use { server ->
            val failure = runCatching {
                runBlocking { fetcher.downloadTo(url(server, "/half.apk"), target, 4L * 1024 * 1024) }
            }.exceptionOrNull() as? UpdateNetworkException

            assertNotNull("expected an UpdateNetworkException", failure)
            assertTrue(
                "截断的下载要以中文说明开头：${failure!!.message}",
                failure.message!!.startsWith("下载中断"),
            )
            assertFalse("截断的下载留下了文件", target.exists())
            assertFalse("截断的下载留下了半截文件", File(target.parentFile, "${target.name}.part").exists())
        }
    }

    @Test
    fun `a download reports its progress and the declared total`() {
        val payload = ByteArray(200_000) { 9 }
        val target = target("progress")
        val seen = mutableListOf<DownloadProgress>()
        WireServer(reply("HTTP/1.1 200 OK", payload.size.toLong(), payload)).use { server ->
            runBlocking {
                fetcher.downloadTo(url(server, "/app.apk"), target, 4L * 1024 * 1024) { seen += it }
            }
        }

        assertEquals(0L, seen.first().bytesRead)
        assertEquals(payload.size.toLong(), seen.first().totalBytes)
        assertEquals(payload.size.toLong(), seen.last().bytesRead)
        assertEquals(1f, seen.last().fraction!!, 0.0001f)
        // Monotonic: a bar that jumps backwards is worse than no bar.
        assertEquals(seen.map { it.bytesRead }.sorted(), seen.map { it.bytesRead })
    }

    // ---- the expectation: verified while it is still a part file -----------

    private fun redirect(location: String?): (OutputStream) -> Unit = { out ->
        out.write("HTTP/1.1 302 Found\r\n".toByteArray())
        if (location != null) out.write("Location: $location\r\n".toByteArray())
        out.write("Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    @Test
    fun `a download that matches its expectation is renamed into place`() {
        val payload = ByteArray(5_000) { (it % 97).toByte() }
        val target = target("expected-ok")
        WireServer(reply("HTTP/1.1 200 OK", payload.size.toLong(), payload)).use { server ->
            val written = runBlocking {
                fetcher.downloadTo(
                    url(server, "/app.apk"),
                    target,
                    4L * 1024 * 1024,
                    DownloadExpectation(sizeBytes = payload.size.toLong(), sha256 = sha256Hex(payload)),
                )
            }

            assertEquals(target.absolutePath, written.absolutePath)
            assertEquals(emptyList<String>(), written.parentFile!!.list()!!.filter { it.endsWith(".part") })
        }
    }

    @Test
    fun `a download of the wrong size never takes the target name`() {
        // The target name is what the system installer is handed: a file that failed its
        // check must never have been named that, not even for the moment before a delete.
        val payload = ByteArray(1_000) { 5 }
        val target = target("expected-size")
        WireServer(reply("HTTP/1.1 200 OK", payload.size.toLong(), payload)).use { server ->
            val failure = runCatching {
                runBlocking {
                    fetcher.downloadTo(
                        url(server, "/app.apk"),
                        target,
                        4L * 1024 * 1024,
                        DownloadExpectation(sizeBytes = payload.size + 1L),
                    )
                }
            }.exceptionOrNull() as? UpdateNetworkException

            assertNotNull("大小不符没有被拒绝", failure)
            assertTrue("提示里没有两个尺寸：${failure!!.message}", failure.message!!.contains("${payload.size + 1}"))
            assertFalse("校验失败的下载占了安装用的文件名", target.exists())
            assertFalse("校验失败的下载留下了半截文件", File(target.parentFile, "${target.name}.part").exists())
        }
    }

    @Test
    fun `a download of the wrong digest never takes the target name`() {
        val payload = ByteArray(1_000) { 5 }
        val target = target("expected-digest")
        WireServer(reply("HTTP/1.1 200 OK", payload.size.toLong(), payload)).use { server ->
            val failure = runCatching {
                runBlocking {
                    fetcher.downloadTo(
                        url(server, "/app.apk"),
                        target,
                        4L * 1024 * 1024,
                        DownloadExpectation(sha256 = "0".repeat(64)),
                    )
                }
            }.exceptionOrNull() as? UpdateNetworkException

            assertNotNull("摘要不符没有被拒绝", failure)
            assertTrue("提示没有说是摘要问题：${failure!!.message}", failure.message!!.contains("SHA-256"))
            assertFalse("校验失败的下载占了安装用的文件名", target.exists())
            assertFalse("校验失败的下载留下了半截文件", File(target.parentFile, "${target.name}.part").exists())
        }
    }

    // ---- redirects are followed by hand, against the release hosts ---------

    @Test
    fun `a redirect to a host outside the release list is refused`() {
        // The address comes from a feed whose digest travels in the same document, so a hop
        // off the release hosts is how the bytes and the checksum both end up an attacker's.
        WireServer(redirect("https://evil.test/app.apk")).use { server ->
            val failure = failureOf(server, "/app.apk", 1024)

            assertNotNull("跳转到别的站点没有被拒绝", failure)
            assertTrue("提示没有说是站点问题：${failure!!.message}", failure.message!!.contains("不允许的站点"))
        }
    }

    @Test
    fun `a redirect that would leave https is refused`() {
        WireServer(redirect("http://127.0.0.1:1/app.apk")).use { server ->
            val failure = failureOf(server, "/app.apk", 1024)

            assertNotNull("明文降级没有被拒绝", failure)
            assertTrue("提示没有说是站点问题：${failure!!.message}", failure.message!!.contains("不允许的站点"))
        }
    }

    @Test
    fun `a redirect without a target is refused`() {
        WireServer(redirect(null)).use { server ->
            val failure = failureOf(server, "/app.apk", 1024)

            assertNotNull("没有 Location 的跳转没有得到处理", failure)
            assertTrue("提示没有说是缺目标：${failure!!.message}", failure.message!!.contains("缺少目标"))
        }
    }

    @Test
    fun `a redirect whose target cannot be used is refused`() {
        // A malformed Location is a refusal, not a guess: resolving it against the reply's own
        // URL would otherwise turn ":://not a url" into a same-host path and follow it.
        WireServer(redirect(":://not a url")).use { server ->
            val failure = failureOf(server, "/app.apk", 1024)

            assertNotNull("无法使用的跳转目标没有得到处理", failure)
            assertTrue("提示不是跳转相关的拒绝：${failure!!.message}", failure.message!!.contains("跳转"))
        }
    }

    @Test
    fun `the update chain will not chase an unlimited redirect chain`() {
        // The cap only ever matters on a chain of *allowed* hosts, which a socket test cannot
        // build without talking to GitHub; the number itself is the assertion here.
        assertTrue(
            "跳转上限太大，等于没有上限：${UpdateHosts.MAX_UPDATE_REDIRECT_HOPS}",
            UpdateHosts.MAX_UPDATE_REDIRECT_HOPS <= 5,
        )
    }

    // ---- a redirect that is allowed is actually followed to the end ------------

    /**
     * A client whose replies are fabricated by an interceptor, so the *accepted* hop — the
     * one the allow-list exists to permit — can be exercised offline. The real chain has
     * exactly this shape (`github.com` → `release-assets.githubusercontent.com`) and no
     * socket test can build it: the accepted target is https on a host this machine cannot
     * serve. The address never reaches DNS or a connection; the interceptor answers first.
     */
    private fun syntheticClient(finalBody: ByteArray): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                val redirect = request.url.host == "github.com"
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (redirect) 302 else 200)
                    .message(if (redirect) "Found" else "OK")
                    .apply {
                        if (redirect) {
                            header("Location", "https://release-assets.githubusercontent.com/final")
                        }
                    }
                    .body(if (redirect) ByteArray(0).toResponseBody(null) else finalBody.toResponseBody(null))
                    .build()
            }
            .build()

    @Test
    fun `a fetch that is redirected once is followed to the final body`() {
        // The chain the app meets on every real check: github.com answers 302 and the bytes
        // are on the CDN host. Returning them is the whole point of the allow-list; a
        // redirect the app cannot complete is an update channel that never works.
        val payload = "{\"formatVersion\":2}".toByteArray()
        val fetcher = OkHttpFetcher(syntheticClient(payload))

        val bytes = runBlocking {
            withTimeout(5_000) { fetcher.fetch("https://github.com/start.json", 1024) }
        }

        assertArrayEquals(payload, bytes)
    }

    @Test
    fun `a download that is redirected once is followed to the final bytes`() {
        val payload = ByteArray(4_000) { (it % 89).toByte() }
        val target = target("redirected")
        val fetcher = OkHttpFetcher(syntheticClient(payload))

        val written = runBlocking {
            withTimeout(5_000) { fetcher.downloadTo("https://github.com/start.apk", target, 4L * 1024 * 1024) }
        }

        assertEquals(target.absolutePath, written.absolutePath)
        assertArrayEquals(payload, written.readBytes())
    }

    @Test
    fun `cancelling a redirected fetch cancels the request that is on the wire`() = runBlocking {
        // One cancellation handler follows the whole chain and swaps the call it cancels as
        // each hop begins. Without that swap, cancelling a download leaves the connection that
        // is actually open untouched: the coroutine is gone, the socket is not.
        val calls = ConcurrentLinkedQueue<Call>()
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                calls.add(chain.call())
                val redirect = request.url.host == "github.com"
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (redirect) 302 else 200)
                    .message(if (redirect) "Found" else "OK")
                    .apply {
                        if (redirect) {
                            header("Location", "https://release-assets.githubusercontent.com/final")
                        }
                    }
                    .body(
                        if (redirect) {
                            ByteArray(0).toResponseBody(null)
                        } else {
                            // The last hop answers, then its body waits: the call has to be in
                            // flight while the coroutine is cancelled, which is the window this
                            // test is about.
                            object : ResponseBody() {
                                override fun contentType(): MediaType? = null
                                override fun contentLength(): Long = -1L
                                override fun source(): BufferedSource = object : Source {
                                    override fun timeout(): Timeout = Timeout.NONE
                                    override fun read(sink: Buffer, byteCount: Long): Long {
                                        release.await(5, TimeUnit.SECONDS)
                                        return -1L
                                    }
                                    override fun close() = Unit
                                }.buffer()
                            }
                        },
                    )
                    .build()
            }
            .build()
        val fetcher = OkHttpFetcher(client)
        try {
            val job = launch(Dispatchers.IO) {
                fetcher.fetch("https://github.com/start.json", 1024)
            }
            withTimeout(5_000) {
                while (calls.size < 2) delay(10)
            }
            val onWire = calls.last()
            job.cancelAndJoin()
            assertTrue("取消后仍在网络上的请求没有被中止", onWire.isCanceled())
        } finally {
            release.countDown()
        }
    }
}

/** Consumes one request up to the blank line that ends its headers. */
private fun InputStream.readRequestHead() {
    var tail = 0
    while (true) {
        val byte = read()
        if (byte < 0) return
        tail = (tail shl 8) or byte
        if (tail == 0x0D0A0D0A) return
    }
}
