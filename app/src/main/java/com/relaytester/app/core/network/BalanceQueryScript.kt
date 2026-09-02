package com.relaytester.app.core.network

import com.quickjs.QuickJS
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes the narrow cc-switch-style script contract used by balance templates.
 * Scripts can describe one request and map one JSON response, but do not receive
 * Android objects, network APIs, credentials, or any native callback.
 */
internal class BalanceQueryScript {
    data class RequestSpec(
        val urlTemplate: String,
        val method: String,
        val headers: Map<String, String>,
        val bodyTemplate: String?,
    )

    data class ExtractedBalance(
        val remaining: Double,
        val used: Double?,
        val total: Double?,
        val unit: String,
        val currency: String?,
        val planName: String?,
    )

    fun validate(source: String?): String? = runCatching {
        compileRequest(source.orEmpty())
        null
    }.getOrElse { error ->
        (error as? BalanceScriptException)?.message ?: "查询脚本无效，请检查语法与返回结构"
    }

    fun compileRequest(source: String): RequestSpec {
        validateSource(source)
        val json = evaluate(source, responseJson = null)
        val root = runCatching { JSONObject(json) }
            .getOrElse { throw BalanceScriptException("查询脚本必须返回 request 与 extractor") }
        val request = root.optJSONObject("request")
            ?: throw BalanceScriptException("查询脚本缺少 request 对象")
        val url = request.optString("url").trim()
        if (url.isEmpty()) throw BalanceScriptException("查询脚本缺少 request.url")
        val method = request.optString("method", "GET").trim().uppercase()
        if (method !in setOf("GET", "POST")) {
            throw BalanceScriptException("查询脚本只支持 GET 或 POST 请求")
        }
        val headerObject = request.optJSONObject("headers") ?: JSONObject()
        if (headerObject.length() > MAX_HEADERS) {
            throw BalanceScriptException("查询脚本的请求头数量超出限制")
        }
        val headers = buildMap {
            headerObject.keys().forEach { name ->
                val value = headerObject.opt(name)
                if (value !is String) {
                    throw BalanceScriptException("查询脚本的请求头值必须是字符串")
                }
                put(name, value)
            }
        }
        val rawBody = request.opt("body")
        val body = when (rawBody) {
            null, JSONObject.NULL -> null
            is String -> rawBody
            is JSONObject, is JSONArray -> rawBody.toString()
            else -> throw BalanceScriptException("查询脚本的 request.body 必须是字符串或 JSON")
        }
        if (method == "GET" && !body.isNullOrBlank()) {
            throw BalanceScriptException("GET 查询脚本不能填写 request.body")
        }
        return RequestSpec(url, method, headers, body)
    }

    fun extract(source: String, responseJson: String): ExtractedBalance {
        validateSource(source)
        val json = evaluate(source, responseJson)
        val root = runCatching { JSONObject(json) }
            .getOrElse { throw BalanceScriptException("查询脚本的 extractor 必须返回对象") }
        if (!root.optBoolean("isValid", true)) {
            throw BalanceScriptException(
                root.optString("invalidMessage", "接口返回的数据不符合查询脚本预期")
                    .trim()
                    .ifBlank { "接口返回的数据不符合查询脚本预期" }
                    .redactSecrets()
                    .take(MAX_FAILURE_MESSAGE_CHARS),
            )
        }
        val remaining = root.opt("remaining").asFiniteNumber()
            ?: throw BalanceScriptException("查询脚本的 extractor 必须返回有限的 remaining 数值")
        val used = root.opt("used").asFiniteNumber()
        val total = root.opt("total").asFiniteNumber()
        return ExtractedBalance(
            remaining = remaining,
            used = used,
            total = total,
            unit = root.optString("unit", "额度").trim().ifBlank { "额度" }.take(MAX_LABEL_LENGTH),
            currency = root.optString("currency").trim().takeIf(String::isNotBlank)?.take(MAX_LABEL_LENGTH),
            planName = root.optString("planName").trim().takeIf(String::isNotBlank)?.take(MAX_LABEL_LENGTH),
        )
    }

    private fun evaluate(source: String, responseJson: String?): String {
        val program = if (responseJson == null) {
            """
                "use strict";
                const __relayStringify = JSON.stringify.bind(JSON);
                const __relaySpec = (
                $source
                );
                if (!__relaySpec || typeof __relaySpec !== "object" ||
                    !__relaySpec.request || typeof __relaySpec.request !== "object" ||
                    typeof __relaySpec.extractor !== "function") {
                    throw new Error("Invalid relay balance script");
                }
                __relayStringify({ request: __relaySpec.request });
            """.trimIndent()
        } else {
            """
                "use strict";
                const __relayStringify = JSON.stringify.bind(JSON);
                const __relayParse = JSON.parse.bind(JSON);
                const __relaySpec = (
                $source
                );
                if (!__relaySpec || typeof __relaySpec !== "object" ||
                    typeof __relaySpec.extractor !== "function") {
                    throw new Error("Invalid relay balance script");
                }
                const __relayResult = __relaySpec.extractor(__relayParse(${JSONObject.quote(responseJson)}));
                if (!__relayResult || typeof __relayResult !== "object") {
                    throw new Error("Invalid extractor result");
                }
                __relayStringify(__relayResult);
            """.trimIndent()
        }
        val runtime = QuickJS.createRuntime()
        val context = runtime.createContext()
        return try {
            val result = context.executeStringScript(program, "relay-balance-script.js")
                ?.takeIf(String::isNotBlank)
                ?: throw BalanceScriptException("查询脚本未返回有效结果")
            if (result.length > MAX_SCRIPT_OUTPUT_CHARS) {
                throw BalanceScriptException("查询脚本输出超过限制")
            }
            result
        } catch (error: BalanceScriptException) {
            throw error
        } catch (_: Throwable) {
            throw BalanceScriptException("查询脚本执行失败，请检查语法与返回字段")
        } finally {
            runCatching { context.close() }
            runCatching { runtime.close() }
        }
    }

    private fun validateSource(source: String) {
        if (source.isBlank()) throw BalanceScriptException("请填写查询脚本")
        if (source.length > MAX_SCRIPT_CHARS) throw BalanceScriptException("查询脚本超过 16 KB 限制")
        val blocked = FORBIDDEN_TOKENS.find(source)?.value
        if (blocked != null) {
            throw BalanceScriptException("查询脚本不能使用 $blocked；仅支持请求描述与响应映射")
        }
    }

    private fun Any?.asFiniteNumber(): Double? = when (this) {
        is Number -> toDouble().takeIf(Double::isFinite)
        is String -> trim().toDoubleOrNull()?.takeIf(Double::isFinite)
        else -> null
    }

    private fun String.redactSecrets(): String = replace(
        Regex("(?i)(bearer\\s+|x-api-key[=:]\\s*|api[_-]?key[=:]\\s*|access[_-]?token[=:]\\s*)[^\\s,}]+"),
        "\$1***",
    )

    private companion object {
        const val MAX_SCRIPT_CHARS = 16 * 1024
        const val MAX_SCRIPT_OUTPUT_CHARS = 16 * 1024
        const val MAX_HEADERS = 32
        const val MAX_LABEL_LENGTH = 256
        const val MAX_FAILURE_MESSAGE_CHARS = 256
        val FORBIDDEN_TOKENS = Regex(
            """(?i)\b(?:while|for|do|eval|function\s*\*|new\s+|class|import|require|fetch|xmlhttprequest|websocket|worker|promise|settimeout|setinterval|async|await|yield|constructor|__proto__|prototype|repeat|padstart|padend|fill)\b""",
        )
    }
}

internal class BalanceScriptException(message: String) : Exception(message)
