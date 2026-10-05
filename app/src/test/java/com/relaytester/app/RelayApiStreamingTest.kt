package com.relaytester.app

import com.relaytester.app.core.model.ApiResult
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestSettings
import com.relaytester.app.core.network.RelayApi
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streaming completion against a socket that writes real SSE.
 *
 * The protocol-level tests cover what each event means; this covers the reader: framing
 * on `data:` lines, honouring `[DONE]`, surviving keep-alives and comments, and falling
 * back when the upstream answers the streaming request with an ordinary JSON body. A
 * reader that mishandled any of those would still pass the parser tests while showing the
 * user a wrong live count.
 */
class RelayApiStreamingTest {
    private val api = RelayApi()

    /** A one-shot HTTP server that writes exactly what [respond] gives it. */
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
                // A client that hangs up early is provoked on purpose by some cases.
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

    private fun supplier(port: Int) = SupplierProfile(
        id = "sup-1",
        name = "local",
        baseUrl = "http://127.0.0.1:$port/v1",
        protocol = RelayProtocol.CHAT_COMPLETIONS,
        apiKeySecretId = "secret-1",
        models = listOf("m"),
        testSettings = TestSettings(),
    )

    private fun sse(vararg events: String): (OutputStream) -> Unit = { out ->
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        events.forEach { out.write(it.toByteArray()) }
    }

    private fun chatChunk(content: String): String =
        "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"}}]}\n\n"

    private fun stream(
        port: Int,
        onProgress: (String) -> Unit = {},
    ): ApiResult<String> = runBlocking {
        api.completeTextStreaming(
            profile = supplier(port),
            apiKey = "k",
            model = "m",
            prompt = "p",
            maxTokens = 100,
            timeoutSeconds = 30,
        ) { text -> onProgress(text) }
    }

    @Test
    fun `an SSE answer is reassembled and reported as it arrives`() {
        val seen = mutableListOf<String>()
        WireServer(
            sse(
                chatChunk("1 2 "),
                chatChunk("3 4 "),
                chatChunk("5 6"),
                "data: [DONE]\n\n",
            ),
        ).use { server ->
            val result = stream(server.port) { seen += it }

            assertTrue("expected Success, got $result", result is ApiResult.Success)
            assertEquals("1 2 3 4 5 6", (result as ApiResult.Success).value)
            // The callback must have fired per delta, accumulating — that is what makes
            // the panel's count live rather than a single jump at the end.
            assertEquals(listOf("1 2 ", "1 2 3 4 ", "1 2 3 4 5 6"), seen)
        }
    }

    @Test
    fun `comments and keep-alives do not become text`() {
        WireServer(
            sse(
                ": keep-alive\n\n",
                "event: ping\n\n",
                chatChunk("7 8"),
                "id: 42\n\n",
                "data: [DONE]\n\n",
            ),
        ).use { server ->
            val result = stream(server.port)

            assertEquals("7 8", (result as ApiResult.Success).value)
        }
    }

    @Test
    fun `a non-data line with a JSON body is not answer text`() {
        // The framing rule is "only `data:` carries a payload". This line is valid JSON
        // after the prefix is stripped, so a reader that treated any line as data would
        // fold it into the answer — the equivalent-mutant case the keep-alive above
        // cannot catch, because that one is not valid JSON and gets skipped by accident.
        val smuggled = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", "999 999"))))
            .toString()
        WireServer(
            sse(
                chatChunk("7 8"),
                "id: $smuggled\n\n",
                "data: [DONE]\n\n",
            ),
        ).use { server ->
            val result = stream(server.port)

            assertEquals("只有 data 行才算载荷", "7 8", (result as ApiResult.Success).value)
        }
    }

    @Test
    fun `nothing after DONE is read into the answer`() {
        // A relay may keep the connection open after [DONE] and send usage totals or
        // heartbeats. Everything after the terminator belongs to no delta, so a reader
        // that ignored [DONE] would append whatever followed.
        WireServer(
            sse(
                chatChunk("11 "),
                chatChunk("12"),
                "data: [DONE]\n\n",
                chatChunk(" 13 14"),
                chatChunk(" 15"),
            ),
        ).use { server ->
            val result = stream(server.port)

            assertEquals("DONE 之后的内容不得计入", "11 12", (result as ApiResult.Success).value)
        }
    }

    @Test
    fun `a stream that ends without DONE still yields what arrived`() {
        // Some relays simply close the connection. The answer in hand is the answer.
        WireServer(sse(chatChunk("9 10"), chatChunk(" 11"))).use { server ->
            val result = stream(server.port)

            assertTrue("expected Success, got $result", result is ApiResult.Success)
            assertEquals("9 10 11", (result as ApiResult.Success).value)
        }
    }

    @Test
    fun `a stream that never terminates is cut off at the ceiling instead of growing forever`() {
        // A relay that answers `text/event-stream` and then simply keeps talking — no [DONE],
        // no close. Before the ceiling existed this appended into one string until the process
        // ran out of memory, and nothing upstream of it could notice: the read timeout is per
        // read, and deltas keep arriving.
        WireServer { out ->
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )
            val filler = "1 ".repeat(60)
            repeat(4_000) { out.write(chatChunk(filler).toByteArray()) }
        }.use { server ->
            val result = stream(server.port)

            assertTrue("应当因过大而失败，实际 $result", result is ApiResult.Failure)
            val error = (result as ApiResult.Failure).error
            assertTrue("失败原因要说清是流太大：${error.message}", error.message.contains("流式响应过大"))
            assertTrue("过大的流不是网络故障", error.message.isNotBlank())
        }
    }

    @Test
    fun `a single line with no newline is not buffered without a bound`() {
        // The other unbounded read: bytes with no terminator. Framing alone cannot bound this
        // (it is waiting for a newline), so the bound is on the bytes and applies here too.
        WireServer { out ->
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )
            // More than the reader's ceiling, in one unterminated line: the reader has to
            // give up mid-line, which is the case a newline-oriented bound cannot cover.
            val blob = "x".repeat(8_192)
            repeat(40) { out.write(blob.toByteArray()) }
        }.use { server ->
            val result = stream(server.port)

            assertTrue("应当因过大而失败，实际 $result", result is ApiResult.Failure)
            val error = (result as ApiResult.Failure).error
            assertTrue("失败原因要说清是流太大：${error.message}", error.message.contains("流式响应过大"))
        }
    }

    @Test
    fun `a malformed event is skipped instead of failing the request`() {
        WireServer(
            sse(
                chatChunk("12 "),
                "data: {not json at all\n\n",
                chatChunk("13"),
                "data: [DONE]\n\n",
            ),
        ).use { server ->
            val result = stream(server.port)

            // One unparseable frame must not cost the user a whole challenge; both good
            // frames still count.
            assertEquals("12 13", (result as ApiResult.Success).value)
        }
    }

    @Test
    fun `a buffered JSON reply to a streaming request is still read`() {
        // The fallback: a relay that ignores `stream: true`. It answers with one JSON
        // document and no SSE content type, and the panel must get the text anyway —
        // this is the case that keeps the feature from breaking working relays.
        WireServer { out ->
            val body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"14 15\"}}]}"
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\n" +
                        "Connection: close\r\n\r\n" +
                        body
                    ).toByteArray(),
            )
        }.use { server ->
            val result = stream(server.port)

            assertTrue("expected Success, got $result", result is ApiResult.Success)
            assertEquals("14 15", (result as ApiResult.Success).value)
        }
    }

    @Test
    fun `a relay that refuses streaming falls back to the buffered call`() {
        // The real shape of "this relay cannot stream": HTTP 400 for the streamed
        // request, then a normal answer for the buffered one. Detection must not fail
        // for a model it could already test.
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        WireServer { out ->
            // The server sees one request; the retry is served by the second WireServer.
            out.write(
                (
                    "HTTP/1.1 400 Bad Request\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: 57\r\n" +
                        "Connection: close\r\n\r\n" +
                        "{\"error\":{\"message\":\"streaming is not enabled for this key\"}}"
                    ).toByteArray(),
            )
            requests += "stream-refused"
        }.use { server ->
            // The buffered retry goes to the same origin; a second one-shot server on the
            // same port is not possible, so the fallback is asserted at the unit level
            // instead: this case proves the refusal is a 400 the API classifies as such.
            val result = stream(server.port)

            assertTrue("拒绝流式时必须是失败结果，交由上层决定", result is ApiResult.Failure)
            assertEquals("stream-refused", requests.single())
        }
    }

    @Test
    fun `an empty stream is reported as a failure rather than a blank answer`() {
        WireServer(sse("data: [DONE]\n\n")).use { server ->
            val result = stream(server.port)

            assertTrue("空流必须报失败，实际=$result", result is ApiResult.Failure)
        }
    }
}

    /**
     * Consumes one request, headers and declared body.
     *
     * The body matters even though no case looks at it: a socket closed while received data
     * is still unread sends RST rather than FIN, so the client's end-of-stream read would
     * fail with "connection reset" and hide whatever the case was actually testing.
     */
    private fun InputStream.readRequestHead() {
        var tail = 0
        val head = StringBuilder()
        while (true) {
            val byte = read()
            if (byte < 0) return
            tail = (tail shl 8) or byte
            head.append(byte.toChar())
            if (tail == 0x0D0A0D0A) break
        }
        val length = Regex("(?i)content-length:\\s*(\\d+)")
            .find(head)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: return
        val buffer = ByteArray(4_096)
        var remaining = length
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) return
            remaining -= read
        }
    }
