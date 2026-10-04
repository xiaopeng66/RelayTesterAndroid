package com.relaytester.app

import com.relaytester.app.core.update.DownloadProgress
import com.relaytester.app.core.update.OkHttpFetcher
import com.relaytester.app.core.update.UpdateNetworkException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
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
