package com.relaytester.app.core.network

import com.relaytester.app.core.model.ApiResult
import com.relaytester.app.core.model.ErrorKind
import com.relaytester.app.core.model.ModelTestResult
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestError
import com.relaytester.app.core.model.TestStatus
import com.relaytester.app.core.model.TokenUsage
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject

open class RelayApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .build(),
) {
    open suspend fun fetchModels(
        profile: SupplierProfile,
        apiKey: String,
        timeoutSeconds: Int,
    ): ApiResult<List<String>> {
        apiKeyProblem(apiKey)?.let { return ApiResult.Failure(TestError(ErrorKind.OTHER, it)) }
        val baseUrl = normalizedBaseUrl(profile.baseUrl) ?: return ApiResult.Failure(
            TestError(ErrorKind.OTHER, "Base URL 必须是有效的 HTTP(S) 地址"),
        )
        val url = (baseUrl + "/models").toHttpUrlOrNull() ?: return ApiResult.Failure(
            TestError(ErrorKind.OTHER, "Base URL 无法组成模型地址"),
        )
        val request = requestBuilder(url.toString(), profile.protocol, apiKey)
            .get()
            .build()

        return try {
            val payload = execute(request, timeoutSeconds)
            if (payload.status !in 200..299) {
                ApiResult.Failure(
                    errorForHttp(payload.status, payload.body),
                    httpStatus = payload.status,
                )
            } else {
                val body = JSONObject(payload.body)
                val models = parseModels(body)
                ApiResult.Success(models)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ApiResult.Failure(errorForThrowable(error))
        }
    }

    open suspend fun test(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
    ): ModelTestResult {
        apiKeyProblem(apiKey)?.let { return failed(model, ErrorKind.OTHER, it) }
        val baseUrl = normalizedBaseUrl(profile.baseUrl)
            ?: return failed(model, ErrorKind.OTHER, "Base URL 必须是有效的 HTTP(S) 地址")
        val endpoint = when (profile.protocol) {
            RelayProtocol.CHAT_COMPLETIONS -> "/chat/completions"
            RelayProtocol.RESPONSES -> "/responses"
            RelayProtocol.ANTHROPIC -> "/messages"
        }
        val url = (baseUrl + endpoint).toHttpUrlOrNull()
            ?: return failed(model, ErrorKind.OTHER, "Base URL 无法组成测试地址")

        val request = requestBuilder(url.toString(), profile.protocol, apiKey)
            .post(buildTestPayload(profile.protocol, model, prompt, maxTokens))
            .build()
        val startedAt = System.nanoTime()

        return try {
            val payload = execute(request, timeoutSeconds)
            val latencyMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
            parseTest(payload, profile.protocol, model, latencyMs)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val latencyMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
            failed(model, errorForThrowable(error), latencyMs = latencyMs)
        }
    }

    /**
     * Refuses an API key that cannot legally go into an HTTP header, before OkHttp sees it.
     *
     * `Request.Builder.header` validates its value, and the exception it throws quotes the
     * value — the key — in its message. Building the request happened outside every caller's
     * `try`, so that message escaped to the result row and the exported JSON. The bad
     * characters only ever arrive by accident (a full-width punctuation mark, a tab or a
     * newline picked up while pasting), which is why the check is here and why the message
     * says what to do rather than naming a header.
     */
    internal fun apiKeyProblem(apiKey: String): String? = when {
        apiKey.isEmpty() -> null
        apiKey.any { it != '\t' && it.code !in ASCII_PRINTABLE } ->
            "API Key 里有不能放进请求头的字符（换行、全角标点等非 ASCII 字符），请重新粘贴原始密钥"
        else -> null
    }

    private fun requestBuilder(
        url: String,
        protocol: RelayProtocol,
        apiKey: String,
    ): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")
        .apply {
            when (protocol) {
                RelayProtocol.ANTHROPIC -> {
                    header("x-api-key", apiKey)
                    header("anthropic-version", ANTHROPIC_VERSION)
                }
                RelayProtocol.CHAT_COMPLETIONS,
                RelayProtocol.RESPONSES,
                -> header("Authorization", "Bearer $apiKey")
            }
        }

    private fun buildTestPayload(
        protocol: RelayProtocol,
        model: String,
        prompt: String,
        maxTokens: Int,
    ) = when (protocol) {
        RelayProtocol.RESPONSES -> JSONObject()
            .put("model", model)
            .put("input", prompt)
            .put("max_output_tokens", maxTokens)

        RelayProtocol.ANTHROPIC -> JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("max_tokens", maxTokens)

        RelayProtocol.CHAT_COMPLETIONS -> JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("max_tokens", maxTokens)
            .put("stream", false)
    }.toString().toRequestBody(JSON_MEDIA_TYPE)

    /**
     * Sends one completion and returns the assistant's text.
     *
     * [test] only proves a response carries the protocol's envelope — it never reads
     * the body. Fingerprint detection needs the whole answer, so this extracts the
     * text per protocol instead of just checking that a field is non-empty.
     *
     * [timeoutSeconds] must be generous: a challenge asks for ~300 integers and slow
     * relays routinely need more than a minute.
     */
    open suspend fun completeText(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
    ): ApiResult<String> {
        val prepared = when (val prep = prepareCompletion(profile, apiKey)) {
            is CompletionPrep.Ready -> prep.builder
            is CompletionPrep.Refused -> return ApiResult.Failure(prep.error)
        }
        val request = prepared
            .post(buildCompletionPayload(profile.protocol, model, prompt, maxTokens, stream = false))
            .build()

        return try {
            val payload = execute(request, timeoutSeconds)
            if (payload.status !in 200..299) {
                ApiResult.Failure(
                    errorForHttp(payload.status, payload.body),
                    httpStatus = payload.status,
                )
            } else {
                val body = runCatching { JSONObject(payload.body) }.getOrElse {
                    return ApiResult.Failure(
                        TestError(ErrorKind.INVALID_RESPONSE, "上游返回非 JSON 响应"),
                        httpStatus = payload.status,
                    )
                }
                val text = RelayCompletionText.extract(body, profile.protocol)
                if (text.isBlank()) {
                    ApiResult.Failure(
                        TestError(
                            ErrorKind.INVALID_RESPONSE,
                            protocolHint(body, "上游返回 HTTP 200 但没有可用的文本内容"),
                        ),
                        httpStatus = payload.status,
                    )
                } else {
                    ApiResult.Success(text)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ApiResult.Failure(errorForThrowable(error))
        }
    }

    /**
     * Sends one completion as a stream, reporting how much has arrived while it runs.
     *
     * Fingerprint detection scores a list of integers, and a long list takes a minute or
     * more; a panel that shows nothing until the whole body lands reads as frozen. The
     * callback receives the text accumulated so far, so the caller can count what is
     * usable instead of guessing from a spinner.
     *
     * The stream is an enhancement, never a requirement: an upstream that ignores
     * `stream: true` and answers with one JSON body (or a relay that buffers the whole
     * SSE frame) still yields the same text, because the buffered body is handed to the
     * same extractor. [onProgress] is called from the reading coroutine and must not
     * block.
     */
    open suspend fun completeTextStreaming(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        onProgress: suspend (String) -> Unit,
    ): ApiResult<String> {
        val prepared = when (val prep = prepareCompletion(profile, apiKey)) {
            is CompletionPrep.Ready -> prep.builder
            is CompletionPrep.Refused -> return ApiResult.Failure(prep.error)
        }
        val request = prepared
            .post(buildCompletionPayload(profile.protocol, model, prompt, maxTokens, stream = true))
            .header("Accept", "text/event-stream")
            .build()

        return try {
            // Same dispatcher rule as the buffered path: reading the body blocks the
            // thread, so it must not happen on the caller's.
            withContext(Dispatchers.IO) {
                val totalSeconds = (timeoutSeconds + CLIENT_GRACE_SECONDS).toLong()
                val ready = client.forSameOriginRequests(totalSeconds, totalSeconds, totalSeconds, totalSeconds)
                ready.executeSameOrigin(request).use { response ->
                    if (response.code !in 200..299) {
                        val errorBody = response.body?.readLimitedUtf8().orEmpty()
                        // A relay that does not understand `stream: true` must not cost
                        // the user a detection it used to perform: these three codes are
                        // the "your request is malformed" family, so the buffered call is
                        // asked instead. Codes that mean auth, quota or a missing model
                        // are reported as they are — retrying those would spend a second
                        // request on an error the flag cannot cause.
                        if (response.code in STREAM_REFUSAL_CODES) {
                            return@withContext completeText(
                                profile = profile,
                                apiKey = apiKey,
                                model = model,
                                prompt = prompt,
                                maxTokens = maxTokens,
                                timeoutSeconds = timeoutSeconds,
                            )
                        }
                        return@withContext ApiResult.Failure(
                            errorForHttp(response.code, errorBody),
                            httpStatus = response.code,
                        )
                    }
                    val body = response.body
                        ?: return@withContext ApiResult.Failure(
                            TestError(ErrorKind.INVALID_RESPONSE, "上游返回 HTTP ${response.code} 但没有响应体"),
                            httpStatus = response.code,
                        )
                    val contentType = body.contentType()?.toString().orEmpty()
                    val text = if (contentType.contains("event-stream", ignoreCase = true)) {
                        readEventStream(body, profile.protocol, onProgress)
                    } else {
                        // Not an SSE reply: the upstream ignored the flag. Read it whole and
                        // run it through the same extractor as the buffered path, so a relay
                        // that only supports complete answers still works, just without the
                        // live count.
                        decodeCompletionText(body, profile.protocol)
                    }
                    if (text.isBlank()) {
                        ApiResult.Failure(
                            TestError(
                                ErrorKind.INVALID_RESPONSE,
                                "上游返回 HTTP 200 但没有可用的文本内容",
                            ),
                            httpStatus = response.code,
                        )
                    } else {
                        ApiResult.Success(text)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ApiResult.Failure(errorForThrowable(error))
        }
    }

    /**
     * The endpoint and headers both completion paths send to.
     *
     * Modeled as a result rather than a nullable so each failure keeps the message the
     * buffered path has always shown: the two Base-URL failures read differently in the
     * original and collapsing them would change what an existing configuration is told.
     */
    private sealed interface CompletionPrep {
        class Ready(val builder: Request.Builder) : CompletionPrep

        class Refused(val error: TestError) : CompletionPrep
    }

    private fun prepareCompletion(profile: SupplierProfile, apiKey: String): CompletionPrep {
        apiKeyProblem(apiKey)?.let { return CompletionPrep.Refused(TestError(ErrorKind.OTHER, it)) }
        val baseUrl = normalizedBaseUrl(profile.baseUrl) ?: return CompletionPrep.Refused(
            TestError(ErrorKind.OTHER, "Base URL 必须是有效的 HTTP(S) 地址"),
        )
        val endpoint = when (profile.protocol) {
            RelayProtocol.CHAT_COMPLETIONS -> "/chat/completions"
            RelayProtocol.RESPONSES -> "/responses"
            RelayProtocol.ANTHROPIC -> "/messages"
        }
        val url = (baseUrl + endpoint).toHttpUrlOrNull() ?: return CompletionPrep.Refused(
            TestError(ErrorKind.OTHER, "Base URL 无法组成请求地址"),
        )
        return CompletionPrep.Ready(requestBuilder(url.toString(), profile.protocol, apiKey))
    }

    /**
     * Reads one completion body and returns its text, whether or not it was streamed.
     *
     * The buffered branch is not dead weight: it is what makes the streaming call work
     * against a relay that ignores `stream: true`.
     */
    private fun decodeCompletionText(body: ResponseBody, protocol: RelayProtocol): String {
        val raw = body.readLimitedUtf8()
        return RelayCompletionText.extractFromBody(raw, protocol)
    }

    /**
     * Reads an SSE body, emitting the accumulated text as each delta arrives.
     *
     * Only `data:` payloads carry text; `event:`, `id:` and comment lines are skipped as
     * the format requires. `[DONE]` ends the stream. A malformed frame is skipped rather
     * than failing the request: a relay that emits one unparseable keep-alive must not
     * cost the user a whole challenge.
     *
     * The `startsWith` filter is belt-and-braces rather than the load-bearing check: any
     * line that reaches the parser without a `data:` prefix keeps its own `event:`/`id:`
     * text, which is not JSON and is rejected a few lines later anyway. It stays because
     * it makes the intent explicit at the point of reading, and it saves parsing every
     * heartbeat on a long stream.
     */
    private suspend fun readEventStream(
        body: ResponseBody,
        protocol: RelayProtocol,
        onProgress: suspend (String) -> Unit,
    ): String {
        val accumulated = StringBuilder()
        // The ceiling lives on the bytes, not on the assembled text: a stream is bounded by
        // how much of it is read, and bounding that is what also bounds a peer that sends
        // bytes with no newline at all (which would otherwise be buffered whole while the
        // reader waited for a terminator). The text cannot outgrow the bytes it came from.
        val source = BoundedSource(body.source(), MAX_STREAM_BYTES).buffer()
        var lastReported = 0
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isBlank()) continue
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") break
            val delta = runCatching { RelayCompletionText.extractStreamDelta(payload, protocol) }
                .getOrNull()
                .orEmpty()
            if (delta.isEmpty()) continue
            accumulated.append(delta)
            // Reported per delta, not per byte: a challenge yields a few hundred small
            // chunks, and one callback per chunk is what keeps the count live without
            // flooding the UI state. The cost is quadratic in the number of chunks, which is
            // why the ceiling exists — the cadence is the contract, the ceiling is the bound
            // that keeps the contract affordable.
            if (accumulated.length > lastReported) {
                lastReported = accumulated.length
                onProgress(accumulated.toString())
            }
        }
        // A stream that carried no deltas at all (some relays answer 200 text/event-stream
        // with only a [DONE]) has nothing to fall back to here; the caller's blank check
        // reports it.
        return accumulated.toString()
    }

    private fun buildCompletionPayload(
        protocol: RelayProtocol,
        model: String,
        prompt: String,
        maxTokens: Int,
        stream: Boolean,
    ) = when (protocol) {
        RelayProtocol.RESPONSES -> JSONObject()
            .put("model", model)
            .put("input", prompt)
            .put("max_output_tokens", maxTokens)
            // Responses streams by default and needs the flag to be told otherwise; it
            // is passed explicitly so the two call sites differ only in this value.
            .put("stream", stream)

        RelayProtocol.ANTHROPIC -> JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("max_tokens", maxTokens)
            .put("stream", stream)

        RelayProtocol.CHAT_COMPLETIONS -> JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("max_tokens", maxTokens)
            .put("stream", stream)
    }.toString().toRequestBody(JSON_MEDIA_TYPE)

    private fun parseModels(body: JSONObject): List<String> {
        val values = body.optJSONArray("data") ?: body.optJSONArray("models") ?: JSONArray()
        return buildList {
            for (index in 0 until values.length()) {
                when (val item = values.opt(index)) {
                    is String -> item
                    is JSONObject -> item.optString("id")
                    else -> ""
                }.trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct().sorted()
    }

    private fun parseTest(
        payload: HttpPayload,
        protocol: RelayProtocol,
        model: String,
        latencyMs: Long,
    ): ModelTestResult {
        if (payload.status !in 200..299) {
            return failed(
                model = model,
                error = errorForHttp(payload.status, payload.body),
                httpStatus = payload.status,
                latencyMs = latencyMs,
            )
        }
        val body = runCatching { JSONObject(payload.body) }.getOrElse {
            return failed(
                model = model,
                error = TestError(ErrorKind.INVALID_RESPONSE, "上游返回非 JSON 响应"),
                httpStatus = payload.status,
                latencyMs = latencyMs,
            )
        }
        val usage = parseUsage(body.optJSONObject("usage"))

        return when (protocol) {
            RelayProtocol.CHAT_COMPLETIONS -> {
                val choices = body.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    ModelTestResult(
                        model = model,
                        status = TestStatus.SUCCESS,
                        latencyMs = latencyMs,
                        httpStatus = payload.status,
                        finishReason = choices.optJSONObject(0)?.optString("finish_reason")
                            ?.takeIf(String::isNotBlank),
                        usage = usage,
                    )
                } else {
                    failed(
                        model,
                        TestError(
                            ErrorKind.INVALID_RESPONSE,
                            protocolHint(body, "上游返回 HTTP 200 但 choices 为空"),
                        ),
                        payload.status,
                        latencyMs,
                    )
                }
            }

            RelayProtocol.RESPONSES -> {
                val isCompleted = body.optString("status") == "completed"
                if (body.optJSONArray("output")?.length() ?: 0 > 0 || isCompleted) {
                    ModelTestResult(
                        model = model,
                        status = TestStatus.SUCCESS,
                        latencyMs = latencyMs,
                        httpStatus = payload.status,
                        finishReason = body.optString("status").takeIf(String::isNotBlank),
                        usage = usage,
                    )
                } else {
                    failed(
                        model,
                        TestError(
                            ErrorKind.INVALID_RESPONSE,
                            protocolHint(body, "上游返回 HTTP 200 但缺少 output"),
                        ),
                        payload.status,
                        latencyMs,
                    )
                }
            }

            RelayProtocol.ANTHROPIC -> {
                if (body.optJSONArray("content")?.length() ?: 0 > 0) {
                    ModelTestResult(
                        model = model,
                        status = TestStatus.SUCCESS,
                        latencyMs = latencyMs,
                        httpStatus = payload.status,
                        finishReason = body.optString("stop_reason").takeIf(String::isNotBlank),
                        usage = usage,
                    )
                } else {
                    failed(
                        model,
                        TestError(
                            ErrorKind.INVALID_RESPONSE,
                            protocolHint(body, "上游返回 HTTP 200 但缺少 content"),
                        ),
                        payload.status,
                        latencyMs,
                    )
                }
            }
        }
    }

    private fun parseUsage(usage: JSONObject?): TokenUsage? {
        usage ?: return null
        fun value(vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
            usage.optLong(name, -1).takeIf { it >= 0 }
        }
        return TokenUsage(
            inputTokens = value("prompt_tokens", "input_tokens"),
            outputTokens = value("completion_tokens", "output_tokens"),
            totalTokens = value("total_tokens"),
        )
    }

    private fun protocolHint(body: JSONObject, fallback: String): String = when {
        body.has("choices") -> "上游返回 Chat 格式；请把协议切换为 Chat"
        body.has("output") -> "上游返回 Responses 格式；请把协议切换为 Responses"
        body.has("content") -> "上游返回 Anthropic 格式；请把协议切换为 Anthropic"
        else -> fallback
    }

    private suspend fun execute(request: Request, timeoutSeconds: Int): HttpPayload =
        withContext(Dispatchers.IO) {
            // Build a per-request client so connect/read/write timeouts scale with the
            // user's configured timeout. Slow relays that need longer than the OkHttp
            // default (10s) connect/read window must not fail early.
            val totalSeconds = (timeoutSeconds + CLIENT_GRACE_SECONDS).toLong()
            val ready = client.forSameOriginRequests(totalSeconds, totalSeconds, totalSeconds, totalSeconds)
            ready.executeSameOrigin(request).use { response ->
                HttpPayload(
                    status = response.code,
                    body = response.body?.readLimitedUtf8() ?: "",
                )
            }
        }

    private fun errorForHttp(status: Int, body: String): TestError {
        val kind = when (status) {
            401 -> ErrorKind.AUTHENTICATION
            402 -> ErrorKind.INSUFFICIENT_QUOTA
            403 -> ErrorKind.FORBIDDEN
            404 -> ErrorKind.MODEL_NOT_FOUND
            429 -> ErrorKind.RATE_LIMITED
            in 500..599 -> ErrorKind.UPSTREAM
            else -> ErrorKind.OTHER
        }
        return TestError(
            kind = kind,
            message = extractErrorMessage(body).ifBlank { "上游返回 HTTP $status" },
        )
    }

    internal fun errorForThrowable(error: Throwable): TestError = when (error) {
        is InterruptedIOException -> TestError(ErrorKind.TIMEOUT, "请求超时")
        // Before the generic IOException: an oversized body is a property of the upstream
        // answer, not of the link. Reported as a network failure it also entered the retry
        // set, so the largest responses burned the whole retry budget and then blamed the
        // connection.
        is ResponseTooLargeException -> TestError(
            ErrorKind.INVALID_RESPONSE,
            error.message ?: "响应体过大",
        )
        // Before the generic IOException: the refusal carries the reason a redirect was
        // not followed, and collapsing it to "网络连接失败" would hide an attack shape.
        is RedirectRefusedException ->
            TestError(ErrorKind.OTHER, error.message?.take(MAX_ERROR_CHARS) ?: "上游重定向被拒绝")
        is IOException -> TestError(ErrorKind.NETWORK, "网络连接失败")
        // The catch-all is the one path that carries a *foreign* message: it is whatever the
        // platform or OkHttp chose to say, and some of those sayings quote the request they
        // were given — header values included. Redacted before the length cut, because
        // truncating first can leave a secret's own prefix behind and the pattern then no
        // longer recognises it.
        else -> TestError(
            ErrorKind.OTHER,
            (error.message ?: "请求失败").redactSecrets().take(MAX_ERROR_CHARS),
        )
    }

    private fun extractErrorMessage(body: String): String = runCatching {
        val json = JSONObject(body)
        val error = json.opt("error")
        when (error) {
            is JSONObject -> error.optString("message").ifBlank { error.toString() }
            is String -> error
            else -> json.optString("message")
        }.redactSecrets().take(MAX_ERROR_CHARS)
    }.getOrElse {
        // Some upstreams return HTML or plain text when an API gateway rejects
        // a request. Apply the same redaction used for JSON errors so a reflected
        // Authorization/API-key value can never reach the result list or export.
        body.replace(Regex("\\s+"), " ").redactSecrets().take(MAX_ERROR_CHARS)
    }

    private fun failed(
        model: String,
        kind: ErrorKind,
        message: String,
    ): ModelTestResult = failed(model, TestError(kind, message))

    private fun failed(
        model: String,
        error: TestError,
        httpStatus: Int? = null,
        latencyMs: Long? = null,
    ): ModelTestResult = ModelTestResult(
        model = model,
        status = TestStatus.FAILED,
        latencyMs = latencyMs,
        httpStatus = httpStatus,
        error = error,
    )

    private fun normalizedBaseUrl(raw: String): String? = RelayBaseUrl.normalize(raw)

    private fun ResponseBody.readLimitedUtf8(): String {
        source().use { source ->
            val buffer = Buffer()
            while (source.read(buffer, READ_CHUNK_BYTES) != -1L) {
                if (buffer.size > MAX_RESPONSE_BYTES) {
                    throw ResponseTooLargeException()
                }
            }
            return buffer.readUtf8()
        }
    }

    private data class HttpPayload(
        val status: Int,
        val body: String,
    )

    /**
     * An answer larger than this client will hold; the link is fine, the answer is not.
     *
     * The message is carried rather than fixed because two different ceilings throw it — a
     * buffered body past [MAX_RESPONSE_BYTES] and a stream past [MAX_STREAM_BYTES] — and the
     * user is owed the ceiling that actually applied.
     */
    internal class ResponseTooLargeException(message: String) : IOException(message) {
        constructor() : this("响应体过大（超过 ${MAX_RESPONSE_BYTES / 1024} KB）")
    }

    /**
     * Passes bytes through until [maxBytes] have been read, then refuses.
     *
     * A `readUtf8Line()` on an unbounded source is unbounded twice over: a peer that never
     * sends a newline accumulates in the reader's buffer, and one that never sends `[DONE]`
     * accumulates in the caller's string. Both are bounded here, at the one place every byte
     * goes through, so neither the framing (which may not know about a ceiling) nor the
     * caller has to enforce it. The throw surfaces as an ordinary read failure, which the
     * caller's `errorForThrowable` already classifies as an answer problem rather than a
     * broken link.
     */
    private class BoundedSource(delegate: Source, private val maxBytes: Long) : ForwardingSource(delegate) {
        private var bytesRead = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            val count = super.read(sink, byteCount)
            if (count == -1L) return count
            bytesRead += count
            if (bytesRead > maxBytes) {
                throw ResponseTooLargeException(
                    "流式响应过大（超过 ${maxBytes / 1024} KB），已停止读取",
                )
            }
            return count
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val USER_AGENT = "RelayTesterAndroid/1.0.0"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val CLIENT_GRACE_SECONDS = 5
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_RESPONSE_BYTES = 1_048_576L
        const val READ_CHUNK_BYTES = 8_192L
        const val MAX_ERROR_CHARS = 300

        /**
         * How many bytes of a streaming answer are read before the read is abandoned.
         *
         * The buffered path already refuses a body past [MAX_RESPONSE_BYTES]; the streaming
         * one had no ceiling at all, so a relay that never sent `[DONE]` — or an endpoint that
         * answered `text/event-stream` and then streamed something else — grew a string until
         * the process ran out of memory. 256 KB is far past any real answer (the detection
         * challenge asks for ~300 numbers, a couple of kilobytes) and well under the buffered
         * path's ceiling, which also counts the framing this one strips.
         */
        const val MAX_STREAM_BYTES = 262_144L

        /** What an HTTP header value may contain: the printable range, plus tab. */
        val ASCII_PRINTABLE = 0x20..0x7e

        /**
         * Codes that mean "this request is malformed", the ones a relay sends when it
         * cannot handle `stream: true`. Detection retries those buffered; anything else
         * is a real failure and is reported without spending a second request.
         */
        val STREAM_REFUSAL_CODES = setOf(400, 415, 422)
    }
}

/**
 * Extracts the assistant text from a completion response.
 *
 * Split out from [RelayApi] because it is pure JSON shaping and is the only part of
 * the request path that can be exercised without a server. Responses and Anthropic
 * both return a content array that may hold reasoning or tool blocks next to text,
 * so every text-bearing block is concatenated rather than only the first.
 */
object RelayCompletionText {
    fun extract(body: JSONObject, protocol: RelayProtocol): String = when (protocol) {
        RelayProtocol.CHAT_COMPLETIONS -> {
            val message = body.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            when (val content = message?.opt("content")) {
                is String -> content
                // Some relays emit the multimodal content-block array here.
                is JSONArray -> joinBlocks(content)
                else -> ""
            }
        }

        RelayProtocol.RESPONSES -> {
            val output = body.optJSONArray("output") ?: JSONArray()
            buildString {
                for (index in 0 until output.length()) {
                    val item = output.optJSONObject(index) ?: continue
                    // Reasoning blocks carry a summary, not the model's answer.
                    if (item.optString("type") == "reasoning") continue
                    append(joinBlocks(item.optJSONArray("content")))
                }
            }
        }

        RelayProtocol.ANTHROPIC -> joinBlocks(body.optJSONArray("content"))
    }

    /**
     * Extracts the text from one SSE event's JSON payload.
     *
     * A streamed event is not a full response: the text sits under the protocol's own
     * delta path, which is a different shape per protocol, and an event that carries no
     * text (a role preamble, a usage tally, a ping) must contribute an empty string
     * rather than a guess. Kept next to [extract] so both read the same protocol rules.
     *
     * @param payload the JSON after `data:`; never `[DONE]`, which the reader handles.
     */
    fun extractStreamDelta(payload: String, protocol: RelayProtocol): String {
        val json = JSONObject(payload)
        return when (protocol) {
            RelayProtocol.CHAT_COMPLETIONS -> {
                val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                when (val content = delta?.opt("content")) {
                    is String -> content
                    // Multimodal relays stream the same content-block array as their
                    // buffered replies; the text still lives in the blocks.
                    is JSONArray -> joinBlocks(content)
                    else -> ""
                }
            }

            RelayProtocol.RESPONSES -> {
                // The text delta is the only event type that carries answer text;
                // `response.output_item.done` repeats what was already streamed, so
                // counting it too would double every number.
                if (json.optString("type") != "response.output_text.delta") return ""
                json.optString("delta")
            }

            RelayProtocol.ANTHROPIC -> {
                if (json.optString("type") != "content_block_delta") return ""
                val delta = json.optJSONObject("delta") ?: return ""
                // `thinking_delta` carries reasoning, which is not the answer.
                if (delta.optString("type") != "text_delta") return ""
                delta.optString("text")
            }
        }
    }

    /**
     * Extracts text from a whole completion body that may or may not be a stream.
     *
     * Used by the streaming call's fallback branch: an upstream that ignored
     * `stream: true` answers with one JSON document, and one that honoured it but had
     * its events coalesced may still arrive as a single object.
     */
    fun extractFromBody(raw: String, protocol: RelayProtocol): String {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return ""
        return extract(json, protocol)
    }

    private fun joinBlocks(blocks: JSONArray?): String {
        blocks ?: return ""
        return buildString {
            for (index in 0 until blocks.length()) {
                val block = blocks.optJSONObject(index) ?: continue
                if (block.optString("type") == "thinking") continue
                val value = block.optString("text")
                if (value.isNotEmpty()) append(value)
            }
        }
    }
}

/**
 * Base-URL normalization shared by every relay request.
 *
 * A scheme-less address defaults to HTTPS, so cleartext is opt-in: it happens
 * only when the user types an explicit `http://` prefix. That keeps every
 * existing configuration on TLS while letting a relay that only serves plain
 * HTTP remain usable.
 */
object RelayBaseUrl {
    fun normalize(raw: String): String? {
        val candidate = raw.trim()
            .let {
                when {
                    it.startsWith("https://", ignoreCase = true) -> it
                    it.startsWith("http://", ignoreCase = true) -> it
                    else -> "https://$it"
                }
            }
            .trimEnd('/')
        val parsed = candidate.toHttpUrlOrNull() ?: return null
        if (parsed.host.isBlank()) return null
        return parsed.toString().trimEnd('/')
    }

    /** True when [raw] already names a cleartext HTTP endpoint. */
    fun isCleartext(raw: String): Boolean =
        raw.trim().startsWith("http://", ignoreCase = true)
}
