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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relaytester.app.core.model.ModelTestResult
import com.relaytester.app.core.model.ModelCatalogEntry
import com.relaytester.app.core.model.ModelSource
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestStatus
import com.relaytester.app.feature.tester.UnifiedModelTestResult
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
    var showModelCatalog by rememberSaveable { mutableStateOf(false) }
    var configurationSupplierId by rememberSaveable { mutableStateOf<String?>(null) }
    var supplierToDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
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
                onSettingChange = viewModel::updateSetting,
                onToggleQuickFilter = viewModel::toggleQuickFilterTerm,
                onAddQuickFilters = viewModel::addQuickFilterTerms,
                onRemoveQuickFilter = viewModel::removeQuickFilterTerm,
                onSelectSupplier = viewModel::selectSupplier,
                onRefreshSupplierModels = viewModel::refreshSupplierModels,
                onEditSupplier = { supplierId ->
                    configurationSupplierId = supplierId
                    viewModel.selectSupplier(supplierId)
                },
                onAddSupplier = viewModel::addSupplier,
                onDeleteSupplier = { supplierToDeleteId = state.activeSupplierId },
                onFetchModels = viewModel::fetchModels,
                onStartTest = viewModel::startTest,
                onToggleModel = viewModel::toggleModelSelection,
                onSelectAll = viewModel::selectAllModels,
                onClearAll = viewModel::clearAllModels,
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
                onOpenModelCatalog = { showModelCatalog = true },
                showDeferredContent = showDeferredContent,
                contentPadding = innerPadding,
            )
        }
    }
    if (showModelCatalog && state.draft != null) {
        ModelCatalogDialog(
            state = state,
            onDismiss = {
                viewModel.clearCatalogPickerModels()
                showModelCatalog = false
            },
            onFetchModels = viewModel::fetchCatalogModels,
            onClearPicker = viewModel::clearCatalogPickerModels,
            onSaveEntry = { entryId, name, sources ->
                if (entryId == null) {
                    viewModel.saveModelCatalogEntry(name, sources)
                } else {
                    viewModel.updateModelCatalogEntry(entryId, name, sources)
                }
            },
            onDeleteEntry = viewModel::deleteModelCatalogEntry,
            onRunEntry = viewModel::startUnifiedCatalogTest,
            onCancelRun = viewModel::cancelUnifiedCatalogTest,
        )
    }
    configurationSupplierId?.let { supplierId ->
        val draft = state.draft
        if (state.activeSupplierId == supplierId && draft?.id == supplierId) {
            SupplierConfigurationDialog(
                draft = draft,
                errors = state.errors,
                enabled = !state.isRunning && !state.isUnifiedTesting && !state.isFetchingModels && !state.isSecretsHydrating,
                isSecretsHydrating = state.isSecretsHydrating,
                onDismiss = { configurationSupplierId = null },
                onNameChange = viewModel::updateName,
                onBaseUrlChange = viewModel::updateBaseUrl,
                onProtocolChange = viewModel::updateProtocol,
                onApiKeyChange = viewModel::updateApiKey,
                onSave = viewModel::saveCurrentSupplier,
            )
        }
    }
    supplierToDeleteId?.let { supplierId ->
        val supplier = state.suppliers.firstOrNull { it.id == supplierId }
        if (supplier != null) {
            AlertDialog(
                onDismissRequest = { supplierToDeleteId = null },
                title = { Text("删除供应商？") },
                text = { Text("“${supplier.name}”及其模型、余额配置将被删除。此操作无法撤销。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            supplierToDeleteId = null
                            viewModel.deleteCurrentSupplier()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { supplierToDeleteId = null },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("取消") }
                },
            )
        }
    }
}

@Composable
private fun TesterContent(
    state: TesterUiState,
    visibleResults: List<ModelTestResult>,
    onSettingChange: (SettingField, String) -> Unit,
    onToggleQuickFilter: (String) -> Unit,
    onAddQuickFilters: (String) -> Unit,
    onRemoveQuickFilter: (String) -> Unit,
    onSelectSupplier: (String) -> Unit,
    onRefreshSupplierModels: (String) -> Unit,
    onEditSupplier: (String) -> Unit,
    onAddSupplier: () -> Unit,
    onDeleteSupplier: () -> Unit,
    onFetchModels: () -> Unit,
    onStartTest: () -> Unit,
    onToggleModel: (String) -> Unit,
    onSelectAll: (Collection<String>?) -> Unit,
    onClearAll: (Collection<String>?) -> Unit,
    onCancelRun: () -> Unit,
    onRetryFailed: () -> Unit,
    onRetestAll: () -> Unit,
    onFilterChange: (ResultFilter) -> Unit,
    onSortChange: (ResultSort) -> Unit,
    onQueryChange: (String) -> Unit,
    onCopy: (String, List<String>) -> Unit,
    onExport: () -> Unit,
    onOpenModelCatalog: () -> Unit,
    showDeferredContent: Boolean,
    contentPadding: PaddingValues,
) {
    val draft = requireNotNull(state.draft)
    val operationDisabled = state.isRunning || state.isUnifiedTesting || state.isSecretsHydrating
    val disabled = operationDisabled || state.isFetchingModels
    val modelFilterTerms = remember(state.modelFilterKeyword, state.selectedQuickFilterTerms) {
        mergeModelFilterTerms(state.modelFilterKeyword, state.selectedQuickFilterTerms)
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
                fetchingSupplierIds = state.fetchingSupplierIds,
                enabled = !operationDisabled,
                onSelect = onSelectSupplier,
                onRefreshModels = onRefreshSupplierModels,
                onEditSupplier = onEditSupplier,
                onAdd = onAddSupplier,
                onDelete = onDeleteSupplier,
            )
        }
        if (showDeferredContent) {
            item(key = "test_settings", contentType = "test_settings") {
                TestSettingsCard(
                    draft = draft,
                    errors = state.errors,
                    enabled = !disabled,
                    onSettingChange = onSettingChange,
                    modelFilterKeyword = state.modelFilterKeyword,
                    quickFilterTerms = state.quickFilterTerms,
                    selectedQuickFilters = state.selectedQuickFilterTerms,
                    onToggleQuickFilter = onToggleQuickFilter,
                    onAddQuickFilters = onAddQuickFilters,
                    onRemoveQuickFilter = onRemoveQuickFilter,
                    onFetchModels = onFetchModels,
                    onStartTest = onStartTest,
                    onOpenModelCatalog = onOpenModelCatalog,
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
                    onToggleModel = onToggleModel,
                    onSelectAll = onSelectAll,
                    onClearAll = onClearAll,
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
                        selectedModels = state.selectedModels,
                        enabled = !disabled,
                        onToggleModel = onToggleModel,
                        onSelectAll = onSelectAll,
                        onClearAll = onClearAll,
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
private const val MAX_MODEL_SOURCES_PER_ENTRY = 32

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
private fun SupplierSelector(
    suppliers: List<SupplierProfile>,
    activeSupplierId: String?,
    fetchingSupplierIds: Set<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onRefreshModels: (String) -> Unit,
    onEditSupplier: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
) {
    val manageEnabled = enabled && fetchingSupplierIds.isEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("供应商", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = onAdd,
                    enabled = manageEnabled,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "添加供应商",
                    )
                }
                IconButton(
                    onClick = onDelete,
                    enabled = manageEnabled,
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
                    val isRefreshing = supplier.id in fetchingSupplierIds
                    val containerColor = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                    val borderColor = if (selected || isRefreshing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                    OutlinedCard(
                        modifier = Modifier
                            .width(cardWidth)
                            .height(58.dp)
                            .combinedClickable(
                                enabled = enabled,
                                role = Role.Tab,
                                onClick = { onSelect(supplier.id) },
                                onDoubleClick = { onRefreshModels(supplier.id) },
                            )
                            .semantics {
                                role = Role.Tab
                                this.selected = selected
                                contentDescription = "供应商 ${supplier.name}；双击重新拉取模型列表"
                            },
                        border = BorderStroke(1.dp, borderColor),
                        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 10.dp)
                                    .padding(end = 34.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
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
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = buildAnnotatedString {
                                        pushStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant))
                                        append(protocolShortLabel(supplier.protocol))
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
                            // Keep a 48dp touch target while centering the compact square
                            // control over the card's top-right rounded corner.
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(48.dp)
                                    .clickable(
                                        enabled = enabled,
                                        role = Role.Button,
                                        onClick = { onEditSupplier(supplier.id) },
                                    )
                                    .semantics {
                                        contentDescription = "编辑 ${supplier.name} 站点配置"
                                    },
                                contentAlignment = Alignment.TopEnd,
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .padding(top = 2.dp, end = 2.dp)
                                        .size(28.dp),
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = if (selected || isRefreshing) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    contentColor = if (selected || isRefreshing) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Outlined.EditNote,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 8.dp, bottom = 6.dp)
                                        .size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            "点击切换站点，双击重新拉取模型；右上角图标编辑站点配置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SupplierConfigurationDialog(
    draft: SupplierDraft,
    errors: FormErrors,
    enabled: Boolean,
    isSecretsHydrating: Boolean,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onProtocolChange: (RelayProtocol) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("站点配置")
                Text(
                    draft.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SupplierConfigurationForm(
                    draft = draft,
                    errors = errors,
                    enabled = enabled,
                    isSecretsHydrating = isSecretsHydrating,
                    onNameChange = onNameChange,
                    onBaseUrlChange = onBaseUrlChange,
                    onProtocolChange = onProtocolChange,
                    onApiKeyChange = onApiKeyChange,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("保存站点") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("关闭") }
        },
    )
}

@Composable
private fun SupplierConfigurationForm(
    draft: SupplierDraft,
    errors: FormErrors,
    enabled: Boolean,
    isSecretsHydrating: Boolean,
    onNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onProtocolChange: (RelayProtocol) -> Unit,
    onApiKeyChange: (String) -> Unit,
) {
    var revealApiKey by rememberSaveable(draft.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "模型测试直接从本机请求目标站点。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestSettingsCard(
    draft: SupplierDraft,
    errors: FormErrors,
    enabled: Boolean,
    onSettingChange: (SettingField, String) -> Unit,
    modelFilterKeyword: String,
    quickFilterTerms: List<String>,
    selectedQuickFilters: Set<String>,
    onToggleQuickFilter: (String) -> Unit,
    onAddQuickFilters: (String) -> Unit,
    onRemoveQuickFilter: (String) -> Unit,
    onFetchModels: () -> Unit,
    onStartTest: () -> Unit,
    onOpenModelCatalog: () -> Unit,
    onCancel: () -> Unit,
    isFetchingModels: Boolean,
    isRunning: Boolean,
) {
    var showAdvanced by rememberSaveable(draft.id) { mutableStateOf(false) }
    var showQuickFilterDialog by rememberSaveable(draft.id) { mutableStateOf(false) }
    var quickFilterInput by rememberSaveable(draft.id) { mutableStateOf("") }
    var quickFilterToDelete by remember { mutableStateOf<String?>(null) }
    ElevatedCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionTitle("测试参数", "并发/限速/重试")
                }
                OutlinedButton(
                    onClick = onOpenModelCatalog,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier
                        .heightIn(min = 40.dp, max = 44.dp)
                        .widthIn(min = 96.dp, max = 112.dp),
                ) {
                    Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("高级测试", maxLines = 1, softWrap = false)
                }
            }
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
                value = modelFilterKeyword,
                onValueChange = { onSettingChange(SettingField.KEYWORD, it) },
                label = "模型名过滤（逗号分隔，OR）",
                placeholder = "例如 gpt,claude",
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
                    "多个词用英文半角逗号 , 表示 OR",
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
                if (quickFilterTerms.isEmpty()) {
                    Text(
                        "添加常用模型词后，可一键组合筛选。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val filterWidth = (maxWidth - 8.dp) / 2
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            maxItemsInEachRow = 2,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            quickFilterTerms.forEach { term ->
                                val selected = term in selectedQuickFilters
                                Surface(
                                    modifier = Modifier
                                        .width(filterWidth)
                                        .height(48.dp)
                                        .clickable(
                                            enabled = enabled,
                                            role = Role.Checkbox,
                                            onClick = { onToggleQuickFilter(term) },
                                        )
                                        .semantics {
                                            contentDescription = "模型快捷筛选词：$term"
                                            this.selected = selected
                                        },
                                    shape = MaterialTheme.shapes.small,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                ) {
                                    Box(Modifier.fillMaxSize()) {
                                        Text(
                                            term,
                                            modifier = Modifier
                                                .align(Alignment.CenterStart)
                                                .padding(start = 12.dp, end = 32.dp),
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                        IconButton(
                                            onClick = { quickFilterToDelete = term },
                                            enabled = enabled,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(36.dp)
                                                .semantics {
                                                    contentDescription = "删除快捷筛选词 $term"
                                                },
                                        ) {
                                            Icon(
                                                Icons.Outlined.Close,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
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
                    placeholder = "例如 gpt,claude",
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
    quickFilterToDelete?.let { term ->
        AlertDialog(
            onDismissRequest = { quickFilterToDelete = null },
            title = { Text("删除快捷筛选？") },
            text = { Text("将移除快捷筛选词“$term”。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        quickFilterToDelete = null
                        onRemoveQuickFilter(term)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { quickFilterToDelete = null },
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
    selectedModels: Set<String>,
    enabled: Boolean,
    onToggleModel: (String) -> Unit,
    onSelectAll: (Collection<String>?) -> Unit,
    onClearAll: (Collection<String>?) -> Unit,
) {
    item(key = "fetched_models_header", contentType = "fetched_models_header") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionTitle(
                        "已获取模型",
                        if (models.size == totalModels) {
                            "已选 ${selectedModels.intersect(models.toSet()).size} / $totalModels 个"
                        } else {
                            "匹配 ${models.size} / $totalModels · 已选 ${selectedModels.intersect(models.toSet()).size} 个"
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "勾选后仅测试选中的模型",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { onSelectAll(models) },
                    enabled = enabled && models.any { it !in selectedModels },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("全选") }
                TextButton(
                    onClick = { onClearAll(models) },
                    enabled = enabled && models.any { it in selectedModels },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("取消全选") }
            }
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
                selectable = true,
                selected = model in selectedModels,
                enabled = enabled,
                onToggle = { onToggleModel(model) },
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
    onToggleModel: (String) -> Unit,
    onSelectAll: (Collection<String>?) -> Unit,
    onClearAll: (Collection<String>?) -> Unit,
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
    item(key = "result_selection", contentType = "result_selection") {
        val visibleModels = visibleResults.map { it.model }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "已选 ${state.selectedModels.intersect(visibleModels.toSet()).size} / ${visibleModels.size} 个",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { onSelectAll(visibleModels) },
                enabled = !state.isRunning && visibleModels.any { it !in state.selectedModels },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("全选") }
            TextButton(
                onClick = { onClearAll(visibleModels) },
                enabled = !state.isRunning && visibleModels.any { it in state.selectedModels },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("取消全选") }
        }
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
            ResultItem(
                result = result,
                selectable = true,
                selected = result.model in state.selectedModels,
                enabled = !state.isRunning,
                onToggle = { onToggleModel(result.model) },
            )
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
            label = "搜索测试结果（逗号分隔，OR）",
            placeholder = "例如 gpt,claude",
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
    selectable: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    onToggle: () -> Unit = {},
) {
    var expanded by rememberSaveable(result.model, result.status) { mutableStateOf(false) }
    val statusColor = when (result.status) {
        TestStatus.SUCCESS -> Color(0xFF16A34A)
        TestStatus.FAILED -> MaterialTheme.colorScheme.error
        TestStatus.PENDING -> MaterialTheme.colorScheme.tertiary
    }
    val failureDetail = result.error?.message
        ?.takeIf { message ->
            message.isNotBlank() &&
                message != result.error?.kind?.label &&
                !failureSummary(result).contains(message)
        }
    val canExpandFailure = !isFetchedOnly && result.status == TestStatus.FAILED && failureDetail != null
    OutlinedCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (selectable) {
                Box(
                    modifier = Modifier.width(40.dp).height(56.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggle() },
                        enabled = enabled,
                        modifier = Modifier
                            .size(46.dp)
                            .graphicsLayer(scaleX = 0.7f, scaleY = 0.7f),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(statusColor),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp),
                contentAlignment = if (expanded) Alignment.TopStart else Alignment.CenterStart,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (expanded) 7.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        result.model,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    TestStatus.FAILED -> {
                        Text(
                            listOfNotNull(failureSummary(result), failureDetail).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = if (expanded) Int.MAX_VALUE else 1,
                            softWrap = expanded,
                            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                        )
                    }

                    TestStatus.PENDING -> {
                        Text(
                            if (isFetchedOnly) "已获取，尚未开始测试" else "等待测试…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            }
            if (isFetchedOnly) {
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(56.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(
                        onClick = {},
                        enabled = false,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.width(56.dp).height(40.dp),
                    ) {
                        Text(
                            "待测",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .width(60.dp)
                        .height(56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .height(28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        StatusLabel(result.status)
                    }
                    if (canExpandFailure) {
                        TextButton(
                            onClick = { expanded = !expanded },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.width(56.dp).height(28.dp),
                        ) {
                            Text(
                                if (expanded) "收起" else "详情",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    } else {
                        Spacer(Modifier.height(28.dp))
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
    val selectedFailed = failed.filter { it in state.selectedModels }
    val selectedResults = state.results.count { it.model in state.selectedModels }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onRetestAll,
                enabled = !state.isRunning && selectedResults > 0,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("重测已选")
            }
            OutlinedButton(
                onClick = onRetryFailed,
                enabled = !state.isRunning && selectedFailed.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text("重测已选失败")
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
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
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

private fun failureSummary(result: ModelTestResult): String {
    val kind = result.error?.kind?.label ?: "请求失败"
    return result.httpStatus?.let { "HTTP $it · $kind" } ?: kind
}

private fun protocolDescription(protocol: RelayProtocol): String = when (protocol) {
    RelayProtocol.CHAT_COMPLETIONS -> "POST /chat/completions · Authorization: Bearer"
    RelayProtocol.RESPONSES -> "POST /responses · Authorization: Bearer"
    RelayProtocol.ANTHROPIC -> "POST /messages · x-api-key + anthropic-version"
}

private fun protocolShortLabel(protocol: RelayProtocol): String = when (protocol) {
    RelayProtocol.CHAT_COMPLETIONS -> "C"
    RelayProtocol.RESPONSES -> "R"
    RelayProtocol.ANTHROPIC -> "A"
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

/**
 * Cross-supplier model directory. It is intentionally a dialog so the existing
 * supplier/test form remains untouched and users can configure sources without
 * losing their current scroll position or test selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelCatalogDialog(
    state: TesterUiState,
    onDismiss: () -> Unit,
    onFetchModels: (String) -> Unit,
    onClearPicker: () -> Unit,
    onSaveEntry: (String?, String, List<ModelSource>) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onRunEntry: (String) -> Unit,
    onCancelRun: () -> Unit,
) {
    var editingEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var entryName by rememberSaveable { mutableStateOf("") }
    var selectedSupplierId by rememberSaveable { mutableStateOf(state.suppliers.firstOrNull()?.id) }
    var selectedModel by rememberSaveable { mutableStateOf("") }
    var pendingSources by remember { mutableStateOf<List<ModelSource>>(emptyList()) }
    var sourceToDelete by remember { mutableStateOf<ModelSource?>(null) }
    var entryToDeleteId by remember { mutableStateOf<String?>(null) }
    var supplierMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var modelMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val selectedSupplier = state.suppliers.firstOrNull { it.id == selectedSupplierId }
    val pickerModels = state.catalogPickerModels
    val unifiedByEntry = state.unifiedResults.groupBy { it.entryId }
    val editingEnabled = !state.isUnifiedTesting
    val entryNameError = if (entryName.length > 256) "模型名称不能超过 256 个字符" else null
    val sourceLimitReached = pendingSources.size >= MAX_MODEL_SOURCES_PER_ENTRY
    val canAddSource = editingEnabled && !sourceLimitReached &&
        selectedSupplierId != null && selectedModel.isNotBlank()
    val canSave = editingEnabled &&
        entryName.trim().isNotEmpty() &&
        entryNameError == null &&
        pendingSources.isNotEmpty()

    fun resetDraft() {
        editingEntryId = null
        entryName = ""
        selectedModel = ""
        pendingSources = emptyList()
        onClearPicker()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (editingEntryId == null) "高级测试" else "编辑模型来源",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "为同一模型配置多个供应商来源，并单独检查连接状态、延迟和用量。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppTextField(
                        value = entryName,
                        onValueChange = { entryName = it },
                        label = "模型名称",
                        placeholder = "例如 GPT-4o（主用）",
                        enabled = editingEnabled,
                        errorMessage = entryNameError,
                    )
                    Text("添加供应商来源", style = MaterialTheme.typography.titleSmall)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { supplierMenuExpanded = true },
                            enabled = editingEnabled && !state.isCatalogFetching && state.suppliers.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(
                                selectedSupplier?.name ?: "选择供应商",
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("选择")
                        }
                        DropdownMenu(
                            expanded = supplierMenuExpanded,
                            onDismissRequest = { supplierMenuExpanded = false },
                            modifier = Modifier.heightIn(max = 280.dp),
                        ) {
                            state.suppliers.forEach { supplier ->
                                DropdownMenuItem(
                                    text = { Text(supplier.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = {
                                        supplierMenuExpanded = false
                                        selectedSupplierId = supplier.id
                                        selectedModel = ""
                                        onClearPicker()
                                    },
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { modelMenuExpanded = true },
                                enabled = editingEnabled && !state.isCatalogFetching && pickerModels.isNotEmpty() &&
                                    state.catalogPickerSupplierId == selectedSupplierId,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text(
                                    selectedModel.ifBlank { "先拉取模型列表" },
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text("选择")
                            }
                            DropdownMenu(
                                expanded = modelMenuExpanded,
                                onDismissRequest = { modelMenuExpanded = false },
                                modifier = Modifier.heightIn(max = 280.dp),
                            ) {
                                pickerModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        onClick = {
                                            selectedModel = model
                                            modelMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { selectedSupplierId?.let(onFetchModels) },
                            enabled = editingEnabled && selectedSupplierId != null && !state.isCatalogFetching,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.widthIn(min = 96.dp, max = 100.dp).heightIn(min = 48.dp),
                        ) {
                            if (state.isCatalogFetching) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (state.isCatalogFetching) "拉取中" else "拉取",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip,
                            )
                        }
                    }
                    if (pendingSources.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            Text(
                                "已添加 ${pendingSources.size} / $MAX_MODEL_SOURCES_PER_ENTRY 个来源",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (sourceLimitReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            pendingSources.forEachIndexed { index, source ->
                                if (index > 0) HorizontalDivider()
                                val supplierName = state.suppliers.firstOrNull { it.id == source.supplierId }?.name ?: "已删除供应商"
                                Row(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "$supplierName · ${source.modelId}",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(
                                        onClick = { sourceToDelete = source },
                                        enabled = editingEnabled,
                                        modifier = Modifier.size(44.dp),
                                    ) {
                                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "移除来源", modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("已配置模型", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${state.modelCatalog.size} 个模型 · ${state.modelCatalog.sumOf { it.sources.size }} 个供应商来源",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.isUnifiedTesting && state.unifiedProgressTotal > 0) {
                        LinearProgressIndicator(
                            progress = { state.unifiedProgressDone.toFloat() / state.unifiedProgressTotal },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "正在测试 ${state.unifiedProgressDone} / ${state.unifiedProgressTotal} 个来源",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.modelCatalog.isEmpty()) {
                        Text(
                            "尚未配置跨供应商模型。先选择供应商并拉取模型列表，再添加来源。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.modelCatalog.forEach { entry ->
                                val isTesting = state.isUnifiedTesting && state.unifiedEntryId == entry.id
                                ModelCatalogEntryCard(
                                    entry = entry,
                                    suppliers = state.suppliers,
                                    results = unifiedByEntry[entry.id].orEmpty(),
                                    enabled = !state.isUnifiedTesting,
                                    isTesting = isTesting,
                                    onEdit = {
                                        editingEntryId = entry.id
                                        entryName = entry.name
                                        pendingSources = entry.sources
                                        selectedSupplierId = entry.sources.firstOrNull()?.supplierId ?: state.suppliers.firstOrNull()?.id
                                        selectedModel = ""
                                        onClearPicker()
                                    },
                                    onRun = { onRunEntry(entry.id) },
                                    onCancel = onCancelRun,
                                    onDelete = { entryToDeleteId = entry.id },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = {
                            val supplierId = selectedSupplierId
                            if (supplierId != null && selectedModel.isNotBlank()) {
                                pendingSources = (pendingSources + ModelSource(supplierId, selectedModel))
                                    .distinctBy { it.supplierId + "\u0000" + it.modelId }
                                selectedModel = ""
                            }
                        },
                        enabled = canAddSource,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("添加来源", maxLines = 1)
                    }
                    Button(
                        onClick = {
                            onSaveEntry(editingEntryId, entryName, pendingSources)
                            resetDraft()
                        },
                        enabled = canSave,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (editingEntryId == null) "保存模型来源" else "保存修改") }
                }
            }
        }
    }
    sourceToDelete?.let { source ->
        val supplierName = state.suppliers.firstOrNull { it.id == source.supplierId }?.name ?: "未知供应商"
        AlertDialog(
            onDismissRequest = { sourceToDelete = null },
            title = { Text("移除供应商来源？") },
            text = { Text("将移除“$supplierName · ${source.modelId}”，但不会影响已保存配置。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        sourceToDelete = null
                        pendingSources = pendingSources - source
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { sourceToDelete = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("取消") }
            },
        )
    }
    entryToDeleteId?.let { entryId ->
        val entry = state.modelCatalog.firstOrNull { it.id == entryId }
        if (entry != null) {
            AlertDialog(
                onDismissRequest = { entryToDeleteId = null },
                title = { Text("删除已配置模型？") },
                text = { Text("“${entry.name}”及其供应商来源将被删除。此操作无法撤销。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            entryToDeleteId = null
                            onDeleteEntry(entry.id)
                            if (editingEntryId == entry.id) {
                                resetDraft()
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { entryToDeleteId = null },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("取消") }
                },
            )
        }
    }
}

@Composable
private fun ModelCatalogEntryCard(
    entry: ModelCatalogEntry,
    suppliers: List<SupplierProfile>,
    results: List<UnifiedModelTestResult>,
    enabled: Boolean,
    isTesting: Boolean,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onEdit, enabled = enabled, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑模型来源", modifier = Modifier.size(20.dp))
                }
                IconButton(
                    onClick = if (isTesting) onCancel else onRun,
                    enabled = enabled || isTesting,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        if (isTesting) Icons.Outlined.StopCircle else Icons.Outlined.PlayArrow,
                        contentDescription = if (isTesting) "取消测试 ${entry.name}" else "测试 ${entry.name}",
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除模型来源配置", modifier = Modifier.size(20.dp))
                }
            }
            Text(
                "${entry.sources.size} 个供应商来源",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            entry.sources.forEachIndexed { index, source ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                val supplierName = suppliers.firstOrNull { it.id == source.supplierId }
                    ?.name
                    ?: "已删除供应商"
                val result = results.firstOrNull {
                    it.supplierId == source.supplierId && it.sourceModel == source.modelId
                }?.result
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "$supplierName · ${source.modelId}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (result != null) {
                        StatusLabel(result.status)
                    } else {
                        Text(
                            "未测试",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (result != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        result.latencyMs?.let {
                            Text(
                                "${it}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                            )
                        }
                        result.httpStatus?.let {
                            Text(
                                "HTTP $it",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (result.status == TestStatus.SUCCESS) {
                                    Color(0xFF15803D)
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                maxLines = 1,
                            )
                        }
                        result.usage?.resolvedTotal?.takeIf { it > 0 }?.let {
                            Text(
                                "$it tok",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
                result?.error?.message
                    ?.takeIf { message -> message.isNotBlank() && message != result.error?.kind?.label }
                    ?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
