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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

class RelayApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .build(),
) {
    suspend fun fetchModels(
        profile: SupplierProfile,
        apiKey: String,
        timeoutSeconds: Int,
    ): ApiResult<List<String>> {
        val baseUrl = normalizedBaseUrl(profile.baseUrl) ?: return ApiResult.Failure(
            TestError(ErrorKind.OTHER, "Base URL 必须是有效的 HTTPS 地址"),
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

    suspend fun test(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
    ): ModelTestResult {
        val baseUrl = normalizedBaseUrl(profile.baseUrl)
            ?: return failed(model, ErrorKind.OTHER, "Base URL 必须是有效的 HTTPS 地址")
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
        body.has("choices") -> "上游返回 Chat 格式响应，请将接口协议切换为 Chat"
        body.has("output") -> "上游返回 Responses 格式响应，请将接口协议切换为 Responses"
        body.has("content") -> "上游返回 Anthropic 格式响应，请将接口协议切换为 Anthropic"
        else -> fallback
    }

    private suspend fun execute(request: Request, timeoutSeconds: Int): HttpPayload =
        withContext(Dispatchers.IO) {
            val call = client.newBuilder()
                .callTimeout((timeoutSeconds + CLIENT_GRACE_SECONDS).toLong(), TimeUnit.SECONDS)
                .build()
                .newCall(request)
            call.awaitResponse().use { response ->
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

    private fun errorForThrowable(error: Throwable): TestError = when (error) {
        is InterruptedIOException -> TestError(ErrorKind.TIMEOUT, "请求超时")
        is IOException -> TestError(ErrorKind.NETWORK, "网络连接失败")
        else -> TestError(ErrorKind.OTHER, error.message?.take(MAX_ERROR_CHARS) ?: "请求失败")
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

    private fun String.redactSecrets(): String = replace(
        Regex("(?i)(bearer\\s+|x-api-key[=:]\\s*|api[_-]?key[=:]\\s*)[^\\s,}]+"),
        "$1***",
    )

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

    private fun normalizedBaseUrl(raw: String): String? {
        val candidate = raw.trim()
            .let { if (it.startsWith("https://", ignoreCase = true)) it else "https://$it" }
            .trimEnd('/')
        val parsed = candidate.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" || parsed.host.isBlank()) return null
        return parsed.toString().trimEnd('/')
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }
        })
    }

    private fun ResponseBody.readLimitedUtf8(): String {
        source().use { source ->
            val buffer = Buffer()
            while (source.read(buffer, READ_CHUNK_BYTES) != -1L) {
                if (buffer.size > MAX_RESPONSE_BYTES) {
                    throw IOException("响应体过大")
                }
            }
            return buffer.readUtf8()
        }
    }

    private data class HttpPayload(
        val status: Int,
        val body: String,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val USER_AGENT = "RelayTesterAndroid/1.0.0"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val CLIENT_GRACE_SECONDS = 5
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_RESPONSE_BYTES = 1_048_576L
        const val READ_CHUNK_BYTES = 8_192L
        const val MAX_ERROR_CHARS = 300
    }
}
