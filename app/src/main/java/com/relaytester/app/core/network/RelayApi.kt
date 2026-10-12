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
                if (profile.protocol == RelayProtocol.RESPONSES) {
                    // The buffered Responses path shares the terminal rule with the
                    // streaming one: text already in hand is not a verdict. failed /
                    // incomplete / missing status each keep their own failure message
                    // instead of all collapsing into "no text".
                    when (
                        val terminal = RelayCompletionText
                            .responsesTerminalFromStatus(body.optString("status"))
                            ?: ResponsesTerminal.UNCONFIRMED
                    ) {
                        ResponsesTerminal.COMPLETED -> {}
                        ResponsesTerminal.FAILED -> return ApiResult.Failure(
                            TestError(
                                ErrorKind.UPSTREAM,
                                responsesFailureMessage(body, "上游返回失败状态（status=failed）"),
                            ),
                            httpStatus = payload.status,
                        )
                        ResponsesTerminal.INCOMPLETE -> return ApiResult.Failure(
                            TestError(
                                ErrorKind.INVALID_RESPONSE,
                                "上游返回未完成状态（status=incomplete），本次回答不计为完整结果",
                            ),
                            httpStatus = payload.status,
                        )
                        ResponsesTerminal.UNCONFIRMED -> return ApiResult.Failure(
                            TestError(
                                ErrorKind.INVALID_RESPONSE,
                                "上游返回 HTTP 200 但未给出 Responses 完成状态（status 缺失）",
                            ),
                            httpStatus = payload.status,
                        )
                    }
                }
                val text = RelayCompletionText.extract(body, profile.protocol)
                if (text.isBlank()) {
                    ApiResult.Failure(
                        TestError(
                            ErrorKind.INVALID_RESPONSE,
                            protocolHint(body, NO_ANSWER_TEXT_MESSAGE),
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
     *
     * The two callbacks report at different granularities, and the difference is
     * deliberate rather than incidental:
     *  - [onProgress] receives the answer **accumulated so far**. The caller counts usable
     *    numbers or characters in it, which only works on the whole text. It fires on every
     *    increase while the answer is small and on a coarser, size-proportional cadence once
     *    it is not ([streamProgressDue]), so a large document does not make the copying cost
     *    quadratic.
     *  - [onReasoning] receives the **fragment the frame carried**, not the accumulation.
     *    Reasoning is unbounded and can be far longer than the answer — measured against this
     *    app's own animation prompt, one model emitted 44 145 characters of reasoning without
     *    writing any answer at all — so accumulating it here would copy an ever-growing
     *    string on every frame, the quadratic cost [streamProgressDue] exists to avoid. A
     *    caller that wants the whole chain of thought accumulates it itself, under whatever
     *    bound its display needs.
     */
    open suspend fun completeTextStreaming(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        onProgress: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit = {},
        salvageOnDisconnect: Boolean = false,
    ): ApiResult<String> {
        val prepared = when (val prep = prepareCompletion(profile, apiKey)) {
            is CompletionPrep.Ready -> prep.builder
            is CompletionPrep.Refused -> return ApiResult.Failure(prep.error)
        }
        val request = prepared
            .post(
                buildCompletionPayload(
                    profile.protocol,
                    model,
                    prompt,
                    maxTokens,
                    stream = true,
                ),
            )
            .header("Accept", "text/event-stream")
            .build()

        return try {
            // Same dispatcher rule as the buffered path: reading the body blocks the
            // thread, so it must not happen on the caller's.
            withContext(Dispatchers.IO) {
                val totalSeconds = (timeoutSeconds + CLIENT_GRACE_SECONDS).toLong()
                // Only connect/read/write are bounded; the whole call deliberately is not.
                // A streaming completion's total is the model's thinking time, which the app
                // cannot predict — measured at 850 s of healthy streaming on this supplier —
                // so bounding it would report "请求超时" for a request that was working. The
                // read window is the real watchdog: it fires only after `totalSeconds` with
                // *no* bytes at all, which is what a dead link looks like.
                val ready = client.forSameOriginRequests(totalSeconds, totalSeconds, totalSeconds)
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
                    if (contentType.contains("event-stream", ignoreCase = true)) {
                        val read = readEventStream(
                            body,
                            profile.protocol,
                            onProgress,
                            onReasoning,
                            tolerateDisconnect = salvageOnDisconnect,
                        )
                        if (profile.protocol != RelayProtocol.RESPONSES) {
                            // Chat and Anthropic keep their EOF compatibility: what
                            // arrived is the answer, [DONE] or not (see the streaming
                            // tests that pin this for Chat).
                            return@withContext answerOutcome(read.text, response.code)
                        }
                        // A Responses stream is only as finished as its terminal event
                        // says. Text already received never overrules `response.failed`,
                        // and an EOF / [DONE] without a verdict is *not* a completion —
                        // the same UNCONFIRMED policy the buffered path applies.
                        when (read.terminal ?: ResponsesTerminal.UNCONFIRMED) {
                            ResponsesTerminal.COMPLETED -> return@withContext answerOutcome(
                                read.text,
                                response.code,
                            )
                            ResponsesTerminal.FAILED -> return@withContext ApiResult.Failure(
                                TestError(
                                    ErrorKind.UPSTREAM,
                                    "上游流式返回失败（response.failed）" +
                                        read.failureMessage.orEmpty().let { if (it.isBlank()) "" else "：$it" },
                                ),
                                httpStatus = response.code,
                            )
                            ResponsesTerminal.INCOMPLETE -> return@withContext ApiResult.Failure(
                                TestError(
                                    ErrorKind.INVALID_RESPONSE,
                                    "上游流式返回未完成（response.incomplete），" +
                                        "已收到 ${read.text.length} 个字符，不作为完整回答",
                                ),
                                httpStatus = response.code,
                            )
                            ResponsesTerminal.UNCONFIRMED -> return@withContext ApiResult.Failure(
                                TestError(
                                    ErrorKind.INVALID_RESPONSE,
                                    "流已结束但未收到完成事件（缺少 response.completed），" +
                                        "已收到 ${read.text.length} 个字符",
                                ),
                                httpStatus = response.code,
                            )
                        }
                    }
                    // Not an SSE reply: the upstream ignored the flag. Read it whole and
                    // hold it to the same status rules as the buffered path, so a relay
                    // that only supports complete answers is judged the same way with or
                    // without the streaming flag.
                    val raw = body.readLimitedUtf8()
                    val buffered = runCatching { JSONObject(raw) }.getOrNull()
                    val text = buffered
                        ?.let { RelayCompletionText.extract(it, profile.protocol) }
                        .orEmpty()
                    if (profile.protocol == RelayProtocol.RESPONSES) {
                        when {
                            buffered == null -> return@withContext ApiResult.Failure(
                                TestError(ErrorKind.INVALID_RESPONSE, "上游返回非 JSON 响应"),
                                httpStatus = response.code,
                            )
                            else -> when (
                                RelayCompletionText
                                    .responsesTerminalFromStatus(buffered.optString("status"))
                                    ?: ResponsesTerminal.UNCONFIRMED
                            ) {
                                ResponsesTerminal.COMPLETED -> return@withContext answerOutcome(
                                    text,
                                    response.code,
                                )
                                ResponsesTerminal.FAILED -> return@withContext ApiResult.Failure(
                                    TestError(
                                        ErrorKind.UPSTREAM,
                                        responsesFailureMessage(
                                            buffered,
                                            "上游返回失败状态（status=failed）",
                                        ),
                                    ),
                                    httpStatus = response.code,
                                )
                                ResponsesTerminal.INCOMPLETE -> return@withContext ApiResult.Failure(
                                    TestError(
                                        ErrorKind.INVALID_RESPONSE,
                                        "上游返回未完成状态（status=incomplete），本次回答不计为完整结果",
                                    ),
                                    httpStatus = response.code,
                                )
                                ResponsesTerminal.UNCONFIRMED -> return@withContext ApiResult.Failure(
                                    TestError(
                                        ErrorKind.INVALID_RESPONSE,
                                        "上游返回 HTTP 200 但未给出 Responses 完成状态（status 缺失）",
                                    ),
                                    httpStatus = response.code,
                                )
                            }
                        }
                    }
                    answerOutcome(text, response.code)
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
     * against a relay that ignores `stream: true`. (The streaming call now reads that
     * body inline so it can also read the Responses `status` field next to the text.)
     */

    /**
     * A 200 answered with text and, for Responses, the terminal state the protocol
     * itself declared on the wire. The text alone was never the verdict.
     */
    private class StreamRead(
        val text: String,
        val terminal: ResponsesTerminal?,
        val failureMessage: String? = null,
    )

    /** Success or "no usable text" for a body that already passed the status rules. */
    private fun answerOutcome(text: String, httpStatus: Int): ApiResult<String> =
        if (text.isBlank()) {
            ApiResult.Failure(
                TestError(ErrorKind.INVALID_RESPONSE, NO_ANSWER_TEXT_MESSAGE),
                httpStatus = httpStatus,
            )
        } else {
            ApiResult.Success(text)
        }

    /**
     * The reason a failed Responses result gives, when it gives one.
     *
     * `error` may be an object (the OpenAI shape) or a bare string; the fallback keeps
     * the caller's own wording so the message never collapses to "failed" with nothing
     * else.
     */
    private fun responsesFailureMessage(body: JSONObject, fallback: String): String {
        val detail = when (val error = body.opt("error")) {
            is JSONObject -> error.optString("message")
            is String -> error
            else -> ""
        }.trim()
        return (if (detail.isBlank()) fallback else "$fallback：$detail")
            .redactSecrets()
            .take(MAX_ERROR_CHARS)
    }

    /**
     * Reads an SSE body, emitting the accumulated text as each delta arrives.
     *
     * Only `data:` payloads carry text; `event:`, `id:` and comment lines are skipped as
     * the format requires. `[DONE]` ends the stream. A malformed frame is skipped rather
     * than failing the request: a relay that emits one unparseable keep-alive must not
     * cost the user a whole challenge.
     *
     * For Responses, terminal events are recorded as they pass: `response.completed`,
     * `response.failed` and `response.incomplete` are the protocol's own verdict, and
     * the first one seen wins. The reader returns them next to the text so the caller
     * can refuse to score a stream that never reached `response.completed` — EOF and
     * `[DONE]` end the *reading*, not the *answer*.
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
        onReasoning: suspend (String) -> Unit = {},
        tolerateDisconnect: Boolean = false,
    ): StreamRead {
        val accumulated = StringBuilder()
        // The ceiling lives on the bytes, not on the assembled text: a stream is bounded by
        // how much of it is read, and bounding that is what also bounds a peer that sends
        // bytes with no newline at all (which would otherwise be buffered whole while the
        // reader waited for a terminator). The text cannot outgrow the bytes it came from.
        val source = BoundedSource(body.source(), MAX_STREAM_BYTES).buffer()
        var lastReported = 0
        var terminal: ResponsesTerminal? = null
        var failureMessage: String? = null
        // Chat streams from thinking models sometimes carry the reasoning *inside* the
        // answer content, fenced by `<think>…</think>` (vLLM/sglang deployments do this
        // where a relay has no separate reasoning channel). Left in place it poisons both
        // channels at once: the panel shows the chain of thought as if it were the document
        // being written, and a scorer reads think-text as answer text. The splitter is
        // stateful because a tag can be split across two deltas, and it is only built for
        // Chat — Anthropic and Responses have real reasoning channels of their own.
        val thinkSplitter = if (protocol == RelayProtocol.CHAT_COMPLETIONS) {
            ThinkTagSplitter()
        } else {
            null
        }
        // One event's payload may be split across several `data:` lines, and the spec joins
        // them with a newline before the payload is read. Framing per line instead of per
        // event drops every continuation line, and the answer text goes missing with no
        // error to show for it — which reads as a model that answered wrongly rather than
        // as a relay that framed legally. Only a blank line ends an event: comments,
        // `event:` and `id:` belong to the current one and must not dispatch it.
        val payload = StringBuilder()
        var sawData = false

        /** Reads the buffered event. True means `[DONE]`: the stream is over. */
        suspend fun dispatchEvent(): Boolean {
            if (!sawData) return false
            val event = payload.toString()
            payload.setLength(0)
            sawData = false
            if (event == "[DONE]") return true
            if (protocol == RelayProtocol.RESPONSES && terminal == null) {
                // First terminal event wins: the protocol says nothing follows its
                // verdict, and a second event after `response.failed` must not upgrade
                // the outcome the failure already set.
                runCatching {
                    val json = JSONObject(event)
                    when (
                        RelayCompletionText.responsesTerminalFromEvent(json.optString("type"))
                    ) {
                        ResponsesTerminal.COMPLETED -> terminal = ResponsesTerminal.COMPLETED
                        ResponsesTerminal.FAILED -> {
                            terminal = ResponsesTerminal.FAILED
                            // The upstream wrote this sentence and a relay that rejects a key
                            // routinely quotes it back, so it is redacted and bounded here,
                            // where it enters the app. Redacting at the point of use instead
                            // would leave the raw text sitting in StreamRead for the next
                            // caller to print; this is the same rule `responsesFailureMessage`
                            // applies on the buffered path.
                            failureMessage = json.optJSONObject("response")
                                ?.optJSONObject("error")
                                ?.optText("message")
                                .orEmpty()
                                .redactSecrets()
                                .take(MAX_ERROR_CHARS)
                        }
                        ResponsesTerminal.INCOMPLETE -> terminal = ResponsesTerminal.INCOMPLETE
                        null, ResponsesTerminal.UNCONFIRMED -> {}
                    }
                }
            }
            val delta = runCatching { RelayCompletionText.extractStreamDelta(event, protocol) }
                .getOrNull()
                .orEmpty()
            // `<think>` fences ride in the content channel; what they wrap is reasoning and
            // must land on the reasoning callback, never in the accumulated answer.
            val routed = thinkSplitter?.feed(delta)
            val answerDelta = routed?.first ?: delta
            if (routed != null && routed.second.isNotEmpty()) onReasoning(routed.second)
            if (answerDelta.isEmpty()) {
                // A frame with no answer text may still be the model thinking. Reporting it
                // is the difference between "the model is working on it" and "this screen is
                // frozen": a reasoning model can spend its whole budget before the first
                // answer frame, and with the frames dropped the panel sits on 等待响应… for
                // that entire time with nothing to distinguish it from a hung request.
                // (A frame whose content was only a held-back `<think>` tag fragment also
                // lands here, with nothing on either channel — correct: nothing was said.)
                val reasoning = runCatching {
                    RelayCompletionText.extractStreamReasoning(event, protocol)
                }.getOrNull().orEmpty()
                if (reasoning.isNotEmpty()) onReasoning(reasoning)
                return false
            }
            // Past the answer ceiling the stream is drained but not recorded: reasoning
            // frames never accumulated in the first place, and an answer that runs this
            // long is truncated with its head intact rather than killing the request
            // (see [MAX_STREAM_ANSWER_CHARS] for why that is the honest trade).
            if (accumulated.length < MAX_STREAM_ANSWER_CHARS) {
                accumulated.append(answerDelta.take(MAX_STREAM_ANSWER_CHARS - accumulated.length))
            }
            // Reported on a geometric cadence rather than per delta, so the cost of copying
            // the accumulated text stays proportional to the answer instead of to its square.
            // Small answers still report every delta — the panel's count stays live, which is
            // the contract the callers rely on — and only a large answer steps up. See
            // [streamProgressDue].
            if (streamProgressDue(accumulated.length, lastReported)) {
                lastReported = accumulated.length
                onProgress(accumulated.toString())
            }
            return false
        }

        // A disconnect mid-stream is not always the end of the world: with
        // [tolerateDisconnect] the read stops there and what already arrived is returned as
        // if the stream had ended — the caller's own rules (a complete document, a terminal
        // event) decide what it is worth. The animation test sets this: an upstream that
        // cuts the connection after 4 minutes of thinking must not throw away the document
        // half that already came through the wire. The size refusal is *not* tolerated —
        // it is a verdict about the answer, not a broken link.
        try {
            var done = false
            while (!done) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isBlank() -> done = dispatchEvent()
                    line.startsWith("data:") -> {
                        // The separator belongs between lines, so a line that contributes an
                        // empty value still counts as one.
                        if (sawData) payload.append('\n')
                        payload.append(line.removePrefix("data:").trim())
                        sawData = true
                    }
                    // A comment (`:`), `event:` or `id:` line is part of the event it sits in
                    // and never ends it.
                    else -> Unit
                }
            }
            // A relay that closes without the closing blank line still sent its payload;
            // dropping it would lose the last delta of an otherwise complete answer.
            if (!done) dispatchEvent()
        } catch (error: IOException) {
            if (!tolerateDisconnect || error is ResponseTooLargeException) throw error
        } finally {
            // Whatever the read ended with, the splitter may still be holding a tail that
            // never resolved into a tag (a partial `<thi` at the cut, or a `<think>` block
            // the disconnect left unclosed). Flush it by its own rule so the last sliver of
            // answer or reasoning is not lost.
            thinkSplitter?.finish()?.let { (answer, reasoning) ->
                if (reasoning.isNotEmpty()) onReasoning(reasoning)
                if (answer.isNotEmpty() && accumulated.length < MAX_STREAM_ANSWER_CHARS) {
                    accumulated.append(answer.take(MAX_STREAM_ANSWER_CHARS - accumulated.length))
                    if (streamProgressDue(accumulated.length, lastReported)) {
                        onProgress(accumulated.toString())
                    }
                }
            }
        }
        // A stream that carried no deltas at all (some relays answer 200 text/event-stream
        // with only a [DONE]) has nothing to fall back to here; the caller's status rules
        // and blank check report it.
        return StreamRead(accumulated.toString(), terminal, failureMessage)
    }

    /**
     * Whether the accumulated answer should be handed to the progress callback now.
     *
     * The callback receives the **whole** accumulated text, so reporting every increase
     * copies the whole answer once per increase: `O(n²)` over a stream. That is free while
     * the answer is small and is not once an answer can reach [MAX_STREAM_ANSWER_CHARS] —
     * at 262 144 characters arriving in ~60-character deltas, per-delta reporting copies
     * ~570 million characters (measured by simulation), for a readout that shows a number.
     *
     * So the cadence steps up with size: every increase below [PROGRESS_STEP_FLOOR] (the
     * three-question round's answer sizes, where the callers' live count matters and the
     * text is small), then a report once another `length / PROGRESS_STEP_DIVISOR` characters
     * have arrived. That divisor makes the total copied volume linear in the answer rather
     * than quadratic: over an answer of final length N the reports copy about
     * `PROGRESS_STEP_DIVISOR × N` characters in total — ~16.8 million for a full-budget
     * document, from 443 reports instead of 4 370 — and the reported count is never more
     * than `1 / PROGRESS_STEP_DIVISOR` (1.6%) behind what has actually arrived.
     */
    internal fun streamProgressDue(length: Int, lastReported: Int): Boolean {
        if (length <= lastReported) return false
        if (length < PROGRESS_STEP_FLOOR) return true
        val step = (length / PROGRESS_STEP_DIVISOR).coerceAtLeast(1)
        return length - lastReported >= step
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

        // Nothing here restrains the model's reasoning. This app deliberately leaves the
        // thinking phase untouched: the animation test exists to show what a model does
        // when nothing is holding it back, so no `reasoning_effort`, no
        // `chat_template_kwargs` and no `thinking` control is sent. What the caller does
        // instead is give the request a wide budget and a long timeout, and subscribe to
        // [readEventStream]'s reasoning callback so the thinking is visible while it runs.
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
                // The connection test shares the terminal vocabulary with the completion
                // paths, but not their text requirement: its question is "does this
                // endpoint speak Responses and answer it", so `completed` counts with or
                // without text. What it must not do is let HTTP 200 launder a failure
                // the body itself declares: `failed` and `incomplete` are verdicts, and
                // non-empty `output` next to them is a partial, not a success. A body
                // that declares no status at all keeps the connection-level answer it
                // always got (the completion paths hold it to UNCONFIRMED instead).
                val terminal = RelayCompletionText.responsesTerminalFromStatus(
                    body.optString("status"),
                )
                when (terminal) {
                    ResponsesTerminal.FAILED -> failed(
                        model,
                        TestError(
                            ErrorKind.UPSTREAM,
                            responsesFailureMessage(body, "上游返回失败状态（status=failed）"),
                        ),
                        payload.status,
                        latencyMs,
                    )
                    ResponsesTerminal.INCOMPLETE -> failed(
                        model,
                        TestError(
                            ErrorKind.INVALID_RESPONSE,
                            "上游返回未完成状态（status=incomplete）",
                        ),
                        payload.status,
                        latencyMs,
                    )
                    else -> {
                        val hasOutput = (body.optJSONArray("output")?.length() ?: 0) > 0
                        if (hasOutput || terminal == ResponsesTerminal.COMPLETED) {
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
            // default (10s) connect/read window must not fail early. The whole call is left
            // unbounded for the same reason as the streaming path: a non-streaming request
            // waits for the same thinking the streaming one does, so a whole-call bound
            // would kill it at the same point — just with the reading already finished.
            val totalSeconds = (timeoutSeconds + CLIENT_GRACE_SECONDS).toLong()
            val ready = client.forSameOriginRequests(totalSeconds, totalSeconds, totalSeconds)
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

        /**
         * The largest buffered body the app will hold.
         *
         * Raised 1 MB → 4 MB alongside [MAX_STREAM_BYTES]: this is the ceiling the animation
         * test falls back to when a relay refuses `stream: true` (the malformed-request
         * codes), and a non-streamed completion carries the whole document — plus, for a
         * thinking model, the whole reasoning trace — in one JSON body. At the request's own
         * `max_tokens = 65 536` that is a six-figure character count before JSON escaping,
         * which 1 MB could refuse; refusing it would turn a relay's harmless lack of stream
         * support into "响应体过大" on a document the app is perfectly able to preview.
         */
        const val MAX_RESPONSE_BYTES = 4_194_304L
        const val READ_CHUNK_BYTES = 8_192L
        const val MAX_ERROR_CHARS = 300

        /**
         * How large a jump in the answer may go unreported once the answer is not tiny.
         *
         * The progress callback copies the accumulated text, so reporting every delta is
         * `O(n²)` in the answer length. Below [PROGRESS_STEP_FLOOR] characters the callback
         * still fires on every increase — that is the live-count behaviour the panels are
         * built on, and those answers are small enough for it to be free. Above it, a report
         * is due only once another `length / this` characters have arrived, which makes the
         * total copied volume linear in the answer.
         *
         * 64 means a report at most every ~1.6% of the answer's current size: for a
         * 100 000-character document that is a report every ~1 500 characters, i.e. tens of
         * reports where there would otherwise be thousands, and a count that is never more
         * than 1.6% behind what has actually arrived.
         */
        const val PROGRESS_STEP_DIVISOR = 64

        /**
         * The answer length below which every increase is reported.
         *
         * Set well above a challenge answer (a few kilobytes) so the three-question round
         * keeps its exact current cadence, and far below the largest answer the animation
         * test can request, so the quadratic term never grows large enough to matter. See
         * [streamProgressDue].
         */
        const val PROGRESS_STEP_FLOOR = 16_384

        /**
         * How many raw stream bytes are read before the read is abandoned.
         *
         * The buffered path refuses a body past [MAX_RESPONSE_BYTES]; the streaming path
         * needs the same kind of wall — a relay that never sent `[DONE]`, or an endpoint
         * that answered `text/event-stream` and then streamed something else, must hit a
         * bound instead of growing a buffer forever — but it is not the same number, and
         * the difference is the whole point of this constant.
         *
         * The ceiling's job is memory, not judging the answer. Reasoning models stream
         * thousands of `reasoning_content`/`thinking` frames before any answer text, and
         * every frame pays JSON framing on the wire: measured 58 bytes for a two-character
         * token frame, ~350 for a couple of hundred characters of reasoning. Those frames
         * are discarded per line by the extractor and never accumulate, so a megabyte of
         * this bound is spent after roughly three to seven thousand frames of reasoning —
         * reachable by any long chain of thought, and the challenge it kills has an answer
         * of ~300 numbers, a couple of kilobytes. The old 256 KB bound was this mistake one
         * turn earlier; 1 MB only moved the wall to where a slow, thorough model still
         * crosses it, and the user sees the same wrong verdict ("stream too large") for an
         * answer that was never large.
         *
         * 8 MB is sized against the framing, not the answer. The challenge asks for a few
         * hundred numbers, but it is sent `max_tokens = 8192`, and a reasoning model may
         * spend every one of them thinking before it answers: at ~350 bytes per framed
         * reasoning step that is ~2.9 MB of wire for an answer under 32 KB of text — the
         * 1 MB bound could not hold one full-budget chain of thought. 8 MB holds that with
         * ~2.7x headroom, i.e. ~25k-55k frames depending on step size, and matches
         * [com.relaytester.app.core.fingerprint.FingerprintBankStore]'s own ceiling for a
         * large file, so the app has one "biggest thing it will hold" number rather than
         * two. The three challenges of a round run concurrently, so the transient worst
         * case is three of these, ~24 MB, the same order as one detection package. A relay
         * that crosses 8 MB inside the challenge's 245 s call timeout (>34 KB/s of framing)
         * is not delivering an answer this app can score, and that timeout is the wall
         * which catches it; this one is the guard against a stream that never ends. The
         * bound that judges the answer itself is [MAX_STREAM_ANSWER_CHARS], not this one.
         *
         * 32 MB, raised from 8 MB, for the animation path with thinking left free — the one
         * request that can legitimately spend the whole of both budgets. Measured on the
         * supplier this was diagnosed against: reasoning framing runs at ~5.3 KB/s sustained
         * (60 s → 319 444 bytes of wire for 7 802 characters of reasoning), and the document
         * phase costs ~16 wire bytes per character of extracted HTML (10 231 characters of
         * document over 163 669 bytes). A full 600 s window of thinking plus a full-budget
         * document is therefore ~3.2 MB + ~4.2 MB ≈ 7.4 MB — inside 8 MB by almost nothing,
         * which is exactly the kind of ceiling that fails on the day a model thinks a little
         * longer. 32 MB holds that worst case with ~4x headroom. It is a bound on bytes read,
         * not bytes held: the reader discards each frame after extracting it, so this does
         * not set the process's memory footprint — [MAX_STREAM_ANSWER_CHARS] does that.
         */
        const val MAX_STREAM_BYTES = 33_554_432L

        /**
         * How many characters of extracted answer text are accumulated before the rest
         * of the stream is drained but not recorded.
         *
         * Sized against the largest answer the app itself will ask for, not against what a
         * typical answer looks like. The animation test requests `max_tokens = 65 536`, and
         * [com.relaytester.app.feature.fingerprint.TRUNCATION_CHARS_PER_TOKEN] is this
         * codebase's own working estimate of characters per token (3), so a full-budget
         * answer is ~196 608 characters. A ceiling below that truncates a legitimate answer
         * and then has the app report it as "the model did not finish its document" — the
         * app blaming the model for its own cut. 262 144 covers a full budget with margin.
         *
         * This used to be 65 536, justified by the per-delta copy cost: the progress
         * callback hands the caller the accumulated text, so reading is quadratic in the
         * answer length. That cost is real, and the ceiling is now raised anyway because
         * the copy cost is bounded directly instead — see [streamProgressDue], which reports
         * every delta while the answer is small and steps up once it is not, keeping the
         * total copied volume proportional to the answer rather than to its square.
         */
        const val MAX_STREAM_ANSWER_CHARS = 262_144

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
 * The sentence a 200 with no answer text produces, in one place.
 *
 * Both completion paths raise it — the buffered one through [protocolHint]'s fallback and
 * the streaming one through `answerOutcome` — and the fingerprint round recognises it: a
 * blank answer is the failure an automatic re-ask can repair, because a real upstream
 * produces it as its own coin toss rather than as a property of the request. The decision
 * to ask again keys on this constant, not on a copy of the sentence, so the wording the
 * user sees and the wording the retry matches can never drift apart.
 */
internal const val NO_ANSWER_TEXT_MESSAGE = "上游返回 HTTP 200 但没有可用的文本内容"

/**
 * True for the "200 with no answer text" failure, the one an automatic re-ask repairs.
 *
 * Equality, not a prefix: the buffered path wraps this sentence in [protocolHint]'s
 * fallback, and a message that instead names a protocol mismatch ("上游返回 Chat 格式…")
 * is a configuration verdict — re-sending it identically would spend a second request to
 * be told the same thing.
 */
internal fun TestError.isMissingAnswerText(): Boolean =
    kind == ErrorKind.INVALID_RESPONSE && message == NO_ANSWER_TEXT_MESSAGE

/**
 * How a Responses exchange ended, named by the protocol's own terminal vocabulary.
 *
 * The Responses API reports its outcome on the response object (`status`) and, when
 * streaming, on a terminal event (`response.completed` / `response.failed` /
 * `response.incomplete`). Neither HTTP 200 nor the presence of text is that verdict: a
 * relay that answers 200 with `status: "failed"` and a half-written `output`, or a
 * stream cut off before the terminal event, used to be read as a usable answer because
 * the text was already there.
 *
 * [UNCONFIRMED] is the policy for Responses-compatible replies that never say how they
 * ended — a body without `status`, a stream that ends on EOF or `[DONE]` alone. The
 * default is to *not* treat them as completed; a relay that needs that compatibility has
 * to say so explicitly rather than being silently equated with a real completion.
 */
enum class ResponsesTerminal {
    COMPLETED,
    FAILED,
    INCOMPLETE,

    /** No terminal state was observed; EOF or `[DONE]` alone is not completion. */
    UNCONFIRMED,
}

/**
 * Extracts the assistant text from a completion response.
 *
 * Split out from [RelayApi] because it is pure JSON shaping and is the only part of
 * the request path that can be exercised without a server. Responses and Anthropic
 * both return a content array that may hold reasoning or tool blocks next to text,
 * so every text-bearing block is concatenated rather than only the first.
 */
/**
 * Reads a string field that may be absent, an explicit JSON null, or a non-string as the
 * empty string.
 *
 * `optString` cannot do this: it returns the empty string for a *missing* key but the
 * literal text `"null"` for a key present with JSON null — which is exactly the shape many
 * relays use for every frame of a non-thinking model. Every wire field this file reads for
 * text goes through here so no upstream null can become visible text.
 */
internal fun JSONObject.optText(key: String): String {
    if (!has(key) || isNull(key)) return ""
    return when (val value = opt(key)) {
        is String -> value
        else -> ""
    }
}

object RelayCompletionText {
    /**
     * The terminal state a buffered Responses body declares, or null when it declares
     * none (the caller then holds [ResponsesTerminal.UNCONFIRMED]).
     */
    fun responsesTerminalFromStatus(status: String?): ResponsesTerminal? = when (status) {
        "completed" -> ResponsesTerminal.COMPLETED
        "failed" -> ResponsesTerminal.FAILED
        "incomplete" -> ResponsesTerminal.INCOMPLETE
        else -> null
    }

    /**
     * The terminal state one SSE event declares, or null for every other event type —
     * created/queued/in-progress events and the text deltas themselves are not verdicts.
     */
    fun responsesTerminalFromEvent(type: String): ResponsesTerminal? = when (type) {
        "response.completed" -> ResponsesTerminal.COMPLETED
        "response.failed" -> ResponsesTerminal.FAILED
        "response.incomplete" -> ResponsesTerminal.INCOMPLETE
        else -> null
    }

    fun extract(body: JSONObject, protocol: RelayProtocol): String = when (protocol) {
        RelayProtocol.CHAT_COMPLETIONS -> {
            val message = body.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            when (val content = message?.opt("content")) {
                is String -> stripThinkBlocks(content)
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
                json.optText("delta")
            }

            RelayProtocol.ANTHROPIC -> {
                if (json.optString("type") != "content_block_delta") return ""
                val delta = json.optJSONObject("delta") ?: return ""
                // `thinking_delta` carries reasoning, which is not the answer.
                if (delta.optString("type") != "text_delta") return ""
                delta.optText("text")
            }
        }
    }

    /**
     * Extracts the *reasoning* a streamed event carries, which is never the answer.
     *
     * Kept strictly apart from [extractStreamDelta]: reasoning is shown as activity so the
     * user can tell a thinking model from a hung request, but it must never reach the answer.
     * The two are separate functions rather than one flagged one precisely because a caller
     * that mixed them up would corrupt every answer while still looking correct.
     *
     * Field names differ per protocol and per relay: Chat proxies use `reasoning_content`
     * (DeepSeek style) and some use `reasoning`; Anthropic uses `thinking_delta`, which the
     * fact that [extractStreamDelta] refuses it is why this exists at all; Responses puts a
     * `reasoning_summary_text.delta` on the wire next to its output-text deltas.
     */
    fun extractStreamReasoning(payload: String, protocol: RelayProtocol): String {
        val json = JSONObject(payload)
        return when (protocol) {
            RelayProtocol.CHAT_COMPLETIONS -> {
                val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    ?: return ""
                // `reasoning_content` is the common one; `reasoning` shows up on relays that
                // rename it. Both are read so a thinking model is not silent just because the
                // proxy picked the other spelling. Read through [optText]: a relay that sends
                // `"reasoning_content": null` for a non-thinking frame must yield an empty
                // string, and `optString` answers the four characters `null` for an explicit
                // JSON null — which the panel then printed, one `null` per frame, as the
                // chain of thought.
                delta.optText("reasoning_content").ifEmpty { delta.optText("reasoning") }
            }

            RelayProtocol.RESPONSES -> {
                if (json.optString("type") != "response.reasoning_summary_text.delta") return ""
                json.optText("delta")
            }

            RelayProtocol.ANTHROPIC -> {
                if (json.optString("type") != "content_block_delta") return ""
                val delta = json.optJSONObject("delta") ?: return ""
                // `thinking_delta` carries the reasoning text; `signature_delta` carries a
                // cryptographic signature with no text, so it contributes nothing.
                if (delta.optString("type") != "thinking_delta") return ""
                delta.optText("thinking")
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

    /**
     * Removes `<think>…</think>` blocks — and an unterminated `<think>…` tail — from a
     * whole answer, for the buffered paths where there is no reasoning channel to put
     * them on. The streaming path splits these into [extractStreamReasoning] instead; the
     * two share the fence rule so a relay's framing cannot change what counts as thinking.
     */
    internal fun stripThinkBlocks(text: String): String {
        var result = text
        while (true) {
            val open = result.indexOf("<think>")
            if (open < 0) return result
            val close = result.indexOf("</think>", open)
            result = if (close >= 0) {
                result.removeRange(open, close + "</think>".length)
            } else {
                result.removeRange(open, result.length)
            }
        }
    }

    private fun joinBlocks(blocks: JSONArray?): String {
        blocks ?: return ""
        return buildString {
            for (index in 0 until blocks.length()) {
                val block = blocks.optJSONObject(index) ?: continue
                if (block.optString("type") == "thinking") continue
                val value = block.optText("text")
                if (value.isNotEmpty()) append(value)
            }
        }
    }
}

/**
 * Routes `<think>…</think>` fences out of a chat `content` stream into the reasoning channel.
 *
 * Some serving stacks have no separate reasoning field and fold the chain of thought into
 * the answer text, fenced by `<think>` … `</think>`. For such a model this app used to show
 * "not thinking" (the reasoning box stayed empty — the thinking arrived on the *answer*
 * channel, polluting the document view and any scoring of that text). Both halves are fixed
 * by splitting at the fence: everything inside becomes reasoning, everything outside stays
 * answer.
 *
 * The splitter is stateful because a fence can be cut by the delta boundary — one frame may
 * end `…<thi` and the next begin `nk>`. A tail that could still grow into a tag is held
 * back until the next feed or [finish] resolves it; that hold-back is bounded by the tag
 * length, so nothing accumulates. Matching is case-sensitive on the lowercase spellings
 * every known emitter uses; a document that merely *quotes* `<think>` in prose would be
 * split at the fence too, which is a model doing that to itself and is the same trade other
 * clients make.
 */
internal class ThinkTagSplitter {
    private var inside = false
    private val carry = StringBuilder()

    /**
     * Feeds one content delta and returns the (answer, reasoning) it yields. Either side
     * may be empty; both are empty when the whole delta was held back as a possible
     * partial tag.
     */
    fun feed(delta: String): Pair<String, String> {
        if (delta.isEmpty()) return "" to ""
        carry.append(delta)
        val answer = StringBuilder()
        val reasoning = StringBuilder()
        while (carry.isNotEmpty()) {
            if (inside) {
                val close = carry.indexOf(CLOSE)
                if (close >= 0) {
                    reasoning.append(carry, 0, close)
                    carry.delete(0, close + CLOSE.length)
                    inside = false
                    continue
                }
                val keep = carry.suffixMatchingPrefixOf(CLOSE)
                if (keep < carry.length) {
                    reasoning.append(carry, 0, carry.length - keep)
                    carry.delete(0, carry.length - keep)
                }
                break
            } else {
                val open = carry.indexOf(OPEN)
                if (open >= 0) {
                    answer.append(carry, 0, open)
                    carry.delete(0, open + OPEN.length)
                    inside = true
                    continue
                }
                val keep = carry.suffixMatchingPrefixOf(OPEN)
                if (keep < carry.length) {
                    answer.append(carry, 0, carry.length - keep)
                    carry.delete(0, carry.length - keep)
                }
                break
            }
        }
        return answer.toString() to reasoning.toString()
    }

    /**
     * Flushes what is held when the stream ends, once. An unclosed `<think>` block is all
     * reasoning (the model never got to close it — the common case when an upstream cuts
     * the connection mid-thought); a held partial tag outside a block is answer text.
     */
    fun finish(): Pair<String, String> {
        val rest = carry.toString()
        carry.setLength(0)
        return if (inside) "" to rest else rest to ""
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}

/** The longest suffix of this builder that is a proper prefix of [tag], or 0. */
private fun StringBuilder.suffixMatchingPrefixOf(tag: String): Int {
    for (keep in minOf(length, tag.length - 1) downTo 1) {
        var index = 0
        while (index < keep && this[length - keep + index] == tag[index]) index++
        if (index == keep) return keep
    }
    return 0
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
