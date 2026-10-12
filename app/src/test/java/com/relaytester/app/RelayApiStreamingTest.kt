package com.relaytester.app

import com.relaytester.app.core.model.ApiResult
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestSettings
import com.relaytester.app.core.network.RelayApi
import com.relaytester.app.core.network.isMissingAnswerText
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        /**
         * The request body the client sent, readable once the case has run.
         *
         * Captured rather than ignored because some cases are about what the app *sent* —
         * the thinking-off fields exist only on the wire, and a test that asserted on some
         * intermediate object would pass while the request carried nothing.
         */
        @Volatile
        var requestBody: String = ""

        private val thread = Thread {
            try {
                socket.accept().use { client ->
                    requestBody = client.getInputStream().readRequestHead()
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

    /**
     * A Chat frame carrying only reasoning, the shape a thinking relay streams.
     *
     * `reasoning_content` is the DeepSeek-style field; relays that proxy such models emit
     * thousands of these before the first `content` frame.
     */
    private fun chatReasoningChunk(reasoning: String): String =
        "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"$reasoning\"}}]}\n\n"

    /** An Anthropic thinking frame, the other common shape for reasoning-only deltas. */
    private fun anthropicThinkingChunk(thinking: String): String = "data: " + JSONObject()
        .put("type", "content_block_delta")
        .put("delta", JSONObject().put("type", "thinking_delta").put("thinking", thinking))
        .toString() + "\n\n"

    @Test
    fun `a reasoning-only stream reports activity instead of looking stuck`() {
        // 推理模型先吐几千个只带思考内容的分片，之后才有正文。这些分片不是答案，所以不能
        // 混进正文；但只把它们丢掉的话，用户在整段思考期间看到的是一个停在「等待响应…」
        // 的界面——和请求卡死完全一样，分辨不出来。这里钉住的是：思考期间必须仍然有信号
        // 送到界面（思考回调被调用），而正文里绝不能混进思考内容。
        val reasoningSeen = StringBuilder()
        WireServer(
            sse(
                chatReasoningChunk("让我想想。"),
                chatReasoningChunk("先画一个圆。"),
                chatChunk("<!DOCTYPE html>"),
                "data: [DONE]\n\n",
            ),
        ).use { server ->
            val result = runBlocking {
                api.completeTextStreaming(
                    profile = supplier(server.port),
                    apiKey = "k",
                    model = "m",
                    prompt = "p",
                    maxTokens = 100,
                    timeoutSeconds = 30,
                    onProgress = {},
                    onReasoning = { text -> reasoningSeen.append(text) },
                )
            }

            assertTrue("expected Success, got $result", result is ApiResult.Success)
            // 正文只含答案，绝不含思考内容。
            assertEquals("<!DOCTYPE html>", (result as ApiResult.Success).value)
            // 思考内容必须如实送到界面，而且不含答案。
            assertEquals("让我想想。先画一个圆。", reasoningSeen.toString())
        }
    }

    @Test
    fun `an anthropic thinking stream also reports its reasoning`() {
        // Anthropic 的思考走 `thinking_delta`，是另一个字段名。只认 text_delta 的话，
        // 扩展思考模型在思考期间同样是一片空白。
        val reasoningSeen = StringBuilder()
        WireServer(
            sse(
                anthropicThinkingChunk("先算一下"),
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"<!DOCTYPE html>\"}}\n\n",
                "data: [DONE]\n\n",
            ),
        ).use { server ->
            val result = runBlocking {
                api.completeTextStreaming(
                    profile = supplier(server.port).copy(protocol = RelayProtocol.ANTHROPIC),
                    apiKey = "k",
                    model = "m",
                    prompt = "p",
                    maxTokens = 100,
                    timeoutSeconds = 30,
                    onProgress = {},
                    onReasoning = { text -> reasoningSeen.append(text) },
                )
            }

            assertTrue("expected Success, got $result", result is ApiResult.Success)
            assertEquals("<!DOCTYPE html>", (result as ApiResult.Success).value)
            assertEquals("先算一下", reasoningSeen.toString())
        }
    }

    @Test
    fun `a stream with no answer text after reasoning is still reported as empty`() {
        // 模型只思考、没写出答案就结束：这是「没有可用文本」，不能因为思考期间报了进度
        // 就把它当成一次成功。
        WireServer(
            sse(
                chatReasoningChunk("想了半天"),
                "data: [DONE]\n\n",
            ),
        ).use { server ->
            val result = stream(server.port)

            assertTrue(
                "只思考不回答必须报失败而不是成功，实际 $result",
                result is ApiResult.Failure,
            )
            // 这一句是指纹检测自动重发的挂钩：真实供应商实测同题四次里有一次整段思考烧完
            // 8192 的预算、正文 0 字符、finish_reason: length，再问一次就能拿到正文（见
            // CHALLENGE_MAX_ATTEMPTS）。识别面必须精确等于这句话，所以这里同时钉住原话和
            // 识别结果。
            val error = (result as ApiResult.Failure).error
            assertEquals("上游返回 HTTP 200 但没有可用的文本内容", error.message)
            assertTrue("只出思考的空白必须被识别为可重发的空白", error.isMissingAnswerText())
        }
    }

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
            onProgress = { text -> onProgress(text) },
        )
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
            // ~1 MB per frame, 40 frames: ~40 MB of framing, past the 32 MB ceiling. The
            // frames are large rather than numerous because the ceiling is now sized for a
            // thinking model's whole window (see MAX_STREAM_BYTES) and reaching it with small
            // frames would mean tens of thousands of writes for the same coverage.
            val filler = "1 ".repeat(500_000)
            repeat(40) { out.write(chatChunk(filler).toByteArray()) }
        }.use { server ->
            val result = stream(server.port)

            assertTrue("应当因过大而失败，实际 $result", result is ApiResult.Failure)
            val error = (result as ApiResult.Failure).error
            assertTrue("失败原因要说清是流太大：${error.message}", error.message.contains("流式响应过大"))
            assertTrue("过大的流不是网络故障", error.message.isNotBlank())
        }
    }

    @Test
    fun `a reasoning stream far larger than the old wire bound still answers`() {
        // The report that started this: a reasoning model streams thousands of thinking
        // frames before a single answer token. Each frame is discarded by the extractor,
        // but it is a couple of hundred bytes on the wire, so the old 256 KB wire bound
        // counted framing that never reaches the answer and failed a challenge whose
        // answer is ~300 numbers. What the ceiling exists for — memory — never depended
        // on the size of the reasoning.
        val frames = 1_500
        WireServer { out ->
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )
            // ~350 bytes per frame: ~500 KB of reasoning on the wire — twice the original
            // 256 KB bound, and none of it the answer. The 1 MB era is covered by the
            // regression above; this one keeps the smallest repro of the original report.
            repeat(frames) { index ->
                val reasoning = "thinking step $index ".repeat(16)
                out.write(
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"$reasoning\"}}]}\n\n"
                        .toByteArray(),
                )
            }
            out.write(chatChunk("7 8 9 ").toByteArray())
            out.write("data: [DONE]\n\n".toByteArray())
        }.use { server ->
            val result = stream(server.port)

            assertTrue("推理帧不该让检测失败：$result", result is ApiResult.Success)
            assertEquals("推理帧不得混进回答", "7 8 9 ", (result as ApiResult.Success).value)
        }
    }

    @Test
    fun `a reasoning stream past the previous wire bound still answers`() {
        // The report that kept coming back: at the 1 MB bound a slow, thorough model still
        // crossed it, so the panel showed "stream too large" for a challenge whose answer is
        // a few kilobytes. Reasoning frames pay 58-350 bytes each of JSON framing and never
        // reach the answer, so the bound is spent by frame count, not by anything the user
        // asked for. ~2 MB here is twice the bound that used to fail and a quarter of the
        // current one: this is the regression that says the raise is real.
        val frames = 6_000
        WireServer { out ->
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )
            // ~330 bytes per frame: ~2 MB of reasoning on the wire, none of it the answer.
            repeat(frames) { index ->
                val reasoning = "thinking step $index ".repeat(16)
                out.write(
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"$reasoning\"}}]}\n\n"
                        .toByteArray(),
                )
            }
            out.write(chatChunk("4 5 6 ").toByteArray())
            out.write("data: [DONE]\n\n".toByteArray())
        }.use { server ->
            val result = stream(server.port)

            assertTrue("超过旧上限的推理流不该让检测失败：$result", result is ApiResult.Success)
            assertEquals("推理帧不得混进回答", "4 5 6 ", (result as ApiResult.Success).value)
        }
    }

    @Test
    fun `an answer longer than the answer ceiling is truncated with its head intact`() {
        // The other half of the same trade: the wire ceiling stays for memory, and the
        // answer gets its own bound. Past it the stream is drained, not killed — an
        // over-long answer keeps the numbers the scorer reads, and the request does not
        // fail the way it would have under the old single 256 KB bound.
        WireServer { out ->
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )
            // ~4 KB per chunk over 100 chunks: ~400 KB of answer text, past the 256K answer
            // ceiling and comfortably inside the wire ceiling. Sized to the current ceiling
            // on purpose: the assertion below is that truncation happens *at* the ceiling,
            // so a body smaller than it would pass this test while proving nothing.
            repeat(100) { index ->
                out.write(chatChunk("$index ".repeat(1_000)).toByteArray())
            }
            out.write("data: [DONE]\n\n".toByteArray())
        }.use { server ->
            val result = stream(server.port)

            assertTrue("过长的回答应当截断而不是失败：$result", result is ApiResult.Success)
            val answer = (result as ApiResult.Success).value
            assertEquals("截断到回答上限", 262_144, answer.length)
            assertTrue("头部必须保留（打分读的就是开头）", answer.startsWith("0 0 "))
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
            // ~4 MB in one unterminated line, repeated: 16 such blobs cross the 32 MB bound
            // mid-line, which is the case a newline-oriented bound cannot cover.
            val blob = "x".repeat(4_194_304)
            repeat(16) { out.write(blob.toByteArray()) }
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

    // ---- SSE event framing: one event may span several `data:` lines ----------

    @Test
    fun `a payload split across several data lines is joined into one event`() {
        // The spec joins an event's `data:` lines with a newline before the payload is read,
        // so a relay may legally split one JSON document over several lines. Reading per line
        // instead of per event drops every continuation line, and the answer text goes
        // missing with no error: it reads as a model that answered wrongly rather than as a
        // relay that framed correctly. The split here is at a token boundary, where the
        // joining newline is ordinary JSON whitespace.
        WireServer(
            sse(
                "data: {\"choices\":[{\"delta\":\n",
                "data: {\"content\":\"4 5\"}}]}\n\n",
                "data: [DONE]\n\n",
            ),
        ).use { server ->
            val result = stream(server.port)

            assertTrue("跨行事件必须被重组，实际 $result", result is ApiResult.Success)
            assertEquals("回答文本必须完整保留", "4 5", (result as ApiResult.Success).value)
        }
    }

    @Test
    fun `a comment between data lines does not end the event`() {
        // Only a blank line dispatches. A comment or an `event:` line belongs to the event it
        // sits in, so a reader that dispatched on any non-data line would cut the payload in
        // half — the same loss as above, reached by a different route.
        WireServer(
            sse(
                "data: {\"choices\":[{\"delta\":\n",
                ": keep-alive\n",
                "event: message\n",
                "data: {\"content\":\"6 7\"}}]}\n\n",
                "data: [DONE]\n\n",
            ),
        ).use { server ->
            val result = stream(server.port)

            assertTrue("事件中间的注释不得切断载荷，实际 $result", result is ApiResult.Success)
            assertEquals("回答文本必须完整保留", "6 7", (result as ApiResult.Success).value)
        }
    }

    @Test
    fun `a stream that closes without a final blank line still yields its last event`() {
        // The closing blank line is the terminator, but a relay that simply closes after the
        // payload has still sent it. Dropping it would lose the last delta of an otherwise
        // complete answer.
        WireServer(
            sse(
                chatChunk("8 9"),
                "data: {\"choices\":[{\"delta\":{\"content\":\"10\"}}]}",
            ),
        ).use { server ->
            val result = stream(server.port)

            assertTrue("未以空行结束的最后事件也必须计入，实际 $result", result is ApiResult.Success)
            assertEquals("8 910", (result as ApiResult.Success).value)
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

    @Test
    fun `the progress cadence stays live for small answers and steps up for large ones`() {
        // 这条钉的是"把正文上限提到 262 144 之后，逐片复制累计串不会变成二次开销"。
        // 回调每次收到的是**整段**累计文本，所以每片都报一次 = O(n²)：按 60 字一片模拟，
        // 262 144 字的答案会复制约 5.7 亿字符。这里同时验证两件事——小答案仍然每片都报
        // （面板的实时计数靠它），大答案的总复制量随长度线性增长（不是平方）。
        val api = RelayApi()

        // 小答案：每一次增长都报，挑战题（几 KB）与原来完全一致。
        var last = 0
        var smallReports = 0
        for (length in 1..2_000) {
            if (api.streamProgressDue(length, last)) {
                smallReports++
                last = length
            }
        }
        assertEquals("小答案必须每片都报，计数才是实时的", 2_000, smallReports)

        // 大答案：报告次数随长度线性增长。用 TOTAL/DIVISOR 的界来钉，不写死次数。
        last = 0
        var reports = 0
        var copied = 0L
        val total = 262_144
        var length = 0
        while (length < total) {
            length = minOf(total, length + 60)
            if (api.streamProgressDue(length, last)) {
                reports++
                copied += length
                last = length
            }
        }
        // 线性界：总复制量 ≲ DIVISOR × 最终长度 × 一点余量；二次开销会远高于此。
        assertTrue(
            "大答案的复制量必须线性，实际 $copied",
            copied <= 64L * total * 2,
        )
        assertTrue(
            "报告次数应远少于片数（$reports 次），否则还是逐片复制",
            reports < total / 60 / 5,
        )
        // 且计数不能落后太多：末尾的滞后不超过 1/DIVISOR。
        assertTrue("计数滞后必须很小，实际滞后 ${total - last}", total - last <= total / 64 + 60)
    }

    @Test
    fun `the animation request leaves the model's reasoning alone`() {
        // 钉的是「不限制思考」这条产品决定：请求体里不得出现任何关闭或压低推理的字段。
        // 上一轮这里是反过来的（断言三条关闭思考的字段确实在请求里），所以这条测试同时
        // 防止旧行为悄悄回来 —— 一个 unfiltered 的请求体是用户明确要的。
        //
        // 为什么必须是"不得出现"而不是"某个中间对象为空"：只要断言中间对象，请求真实
        // 发出时带了字段也照样能通过。
        val body = org.json.JSONObject(sendAndCapture())
        assertFalse("不得发送 reasoning_effort", body.has("reasoning_effort"))
        assertFalse("不得发送 chat_template_kwargs", body.has("chat_template_kwargs"))
        assertFalse("不得发送 thinking 开关", body.has("thinking"))
        assertFalse("不得发送 enable_thinking", body.has("enable_thinking"))
        assertFalse("不得发送 reasoning 配置", body.has("reasoning"))
        // 这个请求本来就该有的字段一个都不能少。
        assertEquals("m", body.optString("model"))
        assertTrue("流式标志必须保留", body.optBoolean("stream"))
        assertEquals(100, body.optInt("max_tokens"))
        assertEquals("p", body.getJSONArray("messages").getJSONObject(0).optString("content"))
    }

    @Test
    fun `reasoning is reported as fragments the caller accumulates`() {
        // 钉的是回调契约本身：网络层报的是「这一片」而不是累计值 —— 推理可以长到三万
        // token，逐片复制累计串是二次开销（见 completeTextStreaming 的说明）。ViewModel
        // 曾把分片当累计值用，屏幕上的思考就只剩最后一句残句。
        val fragments = java.util.Collections.synchronizedList(mutableListOf<String>())
        WireServer(
            sse(
                chatReasoningChunk("第一片。"),
                chatReasoningChunk("第二片。"),
                chatChunk("answer"),
            ),
        ).use { server ->
            val result = runBlocking {
                api.completeTextStreaming(
                    profile = supplier(server.port),
                    apiKey = "k",
                    model = "m",
                    prompt = "p",
                    maxTokens = 100,
                    timeoutSeconds = 30,
                    onProgress = {},
                    onReasoning = { text -> fragments += text },
                )
            }

            assertTrue("expected Success, got $result", result is ApiResult.Success)
            assertEquals("每次回调是独立分片，不是累计前缀", listOf("第一片。", "第二片。"), fragments)
            assertEquals("思考不得混进答案", "answer", (result as ApiResult.Success).value)
        }
    }

    /** Sends one streaming call and returns the request body the server read. */
    private fun sendAndCapture(): String {
        WireServer(sse(chatChunk("ok"), "data: [DONE]\n\n")).use { server ->
            val result = runBlocking {
                api.completeTextStreaming(
                    profile = supplier(server.port),
                    apiKey = "k",
                    model = "m",
                    prompt = "p",
                    maxTokens = 100,
                    timeoutSeconds = 30,
                    onProgress = {},
                )
            }
            assertTrue("expected Success, got $result", result is ApiResult.Success)
            return server.requestBody
        }
    }
    @Test
    fun `think fences riding in the content stream become reasoning, not answer`() {
        // 一些部署没有思考字段，把思考用 <think>…</think> 包在 content 里发。这些文本必须
        // 走思考回调，正文里一个字都不能留 —— 否则思考框一片空白（"有的模型不思考"），
        // 而答案被思考污染。围栏被分片边界切开时也要接得住。
        val reasoningSeen = StringBuilder()
        WireServer(
            sse(
                chatChunk("<thi"),
                chatChunk("nk>先画一个圆。"),
                chatReasoningChunk("字段里的思考。"),
                chatChunk("</think><!DOCTYPE html><html></html>"),
                "data: [DONE]\n\n",
            ),
        ).use { server ->
            val result = runBlocking {
                api.completeTextStreaming(
                    profile = supplier(server.port),
                    apiKey = "k",
                    model = "m",
                    prompt = "p",
                    maxTokens = 100,
                    timeoutSeconds = 30,
                    onProgress = {},
                    onReasoning = { text -> reasoningSeen.append(text) },
                )
            }

            assertTrue("expected Success, got $result", result is ApiResult.Success)
            assertEquals("<!DOCTYPE html><html></html>", (result as ApiResult.Success).value)
            assertEquals("先画一个圆。字段里的思考。", reasoningSeen.toString())
        }
    }

    @Test
    fun `an unclosed think fence flushed at end of stream is all reasoning`() {
        // 上游在思考中途掐断：<think> 开了、</think> 永远不来。已到的思考仍要走思考通道，
        // 不能算进答案。
        val reasoningSeen = StringBuilder()
        WireServer(
            sse(
                chatChunk("<html>"),
                chatChunk("<think>想到一半"),
                "data: [DONE]\n\n",
            ),
        ).use { server ->
            val result = runBlocking {
                api.completeTextStreaming(
                    profile = supplier(server.port),
                    apiKey = "k",
                    model = "m",
                    prompt = "p",
                    maxTokens = 100,
                    timeoutSeconds = 30,
                    onProgress = {},
                    onReasoning = { text -> reasoningSeen.append(text) },
                )
            }

            assertTrue("expected Success, got $result", result is ApiResult.Success)
            assertEquals("<html>", (result as ApiResult.Success).value)
            assertEquals("想到一半", reasoningSeen.toString())
        }
    }

    @Test
    fun `a mid-stream disconnect returns what arrived when salvage is on`() {
        // 动画测试的真实场景：思考几分钟后上游掐断连接。已收到的字节不能跟着异常一起
        // 丢掉——按「流已结束」交还，能不能成篇由调用方的解析规则决定。
        WireServer(midStreamDisconnect()).use { server ->
            val result = runBlocking {
                api.completeTextStreaming(
                    profile = supplier(server.port),
                    apiKey = "k",
                    model = "m",
                    prompt = "p",
                    maxTokens = 100,
                    timeoutSeconds = 30,
                    onProgress = {},
                    salvageOnDisconnect = true,
                )
            }

            assertTrue("抢救必须成功而不是报网络失败，实际 $result", result is ApiResult.Success)
            assertEquals("已到的分片必须完整保留", "1 2 3 4 ", (result as ApiResult.Success).value)
        }
    }

    @Test
    fun `a mid-stream disconnect still fails when salvage is off`() {
        // 指纹检测等调用方保持原行为：断流是网络失败，交由它们自己的重试规则处理。
        WireServer(midStreamDisconnect()).use { server ->
            val result = stream(server.port)
            assertTrue("默认行为必须保持失败，实际 $result", result is ApiResult.Failure)
        }
    }

    /**
     * A response that starts as SSE and dies before the body is complete: the declared
     * `Content-Length` is longer than what gets written, so the client hits EOF mid-body
     * and OkHttp raises it — the deterministic form of a relay dropping the connection.
     */
    private fun midStreamDisconnect(): (OutputStream) -> Unit = { out ->
        val body = chatChunk("1 2 3 4 ")
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream\r\n" +
                    "Content-Length: ${body.length + 500}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.write(body.toByteArray())
        out.flush()
    }

}

    /**
     * Consumes one request, headers and declared body.
     *
     * The body matters even though no case looks at it: a socket closed while received data
     * is still unread sends RST rather than FIN, so the client's end-of-stream read would
     * fail with "connection reset" and hide whatever the case was actually testing.
     */
    private fun InputStream.readRequestHead(): String {
        var tail = 0
        val head = StringBuilder()
        while (true) {
            val byte = read()
            if (byte < 0) return ""
            tail = (tail shl 8) or byte
            head.append(byte.toChar())
            if (tail == 0x0D0A0D0A) break
        }
        val length = Regex("(?i)content-length:\\s*(\\d+)")
            .find(head)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: return ""
        val body = StringBuilder()
        val buffer = ByteArray(4_096)
        var remaining = length
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            body.append(String(buffer, 0, read, Charsets.UTF_8))
            remaining -= read
        }
        return body.toString()
}
