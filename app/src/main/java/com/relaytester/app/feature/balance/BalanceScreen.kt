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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import com.relaytester.app.feature.tester.BalanceCredentialsDraft
import com.relaytester.app.feature.tester.BalanceCredentialsErrors
import com.relaytester.app.feature.tester.BalanceUiState
import com.relaytester.app.feature.tester.TesterViewModel
import com.relaytester.app.ui.navigation.AppDestination
import com.relaytester.app.ui.components.RelayAppHeader
import java.text.DateFormat
import java.util.Date
import java.util.Locale

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
) {
    val state by viewModel.balanceUiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearBalanceMessage()
        }
    }

    val editor = state.editor
    if (editor != null) {
        // The editor is an in-screen state, not a separate Activity destination. Intercepting
        // the system Back gesture keeps it consistent with the visible "取消" action instead
        // of finishing MainActivity and making the app appear to have exited unexpectedly.
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
    } else {
        BalanceHome(
            state = state,
            activeDestination = activeDestination,
            onDestinationSelected = onDestinationSelected,
            onConfigurationBackup = onConfigurationBackup,
            onQuery = viewModel::queryBalance,
            onQueryAll = viewModel::queryAllBalances,
            onSelectSupplier = viewModel::selectSupplier,
            onRefreshSupplierBalance = viewModel::refreshSupplierBalance,
            onAccessTokenChange = viewModel::updateBalanceAccessToken,
            onUserIdChange = viewModel::updateBalanceUserId,
            onSaveCredentials = viewModel::saveBalanceCredentials,
            onTemplateSelected = viewModel::selectBalanceTemplate,
            onNewTemplate = viewModel::beginCreateBalanceTemplate,
            onEditTemplate = viewModel::beginEditBalanceTemplate,
            onDeleteTemplate = viewModel::deleteBalanceTemplate,
            isQuerying = state.isQuerying,
            isSecretsHydrating = state.isSecretsHydrating,
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
    onQuery: () -> Unit,
    onQueryAll: () -> Unit,
    onSelectSupplier: (String) -> Unit,
    onRefreshSupplierBalance: (String) -> Unit,
    onAccessTokenChange: (String) -> Unit,
    onUserIdChange: (String) -> Unit,
    onSaveCredentials: () -> Unit,
    onTemplateSelected: (String) -> Unit,
    onNewTemplate: () -> Unit,
    onEditTemplate: (String) -> Unit,
    onDeleteTemplate: (String) -> Unit,
    isQuerying: Boolean,
    isSecretsHydrating: Boolean,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier,
) {
    var templateToDelete by remember { mutableStateOf<BalanceQueryTemplate?>(null) }
    var credentialsSupplierId by rememberSaveable { mutableStateOf<String?>(null) }
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
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
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
                    credentialsSupplierId = supplierId
                    onSelectSupplier(supplierId)
                },
                onTemplateSelected = onTemplateSelected,
                onNewTemplate = onNewTemplate,
                onEditTemplate = onEditTemplate,
                onDeleteTemplate = { templateToDelete = it },
                contentPadding = innerPadding,
            )
        }
    }

    templateToDelete?.let { template ->
        AlertDialog(
            onDismissRequest = { templateToDelete = null },
            title = { Text("删除余额模板？") },
            text = { Text("“${template.name}”将从所有站点取消绑定。此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        templateToDelete = null
                        onDeleteTemplate(template.id)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { templateToDelete = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("取消") }
            },
        )
    }
    credentialsSupplierId?.let { supplierId ->
        if (activeSupplier?.id == supplierId && !isSecretsHydrating) {
            BalanceCredentialsDialog(
                supplierName = activeSupplier.name,
                credentials = state.credentials,
                errors = state.credentialErrors,
                enabled = !isQuerying,
                onDismiss = { credentialsSupplierId = null },
                onAccessTokenChange = onAccessTokenChange,
                onUserIdChange = onUserIdChange,
                onSave = onSaveCredentials,
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
    onSelectSupplier: (String) -> Unit,
    onRefreshSupplierBalance: (String) -> Unit,
    onEditCredentials: (String) -> Unit,
    onTemplateSelected: (String) -> Unit,
    onNewTemplate: () -> Unit,
    onEditTemplate: (String) -> Unit,
    onDeleteTemplate: (BalanceQueryTemplate) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
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
            Box(modifier = Modifier.fillMaxWidth()) {
                BalanceSupplierCard(
                    modifier = Modifier
                        .widthIn(max = 336.dp)
                        .align(Alignment.Center),
                    supplier = supplier,
                    snapshot = snapshots[supplier.id],
                    errorMessage = errors[supplier.id],
                    selected = supplier.id == activeSupplier?.id,
                    isQuerying = supplier.id in queryingSupplierIds,
                    enabled = !isSecretsHydrating,
                    onClick = { onSelectSupplier(supplier.id) },
                    onDoubleClick = { onRefreshSupplierBalance(supplier.id) },
                    onEditCredentials = { onEditCredentials(supplier.id) },
                )
            }
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
                Text("全部站点余额", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(12.dp))
                Text(
                    "$supplierCount 个站点",
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
                contentDescription = "供应商 ${supplier.name}；双击查询余额"
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
                .fillMaxWidth()
                .heightIn(min = 118.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .padding(end = 50.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = supplier.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    BalanceCardMetric(
                        label = "可用",
                        value = snapshot?.formatValue(snapshot.availableRaw) ?: "—",
                        valueColor = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                    )
                    BalanceCardMetric(
                        label = "总额",
                        value = snapshot?.totalRaw?.let(snapshot::formatValue) ?: "—",
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = when {
                        isQuerying -> "正在查询…"
                        errorMessage != null -> errorMessage
                        snapshot?.planName != null -> snapshot.planName.orEmpty()
                        snapshot != null -> "已更新 ${formatDateTime(snapshot.checkedAt)}"
                        supplier.baseUrl.isBlank() -> "需要配置站点地址"
                        else -> "尚未查询"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
            "余额访问令牌可与模型测试 API Key 不同，并会加密保存在本机。",
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
                ?: "仅用于余额接口，保存后受 Android Keystore 保护。",
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
                    Text("当前站点查询详情", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (snapshot == null) "数据来自站点 API，不是本地估算" else "最近一次 API 查询结果",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (isQuerying) CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
            if (snapshot == null && errorMessage == null) {
                Text(
                    "确认站点地址、查询凭据与模板后，可读取当前账户余额。",
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
                "模板决定请求地址、认证请求头和响应字段映射；每个供应商可单独选择。",
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
        TemplateSafetyBullet("HTTPS：只能访问当前站点的 HTTPS 地址。")
        TemplateSafetyBullet("凭据：API Key / PAT 仅在请求时注入，不写入模板或日志。")
        TemplateSafetyBullet("脚本：只能描述同站请求与 JSON 映射，不能直接联网或访问设备能力。")
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
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("余额模板编辑", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "参数配置或受限查询脚本，均由本机安全发起请求",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isQuerying) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回余额页")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = innerPadding.calculateTopPadding() + 12.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "editor_intro") {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (draft.queryMode == BalanceQueryMode.FORM) {
                            "参数配置适合固定接口与 JSON 路径；复杂的可选字段可在“高级响应映射”中展开。"
                        } else {
                            "查询脚本采用 cc-switch 风格的 request + extractor 结构；网络请求仍由本机校验后执行。"
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
                            FilterChip(
                                selected = draft.queryMode == mode,
                                onClick = { onModeChange(mode) },
                                label = { Text(mode.label) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
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
                            FilterChip(
                                selected = draft.method == method,
                                onClick = { onMethodChange(method) },
                                label = { Text(method.label) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            )
                        }
                    }
                    TemplateTextField(
                        label = "请求地址 *",
                        value = draft.endpointTemplate,
                        onValueChange = { onUpdate(BalanceTemplateField.ENDPOINT, it) },
                        errorMessage = errors.endpoint,
                        helperText = "以 / 开头时从站点根路径解析；也可用 {{baseUrl}}。",
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
                        helperText = "可使用 {{apiKey}}、{{accessToken}}、{{userId}} 或 {{nowEpochMs}}。",
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
                        helperText = "显示值 = API 原始值 ÷ 此除数；new-api 默认是 500000。",
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
                            "适用于 new-api：响应只包含 quota 与 used_quota 时自动计算总额度。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TemplateTextField(
                            label = "币种 JSON 路径（可选）",
                            value = draft.currencyPath,
                            onValueChange = { onUpdate(BalanceTemplateField.CURRENCY_PATH, it) },
                            helperText = "接口有返回币种字符串时填写，例如 data.currency。",
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
                            helperText = "例如 success；留空则仅按 HTTP 2xx 判断。",
                        )
                        TemplateTextField(
                            label = "成功标记预期值（可选）",
                            value = draft.successExpectedValue,
                            onValueChange = { onUpdate(BalanceTemplateField.SUCCESS_EXPECTED_VALUE, it) },
                            errorMessage = errors.success,
                            helperText = "例如 true。填写此项时必须同时填写成功标记路径。",
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
                            helperText = "返回 { request, extractor }；extractor 返回 remaining、used、total、unit、planName。",
                            singleLine = false,
                            maxLines = 18,
                        )
                        OutlinedButton(
                            onClick = onRestoreDefaultScript,
                            enabled = !isQuerying,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text("恢复 new-api 示例脚本")
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
                                "request: url、method（GET/POST）、headers、body（可选）；extractor(response) 可返回 isValid: false 与 invalidMessage。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "余额数值由 extractor 直接返回显示值；可用占位符仅在请求发送前替换。",
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
                            "地址 · 模型 API Key · 余额令牌 · 用户 ID · 毫秒时间戳",
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
                        Text("保存并查询当前站点")
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
