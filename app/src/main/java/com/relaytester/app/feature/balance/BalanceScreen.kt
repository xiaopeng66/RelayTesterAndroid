package com.relaytester.app.feature.balance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relaytester.app.core.model.BalanceHttpMethod
import com.relaytester.app.core.model.BalanceQueryMode
import com.relaytester.app.core.model.BalanceQueryTemplate
import com.relaytester.app.core.model.BalanceSnapshot
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.feature.tester.BalanceTemplateDraft
import com.relaytester.app.feature.tester.BalanceTemplateErrors
import com.relaytester.app.feature.tester.BalanceTemplateField
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.relaytester.app.feature.tester.BalanceCredentialsDraft
import com.relaytester.app.feature.tester.BalanceCredentialsErrors
import com.relaytester.app.feature.tester.BalanceUiState
import com.relaytester.app.feature.tester.TesterViewModel
import com.relaytester.app.ui.navigation.AppDestination
import com.relaytester.app.ui.components.DialogDismissScrim
import com.relaytester.app.ui.components.RelayAppHeader
import com.relaytester.app.ui.components.StatusToast
import com.relaytester.app.ui.components.rememberDialogWindowBox
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val BALANCE_SUPPLIER_CARD_HEIGHT = 112.dp
private val BALANCE_RING_SLOT_SIZE = 44.dp
private val BALANCE_RING_DIAMETER = 36.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BalanceScreen(
    viewModel: TesterViewModel,
    activeDestination: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
    onConfigurationBackup: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenUpdates: () -> Unit = {},
    hasAppUpdate: Boolean = false,
) {
    val state by viewModel.balanceUiState.collectAsStateWithLifecycle()
    // 切换供应商要重写草稿与凭据，ViewModel 在模型测试运行中会拒掉这个动作
    // （runningOrInitializing）。余额页只收 balanceUiState，看不见那条链在跑；卡片亮着
    // 而点击被吞，用户只会觉得「点了没反应」。把同一把锁的可见部分收在这里，拒绝时说
    // 出原因。卡片本身不禁用：双击刷新走的是另一条链，测试在跑时仍然可用。
    val testerState by viewModel.uiState.collectAsStateWithLifecycle()
    val siteSwitchLocked = testerState.isRunning ||
        testerState.isUnifiedTesting ||
        testerState.isInitializing ||
        testerState.isSecretsHydrating
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearBalanceMessage()
        }
    }

    val editor = state.editor
    BalanceHome(
        state = state,
        activeDestination = activeDestination,
        onDestinationSelected = onDestinationSelected,
        onConfigurationBackup = onConfigurationBackup,
        onOpenUpdates = onOpenUpdates,
        hasAppUpdate = hasAppUpdate,
        onQuery = viewModel::queryBalance,
        onQueryAll = viewModel::queryAllBalances,
        onSelectSupplier = { supplierId, onActivated ->
            if (siteSwitchLocked) {
                scope.launch {
                    snackbarHostState.showSnackbar("模型测试正在运行，暂时不能切换供应商")
                }
                onActivated(false)
            } else {
                viewModel.selectSupplier(supplierId, onActivated)
            }
        },
        onRefreshSupplierBalance = viewModel::refreshSupplierBalance,
        onAccessTokenChange = viewModel::updateBalanceAccessToken,
        onUserIdChange = viewModel::updateBalanceUserId,
        onSaveCredentials = viewModel::saveBalanceCredentials,
        onDismissCredentials = viewModel::discardBalanceCredentialsChanges,
        onTemplateSelected = viewModel::selectBalanceTemplate,
        onNewTemplate = viewModel::beginCreateBalanceTemplate,
        onEditTemplate = viewModel::beginEditBalanceTemplate,
        onDeleteTemplate = viewModel::deleteBalanceTemplate,
        isQuerying = state.isQuerying,
        isSecretsHydrating = state.isSecretsHydrating,
        // 编辑器开着时它自己托管同一个 state（消息要落在弹窗那一层窗口里）。两个宿主
        // 同时在组合里会让同一条消息被渲染两次——与更新弹窗同一条互斥规则。
        editorOpen = editor != null,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
    if (editor != null) {
        // 编辑器是盖在余额页上的一层弹窗，不是换掉这一页：换页会把 LazyColumn 连同滚动位置
        // 一起重建，于是「保存 / 取消」回来永远落在列表顶端，用户排到一半的位置没了。弹窗把
        // 余额页留在下面，回来时就是离开前的那一屏。
        //
        // Back 键仍然归它：弹窗自己吃掉返回手势，否则会直接结束 MainActivity，看起来像应用
        // 意外退出（这是它从整屏页改成弹窗时必须保住的那条行为）。
        BackHandler(onBack = viewModel::dismissBalanceTemplateEditor)
        BalanceTemplateEditor(
            draft = editor,
            errors = state.errors,
            isQuerying = state.isQuerying,
            onBack = viewModel::dismissBalanceTemplateEditor,
            onUpdate = viewModel::updateBalanceTemplate,
            onModeChange = viewModel::updateBalanceTemplateMode,
            onRestoreDefaultScript = viewModel::restoreDefaultBalanceScript,
            onMethodChange = viewModel::updateBalanceTemplateMethod,
            onDerivedTotalChange = viewModel::updateBalanceTemplateDerivedTotal,
            onSave = { viewModel.saveBalanceTemplate(queryAfterSave = false) },
            onSaveAndQuery = { viewModel.saveBalanceTemplate(queryAfterSave = true) },
            snackbarHostState = snackbarHostState,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BalanceHome(
    state: BalanceUiState,
    activeDestination: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
    onConfigurationBackup: () -> Unit,
    onOpenUpdates: () -> Unit,
    hasAppUpdate: Boolean,
    onQuery: () -> Unit,
    onQueryAll: () -> Unit,
    onSelectSupplier: (String, (Boolean) -> Unit) -> Unit,
    onRefreshSupplierBalance: (String) -> Unit,
    onAccessTokenChange: (String) -> Unit,
    onUserIdChange: (String) -> Unit,
    onSaveCredentials: (() -> Unit) -> Unit,
    onDismissCredentials: () -> Unit,
    onTemplateSelected: (String) -> Unit,
    onNewTemplate: () -> Unit,
    onEditTemplate: (String) -> Unit,
    onDeleteTemplate: (String) -> Unit,
    isQuerying: Boolean,
    isSecretsHydrating: Boolean,
    /** 模板编辑器开着时页面宿主让位：那条消息由编辑器窗口自己渲染。 */
    editorOpen: Boolean,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier,
) {
    // 存 id 而不是对象：旋转恢复时模板列表重新读入，旧实例对不上；与凭据弹窗的
    // credentialsSupplierId 同为 saveable，旋转时确认框不能只剩一个空壳。
    var templateToDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var credentialsSupplierId by rememberSaveable { mutableStateOf<String?>(null) }
    var credentialsSavedAt by rememberSaveable { mutableStateOf(0L) }
    val activeSupplier = state.suppliers.firstOrNull { it.id == state.activeSupplierId }
    val selectedTemplate = state.templates.firstOrNull { it.id == activeSupplier?.balanceTemplateId }
        ?: state.templates.firstOrNull()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            RelayAppHeader(
                subtitle = "余额查询",
                selectedDestination = activeDestination,
                onDestinationSelected = onDestinationSelected,
                onConfigurationBackup = onConfigurationBackup,
                onOpenUpdates = onOpenUpdates,
                hasUpdate = hasAppUpdate,
            )
        },
        snackbarHost = {
            if (!editorOpen) {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier,
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (state.isInitializing) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                BalanceContent(
                    suppliers = state.suppliers,
                    activeSupplier = activeSupplier,
                    selectedTemplate = selectedTemplate,
                    templates = state.templates,
                    snapshots = state.balanceSnapshots,
                    errors = state.balanceErrors,
                    isQuerying = isQuerying,
                    isSecretsHydrating = isSecretsHydrating,
                    queryingSupplierIds = state.queryingSupplierIds,
                    batchProgressDone = state.batchProgressDone,
                    batchProgressTotal = state.batchProgressTotal,
                    onQuery = onQuery,
                    onQueryAll = onQueryAll,
                    onSelectSupplier = onSelectSupplier,
                    onRefreshSupplierBalance = onRefreshSupplierBalance,
                    onEditCredentials = { supplierId ->
                        // 先切站点、切成功才记 id：与测试页同一处修正——切换被拒或写盘
                        // 失败时留下 id，之后这个站点变成活动站点时弹窗会自己蹦出来。
                        onSelectSupplier(supplierId) { activated ->
                            if (activated) credentialsSupplierId = supplierId
                        }
                    },
                    onDismissCredentials = onDismissCredentials,
                    onTemplateSelected = onTemplateSelected,
                    onNewTemplate = onNewTemplate,
                    onEditTemplate = onEditTemplate,
                    onDeleteTemplate = { templateToDeleteId = it.id },
                    contentPadding = innerPadding,
                )
            }
            // 与模型测试页同一条规则：保存成功的提示条画在页面这一层，同一个组件、
            // 同一个位置、同一个消失时机。以前它走页面底部的 snackbar，会被还开着的
            // 弹窗压在遮罩下面。
            //
            // 纵坐标要叠上 innerPadding：这一层 Box 从窗口顶算起，只按窗口顶 +4dp
            // 定位，整条提示会藏进不透明的顶栏背后——保存成功也看不见。
            if (credentialsSavedAt > 0L) {
                StatusToast(
                    message = "余额查询凭据已保存",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = innerPadding.calculateTopPadding() + 4.dp),
                    onToastShown = { credentialsSavedAt = 0L },
                )
            }
        }
    }

    templateToDeleteId?.let { templateId ->
        val template = state.templates.firstOrNull { it.id == templateId }
        if (template != null) {
            AlertDialog(
                onDismissRequest = { templateToDeleteId = null },
                title = { Text("删除余额模板？") },
                text = { Text("解除「${template.name}」与所有供应商的绑定，无法撤销。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            templateToDeleteId = null
                            onDeleteTemplate(template.id)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { templateToDeleteId = null },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("取消") }
                },
            )
        }
    }
    credentialsSupplierId?.let { supplierId ->
        if (activeSupplier?.id == supplierId && !isSecretsHydrating) {
            BalanceCredentialsDialog(
                supplierName = activeSupplier.name,
                credentials = state.credentials,
                errors = state.credentialErrors,
                enabled = !isQuerying,
                onDismiss = {
                    onDismissCredentials()
                    credentialsSupplierId = null
                },
                onAccessTokenChange = onAccessTokenChange,
                onUserIdChange = onUserIdChange,
                onSave = {
                    onSaveCredentials {
                        credentialsSavedAt = System.currentTimeMillis()
                        credentialsSupplierId = null
                    }
                },
            )
        }
    }
}

@Composable
private fun BalanceContent(
    suppliers: List<SupplierProfile>,
    activeSupplier: SupplierProfile?,
    selectedTemplate: BalanceQueryTemplate?,
    templates: List<BalanceQueryTemplate>,
    snapshots: Map<String, BalanceSnapshot>,
    errors: Map<String, String>,
    isQuerying: Boolean,
    isSecretsHydrating: Boolean,
    queryingSupplierIds: Set<String>,
    batchProgressDone: Int,
    batchProgressTotal: Int,
    onQuery: () -> Unit,
    onQueryAll: () -> Unit,
    onSelectSupplier: (String, (Boolean) -> Unit) -> Unit,
    onRefreshSupplierBalance: (String) -> Unit,
    onEditCredentials: (String) -> Unit,
    onDismissCredentials: () -> Unit,
    onTemplateSelected: (String) -> Unit,
    onNewTemplate: () -> Unit,
    onEditTemplate: (String) -> Unit,
    onDeleteTemplate: (BalanceQueryTemplate) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(
            key = "balance_overview",
            span = { GridItemSpan(maxLineSpan) },
            contentType = "balance_overview",
        ) {
            BalanceOverviewHeader(
                supplierCount = suppliers.size,
                isQuerying = isQuerying,
                enabled = !isSecretsHydrating,
                progressDone = batchProgressDone,
                progressTotal = batchProgressTotal,
                onQueryAll = onQueryAll,
            )
        }
        items(
            items = suppliers,
            key = { it.id },
            contentType = { "supplier_balance" },
        ) { supplier ->
            BalanceSupplierCard(
                supplier = supplier,
                snapshot = snapshots[supplier.id],
                errorMessage = errors[supplier.id],
                selected = supplier.id == activeSupplier?.id,
                isQuerying = supplier.id in queryingSupplierIds,
                enabled = !isSecretsHydrating,
                onClick = { onSelectSupplier(supplier.id) { } },
                onDoubleClick = { onRefreshSupplierBalance(supplier.id) },
                onEditCredentials = { onEditCredentials(supplier.id) },
            )
        }
        if (isSecretsHydrating) {
            item(
                key = "balance_secret_hydration",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "balance_secret_hydration",
            ) {
                Text(
                    "正在安全读取本机凭据，暂不能修改或查询…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(
            key = "balance_result",
            span = { GridItemSpan(maxLineSpan) },
            contentType = "balance_result",
        ) {
            BalanceResultCard(
                snapshot = activeSupplier?.let { snapshots[it.id] },
                errorMessage = activeSupplier?.let { errors[it.id] },
                isQuerying = activeSupplier?.id in queryingSupplierIds,
                canQuery = activeSupplier != null && selectedTemplate != null && !isSecretsHydrating,
                onQuery = onQuery,
            )
        }
        item(
            key = "template_selector",
            span = { GridItemSpan(maxLineSpan) },
            contentType = "template_selector",
        ) {
            BalanceTemplateCard(
                selectedTemplate = selectedTemplate,
                templates = templates,
                enabled = !isQuerying && !isSecretsHydrating,
                onTemplateSelected = onTemplateSelected,
                onNewTemplate = onNewTemplate,
                onEditTemplate = onEditTemplate,
                onDeleteTemplate = onDeleteTemplate,
            )
        }
    }
}

@Composable
private fun BalanceOverviewHeader(
    supplierCount: Int,
    isQuerying: Boolean,
    enabled: Boolean,
    progressDone: Int,
    progressTotal: Int,
    onQueryAll: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("全部供应商余额", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(12.dp))
                Text(
                    "$supplierCount 个供应商",
                    modifier = Modifier.widthIn(min = 56.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = onQueryAll,
                enabled = supplierCount > 0 && !isQuerying && enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isQuerying && progressTotal > 1) "正在查询 $progressDone / $progressTotal"
                    else if (isQuerying) "正在查询…"
                    else "批量查询余额",
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BalanceSupplierCard(
    modifier: Modifier = Modifier,
    supplier: SupplierProfile,
    snapshot: BalanceSnapshot?,
    errorMessage: String?,
    selected: Boolean,
    isQuerying: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onEditCredentials: () -> Unit,
) {
    val statusColor = when {
        errorMessage != null -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val cardShape = MaterialTheme.shapes.medium
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onDoubleClick = onDoubleClick,
            )
            .semantics {
                this.selected = selected
                // The card's own lines carry the name, the available balance and the status;
                // a contentDescription here replaced all of them with a generic sentence, so
                // the number a screen-reader user came for was the one thing not announced.
                // A double tap is not an accessibility action either, so the refresh is
                // published as one instead of relying on the gesture.
                customActions = listOf(
                    CustomAccessibilityAction("查询余额") {
                        onDoubleClick()
                        true
                    },
                )
            },
        colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(
            1.dp,
            if (selected || isQuerying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        shape = cardShape,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = BALANCE_SUPPLIER_CARD_HEIGHT),
        ) {
            Column(
                modifier = Modifier
                    // A floor rather than a fixed height, and no weighted spacers: a
                    // weight inside an unbounded column collapses, and at 2x fonts the
                    // four lines need more than 112dp — a fixed height clipped them.
                    .heightIn(min = BALANCE_SUPPLIER_CARD_HEIGHT)
                    .padding(start = 14.dp, top = 7.dp, end = 14.dp, bottom = 7.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 28.dp)
                        .padding(end = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = supplier.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "可用",
                        modifier = Modifier.heightIn(min = 16.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = snapshot?.formatValue(snapshot.availableRaw) ?: "—",
                        modifier = Modifier.heightIn(min = 21.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 34.dp),
                ) {
                    Text(
                        text = when {
                            isQuerying -> "正在查询…"
                            errorMessage != null -> errorMessage
                            snapshot?.planName != null -> snapshot.planName.orEmpty()
                            snapshot != null -> "已更新 ${formatDateTime(snapshot.checkedAt)}"
                            supplier.baseUrl.isBlank() -> "需要配置中转站地址"
                            else -> "尚未查询"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .size(BALANCE_RING_SLOT_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                if (isQuerying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    UsageRing(
                        snapshot = snapshot,
                        hasError = errorMessage != null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(48.dp)
                    .clickable(
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onEditCredentials,
                    )
                    .semantics { contentDescription = "编辑 ${supplier.name} 查询凭据" },
                contentAlignment = Alignment.BottomEnd,
            ) {
                Surface(
                    modifier = Modifier.padding(end = 2.dp, bottom = 2.dp).size(28.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceCardMetric(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UsageRing(
    snapshot: BalanceSnapshot?,
    hasError: Boolean,
    modifier: Modifier = Modifier,
) {
    val usedFraction = snapshot?.let { value ->
        val total = value.totalRaw
        val used = value.usedRaw
        if (total != null && total > 0 && used != null) {
            (used / total).toFloat().coerceIn(0f, 1f)
        } else {
            null
        }
    }
    val ringColor = when {
        hasError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    // The selected card itself uses secondaryContainer, which can be very
    // close to surfaceVariant. Use the semantic outline instead so an empty
    // ring remains visible in both themes.
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(BALANCE_RING_DIAMETER)) {
            val stroke = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
            )
            usedFraction?.let { fraction ->
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    style = stroke,
                )
            }
        }
        Text(
            text = usedFraction?.let { "${(it * 100).toInt()}%" } ?: "—",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BalanceCredentialsDialog(
    supplierName: String,
    credentials: BalanceCredentialsDraft,
    errors: BalanceCredentialsErrors,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onAccessTokenChange: (String) -> Unit,
    onUserIdChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("余额查询凭据")
                Text(
                    supplierName,
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
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                BalanceCredentialsForm(
                    supplierName = supplierName,
                    credentials = credentials,
                    errors = errors,
                    enabled = enabled,
                    onAccessTokenChange = onAccessTokenChange,
                    onUserIdChange = onUserIdChange,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("保存凭据") }
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
private fun BalanceCredentialsForm(
    supplierName: String,
    credentials: BalanceCredentialsDraft,
    errors: BalanceCredentialsErrors,
    enabled: Boolean,
    onAccessTokenChange: (String) -> Unit,
    onUserIdChange: (String) -> Unit,
) {
    var revealAccessToken by rememberSaveable(supplierName) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "可与模型测试 API Key 不同，加密保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = credentials.accessToken,
            onValueChange = onAccessTokenChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text("余额查询访问令牌（PAT）") },
            placeholder = { Text("用于 Authorization: Bearer …") },
            singleLine = true,
            isError = errors.accessToken != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (revealAccessToken) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(
                    onClick = { revealAccessToken = !revealAccessToken },
                    enabled = enabled,
                ) {
                    Icon(
                        imageVector = if (revealAccessToken) {
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
                        },
                        contentDescription = if (revealAccessToken) "隐藏访问令牌" else "显示访问令牌",
                    )
                }
            },
        )
        CredentialHelperText(
            text = errors.accessToken
                ?: "仅用于余额接口，受 Keystore 保护。",
            isError = errors.accessToken != null,
        )
        OutlinedTextField(
            value = credentials.userId,
            onValueChange = onUserIdChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text("用户 ID（可选）") },
            placeholder = { Text("需要指定账户时填写") },
            singleLine = true,
            isError = errors.userId != null,
        )
        CredentialHelperText(
            text = errors.userId ?: "可留空；仅在站点要求指定账户时填写。",
            isError = errors.userId != null,
        )
    }
}

@Composable
private fun CredentialHelperText(text: String, isError: Boolean) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun BalanceResultCard(
    snapshot: BalanceSnapshot?,
    errorMessage: String?,
    isQuerying: Boolean,
    canQuery: Boolean,
    onQuery: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("当前供应商查询详情", style = MaterialTheme.typography.titleMedium)
                }
                if (isQuerying) CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
            if (snapshot == null && errorMessage == null) {
                Text(
                    "确认地址、凭据与模板后即可读取余额。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (snapshot != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BalanceMetric(
                        label = "可用",
                        value = snapshot.formatValue(snapshot.availableRaw),
                        modifier = Modifier.weight(1f),
                        valueColor = MaterialTheme.colorScheme.tertiary,
                    )
                    BalanceMetric(
                        label = "已用",
                        value = snapshot.usedRaw?.let(snapshot::formatValue) ?: "—",
                        modifier = Modifier.weight(1f),
                        valueColor = MaterialTheme.colorScheme.primary,
                    )
                    BalanceMetric(
                        label = "总额",
                        value = snapshot.totalRaw?.let(snapshot::formatValue) ?: "—",
                        modifier = Modifier.weight(1f),
                        valueColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
                HorizontalDivider()
                BalanceQueryMetadata(snapshot = snapshot)
            }
            if (errorMessage != null) {
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onQuery,
                enabled = canQuery && !isQuerying,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(
                    imageVector = if (isQuerying) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isQuerying) "正在查询…" else "查询余额")
            }
        }
    }
}

@Composable
private fun BalanceMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label：$value"
        },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun BalanceQueryMetadata(snapshot: BalanceSnapshot) {
    snapshot.planName?.let { planName ->
        Text(
            text = "套餐：$planName · ${snapshot.latencyMs} ms",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } ?: Text(
        text = "请求延迟：${snapshot.latencyMs} ms",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = "模板：${snapshot.templateName} · 更新：${formatDateTime(snapshot.checkedAt)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun BalanceTemplateCard(
    selectedTemplate: BalanceQueryTemplate?,
    templates: List<BalanceQueryTemplate>,
    enabled: Boolean,
    onTemplateSelected: (String) -> Unit,
    onNewTemplate: () -> Unit,
    onEditTemplate: (String) -> Unit,
    onDeleteTemplate: (BalanceQueryTemplate) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("余额查询模板", style = MaterialTheme.typography.titleMedium)
            Text(
                "模板决定地址、认证头与字段映射；可按供应商选。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { menuExpanded = true },
                    enabled = enabled && templates.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            selectedTemplate?.name ?: if (templates.isEmpty()) "尚无模板" else "选择模板",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        selectedTemplate?.let { TemplateKindBadge(builtIn = it.builtIn) }
                        Icon(Icons.Outlined.ExpandMore, contentDescription = null)
                    }
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.heightIn(max = 280.dp),
                ) {
                    templates.forEach { template ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        template.name,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    TemplateKindBadge(builtIn = template.builtIn)
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                onTemplateSelected(template.id)
                            },
                        )
                    }
                }
            }
            selectedTemplate?.let { template ->
                Text(
                    template.description.ifBlank { "未填写模板说明" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${template.method.label} ${template.endpointTemplate}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(
                onClick = { selectedTemplate?.let { onEditTemplate(it.id) } },
                enabled = enabled && selectedTemplate != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(
                    imageVector = if (selectedTemplate?.builtIn == true) {
                        Icons.Outlined.Add
                    } else {
                        Icons.Outlined.Edit
                    },
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (selectedTemplate?.builtIn == true) "复制为自定义模板" else "编辑模板")
            }
            OutlinedButton(
                onClick = onNewTemplate,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("新建余额模板")
            }
            if (selectedTemplate != null && !selectedTemplate.builtIn) {
                TextButton(
                    onClick = { onDeleteTemplate(selectedTemplate) },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("删除此自定义模板", color = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider()
            TemplateSecuritySummary()
        }
    }
}

@Composable
private fun TemplateKindBadge(builtIn: Boolean) {
    val containerColor = if (builtIn) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (builtIn) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = if (builtIn) "内置" else "自定义",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun TemplateSecuritySummary() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("安全边界", style = MaterialTheme.typography.labelLarge)
        // The guard is same-origin, not TLS-only: a supplier configured as cleartext HTTP
        // must still be able to query its own balance, so claiming "HTTPS only" here
        // would describe a rule the code deliberately does not enforce.
        TemplateSafetyBullet("只访问当前站点同源地址（协议、域名、端口一致）。")
        TemplateSafetyBullet("凭据仅请求时注入，不写模板与日志。")
        TemplateSafetyBullet("只描述同站请求与 JSON 映射，不联网、不碰设备。")
    }
}

@Composable
private fun TemplateSafetyBullet(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .background(
                    color = MaterialTheme.colorScheme.tertiary,
                    shape = CircleShape,
                ),
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BalanceTemplateEditor(
    draft: BalanceTemplateDraft,
    errors: BalanceTemplateErrors,
    isQuerying: Boolean,
    onBack: () -> Unit,
    onUpdate: (BalanceTemplateField, String) -> Unit,
    onModeChange: (BalanceQueryMode) -> Unit,
    onRestoreDefaultScript: () -> Unit,
    onMethodChange: (BalanceHttpMethod) -> Unit,
    onDerivedTotalChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onSaveAndQuery: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier,
) {
    var showAdvancedMapping by rememberSaveable(draft.id) { mutableStateOf(false) }
    // 与其它弹窗同一套几何：卡片占屏宽 94%，内容盒按窗口定尺（要在弹窗外面算），遮罩自绘
    // 才能点卡片外关掉。编辑器以前是整屏页，没有这些。
    val windowBox = rememberDialogWindowBox()
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    Dialog(
        onDismissRequest = { if (!isQuerying) onBack() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = windowBox, contentAlignment = Alignment.Center) {
            DialogDismissScrim(onDismiss = { if (!isQuerying) onBack() })
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = onBack,
                                enabled = !isQuerying,
                                modifier = Modifier.size(56.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = "返回余额页",
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 16.dp),
                            ) {
                                Text("余额模板编辑", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "参数配置或受限查询脚本，均由本机安全发起请求",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                top = 12.dp,
                                end = 16.dp,
                                bottom = 12.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
            item(key = "editor_intro") {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (draft.queryMode == BalanceQueryMode.FORM) {
                            "固定接口用参数配置；可选字段见“高级响应映射”。"
                        } else {
                            "脚本为 request + extractor；请求由本机校验后发出。"
                        },
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "identity") {
                EditorSection("基本信息") {
                    TemplateTextField(
                        label = "模板名称 *",
                        value = draft.name,
                        onValueChange = { onUpdate(BalanceTemplateField.NAME, it) },
                        errorMessage = errors.name,
                        helperText = "例如：某站点用户余额",
                    )
                    TemplateTextField(
                        label = "说明",
                        value = draft.description,
                        onValueChange = { onUpdate(BalanceTemplateField.DESCRIPTION, it) },
                        helperText = "说明认证方式或适用站点，便于日后识别。",
                        singleLine = false,
                        maxLines = 3,
                    )
                }
            }
            item(key = "query_mode") {
                EditorSection("配置方式") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BalanceQueryMode.entries.forEach { mode ->
                            ModeChoiceChip(
                                selected = draft.queryMode == mode,
                                label = mode.label,
                                onClick = { onModeChange(mode) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Text(
                        "切换方式不会清除另一种配置，可随时切回继续编辑。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (draft.queryMode == BalanceQueryMode.FORM) {
            item(key = "request") {
                EditorSection("请求配置") {
                    Text("请求方法", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BalanceHttpMethod.entries.forEach { method ->
                            ModeChoiceChip(
                                selected = draft.method == method,
                                label = method.label,
                                onClick = { onMethodChange(method) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    TemplateTextField(
                        label = "请求地址 *",
                        value = draft.endpointTemplate,
                        onValueChange = { onUpdate(BalanceTemplateField.ENDPOINT, it) },
                        errorMessage = errors.endpoint,
                        helperText = "以 / 开头按站点根解析；可用 {{baseUrl}}。",
                    )
                    TemplateTextField(
                        label = "请求头（每行 名称: 值）",
                        value = draft.headersText,
                        onValueChange = { onUpdate(BalanceTemplateField.HEADERS, it) },
                        errorMessage = errors.headers,
                        helperText = "示例：Authorization: Bearer {{accessToken}}",
                        singleLine = false,
                        maxLines = 6,
                    )
                    if (draft.method == BalanceHttpMethod.POST) {
                        TemplateTextField(
                            label = "请求 Body（可选 JSON）",
                            value = draft.requestBodyTemplate,
                            onValueChange = { onUpdate(BalanceTemplateField.BODY, it) },
                            errorMessage = errors.body,
                        helperText = "可用 {{apiKey}}/{{accessToken}}/{{userId}}/{{nowEpochMs}}。",
                            singleLine = false,
                            maxLines = 8,
                        )
                    }
                }
            }
            item(key = "mapping") {
                EditorSection("响应映射") {
                    TemplateTextField(
                        label = "可用余额 JSON 路径 *",
                        value = draft.availablePath,
                        onValueChange = { onUpdate(BalanceTemplateField.AVAILABLE_PATH, it) },
                        errorMessage = errors.availablePath,
                        helperText = "示例：data.quota 或 data.items[0].balance",
                    )
                    TemplateTextField(
                        label = "显示单位",
                        value = draft.unitLabel,
                        onValueChange = { onUpdate(BalanceTemplateField.UNIT_LABEL, it) },
                        helperText = "例如 USD、CNY、积分或额度。",
                    )
                    TemplateTextField(
                        label = "换算除数 *",
                        value = draft.scaleDivisor,
                        onValueChange = { onUpdate(BalanceTemplateField.SCALE_DIVISOR, it) },
                        errorMessage = errors.divisor,
                        helperText = "显示值 = 原始值 ÷ 除数（new-api 默认 500000）。",
                        keyboardType = KeyboardType.Decimal,
                    )
                    OutlinedButton(
                        onClick = { showAdvancedMapping = !showAdvancedMapping },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(if (showAdvancedMapping) "收起高级响应映射" else "显示高级响应映射")
                    }
                    if (showAdvancedMapping) {
                        TemplateTextField(
                            label = "已用额度 JSON 路径（可选）",
                            value = draft.usedPath,
                            onValueChange = { onUpdate(BalanceTemplateField.USED_PATH, it) },
                            helperText = "例如 data.used_quota",
                        )
                        TemplateTextField(
                            label = "总额度 JSON 路径（可选）",
                            value = draft.totalPath,
                            onValueChange = { onUpdate(BalanceTemplateField.TOTAL_PATH, it) },
                            helperText = "例如 data.total_quota",
                        )
                        FilterChip(
                            selected = draft.deriveTotalFromAvailableAndUsed,
                            onClick = {
                                onDerivedTotalChange(!draft.deriveTotalFromAvailableAndUsed)
                            },
                            label = { Text("总额度 = 可用额度 + 已用额度") },
                        )
                        Text(
                            "new-api：仅有 quota/used_quota 时自动算总额。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TemplateTextField(
                            label = "币种 JSON 路径（可选）",
                            value = draft.currencyPath,
                            onValueChange = { onUpdate(BalanceTemplateField.CURRENCY_PATH, it) },
                            helperText = "有币种字段时填，如 data.currency。",
                        )
                        TemplateTextField(
                            label = "套餐名称 JSON 路径（可选）",
                            value = draft.planNamePath,
                            onValueChange = { onUpdate(BalanceTemplateField.PLAN_NAME_PATH, it) },
                            helperText = "例如 data.group；会展示在查询结果中。",
                        )
                        TemplateTextField(
                            label = "成功标记 JSON 路径（可选）",
                            value = draft.successPath,
                            onValueChange = { onUpdate(BalanceTemplateField.SUCCESS_PATH, it) },
                            errorMessage = errors.success,
                            helperText = "如 success；留空只按 HTTP 2xx。",
                        )
                        TemplateTextField(
                            label = "成功标记预期值（可选）",
                            value = draft.successExpectedValue,
                            onValueChange = { onUpdate(BalanceTemplateField.SUCCESS_EXPECTED_VALUE, it) },
                            errorMessage = errors.success,
                            helperText = "如 true；需与成功标记路径同时填写。",
                        )
                    }
                }
            }
            } else {
                item(key = "script") {
                    EditorSection("查询脚本") {
                        TemplateTextField(
                            label = "查询脚本 *",
                            value = draft.scriptCode,
                            onValueChange = { onUpdate(BalanceTemplateField.SCRIPT_CODE, it) },
                            errorMessage = errors.script,
                            helperText = "返回 { request, extractor }；字段 remaining/used/total/unit/planName。",
                            singleLine = false,
                            maxLines = 18,
                        )
                        OutlinedButton(
                            onClick = onRestoreDefaultScript,
                            enabled = !isQuerying,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text("填写 new-api 示例脚本")
                        }
                    }
                }
                item(key = "script_contract") {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("脚本返回约定", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "request: url、method、headers、body；extractor 可返回 isValid/invalidMessage。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "extractor 返回显示值；占位符发送前替换。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item(key = "template_placeholders") {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("可用占位符", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "地址、API Key、余额令牌、用户 ID、时间戳",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "{{baseUrl}} · {{apiKey}} · {{accessToken}} · {{userId}} · {{nowEpochMs}}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item(key = "save_actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSave,
                        enabled = !isQuerying,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("保存模板")
                    }
                    OutlinedButton(
                        onClick = onSaveAndQuery,
                        enabled = !isQuerying,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("保存并查询当前供应商")
                    }
                    TextButton(
                        onClick = onBack,
                        enabled = !isQuerying,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("取消") }
                }
            }
                        }
                    }
                }
            }
            // 校验失败的消息（「请先修正模板配置」等）写进的是页面级 SnackbarHostState，
            // 而那个宿主在活动窗口里、被这个弹窗盖着——编辑器不自己托管，消息就等于没有。
            // 与更新弹窗同一做法：弹窗托管同一个 state，消息落在这一层窗口里。
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * A two-or-three way choice: 配置方式 and 请求方法. The Material default draws the
 * selected chip as a filled *surface-variant* — a hair off the background, so on a
 * bright screen you cannot tell which one is on without hunting for the check mark.
 * Filling it with the primary colour and bolding the label makes the choice the
 * loudest thing in the section, which is what a selector is for.
 *
 * The unselected chip keeps a transparent container but gains an outline: two
 * transparent chips side by side read as labels, not as buttons.
 */
@Composable
private fun ModeChoiceChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            containerColor = Color.Transparent,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
        ),
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier.heightIn(min = 48.dp),
    )
}

@Composable
private fun EditorSection(title: String, content: @Composable () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun TemplateTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    helperText: String,
    errorMessage: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        isError = errorMessage != null,
        label = { Text(label) },
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = {
            Text(
                errorMessage ?: helperText,
                color = if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

private fun BalanceSnapshot.formatValue(raw: Double): String {
    val converted = raw / scaleDivisor
    val suffix = currency?.takeIf(String::isNotBlank) ?: unitLabel
    return "${formatDisplayNumber(converted)} $suffix"
}

private fun formatDisplayNumber(value: Double): String = String.format(Locale.ROOT, "%.4f", value)
    .trimEnd('0')
    .trimEnd('.')

private fun formatDateTime(millis: Long): String = DateFormat.getDateTimeInstance(
    DateFormat.SHORT,
    DateFormat.MEDIUM,
    Locale.getDefault(),
).format(Date(millis))
