package com.relaytester.app.feature.tester

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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Sync
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import kotlin.math.floor
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relaytester.app.core.model.ModelTestResult
import com.relaytester.app.core.model.ModelCatalogEntry
import com.relaytester.app.core.model.ModelSource
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestStatus
import com.relaytester.app.feature.tester.UnifiedModelTestResult
import com.relaytester.app.feature.tester.CatalogSearchResult
import com.relaytester.app.core.model.matchesAnyModelFilterTerm
import com.relaytester.app.core.model.mergeModelFilterTerms
import com.relaytester.app.ui.components.PullOnlyMarker
import com.relaytester.app.core.network.RelayBaseUrl
import com.relaytester.app.feature.fingerprint.AnimationPhase
import com.relaytester.app.core.update.PreviewOpener
import com.relaytester.app.feature.fingerprint.AnimationTestPanel
import com.relaytester.app.feature.fingerprint.AnimationUiState
import com.relaytester.app.feature.fingerprint.AnimationView
import com.relaytester.app.feature.fingerprint.SupplierOption
import com.relaytester.app.ui.navigation.AppDestination
import com.relaytester.app.ui.components.DialogDismissScrim
import com.relaytester.app.ui.components.QuietIconButton
import com.relaytester.app.ui.components.RelayAppHeader
import com.relaytester.app.ui.components.StatusToast
import com.relaytester.app.ui.components.copyToClipboard
import com.relaytester.app.ui.components.rememberDialogWindowBox
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Mirrors the quick prompts provided by the initial relay-tester web source. */
private val TEST_PROMPT_PRESETS = listOf("ping", "Hi", "Say OK", "1+1=?", "回复ok")

/**
 * Keeps the model editor's in-progress source list across a configuration change.
 *
 * The editor's other fields are `rememberSaveable`; this one was plain `remember`, so a
 * rotation reopened the editor with its title and name restored and its sources empty —
 * and saving then replaced the entry's stored sources with the few that had been re-added.
 * A source is two strings, so it is flattened rather than made `Parcelable`.
 */
private val modelSourcesSaver = listSaver<List<ModelSource>, String>(
    save = { sources -> sources.map { "${it.supplierId}\u0000${it.modelId}" } },
    restore = { flat ->
        flat.mapNotNull { entry ->
            val split = entry.indexOf('\u0000')
            if (split <= 0) {
                null
            } else {
                ModelSource(
                    supplierId = entry.substring(0, split),
                    modelId = entry.substring(split + 1),
                )
            }
        }
    },
)

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
    onFingerprintModel: (String) -> Unit = {},
    onOpenUpdates: () -> Unit = {},
    hasAppUpdate: Boolean = false,
    /**
     * The animation test card's state and actions.
     *
     * Owned by the fingerprint view model — that is where the sandbox, the request path and
     * the preview already live — and lent to this screen because the entry point sits here,
     * next to 高级测试. Passing the state in rather than duplicating the machine keeps one
     * implementation of "what is generating" for both the card and its button badge.
     */
    animationState: AnimationUiState = AnimationUiState(),
    animationSuppliers: List<SupplierOption> = emptyList(),
    onAnimationSupplierChange: (String) -> Unit = {},
    onAnimationModelToggle: (String) -> Unit = {},
    onStartAnimationSelected: (String) -> Unit = {},
    onOpenAnimationSession: (Long?) -> Unit = {},
    onRemoveAnimationSession: (Long) -> Unit = {},
    onAnimationPromptChange: (String) -> Unit = {},
    onRestoreAnimationPrompt: () -> Unit = {},
    onRegenerateAnimation: () -> Unit = {},
    onCancelAnimation: () -> Unit = {},
    onReplayAnimation: () -> Unit = {},
    onAnimationViewChange: (AnimationView) -> Unit = {},
    onAnimationRenderFailure: (String) -> Unit = {},
    onAnimationSnapshot: (Long, ByteArray) -> Unit = { _, _ -> },
    onAnimationReasoningHold: (Boolean) -> Unit = {},
    onOpenAnimation: () -> Unit = {},
    onCloseAnimation: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    // Deliberately `remember`, unlike the flags below: this payload holds the credentials
    // the user just typed, and `rememberSaveable` writes to the platform's saved instance
    // state (a file the system keeps for restore). Losing the confirmation on a rotation
    // is the cheaper mistake — the export can be asked for again; a leaked secret cannot
    // be un-leaked.
    var pendingExport by remember { mutableStateOf<ExportPayload?>(null) }
    var showModelCatalog by rememberSaveable { mutableStateOf(false) }
    // 动画测试卡片。它是页面这一层的一层覆盖：关掉以后滚动位置与下面的状态原样保留，
    // 与目录弹窗同一条做法。
    var showAnimationSheet by rememberSaveable { mutableStateOf(false) }
    var configurationSupplierId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSupplierOrder by rememberSaveable { mutableStateOf(false) }
    var saveCompletedAt by rememberSaveable { mutableLongStateOf(0L) }
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
                onOpenUpdates = onOpenUpdates,
                hasUpdate = hasAppUpdate,
            )
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier,
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
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
                    onSelectSupplier = { supplierId -> viewModel.selectSupplier(supplierId) },
                    onRefreshSupplierModels = viewModel::refreshSupplierModels,
                    onFetchAllSupplierModels = viewModel::fetchAllSupplierModels,
                    onEditSupplier = { supplierId ->
                        // 先切站点、成功才记 id：切不过去（测试占着 / 写盘失败）时不能把
                        // id 留成悬空状态——那样等到别的操作把这个站点变成活动站点，配置
                        // 弹窗会自己蹦出来。
                        viewModel.selectSupplier(supplierId) { activated ->
                            if (activated) configurationSupplierId = supplierId
                        }
                    },
                    onAddSupplier = viewModel::addSupplier,
                    onReorderSuppliers = { showSupplierOrder = true },
                    onFetchModels = viewModel::fetchModels,
                    onStartTest = viewModel::startTest,
                    onToggleModel = viewModel::toggleModelSelection,
                    onSelectAll = viewModel::selectAllModels,
                    onClearAll = viewModel::clearAllModels,
                    onCancelRun = viewModel::cancelRun,
                    onCancelUnifiedRun = viewModel::cancelUnifiedCatalogTest,
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
                    // 入口永远落在「选模型 + 测试记录」那一层：onOpenAnimation 会把上一次
                    // 看着的那条记录取消选中，否则卡片会直接弹进那条的详情，而用户想点的是
                    // 列表——尤其是他刚生成完、正想再开一条的时候。
                    onOpenAnimationSheet = {
                        showAnimationSheet = true
                        onOpenAnimation()
                    },
                    animationGenerating = animationState.generating != null,
                    onFingerprintModel = onFingerprintModel,
                    showDeferredContent = showDeferredContent,
                    contentPadding = innerPadding,
                )
            }
            // 保存成功的提示条画在页面这一层，不住在弹窗里：保存成功弹窗就关，
            // 跟着弹窗走的提示条会一起消失。余额页的保存提示用同一个组件、同一个位置。
            //
            // 纵坐标要叠上 innerPadding：这一层 Box 从窗口顶算起（Scaffold 把不透明的
            // 顶栏画在 content 之上），只按窗口顶 +4dp 定位，整条提示会藏进顶栏背后——
            // 保存成功的那一刻，屏幕上什么也没发生。
            if (saveCompletedAt > 0L) {
                StatusToast(
                    message = "供应商配置已保存",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = innerPadding.calculateTopPadding() + 4.dp),
                    onToastShown = { saveCompletedAt = 0L },
                )
            }
        }
    }
    if (showAnimationSheet) {
        AnimationTestPanel(
            state = animationState,
            suppliers = animationSuppliers,
            onSupplierChange = onAnimationSupplierChange,
            onModelToggle = onAnimationModelToggle,
            onStartSelected = onStartAnimationSelected,
            onOpenSession = onOpenAnimationSession,
            onRemoveSession = onRemoveAnimationSession,
            onPromptChange = onAnimationPromptChange,
            onRestoreDefault = onRestoreAnimationPrompt,
            onRegenerate = onRegenerateAnimation,
            onCancel = onCancelAnimation,
            onReplay = onReplayAnimation,
            onOpenInBrowser = {
                // Hands the sandboxed document to a real browser. Deliberately the one path
                // that leaves the restrictions in `AnimationPreview` — the user asked for it,
                // presses a button for it, and the preview stays sandboxed either way.
                animationState.selected?.html?.let { html ->
                    PreviewOpener.open(context, html)
                } ?: false
            },
            onViewChange = onAnimationViewChange,
            onRenderFailure = onAnimationRenderFailure,
            onSnapshot = onAnimationSnapshot,
            onReasoningHold = onAnimationReasoningHold,
            // 关闭只是收起这一层：生成中的测试继续跑，入口按钮上的角标继续显示进度。
            onDismiss = {
                showAnimationSheet = false
                onCloseAnimation()
            },
        )
    }
    if (showModelCatalog && state.draft != null) {
        ModelCatalogDialog(
            state = state,
            onDismiss = {
                viewModel.clearCatalogPickerModels()
                showModelCatalog = false
            },
            onFetchModels = viewModel::fetchCatalogModels,
            onSearchAllModels = viewModel::searchAllCatalogModels,
            onClearPicker = viewModel::clearCatalogPickerModels,
            onClearPickerPreservingSearch = viewModel::clearCatalogPickerModelsPreservingSearch,
            onSaveEntry = { entryId, name, sources ->
                if (entryId == null) {
                    viewModel.saveModelCatalogEntry(name, sources)
                } else {
                    viewModel.updateModelCatalogEntry(entryId, name, sources)
                }
            },
            onDeleteEntry = viewModel::deleteModelCatalogEntry,
            onRunEntry = viewModel::startUnifiedCatalogTest,
            onRunSource = viewModel::startUnifiedCatalogSourceTest,
            onRefreshEntry = viewModel::refreshModelCatalogEntry,
            onCancelRun = viewModel::cancelUnifiedCatalogTest,
            onRemoveSource = viewModel::removeModelSourceFromCatalog,
        )
    }
    configurationSupplierId?.let { supplierId ->
        val draft = state.draft
        if (state.activeSupplierId == supplierId && draft?.id == supplierId) {
            SupplierConfigurationDialog(
                draft = draft,
                errors = state.errors,
                enabled = !state.isRunning && !state.isFetchingModels && !state.isSecretsHydrating,
                isSecretsHydrating = state.isSecretsHydrating,
                onDismiss = {
                    saveCompletedAt = 0L
                    viewModel.discardCurrentSupplierChanges()
                    configurationSupplierId = null
                },
                onDelete = { configurationSupplierId?.let { supplierToDeleteId = it } },
                deleteEnabled = state.suppliers.size > 1,
                onNameChange = viewModel::updateName,
                onBaseUrlChange = viewModel::updateBaseUrl,
                onProtocolChange = viewModel::updateProtocol,
                onApiKeyChange = viewModel::updateApiKey,
                onTestingDisabledChange = viewModel::updateTestingDisabled,
                onSave = {
                    viewModel.saveCurrentSupplier {
                        // 保存成功＝收工：弹窗自己关，提示条交给页面这一层。
                        saveCompletedAt = System.currentTimeMillis()
                        configurationSupplierId = null
                    }
                },
            )
        }
    }
    if (showSupplierOrder) {
        SupplierOrderDialog(
            suppliers = state.suppliers,
            onMove = viewModel::moveSupplierTo,
            onDismiss = { showSupplierOrder = false },
        )
    }
    supplierToDeleteId?.let { supplierId ->
        val supplier = state.suppliers.firstOrNull { it.id == supplierId }
        if (supplier != null) {
            AlertDialog(
                onDismissRequest = { supplierToDeleteId = null },
                title = { Text("删除供应商？") },
                text = { Text("删除「${supplier.name}」及其模型、余额配置，无法撤销。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = supplier.id
                            supplierToDeleteId = null
                            // Deleting the supplier that is open in the configuration
                            // sheet must also close that sheet; otherwise the saved id
                            // outlives the record it points at.
                            if (configurationSupplierId == target) {
                                saveCompletedAt = 0L
                                configurationSupplierId = null
                            }
                            viewModel.deleteSupplier(target)
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
    onFetchAllSupplierModels: () -> Unit,
    onEditSupplier: (String) -> Unit,
    onAddSupplier: () -> Unit,
    onReorderSuppliers: () -> Unit,
    onFetchModels: () -> Unit,
    onStartTest: () -> Unit,
    onToggleModel: (String) -> Unit,
    onSelectAll: (Collection<String>?) -> Unit,
    onClearAll: (Collection<String>?) -> Unit,
    onCancelRun: () -> Unit,
    onCancelUnifiedRun: () -> Unit,
    onRetryFailed: () -> Unit,
    onRetestAll: () -> Unit,
    onFilterChange: (ResultFilter) -> Unit,
    onSortChange: (ResultSort) -> Unit,
    onQueryChange: (String) -> Unit,
    onCopy: (String, List<String>) -> Unit,
    onExport: () -> Unit,
    onOpenModelCatalog: () -> Unit,
    onOpenAnimationSheet: () -> Unit,
    animationGenerating: Boolean,
    onFingerprintModel: (String) -> Unit,
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
                modelFetchProgressDone = state.modelFetchProgressDone,
                modelFetchProgressTotal = state.modelFetchProgressTotal,
                enabled = !operationDisabled,
                onSelect = onSelectSupplier,
                onRefreshModels = onRefreshSupplierModels,
                onFetchAll = onFetchAllSupplierModels,
                onEditSupplier = onEditSupplier,
                onAdd = onAddSupplier,
                onReorder = onReorderSuppliers,
            )
        }
        if (state.modelFetchFailures.isNotEmpty()) {
            item(key = "model_fetch_failures", contentType = "model_fetch_failures") {
                ModelFetchFailureCard(failures = state.modelFetchFailures)
            }
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
                    onOpenAnimationSheet = onOpenAnimationSheet,
                    animationGenerating = animationGenerating,
                    onCancel = onCancelRun,
                    onCancelUnifiedTest = onCancelUnifiedRun,
                    isFetchingModels = state.isFetchingModels,
                    isRunning = state.isRunning,
                    isUnifiedTesting = state.isUnifiedTesting,
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
                    onFingerprintModel = onFingerprintModel,
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
                        onCopyName = { model -> onCopy("模型名", listOf(model)) },
                    )
                }
            }
            item(key = "security_note", contentType = "security_note") {
                SecurityNote()
            }
        }
    }
}

// 排序面板的行高：固定值让每格的高度一致，指尖落在哪一格就移到哪一格时几何才算得准，
// 也免得名字长短不一时行高参差。48dp 也是触摸目标的下限——行高再矮下去，手指落在行与行
// 之间时会更常压到相邻那格（用户 1.7.17 反馈「供应商之间上下间隔太大」，收的是行高与行距，
// 不是把行压到按不准）。
private val SUPPLIER_ORDER_ROW_HEIGHT = 48.dp

// 两行之间的行距。换位判据要的是「一格」的间距（行高 + 行距），它由列表的布局信息量出来，
// 这个常量只在列表里只剩一行可量时兜底。
private val SUPPLIER_ORDER_ROW_SPACING = 2.dp

/** 指尖离视口上下边缘这么近时，列表开始跟着手指自己滚；也是滚动速度的爬升区间。 */
private val SUPPLIER_ORDER_EDGE_ZONE = 56.dp

/**
 * 自动滚动的速度上限：每帧像素。
 *
 * 60fps 下约 720px/s——比手滑得快一点，又不至于一帧跨过一整格（一格 48dp ≈ 126px），
 * 否则换位会连跳两格。
 */
private const val SUPPLIER_ORDER_AUTO_SCROLL_MAX_PX_PER_FRAME = 12f

/**
 * Which slot the finger at [fingerRootY] is over, as an index into the supplier list.
 *
 * Arithmetic on purpose: this is the one part of the drag that [SupplierOrderTest] can pin
 * with numbers instead of a source-contract regex, and it is exactly where a real defect
 * hid. The list reports item offsets from the **start of its content**, while the finger is
 * captured against the list's own top edge; the gap between the two is the list's leading
 * content padding, which the layout publishes as a negative [viewportStartOffset]. Judging
 * the finger without it puts it one padding lower than it is — on the device that was 8dp
 * (21px), so every slot boundary fired 21px early and a finger resting on a boundary moved
 * the row one slot too far. The measured coordinates that pinned it (density 2.625, the
 * 56dp/6dp rows of that build) are the numbers [SupplierOrderTest] still uses; the function
 * itself only reads the [pitch] it is handed, so they stay valid however the rows are sized.
 *
 * The rest of the mapping is what makes the target a pure function of where the finger is:
 * the anchor's own geometry fixes the frame, and every other visible row sits a whole
 * [pitch] away from it. A finger that has not moved therefore resolves to the same slot
 * however much the rows have shifted underneath, which is what stops the rows bouncing.
 *
 * Two terms were added when the list learned to follow the finger along the edges:
 *  * the finger is first clamped to the viewport **inset by half a row** ([listBottomInRoot] is
 *    the viewport's lower edge, the row height is [anchorSize]), so a finger dragged past an end
 *    names the slot at that edge instead of jumping straight to the end of the list, and the row
 *    it names is always a whole row inside the viewport — the dragged row never hangs half out
 *    of the window. The auto-scroll loop keeps scrolling from there, and each row it brings past
 *    the finger advances the slot by one, which is what "drag it to the very bottom" looks like.
 *    The half-row inset is also exactly what lets the last slot be reached: at the end of the
 *    list the fingertip at an edge lands on the last row's centre.
 *  * [autoScrolledPx] is how far that loop has scrolled the content since the drag began.
 *    Content that slid out from under the finger must advance the slot under it by the same
 *    amount, or the target falls a row further behind with every scrolled row.
 */
internal fun supplierSlotUnderFinger(
    fingerRootY: Float,
    listTopInRoot: Float,
    viewportStartOffset: Int,
    anchorIndex: Int,
    anchorOffset: Int,
    anchorSize: Int,
    pitch: Float,
    lastIndex: Int,
    autoScrolledPx: Float = 0f,
    listBottomInRoot: Float = Float.POSITIVE_INFINITY,
): Int {
    val halfRow = anchorSize / 2f
    val fingerInViewport = fingerRootY.coerceIn(
        minimumValue = listTopInRoot + halfRow,
        maximumValue = maxOf(listTopInRoot + halfRow, listBottomInRoot - halfRow),
    )
    val fingerInContent = fingerInViewport - listTopInRoot + viewportStartOffset + autoScrolledPx
    val raw = anchorIndex + (fingerInContent - (anchorOffset + anchorSize / 2f)) / pitch
    return raw.roundToInt().coerceIn(0, lastIndex)
}

/**
 * How far the list should scroll this frame for a finger at [fingerRootY], in pixels —
 * negative for up, positive for down, 0 when the finger is not near an edge.
 *
 * A pure function because the ramp is the part a device cannot check frame by frame: over
 * [edgeZonePx] the speed rises linearly from 0 at the zone's inner edge to [maxStepPxPerFrame]
 * at the viewport's edge, and a finger held past the edge (dragged off the list or off the
 * screen) keeps the ceiling, so the list scrolls until it runs out of content. That is the
 * "the list doesn't follow my finger all the way to the top/bottom" report: without this the
 * finger simply ran off the visible window and the drag was cancelled there.
 */
internal fun supplierAutoScrollStep(
    fingerRootY: Float,
    viewportTop: Float,
    viewportBottom: Float,
    edgeZonePx: Float,
    maxStepPxPerFrame: Float,
): Float {
    if (edgeZonePx <= 0f) return 0f
    val fromTop = fingerRootY - viewportTop
    if (fromTop < edgeZonePx) {
        return -maxStepPxPerFrame * ((edgeZonePx - fromTop) / edgeZonePx).coerceIn(0f, 1f)
    }
    val fromBottom = viewportBottom - fingerRootY
    if (fromBottom < edgeZonePx) {
        return maxStepPxPerFrame * ((edgeZonePx - fromBottom) / edgeZonePx).coerceIn(0f, 1f)
    }
    return 0f
}

/**
 * The frame one gesture judges the finger in, sampled once when the finger goes down.
 *
 * The list reports item offsets from the **start of its content**, so the finger's place in
 * the list is `fingerRootY - listTopInRoot + viewportStartOffset`, and the slot's centre is
 * the anchor's offset plus [anchorSize] / 2, a whole [pitch] between neighbours. Sampling the
 * frame once and never re-reading it is what makes the mapping depend on the finger's travel
 * alone: reading it per event bills any movement of the rows themselves to the finger, which
 * used to happen every time a reorder made the list scroll by one row (the row then ran away
 * from the hand). Nothing moves the rows during a drag any more — the order is committed on
 * release, see [SupplierOrderDialog] — so the frame is simply valid for the whole gesture;
 * the one thing that does move the content, the edge auto-scroll, travels separately as
 * [supplierSlotUnderFinger]'s `autoScrolledPx`.
 */
private data class SupplierDragFrame(
    val listTopInRoot: Float,
    val viewportStartOffset: Int,
    val anchorIndex: Int,
    val anchorOffset: Int,
    val anchorSize: Int,
    val pitch: Float,
)

/**
 * Samples the frame a drag started in: [supplierSlotUnderFinger]'s inputs as the layout stood
 * when the finger went down. The pitch is measured from the layout (the gap between two
 * neighbouring item offsets) rather than taken from the row height, so a change to the row
 * spacing cannot silently skew the judgement.
 */
private fun currentSupplierDragFrame(
    listState: LazyListState,
    listTopInRoot: Float,
    spacingPx: Float,
): SupplierDragFrame? {
    val info = listState.layoutInfo
    val visible = info.visibleItemsInfo
    val anchor = visible.firstOrNull() ?: return null
    if (listTopInRoot.isNaN()) return null
    val pitch = if (visible.size >= 2) {
        (visible[1].offset - visible[0].offset).toFloat()
    } else {
        anchor.size + spacingPx
    }
    if (pitch <= 0f) return null
    return SupplierDragFrame(
        listTopInRoot = listTopInRoot,
        viewportStartOffset = info.viewportStartOffset,
        anchorIndex = anchor.index,
        anchorOffset = anchor.offset,
        anchorSize = anchor.size,
        pitch = pitch,
    )
}

/** The row a drag starts on: its id, its index, and where its top edge sits in root coordinates. */
private data class SupplierRowHit(val id: String, val index: Int, val topInRoot: Float)

/**
 * Which row the finger at [fingerRootY] is over, or null when it is over none of them.
 *
 * The drag gesture lives on the list box rather than on a row — a row is exactly the node
 * LazyColumn recycles, and a gesture dies with the node it hangs on — so the row to pick up is
 * found by hit-testing the layout instead of by the node the event arrived at. The finger's
 * content coordinate uses the same expression [supplierSlotUnderFinger] uses (`- listTopInRoot
 * + viewportStartOffset`), which is what puts the comparison against the published item offsets
 * on one origin. A press between two rows, on the list's padding or up on the title names no
 * row and starts no drag, so a long press there cannot pick one at random.
 *
 * The returned top edge is what the floating row is aligned to, so the row stays under the
 * fingertip exactly where it was grabbed rather than jumping its centre onto it.
 */
private fun supplierRowUnderFinger(
    listState: LazyListState,
    fingerRootY: Float,
    frame: SupplierDragFrame,
): SupplierRowHit? {
    val fingerInContent = fingerRootY - frame.listTopInRoot + frame.viewportStartOffset
    val item = listState.layoutInfo.visibleItemsInfo.firstOrNull {
        fingerInContent >= it.offset && fingerInContent < it.offset + it.size
    } ?: return null
    val id = item.key as? String ?: return null
    return SupplierRowHit(
        id = id,
        index = item.index,
        topInRoot = frame.listTopInRoot - frame.viewportStartOffset + item.offset,
    )
}

private const val INITIAL_DEFERRED_CONTENT_DELAY_MS = 350L
private const val MAX_MODEL_SOURCES_PER_ENTRY = 32

/**
 * One cell of a result row's action rail. Material 3's smallest documented icon
 * button (the "small" container) rather than the 48dp default: two 48dp cells made
 * the row 48dp tall for 19dp of chips, which was most of the card's height.
 */
private val RESULT_ACTION_SLOT = 40.dp

/**
 * The status pill's own horizontal padding. The label that shares the row with the pill
 * indents by the same amount, so the two texts line up rather than the pill's background.
 */
private val RESULT_PILL_INSET = 8.dp

/**
 * How far the 检索 button is lifted off the text field's bottom edge.
 *
 * An outlined text field's layout box is 8 dp taller than the box it draws: the top 8 dp stay
 * clear of the border for the floating label (device measurement at 420 dpi: layout box 64 dp,
 * drawn border 56 dp, both ending on the same bottom edge). A 48 dp button centred against the
 * whole layout box therefore floats its centre 4 dp above the border's centre — the "button
 * looks a bit high" this fixes; bottom-aligning the two boxes alone would instead put it 4 dp
 * *below* that centre. So: align the bottoms, then lift by half of what the button does not
 * fill, (56 − 48) / 2. Same construction as the fingerprint panel's model-list button.
 */
private val MODEL_SEARCH_BUTTON_INSET = 4.dp

/**
 * How far the copy icon has to move so its centre lands on the pill's **text** centre.
 *
 * The rail's last cell is [slotWidth] wide and sits flush with the content's right edge, so
 * its centre is `slotWidth / 2` from that edge; the pill's text centre is
 * `pillInset + pillLabelWidth / 2` from it. The difference is a function of the rendered
 * label width, not of a font-scale step: at 1.0× the two are 1dp apart and at 2.0× ten,
 * which is why the caller measures the label instead of guessing a tier.
 *
 * A free function so the invariant is unit-tested rather than only measured on a device.
 */
internal fun copyAlignmentOffset(slotWidth: Dp, pillInset: Dp, pillLabelWidth: Dp): Dp =
    slotWidth / 2 - pillInset - pillLabelWidth / 2

/**
 * The pill's own wording, in one place: the row that aligns the copy with the pill measures
 * the same label the pill draws, so the two can never drift apart.
 */
internal fun statusChipLabel(status: TestStatus, isFetchedOnly: Boolean): String = when (status) {
    TestStatus.SUCCESS -> "可用"
    TestStatus.FAILED -> "失败"
    // A pull-only source was never sent anywhere: it reads "仅拉取", not "失败", so the
    // deliberate skip is not counted as a network error anywhere the chip is read.
    TestStatus.SKIPPED -> "仅拉取"
    // PENDING covers both "queued behind other models" and "this row's request is in
    // flight", so "进行中" here contradicted the body text on the same row. The row
    // itself is what says it is running; the badge only says it is not finished yet.
    TestStatus.PENDING -> if (isFetchedOnly) "待测" else "等待中"
}

/**
 * Names every supplier whose model pull failed and why. The snackbar only
 * carries one line, so the per-supplier reasons live here until the next pull.
 */
@Composable
private fun ModelFetchFailureCard(failures: List<ModelFetchFailure>) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "批量拉取失败：${failures.size} 个供应商",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            failures.forEach { failure ->
                Text(
                    "${failure.supplierName}：${failure.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
private fun SupplierSelector(
    suppliers: List<SupplierProfile>,
    activeSupplierId: String?,
    fetchingSupplierIds: Set<String>,
    modelFetchProgressDone: Int,
    modelFetchProgressTotal: Int,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onRefreshModels: (String) -> Unit,
    onFetchAll: () -> Unit,
    onEditSupplier: (String) -> Unit,
    onAdd: () -> Unit,
    onReorder: () -> Unit,
) {
    val manageEnabled = enabled && fetchingSupplierIds.isEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("供应商", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 排序只在有两个以上供应商时才有意义：一个供应商的面板是一个空操作，
                // 摆在那里只会让人点进去发现没得排。
                if (suppliers.size > 1) {
                    IconButton(
                        onClick = onReorder,
                        enabled = manageEnabled,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SwapVert,
                            contentDescription = "排序供应商",
                        )
                    }
                }
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
            }
        }
        Button(
            onClick = onFetchAll,
            enabled = enabled && suppliers.isNotEmpty() && fetchingSupplierIds.isEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        ) {
            Icon(Icons.Outlined.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (fetchingSupplierIds.isNotEmpty() && modelFetchProgressTotal > 1) {
                    "正在拉取 $modelFetchProgressDone / $modelFetchProgressTotal"
                } else if (fetchingSupplierIds.isNotEmpty()) {
                    "正在拉取…"
                } else {
                    "批量拉取模型"
                },
                maxLines = 1,
                softWrap = false,
            )
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
                    val cardShape = MaterialTheme.shapes.medium
                    OutlinedCard(
                        modifier = Modifier
                            .width(cardWidth)
                            // A floor, not a fixed height: the grid already levels both
                            // columns of a row, and at a large font scale the name plus
                            // the protocol line need more than 58dp or they clip.
                            .heightIn(min = 58.dp)
                            .clip(cardShape)
                            .combinedClickable(
                                enabled = enabled,
                                role = Role.Tab,
                                onClick = { onSelect(supplier.id) },
                                onDoubleClick = { onRefreshModels(supplier.id) },
                            )
                            .semantics {
                                role = Role.Tab
                                this.selected = selected
                                contentDescription = if (supplier.isTestingDisabled) {
                                    "供应商 ${supplier.name}，已禁止测试"
                                } else {
                                    "供应商 ${supplier.name}；双击重新拉取模型列表"
                                }
                            },
                        border = BorderStroke(1.dp, borderColor),
                        shape = cardShape,
                        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
                    ) {
                        // Width-only: a full-size box would stretch to whatever height the
                        // card is given and defeat the column's own floor below.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(),
                        ) {
                            if (supplier.isTestingDisabled) {
                                // A pull-only marker that draws over the corner instead of
                                // taking layout space, so every card keeps the same height
                                // and the two columns stay level.
                                PullOnlyMarker(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(start = 5.dp, top = 7.dp),
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // The column carries the same floor as the card, so the
                                    // leftover space is its own and Arrangement.Center
                                    // splits it into equal top and bottom padding. Without
                                    // this floor a wrapping column would sit at the top and
                                    // leave all the slack below.
                                    .heightIn(min = 58.dp)
                                    // Pull-only cards reserve room for the corner marker.
                                    .padding(start = if (supplier.isTestingDisabled) 14.dp else 10.dp)
                                    // Clears the edit control stacked over the corner.
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
                                        contentDescription = "编辑 ${supplier.name} 的配置"
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
                                    Box(
                                        Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
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
            "点按切换供应商，双击重拉模型；右上角图标可编辑。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Reorders the suppliers, and through them every surface that lists them.
 *
 * The list is the profiles list's own order — there is no rank field — so the panel sends
 * [onMove] once, when the finger comes up, and the stored list follows; the test tab's cards,
 * the balance grid and the fingerprint picker (on its next tab entry) all render that one
 * order.
 *
 * 拖动的三条地基，是用户报的两个症状（到边缘闪屏、偶尔断触拖不到最顶/最底）的根治：
 *
 *  * **手势挂在列表盒上，不挂在行上。** Compose 只把事件送给按下那一刻命中的节点，而
 *    LazyColumn 一旦把某行划出视口就把它整个丢出组合：被拖的行被回收，挂在它身上的手势
 *    跟着一起消失——手指还在屏幕上，行却不再跟手，这就是「断触」。列表盒在整个弹窗存活期内
 *    都在组合里，手势挂在这里，行的高矮进退与它无关。代价是盒子是列表的父节点，指针事件先送
 *    子节点（LazyColumn 自己的滚动在盒子的手势之前），所以拖动期间必须关掉列表的用户滚动
 *    （`userScrollEnabled`）：不关，滚动手势会先把手移动吃掉，盒子上的拖动根本起不来。
 *    哪一行被拿起来，由 `supplierRowUnderFinger` 命中测试布局得出，不再由事件落在谁身上决定。
 *  * **拖动期间不换位。** 以前每跨一格就写一次列表，而 LazyColumn 会跟着「第一可见行」的 key
 *    保持窗口位置：被拖的行换进第一格的那一次，窗口被拽下一格、行当场出视口（同一类断触）；
 *    为压住这次滚动又得逐帧把窗口「钉」回去，于是滚动与钉窗成了同一帧上的两个写手，谁最后
 *    落地取决于测量顺序——用户看到的就是边缘那一段一直闪。现在顺序在松手时写一次，窗口除了
 *    指尖自己的边缘滚动之外没有任何东西动它：钉窗、滚动互斥、换位动画抢帧这一整类问题不存在了。
 *  * **被拖的行画在列表之外（浮层）。** 它跟着手指走，列表里原来那一格留一个等高的空位。
 *    浮层不在 LazyColumn 里，滚多远都不会被回收；它只跟手指有关，与换位之后的行位置无关，
 *    也就没有「行自己挪一格」这回事。
 *
 * 判定没变，还是「指尖落在哪一格」：[supplierSlotUnderFinger] 拿按下时采下的那一帧
 * （[SupplierDragFrame]）算——拖动期间列表不动，这一帧就不会过期；指尖贴住边缘时列表由
 * [supplierAutoScrollStep] 逐帧自己滚，滚过的距离按实际消费量补进判定。落点以「第几格」的
 * 形式在松手时交给 [onMove]，视图模型再夹一次到活列表上，所以一次没离开原格的拖动一个字都不写。
 */
@Composable
private fun SupplierOrderDialog(
    suppliers: List<SupplierProfile>,
    onMove: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 与供应商配置弹窗同一套几何：卡片占屏宽 94%，内容盒按窗口定尺，遮罩自绘。
    val windowBox = rememberDialogWindowBox()
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    val listState = rememberLazyListState()
    var draggingId by remember { mutableStateOf<String?>(null) }
    // 落点：指尖现在指着第几格。整次拖动只改这一个数，列表的顺序等松手才写。
    var targetSlot by remember { mutableIntStateOf(0) }
    // 指尖在列表盒里的纵坐标（盒子的局部坐标，与任何一行无关）；NaN = 手指还没按下。
    var pointerYInBox by remember { mutableFloatStateOf(Float.NaN) }
    // 按下时指尖落在被拖行内的位置：浮层用它把行的上沿对齐到手指底下，行才像被捏着走，
    // 而不是整行跳到指尖中心。
    var grabOffsetPx by remember { mutableFloatStateOf(0f) }
    // 手势盒与列表视口各自在根坐标里的位置，两个都量：手势的坐标属于盒子，滚动的边界属于视口。
    var boxTopInRoot by remember { mutableFloatStateOf(Float.NaN) }
    var listTopInRoot by remember { mutableFloatStateOf(Float.NaN) }
    var listBottomInRoot by remember { mutableFloatStateOf(Float.NaN) }
    // 判定用的那一帧在手指按下时采一次，整次拖动不再重读（见 [SupplierDragFrame]）。
    var dragFrame by remember { mutableStateOf<SupplierDragFrame?>(null) }
    // 这一趟已经滚过的像素：内容从手指底下滑过去的距离要补进判定，否则指尖不动、格子一直在走。
    var autoScrollPx by remember { mutableFloatStateOf(0f) }
    // 只剩一行可量时兜底用的行距；正常路径下格子间距直接量自布局信息。
    val spacingPx = with(LocalDensity.current) { SUPPLIER_ORDER_ROW_SPACING.toPx() }
    val edgeZonePx = with(LocalDensity.current) { SUPPLIER_ORDER_EDGE_ZONE.toPx() }
    // 「指尖底下是第几格」的判定收口成一处：拖动事件与自动滚动都走它。闭包里 suppliers 的
    // 数目在弹窗存活期内不变（弹窗开着时增删不了供应商），其余输入全是状态，读到的永远是
    // 当前值——所以这个闭包被逐帧循环抓着用也不会陈旧。
    val judgeSlot: (Float) -> Int? = { fingerRootY ->
        val frame = dragFrame
        if (frame == null) {
            null
        } else {
            supplierSlotUnderFinger(
                fingerRootY = fingerRootY,
                listTopInRoot = frame.listTopInRoot,
                viewportStartOffset = frame.viewportStartOffset,
                anchorIndex = frame.anchorIndex,
                anchorOffset = frame.anchorOffset,
                anchorSize = frame.anchorSize,
                pitch = frame.pitch,
                lastIndex = suppliers.lastIndex,
                autoScrolledPx = autoScrollPx,
                listBottomInRoot = listBottomInRoot,
            )
        }
    }
    // 松手（或手势被系统取消）：落点写一次，然后把拖动状态清干净。取消不写——用户没有表达
    // 落点，顺序就该原样留着。
    fun endDrag(draggedId: String?, commit: Boolean) {
        if (commit && draggedId != null) onMove(draggedId, targetSlot)
        draggingId = null
        dragFrame = null
        pointerYInBox = Float.NaN
        autoScrollPx = 0f
    }
    // 指尖贴着视口边缘时列表跟着手指滚，一直滚到顶/底：手指停在边缘不动也不会中断
    // （滚不动时消费为 0，累积不再增长，落点就停在那一格）。逐帧步进，不是一次跳一屏。
    // 滚动发生的那一帧必须重判一次指尖底下的格子——手指不动、内容在动，只靠拖动事件判定的
    // 落点会停在原地被内容推走（设备实测：按住下边缘，列表滚过五行，落点没跟上）。
    LaunchedEffect(draggingId) {
        if (draggingId == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val pointerRootY = boxTopInRoot + pointerYInBox
            val step = supplierAutoScrollStep(
                fingerRootY = pointerRootY,
                viewportTop = listTopInRoot,
                viewportBottom = listBottomInRoot,
                edgeZonePx = edgeZonePx,
                maxStepPxPerFrame = SUPPLIER_ORDER_AUTO_SCROLL_MAX_PX_PER_FRAME,
            )
            if (step != 0f) {
                var consumed = 0f
                listState.scroll { consumed = scrollBy(step) }
                autoScrollPx += consumed
                if (consumed != 0f) {
                    judgeSlot(pointerRootY)?.let { targetSlot = it }
                }
            }
        }
    }
    // 长按列表里的任意一行即可拖动，押在整行上而不是右端那个 44dp 的手柄上：手柄太小，手指
    // 按住它再拖，起手一点抖动就滑出它的边界，手势当场取消（更早那一版的「拖不动」）。
    // 键取行距：它在整次拖动里不会变，键一变这个块就重启、手势跟着断。
    val dragModifier = Modifier.pointerInput(spacingPx) {
        detectDragGesturesAfterLongPress(
            onDragStart = { start ->
                val frame = currentSupplierDragFrame(listState, listTopInRoot, spacingPx)
                val rootY = boxTopInRoot + start.y
                val hit = frame?.let { supplierRowUnderFinger(listState, rootY, it) }
                // 押在两行之间、押在列表的内边距或标题上：没有行可以拿起来，这一下不成为拖动
                // （也就不会消费这串事件，列表照旧能滚）。
                if (frame != null && hit != null) {
                    pointerYInBox = start.y
                    grabOffsetPx = rootY - hit.topInRoot
                    dragFrame = frame
                    targetSlot = hit.index
                    autoScrollPx = 0f
                    draggingId = hit.id
                }
            },
            onDragEnd = { endDrag(draggingId, commit = true) },
            onDragCancel = { endDrag(draggingId, commit = false) },
            onDrag = { change, _ ->
                if (draggingId == null) return@detectDragGesturesAfterLongPress
                change.consume()
                pointerYInBox = change.position.y
                judgeSlot(boxTopInRoot + change.position.y)?.let { targetSlot = it }
            },
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = windowBox, contentAlignment = Alignment.Center) {
            DialogDismissScrim(onDismiss = onDismiss)
            Box(modifier = Modifier.fillMaxWidth(0.94f)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxSheetHeight),
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 6.dp,
                    shadowElevation = 10.dp,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(
                                start = 24.dp,
                                end = 24.dp,
                                top = 20.dp,
                                bottom = 4.dp,
                            ),
                        ) {
                            // 标题下面那行说明（「按住任意一行拖动即可换位…」）按用户要求删掉了：
                            // 面板上只有一种操作，行右边就是拖动手柄，说明文字只是占地方。
                            Text("供应商排序", style = MaterialTheme.typography.headlineSmall)
                        }
                        // 列表与浮层共用一个盒子、一套局部坐标：手势挂在这个盒子上（行会被回收，
                        // 盒子不会），浮层也在这个盒子里按它的坐标摆。
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .onGloballyPositioned { boxTopInRoot = it.positionInRoot().y }
                                .then(dragModifier),
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned {
                                        listTopInRoot = it.positionInRoot().y
                                        listBottomInRoot = it.positionInRoot().y + it.size.height
                                    },
                                state = listState,
                                // 拖动期间关掉列表自己的滚动，理由见函数头：指针事件先送子节点，
                                // 不关就会把手指的移动吃掉，盒子上的拖动起不来。边缘自动滚动走的是
                                // 程序滚动（listState.scroll），不受这个开关影响。
                                userScrollEnabled = draggingId == null,
                                verticalArrangement = Arrangement.spacedBy(SUPPLIER_ORDER_ROW_SPACING),
                                contentPadding = PaddingValues(
                                    start = 12.dp,
                                    end = 12.dp,
                                    top = 8.dp,
                                    bottom = 8.dp,
                                ),
                            ) {
                                itemsIndexed(
                                    items = suppliers,
                                    key = { _, supplier -> supplier.id },
                                    // 松手落地时行是平移过去的，不是原地闪一下；animateItem 需要
                                    // 稳定身份，key 已经给了。
                                    contentType = { _, _ -> "supplier-order-row" },
                                ) { index, supplier ->
                                    if (supplier.id == draggingId) {
                                        // 被拖的这一行此刻画在浮层上，这里只留一个等高的空位：
                                        // 列表的几何（因此还有判定用的那一帧）整次拖动都不变，
                                        // 松手之前也不会有任何一行挪位置。
                                        Spacer(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(SUPPLIER_ORDER_ROW_HEIGHT)
                                                .animateItem(),
                                        )
                                    } else {
                                        SupplierOrderRow(
                                            supplier = supplier,
                                            position = index + 1,
                                            isDragging = false,
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                }
                            }
                            val dragged = suppliers.firstOrNull { it.id == draggingId }
                            if (dragged != null) {
                                // 浮层：位置只由指尖决定，与这一行在列表里的位置无关。序号显示
                                // 它将落到的位置，「手指在哪儿、会落到第几位」一眼可见。
                                SupplierOrderRow(
                                    supplier = dragged,
                                    position = targetSlot + 1,
                                    isDragging = true,
                                    modifier = Modifier
                                        .zIndex(1f)
                                        .offset {
                                            IntOffset(0, overlayOffsetY(pointerYInBox, grabOffsetPx))
                                        },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("完成")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 浮层的纵坐标：手指按下时捏住行内的那一点要一直贴在指尖下，行才像被捏着走。
 *
 * 指尖还没落位（NaN）时给 0：`roundToInt` 对 NaN 会抛异常，而这个偏移每帧都要算一遍。
 * 纯函数，为的是「捏在哪、行就停在哪」这条几何能脱离设备被钉住。
 */
internal fun overlayOffsetY(pointerYInBox: Float, grabOffsetPx: Float): Int {
    if (pointerYInBox.isNaN()) return 0
    return (pointerYInBox - grabOffsetPx).roundToInt()
}

/**
 * 排序面板里的一行——拖动时也在浮层上充当被提着的那一行。
 *
 * 它现在只负责画，换位的手势挂在列表盒上（见 [SupplierOrderDialog]）。两条理由让它回不去
 * 「行自己带着手势」：手势跟着挂载它的节点一起死，而行正是 LazyColumn 会回收的那个节点；
 * 一行也判断不出押在它身上的手指是压着它还是压着邻行，那要整张列表的布局信息。
 *
 * 整行都是把手，不是右端那个手柄图标：图标只有 44dp 宽，手指按住它再拖，起手一点抖动就滑出
 * 它的边界，手势当场取消——图标退回成「这里可以拖」的提示，语义另外给读屏用。
 */
@Composable
private fun SupplierOrderRow(
    supplier: SupplierProfile,
    position: Int,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "拖动排序 ${supplier.name}" },
        color = if (isDragging) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (isDragging) 4.dp else 0.dp,
        // 浮层是「提起来」的那一行：投影把它与列表分开，不再需要靠换位动画表达「它在动」。
        shadowElevation = if (isDragging) 6.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SUPPLIER_ORDER_ROW_HEIGHT)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 序号用主色并加粗：一眼能看出「这是第几位」，灰色小字在浅底上几乎是装饰。
            Text(
                text = position.toString(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(28.dp),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = supplier.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun SupplierConfigurationDialog(
    draft: SupplierDraft,
    errors: FormErrors,
    enabled: Boolean,
    isSecretsHydrating: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    deleteEnabled: Boolean,
    onNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onProtocolChange: (RelayProtocol) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onTestingDisabledChange: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    // 卡片要占屏宽 94%，所以关掉平台的默认宽度（它只给约 320dp）；代价是 Compose 会按整屏测量
    // 内容，盒子的高度得自己按窗口算，见 rememberDialogWindowBox()——注意在弹窗**外面**调用：
    // 弹窗窗口夹在状态栏与导航栏之间，在里面读内边距一律是 0。
    val windowBox = rememberDialogWindowBox()
    // 卡片最高到屏高的九成（与「关于与更新」同一条），表单区按内容自适应拉高：以前写死
    // 480dp，高屏上表单明明放得下也要在小框里滚。到顶以后由表单区自己滚，卡片不再长高。
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 外层这层是为了「点到卡片外就关掉」：内容铺满整个窗口时，平台看不到「在窗口内但在卡片外」
        // 的点击，dismissOnClickOutside 不会触发，遮罩只能自己画（见 DialogDismissScrim）。
        // 它同时把内容居中——卡片的高度随内容走，居中得自己说。
        Box(modifier = windowBox, contentAlignment = Alignment.Center) {
            DialogDismissScrim(onDismiss = onDismiss)
            Box(modifier = Modifier.fillMaxWidth(0.94f)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxSheetHeight),
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 6.dp,
                    shadowElevation = 10.dp,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(
                                    start = 24.dp,
                                    end = 64.dp,
                                    top = 20.dp,
                                    bottom = 20.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text("供应商配置", style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    draft.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // Destructive control lives inside the card's own top-right
                            // corner so it reads as part of this supplier's sheet.
                            IconButton(
                                onClick = onDelete,
                                enabled = enabled && deleteEnabled,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(end = 12.dp, top = 12.dp)
                                    .size(44.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = if (deleteEnabled) {
                                        "删除该供应商"
                                    } else {
                                        "至少保留一个供应商，无法删除"
                                    },
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 自适应：内容放得下就按内容高（卡片跟着长），放不下才收窄、由这里滚。
                                // fill = false 是「长到够用为止」，不是「把剩下的都占掉」。
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp),
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
                                onTestingDisabledChange = onTestingDisabledChange,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("关闭")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = onSave,
                                enabled = enabled,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("保存供应商") }
                        }
                    }
                }
            }
        }
    }
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
    onTestingDisabledChange: (Boolean) -> Unit,
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
        if (RelayBaseUrl.isCleartext(draft.baseUrl)) {
            // Cleartext is opt-in, so the warning only appears once the user has
            // actually typed the http:// prefix.
            Text(
                "明文 HTTP：密钥与内容不加密，仅限本机/内网。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
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
            "密钥存于 Keystore；加密备份含密钥。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PullOnlyModeRow(
            checked = draft.isTestingDisabled,
            enabled = enabled,
            onCheckedChange = onTestingDisabledChange,
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

/**
 * Pull-only switch for the supplier form. The site still serves its model
 * directory, but nothing is sent to it for a connectivity check.
 */
@Composable
private fun PullOnlyModeRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (checked) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
            Text(
                "禁止测试：只拉模型，不发测试请求。",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The animation test entry, beside 高级测试 in the settings header.
 *
 * The running state is a dot on the icon, never a change of label: the button's job is to
 * be findable, and a label that rewrites itself under the user's eyes moves the target they
 * were aiming at. The dot alone carries "something of yours is still generating", and it
 * rides on the icon so it survives the label being truncated.
 */
@Composable
private fun AnimationTestEntry(generating: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 6.dp),
        modifier = Modifier
            .heightIn(min = 28.dp, max = 30.dp)
            .widthIn(min = 64.dp, max = HEADER_BUTTON_MAX_WIDTH.scaledForFont()),
    ) {
        Box {
            Icon(
                Icons.Outlined.PlayCircleOutline,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            if (generating) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-3).dp)
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(3.dp))
        Text(
            "动画测试",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The model catalog entry; unchanged apart from being extracted from the header row. */
@Composable
private fun AdvancedCatalogButton(
    enabled: Boolean,
    isUnifiedTesting: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        // 统一测试跑着的时候，这个按钮是回弹窗看进度、取消测试的唯一入口：
        // 关掉弹窗后主界面没有任何进行中指示，再禁掉它，用户就被锁在一轮
        // 看不见的测试外面了。普通批量测试运行中它仍然禁用（那是另一条链）。
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 6.dp),
        modifier = Modifier
            .heightIn(min = 28.dp, max = 30.dp)
            .widthIn(min = 64.dp, max = HEADER_BUTTON_MAX_WIDTH.scaledForFont()),
    ) {
        Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text(
            if (isUnifiedTesting) "统一测试中…" else "高级测试",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Width the two header entries need together, plus the gap between them, at font scale 1.
 *
 * 动画测试 and 高级测试 are each four characters plus a 14dp icon and 6dp of padding on both
 * sides; this is the pair's natural width, and it is what the header's wrap test is measured
 * against. It has to move with the buttons: left too wide, the header wraps to its fallback
 * layout on a screen where the row actually fits, and the two buttons drop below the title the
 * user asked to see them aligned with.
 */
private val SETTINGS_HEADER_BUTTONS_WIDTH = 168.dp

/**
 * How wide one header entry may grow, at font scale 1.
 *
 * A cap and not a fixed width: at 1.0 the two entries sit at their natural size, and the
 * ceiling only stops one of them from eating the row.
 */
private val HEADER_BUTTON_MAX_WIDTH = 92.dp

/**
 * The cap, widened by the font scale.
 *
 * `widthIn(max = …)` is in dp while the label is in sp, so a fixed ceiling truncates the
 * text as soon as the user raises 字体大小 — at 1.5x 动画测试 was rendering as 动画… and at
 * 2.0x as 动…, which is the one thing the entry cannot afford: the two buttons are how the
 * user tells the animation test from the catalog, and a label that reads 动… says neither.
 * Scaling the ceiling keeps the label whole; the header's wrap test
 * ([SETTINGS_HEADER_BUTTONS_WIDTH] × fontScale) already measures the row against the same
 * scale, so the pair still drops below the title when the row genuinely does not fit.
 */
@Composable
private fun Dp.scaledForFont(): Dp {
    val fontScale = LocalDensity.current.fontScale
    return this * (if (fontScale > 1f) fontScale else 1f)
}

/** Room the 测试参数 title must keep to avoid being squeezed into a one-character column. */
private val SETTINGS_HEADER_TITLE_MIN_WIDTH = 68.dp

/** The gap between the title and the first entry, and between the two entries. */
private val SETTINGS_HEADER_GAP = 16.dp

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
    onOpenAnimationSheet: () -> Unit,
    animationGenerating: Boolean,
    onCancel: () -> Unit,
    onCancelUnifiedTest: () -> Unit,
    isFetchingModels: Boolean,
    isRunning: Boolean,
    isUnifiedTesting: Boolean,
) {
    val density = LocalDensity.current
    var showAdvanced by rememberSaveable(draft.id) { mutableStateOf(false) }
    var showQuickFilterDialog by rememberSaveable(draft.id) { mutableStateOf(false) }
    var quickFilterInput by rememberSaveable(draft.id) { mutableStateOf("") }
    // 与同组三个 saveable 对齐：旋转时弹窗本身（showQuickFilterDialog 的兄弟态）会留下，
    // 待确认的词却跟着 remember 没了，看起来像确认框自己消失了。
    var quickFilterToDelete by rememberSaveable { mutableStateOf<String?>(null) }
    ElevatedCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                // 两个入口（动画测试、高级测试）与标题争的是同一行的宽度：按钮的宽度随字号
                // 一起长，所以判断也要跟着字号走，而不是写死一个宽度阈值。按字号缩放按钮需求
                // 宽度，再留出标题不被挤成竖排的最小宽度，放不下才让标题独占一行、两个按钮
                // 另起一行靠右——与操作栏、挑战头部同一条「放不下就换行」的规则。
                val buttonScale = if (density.fontScale > 1f) density.fontScale else 1f
                val sideBySide = maxWidth >=
                    SETTINGS_HEADER_TITLE_MIN_WIDTH +
                    SETTINGS_HEADER_BUTTONS_WIDTH * buttonScale +
                    SETTINGS_HEADER_GAP
                if (sideBySide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            SectionTitle("测试参数")
                        }
                        AnimationTestEntry(
                            generating = animationGenerating,
                            onClick = onOpenAnimationSheet,
                        )
                        AdvancedCatalogButton(
                            enabled = enabled || isUnifiedTesting,
                            isUnifiedTesting = isUnifiedTesting,
                            onClick = onOpenModelCatalog,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionTitle("测试参数")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AnimationTestEntry(
                                generating = animationGenerating,
                                onClick = onOpenAnimationSheet,
                            )
                            AdvancedCatalogButton(
                                enabled = enabled || isUnifiedTesting,
                                isUnifiedTesting = isUnifiedTesting,
                                onClick = onOpenModelCatalog,
                            )
                        }
                    }
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
                                val filterShape = MaterialTheme.shapes.small
                                Surface(
                                    modifier = Modifier
                                        .width(filterWidth)
                                        .height(48.dp)
                                        .clip(filterShape)
                                        .clickable(
                                            enabled = enabled,
                                            role = Role.Checkbox,
                                            onClick = { onToggleQuickFilter(term) },
                                        )
                                        .semantics {
                                            contentDescription = "模型快捷筛选词：$term"
                                            this.selected = selected
                                        },
                                    shape = filterShape,
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
                                                        .align(Alignment.CenterEnd)
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
                Text(if (showAdvanced) "收起更多测试参数" else "更多测试参数")
            }
            if (showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Prompt 快速选择",
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
                    "只重试网络、超时、429 与 5xx。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            if (isUnifiedTesting) {
                // 统一测试与批量测试是两条链，取消的是各自那一条。这个按钮让「关掉弹窗
                // 之后还能回来停掉它」成立——以前只有弹窗里有取消，主界面一片安静。
                Button(
                    onClick = onCancelUnifiedTest,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Outlined.StopCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("取消统一测试")
                }
            } else if (isRunning) {
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
                        // Pull-only suppliers keep "获取模型" available but never test.
                        enabled = !isFetchingModels && !draft.isTestingDisabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (draft.isTestingDisabled) "禁止测试" else "开始测试")
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
    onCopyName: (String) -> Unit,
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
                "结果会显示在同一列表里。",
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
                onCopyName = onCopyName,
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
    onFingerprintModel: (String) -> Unit,
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
        // 与 ViewModel 的守卫同一把锁：勾选与批量动作在统一测试进行中同样会被丢弃
        // （runningOrInitializing 两者都认），界面上少禁一个就是「点得动、没反应」。
        val selectionLocked = state.isRunning || state.isUnifiedTesting
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
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                onClick = { onSelectAll(visibleModels) },
                enabled = !selectionLocked && visibleModels.any { it !in state.selectedModels },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("全选") }
            TextButton(
                onClick = { onClearAll(visibleModels) },
                enabled = !selectionLocked && visibleModels.any { it in state.selectedModels },
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
                enabled = !state.isRunning && !state.isUnifiedTesting,
                // 勾选框在整批运行期间仍然冻结，但这一行自己的两个动作只看这一行：
                // 它一旦有结论（成功/失败）就能点，不必等整张列表跑完。
                actionsEnabled = result.status != TestStatus.PENDING,
                onToggle = { onToggleModel(result.model) },
                onCopyName = { model -> onCopy("模型名", listOf(model)) },
                onFingerprint = onFingerprintModel,
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
    val results = state.results
    val succeeded = remember(results) { results.count { it.status == TestStatus.SUCCESS } }
    val failed = remember(results) { results.count { it.status == TestStatus.FAILED } }
    val skipped = remember(results) { results.count { it.status == TestStatus.SKIPPED } }
    val total = results.size
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
            SectionTitle(
                "测试结果",
                buildAnnotatedString {
                    pushStyle(SpanStyle(color = Color(0xFF15803D)))
                    append("可用 " + succeeded)
                    pop()
                    append(" · ")
                    pushStyle(SpanStyle(color = MaterialTheme.colorScheme.error))
                    append("不可用 " + failed)
                    pop()
                    // 仅拉取的行既没跑也没失败，单独报数：把它并进「不可用」会让一次
                    // 按策略完成的全跳过测试看起来像全军覆没。
                    if (skipped > 0) {
                        append(" · ")
                        pushStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant))
                        append("已跳过 " + skipped)
                        pop()
                    }
                },
            )
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
                    valueColor = MaterialTheme.colorScheme.tertiary,
                )
                SummaryMetric(
                    "总 token",
                    summary?.totalTokens?.toString() ?: "—",
                    Modifier.weight(1f),
                    valueColor = MaterialTheme.colorScheme.primary,
                )
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
        // 大字号下三枚筛选 Chip 与排序按钮挤在一行里会各自折行（2× 时「按延迟」被拆成
        // 两行并顶破卡片）。超过阈值就让排序按钮整块落到第二行、右对齐，按钮文字本身
        // 在任何字号下都不换行。
        val stackSortButton = LocalDensity.current.fontScale >= 1.5f
        val filterChips: @Composable () -> Unit = {
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
        }
        val sortButton: @Composable () -> Unit = {
            OutlinedButton(
                onClick = {
                    onSortChange(
                        if (state.sort == ResultSort.LATENCY) ResultSort.NAME else ResultSort.LATENCY,
                    )
                },
            ) {
                Text(
                    if (state.sort == ResultSort.LATENCY) "按延迟" else "按名称",
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (stackSortButton) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { filterChips() }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) { sortButton() }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filterChips()
                Spacer(Modifier.weight(1f))
                sortButton()
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
    /**
     * Whether this row's own two actions are live. They only exist once the row has an
     * outcome, and from that moment nothing about the rest of the batch can change them,
     * so a run still working through other rows leaves this one readable instead of
     * locking every card until the whole list finishes.
     */
    actionsEnabled: Boolean = enabled,
    onToggle: () -> Unit = {},
    onCopyName: ((String) -> Unit)? = null,
    /** Only offered on a reachable model: a failed test has no answer to fingerprint. */
    onFingerprint: ((String) -> Unit)? = null,
) {
    var expanded by rememberSaveable(result.model, result.status) { mutableStateOf(false) }
    val statusColor = when (result.status) {
        TestStatus.SUCCESS -> Color(0xFF16A34A)
        TestStatus.FAILED -> MaterialTheme.colorScheme.error
        TestStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        TestStatus.PENDING -> MaterialTheme.colorScheme.tertiary
    }
    val failureDetail = result.error?.message
        ?.takeIf { message ->
            message.isNotBlank() &&
                message != result.error?.kind?.label &&
                !failureSummary(result).contains(message)
        }
    val canExpandFailure = !isFetchedOnly && result.status == TestStatus.FAILED && failureDetail != null
    // 首格宽度：「详情 / 收起」是两个汉字，40dp 的格子扣掉 8dp 右侧内缩后，小字号刚好、
    // 大字号会顶出去（实测 2.0× 时这两个字要 48dp）。所以按**实际排版宽度**算一次格子
    // 宽度，不再按字号档位猜。首格只有「详情」有（失败行），其余行是同宽占位；复制的 x
    // 只由末格决定，所以它仍然不随状态左右跳。
    val labelStyle = MaterialTheme.typography.labelMedium
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelWidth = remember(textMeasurer, labelStyle, density) {
        with(density) {
            listOf("详情", "收起").maxOf { textMeasurer.measure(it, labelStyle).size.width }.toDp()
        }
    }
    val trailingWidth = maxOf(RESULT_ACTION_SLOT, labelWidth + RESULT_PILL_INSET)
    // 末格是复制，它的中心要落在状态胶囊里**文字**的中心上：胶囊左右各有 8dp 内边距，
    // 而 40dp 的格子中心离内容右缘 20dp，差的量随字号缩放变（实测「可用」22dp 时差 1dp，
    // 2.0× 的 44dp 时差 10dp），所以按实排字宽算一次偏移，不按字号档位猜。量的是「可用」：
    // 用户要的就是与它对齐；「失败 / 待测」同为两个汉字、宽度相同 ⇒ 复制在三种状态行里
    // 停在同一个 x。
    val pillStyle = MaterialTheme.typography.labelSmall
    val pillLabelWidth = remember(textMeasurer, pillStyle, density) {
        with(density) {
            textMeasurer.measure(statusChipLabel(TestStatus.SUCCESS, isFetchedOnly = false), pillStyle)
                .size.width.toDp()
        }
    }
    val copyOffset = copyAlignmentOffset(RESULT_ACTION_SLOT, RESULT_PILL_INSET, pillLabelWidth)
    OutlinedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 第 1 行：勾选框 · 状态点 · 模型名（占满剩余宽度）· 状态胶囊。勾选框放在行内
            // 而不是另起一列：它因此与名称、胶囊永远在同一条中线上，也不会像旧版那样按
            // 整张卡片的高度居中、被失败行下面那行详情文本推下去。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectable) {
                    Box(
                        modifier = Modifier.size(32.dp),
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
                        .size(8.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(statusColor),
                )
                Text(
                    result.model,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ResultStatusChip(result.status, isFetchedOnly)
            }
            // 第 2 行：指标胶囊（永远单行）在左，固定宽度的动作轨在右。旧版这里是 FlowRow
            // 与图标混排：空间不够就换行，后面那个图标还会把复制按钮顶得左右乱跳。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        // 与第 1 行的状态点左对齐（勾选框 32dp + 间距 6dp）；没有勾选框时
                        // 不需要缩进，胶囊就从卡片内边距开始。
                        .padding(start = if (selectable) 38.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (result.status) {
                        TestStatus.SUCCESS -> {
                            // 一个指标一种颜色，全 app 同一套：延迟青、token 蓝、
                            // 结论绿/红；胶囊底色是同色 12%，不靠颜色也能读文字。
                            result.latencyMs?.let {
                                ResultMetricChip(it.toString() + " ms", MaterialTheme.colorScheme.tertiary)
                            }
                            result.finishReason?.let { reason ->
                                val label = finishReasonLabel(reason)
                                val color = when (label) {
                                    "正常" -> Color(0xFF15803D)
                                    "长度截断" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                // 唯一可压缩的一枚：宽度不够时它省略，延迟与 token 保持完整。
                                ResultMetricChip(label, color, Modifier.weight(1f, fill = false))
                            }
                            val tokenTotal = result.usage?.resolvedTotal?.takeIf { it > 0 }
                            tokenTotal?.let {
                                ResultMetricChip(it.toString() + " tok", MaterialTheme.colorScheme.primary)
                            }
                            if (result.latencyMs == null && result.finishReason == null && tokenTotal == null) {
                                ResultMetricChip("请求成功", Color(0xFF15803D))
                            }
                        }
                        TestStatus.FAILED -> {
                            result.httpStatus?.let {
                                ResultMetricChip("HTTP $it", MaterialTheme.colorScheme.error)
                            }
                            ResultMetricChip(
                                result.error?.kind?.label ?: "请求失败",
                                MaterialTheme.colorScheme.error,
                                Modifier.weight(1f, fill = false),
                            )
                        }
                        TestStatus.SKIPPED -> {
                            // No request was sent, so there is no latency, token count or
                            // HTTP code to print — only the reason it was skipped.
                            ResultMetricChip(
                                result.error?.message ?: "该供应商已禁止测试，仅允许拉取模型",
                                MaterialTheme.colorScheme.onSurfaceVariant,
                                Modifier.weight(1f, fill = false),
                            )
                        }
                        TestStatus.PENDING -> {
                            // 状态词由名称行的胶囊承载；这里补一句进度说明，从未跑过的行
                            // 换成更具体的提示。一句话都不放会让第二行只剩右侧图标。
                            Text(
                                if (isFetchedOnly) "开始测试后显示延迟与用量" else "等待测试…",
                                modifier = Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // 动作轨：两格 —— [指纹 / 详情 / 空 · 首格][复制 · 末格]。
                //
                // 这一轮是**真的把两个图标换位**：复制搬到指纹原来待的那一格（末格，
                // 中心按 copyOffset 对齐状态胶囊里「可用」两字的中心），指纹搬到复制原来
                // 待的那一格（首格 40dp，连同旧版复制那个右推 4dp 一起照搬），不再是
                // 「指纹插到复制左边、复制不动」。
                //
                // 首格在成功行放指纹、失败行放「详情 / 收起」：指纹只在成功行、详情只在
                // 失败行，两者永不同行，所以共用一个格子。首格宽度按「详情 / 收起」的实排
                // 宽度算（大字号才顶得出去），复制的 x 只由末格决定 ⇒ 复制在成功 / 失败 /
                // 待测行里停在同一个 x。两格都渲染（缺内容用同宽占位），行高恒为
                // RESULT_ACTION_SLOT，免得这一行被首格撑高。
                when {
                    onFingerprint != null && result.status == TestStatus.SUCCESS -> {
                        IconButton(
                            onClick = { onFingerprint(result.model) },
                            enabled = actionsEnabled,
                            modifier = Modifier.size(RESULT_ACTION_SLOT),
                        ) {
                            Icon(
                                Icons.Outlined.Fingerprint,
                                contentDescription = "检测 ${result.model} 的模型指纹",
                                // 与旧版复制图标同一个内推：紧贴右边的复制，而不是悬在
                                // 格子正中。
                                modifier = Modifier.size(16.dp).offset(x = 4.dp),
                            )
                        }
                    }
                    canExpandFailure -> {
                        Box(
                            modifier = Modifier
                                .width(trailingWidth)
                                .height(RESULT_ACTION_SLOT)
                                // 不要涟漪：点击时默认会在这块 40dp 见方的格子上铺一层
                                // 方形高光，看着像凭空多出一个按钮框。
                                .clickable(
                                    enabled = actionsEnabled,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                ) { expanded = !expanded },
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Text(
                                if (expanded) "收起" else "详情",
                                // 右缘内缩 8dp，于是它贴着右边复制的格子，两行的动作
                                // 间距一致。
                                modifier = Modifier.padding(end = RESULT_PILL_INSET),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    else -> Spacer(Modifier.size(RESULT_ACTION_SLOT))
                }
                if (onCopyName != null) {
                    QuietIconButton(
                        onClick = { onCopyName(result.model) },
                        modifier = Modifier.size(RESULT_ACTION_SLOT),
                    ) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "复制模型名 ${result.model}",
                            modifier = Modifier.size(16.dp).offset(x = copyOffset),
                        )
                    }
                } else {
                    Spacer(Modifier.size(RESULT_ACTION_SLOT))
                }
            }
            if (result.status == TestStatus.FAILED && failureDetail != null) {
                Text(
                    failureDetail,
                    modifier = Modifier.padding(start = if (selectable) 38.dp else 0.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    softWrap = expanded,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                )
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
    // 重测与导出同样要认统一测试：ViewModel 的守卫是两者都拒，界面只认 isRunning 时
    // 按钮亮着而点击被吞。导出尤其要挡住——统一测试跑着时它导的是批量结果，不是用户
    // 以为的当前整份。
    val runLocked = state.isRunning || state.isUnifiedTesting
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onRetestAll,
                enabled = !runLocked && selectedResults > 0,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("重测已选")
            }
            OutlinedButton(
                onClick = onRetryFailed,
                enabled = !runLocked && selectedFailed.isNotEmpty(),
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
                // 动宾结构要对称：“复制失败”会被读成“复制操作失败了”。
                Text("复制可用项")
            }
            OutlinedButton(
                onClick = { onCopy("失败模型", failed) },
                enabled = failed.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text("复制失败项")
            }
        }
        Button(
            onClick = onExport,
            enabled = !runLocked,
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
    // The default 24 dp icon towers over this note's 16 sp line, and top alignment
    // then leaves its centre below the text's. Match the line box and centre on it, so
    // the glyph rides the text's centre line at every font scale.
    val lineHeight = MaterialTheme.typography.bodySmall.lineHeight
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Security,
            contentDescription = null,
            modifier = Modifier.size(with(LocalDensity.current) { lineHeight.toDp() }),
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            "HTTPS 优先；http:// 为明文。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    SectionTitle(title, buildAnnotatedString { append(subtitle) })
}

@Composable
private fun SectionTitle(title: String, subtitle: AnnotatedString) {
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
    valueColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
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
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 指标胶囊：圆角配方 + 同色 12% 底色，等宽数字让各行的 ms / tok 对齐。
 *
 * 高度取 18.sp 换算成的 dp（随字号缩放），而不是纵向 padding：CJK（「长度截断」）与
 * 数字（「25 tok」）的行盒天生不一样高，几枚胶囊并排就会上下参差；固定高度 + 垂直
 * 居中让它们逐像素等高，字号放大后也等高。
 */
@Composable
private fun ResultMetricChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(with(LocalDensity.current) { 18.sp.toDp() })
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 状态胶囊：全圆角，把「可用 / 失败 / 待测 / 等待中」放在名称行右端。 */
@Composable
private fun ResultStatusChip(status: TestStatus, isFetchedOnly: Boolean = false) {
    val label = statusChipLabel(status, isFetchedOnly)
    val color = when (status) {
        TestStatus.SUCCESS -> Color(0xFF15803D)
        TestStatus.FAILED -> MaterialTheme.colorScheme.error
        TestStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        TestStatus.PENDING -> if (isFetchedOnly) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.tertiary
        }
    }
    Box(
        // 与指标胶囊同高，名称行的高度因此不随状态词长短变化。
        modifier = Modifier
            .height(with(LocalDensity.current) { 18.sp.toDp() })
            .clip(RoundedCornerShape(percent = 50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
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
    // 上游各家自造的收尾词：同一含义在不同中转站叫法不一，长短不齐的原文（如
    // "incomplete"）会把胶囊撑成长条、把这一行挤到换行，常见几档统一成短标签。
    "incomplete" -> "未完成"
    "content_filter" -> "内容过滤"
    "tool_calls", "tool_use", "function_call" -> "工具调用"
    "refusal" -> "被拒绝"
    "canceled", "cancelled" -> "已取消"
    // 其余照旧展示原文；胶囊是这一行里唯一可压缩的一枚，超长时省略而不是换行。
    else -> value
}

private fun formatElapsed(milliseconds: Long): String =
    String.format(java.util.Locale.ROOT, "%.1f s", milliseconds / 1_000.0)

private fun copyNames(context: Context, label: String, values: List<String>) {
    if (values.isEmpty()) {
        Toast.makeText(context, "没有可复制的" + label, Toast.LENGTH_SHORT).show()
        return
    }
    // 单条读「已复制模型名」，多条读「已复制 3 个失败模型」——「1 个」在中文里是多余的。
    val confirmation = if (values.size == 1) "已复制$label" else "已复制 ${values.size} 个$label"
    copyToClipboard(context, values.joinToString("\n"), confirmation)
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
    onSearchAllModels: (String) -> Unit,
    onClearPicker: () -> Unit,
    onClearPickerPreservingSearch: () -> Unit,
    onSaveEntry: (String?, String, List<ModelSource>) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onRunEntry: (String) -> Unit,
    onRunSource: (String, ModelSource) -> Unit,
    onRefreshEntry: (String) -> Unit,
    onCancelRun: () -> Unit,
    onRemoveSource: (String, ModelSource) -> Unit,
) {
    var editingEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var entryName by rememberSaveable { mutableStateOf("") }
    val selectableSuppliers = state.suppliers.filterNot { it.isTestingDisabled }
    var selectedSupplierId by rememberSaveable {
        mutableStateOf(selectableSuppliers.firstOrNull()?.id)
    }
    var selectedModel by rememberSaveable { mutableStateOf("") }
    // Saved like the rest of the editor: without this, a rotation restored the title and the
    // name but emptied the source list, and the next 保存修改 replaced the entry's stored
    // sources with whatever had been re-added — silently dropping the rest.
    var pendingSources by rememberSaveable(stateSaver = modelSourcesSaver) {
        mutableStateOf<List<ModelSource>>(emptyList())
    }
    var catalogSearchKeyword by rememberSaveable { mutableStateOf("") }
    var editingSearchKeyword by rememberSaveable { mutableStateOf("") }
    // 这三处与编辑器同组：弹窗开着时旋转，之前只有它们跟着 remember 丢——勾选的检索结果
    // 全清、待确认的移除/删除框连同它的目标一起消失，看起来像确认框自己关了。
    // Set 与 ModelSource 都用扁平化的可存形式（与 modelSourcesSaver 同一做法）。
    var selectedSearchKeys by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf<Set<String>>(emptySet()) }
    var sourceToDeleteKey by rememberSaveable { mutableStateOf<String?>(null) }
    var entryToDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var supplierMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var modelMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val selectedSupplier = selectableSuppliers.firstOrNull { it.id == selectedSupplierId }
    val pickerModels = state.catalogPickerModels
    val searchResults = state.catalogSearchResults
    val unifiedByEntry = state.unifiedResults.groupBy { it.entryId }
    val editingEnabled = !state.isUnifiedTesting
    val entryNameError = if (entryName.length > 256) "模型名称不能超过 256 个字符" else null
    val sourceLimitReached = pendingSources.size >= MAX_MODEL_SOURCES_PER_ENTRY
    // 认的是「这个供应商现在还可选」，不是「当初记住了一个 id」：弹窗开着时用户可能把当前
    // 供应商设成「禁止测试」（另一个页面），只判 id 非空就还能从它添加来源——而那个来源
    // 之后必然被策略拒绝，等于在弹窗里留下一条永远跑不了的配置。
    val canAddSource = editingEnabled && !sourceLimitReached &&
        selectedSupplier?.id != null && selectedModel.isNotBlank()
    val canSave = editingEnabled &&
        entryName.trim().isNotEmpty() &&
        entryNameError == null &&
        pendingSources.isNotEmpty()

    fun resetDraft() {
        editingEntryId = null
        entryName = ""
        selectedModel = ""
        pendingSources = emptyList()
        catalogSearchKeyword = ""
        editingSearchKeyword = ""
        selectedSearchKeys = emptySet()
        onClearPicker()
    }

    // R03：草稿只在持久化成功之后清空。结果带着保存时提交的草稿快照，只有当前草稿
    // 仍是那一份时才重置：失败保留全部输入，保存后再编辑、换草稿或关掉重开都不会被
    // 晚到的成功事件清掉。
    LaunchedEffect(state.catalogSaveOutcome?.id) {
        val outcome = state.catalogSaveOutcome ?: return@LaunchedEffect
        if (catalogSaveResetsDraft(outcome, editingEntryId, entryName, pendingSources)) {
            resetDraft()
        }
    }

    // 卡片要占屏宽 94%，所以关掉平台的默认宽度（它只给约 320dp）；代价是 Compose 会按整屏测量
    // 内容，卡片「可用高度的九成」也就跟着算错（比可见窗口还高、底部一截在屏幕外），盒子的高度
    // 得自己按窗口算，见 rememberDialogWindowBox()——同样要在弹窗外面调用。
    val windowBox = rememberDialogWindowBox()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 自绘遮罩 + 内容居中：内容铺满整个窗口时平台看不到「窗口内、卡片外」的点击，遮罩只能
        // 自己画；卡片高度随内容走，居中由这层盒子来说。见 DialogDismissScrim。
        Box(modifier = windowBox, contentAlignment = Alignment.Center) {
            DialogDismissScrim(onDismiss = onDismiss)
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
                            "同一模型可配多个供应商来源，分别检查连接。",
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
                        Text("来源管理", style = MaterialTheme.typography.titleSmall)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            tonalElevation = 1.dp,
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("跨供应商检索", style = MaterialTheme.typography.titleSmall)
                                    }
                                    if (state.isCatalogSearching) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    }
                                }
                                // 贴底对齐，再让出 MODEL_SEARCH_BUTTON_INSET：OutlinedTextField 的
                                // 布局盒是 64dp，画出来的边框只占下面 56dp（顶端 8dp 留给浮动
                                // 标签），按布局盒居中会让按钮看起来偏高。1.0× 与 2.0× 字号下都用
                                // uiautomator 量过节点与可见边框。
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = catalogSearchKeyword,
                                        onValueChange = { catalogSearchKeyword = it },
                                        modifier = Modifier.weight(1f),
                                        enabled = editingEnabled && !state.isCatalogSearching,
                                        label = { Text("模型关键词") },
                                        placeholder = { Text("例如 gpt、claude") },
                                        singleLine = true,
                                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                    )
                                    Button(
                                        onClick = {
                                            selectedSearchKeys = emptySet()
                                            onSearchAllModels(catalogSearchKeyword)
                                            editingSearchKeyword = catalogSearchKeyword
                                        },
                                        enabled = editingEnabled && !state.isCatalogSearching && catalogSearchKeyword.isNotBlank(),
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        modifier = Modifier
                                            .padding(bottom = MODEL_SEARCH_BUTTON_INSET)
                                            .heightIn(min = 48.dp),
                                    ) {
                                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("检索", maxLines = 1, softWrap = false)
                                    }
                                }
                                if (searchResults.isNotEmpty()) {
                                    val searchResultKeys = searchResults.map {
                                        it.supplierId + "\u0000" + it.modelId
                                    }.toSet()
                                    val pendingSourceKeys = pendingSources.map {
                                        it.supplierId + "\u0000" + it.modelId
                                    }.toSet().intersect(searchResultKeys)
                                    LaunchedEffect(searchResults, pendingSourceKeys) {
                                        selectedSearchKeys = selectedSearchKeys
                                            .intersect(searchResultKeys)
                                            .subtract(pendingSourceKeys)
                                    }
                                    Text(
                                        "找到 ${searchResults.size} 个模型，勾选后添加来源",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 240.dp)
                                            .verticalScroll(rememberScrollState()),
                                    ) {
                                        searchResults.forEach { result ->
                                            val resultKey = result.supplierId + "\u0000" + result.modelId
                                            val resultLock = resultKey in pendingSourceKeys
                                            val checked = resultLock || resultKey in selectedSearchKeys
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable(
                                                        enabled = editingEnabled && !resultLock,
                                                        onClick = {
                                                            selectedSearchKeys = if (checked) {
                                                                selectedSearchKeys - resultKey
                                                            } else {
                                                                selectedSearchKeys + resultKey
                                                            }
                                                        },
                                                    )
                                                    .heightIn(min = 48.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Checkbox(
                                                    checked = checked,
                                                    onCheckedChange = { nextChecked ->
                                                        if (!resultLock) {
                                                            selectedSearchKeys = if (nextChecked) {
                                                                selectedSearchKeys + resultKey
                                                            } else {
                                                                selectedSearchKeys - resultKey
                                                            }
                                                        }
                                                    },
                                                    enabled = editingEnabled && !resultLock,
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        result.modelId,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        "来源：${result.supplierName}",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                        color = MaterialTheme.colorScheme.primary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    val selectedSearchCount = selectedSearchKeys
                                        .intersect(searchResultKeys)
                                        .subtract(pendingSourceKeys)
                                        .size
                                    val remainingSourceSlots = (MAX_MODEL_SOURCES_PER_ENTRY - pendingSources.size).coerceAtLeast(0)
                                    OutlinedButton(
                                        onClick = {
                                            val additions = searchResults
                                                .filter { it.supplierId + "\u0000" + it.modelId in selectedSearchKeys }
                                                .take(remainingSourceSlots)
                                                .map { ModelSource(it.supplierId, it.modelId) }
                                            pendingSources = (pendingSources + additions)
                                                .distinctBy { it.supplierId + "\u0000" + it.modelId }
                                            selectedSearchKeys = emptySet()
                                        },
                                        enabled = editingEnabled && selectedSearchCount > 0 && remainingSourceSlots > 0,
                                        modifier = Modifier.heightIn(min = 44.dp),
                                    ) {
                                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("添加已选（$selectedSearchCount）", maxLines = 1, softWrap = false)
                                    }
                                } else if (!state.isCatalogSearching && state.catalogSearchMessage != null) {
                                    Text(
                                        state.catalogSearchMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (state.isCatalogSearchError) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                HorizontalDivider()
                                Text("手动选模型", style = MaterialTheme.typography.titleSmall)
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = { supplierMenuExpanded = true },
                                        enabled = editingEnabled && !state.isCatalogFetching && selectableSuppliers.isNotEmpty(),
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
                                        selectableSuppliers.forEach { supplier ->
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
                                                selectedModel.ifBlank { "尚未选择模型" },
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
                                // 拉取的结果就印在这行按钮底下：页面级的 snackbar 住在活动
                                // 窗口里、被这个弹窗盖着，失败只写那边时用户看到的是「转完圈
                                // 后什么也没发生」。
                                if (!state.isCatalogFetching && state.catalogPickerMessage != null) {
                                    Text(
                                        state.catalogPickerMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (state.isCatalogPickerMessageError) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
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
                                            onClick = {
                                                sourceToDeleteKey =
                                                    source.supplierId + "\u0000" + source.modelId
                                            },
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
                        if (state.modelCatalog.isEmpty()) {
                            Text(
                                "先拉取模型列表，再添加来源。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                state.modelCatalog.forEach { entry ->
                                    val entryTestingKeys = state.unifiedTestingKeys.filter { key ->
                                        key.startsWith("${entry.id}\u0000")
                                    }
                                    val entryTestingCount = entryTestingKeys.size
                                    val entrySourceCount = entry.sources.size
                                    val entryCompletedCount = (entrySourceCount - entryTestingCount).coerceIn(0, entrySourceCount)
                                    // A model whose every source is pull-only has nothing to run.
                                    val entryHasTestableSource = entry.sources.any { source ->
                                        val supplier = state.suppliers.firstOrNull { it.id == source.supplierId }
                                        val missing = "${source.supplierId}\u0000${source.modelId}" in
                                            state.catalogMissingSources
                                        supplier != null && !supplier.isTestingDisabled && !missing
                                    }
                                    ModelCatalogEntryCard(
                                        entry = entry,
                                        // 完整供应商列表，不是 selectableSuppliers：这张卡要显示
                                        // 历史来源所属供应商的名称与「仅拉取」标记。传过滤后的列表
                                        // 会让已禁止测试的供应商显示成「已删除供应商」，用户以为
                                        // 配置丢了。是否可新增/可发起测试由卡片自己的策略判定。
                                        suppliers = state.suppliers,
                                        results = unifiedByEntry[entry.id].orEmpty(),
                                        testingKeys = state.unifiedTestingKeys,
                                        progressDone = entryCompletedCount,
                                        progressTotal = entrySourceCount,
                                        editingEnabled = editingEnabled,
                                        testingEnabled = entryHasTestableSource,
                                        isTesting = entryTestingKeys.isNotEmpty(),
                                        isRefreshing = entry.id in state.catalogRefreshingEntryIds,
                                        missingSources = state.catalogMissingSources,
                                        onEdit = {
                                            editingEntryId = entry.id
                                            entryName = entry.name
                                            pendingSources = entry.sources
                    selectedSupplierId = entry.sources.firstOrNull { source ->
                        selectableSuppliers.any { it.id == source.supplierId }
                    }?.supplierId ?: selectableSuppliers.firstOrNull()?.id
                    selectedModel = ""
                    catalogSearchKeyword = editingSearchKeyword
                    onClearPickerPreservingSearch()
                                        },
                                        onRun = { onRunEntry(entry.id) },
                                        onRunSource = { source -> onRunSource(entry.id, source) },
                                        onCancel = onCancelRun,
                                        onDelete = { entryToDeleteId = entry.id },
                                        onRefresh = { onRefreshEntry(entry.id) },
                                        onRemoveSource = { src ->
                                            // Deleting the entry's last source drops the whole
                                            // entry in the ViewModel, so an in-progress edit of
                                            // that entry would point at a model that no longer
                                            // exists. Reset the draft instead of leaving it stale.
                                            if (entry.sources.size <= 1 && editingEntryId == entry.id) {
                                                resetDraft()
                                            }
                                            onRemoveSource(entry.id, src)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    CatalogSaveFeedback(
                        outcome = state.catalogSaveOutcome,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    CatalogDialogActionBar(
                        canSave = canSave && !state.isSavingCatalogEntry,
                        isSaving = state.isSavingCatalogEntry,
                        canAddSource = canAddSource,
                        editingExisting = editingEntryId != null,
                        onClose = onDismiss,
                        onAddSource = {
                            // 与 canAddSource 同一判据：只接受当前仍可选的供应商，避免在
                            // 按钮被禁用后，这条路径仍把禁止测试的来源写进待保存列表。
                            val supplierId = selectedSupplier?.id
                            if (supplierId != null && selectedModel.isNotBlank()) {
                                pendingSources = (pendingSources + ModelSource(supplierId, selectedModel))
                                    .distinctBy { it.supplierId + "\u0000" + it.modelId }
                                selectedModel = ""
                            }
                        },
                        onSave = { onSaveEntry(editingEntryId, entryName, pendingSources) },
                    )
                }
            }
        }
        sourceToDeleteKey?.let { sourceKey ->
            // 按 key 对当前草稿解引用：旋转恢复后列表已经重新读入，直接存对象会指向旧实例；
            // 对不上（草稿已变）就当确认框已过期，静默收起。
            val source = pendingSources.firstOrNull {
                it.supplierId + "\u0000" + it.modelId == sourceKey
            }
            if (source != null) {
                val supplierName = state.suppliers.firstOrNull { it.id == source.supplierId }?.name ?: "未知供应商"
                AlertDialog(
                    onDismissRequest = { sourceToDeleteKey = null },
                    title = { Text("移除供应商来源？") },
                    text = { Text("将移除「$supplierName · ${source.modelId}」；已保存配置不受影响。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                sourceToDeleteKey = null
                                pendingSources = pendingSources - source
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("移除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { sourceToDeleteKey = null },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("取消") }
                    },
                )
            }
        }
        entryToDeleteId?.let { entryId ->
            val entry = state.modelCatalog.firstOrNull { it.id == entryId }
            if (entry != null) {
                AlertDialog(
                    onDismissRequest = { entryToDeleteId = null },
                    title = { Text("删除已配置模型？") },
                    text = { Text("删除「${entry.name}」及其全部来源，无法撤销。") },
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
}

/**
 * Whether a finished save still owns the draft on screen, and may therefore clear it.
 *
 * The comparison is the whole guard: only a draft that still *is* the saved content —
 * same entry, same name, same sources — gets reset. A draft the user typed into after
 * the click keeps its newer edits, and a dialog reopened fresh never matches (its name
 * is blank), so a late success from a previous session cannot wipe it.
 */
internal fun catalogSaveResetsDraft(
    outcome: CatalogSaveOutcome,
    editingEntryId: String?,
    entryName: String,
    pendingSources: List<ModelSource>,
): Boolean = outcome.success &&
    outcome.entryId == editingEntryId &&
    outcome.savedName == entryName &&
    outcome.savedSources == pendingSources

/**
 * The save outcome, drawn where the save button can be seen.
 *
 * A failure has to be visible inside the dialog: the page-level snackbar lives under
 * this window, and「按了保存却什么都没发生」is the one reading a failed save must not
 * get. The message stays until the next save starts (the outcome is replaced) or the
 * dialog is left; a success publishes no line here — the toast lives above the dialog.
 */
@Composable
private fun CatalogSaveFeedback(
    outcome: CatalogSaveOutcome?,
    modifier: Modifier = Modifier,
) {
    val failure = outcome?.takeIf { !it.success } ?: return
    Text(
        failure.message,
        modifier = modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * The advanced-catalog dialog's bottom actions.
 *
 * One fixed row fits ordinary widths at ordinary font scales. On a narrow screen or a
 * large font scale it does not: 320dp at 字号 1.6 squeezed「保存模型来源」into three
 * lines and grew the bar over the scrollable body. There the primary save takes a
 * full-width row of its own and the secondary actions share the other — the body stays
 * its own scroll area either way.
 */
@Composable
private fun CatalogDialogActionBar(
    canSave: Boolean,
    isSaving: Boolean,
    canAddSource: Boolean,
    editingExisting: Boolean,
    onClose: () -> Unit,
    onAddSource: () -> Unit,
    onSave: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints {
        val stacked = maxWidth < CATALOG_ACTION_BAR_STACK_WIDTH ||
            fontScale >= CATALOG_ACTION_BAR_STACK_FONT_SCALE
        if (stacked) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("关闭")
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = onAddSource,
                        enabled = canAddSource,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("添加来源", maxLines = 1)
                    }
                }
                Button(
                    onClick = onSave,
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (editingExisting) "保存修改" else "保存模型来源",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = onAddSource,
                    enabled = canAddSource,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("添加来源", maxLines = 1)
                }
                Button(
                    onClick = onSave,
                    enabled = canSave,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (editingExisting) "保存修改" else "保存模型来源", maxLines = 1)
                }
            }
        }
    }
}

/** Below this effective width the dialog's bottom actions stack into two rows. */
private val CATALOG_ACTION_BAR_STACK_WIDTH = 342.dp

/** At this font scale or above, the dialog's bottom actions stack into two rows. */
private const val CATALOG_ACTION_BAR_STACK_FONT_SCALE = 1.3f

@Composable
private fun ModelCatalogEntryCard(
    entry: ModelCatalogEntry,
    suppliers: List<SupplierProfile>,
    results: List<UnifiedModelTestResult>,
    testingKeys: Set<String>,
    progressDone: Int,
    progressTotal: Int,
    editingEnabled: Boolean,
    testingEnabled: Boolean,
    isTesting: Boolean,
    isRefreshing: Boolean,
    missingSources: Set<String>,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onRunSource: (ModelSource) -> Unit,
    onRemoveSource: (ModelSource) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
) {
    val anySourceMissing = entry.sources.any { "${it.supplierId}\u0000${it.modelId}" in missingSources }
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 四个动作按钮：无涟漪（按下态的高光被读成「选中」）、盒子 44dp→40dp、间距 2dp、
            // 图标仍是 20dp——用户要求把这一组往右收紧但不要放大图标。整组因此从 4×44=176dp
            // 收到 4×40+3×2=166dp。40dp 低于 48dp 的无障碍建议触控尺寸，这是刻意的取舍：
            // 图标尺寸与读屏文案都不动。
            //
            // 禁用态的颜色要自己补：IconButton 会按自己的颜色表画 38% 的主色，
            // QuietIconButton 只是一个可点的盒子，不替内容做这个决定。
            val disabledIconTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    entry.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                val refreshEnabled = editingEnabled && !isRefreshing
                QuietIconButton(
                    onClick = onRefresh,
                    enabled = refreshEnabled,
                    modifier = Modifier.size(40.dp),
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Sync,
                            contentDescription = "拉取更新 ${entry.name} 的模型来源",
                            modifier = Modifier.size(20.dp),
                            tint = if (refreshEnabled) Color.Unspecified else disabledIconTint,
                        )
                    }
                }
                QuietIconButton(
                    onClick = onEdit,
                    enabled = editingEnabled,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "编辑模型来源",
                        modifier = Modifier.size(20.dp),
                        tint = if (editingEnabled) Color.Unspecified else disabledIconTint,
                    )
                }
                val testEnabled = testingEnabled || isTesting
                QuietIconButton(
                    onClick = if (isTesting) onCancel else onRun,
                    enabled = testEnabled,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        if (isTesting) Icons.Outlined.StopCircle else Icons.Outlined.PlayArrow,
                        contentDescription = if (isTesting) "取消测试 ${entry.name}" else "测试 ${entry.name}",
                        modifier = Modifier.size(20.dp),
                        tint = if (testEnabled) Color.Unspecified else disabledIconTint,
                    )
                }
                QuietIconButton(
                    onClick = onDelete,
                    enabled = editingEnabled,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "删除模型来源配置",
                        modifier = Modifier.size(20.dp),
                        tint = if (editingEnabled) Color.Unspecified else disabledIconTint,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${entry.sources.size} 个供应商来源",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
                if (anySourceMissing) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(
                            "有模型已不存在",
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                if (testingKeys.any { it.startsWith("${entry.id}\u0000") }) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "$progressDone/$progressTotal",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    LinearProgressIndicator(
                        progress = { progressDone.toFloat() / progressTotal.coerceAtLeast(1) },
                        modifier = Modifier.width(72.dp).height(4.dp),
                    )
                }
            }
            entry.sources.forEachIndexed { index, source ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                val supplier = suppliers.firstOrNull { it.id == source.supplierId }
                val supplierName = supplier?.name ?: "已删除供应商"
                val sourceMissing = "${source.supplierId}\u0000${source.modelId}" in missingSources
                val result = results.firstOrNull {
                    it.supplierId == source.supplierId && it.sourceModel == source.modelId
                }?.result
                val sourceKey = "${entry.id}\u0000${source.supplierId}\u0000${source.modelId}"
                val sourceTesting = sourceKey in testingKeys
                // A pull-only supplier or a delisted model must not offer a run button.
                val sourceTestable = testingEnabled && !sourceTesting &&
                    supplier?.isTestingDisabled != true && !sourceMissing
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "$supplierName · ${source.modelId}",
                        modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        sourceMissing -> Text(
                            "已不存在",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )

                        supplier?.isTestingDisabled == true -> Text(
                            "仅拉取",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        result != null -> {
                            if (sourceTesting) {
                                Text(
                                    "进行中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                            } else if (result.status == TestStatus.SUCCESS) {
                                Text(
                                    "可用",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF15803D),
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                            } else {
                                Text(
                                    result.httpStatus?.toString() ?: result.error?.kind?.label ?: "失败",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                            }
                        }

                        else -> Text(
                            "未测试",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = if (sourceMissing) {
                            { onRemoveSource(source) }
                        } else {
                            { onRunSource(source) }
                        },
                        enabled = editingEnabled && (sourceMissing || sourceTestable),
                    ) {
                        if (sourceMissing) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = "删除已不存在的来源 $supplierName · ${source.modelId}",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Icon(
                                Icons.Outlined.PlayArrow,
                                contentDescription = "测试 ${entry.name} 的 $supplierName 来源",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
