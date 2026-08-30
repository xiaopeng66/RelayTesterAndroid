package com.relaytester.app.feature.tester

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.relaytester.app.core.backup.ConfigurationBackup
import com.relaytester.app.core.backup.ConfigurationBackupCodec
import com.relaytester.app.core.backup.ConfigurationBackupException
import com.relaytester.app.core.backup.ConfigurationBackupPreview
import com.relaytester.app.core.backup.ConfigurationBackupSupplier
import com.relaytester.app.core.model.ApiResult
import com.relaytester.app.core.model.BalanceHttpMethod
import com.relaytester.app.core.model.BalanceQueryResult
import com.relaytester.app.core.model.BalanceQueryTemplate
import com.relaytester.app.core.model.BalanceSnapshot
import com.relaytester.app.core.model.BalanceTemplateHeader
import com.relaytester.app.core.model.BatchTestConfig
import com.relaytester.app.core.model.ModelTestResult
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestRunSummary
import com.relaytester.app.core.model.TestSettings
import com.relaytester.app.core.model.TestStatus
import com.relaytester.app.core.network.BalanceApi
import com.relaytester.app.core.network.RelayApi
import com.relaytester.app.core.security.KeystoreSecretStore
import com.relaytester.app.core.security.SecretStore
import com.relaytester.app.core.storage.SupplierStore
import com.relaytester.app.core.storage.SupplierStoreState
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

enum class SettingField {
    TIMEOUT_SECONDS,
    CONCURRENCY,
    PROMPT,
    KEYWORD,
    MAX_TOKENS,
    RETRY_COUNT,
    DELAY_MIN_SECONDS,
    DELAY_MAX_SECONDS,
    BATCH_SIZE,
    BATCH_PAUSE_SECONDS,
}

enum class ResultFilter {
    ALL,
    AVAILABLE,
    FAILED,
}

enum class ResultSort {
    LATENCY,
    NAME,
}

private fun String.toIntOr(default: Int): Int = trim().toIntOrNull() ?: default

private fun String.toMilliseconds(default: Long): Long =
    trim().toDoubleOrNull()?.times(1_000)?.roundToLong() ?: default

private fun Long.toSecondsText(): String = String.format(Locale.ROOT, "%.1f", this / 1_000.0)
    .trimEnd('0')
    .trimEnd('.')

@Immutable
data class SupplierDraft(
    val id: String,
    val name: String,
    val baseUrl: String,
    val protocol: RelayProtocol,
    val apiKey: String,
    val apiKeySecretId: String?,
    val apiKeyDirty: Boolean,
    val balanceTemplateId: String?,
    val models: List<String>,
    val timeoutSeconds: String,
    val concurrency: String,
    val prompt: String,
    val keyword: String,
    val maxTokens: String,
    val retryCount: String,
    val delayMinSeconds: String,
    val delayMaxSeconds: String,
    val batchSize: String,
    val batchPauseSeconds: String,
) {
    fun toProfile(
        apiKeySecretId: String?,
        balanceAccessTokenSecretId: String?,
        balanceUserId: String,
    ): SupplierProfile = SupplierProfile(
        id = id,
        name = name.trim().ifBlank { "未命名供应商" },
        baseUrl = baseUrl.trim(),
        protocol = protocol,
        apiKeySecretId = apiKeySecretId,
        balanceTemplateId = balanceTemplateId,
        balanceAccessTokenSecretId = balanceAccessTokenSecretId,
        balanceUserId = balanceUserId,
        models = models.distinct().sorted(),
        testSettings = TestSettings(
            timeoutSeconds = timeoutSeconds.toIntOr(20).coerceIn(3, 120),
            concurrency = concurrency.toIntOr(2).coerceIn(1, 20),
            prompt = prompt.trim().ifBlank { "ping" },
            keyword = keyword.trim(),
            maxTokens = maxTokens.toIntOr(4).coerceIn(1, 64),
            retryCount = retryCount.toIntOr(0).coerceIn(0, 5),
            delayMinMs = delayMinSeconds.toMilliseconds(500).coerceIn(0, 10_000),
            delayMaxMs = delayMaxSeconds.toMilliseconds(2_000).coerceIn(0, 10_000),
            batchSize = batchSize.toIntOr(10).coerceIn(1, 200),
            batchPauseMs = batchPauseSeconds.toMilliseconds(3_000).coerceIn(0, 60_000),
        ),
    )

    companion object {
        fun from(profile: SupplierProfile, apiKey: String): SupplierDraft = SupplierDraft(
            id = profile.id,
            name = profile.name,
            baseUrl = profile.baseUrl,
            protocol = profile.protocol,
            apiKey = apiKey,
            apiKeySecretId = profile.apiKeySecretId,
            apiKeyDirty = false,
            balanceTemplateId = profile.balanceTemplateId,
            models = profile.models,
            timeoutSeconds = profile.testSettings.timeoutSeconds.toString(),
            concurrency = profile.testSettings.concurrency.toString(),
            prompt = profile.testSettings.prompt,
            keyword = profile.testSettings.keyword,
            maxTokens = profile.testSettings.maxTokens.toString(),
            retryCount = profile.testSettings.retryCount.toString(),
            delayMinSeconds = profile.testSettings.delayMinMs.toSecondsText(),
            delayMaxSeconds = profile.testSettings.delayMaxMs.toSecondsText(),
            batchSize = profile.testSettings.batchSize.toString(),
            batchPauseSeconds = profile.testSettings.batchPauseMs.toSecondsText(),
        )
    }
}

@Immutable
data class FormErrors(
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val rateLimit: String? = null,
)

@Immutable
data class TesterUiState(
    val isInitializing: Boolean = true,
    /** True only while the first active site's Keystore values are read off the main thread. */
    val isSecretsHydrating: Boolean = false,
    val suppliers: List<SupplierProfile> = emptyList(),
    val activeSupplierId: String? = null,
    val draft: SupplierDraft? = null,
    val isFetchingModels: Boolean = false,
    val isRunning: Boolean = false,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val results: List<ModelTestResult> = emptyList(),
    val summary: TestRunSummary? = null,
    val filter: ResultFilter = ResultFilter.ALL,
    val sort: ResultSort = ResultSort.LATENCY,
    val resultQuery: String = "",
    val errors: FormErrors = FormErrors(),
    val message: String? = null,
    val isMessageError: Boolean = false,
)

@Immutable
data class ExportPayload(
    val fileName: String,
    val json: String,
)

/** Encrypted bytes waiting only for Android's system file-creation result. */
data class ConfigurationBackupExportPayload(
    val fileName: String,
    val encryptedBytes: ByteArray,
)

@Immutable
data class ConfigurationBackupUiState(
    val isBusy: Boolean = false,
    val exportPayload: ConfigurationBackupExportPayload? = null,
    val importFileSelected: Boolean = false,
    val importPreview: ConfigurationBackupPreview? = null,
    val message: String? = null,
    val isMessageError: Boolean = false,
)

enum class BalanceTemplateField {
    NAME,
    DESCRIPTION,
    ENDPOINT,
    HEADERS,
    BODY,
    AVAILABLE_PATH,
    USED_PATH,
    TOTAL_PATH,
    PLAN_NAME_PATH,
    CURRENCY_PATH,
    UNIT_LABEL,
    SCALE_DIVISOR,
    SUCCESS_PATH,
    SUCCESS_EXPECTED_VALUE,
}

@Immutable
data class BalanceTemplateDraft(
    val id: String,
    val name: String,
    val description: String,
    val method: BalanceHttpMethod,
    val endpointTemplate: String,
    val headersText: String,
    val requestBodyTemplate: String,
    val availablePath: String,
    val usedPath: String,
    val totalPath: String,
    val deriveTotalFromAvailableAndUsed: Boolean,
    val currencyPath: String,
    val planNamePath: String,
    val unitLabel: String,
    val scaleDivisor: String,
    val successPath: String,
    val successExpectedValue: String,
    val createdAt: Long,
) {
    fun toTemplate(headers: List<BalanceTemplateHeader>, now: Long): BalanceQueryTemplate = BalanceQueryTemplate(
        id = id,
        name = name.trim(),
        description = description.trim(),
        method = method,
        endpointTemplate = endpointTemplate.trim(),
        headers = headers,
        requestBodyTemplate = requestBodyTemplate.trim().takeIf(String::isNotBlank),
        availablePath = availablePath.trim(),
        usedPath = usedPath.trim().takeIf(String::isNotBlank),
        totalPath = totalPath.trim().takeIf(String::isNotBlank),
        deriveTotalFromAvailableAndUsed = deriveTotalFromAvailableAndUsed,
        currencyPath = currencyPath.trim().takeIf(String::isNotBlank),
        planNamePath = planNamePath.trim().takeIf(String::isNotBlank),
        unitLabel = unitLabel.trim().ifBlank { "额度" },
        scaleDivisor = scaleDivisor.trim().toDoubleOrNull() ?: Double.NaN,
        successPath = successPath.trim().takeIf(String::isNotBlank),
        successExpectedValue = successExpectedValue.trim().takeIf(String::isNotBlank),
        builtIn = false,
        createdAt = createdAt,
        updatedAt = now,
    )

    companion object {
        fun empty(): BalanceTemplateDraft = BalanceTemplateDraft(
            id = UUID.randomUUID().toString(),
            name = "自定义余额模板",
            description = "",
            method = BalanceHttpMethod.GET,
            endpointTemplate = "/api/balance",
            headersText = "Authorization: Bearer {{apiKey}}",
            requestBodyTemplate = "",
            availablePath = "data.balance",
            usedPath = "",
            totalPath = "",
            deriveTotalFromAvailableAndUsed = false,
            currencyPath = "",
            planNamePath = "",
            unitLabel = "额度",
            scaleDivisor = "1",
            successPath = "",
            successExpectedValue = "",
            createdAt = System.currentTimeMillis(),
        )

        fun from(template: BalanceQueryTemplate, copied: Boolean = false): BalanceTemplateDraft = BalanceTemplateDraft(
            id = if (copied) UUID.randomUUID().toString() else template.id,
            name = if (copied) "${template.name} 副本" else template.name,
            description = template.description,
            method = template.method,
            endpointTemplate = template.endpointTemplate,
            headersText = template.headers.joinToString("\n") { "${it.name}: ${it.valueTemplate}" },
            requestBodyTemplate = template.requestBodyTemplate.orEmpty(),
            availablePath = template.availablePath,
            usedPath = template.usedPath.orEmpty(),
            totalPath = template.totalPath.orEmpty(),
            deriveTotalFromAvailableAndUsed = template.deriveTotalFromAvailableAndUsed,
            currencyPath = template.currencyPath.orEmpty(),
            planNamePath = template.planNamePath.orEmpty(),
            unitLabel = template.unitLabel,
            scaleDivisor = template.scaleDivisor.toDisplayText(),
            successPath = template.successPath.orEmpty(),
            successExpectedValue = template.successExpectedValue.orEmpty(),
            createdAt = if (copied) System.currentTimeMillis() else template.createdAt,
        )
    }
}

@Immutable
data class BalanceTemplateErrors(
    val name: String? = null,
    val endpoint: String? = null,
    val headers: String? = null,
    val body: String? = null,
    val availablePath: String? = null,
    val divisor: String? = null,
    val success: String? = null,
)

/** Values shown only for the active site; the token is stored in Keystore on save. */
@Immutable
data class BalanceCredentialsDraft(
    val accessToken: String = "",
    val accessTokenSecretId: String? = null,
    val accessTokenDirty: Boolean = false,
    val userId: String = "",
) {
    companion object {
        fun from(profile: SupplierProfile, accessToken: String): BalanceCredentialsDraft =
            BalanceCredentialsDraft(
                accessToken = accessToken,
                accessTokenSecretId = profile.balanceAccessTokenSecretId,
                userId = profile.balanceUserId,
            )
    }
}

@Immutable
data class BalanceCredentialsErrors(
    val accessToken: String? = null,
    val userId: String? = null,
)

@Immutable
data class BalanceUiState(
    val isInitializing: Boolean = true,
    /** Keeps credential edits and requests safe until the active site's secrets are available. */
    val isSecretsHydrating: Boolean = false,
    val suppliers: List<SupplierProfile> = emptyList(),
    val activeSupplierId: String? = null,
    val templates: List<BalanceQueryTemplate> = emptyList(),
    /** Latest in-memory API result for every configured supplier. */
    val balanceSnapshots: Map<String, BalanceSnapshot> = emptyMap(),
    /** Display-safe per-supplier failure text from the most recent refresh. */
    val balanceErrors: Map<String, String> = emptyMap(),
    /** One ID for a normal query, or a changing ID while a batch runs. */
    val queryingSupplierIds: Set<String> = emptySet(),
    val isQuerying: Boolean = false,
    val batchProgressDone: Int = 0,
    val batchProgressTotal: Int = 0,
    val credentials: BalanceCredentialsDraft = BalanceCredentialsDraft(),
    val credentialErrors: BalanceCredentialsErrors = BalanceCredentialsErrors(),
    val editor: BalanceTemplateDraft? = null,
    val errors: BalanceTemplateErrors = BalanceTemplateErrors(),
    val message: String? = null,
    val isMessageError: Boolean = false,
)

private fun Double.toDisplayText(): String = String.format(Locale.ROOT, "%.8f", this)
    .trimEnd('0')
    .trimEnd('.')

private data class HeaderParseResult(
    val headers: List<BalanceTemplateHeader> = emptyList(),
    val error: String? = null,
)

private fun String.parseTemplateHeaders(): HeaderParseResult {
    val headers = mutableListOf<BalanceTemplateHeader>()
    lineSequence().forEachIndexed { index, rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty()) return@forEachIndexed
        val separator = line.indexOf(':')
        if (separator <= 0) {
            return HeaderParseResult(error = "第 ${index + 1} 行应使用“名称: 值”格式")
        }
        val name = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()
        if (name.isEmpty()) return HeaderParseResult(error = "第 ${index + 1} 行缺少请求头名称")
        headers += BalanceTemplateHeader(name, value)
    }
    return HeaderParseResult(headers = headers)
}

class TesterViewModel(
    private val supplierStore: SupplierStore,
    private val secretStore: SecretStore,
    private val relayApiFactory: () -> RelayApi,
    private val balanceApiFactory: () -> BalanceApi,
) : ViewModel() {
    /** Network clients are not needed to draw or restore the first screen. */
    private val relayApi: RelayApi by lazy { relayApiFactory() }
    private val balanceApi: BalanceApi by lazy { balanceApiFactory() }
    private val batchTestRunner: BatchTestRunner by lazy { BatchTestRunner(relayApi) }

    private val _uiState = MutableStateFlow(TesterUiState())
    val uiState = _uiState
    private val _balanceUiState = MutableStateFlow(BalanceUiState())
    val balanceUiState = _balanceUiState
    private val _configurationBackupUiState = MutableStateFlow(ConfigurationBackupUiState())
    val configurationBackupUiState = _configurationBackupUiState

    private var profiles = mutableListOf<SupplierProfile>()
    private var activeSupplierId: String? = null
    private var balanceTemplates = mutableListOf<BalanceQueryTemplate>()
    private var testJob: Job? = null
    /** Encrypted source bytes only; decrypted configuration is never retained for confirmation. */
    private var pendingConfigurationImportBytes: ByteArray? = null

    init {
        viewModelScope.launch {
            restore()
        }
    }

    fun updateName(value: String) = updateDraft { copy(name = value) }

    fun updateBaseUrl(value: String) = updateDraft { copy(baseUrl = value) }

    fun updateProtocol(value: RelayProtocol) = updateDraft { copy(protocol = value) }

    fun updateApiKey(value: String) = updateDraft {
        copy(apiKey = value, apiKeyDirty = true)
    }

    fun updateBalanceAccessToken(value: String) {
        if (balanceOperationBlocked()) return
        _balanceUiState.update { state ->
            state.copy(
                credentials = state.credentials.copy(accessToken = value, accessTokenDirty = true),
                credentialErrors = BalanceCredentialsErrors(),
                message = null,
            )
        }
    }

    fun updateBalanceUserId(value: String) {
        if (balanceOperationBlocked()) return
        _balanceUiState.update { state ->
            state.copy(
                credentials = state.credentials.copy(userId = value),
                credentialErrors = BalanceCredentialsErrors(),
                message = null,
            )
        }
    }

    fun saveBalanceCredentials() {
        if (balanceOperationBlocked()) return
        viewModelScope.launch {
            persistBalanceCredentials(showSuccessMessage = true)
        }
    }

    fun updateSetting(field: SettingField, value: String) = updateDraft {
        when (field) {
            SettingField.TIMEOUT_SECONDS -> copy(timeoutSeconds = value)
            SettingField.CONCURRENCY -> copy(concurrency = value)
            SettingField.PROMPT -> copy(prompt = value)
            SettingField.KEYWORD -> copy(keyword = value)
            SettingField.MAX_TOKENS -> copy(maxTokens = value)
            SettingField.RETRY_COUNT -> copy(retryCount = value)
            SettingField.DELAY_MIN_SECONDS -> copy(delayMinSeconds = value)
            SettingField.DELAY_MAX_SECONDS -> copy(delayMaxSeconds = value)
            SettingField.BATCH_SIZE -> copy(batchSize = value)
            SettingField.BATCH_PAUSE_SECONDS -> copy(batchPauseSeconds = value)
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun clearBalanceMessage() {
        _balanceUiState.update { it.copy(message = null) }
    }

    fun clearConfigurationBackupMessage() {
        _configurationBackupUiState.update { it.copy(message = null) }
    }

    /** Builds an encrypted, cross-device configuration archive after saving visible drafts. */
    fun createConfigurationBackup(password: String) {
        if (!isConfigurationTransferAllowed()) {
            showConfigurationBackupMessage("请等待当前初始化、测试或余额查询完成", isError = true)
            return
        }
        if (password.length < ConfigurationBackupCodec.MIN_PASSWORD_LENGTH) {
            showConfigurationBackupMessage(
                "备份密码至少需要 ${ConfigurationBackupCodec.MIN_PASSWORD_LENGTH} 个字符",
                isError = true,
            )
            return
        }
        viewModelScope.launch {
            _configurationBackupUiState.update {
                it.copy(isBusy = true, exportPayload = null, message = null, isMessageError = false)
            }
            try {
                checkNotNull(persistDraft()) { "当前站点配置无法保存" }
                checkNotNull(persistBalanceCredentials()) { "当前余额凭据无法保存" }
                val backup = withContext(Dispatchers.IO) { createConfigurationBackupSnapshot() }
                val encryptedBytes = withContext(Dispatchers.IO) {
                    ConfigurationBackupCodec.encrypt(backup, password.toCharArray())
                }
                _configurationBackupUiState.update {
                    it.copy(
                        isBusy = false,
                        exportPayload = ConfigurationBackupExportPayload(
                            fileName = "relay_tester_backup_${System.currentTimeMillis()}.rtbackup",
                            encryptedBytes = encryptedBytes,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _configurationBackupUiState.update {
                    it.copy(
                        isBusy = false,
                        message = error.message ?: "无法创建配置备份",
                        isMessageError = true,
                    )
                }
            }
        }
    }

    /** Removes encrypted export bytes after Android's system document writer completes or is cancelled. */
    fun discardConfigurationBackupExport() {
        _configurationBackupUiState.update { state ->
            state.exportPayload?.encryptedBytes?.fill(0)
            state.copy(exportPayload = null)
        }
    }

    /** Keeps only encrypted source bytes until the user supplies a password and confirms import. */
    fun selectConfigurationImportFile(bytes: ByteArray) {
        if (!isConfigurationTransferAllowed()) {
            showConfigurationBackupMessage("请等待当前初始化、测试或余额查询完成", isError = true)
            return
        }
        if (bytes.size !in 1..ConfigurationBackupCodec.MAX_BACKUP_BYTES) {
            showConfigurationBackupMessage("备份文件大小无效或超过 5 MiB", isError = true)
            return
        }
        pendingConfigurationImportBytes?.fill(0)
        pendingConfigurationImportBytes = bytes.copyOf()
        _configurationBackupUiState.update {
            it.copy(importFileSelected = true, importPreview = null, message = null, isMessageError = false)
        }
    }

    fun previewConfigurationImport(password: String) {
        val encryptedBytes = pendingConfigurationImportBytes
        if (encryptedBytes == null) {
            showConfigurationBackupMessage("请先选择配置备份文件", isError = true)
            return
        }
        if (password.length < ConfigurationBackupCodec.MIN_PASSWORD_LENGTH) {
            showConfigurationBackupMessage(
                "请输入至少 ${ConfigurationBackupCodec.MIN_PASSWORD_LENGTH} 个字符的备份密码",
                isError = true,
            )
            return
        }
        viewModelScope.launch {
            _configurationBackupUiState.update { it.copy(isBusy = true, message = null, isMessageError = false) }
            try {
                val preview = withContext(Dispatchers.IO) {
                    ConfigurationBackupCodec.preview(
                        ConfigurationBackupCodec.decrypt(encryptedBytes, password.toCharArray()),
                    )
                }
                _configurationBackupUiState.update { it.copy(isBusy = false, importPreview = preview) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _configurationBackupUiState.update {
                    it.copy(
                        isBusy = false,
                        importPreview = null,
                        message = error.message ?: "无法读取配置备份",
                        isMessageError = true,
                    )
                }
            }
        }
    }

    /** Re-decrypts the encrypted source and commits it only after the user confirms replacement. */
    fun confirmConfigurationImport(password: String) {
        val encryptedBytes = pendingConfigurationImportBytes
        if (encryptedBytes == null || _configurationBackupUiState.value.importPreview == null) {
            showConfigurationBackupMessage("请先验证并预览配置备份", isError = true)
            return
        }
        if (!isConfigurationTransferAllowed()) {
            showConfigurationBackupMessage("请等待当前初始化、测试或余额查询完成", isError = true)
            return
        }
        viewModelScope.launch {
            _configurationBackupUiState.update { it.copy(isBusy = true, message = null, isMessageError = false) }
            try {
                val backup = withContext(Dispatchers.IO) {
                    ConfigurationBackupCodec.decrypt(encryptedBytes, password.toCharArray())
                }
                applyConfigurationBackup(backup)
                pendingConfigurationImportBytes?.fill(0)
                pendingConfigurationImportBytes = null
                _configurationBackupUiState.update {
                    it.copy(
                        isBusy = false,
                        importFileSelected = false,
                        importPreview = null,
                        message = "配置已导入；未自动发起任何站点请求",
                        isMessageError = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _configurationBackupUiState.update {
                    it.copy(
                        isBusy = false,
                        message = error.message ?: "配置导入失败，当前配置未更改",
                        isMessageError = true,
                    )
                }
            }
        }
    }

    fun clearPendingConfigurationImport() {
        pendingConfigurationImportBytes?.fill(0)
        pendingConfigurationImportBytes = null
        _configurationBackupUiState.update {
            it.copy(importFileSelected = false, importPreview = null, message = null, isMessageError = false)
        }
    }

    fun selectBalanceTemplate(id: String) {
        if (balanceOperationBlocked()) return
        if (balanceTemplates.none { it.id == id }) return
        viewModelScope.launch {
            if (persistDraft() == null) return@launch
            val activeId = activeSupplierId ?: return@launch
            val previousTemplateId = profiles.firstOrNull { it.id == activeId }?.balanceTemplateId
            assignBalanceTemplateToActiveSupplier(id)
            if (!saveProfiles()) {
                assignBalanceTemplateToActiveSupplier(previousTemplateId)
                return@launch
            }
            _balanceUiState.update { state ->
                val selectedId = activeSupplierId
                state.copy(
                    balanceSnapshots = if (selectedId == null) {
                        state.balanceSnapshots
                    } else {
                        state.balanceSnapshots - selectedId
                    },
                    balanceErrors = if (selectedId == null) {
                        state.balanceErrors
                    } else {
                        state.balanceErrors - selectedId
                    },
                    message = "已切换余额查询模板",
                    isMessageError = false,
                )
            }
        }
    }

    fun beginCreateBalanceTemplate() {
        if (balanceOperationBlocked()) return
        _balanceUiState.update {
            it.copy(
                editor = BalanceTemplateDraft.empty(),
                errors = BalanceTemplateErrors(),
                message = null,
            )
        }
    }

    fun beginEditBalanceTemplate(templateId: String) {
        if (balanceOperationBlocked()) return
        val template = balanceTemplates.firstOrNull { it.id == templateId } ?: return
        _balanceUiState.update {
            it.copy(
                // Built-ins are preserved so upstream changes never silently
                // destroy a user's known-good default. Editing starts a copy.
                editor = BalanceTemplateDraft.from(template, copied = template.builtIn),
                errors = BalanceTemplateErrors(),
                message = if (template.builtIn) "内置模板会以副本方式保存" else null,
                isMessageError = false,
            )
        }
    }

    fun dismissBalanceTemplateEditor() {
        _balanceUiState.update {
            it.copy(editor = null, errors = BalanceTemplateErrors(), message = null)
        }
    }

    fun updateBalanceTemplate(field: BalanceTemplateField, value: String) {
        _balanceUiState.update { state ->
            val draft = state.editor ?: return@update state
            val updated = when (field) {
                BalanceTemplateField.NAME -> draft.copy(name = value)
                BalanceTemplateField.DESCRIPTION -> draft.copy(description = value)
                BalanceTemplateField.ENDPOINT -> draft.copy(endpointTemplate = value)
                BalanceTemplateField.HEADERS -> draft.copy(headersText = value)
                BalanceTemplateField.BODY -> draft.copy(requestBodyTemplate = value)
                BalanceTemplateField.AVAILABLE_PATH -> draft.copy(availablePath = value)
                BalanceTemplateField.USED_PATH -> draft.copy(usedPath = value)
                BalanceTemplateField.TOTAL_PATH -> draft.copy(totalPath = value)
                BalanceTemplateField.PLAN_NAME_PATH -> draft.copy(planNamePath = value)
                BalanceTemplateField.CURRENCY_PATH -> draft.copy(currencyPath = value)
                BalanceTemplateField.UNIT_LABEL -> draft.copy(unitLabel = value)
                BalanceTemplateField.SCALE_DIVISOR -> draft.copy(scaleDivisor = value)
                BalanceTemplateField.SUCCESS_PATH -> draft.copy(successPath = value)
                BalanceTemplateField.SUCCESS_EXPECTED_VALUE -> draft.copy(successExpectedValue = value)
            }
            state.copy(editor = updated, errors = BalanceTemplateErrors(), message = null)
        }
    }

    fun updateBalanceTemplateMethod(method: BalanceHttpMethod) {
        _balanceUiState.update { state ->
            val draft = state.editor ?: return@update state
            state.copy(
                editor = draft.copy(method = method),
                errors = BalanceTemplateErrors(),
                message = null,
            )
        }
    }

    fun updateBalanceTemplateDerivedTotal(enabled: Boolean) {
        _balanceUiState.update { state ->
            val draft = state.editor ?: return@update state
            state.copy(
                editor = draft.copy(deriveTotalFromAvailableAndUsed = enabled),
                errors = BalanceTemplateErrors(),
                message = null,
            )
        }
    }

    fun saveBalanceTemplate(queryAfterSave: Boolean = false) {
        if (_balanceUiState.value.isQuerying) return
        viewModelScope.launch {
            val template = persistBalanceTemplate() ?: return@launch
            if (queryAfterSave) {
                queryBalanceInternal(template)
            }
        }
    }

    fun deleteBalanceTemplate(templateId: String) {
        if (balanceOperationBlocked()) return
        val template = balanceTemplates.firstOrNull { it.id == templateId } ?: return
        if (template.builtIn) {
            showBalanceMessage("内置模板不能删除；可复制后自行编辑", isError = true)
            return
        }
        viewModelScope.launch {
            val previousTemplates = balanceTemplates.toMutableList()
            val previousProfiles = profiles.toMutableList()
            balanceTemplates.removeAll { it.id == templateId }
            val fallbackId = balanceTemplates.firstOrNull()?.id
            profiles = profiles.map { profile ->
                if (profile.balanceTemplateId == templateId) {
                    profile.copy(balanceTemplateId = fallbackId)
                } else {
                    profile
                }
            }.toMutableList()
            if (!saveProfiles()) {
                balanceTemplates = previousTemplates
                profiles = previousProfiles
                return@launch
            }
            _uiState.update { state ->
                val draft = state.draft
                state.copy(
                    suppliers = profiles.toList(),
                    draft = draft
                        ?.takeIf { it.id == activeSupplierId && it.balanceTemplateId == templateId }
                        ?.copy(balanceTemplateId = fallbackId)
                        ?: draft,
                )
            }
            _balanceUiState.update { state ->
                val selectedId = activeSupplierId
                state.copy(
                    balanceSnapshots = if (selectedId == null) {
                        state.balanceSnapshots
                    } else {
                        state.balanceSnapshots - selectedId
                    },
                    balanceErrors = if (selectedId == null) {
                        state.balanceErrors
                    } else {
                        state.balanceErrors - selectedId
                    },
                    message = "已删除余额模板",
                    isMessageError = false,
                )
            }
        }
    }

    fun queryBalance() {
        if (balanceOperationBlocked()) return
        viewModelScope.launch { queryBalanceInternal() }
    }

    fun queryAllBalances() {
        if (balanceOperationBlocked()) return
        viewModelScope.launch { queryAllBalancesInternal() }
    }

    fun saveCurrentSupplier() {
        if (runningOrInitializing()) return
        viewModelScope.launch {
            if (persistDraft() != null) {
                showMessage("站点配置已保存")
            }
        }
    }

    fun selectSupplier(id: String) {
        if (runningOrInitializing() || id == activeSupplierId) return
        viewModelScope.launch {
            if (persistDraft() == null || persistBalanceCredentials() == null) return@launch
            val previousActiveSupplierId = activeSupplierId
            activeSupplierId = id
            if (!saveProfiles()) {
                activeSupplierId = previousActiveSupplierId
                return@launch
            }
            profiles.firstOrNull { it.id == id }?.let { profile ->
                installDraft(profile)
            }
            _uiState.update {
                it.copy(
                    activeSupplierId = id,
                    results = emptyList(),
                    summary = null,
                    progressDone = 0,
                    progressTotal = 0,
                    errors = FormErrors(),
                )
            }
            _balanceUiState.update { it.copy(credentialErrors = BalanceCredentialsErrors()) }
        }
    }

    fun addSupplier() {
        if (runningOrInitializing()) return
        viewModelScope.launch {
            if (persistDraft() == null || persistBalanceCredentials() == null) return@launch
            val previousActiveSupplierId = activeSupplierId
            val profile = SupplierProfile.empty(profiles.size + 1)
            profiles += profile
            activeSupplierId = profile.id
            if (!saveProfiles()) {
                profiles.removeAll { it.id == profile.id }
                activeSupplierId = previousActiveSupplierId
                return@launch
            }
            installDraft(profile)
            _uiState.update {
                it.copy(
                    suppliers = profiles.toList(),
                    activeSupplierId = profile.id,
                    results = emptyList(),
                    summary = null,
                    message = "已添加供应商",
                    isMessageError = false,
                )
            }
            _balanceUiState.update { it.copy(credentialErrors = BalanceCredentialsErrors()) }
        }
    }

    fun deleteCurrentSupplier() {
        if (runningOrInitializing()) return
        if (profiles.size <= 1) {
            showMessage("请至少保留一个供应商", isError = true)
            return
        }
        viewModelScope.launch {
            val id = activeSupplierId ?: return@launch
            val removed = profiles.firstOrNull { it.id == id } ?: return@launch
            val previousProfiles = profiles.toMutableList()
            val previousActiveSupplierId = activeSupplierId
            profiles.removeAll { it.id == id }
            val next = profiles.first()
            activeSupplierId = next.id
            if (!saveProfiles()) {
                profiles = previousProfiles
                activeSupplierId = previousActiveSupplierId
                return@launch
            }
            // Delete only after the replacement supplier list is durable. A
            // deletion failure leaves an unreachable encrypted record instead
            // of destroying a key that the stored profile still references.
            withContext(Dispatchers.IO) {
                listOfNotNull(removed.apiKeySecretId, removed.balanceAccessTokenSecretId)
                    .forEach { secretId -> runCatching { secretStore.delete(secretId) } }
            }
            installDraft(next)
            _uiState.update {
                it.copy(
                    suppliers = profiles.toList(),
                    activeSupplierId = next.id,
                    results = emptyList(),
                    summary = null,
                    message = "已删除供应商",
                    isMessageError = false,
                )
            }
            _balanceUiState.update { state ->
                state.copy(
                    balanceSnapshots = state.balanceSnapshots - id,
                    balanceErrors = state.balanceErrors - id,
                    credentialErrors = BalanceCredentialsErrors(),
                )
            }
        }
    }

    fun fetchModels() {
        if (runningOrInitializing()) return
        viewModelScope.launch {
            val request = prepareRequest() ?: return@launch
            _uiState.update { it.copy(isFetchingModels = true, message = null) }
            when (
                val result = fetchModelsInBackground(request)
            ) {
                is ApiResult.Success -> {
                    val saved = request.profile.copy(models = result.value)
                    if (!replaceProfile(saved)) return@launch
                    _uiState.update {
                        it.copy(
                            draft = SupplierDraft.from(saved, request.apiKey),
                            suppliers = profiles.toList(),
                            results = emptyList(),
                            summary = null,
                            progressDone = 0,
                            progressTotal = 0,
                            isFetchingModels = false,
                            message = if (result.value.isEmpty()) {
                                "站点未返回模型列表"
                            } else {
                                "已获取 " + result.value.size + " 个模型"
                            },
                            isMessageError = result.value.isEmpty(),
                        )
                    }
                }

                is ApiResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isFetchingModels = false,
                            message = result.error.message,
                            isMessageError = true,
                        )
                    }
                }
            }
        }
    }

    /** The first RelayApi/OkHttp construction is deferred until a user asks for models. */
    private suspend fun fetchModelsInBackground(request: PreparedRequest): ApiResult<List<String>> =
        withContext(Dispatchers.IO) {
            relayApi.fetchModels(
                profile = request.profile,
                apiKey = request.apiKey,
                timeoutSeconds = request.profile.testSettings.timeoutSeconds,
            )
        }

    fun startTest() {
        if (runningOrInitializing()) return
        viewModelScope.launch {
            var request = prepareRequest() ?: return@launch
            if (request.profile.models.isEmpty()) {
                _uiState.update { it.copy(isFetchingModels = true, message = null) }
                when (
                    val loaded = fetchModelsInBackground(request)
                ) {
                    is ApiResult.Success -> {
                        val saved = request.profile.copy(models = loaded.value)
                        if (!replaceProfile(saved)) {
                            _uiState.update { it.copy(isFetchingModels = false) }
                            return@launch
                        }
                        request = PreparedRequest(saved, request.apiKey)
                        _uiState.update {
                            it.copy(
                                draft = SupplierDraft.from(saved, request.apiKey),
                                suppliers = profiles.toList(),
                                isFetchingModels = false,
                            )
                        }
                    }

                    is ApiResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                isFetchingModels = false,
                                message = loaded.error.message,
                                isMessageError = true,
                            )
                        }
                        return@launch
                    }
                }
            }

            val targets = request.profile.models.filterBy(request.profile.testSettings.keyword)
            if (targets.isEmpty()) {
                showMessage("没有与模型名过滤条件匹配的模型", isError = true)
                return@launch
            }
            startRun(request, targets, replaceAll = true)
        }
    }

    fun retryFailed() {
        if (runningOrInitializing()) return
        viewModelScope.launch {
            val request = prepareRequest() ?: return@launch
            val targets = _uiState.value.results
                .filter { it.status == TestStatus.FAILED }
                .map { it.model }
            if (targets.isEmpty()) {
                showMessage("没有失败项可以重测", isError = true)
                return@launch
            }
            startRun(request, targets, replaceAll = false)
        }
    }

    fun retestAll() {
        if (runningOrInitializing()) return
        viewModelScope.launch {
            val request = prepareRequest() ?: return@launch
            val targets = _uiState.value.results.map { it.model }
            if (targets.isEmpty()) {
                showMessage("没有可重测的结果", isError = true)
                return@launch
            }
            startRun(request, targets, replaceAll = true)
        }
    }

    fun cancelRun() {
        testJob?.cancel()
        testJob = null
        _uiState.update {
            it.copy(
                isRunning = false,
                isFetchingModels = false,
                message = "测试已取消",
                isMessageError = false,
            )
        }
    }

    fun updateFilter(filter: ResultFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun updateSort(sort: ResultSort) {
        _uiState.update { it.copy(sort = sort) }
    }

    fun updateResultQuery(query: String) {
        _uiState.update { it.copy(resultQuery = query) }
    }

    fun visibleResults(): List<ModelTestResult> {
        val state = _uiState.value
        return state.results
            .asSequence()
            .filter {
                when (state.filter) {
                    ResultFilter.ALL -> true
                    ResultFilter.AVAILABLE -> it.status == TestStatus.SUCCESS
                    ResultFilter.FAILED -> it.status == TestStatus.FAILED
                }
            }
            .filter { state.resultQuery.isBlank() || it.model.contains(state.resultQuery, ignoreCase = true) }
            .sortedWith(
                when (state.sort) {
                    ResultSort.NAME -> compareBy<ModelTestResult> { it.model.lowercase(Locale.ROOT) }
                    ResultSort.LATENCY -> compareBy<ModelTestResult> {
                        when (it.status) {
                            TestStatus.SUCCESS -> 0
                            TestStatus.FAILED -> 1
                            TestStatus.PENDING -> 2
                        }
                    }.thenBy { it.latencyMs ?: Long.MAX_VALUE }
                },
            )
            .toList()
    }

    fun buildExportPayload(): ExportPayload? {
        val state = _uiState.value
        val profile = profiles.firstOrNull { it.id == activeSupplierId } ?: return null
        if (state.results.isEmpty()) return null
        val summary = state.summary
        val json = JSONObject()
            .put("supplier", profile.name)
            .put("base_url", profile.baseUrl)
            .put("protocol", profile.protocol.name)
            .put("generated_at", System.currentTimeMillis())
            .put(
                "summary",
                JSONObject()
                    .put("total", summary?.total ?: state.results.size)
                    .put("ok", summary?.succeeded ?: state.results.count { it.status == TestStatus.SUCCESS })
                    .put("failed", summary?.failed ?: state.results.count { it.status == TestStatus.FAILED })
                    .put("avg_latency_ms", summary?.averageLatencyMs)
                    .put("total_tokens", summary?.totalTokens ?: 0),
            )
            .put("results", JSONArray(state.results.map { it.toJson() }))
            .toString(2)
        val safeName = profile.name.replace(Regex("[^\\w\\u4e00-\\u9fa5-]+"), "_")
            .ifBlank { "results" }
        return ExportPayload(
            fileName = "model_test_" + safeName + "_" + System.currentTimeMillis() + ".json",
            json = json,
        )
    }

    private suspend fun persistBalanceTemplate(): BalanceQueryTemplate? {
        val draft = _balanceUiState.value.editor ?: return null
        val parsedHeaders = draft.headersText.parseTemplateHeaders()
        if (parsedHeaders.error != null) {
            _balanceUiState.update {
                it.copy(
                    errors = BalanceTemplateErrors(headers = parsedHeaders.error),
                    message = "请先修正模板配置",
                    isMessageError = true,
                )
            }
            return null
        }
        val template = draft.toTemplate(parsedHeaders.headers, System.currentTimeMillis())
        val validationMessage = withContext(Dispatchers.Default) {
            balanceApi.validateTemplate(template)
        }
        if (validationMessage != null) {
            _balanceUiState.update {
                it.copy(
                    errors = validationMessage.toTemplateErrors(),
                    message = "请先修正模板配置",
                    isMessageError = true,
                )
            }
            return null
        }
        if (balanceTemplates.firstOrNull { it.id == template.id }?.builtIn == true) {
            showBalanceMessage("内置模板请通过“复制为自定义模板”创建副本", isError = true)
            return null
        }

        val previousTemplates = balanceTemplates.toMutableList()
        val previousProfiles = profiles.toMutableList()
        val activeId = activeSupplierId
        val previousTemplateId = activeId
            ?.let { id -> profiles.firstOrNull { it.id == id }?.balanceTemplateId }
        val index = balanceTemplates.indexOfFirst { it.id == template.id }
        if (index >= 0) {
            balanceTemplates[index] = template
        } else {
            balanceTemplates += template
        }
        assignBalanceTemplateToActiveSupplier(template.id)
        if (!saveProfiles()) {
            balanceTemplates = previousTemplates
            profiles = previousProfiles
            assignBalanceTemplateToActiveSupplier(previousTemplateId)
            return null
        }
        _balanceUiState.update { state ->
            val selectedId = activeSupplierId
            state.copy(
                editor = null,
                errors = BalanceTemplateErrors(),
                balanceSnapshots = if (selectedId == null) {
                    state.balanceSnapshots
                } else {
                    state.balanceSnapshots - selectedId
                },
                balanceErrors = if (selectedId == null) {
                    state.balanceErrors
                } else {
                    state.balanceErrors - selectedId
                },
                message = "余额模板已保存",
                isMessageError = false,
            )
        }
        return template
    }

    private suspend fun queryBalanceInternal(preferredTemplate: BalanceQueryTemplate? = null) {
        try {
            val request = prepareBalanceRequest(preferredTemplate) ?: return
            val supplierId = request.profile.id
            _balanceUiState.update {
                it.copy(
                    isQuerying = true,
                    queryingSupplierIds = setOf(supplierId),
                    batchProgressDone = 0,
                    batchProgressTotal = 1,
                    balanceErrors = it.balanceErrors - supplierId,
                    message = null,
                )
            }
            when (
                val result = withContext(Dispatchers.IO) {
                    balanceApi.query(
                        profile = request.profile,
                        apiKey = request.apiKey,
                        accessToken = request.accessToken,
                        userId = request.userId,
                        template = request.template,
                    )
                }
            ) {
                is BalanceQueryResult.Success -> {
                    _balanceUiState.update {
                        it.copy(
                            balanceSnapshots = it.balanceSnapshots + (supplierId to result.snapshot),
                            balanceErrors = it.balanceErrors - supplierId,
                            isQuerying = false,
                            queryingSupplierIds = emptySet(),
                            batchProgressDone = 1,
                            message = "余额已更新",
                            isMessageError = false,
                        )
                    }
                }

                is BalanceQueryResult.Failure -> {
                    _balanceUiState.update {
                        it.copy(
                            balanceErrors = it.balanceErrors + (supplierId to result.message),
                            isQuerying = false,
                            queryingSupplierIds = emptySet(),
                            batchProgressDone = 1,
                            message = result.message,
                            isMessageError = true,
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            _balanceUiState.update { state ->
                val selectedId = activeSupplierId
                state.copy(
                    isQuerying = false,
                    queryingSupplierIds = emptySet(),
                    balanceErrors = if (selectedId == null) {
                        state.balanceErrors
                    } else {
                        state.balanceErrors + (
                            selectedId to "余额查询准备失败，请重新保存凭据后重试"
                        )
                    },
                    message = "余额查询准备失败，请重新保存凭据后重试",
                    isMessageError = true,
                )
            }
        }
    }

    /**
     * Refreshes all suppliers one by one. Sequential execution keeps the tool
     * predictable for heterogeneous relay stations and lets one failure leave
     * the remaining sites untouched.
     */
    private suspend fun queryAllBalancesInternal() {
        // Include a user's unsaved active-site values before taking the batch
        // snapshot. Other suppliers are always read from their own persisted
        // Keystore records and can never borrow these credentials.
        if (persistDraft() == null || persistBalanceCredentials() == null) return
        val targets = profiles.toList()
        if (targets.isEmpty()) return

        _balanceUiState.update {
            it.copy(
                isQuerying = true,
                queryingSupplierIds = emptySet(),
                batchProgressDone = 0,
                batchProgressTotal = targets.size,
                message = null,
            )
        }

        var succeeded = 0
        targets.forEachIndexed { index, profile ->
            _balanceUiState.update {
                it.copy(
                    queryingSupplierIds = setOf(profile.id),
                    batchProgressDone = index,
                    balanceErrors = it.balanceErrors - profile.id,
                )
            }
            val preparation = try {
                prepareBalanceRequestFor(profile)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _balanceUiState.update {
                    it.copy(
                        balanceErrors = it.balanceErrors + (
                            profile.id to "无法读取此站点的查询凭据，请重新保存后重试"
                        ),
                        batchProgressDone = index + 1,
                    )
                }
                return@forEachIndexed
            }
            when (preparation) {
                is BalanceRequestPreparation.Ready -> {
                    when (
                        val result = withContext(Dispatchers.IO) {
                            balanceApi.query(
                                profile = preparation.request.profile,
                                apiKey = preparation.request.apiKey,
                                accessToken = preparation.request.accessToken,
                                userId = preparation.request.userId,
                                template = preparation.request.template,
                            )
                        }
                    ) {
                        is BalanceQueryResult.Success -> {
                            succeeded += 1
                            _balanceUiState.update {
                                it.copy(
                                    balanceSnapshots = it.balanceSnapshots + (profile.id to result.snapshot),
                                    balanceErrors = it.balanceErrors - profile.id,
                                    batchProgressDone = index + 1,
                                )
                            }
                        }

                        is BalanceQueryResult.Failure -> {
                            _balanceUiState.update {
                                it.copy(
                                    balanceErrors = it.balanceErrors + (profile.id to result.message),
                                    batchProgressDone = index + 1,
                                )
                            }
                        }
                    }
                }

                is BalanceRequestPreparation.Invalid -> {
                    _balanceUiState.update {
                        it.copy(
                            balanceErrors = it.balanceErrors + (profile.id to preparation.message),
                            batchProgressDone = index + 1,
                        )
                    }
                }
            }
        }

        val failed = targets.size - succeeded
        _balanceUiState.update {
            it.copy(
                isQuerying = false,
                queryingSupplierIds = emptySet(),
                batchProgressDone = targets.size,
                message = if (failed == 0) {
                    "已更新 $succeeded 个站点"
                } else {
                    "已完成：$succeeded 成功，$failed 需要处理"
                },
                isMessageError = false,
            )
        }
    }

    private suspend fun prepareBalanceRequest(
        preferredTemplate: BalanceQueryTemplate?,
    ): BalancePreparedRequest? {
        val draft = _uiState.value.draft
        if (draft == null || draft.baseUrl.isBlank()) {
            showBalanceMessage("请先在模型测试页填写中转站 Base URL", isError = true)
            return null
        }
        persistDraft() ?: return null
        val savedBalanceProfile = persistBalanceCredentials() ?: return null
        return when (val preparation = prepareBalanceRequestFor(savedBalanceProfile, preferredTemplate)) {
            is BalanceRequestPreparation.Ready -> preparation.request
            is BalanceRequestPreparation.Invalid -> {
                val accessTokenError = preparation.message.takeIf {
                    it.contains("访问令牌")
                }
                _balanceUiState.update {
                    it.copy(
                        credentialErrors = BalanceCredentialsErrors(accessToken = accessTokenError),
                        balanceErrors = it.balanceErrors + (savedBalanceProfile.id to preparation.message),
                        message = preparation.message,
                        isMessageError = true,
                    )
                }
                null
            }
        }
    }

    private suspend fun prepareBalanceRequestFor(
        profile: SupplierProfile,
        preferredTemplate: BalanceQueryTemplate? = null,
    ): BalanceRequestPreparation {
        if (profile.baseUrl.isBlank()) {
            return BalanceRequestPreparation.Invalid("未配置 Base URL")
        }
        val template = preferredTemplate
            ?: balanceTemplates.firstOrNull { it.id == profile.balanceTemplateId }
            ?: balanceTemplates.firstOrNull()
            ?: return BalanceRequestPreparation.Invalid("未选择余额查询模板")
        val apiKey = profile.apiKeySecretId?.let { secretId ->
            withContext(Dispatchers.IO) { secretStore.get(secretId) }
        }.orEmpty()
        val accessToken = profile.balanceAccessTokenSecretId?.let { secretId ->
            withContext(Dispatchers.IO) { secretStore.get(secretId) }
        }.orEmpty()
        if (template.referencesBalancePlaceholder("{{apiKey}}") && apiKey.isBlank()) {
            return BalanceRequestPreparation.Invalid("此模板需要模型 API Key")
        }
        if (template.referencesBalancePlaceholder("{{accessToken}}") && accessToken.isBlank()) {
            return BalanceRequestPreparation.Invalid("此模板需要余额查询访问令牌（PAT）")
        }
        return BalanceRequestPreparation.Ready(
            BalancePreparedRequest(
                profile = profile,
                apiKey = apiKey,
                accessToken = accessToken,
                // userId is deliberately optional. Empty values are omitted
                // from headers by BalanceApi instead of rejecting new-api.
                userId = profile.balanceUserId.trim(),
                template = template,
            ),
        )
    }

    private fun assignBalanceTemplateToActiveSupplier(templateId: String?) {
        val activeId = activeSupplierId ?: return
        val index = profiles.indexOfFirst { it.id == activeId }
        if (index < 0) return
        profiles[index] = profiles[index].copy(balanceTemplateId = templateId)
        _uiState.update { state ->
            val draft = state.draft
            state.copy(
                suppliers = profiles.toList(),
                draft = if (draft?.id == activeId) draft.copy(balanceTemplateId = templateId) else draft,
            )
        }
    }

    private fun BalanceQueryTemplate.referencesBalancePlaceholder(placeholder: String): Boolean = buildList {
        add(endpointTemplate)
        addAll(headers.map { it.valueTemplate })
        requestBodyTemplate?.let(::add)
    }.any { it.contains(placeholder) }

    private fun String.toTemplateErrors(): BalanceTemplateErrors = when {
        contains("模板名称") -> BalanceTemplateErrors(name = this)
        contains("请求地址") -> BalanceTemplateErrors(endpoint = this)
        contains("请求头") -> BalanceTemplateErrors(headers = this)
        contains("GET 模板") -> BalanceTemplateErrors(body = this)
        contains("JSON 路径") -> BalanceTemplateErrors(availablePath = this)
        contains("换算除数") -> BalanceTemplateErrors(divisor = this)
        contains("成功") -> BalanceTemplateErrors(success = this)
        else -> BalanceTemplateErrors(endpoint = this)
    }

    private fun showBalanceMessage(message: String, isError: Boolean = false) {
        _balanceUiState.update { it.copy(message = message, isMessageError = isError) }
    }

    private fun createConfigurationBackupSnapshot(): ConfigurationBackup = ConfigurationBackup(
        createdAt = System.currentTimeMillis(),
        activeSupplierId = activeSupplierId,
        suppliers = profiles.map { profile ->
            ConfigurationBackupSupplier(
                id = profile.id,
                name = profile.name,
                baseUrl = profile.baseUrl,
                protocol = profile.protocol,
                apiKey = profile.apiKeySecretId?.let(secretStore::get).orEmpty(),
                models = profile.models,
                testSettings = profile.testSettings,
                balanceTemplateId = profile.balanceTemplateId,
                balanceAccessToken = profile.balanceAccessTokenSecretId?.let(secretStore::get).orEmpty(),
                balanceUserId = profile.balanceUserId,
            )
        },
        balanceTemplates = balanceTemplates.toList(),
    )

    /**
     * Writes imported secrets first, then atomically commits the DataStore
     * reference set. Old secret entries are deleted only after that commit.
     */
    private suspend fun applyConfigurationBackup(backup: ConfigurationBackup) {
        val validationError = withContext(Dispatchers.Default) {
            backup.balanceTemplates
                .firstNotNullOfOrNull { template -> balanceApi.validateTemplate(template) }
        }
        if (validationError != null) {
            throw ConfigurationBackupException("备份中的余额模板无效，当前配置未更改")
        }

        val oldSecretIds = profiles.flatMap { profile ->
            listOfNotNull(profile.apiKeySecretId, profile.balanceAccessTokenSecretId)
        }.distinct()
        val createdSecretIds = mutableListOf<String>()
        var dataStoreCommitted = false
        try {
            val imported = withContext(Dispatchers.IO) {
                backup.suppliers.map { source ->
                    val apiKeySecretId = source.apiKey.trim().takeIf(String::isNotBlank)?.let { apiKey ->
                        secretStore.put(apiKey).also(createdSecretIds::add)
                    }
                    val balanceTokenSecretId = source.balanceAccessToken.trim()
                        .takeIf(String::isNotBlank)
                        ?.let { token -> secretStore.put(token).also(createdSecretIds::add) }
                    ImportedConfigurationSupplier(
                        profile = SupplierProfile(
                            id = source.id,
                            name = source.name.trim().ifBlank { "未命名供应商" },
                            baseUrl = source.baseUrl.trim(),
                            protocol = source.protocol,
                            apiKeySecretId = apiKeySecretId,
                            models = source.models.distinct().sorted(),
                            testSettings = source.testSettings,
                            balanceTemplateId = source.balanceTemplateId,
                            balanceAccessTokenSecretId = balanceTokenSecretId,
                            balanceUserId = source.balanceUserId.trim(),
                        ),
                        apiKey = source.apiKey.trim(),
                        balanceAccessToken = source.balanceAccessToken.trim(),
                    )
                }
            }
            val activeId = backup.activeSupplierId
                ?.takeIf { candidate -> imported.any { it.profile.id == candidate } }
                ?: imported.first().profile.id
            val stateToSave = SupplierStoreState(
                suppliers = imported.map(ImportedConfigurationSupplier::profile),
                activeSupplierId = activeId,
                balanceTemplates = backup.balanceTemplates,
            )
            withContext(Dispatchers.IO) { supplierStore.save(stateToSave) }
            dataStoreCommitted = true

            profiles = imported.map(ImportedConfigurationSupplier::profile).toMutableList()
            activeSupplierId = activeId
            balanceTemplates = backup.balanceTemplates.toMutableList()
            val active = requireNotNull(imported.firstOrNull { it.profile.id == activeId })
            _uiState.value = TesterUiState(
                isInitializing = false,
                suppliers = profiles.toList(),
                activeSupplierId = activeId,
                draft = SupplierDraft.from(active.profile, active.apiKey),
                message = "配置已导入",
                isMessageError = false,
            )
            _balanceUiState.value = BalanceUiState(
                isInitializing = false,
                suppliers = profiles.toList(),
                activeSupplierId = activeId,
                templates = balanceTemplates.toList(),
                credentials = BalanceCredentialsDraft.from(active.profile, active.balanceAccessToken),
                message = "配置已导入",
                isMessageError = false,
            )
            // Deletion happens after the state is durable. A best-effort failure leaves only
            // inaccessible ciphertext behind and must never roll back a successful import.
            withContext(Dispatchers.IO) {
                oldSecretIds.forEach { secretId -> runCatching { secretStore.delete(secretId) } }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!dataStoreCommitted) {
                withContext(Dispatchers.IO) {
                    createdSecretIds.forEach { secretId -> runCatching { secretStore.delete(secretId) } }
                }
            }
            throw error
        }
    }

    private suspend fun restore() {
        val startup = withContext(Dispatchers.IO) { loadStartupSnapshot() }
        profiles = startup.profiles.toMutableList()
        activeSupplierId = startup.activeSupplierId
        balanceTemplates = startup.templates.toMutableList()
        val active = requireNotNull(profiles.firstOrNull { it.id == activeSupplierId })
        val needsSecretsHydration =
            active.apiKeySecretId != null || active.balanceAccessTokenSecretId != null

        // Stage one publishes the safely displayable structure immediately.
        // Keystore reads may wait on vendor-backed hardware and are deliberately
        // deferred to stage two. While that happens, all state-changing actions
        // are disabled so a blank temporary draft can never overwrite a secret.
        _uiState.value = TesterUiState(
            isInitializing = false,
            isSecretsHydrating = needsSecretsHydration,
            suppliers = profiles.toList(),
            activeSupplierId = activeSupplierId,
            draft = SupplierDraft.from(active, ""),
        )
        _balanceUiState.value = BalanceUiState(
            isInitializing = false,
            isSecretsHydrating = needsSecretsHydration,
            suppliers = profiles.toList(),
            activeSupplierId = activeSupplierId,
            templates = balanceTemplates.toList(),
            credentials = BalanceCredentialsDraft.from(active, ""),
        )
        if (needsSecretsHydration) {
            // Keystore implementations can occasionally take a long time to
            // warm up on a freshly booted emulator or a device under load.
            // Do not make the screen non-interactive for that entire period:
            // a timed initial wait releases the UI, while the same safe
            // background read continues and fills only untouched fields later.
            val credentialsDeferred = viewModelScope.async(Dispatchers.IO) {
                loadStartupCredentials(active)
            }
            val initialCredentials = withTimeoutOrNull(STARTUP_SECRET_INTERACTION_BLOCK_MS) {
                credentialsDeferred.await()
            }
            if (initialCredentials != null) {
                applyStartupCredentials(active, initialCredentials)
            } else {
                finishStartupSecretHydration()
                viewModelScope.launch {
                    runCatching { credentialsDeferred.await() }
                        .getOrNull()
                        ?.let { lateCredentials -> applyStartupCredentials(active, lateCredentials) }
                }
            }
        }
        if (!startup.storageLoaded) {
            val message = "本地配置暂时无法读取，已进入安全空白状态；原配置未被覆盖"
            showMessage(message, isError = true)
            showBalanceMessage(message, isError = true)
        }
    }

    private suspend fun loadStartupSnapshot(): StartupSnapshot {
        // The app must remain launchable even if a prior process was killed
        // while writing preferences or a device vendor reports a DataStore I/O
        // error. Do not clear or overwrite the old file on this fallback path.
        var storageLoaded = true
        val stored = try {
            supplierStore.read()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            storageLoaded = false
            SupplierStoreState(
                suppliers = emptyList(),
                activeSupplierId = null,
            )
        }
        val restoredProfiles = stored.suppliers.toMutableList()
        val restoredTemplates = stored.balanceTemplates
            .ifEmpty { listOf(BalanceQueryTemplate.newApiDefault()) }
            .map { template ->
                // Built-ins are refreshed in memory. Their next ordinary
                // user-initiated save persists the upgrade without adding a
                // startup write to the critical first-frame path.
                if (template.id == BalanceQueryTemplate.NEW_API_TEMPLATE_ID && template.builtIn) {
                    BalanceQueryTemplate.newApiDefault()
                } else {
                    template
                }
            }
        if (restoredProfiles.isEmpty()) {
            restoredProfiles += SupplierProfile.empty()
        }
        val restoredActiveId = stored.activeSupplierId?.takeIf { storedId ->
            restoredProfiles.any { it.id == storedId }
        } ?: restoredProfiles.first().id
        return StartupSnapshot(
            profiles = restoredProfiles,
            activeSupplierId = restoredActiveId,
            templates = restoredTemplates,
            storageLoaded = storageLoaded,
        )
    }

    private suspend fun installDraft(profile: SupplierProfile) {
        val credentials = withContext(Dispatchers.IO) { loadStartupCredentials(profile) }
        _uiState.update {
            it.copy(
                draft = SupplierDraft.from(profile, credentials.apiKey),
                activeSupplierId = profile.id,
                errors = FormErrors(),
            )
        }
        _balanceUiState.update {
            it.copy(
                credentials = BalanceCredentialsDraft.from(profile, credentials.balanceAccessToken),
                credentialErrors = BalanceCredentialsErrors(),
            )
        }
    }

    /**
     * API Key and balance PAT are independent encrypted records. Reading them
     * concurrently removes the old serial Keystore tail while preserving the
     * same in-memory values and AES-GCM storage protocol.
     */
    private suspend fun loadStartupCredentials(profile: SupplierProfile): StartupCredentials = coroutineScope {
        val apiKey = async {
            profile.apiKeySecretId?.let(secretStore::get).orEmpty()
        }
        val balanceAccessToken = async {
            profile.balanceAccessTokenSecretId?.let(secretStore::get).orEmpty()
        }
        StartupCredentials(
            apiKey = apiKey.await(),
            balanceAccessToken = balanceAccessToken.await(),
        )
    }

    /** Clears the short initial lock without changing either temporarily empty credential field. */
    private fun finishStartupSecretHydration() {
        _uiState.update { it.copy(isSecretsHydrating = false) }
        _balanceUiState.update { it.copy(isSecretsHydrating = false) }
    }

    /**
     * Merges a late Keystore read without overwriting anything the user typed
     * after the short startup protection window elapsed.
     */
    private fun applyStartupCredentials(
        profile: SupplierProfile,
        loadedCredentials: StartupCredentials,
    ) {
        _uiState.update { state ->
            val draft = state.draft
            state.copy(
                isSecretsHydrating = false,
                draft = if (
                    state.activeSupplierId == profile.id &&
                    draft?.id == profile.id &&
                    !draft.apiKeyDirty
                ) {
                    draft.copy(
                        apiKey = loadedCredentials.apiKey,
                        apiKeySecretId = profile.apiKeySecretId,
                        apiKeyDirty = false,
                    )
                } else {
                    draft
                },
            )
        }
        _balanceUiState.update { state ->
            val credentials = state.credentials
            state.copy(
                isSecretsHydrating = false,
                credentials = if (
                    state.activeSupplierId == profile.id &&
                    !credentials.accessTokenDirty
                ) {
                    credentials.copy(
                        accessToken = loadedCredentials.balanceAccessToken,
                        accessTokenSecretId = profile.balanceAccessTokenSecretId,
                        accessTokenDirty = false,
                    )
                } else {
                    credentials
                },
            )
        }
    }

    private fun updateDraft(transform: SupplierDraft.() -> SupplierDraft) {
        if (runningOrInitializing()) return
        _uiState.update { state ->
            val draft = state.draft ?: return@update state
            state.copy(
                draft = draft.transform(),
                errors = FormErrors(),
                message = null,
            )
        }
    }

    private suspend fun prepareRequest(): PreparedRequest? {
        val draft = _uiState.value.draft ?: return null
        // A slow Keystore service can outlive the short startup interaction
        // window. In that narrow interval the visible field is still empty,
        // even though the saved secret exists. Resolve it only for the user's
        // explicit request so an existing key is never mistaken for missing.
        val requestApiKey = resolveRequestApiKey(draft)
        val errors = FormErrors(
            baseUrl = if (draft.baseUrl.isBlank()) "请填写中转站 Base URL" else null,
            apiKey = if (requestApiKey.isBlank()) "请填写 API Key" else null,
            rateLimit = if (draft.delayMaxSeconds.toMilliseconds(0) < draft.delayMinSeconds.toMilliseconds(0)) {
                "请求间隔上限不能小于下限"
            } else {
                null
            },
        )
        if (listOf(errors.baseUrl, errors.apiKey, errors.rateLimit).any { it != null }) {
            _uiState.update { it.copy(errors = errors, message = "请先修正配置项", isMessageError = true) }
            return null
        }
        val saved = persistDraft() ?: return null
        return PreparedRequest(saved, requestApiKey)
    }

    private suspend fun resolveRequestApiKey(draft: SupplierDraft): String {
        if (draft.apiKeyDirty || draft.apiKey.isNotBlank()) return draft.apiKey
        val secretId = draft.apiKeySecretId ?: return ""
        return withContext(Dispatchers.IO) { secretStore.get(secretId) }.orEmpty()
    }

    private suspend fun persistDraft(): SupplierProfile? {
        return try {
            val draft = _uiState.value.draft ?: return null
            val existing = profiles.firstOrNull { it.id == draft.id } ?: return null
            val oldSecretId = existing.apiKeySecretId
            var createdSecretId: String? = null
            val secretId = if (draft.apiKeyDirty) {
                if (draft.apiKey.isBlank()) {
                    null
                } else {
                    withContext(Dispatchers.IO) { secretStore.put(draft.apiKey.trim()) }
                        .also { createdSecretId = it }
                }
            } else {
                oldSecretId
            }
            val profile = draft.toProfile(
                apiKeySecretId = secretId,
                balanceAccessTokenSecretId = existing.balanceAccessTokenSecretId,
                balanceUserId = existing.balanceUserId,
            )
            if (!replaceProfile(profile)) {
                createdSecretId?.let { createdId ->
                    withContext(Dispatchers.IO) { runCatching { secretStore.delete(createdId) } }
                }
                return null
            }
            if (draft.apiKeyDirty && oldSecretId != null && oldSecretId != secretId) {
                withContext(Dispatchers.IO) { runCatching { secretStore.delete(oldSecretId) } }
            }
            _uiState.update {
                it.copy(
                    draft = SupplierDraft.from(profile, draft.apiKey),
                    suppliers = profiles.toList(),
                )
            }
            profile
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            showMessage("站点密钥保存失败，请稍后重试", isError = true)
            null
        }
    }

    private suspend fun persistBalanceCredentials(
        showSuccessMessage: Boolean = false,
    ): SupplierProfile? {
        return try {
            val activeId = activeSupplierId ?: return null
            val existing = profiles.firstOrNull { it.id == activeId } ?: return null
            val credentials = _balanceUiState.value.credentials
            val oldSecretId = existing.balanceAccessTokenSecretId
            var createdSecretId: String? = null
            val secretId = if (credentials.accessTokenDirty) {
                if (credentials.accessToken.isBlank()) {
                    null
                } else {
                    withContext(Dispatchers.IO) { secretStore.put(credentials.accessToken.trim()) }
                        .also { createdSecretId = it }
                }
            } else {
                oldSecretId
            }
            val profile = existing.copy(
                balanceAccessTokenSecretId = secretId,
                balanceUserId = credentials.userId.trim(),
            )
            if (!replaceProfile(profile)) {
                createdSecretId?.let { createdId ->
                    withContext(Dispatchers.IO) { runCatching { secretStore.delete(createdId) } }
                }
                return null
            }
            if (credentials.accessTokenDirty && oldSecretId != null && oldSecretId != secretId) {
                withContext(Dispatchers.IO) { runCatching { secretStore.delete(oldSecretId) } }
            }
            _balanceUiState.update {
                it.copy(
                    credentials = BalanceCredentialsDraft.from(profile, credentials.accessToken.trim()),
                    credentialErrors = BalanceCredentialsErrors(),
                    message = if (showSuccessMessage) "余额查询凭据已保存" else it.message,
                    isMessageError = if (showSuccessMessage) false else it.isMessageError,
                )
            }
            profile
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            _balanceUiState.update {
                it.copy(
                    credentialErrors = BalanceCredentialsErrors(accessToken = "令牌无法保存，请稍后重试"),
                    message = "余额查询凭据保存失败，请稍后重试",
                    isMessageError = true,
                )
            }
            null
        }
    }

    private suspend fun replaceProfile(profile: SupplierProfile): Boolean {
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index < 0) return false
        val previous = profiles[index]
        profiles[index] = profile
        if (saveProfiles()) return true
        profiles[index] = previous
        return false
    }

    private suspend fun saveProfiles(): Boolean {
        val stateToSave = SupplierStoreState(
            suppliers = profiles.toList(),
            activeSupplierId = activeSupplierId,
            balanceTemplates = balanceTemplates.toList(),
        )
        try {
            withContext(Dispatchers.IO) { supplierStore.save(stateToSave) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Keep the caller's pre-save state intact. A failed configuration
            // write must not make the UI claim a durable edit or invalidate an
            // older secret reference.
            showMessage("本地配置保存失败，请检查存储空间后重试", isError = true)
            showBalanceMessage("本地配置保存失败，请检查存储空间后重试", isError = true)
            return false
        }
        publishBalanceState()
        return true
    }

    private fun publishBalanceState(isInitializing: Boolean = _balanceUiState.value.isInitializing) {
        _balanceUiState.update { state ->
            state.copy(
                isInitializing = isInitializing,
                suppliers = profiles.toList(),
                activeSupplierId = activeSupplierId,
                templates = balanceTemplates.toList(),
            )
        }
    }

    private suspend fun startRun(
        request: PreparedRequest,
        targets: List<String>,
        replaceAll: Boolean,
    ) {
        val pending = targets.associateWith(ModelTestResult::pending)
        val initialResults = _uiState.value.let { state ->
            val results = if (replaceAll) {
                targets.map { pending.getValue(it) }
            } else {
                state.results.map { pending[it.model] ?: it }
            }
            results
        }
        val resultIndexByModel = initialResults.indices.associateBy { index ->
            initialResults[index].model
        }
        val latestResults = initialResults.toMutableList()
        val publishMutex = Mutex()
        var lastPublishedAtNanos = 0L
        _uiState.update { state ->
            state.copy(
                isRunning = true,
                progressDone = 0,
                progressTotal = targets.size,
                results = initialResults,
                summary = null,
                message = null,
            )
        }
        // OkHttp and its supporting classes are intentionally initialized after
        // the user starts a run, never while the Activity is creating its first frame.
        val runner = withContext(Dispatchers.Default) { batchTestRunner }
        testJob = viewModelScope.launch {
            try {
                val summary = runner.run(
                    config = BatchTestConfig(
                        supplier = request.profile,
                        apiKey = request.apiKey,
                        models = targets,
                        settings = request.profile.testSettings,
                    ),
                ) { result, completed, total ->
                    val update = publishMutex.withLock {
                        resultIndexByModel[result.model]?.let { index ->
                            latestResults[index] = result
                        }
                        val now = System.nanoTime()
                        if (completed == total ||
                            now - lastPublishedAtNanos >= RESULT_UI_UPDATE_INTERVAL_NANOS
                        ) {
                            lastPublishedAtNanos = now
                            RunUiUpdate(
                                results = latestResults.toList(),
                                completed = completed,
                                total = total,
                            )
                        } else {
                            null
                        }
                    }
                    update?.let { current ->
                        _uiState.update { state ->
                            state.copy(
                                results = current.results,
                                progressDone = current.completed,
                                progressTotal = current.total,
                            )
                        }
                    }
                }
                _uiState.update { it.copy(isRunning = false, summary = summary) }
            } catch (_: CancellationException) {
                _uiState.update { it.copy(isRunning = false) }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        message = error.message ?: "批量测试中断",
                        isMessageError = true,
                    )
                }
            }
        }
    }

    private fun showMessage(message: String, isError: Boolean = false) {
        _uiState.update { it.copy(message = message, isMessageError = isError) }
    }

    private fun runningOrInitializing(): Boolean =
        _uiState.value.isRunning ||
            _uiState.value.isInitializing ||
            _uiState.value.isSecretsHydrating ||
            _configurationBackupUiState.value.isBusy

    private fun balanceOperationBlocked(): Boolean =
        _balanceUiState.value.isInitializing ||
            _balanceUiState.value.isSecretsHydrating ||
            _balanceUiState.value.isQuerying ||
            _configurationBackupUiState.value.isBusy

    private fun isConfigurationTransferAllowed(): Boolean =
        !_uiState.value.isInitializing &&
            !_uiState.value.isSecretsHydrating &&
            !_uiState.value.isRunning &&
            !_uiState.value.isFetchingModels &&
            !_balanceUiState.value.isInitializing &&
            !_balanceUiState.value.isSecretsHydrating &&
            !_balanceUiState.value.isQuerying &&
            !_configurationBackupUiState.value.isBusy

    private fun showConfigurationBackupMessage(message: String, isError: Boolean = false) {
        _configurationBackupUiState.update {
            it.copy(message = message, isMessageError = isError)
        }
    }

    private data class PreparedRequest(
        val profile: SupplierProfile,
        val apiKey: String,
    )

    private data class BalancePreparedRequest(
        val profile: SupplierProfile,
        val apiKey: String,
        val accessToken: String,
        val userId: String,
        val template: BalanceQueryTemplate,
    )

    /** Display-safe configuration published in the first non-loading screen state. */
    private data class StartupSnapshot(
        val profiles: List<SupplierProfile>,
        val activeSupplierId: String,
        val templates: List<BalanceQueryTemplate>,
        val storageLoaded: Boolean,
    )

    /** Short-lived, active-site values read only after the first usable UI frame is published. */
    private data class StartupCredentials(
        val apiKey: String,
        val balanceAccessToken: String,
    )

    /** Plaintext only during the active import transaction; never persisted directly. */
    private data class ImportedConfigurationSupplier(
        val profile: SupplierProfile,
        val apiKey: String,
        val balanceAccessToken: String,
    )

    private sealed interface BalanceRequestPreparation {
        data class Ready(val request: BalancePreparedRequest) : BalanceRequestPreparation
        data class Invalid(val message: String) : BalanceRequestPreparation
    }

    private data class RunUiUpdate(
        val results: List<ModelTestResult>,
        val completed: Int,
        val total: Int,
    )

    private fun List<String>.filterBy(keyword: String): List<String> = if (keyword.isBlank()) {
        this
    } else {
        filter { it.contains(keyword, ignoreCase = true) }
    }

    private fun ModelTestResult.toJson(): JSONObject = JSONObject()
        .put("model", model)
        .put("status", status.name)
        .put("latency_ms", latencyMs)
        .put("http_status", httpStatus)
        .put("finish_reason", finishReason)
        .put(
            "usage",
            usage?.let {
                JSONObject()
                    .put("input_tokens", it.inputTokens)
                    .put("output_tokens", it.outputTokens)
                    .put("total_tokens", it.totalTokens)
            },
        )
        .put(
            "error",
            error?.let {
                JSONObject()
                    .put("kind", it.kind.name)
                    .put("message", it.message)
            },
        )

    companion object {
        private const val RESULT_UI_UPDATE_INTERVAL_NANOS = 120_000_000L
        // This only guards the brief state where an empty, not-yet-hydrated
        // draft could be saved. Explicit requests resolve saved credentials on
        // demand, so keeping it short improves startup without losing safety.
        private const val STARTUP_SECRET_INTERACTION_BLOCK_MS = 250L

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras,
            ): T {
                val applicationContext = context.applicationContext
                return TesterViewModel(
                    supplierStore = SupplierStore(applicationContext),
                    secretStore = KeystoreSecretStore(applicationContext),
                    relayApiFactory = { RelayApi() },
                    balanceApiFactory = { BalanceApi() },
                ) as T
            }
        }
    }
}
