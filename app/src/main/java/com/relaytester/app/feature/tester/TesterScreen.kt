package com.relaytester.app.feature.tester

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relaytester.app.core.model.ModelTestResult
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestStatus
import com.relaytester.app.core.model.matchesAnyModelFilterTerm
import com.relaytester.app.core.model.mergeModelFilterTerms
import com.relaytester.app.ui.navigation.AppDestination
import com.relaytester.app.ui.components.RelayAppHeader
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Mirrors the quick prompts provided by the initial relay-tester web source. */
private val TEST_PROMPT_PRESETS = listOf("ping", "Hi", "Say OK", "1+1=?", "回复ok")

private data class FilteredModelsState(
    val values: List<String>,
    val isLoading: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TesterScreen(
    viewModel: TesterViewModel,
    modifier: Modifier = Modifier,
    activeDestination: AppDestination = AppDestination.MODEL_TEST,
    onDestinationSelected: (AppDestination) -> Unit = {},
    onConfigurationBackup: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingExport by remember { mutableStateOf<ExportPayload?>(null) }
    // The configuration card already fills the first viewport. Keeping the
    // below-fold form out of the first composition prevents LazyColumn's
    // prefetch from building every input control immediately after launch.
    var showDeferredContent by remember { mutableStateOf(false) }
    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val export = pendingExport
        pendingExport = null
        if (uri == null || export == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    writer.write(export.json)
                }
            } ?: error("无法打开所选文件")
        }.onSuccess {
            Toast.makeText(context, "已导出测试结果", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "导出失败：" + (it.message ?: "未知错误"), Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(state.isInitializing, state.isSecretsHydrating) {
        if (state.isInitializing || state.isSecretsHydrating) {
            showDeferredContent = false
        } else {
            // This runs after the first content composition and never blocks
            // input or the main thread. The compact delay only separates the
            // first viewport from non-visible form/list composition.
            delay(INITIAL_DEFERRED_CONTENT_DELAY_MS)
            showDeferredContent = true
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            RelayAppHeader(
                subtitle = "中转站模型批量验证",
                selectedDestination = activeDestination,
                onDestinationSelected = onDestinationSelected,
                onConfigurationBackup = onConfigurationBackup,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        if (state.isInitializing || state.draft == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            TesterContent(
                state = state,
                visibleResults = remember(
                    state.results,
                    state.filter,
                    state.sort,
                    state.resultQuery,
                    state.selectedQuickFilterTerms,
                ) { viewModel.visibleResults() },
                onNameChange = viewModel::updateName,
                onBaseUrlChange = viewModel::updateBaseUrl,
                onProtocolChange = viewModel::updateProtocol,
                onApiKeyChange = viewModel::updateApiKey,
                onSettingChange = viewModel::updateSetting,
                onToggleQuickFilter = viewModel::toggleQuickFilterTerm,
                onAddQuickFilters = viewModel::addQuickFilterTerms,
                onRemoveQuickFilter = viewModel::removeQuickFilterTerm,
                onSaveSupplier = viewModel::saveCurrentSupplier,
                onSelectSupplier = viewModel::selectSupplier,
                onAddSupplier = viewModel::addSupplier,
                onDeleteSupplier = viewModel::deleteCurrentSupplier,
                onFetchModels = viewModel::fetchModels,
                onStartTest = viewModel::startTest,
                onCancelRun = viewModel::cancelRun,
                onRetryFailed = viewModel::retryFailed,
                onRetestAll = viewModel::retestAll,
                onFilterChange = viewModel::updateFilter,
                onSortChange = viewModel::updateSort,
                onQueryChange = viewModel::updateResultQuery,
                onCopy = { label, values -> copyNames(context, label, values) },
                onExport = {
                    val export = viewModel.buildExportPayload()
                    if (export == null) {
                        Toast.makeText(context, "没有可导出的测试结果", Toast.LENGTH_SHORT).show()
                    } else {
                        pendingExport = export
                        createDocument.launch(export.fileName)
                    }
                },
                showDeferredContent = showDeferredContent,
                contentPadding = innerPadding,
            )
        }
    }
}

@Composable
private fun TesterContent(
    state: TesterUiState,
    visibleResults: List<ModelTestResult>,
    onNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onProtocolChange: (RelayProtocol) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSettingChange: (SettingField, String) -> Unit,
    onToggleQuickFilter: (String) -> Unit,
    onAddQuickFilters: (String) -> Unit,
    onRemoveQuickFilter: (String) -> Unit,
    onSaveSupplier: () -> Unit,
    onSelectSupplier: (String) -> Unit,
    onAddSupplier: () -> Unit,
    onDeleteSupplier: () -> Unit,
    onFetchModels: () -> Unit,
    onStartTest: () -> Unit,
    onCancelRun: () -> Unit,
    onRetryFailed: () -> Unit,
    onRetestAll: () -> Unit,
    onFilterChange: (ResultFilter) -> Unit,
    onSortChange: (ResultSort) -> Unit,
    onQueryChange: (String) -> Unit,
    onCopy: (String, List<String>) -> Unit,
    onExport: () -> Unit,
    showDeferredContent: Boolean,
    contentPadding: PaddingValues,
) {
    val draft = requireNotNull(state.draft)
    val disabled = state.isRunning || state.isFetchingModels || state.isSecretsHydrating
    val modelFilterTerms = remember(draft.keyword, state.selectedQuickFilterTerms) {
        mergeModelFilterTerms(draft.keyword, state.selectedQuickFilterTerms)
    }
    val filteredModels by produceState(
        initialValue = FilteredModelsState(values = emptyList(), isLoading = true),
        key1 = draft.models,
        key2 = modelFilterTerms,
    ) {
        value = withContext(Dispatchers.Default) {
            FilteredModelsState(
                values = draft.models.filter {
                    it.matchesAnyModelFilterTerm(modelFilterTerms)
                },
                isLoading = false,
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "supplier_selector", contentType = "supplier_selector") {
            SupplierSelector(
                suppliers = state.suppliers,
                activeSupplierId = state.activeSupplierId,
                enabled = !disabled,
                onSelect = onSelectSupplier,
                onAdd = onAddSupplier,
                onDelete = onDeleteSupplier,
            )
        }
        item(key = "supplier_config", contentType = "supplier_config") {
            SupplierConfigurationCard(
                draft = draft,
                errors = state.errors,
                enabled = !disabled,
                isSecretsHydrating = state.isSecretsHydrating,
                onNameChange = onNameChange,
                onBaseUrlChange = onBaseUrlChange,
                onProtocolChange = onProtocolChange,
                onApiKeyChange = onApiKeyChange,
                onSave = onSaveSupplier,
            )
        }
        if (showDeferredContent) {
            item(key = "test_settings", contentType = "test_settings") {
                TestSettingsCard(
                    draft = draft,
                    errors = state.errors,
                    enabled = !disabled,
                    onSettingChange = onSettingChange,
                    selectedQuickFilters = state.selectedQuickFilterTerms,
                    onToggleQuickFilter = onToggleQuickFilter,
                    onAddQuickFilters = onAddQuickFilters,
                    onRemoveQuickFilter = onRemoveQuickFilter,
                    onFetchModels = onFetchModels,
                    onStartTest = onStartTest,
                    onCancel = onCancelRun,
                    isFetchingModels = state.isFetchingModels,
                    isRunning = state.isRunning,
                )
            }
            if (state.results.isNotEmpty()) {
                resultSection(
                    state = state,
                    visibleResults = visibleResults,
                    onFilterChange = onFilterChange,
                    onSortChange = onSortChange,
                    onQueryChange = onQueryChange,
                    onRetryFailed = onRetryFailed,
                    onRetestAll = onRetestAll,
                    onCopy = onCopy,
                    onExport = onExport,
                )
            } else if (draft.models.isNotEmpty()) {
                if (filteredModels.isLoading) {
                    item(key = "fetched_models_preparing", contentType = "fetched_models_preparing") {
                        SectionTitle("已获取模型", "正在后台整理模型列表…")
                    }
                } else {
                    fetchedModelsSection(
                        models = filteredModels.values,
                        totalModels = draft.models.size,
                    )
                }
            }
            item(key = "security_note", contentType = "security_note") {
                SecurityNote()
            }
        }
    }
}

private const val INITIAL_DEFERRED_CONTENT_DELAY_MS = 350L

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SupplierSelector(
    suppliers: List<SupplierProfile>,
    activeSupplierId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("供应商", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = onAdd,
                    enabled = enabled,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "添加供应商",
                    )
                }
                IconButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "删除当前供应商",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cardWidth = (maxWidth - 8.dp) / 2
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 2,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suppliers.forEach { supplier ->
                    val selected = supplier.id == activeSupplierId
                    OutlinedCard(
                        modifier = Modifier
                            .width(cardWidth)
                            .heightIn(min = 52.dp)
                            .clickable(enabled = enabled, role = Role.Tab) { onSelect(supplier.id) }
                            .clearAndSetSemantics {
                                role = Role.Tab
                                this.selected = selected
                                contentDescription = "选择供应商 ${supplier.name}"
                                onClick(label = "选择供应商") {
                                    onSelect(supplier.id)
                                    true
                                }
                            },
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column {
                                Text(
                                    text = supplier.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Text(
                                    text = buildAnnotatedString {
                                        pushStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant))
                                        append(supplier.protocol.label)
                                        append(" · ")
                                        pop()
                                        pushStyle(
                                            SpanStyle(
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold,
                                            ),
                                        )
                                        append(supplier.models.size.toString())
                                        pop()
                                        pushStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant))
                                        append(" 个模型")
                                        pop()
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            "不同站点的模型列表、测试参数和密钥相互隔离。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SupplierConfigurationCard(
    draft: SupplierDraft,
    errors: FormErrors,
    enabled: Boolean,
    isSecretsHydrating: Boolean,
    onNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onProtocolChange: (RelayProtocol) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    var revealApiKey by rememberSaveable(draft.id) { mutableStateOf(false) }
    ElevatedCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("站点配置", "模型测试直接从本机请求目标站点")
            AppTextField(
                value = draft.name,
                onValueChange = onNameChange,
                label = "供应商名称",
                placeholder = "例如：主账号 A",
                enabled = enabled,
            )
            AppTextField(
                value = draft.baseUrl,
                onValueChange = onBaseUrlChange,
                label = "中转站地址（Base URL）",
                placeholder = "https://your-relay.example.com/v1",
                enabled = enabled,
                errorMessage = errors.baseUrl,
                keyboardType = KeyboardType.Uri,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("接口协议", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RelayProtocol.entries.forEach { protocol ->
                        SelectablePill(
                            label = protocol.label,
                            selected = draft.protocol == protocol,
                            onClick = { onProtocolChange(protocol) },
                            enabled = enabled,
                            contentDescription = "使用接口协议：${protocol.label}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    protocolDescription(draft.protocol),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppTextField(
                value = draft.apiKey,
                onValueChange = onApiKeyChange,
                label = "API Key",
                placeholder = "sk-...",
                enabled = enabled,
                errorMessage = errors.apiKey,
                keyboardType = KeyboardType.Password,
                visualTransformation = if (revealApiKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(
                        onClick = { revealApiKey = !revealApiKey },
                        enabled = enabled,
                    ) {
                        Icon(
                            imageVector = if (revealApiKey) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = if (revealApiKey) "隐藏 API Key" else "显示 API Key",
                        )
                    }
                },
            )
            Text(
                "密钥由 Android Keystore 加密保存。测试结果 JSON 不含密钥；加密配置备份会包含密钥。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isSecretsHydrating) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        "正在安全读取本机凭据…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onSave, enabled = enabled) {
                    Text("保存站点")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestSettingsCard(
    draft: SupplierDraft,
    errors: FormErrors,
    enabled: Boolean,
    onSettingChange: (SettingField, String) -> Unit,
    selectedQuickFilters: Set<String>,
    onToggleQuickFilter: (String) -> Unit,
    onAddQuickFilters: (String) -> Unit,
    onRemoveQuickFilter: (String) -> Unit,
    onFetchModels: () -> Unit,
    onStartTest: () -> Unit,
    onCancel: () -> Unit,
    isFetchingModels: Boolean,
    isRunning: Boolean,
) {
    var showAdvanced by rememberSaveable(draft.id) { mutableStateOf(false) }
    var showQuickFilterDialog by rememberSaveable(draft.id) { mutableStateOf(false) }
    var quickFilterInput by rememberSaveable(draft.id) { mutableStateOf("") }
    ElevatedCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("测试参数", "并发、限速和重试均在设备本机执行")
            NumberRow(
                firstLabel = "超时（秒）",
                firstValue = draft.timeoutSeconds,
                firstChange = { onSettingChange(SettingField.TIMEOUT_SECONDS, it) },
                secondLabel = "并发数",
                secondValue = draft.concurrency,
                secondChange = { onSettingChange(SettingField.CONCURRENCY, it) },
                enabled = enabled,
            )
            AppTextField(
                value = draft.prompt,
                onValueChange = { onSettingChange(SettingField.PROMPT, it) },
                label = "测试 Prompt",
                placeholder = "ping",
                enabled = enabled,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "快速选择",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TEST_PROMPT_PRESETS.forEach { preset ->
                        SelectablePill(
                            label = preset,
                            selected = draft.prompt == preset,
                            enabled = enabled,
                            contentDescription = "使用测试 Prompt：$preset",
                            onClick = { onSettingChange(SettingField.PROMPT, preset) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            AppTextField(
                value = draft.keyword,
                onValueChange = { onSettingChange(SettingField.KEYWORD, it) },
                label = "模型名过滤（OR）",
                placeholder = "例如 gpt|claude",
                enabled = enabled,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "快捷筛选",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "多选和输入框中的 | 均为 OR",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { showQuickFilterDialog = true },
                        enabled = enabled,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "添加快捷筛选词")
                    }
                }
                if (draft.quickFilterTerms.isEmpty()) {
                    Text(
                        "添加常用模型词后，可一键组合筛选。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        draft.quickFilterTerms.forEach { term ->
                            InputChip(
                                selected = term in selectedQuickFilters,
                                onClick = { onToggleQuickFilter(term) },
                                label = {
                                    Text(
                                        term,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { onRemoveQuickFilter(term) },
                                        enabled = enabled,
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(Icons.Outlined.Close, contentDescription = "删除快捷筛选词 $term")
                                    }
                                },
                                enabled = enabled,
                                modifier = Modifier
                                    .widthIn(max = 200.dp)
                                    .heightIn(min = 48.dp)
                                    .semantics { contentDescription = "模型快捷筛选词：$term" },
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { showAdvanced = !showAdvanced },
                enabled = enabled,
                modifier = Modifier.align(Alignment.Start),
            ) {
                Text(if (showAdvanced) "收起高级设置" else "高级设置（限速 / 分批 / 重试）")
            }
            if (showAdvanced) {
                NumberRow(
                    firstLabel = "max_tokens",
                    firstValue = draft.maxTokens,
                    firstChange = { onSettingChange(SettingField.MAX_TOKENS, it) },
                    secondLabel = "失败重试次数",
                    secondValue = draft.retryCount,
                    secondChange = { onSettingChange(SettingField.RETRY_COUNT, it) },
                    enabled = enabled,
                )
                NumberRow(
                    firstLabel = "间隔下限（秒）",
                    firstValue = draft.delayMinSeconds,
                    firstChange = { onSettingChange(SettingField.DELAY_MIN_SECONDS, it) },
                    secondLabel = "间隔上限（秒）",
                    secondValue = draft.delayMaxSeconds,
                    secondChange = { onSettingChange(SettingField.DELAY_MAX_SECONDS, it) },
                    enabled = enabled,
                    errorMessage = errors.rateLimit,
                )
                NumberRow(
                    firstLabel = "每批数量",
                    firstValue = draft.batchSize,
                    firstChange = { onSettingChange(SettingField.BATCH_SIZE, it) },
                    secondLabel = "批间暂停（秒）",
                    secondValue = draft.batchPauseSeconds,
                    secondChange = { onSettingChange(SettingField.BATCH_PAUSE_SECONDS, it) },
                    enabled = enabled,
                )
                Text(
                    "默认只重试网络、超时、429 和 5xx；认证、余额或模型不存在错误不会重复请求。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            if (isRunning) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Outlined.StopCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("取消测试")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onFetchModels,
                        enabled = !isFetchingModels,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (isFetchingModels) "获取中…" else "获取模型")
                    }
                    Button(
                        onClick = onStartTest,
                        enabled = !isFetchingModels,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("开始测试")
                    }
                }
            }
        }
    }

    if (showQuickFilterDialog) {
        AlertDialog(
            onDismissRequest = { showQuickFilterDialog = false },
            title = { Text("添加快捷筛选词") },
            text = {
                AppTextField(
                    value = quickFilterInput,
                    onValueChange = { quickFilterInput = it },
                    label = "模型词",
                    placeholder = "例如 gpt|claude",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddQuickFilters(quickFilterInput)
                        quickFilterInput = ""
                        showQuickFilterDialog = false
                    },
                    enabled = quickFilterInput.trim().isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showQuickFilterDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("取消") }
            },
        )
    }
}

/**
 * Shared protocol/prompt choice control: a compact 36dp visual pill inside a
 * stable 48dp touch target, so the two groups read as one control family.
 */
@Composable
private fun SelectablePill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.small
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .widthIn(min = 48.dp)
            .height(48.dp)
            .clip(shape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics {
                this.selected = selected
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            shape = shape,
            border = BorderStroke(
                width = 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
            ),
            color = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun LazyListScope.fetchedModelsSection(
    models: List<String>,
    totalModels: Int,
) {
    item(key = "fetched_models_header", contentType = "fetched_models_header") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle(
                "已获取模型",
                if (models.size == totalModels) {
                    "共 " + totalModels + " 个"
                } else {
                    "匹配 " + models.size + " / " + totalModels + " 个"
                },
            )
            Text(
                "开始测试后将在同一列表中显示延迟、用量和错误详情。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (models.isEmpty()) {
        item(key = "empty_fetched_models", contentType = "empty_results") {
            OutlinedCard {
                Text(
                    "没有匹配的模型，请调整过滤条件。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        items(models, key = { it }, contentType = { "result_item" }) { model ->
            ResultItem(
                result = ModelTestResult.pending(model),
                isFetchedOnly = true,
            )
        }
    }
}

private fun LazyListScope.resultSection(
    state: TesterUiState,
    visibleResults: List<ModelTestResult>,
    onFilterChange: (ResultFilter) -> Unit,
    onSortChange: (ResultSort) -> Unit,
    onQueryChange: (String) -> Unit,
    onRetryFailed: () -> Unit,
    onRetestAll: () -> Unit,
    onCopy: (String, List<String>) -> Unit,
    onExport: () -> Unit,
) {
    item(key = "result_summary", contentType = "result_summary") {
        ResultSummaryCard(state = state)
    }
    item(key = "result_filters", contentType = "result_filters") {
        ResultFilters(
            state = state,
            onFilterChange = onFilterChange,
            onSortChange = onSortChange,
            onQueryChange = onQueryChange,
        )
    }
    if (visibleResults.isEmpty()) {
        item(key = "empty_results", contentType = "empty_results") {
            OutlinedCard {
                Text(
                    "没有匹配的结果",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        items(visibleResults, key = { it.model }, contentType = { "result_item" }) { result ->
            ResultItem(result = result)
        }
    }
    item(key = "result_actions", contentType = "result_actions") {
        ResultActions(
            state = state,
            onRetryFailed = onRetryFailed,
            onRetestAll = onRetestAll,
            onCopy = onCopy,
            onExport = onExport,
        )
    }
}

@Composable
private fun ResultSummaryCard(state: TesterUiState) {
    val (total, succeeded, failed) = remember(state.results) {
        Triple(
            state.results.size,
            state.results.count { it.status == TestStatus.SUCCESS },
            state.results.count { it.status == TestStatus.FAILED },
        )
    }
    val summary = state.summary
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("测试结果", "可用 " + succeeded + " · 不可用 " + failed)
            if (state.isRunning && state.progressTotal > 0) {
                val progress = state.progressDone.toFloat() / state.progressTotal.toFloat()
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "已完成 " + state.progressDone + " / " + state.progressTotal,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryMetric("总模型", total.toString(), Modifier.weight(1f))
                SummaryMetric(
                    "平均延迟",
                    summary?.averageLatencyMs?.let { it.toString() + " ms" } ?: "—",
                    Modifier.weight(1f),
                )
                SummaryMetric("总 token", summary?.totalTokens?.toString() ?: "—", Modifier.weight(1f))
            }
            if (summary != null) {
                Text(
                    "耗时 " + formatElapsed(summary.elapsedMs) +
                        " · 最快 " + (summary.fastestLatencyMs?.toString()?.plus(" ms") ?: "—") +
                        " · 最慢 " + (summary.slowestLatencyMs?.toString()?.plus(" ms") ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (summary.errorCounts.isNotEmpty()) {
                    Text(
                        summary.errorCounts.entries
                            .sortedByDescending { it.value }
                            .joinToString("  ·  ") { it.key.label + " " + it.value },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultFilters(
    state: TesterUiState,
    onFilterChange: (ResultFilter) -> Unit,
    onSortChange: (ResultSort) -> Unit,
    onQueryChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.filter == ResultFilter.ALL,
                onClick = { onFilterChange(ResultFilter.ALL) },
                label = { Text("全部") },
            )
            FilterChip(
                selected = state.filter == ResultFilter.AVAILABLE,
                onClick = { onFilterChange(ResultFilter.AVAILABLE) },
                label = { Text("可用") },
            )
            FilterChip(
                selected = state.filter == ResultFilter.FAILED,
                onClick = { onFilterChange(ResultFilter.FAILED) },
                label = { Text("失败") },
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = {
                    onSortChange(
                        if (state.sort == ResultSort.LATENCY) ResultSort.NAME else ResultSort.LATENCY,
                    )
                },
            ) {
                Text(if (state.sort == ResultSort.LATENCY) "按延迟" else "按名称")
            }
        }
        AppTextField(
            value = state.resultQuery,
            onValueChange = onQueryChange,
            label = "搜索测试结果（OR）",
            placeholder = "例如 gpt|claude",
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null)
            },
        )
    }
}

@Composable
private fun ResultItem(
    result: ModelTestResult,
    isFetchedOnly: Boolean = false,
) {
    val statusColor = when (result.status) {
        TestStatus.SUCCESS -> Color(0xFF16A34A)
        TestStatus.FAILED -> MaterialTheme.colorScheme.error
        TestStatus.PENDING -> MaterialTheme.colorScheme.tertiary
    }
    OutlinedCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(10.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(statusColor),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        result.model,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusLabel(
                        status = result.status,
                        isFetchedOnly = isFetchedOnly,
                    )
                }
                when (result.status) {
                    TestStatus.SUCCESS -> {
                        val meta = buildList {
                            result.latencyMs?.let { add(it.toString() + " ms") }
                            result.finishReason?.let { add(finishReasonLabel(it)) }
                            result.usage?.resolvedTotal?.takeIf { it > 0 }?.let { add(it.toString() + " tok") }
                        }
                        Text(
                            meta.ifEmpty { listOf("请求成功") }.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    TestStatus.FAILED -> {
                        Text(
                            "HTTP " + (result.httpStatus?.toString() ?: "—") +
                                " · " + (result.error?.kind?.label ?: "请求失败"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        result.error?.message?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    TestStatus.PENDING -> {
                        Text(
                            if (isFetchedOnly) "已获取，尚未开始测试" else "等待测试…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultActions(
    state: TesterUiState,
    onRetryFailed: () -> Unit,
    onRetestAll: () -> Unit,
    onCopy: (String, List<String>) -> Unit,
    onExport: () -> Unit,
) {
    val (successful, failed) = remember(state.results) {
        state.results
            .partition { it.status == TestStatus.SUCCESS }
            .let { (ok, bad) -> ok.map { it.model } to bad.map { it.model } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onRetestAll,
                enabled = !state.isRunning,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("重测全部")
            }
            OutlinedButton(
                onClick = onRetryFailed,
                enabled = !state.isRunning && failed.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text("重测失败项")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onCopy("可用模型", successful) },
                enabled = successful.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("复制可用")
            }
            OutlinedButton(
                onClick = { onCopy("失败模型", failed) },
                enabled = failed.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text("复制失败")
            }
        }
        Button(
            onClick = onExport,
            enabled = !state.isRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("导出测试结果 JSON")
        }
    }
}

@Composable
private fun SecurityNote() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.Security,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            "仅支持 HTTPS。测试结果 JSON 不含密钥；加密配置备份会包含密钥。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    errorMessage: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        isError = errorMessage != null,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = {
            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun NumberRow(
    firstLabel: String,
    firstValue: String,
    firstChange: (String) -> Unit,
    secondLabel: String,
    secondValue: String,
    secondChange: (String) -> Unit,
    enabled: Boolean,
    errorMessage: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppTextField(
            value = firstValue,
            onValueChange = firstChange,
            label = firstLabel,
            placeholder = "0",
            enabled = enabled,
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
        )
        AppTextField(
            value = secondValue,
            onValueChange = secondChange,
            label = secondLabel,
            placeholder = "0",
            enabled = enabled,
            errorMessage = errorMessage,
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusLabel(status: TestStatus, isFetchedOnly: Boolean = false) {
    val label = when (status) {
        TestStatus.SUCCESS -> "可用"
        TestStatus.FAILED -> "失败"
        TestStatus.PENDING -> if (isFetchedOnly) "待测" else "进行中"
    }
    val color = when (status) {
        TestStatus.SUCCESS -> Color(0xFF15803D)
        TestStatus.FAILED -> MaterialTheme.colorScheme.error
        TestStatus.PENDING -> MaterialTheme.colorScheme.tertiary
    }
    Text(label, style = MaterialTheme.typography.labelMedium, color = color)
}

private fun protocolDescription(protocol: RelayProtocol): String = when (protocol) {
    RelayProtocol.CHAT_COMPLETIONS -> "POST /chat/completions · Authorization: Bearer"
    RelayProtocol.RESPONSES -> "POST /responses · Authorization: Bearer"
    RelayProtocol.ANTHROPIC -> "POST /messages · x-api-key + anthropic-version"
}

private fun finishReasonLabel(value: String): String = when (value) {
    "stop", "end_turn", "stop_sequence", "completed" -> "正常"
    "length", "max_tokens" -> "长度截断"
    else -> value
}

private fun formatElapsed(milliseconds: Long): String =
    String.format(java.util.Locale.ROOT, "%.1f s", milliseconds / 1_000.0)

private fun copyNames(context: Context, label: String, values: List<String>) {
    if (values.isEmpty()) {
        Toast.makeText(context, "没有可复制的" + label, Toast.LENGTH_SHORT).show()
        return
    }
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, values.joinToString("\n")))
    Toast.makeText(context, "已复制 " + values.size + " 个" + label, Toast.LENGTH_SHORT).show()
}
