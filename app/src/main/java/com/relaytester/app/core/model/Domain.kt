package com.relaytester.app.core.model

import androidx.compose.runtime.Immutable
import java.util.UUID

enum class RelayProtocol(val label: String) {
    CHAT_COMPLETIONS("Chat"),
    RESPONSES("Responses"),
    ANTHROPIC("Anthropic"),
}

@Immutable
data class SupplierProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val protocol: RelayProtocol,
    val apiKeySecretId: String?,
    val models: List<String>,
    val testSettings: TestSettings,
    /** The balance template selected for this supplier. The API key remains in Keystore. */
    val balanceTemplateId: String? = null,
    /** Optional PAT used only by balance templates that reference {{accessToken}}. */
    val balanceAccessTokenSecretId: String? = null,
    /** Non-secret account identifier used by templates that reference {{userId}}. */
    val balanceUserId: String = "",
) {
    companion object {
        fun empty(index: Int = 1): SupplierProfile = SupplierProfile(
            id = UUID.randomUUID().toString(),
            name = "供应商 $index",
            baseUrl = "",
            protocol = RelayProtocol.CHAT_COMPLETIONS,
            apiKeySecretId = null,
            models = emptyList(),
            testSettings = TestSettings(),
            balanceTemplateId = BalanceQueryTemplate.NEW_API_TEMPLATE_ID,
        )
    }
}

/** A model source maps a user-facing model entry to one configured supplier. */
@Immutable
data class ModelSource(
    val supplierId: String,
    val modelId: String,
)

/** Cross-supplier model entry used by the unified connection check panel. */
@Immutable
data class ModelCatalogEntry(
    val id: String,
    val name: String,
    val sources: List<ModelSource> = emptyList(),
)

/**
 * A deliberately small balance-query contract.
 *
 * Form templates describe HTTP and JSON-path fields directly. Script templates
 * use the constrained request/extractor contract enforced by [BalanceQueryMode].
 */
enum class BalanceHttpMethod(val label: String) {
    GET("GET"),
    POST("POST"),
}

enum class BalanceQueryMode(val label: String) {
    FORM("参数配置"),
    SCRIPT("查询脚本"),
}

@Immutable
data class BalanceTemplateHeader(
    val name: String,
    val valueTemplate: String,
)

@Immutable
data class BalanceQueryTemplate(
    val id: String,
    val name: String,
    val description: String,
    val queryMode: BalanceQueryMode = BalanceQueryMode.FORM,
    /** A restricted JavaScript object expression used only when [queryMode] is SCRIPT. */
    val scriptCode: String? = null,
    val method: BalanceHttpMethod,
    val endpointTemplate: String,
    val headers: List<BalanceTemplateHeader>,
    val requestBodyTemplate: String? = null,
    /** Required simple JSON path, e.g. `data.quota`. */
    val availablePath: String,
    val usedPath: String? = null,
    val totalPath: String? = null,
    /** When no total path exists, some APIs expose it as remaining + used. */
    val deriveTotalFromAvailableAndUsed: Boolean = false,
    val currencyPath: String? = null,
    val planNamePath: String? = null,
    val unitLabel: String = "额度",
    /** Convert raw upstream quota to its displayed unit. Must be positive. */
    val scaleDivisor: Double = 1.0,
    /** Optional response field used to verify an API-level success flag. */
    val successPath: String? = null,
    val successExpectedValue: String? = null,
    val builtIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val NEW_API_TEMPLATE_ID = "builtin-new-api-v1"

        /**
         * Matches the new-api account query. A PAT authorizes the request;
         * `New-Api-User` is optional because some deployments infer the user
         * from the token. `quota` is remaining quota and `used_quota` is
         * accumulated consumption.
         */
        fun newApiDefault(): BalanceQueryTemplate = BalanceQueryTemplate(
            id = NEW_API_TEMPLATE_ID,
            name = "new-api 余额查询",
            description = "使用访问令牌读取账户余额与用量",
            method = BalanceHttpMethod.GET,
            endpointTemplate = "/api/user/self",
            headers = listOf(
                BalanceTemplateHeader("Content-Type", "application/json"),
                BalanceTemplateHeader("Authorization", "Bearer {{accessToken}}"),
                BalanceTemplateHeader("User-Agent", "RelayTester/1.0"),
                BalanceTemplateHeader("New-Api-User", "{{userId}}"),
            ),
            availablePath = "data.quota",
            usedPath = "data.used_quota",
            deriveTotalFromAvailableAndUsed = true,
            unitLabel = "USD",
            scaleDivisor = 500_000.0,
            successPath = "success",
            successExpectedValue = "true",
            planNamePath = "data.group",
            builtIn = true,
        )
    }
}

@Immutable
data class BalanceSnapshot(
    val supplierId: String,
    val templateId: String,
    val templateName: String,
    val availableRaw: Double,
    val usedRaw: Double? = null,
    val totalRaw: Double? = null,
    val currency: String? = null,
    val planName: String? = null,
    val unitLabel: String,
    val scaleDivisor: Double,
    val checkedAt: Long,
    val latencyMs: Long,
)

sealed interface BalanceQueryResult {
    data class Success(val snapshot: BalanceSnapshot) : BalanceQueryResult
    data class Failure(
        val message: String,
        val httpStatus: Int? = null,
    ) : BalanceQueryResult
}

@Immutable
data class TestSettings(
    val timeoutSeconds: Int = 20,
    val concurrency: Int = 2,
    val prompt: String = "ping",
    val keyword: String = "",
    /** Legacy per-supplier copy retained for backward-compatible imports. */
    val quickFilterTerms: List<String> = emptyList(),
    val maxTokens: Int = 4,
    val retryCount: Int = 0,
    val delayMinMs: Long = 500,
    val delayMaxMs: Long = 2_000,
    val batchSize: Int = 10,
    val batchPauseMs: Long = 3_000,
)

private const val MAX_MODEL_FILTER_TERM_LENGTH = 128
private const val MAX_QUICK_FILTER_TERMS = 16

/**
 * Splits the model-search expression into OR terms. The primary separator is
 * the English half-width comma; pipe variants remain accepted for backward
 * compatibility with older saved configurations.
 */
fun parseModelFilterTerms(value: String): List<String> = value
    .split(',', '|', '，', '｜')
    .asSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .map { it.take(MAX_MODEL_FILTER_TERM_LENGTH) }
    .distinctBy { it.lowercase() }
    .take(MAX_QUICK_FILTER_TERMS)
    .toList()

/** Normalizes persisted one-tap filters without treating their labels as expressions. */
fun normalizeQuickFilterTerms(values: Iterable<String>): List<String> = values
    .asSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .map { it.take(MAX_MODEL_FILTER_TERM_LENGTH) }
    .distinctBy { it.lowercase() }
    .take(MAX_QUICK_FILTER_TERMS)
    .toList()

fun mergeModelFilterTerms(
    expression: String,
    selectedQuickTerms: Iterable<String> = emptyList(),
): List<String> = normalizeQuickFilterTerms(parseModelFilterTerms(expression) + selectedQuickTerms)

fun String.matchesAnyModelFilterTerm(terms: Collection<String>): Boolean =
    terms.isEmpty() || terms.any { term -> contains(term, ignoreCase = true) }

@Immutable
data class BatchTestConfig(
    val supplier: SupplierProfile,
    val apiKey: String,
    val models: List<String>,
    val settings: TestSettings,
)

enum class TestStatus {
    PENDING,
    SUCCESS,
    FAILED,
}

enum class ErrorKind(val label: String) {
    AUTHENTICATION("认证失败"),
    INSUFFICIENT_QUOTA("余额不足"),
    FORBIDDEN("无权限"),
    MODEL_NOT_FOUND("模型不存在"),
    RATE_LIMITED("限流"),
    TIMEOUT("请求超时"),
    NETWORK("网络连接失败"),
    INVALID_RESPONSE("响应格式异常"),
    UPSTREAM("上游错误"),
    OTHER("其他错误"),
}

@Immutable
data class TestError(
    val kind: ErrorKind,
    val message: String,
)

@Immutable
data class TokenUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
) {
    val resolvedTotal: Long
        get() = totalTokens ?: ((inputTokens ?: 0) + (outputTokens ?: 0))
}

@Immutable
data class ModelTestResult(
    val model: String,
    val status: TestStatus,
    val latencyMs: Long? = null,
    val httpStatus: Int? = null,
    val finishReason: String? = null,
    val usage: TokenUsage? = null,
    val error: TestError? = null,
) {
    companion object {
        fun pending(model: String): ModelTestResult = ModelTestResult(
            model = model,
            status = TestStatus.PENDING,
        )
    }
}

@Immutable
data class TestRunSummary(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val elapsedMs: Long,
    val averageLatencyMs: Long?,
    val fastestLatencyMs: Long?,
    val slowestLatencyMs: Long?,
    val totalTokens: Long,
    val errorCounts: Map<ErrorKind, Int>,
)

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: TestError, val httpStatus: Int? = null) : ApiResult<Nothing>
}
