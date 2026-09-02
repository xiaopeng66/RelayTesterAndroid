package com.relaytester.app.core.network

import com.relaytester.app.core.model.BalanceHttpMethod
import com.relaytester.app.core.model.BalanceQueryMode
import com.relaytester.app.core.model.BalanceQueryResult
import com.relaytester.app.core.model.BalanceQueryTemplate
import com.relaytester.app.core.model.BalanceSnapshot
import com.relaytester.app.core.model.BalanceTemplateHeader
import com.relaytester.app.core.model.SupplierProfile
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

/** Executes a saved declarative balance template without logging request secrets or response bodies. */
class BalanceApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .build(),
) {
    private val scriptRuntime = BalanceQueryScript()

    suspend fun query(
        profile: SupplierProfile,
        apiKey: String,
        accessToken: String,
        userId: String,
        template: BalanceQueryTemplate,
    ): BalanceQueryResult {
        val validation = validateTemplate(template)
        if (validation != null) return BalanceQueryResult.Failure(validation)
        if (template.queryMode == BalanceQueryMode.SCRIPT) {
            return queryWithScript(
                profile = profile,
                apiKey = apiKey,
                accessToken = accessToken,
                userId = userId,
                template = template,
            )
        }
        if (template.requiresPlaceholder("{{apiKey}}") && apiKey.isBlank()) {
            return BalanceQueryResult.Failure("该模板需要模型测试页保存的 API Key")
        }
        if (template.requiresPlaceholder("{{accessToken}}") && accessToken.isBlank()) {
            return BalanceQueryResult.Failure("请先配置余额查询访问令牌（PAT）")
        }
        return try {
            val baseUrl = profile.baseUrl.trim().toHttpUrlOrNull()
                ?.takeIf { it.isHttps }
                ?: return BalanceQueryResult.Failure("Base URL 必须是有效的 HTTPS 地址")
            val endpoint = resolveEndpoint(baseUrl, template.endpointTemplate, apiKey, accessToken, userId)
                ?: return BalanceQueryResult.Failure("模板地址无效，或目标主机与供应商不一致")
            val request = buildRequest(endpoint, baseUrl, apiKey, accessToken, userId, template)
            val startedAt = System.nanoTime()
            val response = execute(request, profile.testSettings.timeoutSeconds)
            val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            response.use { safeResponse ->
                if (safeResponse.code !in 200..299) {
                    return BalanceQueryResult.Failure(
                        message = "站点返回 HTTP ${safeResponse.code}",
                        httpStatus = safeResponse.code,
                    )
                }
                val body = safeResponse.readLimitedBody()
                val root = parseJson(body) ?: return BalanceQueryResult.Failure("站点未返回有效 JSON")
                if (!matchesSuccessFlag(root, template)) {
                    return BalanceQueryResult.Failure(root.readFailureMessage())
                }
                val available = readPath(root, template.availablePath).asFiniteNumber()
                    ?: return BalanceQueryResult.Failure("未能从 ${template.availablePath} 读取数值余额")
                val used = template.usedPath?.let { readPath(root, it).asFiniteNumber() }
                val total = template.totalPath?.let { readPath(root, it).asFiniteNumber() }
                    ?: if (template.deriveTotalFromAvailableAndUsed) {
                        used?.let { available + it }
                    } else {
                        null
                    }
                val currency = template.currencyPath
                    ?.let { readPath(root, it) }
                    ?.asDisplayText()
                    ?.takeIf(String::isNotBlank)
                val planName = template.planNamePath
                    ?.let { readPath(root, it) }
                    ?.asDisplayText()
                    ?.takeIf(String::isNotBlank)
                    ?: template.planNamePath?.takeIf(String::isNotBlank)?.let { "默认套餐" }
                BalanceQueryResult.Success(
                    BalanceSnapshot(
                        supplierId = profile.id,
                        templateId = template.id,
                        templateName = template.name,
                        availableRaw = available,
                        usedRaw = used,
                        totalRaw = total,
                        currency = currency,
                        planName = planName,
                        unitLabel = template.unitLabel,
                        scaleDivisor = template.scaleDivisor,
                        checkedAt = System.currentTimeMillis(),
                        latencyMs = latencyMs,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: TemplateException) {
            BalanceQueryResult.Failure(error.message ?: "模板配置无效")
        } catch (error: InterruptedIOException) {
            BalanceQueryResult.Failure("查询超时，请检查站点或提高超时设置")
        } catch (error: IOException) {
            BalanceQueryResult.Failure("网络连接失败，请检查站点地址和网络")
        } catch (_: Throwable) {
            // Deliberately do not surface raw upstream content, URLs, or headers.
            BalanceQueryResult.Failure("余额查询失败，请检查模板与站点配置")
        }
    }

    /** Returns a display-safe validation message, never an expanded request or secret. */
    fun validateTemplate(template: BalanceQueryTemplate): String? {
        if (template.name.trim().isEmpty()) return "请填写模板名称"
        if (template.queryMode == BalanceQueryMode.SCRIPT) {
            val script = template.scriptCode.orEmpty()
            val scriptError = scriptRuntime.validate(script)
            if (scriptError != null) return scriptError
            val request = runCatching { scriptRuntime.compileRequest(script) }
                .getOrElse { return "查询脚本无效，请检查 request 与 extractor" }
            if (containsUnsupportedPlaceholder(request.urlTemplate) ||
                request.headers.values.any(::containsUnsupportedPlaceholder) ||
                request.bodyTemplate?.let(::containsUnsupportedPlaceholder) == true
            ) {
                return "模板只支持 {{baseUrl}}、{{apiKey}}、{{accessToken}}、{{userId}} 和 {{nowEpochMs}} 占位符"
            }
            return validateHeaders(request.headers.map { BalanceTemplateHeader(it.key, it.value) })
        }
        if (template.endpointTemplate.trim().isEmpty()) return "请填写请求地址"
        if (template.availablePath.trim().isEmpty()) return "请填写可用余额的 JSON 路径"
        if (!template.scaleDivisor.isFinite() || template.scaleDivisor <= 0) return "换算除数必须大于 0"
        if (template.method == BalanceHttpMethod.GET && !template.requestBodyTemplate.isNullOrBlank()) {
            return "GET 模板不能填写请求 Body，请改用 POST"
        }
        if (template.successExpectedValue != null && template.successPath.isNullOrBlank()) {
            return "设置成功预期值时也要填写成功标记路径"
        }
        val templatedValues = buildList {
            add(template.endpointTemplate)
            addAll(template.headers.map { it.valueTemplate })
            template.requestBodyTemplate?.let(::add)
        }
        if (templatedValues.any(::containsUnsupportedPlaceholder)) {
            return "模板只支持 {{baseUrl}}、{{apiKey}}、{{accessToken}}、{{userId}} 和 {{nowEpochMs}} 占位符"
        }
        return validateHeaders(template.headers)
    }

    private suspend fun queryWithScript(
        profile: SupplierProfile,
        apiKey: String,
        accessToken: String,
        userId: String,
        template: BalanceQueryTemplate,
    ): BalanceQueryResult {
        return try {
            val script = template.scriptCode.orEmpty()
            val scriptRequest = scriptRuntime.compileRequest(script)
            if (scriptRequest.requiresPlaceholder("{{apiKey}}") && apiKey.isBlank()) {
                return BalanceQueryResult.Failure("该模板需要模型测试页保存的 API Key")
            }
            if (scriptRequest.requiresPlaceholder("{{accessToken}}") && accessToken.isBlank()) {
                return BalanceQueryResult.Failure("请先配置余额查询访问令牌（PAT）")
            }
            val baseUrl = profile.baseUrl.trim().toHttpUrlOrNull()
                ?.takeIf { it.isHttps }
                ?: return BalanceQueryResult.Failure("Base URL 必须是有效的 HTTPS 地址")
            val endpoint = resolveEndpoint(
                baseUrl,
                scriptRequest.urlTemplate,
                apiKey,
                accessToken,
                userId,
            ) ?: return BalanceQueryResult.Failure("模板地址无效，或目标主机与供应商不一致")
            val headers = scriptRequest.headers.map { BalanceTemplateHeader(it.key, it.value) }
            validateHeaders(headers)?.let { return BalanceQueryResult.Failure(it) }
            val request = buildResolvedRequest(
                endpoint = endpoint,
                baseUrl = baseUrl,
                apiKey = apiKey,
                accessToken = accessToken,
                userId = userId,
                method = if (scriptRequest.method == "POST") BalanceHttpMethod.POST else BalanceHttpMethod.GET,
                headers = headers,
                requestBodyTemplate = scriptRequest.bodyTemplate,
            )
            val startedAt = System.nanoTime()
            val response = execute(request, profile.testSettings.timeoutSeconds)
            val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            response.use { safeResponse ->
                if (safeResponse.code !in 200..299) {
                    return BalanceQueryResult.Failure(
                        message = "站点返回 HTTP ${safeResponse.code}",
                        httpStatus = safeResponse.code,
                    )
                }
                val body = safeResponse.readLimitedBody()
                parseJson(body) ?: return BalanceQueryResult.Failure("站点未返回有效 JSON")
                val extracted = scriptRuntime.extract(script, body)
                BalanceQueryResult.Success(
                    BalanceSnapshot(
                        supplierId = profile.id,
                        templateId = template.id,
                        templateName = template.name,
                        availableRaw = extracted.remaining,
                        usedRaw = extracted.used,
                        totalRaw = extracted.total,
                        currency = extracted.currency,
                        planName = extracted.planName,
                        unitLabel = extracted.unit,
                        scaleDivisor = 1.0,
                        checkedAt = System.currentTimeMillis(),
                        latencyMs = latencyMs,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: BalanceScriptException) {
            BalanceQueryResult.Failure(error.message ?: "查询脚本无效")
        } catch (error: TemplateException) {
            BalanceQueryResult.Failure(error.message ?: "模板配置无效")
        } catch (error: InterruptedIOException) {
            BalanceQueryResult.Failure("查询超时，请检查站点或提高超时设置")
        } catch (error: IOException) {
            BalanceQueryResult.Failure("网络连接失败，请检查站点地址和网络")
        } catch (_: Throwable) {
            BalanceQueryResult.Failure("余额查询失败，请检查查询脚本与站点配置")
        }
    }

    private fun buildRequest(
        endpoint: HttpUrl,
        baseUrl: HttpUrl,
        apiKey: String,
        accessToken: String,
        userId: String,
        template: BalanceQueryTemplate,
    ): Request = buildResolvedRequest(
        endpoint = endpoint,
        baseUrl = baseUrl,
        apiKey = apiKey,
        accessToken = accessToken,
        userId = userId,
        method = template.method,
        headers = template.headers,
        requestBodyTemplate = template.requestBodyTemplate,
    )

    private fun buildResolvedRequest(
        endpoint: HttpUrl,
        baseUrl: HttpUrl,
        apiKey: String,
        accessToken: String,
        userId: String,
        method: BalanceHttpMethod,
        headers: List<BalanceTemplateHeader>,
        requestBodyTemplate: String?,
    ): Request {
        val builder = Request.Builder().url(endpoint)
        headers.forEach { header ->
            val resolvedValue = resolvePlaceholders(
                header.valueTemplate,
                baseUrl,
                apiKey,
                accessToken,
                userId,
            ).trim()
            // Optional placeholders (for example New-Api-User) must not
            // become an empty HTTP header. Omitting it lets deployments that
            // infer the account from the PAT work without a user ID.
            if (resolvedValue.isNotEmpty()) {
                builder.header(header.name.trim(), resolvedValue)
            }
        }
        return when (method) {
            BalanceHttpMethod.GET -> builder.get().build()
            BalanceHttpMethod.POST -> {
                val requestBody = resolvePlaceholders(
                    requestBodyTemplate.orEmpty(),
                    baseUrl,
                    apiKey,
                    accessToken,
                    userId,
                )
                    .toRequestBody(JSON_MEDIA_TYPE)
                builder.post(requestBody).build()
            }
        }
    }

    private fun validateHeaders(headers: List<BalanceTemplateHeader>): String? {
        headers.forEach { header ->
            val name = header.name.trim()
            if (name.isEmpty() || !HEADER_NAME.matches(name)) return "请求头名称无效"
            if (name.lowercase() in FORBIDDEN_HEADERS) return "请求头 $name 不能由模板覆盖"
            if (header.valueTemplate.contains('\n') || header.valueTemplate.contains('\r')) {
                return "请求头值不能包含换行"
            }
            if (name.requiresCredentialPlaceholder() && !header.valueTemplate.containsCredentialPlaceholder()) {
                return "认证请求头请使用 {{apiKey}} 或 {{accessToken}}，不要把密钥保存进模板"
            }
        }
        return null
    }

    private fun resolveEndpoint(
        baseUrl: HttpUrl,
        rawTemplate: String,
        apiKey: String,
        accessToken: String,
        userId: String,
    ): HttpUrl? {
        val value = resolvePlaceholders(rawTemplate, baseUrl, apiKey, accessToken, userId).trim()
        if (value.isEmpty() || UNRESOLVED_PLACEHOLDER.containsMatchIn(value)) return null
        val rootUrl = baseUrl.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
        val candidate = value.toHttpUrlOrNull()
            ?: if (value.startsWith('/')) rootUrl.resolve(value) else baseUrl.resolve(value)
        val resolved = candidate ?: return null
        return resolved.takeIf {
            it.isHttps && it.host == baseUrl.host && it.port == baseUrl.port
        }
    }

    private fun resolvePlaceholders(
        value: String,
        baseUrl: HttpUrl,
        apiKey: String,
        accessToken: String,
        userId: String,
    ): String = value
        .replace("{{baseUrl}}", baseUrl.toString().trimEnd('/'))
        .replace("{{apiKey}}", apiKey)
        .replace("{{accessToken}}", accessToken)
        .replace("{{userId}}", userId)
        .replace("{{nowEpochMs}}", System.currentTimeMillis().toString())

    private fun containsUnsupportedPlaceholder(value: String): Boolean = UNRESOLVED_PLACEHOLDER
        .findAll(value)
        .any { it.value !in SUPPORTED_PLACEHOLDERS }

    private fun String.requiresCredentialPlaceholder(): Boolean {
        val lower = lowercase()
        return lower in SENSITIVE_HEADER_NAMES || SENSITIVE_HEADER_HINTS.any(lower::contains)
    }

    private fun String.containsCredentialPlaceholder(): Boolean =
        contains("{{apiKey}}") || contains("{{accessToken}}")

    private fun BalanceQueryTemplate.requiresPlaceholder(placeholder: String): Boolean = buildList {
        add(endpointTemplate)
        addAll(headers.map { it.valueTemplate })
        requestBodyTemplate?.let(::add)
    }.any { it.contains(placeholder) }

    private fun BalanceQueryScript.RequestSpec.requiresPlaceholder(placeholder: String): Boolean = buildList {
        add(urlTemplate)
        addAll(headers.values)
        bodyTemplate?.let(::add)
    }.any { it.contains(placeholder) }

    private suspend fun execute(request: Request, timeoutSeconds: Int): Response {
        val call = client.newCall(request)
        call.timeout().timeout(timeoutSeconds.coerceIn(3, 120).toLong(), TimeUnit.SECONDS)
        return call.await()
    }

    private fun parseJson(body: String): Any? = runCatching {
        val trimmed = body.trim()
        when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> null
        }
    }.getOrNull()

    private fun matchesSuccessFlag(root: Any, template: BalanceQueryTemplate): Boolean {
        val path = template.successPath?.trim().orEmpty()
        val expected = template.successExpectedValue?.trim().orEmpty()
        if (path.isEmpty() || expected.isEmpty()) return true
        return readPath(root, path).asDisplayText()?.equals(expected, ignoreCase = true) == true
    }

    private fun Any.readFailureMessage(): String = (this as? JSONObject)
        ?.optString("message")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.redactSecrets()
        ?.take(MAX_FAILURE_MESSAGE_CHARS)
        ?: "接口返回的成功标记与模板配置不一致"

    private fun String.redactSecrets(): String = replace(
        Regex("(?i)(bearer\\s+|x-api-key[=:]\\s*|api[_-]?key[=:]\\s*|access[_-]?token[=:]\\s*)[^\\s,}]+"),
        "\$1***",
    )

    /** Supports dot properties and zero-based array indices, such as data.items[0].quota. */
    private fun readPath(root: Any, rawPath: String): Any? {
        val path = rawPath.trim().removePrefix("$").removePrefix(".")
        if (path.isBlank()) return root
        var current: Any? = root
        var cursor = 0
        for (match in PATH_TOKEN.findAll(path)) {
            if (match.range.first != cursor && !(match.range.first == cursor + 1 && path[cursor] == '.')) {
                return null
            }
            cursor = match.range.last + 1
            current = when {
                match.groups[1] != null -> (current as? JSONObject)?.opt(match.groups[1]!!.value)
                match.groups[2] != null -> (current as? JSONArray)?.opt(match.groups[2]!!.value.toInt())
                else -> null
            }
            if (current == null || current == JSONObject.NULL) return null
        }
        return current.takeIf { cursor == path.length }
    }

    private fun Any?.asFiniteNumber(): Double? = when (this) {
        is Number -> toDouble().takeIf(Double::isFinite)
        is String -> trim().toDoubleOrNull()?.takeIf(Double::isFinite)
        else -> null
    }

    private fun Any?.asDisplayText(): String? = when (this) {
        null, JSONObject.NULL -> null
        is String -> this
        else -> toString()
    }

    private fun Response.readLimitedBody(): String {
        val source = body?.source() ?: return ""
        val buffer = Buffer()
        var remaining = MAX_RESPONSE_BYTES.toLong()
        while (remaining > 0) {
            val read = source.read(buffer, minOf(8_192L, remaining))
            if (read == -1L) return buffer.readUtf8()
            remaining -= read
        }
        if (source.read(Buffer(), 1) != -1L) throw TemplateException("站点响应过大，已拒绝解析")
        return buffer.readUtf8()
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private class TemplateException(message: String) : IllegalArgumentException(message)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        // Both closing braces must be escaped for Android's ICU regex engine.
        // The previous expression compiled on the host JDK but threw a
        // PatternSyntaxException when this companion object initialized on
        // Android, crashing the app during ViewModel construction.
        val UNRESOLVED_PLACEHOLDER = Regex("\\{\\{[^}]+\\}\\}")
        val PATH_TOKEN = Regex("([A-Za-z0-9_-]+)|\\[([0-9]+)]")
        val FORBIDDEN_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding")
        val SENSITIVE_HEADER_NAMES = setOf(
            "authorization",
            "x-api-key",
            "api-key",
            "x-auth-token",
            "cookie",
        )
        val SENSITIVE_HEADER_HINTS = listOf("auth", "token", "secret", "key", "cookie", "password")
        val SUPPORTED_PLACEHOLDERS = setOf(
            "{{baseUrl}}",
            "{{apiKey}}",
            "{{accessToken}}",
            "{{userId}}",
            "{{nowEpochMs}}",
        )
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val MAX_FAILURE_MESSAGE_CHARS = 200
    }
}
