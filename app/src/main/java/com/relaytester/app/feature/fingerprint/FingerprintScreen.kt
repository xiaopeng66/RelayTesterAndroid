package com.relaytester.app.feature.fingerprint

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relaytester.app.core.fingerprint.BankSource
import com.relaytester.app.core.fingerprint.FingerprintCandidate
import com.relaytester.app.core.fingerprint.minimumNumbersFor
import com.relaytester.app.ui.components.RelayAppHeader
import com.relaytester.app.ui.navigation.AppDestination

@Composable
fun FingerprintScreen(
    viewModel: FingerprintViewModel,
    modifier: Modifier = Modifier,
    activeDestination: AppDestination = AppDestination.FINGERPRINT,
    onDestinationSelected: (AppDestination) -> Unit = {},
    onConfigurationBackup: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // The view model lives with the activity, so the catalogue it read at startup can
    // be older than what the "模型测试" tab has pulled since; every entry to this panel
    // re-reads it.
    LaunchedEffect(activeDestination) {
        if (activeDestination == AppDestination.FINGERPRINT) {
            viewModel.refreshCatalogue()
            // A package published since the last visit should be on the card already,
            // rather than waiting for the user to think of pressing "检查更新".
            viewModel.refreshBankOnEntry()
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            RelayAppHeader(
                subtitle = "模型指纹检测",
                selectedDestination = activeDestination,
                onDestinationSelected = onDestinationSelected,
                onConfigurationBackup = onConfigurationBackup,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.loadError != null -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    state.loadError.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            else -> FingerprintContent(
                state = state,
                onModeChange = viewModel::selectMode,
                onSupplierChange = viewModel::selectSupplier,
                onFilterChange = viewModel::updateModelFilter,
                onToggleModel = viewModel::toggleModelSelection,
                onClearSelection = viewModel::clearModelSelection,
                onParallelChange = viewModel::updateParallel,
                onManualAnswerChange = viewModel::updateManualAnswer,
                onRun = viewModel::runApiDetection,
                onAnalyze = viewModel::runManualAnalysis,
                onCancel = viewModel::cancelRun,
                onRegenerate = viewModel::regenerateChallenges,
                onRetryChallenge = viewModel::retryChallenge,
                onCheckBankUpdate = viewModel::checkBankUpdate,
                onInstallBankUpdate = viewModel::installBankUpdate,
                onRemovePackage = viewModel::removeInstalledPackage,
                contentPadding = innerPadding,
            )
        }
    }
}

@Composable
private fun FingerprintContent(
    state: FingerprintUiState,
    onModeChange: (DetectionMode) -> Unit,
    onSupplierChange: (String) -> Unit,
    onFilterChange: (String) -> Unit,
    onToggleModel: (String) -> Unit,
    onClearSelection: () -> Unit,
    onParallelChange: (Boolean) -> Unit,
    onManualAnswerChange: (Int, String) -> Unit,
    onRun: () -> Unit,
    onAnalyze: () -> Unit,
    onCancel: () -> Unit,
    onRegenerate: () -> Unit,
    onRetryChallenge: (Int) -> Unit,
    onCheckBankUpdate: () -> Unit,
    onInstallBankUpdate: () -> Unit,
    onRemovePackage: () -> Unit,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { IntentNote() }

        item {
            OutlinedCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SelectionChip(
                            selected = state.mode == DetectionMode.API,
                            label = "API 直连",
                            onClick = { onModeChange(DetectionMode.API) },
                            enabled = !state.isRunning,
                        )
                        SelectionChip(
                            selected = state.mode == DetectionMode.MANUAL,
                            label = "手动粘贴",
                            onClick = { onModeChange(DetectionMode.MANUAL) },
                            enabled = !state.isRunning,
                        )
                    }

                    if (state.mode == DetectionMode.API) {
                        SupplierPicker(
                            state = state,
                            onSupplierChange = onSupplierChange,
                        )
                        ModelPicker(
                            state = state,
                            onFilterChange = onFilterChange,
                            onToggleModel = onToggleModel,
                            onClearSelection = onClearSelection,
                            enabled = !state.isRunning,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // The row is the control: a bare Switch announces only
                                // "switch, off" with no idea what it toggles, because its
                                // label lives in a sibling node. Making the row the
                                // toggleable merges the title, the explanation and the
                                // switch state into one accessible control.
                                .toggleable(
                                    value = state.useParallel,
                                    enabled = !state.isRunning,
                                    role = Role.Switch,
                                    onValueChange = onParallelChange,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("并行发送三题", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "只影响同一个模型的三道题；多个模型始终按顺序逐个检测",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.useParallel,
                                // Null: the row above handles the gesture, so the switch
                                // is a state indicator rather than a second target.
                                onCheckedChange = null,
                                enabled = !state.isRunning,
                            )
                        }
                    } else {
                        Text(
                            "把三道题目分别发给目标模型，再把回答粘贴回对应输入框。适合只有聊天界面、没有 API Key 的情况。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            ChallengeList(
                state = state,
                onManualAnswerChange = onManualAnswerChange,
                onRetryChallenge = onRetryChallenge,
                onRegenerate = onRegenerate,
            )
        }

        item {
            val canRun = !state.isRunning && !state.isLoading
            if (state.isRunning) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("取消") }
            } else {
                Button(
                    onClick = if (state.mode == DetectionMode.API) onRun else onAnalyze,
                    enabled = canRun && (state.mode == DetectionMode.MANUAL || state.selectedModels.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        when {
                            state.mode == DetectionMode.MANUAL -> "分析粘贴的回答"
                            state.selectedModels.size > 1 -> "依次检测 ${state.selectedModels.size} 个模型"
                            else -> "开始检测"
                        },
                    )
                }
            }
        }

        if (state.batchResults.isNotEmpty()) {
            item { BatchResultList(state.batchResults) }
        }

        state.analysis?.let { analysis ->
            item { DetectionResultCard(analysis) }
            item { CandidateList(analysis.candidates) }
        }

        item {
            ReferenceBankCard(
                state = state,
                onCheckBankUpdate = onCheckBankUpdate,
                onInstallBankUpdate = onInstallBankUpdate,
                onRemovePackage = onRemovePackage,
            )
        }
    }
}

/**
 * Which detection package the panel is scoring with, and how to move it forward.
 *
 * The package is downloaded rather than shipped in the APK, so this card is also the
 * panel's provisioner: with nothing installed it explains what is missing and offers
 * the download. After that the check is a button rather than a background poll — the
 * one exception is noted on the card itself, because a panel that quietly talked to the
 * network would make its "检测不联网" promise a lie.
 */
@Composable
private fun ReferenceBankCard(
    state: FingerprintUiState,
    onCheckBankUpdate: () -> Unit,
    onInstallBankUpdate: () -> Unit,
    onRemovePackage: () -> Unit,
) {
    val busy = state.isCheckingBankUpdate || state.isInstallingBank || state.isRunning
    OutlinedCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "检测包",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    bankSourceLabel(state.bankSource),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (state.bankSource) {
                        BankSource.INSTALLED -> MaterialTheme.colorScheme.primary
                        BankSource.INSTALLED_UNREADABLE -> MaterialTheme.colorScheme.error
                        BankSource.NOT_PROVISIONED -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Text(
                bankStateLine(
                    source = state.bankSource,
                    builtAt = state.referenceBuiltAt,
                    modelCount = state.modelCount,
                    sizeBytes = state.bankSizeBytes,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.availableBankUpdate?.let { update ->
                Text(
                    bankUpdateOffer(update.builtAt, update.modelCount, update.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // The published package demands an app newer than this one, so no install
            // button is offered; the panel still has to say why, on every entry.
            state.bankRequiringNewerApp?.let { manifest ->
                Text(
                    "上游发布了新的检测包，但需要先更新 App" +
                        "（最低版本代码 ${manifest.minAppVersionCode}），更新应用后即可安装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.bankSource == BankSource.NOT_PROVISIONED) {
                Text(
                    "检测需要先下载一次检测包；装好之后检测本身完全离线，不联网。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.bankProblem?.let { problem ->
                Text(
                    problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onCheckBankUpdate,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    if (state.isCheckingBankUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("检查更新", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.availableBankUpdate != null) {
                    Button(
                        onClick = onInstallBankUpdate,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        if (state.isInstallingBank) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (state.bankSource == BankSource.NOT_PROVISIONED) {
                                "下载检测包"
                            } else {
                                "更新检测包"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (state.bankSource != BankSource.NOT_PROVISIONED) {
                TextButton(
                    onClick = onRemovePackage,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                ) { Text("删除已安装的检测包") }
            }

            SupportedModelsSection(models = state.bankModels)

            Text(
                "检测本身不联网：每次进入本面板会取一次更新清单（只有版本信息，不上传任何数据），" +
                    "点「检查更新」也会取一次；点「下载/更新检测包」才会下载文件。" +
                    "参考数据由 lm-detector (MIT) 提供。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Which models the package in use can identify.
 *
 * The roster is 53 entries in the published package, so it stays folded behind a button
 * and scrolls inside a bounded box: expanded by default it would push the rest of the
 * panel off screen. Nothing here is shown without a readable package, which is also why
 * the empty case renders nothing at all rather than an empty list.
 *
 * The fold is [rememberSaveable] because this sits in a LazyColumn item: a plain
 * `remember` is dropped when the card scrolls out of composition, so reading a few rows,
 * scrolling up and coming back would silently collapse the list again (found on device).
 */
@Composable
private fun SupportedModelsSection(models: List<BankModelInfo>) {
    if (models.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        ) {
            Text(
                if (expanded) "收起支持的模型" else "查看支持的模型（${models.size} 个）",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                models.forEach { model ->
                    Text(
                        "${model.displayName} · ${model.familyName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The panel's two pickers, styled the same way on purpose: a filled blue chip is the
 * selected one, so "which mode" and "which supplier" read at a glance.
 */
@Composable
private fun SelectionChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        label = {
            Text(
                label,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun IntentNote() {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "让模型凭第一反应写出约 300 个 1–355 的整数。每个模型写出的“随机数”分布相对稳定，" +
                    "把结果与检测包比对就能看出后端最接近哪个模型。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Supplier chips on one line that scrolls sideways.
 *
 * A plain Row clipped every chip past the screen edge, so with more than a handful of
 * configured suppliers the later ones were simply unreachable.
 */
@Composable
private fun SupplierPicker(
    state: FingerprintUiState,
    onSupplierChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("供应商", style = MaterialTheme.typography.labelLarge)
        if (state.suppliers.isEmpty()) {
            Text(
                "还没有配置供应商，请先到“模型测试”里添加。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.suppliers.forEach { supplier ->
                    SelectionChip(
                        selected = state.selectedSupplierId == supplier.id,
                        label = supplier.name,
                        onClick = { onSupplierChange(supplier.id) },
                        enabled = !state.isRunning,
                    )
                }
            }
        }
    }
}

/**
 * Model filter, tick list and selection summary.
 *
 * The text field is a search key over the supplier's catalogue, not a model name;
 * ticking rows chooses what to detect, in tick order. When the keyword matches nothing
 * — including a supplier whose models were never pulled — the typed name is offered as
 * a model of its own, so such a model stays reachable.
 */
@Composable
private fun ModelPicker(
    state: FingerprintUiState,
    onFilterChange: (String) -> Unit,
    onToggleModel: (String) -> Unit,
    onClearSelection: () -> Unit,
    enabled: Boolean,
) {
    val matches = filterModels(state.models, state.modelFilter)
    val unmatched = unmatchedKeyword(state.models, state.modelFilter)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("模型", style = MaterialTheme.typography.labelLarge)

        OutlinedTextField(
            value = state.modelFilter,
            onValueChange = onFilterChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = { Text("筛选模型") },
            placeholder = { Text("输入关键词，筛选该供应商的模型") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (state.modelFilter.isNotEmpty()) {
                    IconButton(onClick = { onFilterChange("") }, enabled = enabled) {
                        Icon(Icons.Outlined.Close, contentDescription = "清空筛选")
                    }
                }
            },
        )

        if (state.models.isEmpty() && unmatched == null) {
            Text(
                "该供应商还没有已拉取的模型。到“模型测试”里拉取后即可勾选；也可以直接把模型名" +
                    "输入上面的筛选框，列表里会出现一行供你勾选。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Five rows at a time; the rest scroll inside this box so the run
                    // button and the challenge cards are never pushed off the page.
                    .heightIn(max = MODEL_ROW_HEIGHT * VISIBLE_MODEL_ROWS)
                    .verticalScroll(rememberScrollState()),
            ) {
                unmatched?.let { keyword ->
                    ModelRow(
                        label = keyword,
                        supporting = "该供应商的列表里没有这个名字，将直接用它检测",
                        order = state.selectedModels.indexOf(keyword).takeIf { it >= 0 }?.plus(1),
                        selected = keyword in state.selectedModels,
                        enabled = enabled,
                        onToggle = { onToggleModel(keyword) },
                    )
                }
                matches.forEach { model ->
                    ModelRow(
                        label = model,
                        supporting = null,
                        order = state.selectedModels.indexOf(model).takeIf { it >= 0 }?.plus(1),
                        selected = model in state.selectedModels,
                        enabled = enabled,
                        onToggle = { onToggleModel(model) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.selectedModels.isEmpty()) {
                    "勾选要检测的模型；勾一个就测一个，勾多个会按勾选顺序逐个检测。"
                } else {
                    "将按顺序检测：" + state.selectedModels.joinToString(" → ")
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.selectedModels.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            if (state.selectedModels.isNotEmpty()) {
                TextButton(
                    onClick = onClearSelection,
                    enabled = enabled,
                ) { Text("清空") }
            }
        }
    }
}

@Composable
private fun ModelRow(
    label: String,
    supporting: String?,
    order: Int?,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A minimum, not a fixed height: the fallback row carries a second line of
            // hint text, which a fixed 48.dp clips once the user's font scale grows.
            .heightIn(min = MODEL_ROW_HEIGHT)
            .clickable(enabled = enabled) { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            enabled = enabled,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            supporting?.let { hint ->
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The tick order is the detection order, so it is shown rather than implied.
        order?.let { index ->
            Text(
                "$index",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** The batch output: one row per tested model, in the order they were run. */
@Composable
private fun BatchResultList(results: List<ModelFingerprintResult>) {
    val settled = settledModelCount(results)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("检测结果", style = MaterialTheme.typography.titleMedium)
        Text(
            "已出结果 $settled/${results.size} 个模型",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        results.forEachIndexed { index, row ->
            OutlinedCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            row.model,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when (row.status) {
                                ModelDetectionStatus.PENDING -> "等待检测"
                                ModelDetectionStatus.RUNNING -> "正在检测…"
                                ModelDetectionStatus.DONE ->
                                    listOfNotNull(row.candidateName, row.familyName).joinToString(" · ") +
                                        "（有效回答 ${row.usableAnswers}/${row.submittedAnswers}）"
                                ModelDetectionStatus.FAILED -> row.error ?: "检测失败"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when (row.status) {
                                ModelDetectionStatus.DONE -> Color(0xFF15803D)
                                ModelDetectionStatus.FAILED -> MaterialTheme.colorScheme.error
                                ModelDetectionStatus.RUNNING -> Color(0xFF1D4ED8)
                                ModelDetectionStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                        )
                    }
                    if (row.status == ModelDetectionStatus.DONE) {
                        row.probability?.let { probability ->
                            Text(
                                "${(probability * 100).toInt()}%",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Row height used to cap the model list at [VISIBLE_MODEL_ROWS] rows. */
private val MODEL_ROW_HEIGHT = 48.dp

@Composable
private fun ChallengeList(
    state: FingerprintUiState,
    onManualAnswerChange: (Int, String) -> Unit,
    onRetryChallenge: (Int) -> Unit,
    onRegenerate: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val manual = state.mode == DetectionMode.MANUAL
    // In manual mode nothing sets RECEIVED until analysis runs, so count pastes that
    // already clear the threshold; otherwise the header reads 0/3 while three usable
    // answers sit in the fields.
    val received = if (manual) {
        state.progress.count {
            it.parsedNumbers >= minimumNumbersFor(it.challenge.expectedCount, state.minimumValidNumbers)
        }
    } else {
        state.progress.count { it.state == ChallengeState.RECEIVED }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("三道题目", style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        if (state.activeModel != null) {
                            val position = state.selectedModels.indexOf(state.activeModel) + 1
                            append("正在检测 ")
                            if (position > 0) append("$position/${state.selectedModels.size} · ")
                            append(state.activeModel)
                            append(" · ")
                        }
                        append("已收到 $received/${state.progress.size} 条有效回答")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRegenerate, enabled = !state.isRunning) {
                Icon(Icons.Outlined.Refresh, contentDescription = "重新生成题目")
            }
        }
        if (state.isRunning) {
            LinearProgressIndicator(
                progress = { state.progress.count { it.state != ChallengeState.PENDING }.toFloat() / maxOf(state.progress.size, 1) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.progress.forEachIndexed { index, entry ->
            ChallengeCard(
                index = index,
                entry = entry,
                manual = state.mode == DetectionMode.MANUAL,
                enabled = !state.isRunning,
                minimumNumbers = minimumNumbersFor(entry.challenge.expectedCount, state.minimumValidNumbers),
                onCopy = { clipboard.setText(AnnotatedString(entry.challenge.prompt)) },
                onAnswerChange = { onManualAnswerChange(index, it) },
                onRetry = { onRetryChallenge(index) },
            )
        }
    }
}

@Composable
private fun ChallengeCard(
    index: Int,
    entry: ChallengeProgress,
    manual: Boolean,
    enabled: Boolean,
    minimumNumbers: Int,
    onCopy: () -> Unit,
    onAnswerChange: (String) -> Unit,
    onRetry: () -> Unit,
) {
    OutlinedCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(stateColor(entry.state), androidx.compose.foundation.shape.CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "题目 ${index + 1} · 需要 ${entry.challenge.expectedCount} 个整数",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onCopy, enabled = enabled) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "复制题目 ${index + 1} 的提示词",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Text(
                stateLabel(entry),
                style = MaterialTheme.typography.bodySmall,
                color = stateColor(entry.state),
            )

            entry.error?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (!manual) {
                    OutlinedButton(onClick = onRetry, enabled = enabled) { Text("重试本题") }
                }
            }

            if (manual) {
                Text(
                    "提示词全文",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    entry.challenge.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = entry.answer,
                    onValueChange = onAnswerChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    enabled = enabled,
                    label = { Text("粘贴模型回答") },
                    placeholder = { Text("粘贴完整数字序列") },
                    supportingText = {
                        if (entry.answer.isBlank()) {
                            Text("需要至少 $minimumNumbers 个整数")
                        } else if (entry.parsedNumbers >= minimumNumbers) {
                            Text(
                                "已识别 ${entry.parsedNumbers} 个整数，可用于检测",
                                color = Color(0xFF15803D),
                            )
                        } else {
                            Text(
                                "只识别到 ${entry.parsedNumbers} 个整数，还差 ${minimumNumbers - entry.parsedNumbers} 个",
                                color = Color(0xFFB91C1C),
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * Short status word for a challenge card.
 *
 * A rejection must not repeat [ChallengeProgress.error] here: the reason already
 * renders on its own line directly below, and echoing it in the status line prints
 * the same sentence twice on every failed card.
 */
internal fun stateLabel(entry: ChallengeProgress): String = when (entry.state) {
    ChallengeState.PENDING -> "等待发送"
    ChallengeState.REQUESTING -> "正在接收…"
    ChallengeState.RECEIVED -> "已接收 ${entry.parsedNumbers} 个有效数字"
    ChallengeState.REJECTED -> "未采用"
}

private fun stateColor(state: ChallengeState): Color = when (state) {
    ChallengeState.RECEIVED -> Color(0xFF15803D)
    ChallengeState.REJECTED -> Color(0xFFB91C1C)
    ChallengeState.REQUESTING -> Color(0xFF1D4ED8)
    ChallengeState.PENDING -> Color(0xFF6B7280)
}

@Composable
private fun DetectionResultCard(analysis: com.relaytester.app.core.fingerprint.FingerprintAnalysis) {
    val top = analysis.prediction
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("最接近的候选", style = MaterialTheme.typography.labelMedium)
            Text(
                top?.displayName ?: "暂不可评分",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("有效回答", style = MaterialTheme.typography.labelSmall)
                    Text("${analysis.usableAnswers}/${analysis.submittedAnswers}", style = MaterialTheme.typography.titleSmall)
                }
                Column {
                    Text("家族", style = MaterialTheme.typography.labelSmall)
                    Text(analysis.familyName, style = MaterialTheme.typography.titleSmall)
                }
                top?.probability?.let { probability ->
                    Column {
                        Text("置信", style = MaterialTheme.typography.labelSmall)
                        Text("${(probability * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            if (analysis.candidates.firstOrNull()?.probability == null) {
                Text(
                    if (analysis.usableAnswers < 3) {
                        "只有 ${analysis.usableAnswers}/3 条有效回答：先给出候选排序，" +
                            "补齐三条有效回答后才有检验分数与置信度。"
                    } else {
                        "这份检测包没有可用的置信度标定，本结果只按候选排序给出。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (analysis.verifierAgrees == false) {
                Text(
                    "排名与核验给出的第一候选不一致：候选顺序仍由排名分数决定，请谨慎对待。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                "这是检测包内的封闭集合排序：不在库中的模型同样会得到一个最接近的候选，" +
                    "分数不是身份证明。同家族相邻版本的区分尤其需要谨慎。置信度是检测包标定的，" +
                    "不是「这就是该模型」的概率。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CandidateList(candidates: List<FingerprintCandidate>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("候选排序", style = MaterialTheme.typography.titleMedium)
        candidates.take(RENDERED_CANDIDATES).forEachIndexed { index, candidate ->
            OutlinedCard {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            candidate.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            candidate.familyName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    candidate.probability?.let { probability ->
                        Text(
                            "${(probability * 100).toInt()}%",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }
        }
    }
}

private const val RENDERED_CANDIDATES = 8
