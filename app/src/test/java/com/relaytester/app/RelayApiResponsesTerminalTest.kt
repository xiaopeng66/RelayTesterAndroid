package com.relaytester.app

import com.relaytester.app.core.model.ApiResult
import com.relaytester.app.core.model.ModelTestResult
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestSettings
import com.relaytester.app.core.model.TestStatus
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R01：Responses 终态判定的回归。
 *
 * 三个网络入口（普通测试、非流式 completion、流式 completion）加流式 400 后的 buffered
 * 回退，共享同一条规则：HTTP 200 与「已有文本」都不是结论，结论只来自协议自己的终态
 * ——buffered 的 `status` 字段、流式的 `response.completed/failed/incomplete` 事件。
 * 缺失终态（无 status、EOF、仅有 [DONE]）一律不当作完成；Chat 协议的 EOF 兼容性由
 * RelayApiStreamingTest 继续锁定，不受本文件影响。
 */
class RelayApiResponsesTerminalTest {
    private val api = RelayApi()

    /**
     * A one-shot-per-connection HTTP server that hands each accepted connection to the
     * next [responders] entry. The streaming fallback paths reuse the origin, so the
     * 400-then-buffered cases need two responses on the same port.
     */
    private class WireServer(vararg responders: (OutputStream) -> Unit) : AutoCloseable {
        private val socket = ServerSocket(0, responders.size + 1, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort

        private val thread = Thread {
            try {
                for (responder in responders) {
                    val client = socket.accept() ?: break
                    client.use {
                        it.getInputStream().readRequestHead()
                        responder(it.getOutputStream())
                        it.getOutputStream().flush()
                    }
                }
            } catch (_: IOException) {
                // The last connection may be torn down by close(); nothing to report.
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
        id = "sup-responses",
        name = "local",
        baseUrl = "http://127.0.0.1:$port/v1",
        protocol = RelayProtocol.RESPONSES,
        apiKeySecretId = "secret-1",
        models = listOf("m"),
        testSettings = TestSettings(),
    )

    /** A Responses JSON body; [status] omitted means the field is absent entirely. */
    private fun responsesBody(
        status: String?,
        text: String?,
        withOutput: Boolean = true,
        withError: Boolean = false,
    ): String {
        val body = JSONObject()
        if (status != null) body.put("status", status)
        if (withOutput) {
            body.put(
                "output",
                JSONArray().put(
                    JSONObject()
                        .put("type", "message")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject().put("type", "output_text").put("text", text ?: ""),
                            ),
                        ),
                ),
            )
        }
        if (withError) body.put("error", JSONObject().put("message", "mock failure"))
        return body.toString()
    }

    private fun jsonReply(body: String, code: Int = 200, reason: String = "OK"): (OutputStream) -> Unit =
        { out ->
            val bytes = body.toByteArray()
            out.write(
                (
                    "HTTP/1.1 $code $reason\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )
            out.write(bytes)
        }

    private fun responsesSse(vararg events: String): (OutputStream) -> Unit = { out ->
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        events.forEach { out.write(it.toByteArray()) }
    }

    private fun responsesDelta(text: String): String =
        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"$text\"}\n\n"

    private val completedEvent = "data: {\"type\":\"response.completed\",\"response\":{}}\n\n"
    private val failedEvent =
        "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"x\",\"message\":\"mock failure\"}}}\n\n"
    private val incompleteEvent = "data: {\"type\":\"response.incomplete\",\"response\":{}}\n\n"
    private val doneEvent = "data: [DONE]\n\n"

    private fun runPlainTest(port: Int): ModelTestResult = runBlocking {
        api.test(
            profile = supplier(port),
            apiKey = "k",
            model = "m",
            prompt = "p",
            maxTokens = 100,
            timeoutSeconds = 30,
        )
    }

    private fun complete(port: Int): ApiResult<String> = runBlocking {
        api.completeText(
            profile = supplier(port),
            apiKey = "k",
            model = "m",
            prompt = "p",
            maxTokens = 100,
            timeoutSeconds = 30,
        )
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

    // ---- 普通测试（连接/协议测试）：failed / incomplete 必须失败，completed 照旧成功 ----

    @Test
    fun `a failed status is not masked by non-empty output in the plain test`() {
        WireServer(jsonReply(responsesBody("failed", "1 2 3", withError = true))).use { server ->
            val result = runPlainTest(server.port)

            assertEquals("failed 状态不得计为可用", TestStatus.FAILED, result.status)
            assertTrue(
                "失败理由要来自响应体：${result.error?.message}",
                result.error?.message?.contains("mock failure") == true,
            )
        }
    }

    @Test
    fun `an incomplete status is not a usable answer in the plain test`() {
        WireServer(jsonReply(responsesBody("incomplete", "1 2 3"))).use { server ->
            val result = runPlainTest(server.port)

            assertEquals(TestStatus.FAILED, result.status)
            assertTrue(
                "失败理由要点明未完成：${result.error?.message}",
                result.error?.message?.contains("incomplete") == true,
            )
        }
    }

    @Test
    fun `a completed status still passes the plain test`() {
        WireServer(jsonReply(responsesBody("completed", "7 8"))).use { server ->
            val result = runPlainTest(server.port)

            assertEquals(TestStatus.SUCCESS, result.status)
            assertEquals("completed", result.finishReason)
        }
    }

    @Test
    fun `a body without a status keeps the plain test's connection-level verdict`() {
        // 连接测试与 completion 的成功条件刻意不同：无 status 但有 output 证明端点会讲
        // Responses 且能出结果，这里照旧可用；completion 路径对同一输入按 UNCONFIRMED
        // 拒绝（见下方用例）。这条用例把差异钉住，防止后续被当成不一致"修掉"。
        WireServer(jsonReply(responsesBody(status = null, text = "1 2 3"))).use { server ->
            val result = runPlainTest(server.port)

            assertEquals(TestStatus.SUCCESS, result.status)
        }
    }

    // ---- 非流式 completion（指纹的 buffered 路径与回退共用）----

    @Test
    fun `completeText refuses a failed Responses result even when text came with it`() {
        WireServer(jsonReply(responsesBody("failed", "1 2 3", withError = true))).use { server ->
            val result = complete(server.port)

            assertTrue("expected Failure, got $result", result is ApiResult.Failure)
            assertTrue(
                "失败理由要来自响应体：${(result as ApiResult.Failure).error.message}",
                result.error.message.contains("mock failure"),
            )
        }
    }

    @Test
    fun `completeText refuses an incomplete Responses result`() {
        WireServer(jsonReply(responsesBody("incomplete", "1 2 3"))).use { server ->
            val result = complete(server.port)

            assertTrue("expected Failure, got $result", result is ApiResult.Failure)
            assertTrue(
                (result as ApiResult.Failure).error.message.contains("incomplete"),
            )
        }
    }

    @Test
    fun `completeText refuses a Responses result that never declared a status`() {
        // 修复文档的默认策略：缺失状态不标记为协议已完成；若需兼容必须单独表达状态，
        // 不得与正式完成混用。空 output 与非空 output 在这里都一样被拒。
        WireServer(jsonReply(responsesBody(status = null, text = "1 2 3"))).use { server ->
            val result = complete(server.port)

            assertTrue("expected Failure, got $result", result is ApiResult.Failure)
            assertTrue(
                (result as ApiResult.Failure).error.message.contains("完成状态"),
            )
        }
    }

    @Test
    fun `completeText succeeds on a completed Responses body`() {
        WireServer(jsonReply(responsesBody("completed", "7 8"))).use { server ->
            val result = complete(server.port)

            assertEquals("7 8", (result as ApiResult.Success).value)
        }
    }

    // ---- 流式 completion ----

    @Test
    fun `a Responses stream cut off after deltas is not a complete answer`() {
        // 模拟器里 mock-eof 复现的形态：delta 之后直接 EOF。
        WireServer(responsesSse(responsesDelta("1 2 "), responsesDelta("3 4"))).use { server ->
            val result = stream(server.port)

            assertTrue("EOF 不得当作完成：$result", result is ApiResult.Failure)
            assertTrue(
                (result as ApiResult.Failure).error.message.contains("完成事件"),
            )
        }
    }

    @Test
    fun `a Responses stream that ends with DONE alone is not confirmed complete`() {
        WireServer(
            responsesSse(responsesDelta("1 2 3"), doneEvent),
        ).use { server ->
            val result = stream(server.port)

            assertTrue("只有 [DONE] 不得当作完成：$result", result is ApiResult.Failure)
            assertTrue(
                (result as ApiResult.Failure).error.message.contains("完成事件"),
            )
        }
    }

    @Test
    fun `a response_failed event is not masked by text already received`() {
        WireServer(responsesSse(responsesDelta("1 2 "), failedEvent, doneEvent)).use { server ->
            val result = stream(server.port)

            assertTrue("expected Failure, got $result", result is ApiResult.Failure)
            val message = (result as ApiResult.Failure).error.message
            assertTrue("失败要点明事件：$message", message.contains("response.failed"))
            assertTrue("失败要带上协议给出的原因：$message", message.contains("mock failure"))
        }
    }

    @Test
    fun `a credential quoted back in a response_failed event is redacted`() {
        // A relay that rejects a key routinely quotes it back in the failure message, and
        // this path put that text straight into the user-visible error — the buffered path
        // redacted it, the streaming one did not. The assertion is on the *absence* of the
        // credential, which is the property that matters: the message may say anything else.
        val secret = "sk-syntheticStreamingKey20261008"
        val leaking = "data: " + JSONObject()
            .put("type", "response.failed")
            .put(
                "response",
                JSONObject().put(
                    "error",
                    JSONObject().put("message", "Bearer $secret rejected by upstream"),
                ),
            )
            .toString() + "\n\n"
        WireServer(responsesSse(leaking, doneEvent)).use { server ->
            val result = stream(server.port)

            assertTrue("expected Failure, got $result", result is ApiResult.Failure)
            val message = (result as ApiResult.Failure).error.message
            assertFalse("凭据不得出现在错误文案里：$message", message.contains(secret))
            assertTrue("失败原因仍要可读：$message", message.contains("response.failed"))
        }
    }

    @Test
    fun `an over-long failure reason is bounded like every other error message`() {
        // The other half of the same rule: every other error path takes MAX_ERROR_CHARS, and
        // an upstream that returns a wall of text here must not be the one exception.
        val wall = "x".repeat(5_000)
        val noisy = "data: " + JSONObject()
            .put("type", "response.failed")
            .put(
                "response",
                JSONObject().put("error", JSONObject().put("message", wall)),
            )
            .toString() + "\n\n"
        WireServer(responsesSse(noisy, doneEvent)).use { server ->
            val result = stream(server.port)

            assertTrue("expected Failure, got $result", result is ApiResult.Failure)
            val message = (result as ApiResult.Failure).error.message
            assertTrue("失败文案必须被截断：实际 ${message.length} 字", message.length <= 400)
        }
    }

    @Test
    fun `a response_incomplete event ends the answer as incomplete`() {
        WireServer(responsesSse(responsesDelta("1 2 "), incompleteEvent)).use { server ->
            val result = stream(server.port)

            assertTrue("expected Failure, got $result", result is ApiResult.Failure)
            val message = (result as ApiResult.Failure).error.message
            assertTrue("失败要点明未完成：$message", message.contains("incomplete"))
        }
    }

    @Test
    fun `a Responses stream with response_completed succeeds`() {
        WireServer(
            responsesSse(responsesDelta("1 2 "), responsesDelta("3 4"), completedEvent, doneEvent),
        ).use { server ->
            val result = stream(server.port)

            assertEquals("1 2 3 4", (result as ApiResult.Success).value)
        }
    }

    @Test
    fun `an empty Responses stream with completed still reports missing text`() {
        // 终态规则与文本规则各自独立：协议说完成了、但一个字都没给，仍按无可用文本失败。
        WireServer(responsesSse(completedEvent, doneEvent)).use { server ->
            val result = stream(server.port)

            assertTrue("expected Failure, got $result", result is ApiResult.Failure)
            assertTrue(
                (result as ApiResult.Failure).error.message.contains("没有可用的文本内容"),
            )
        }
    }

    @Test
    fun `progress still reports while a Responses stream arrives`() {
        val seen = mutableListOf<String>()
        WireServer(
            responsesSse(responsesDelta("1 2 "), responsesDelta("3 4"), completedEvent),
        ).use { server ->
            val result = stream(server.port) { seen += it }

            assertTrue("expected Success, got $result", result is ApiResult.Success)
            assertEquals(listOf("1 2 ", "1 2 3 4"), seen)
        }
    }

    // ---- 流式 400 后的 buffered 回退 ----

    @Test
    fun `streaming refusal falls back to buffered and keeps the failed status`() {
        WireServer(
            jsonReply(
                "{\"error\":{\"message\":\"streaming is not enabled\"}}",
                code = 400,
                reason = "Bad Request",
            ),
            jsonReply(responsesBody("failed", "1 2 3", withError = true)),
        ).use { server ->
            val result = stream(server.port)

            assertTrue("回退后的失败状态不得被掩盖：$result", result is ApiResult.Failure)
            assertTrue(
                (result as ApiResult.Failure).error.message.contains("mock failure"),
            )
        }
    }

    @Test
    fun `streaming refusal falls back to buffered and succeeds on completed`() {
        WireServer(
            jsonReply(
                "{\"error\":{\"message\":\"streaming is not enabled\"}}",
                code = 400,
                reason = "Bad Request",
            ),
            jsonReply(responsesBody("completed", "7 8")),
        ).use { server ->
            val result = stream(server.port)

            assertEquals("7 8", (result as ApiResult.Success).value)
        }
    }

    // ---- 流式请求得到非 SSE 的 JSON 回复（上游无视 stream 标志）----

    @Test
    fun `a buffered Responses reply to a streaming request keeps the status rule`() {
        WireServer(jsonReply(responsesBody("failed", "1 2 3", withError = true))).use { server ->
            val result = stream(server.port)

            assertTrue("expected Failure, got $result", result is ApiResult.Failure)
            assertTrue(
                (result as ApiResult.Failure).error.message.contains("mock failure"),
            )
        }
    }

    @Test
    fun `a buffered completed Responses reply to a streaming request succeeds`() {
        WireServer(jsonReply(responsesBody("completed", "7 8"))).use { server ->
            val result = stream(server.port)

            assertEquals("7 8", (result as ApiResult.Success).value)
        }
    }
}

/**
 * Consumes one request, headers and declared body.
 *
 * Same reason as in RelayApiStreamingTest: a socket closed while received data is still
 * unread sends RST rather than FIN, so the client's read would fail with "connection
 * reset" and hide the case's actual outcome.
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
