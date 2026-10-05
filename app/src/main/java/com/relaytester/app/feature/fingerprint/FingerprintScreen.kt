package com.relaytester.app.feature.fingerprint

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relaytester.app.core.fingerprint.BankSource
import com.relaytester.app.core.fingerprint.DetectionHistoryEntry
import com.relaytester.app.core.fingerprint.FingerprintCandidate
import com.relaytester.app.core.fingerprint.FingerprintHistoryStore
import com.relaytester.app.core.fingerprint.minimumNumbersFor
import com.relaytester.app.ui.components.QuietIconButton
import com.relaytester.app.ui.components.RelayAppHeader
import com.relaytester.app.ui.components.UpdateDownloadRow
import com.relaytester.app.ui.components.UpdateStatusRow
import com.relaytester.app.ui.components.copyToClipboard
import com.relaytester.app.ui.components.openUriSafely
import com.relaytester.app.ui.components.updateCheckLine
import com.relaytester.app.ui.navigation.AppDestination

/**
 * Fingerprint detection panel.
 *
 * Annotated at the file's entry point for [androidx.compose.material3.ExperimentalMaterial3Api]:
 * the parallel switch silences its press ripple through `LocalRippleConfiguration`,
 * which is still an experimental M3 surface in this library version.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FingerprintScreen(
    viewModel: FingerprintViewModel,
    modifier: Modifier = Modifier,
    activeDestination: AppDestination = AppDestination.FINGERPRINT,
    onDestinationSelected: (AppDestination) -> Unit = {},
    onConfigurationBackup: () -> Unit = {},
    onOpenUpdates: () -> Unit = {},
    hasAppUpdate: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // The view model lives with the activity, so the catalogue it read at startup can
    // be older than what the "模型测试" tab has pulled since; every entry to this panel
    // re-reads it.
    LaunchedEffect(activeDestination) {
        if (activeDestination == AppDestination.FINGERPRINT) {
            viewModel.refreshCatalogue()
            // 检测包的自动检查不在这里：它挂在「打开 App 一次 + 常驻 6 小时定时器」上
            // （见 FingerprintViewModel.checkBankOnLaunch）。挂在进面板上时，切三大界面
            // 来回一次就重查一次，卡片上的「上次检查」跟着跳。
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
                onOpenUpdates = onOpenUpdates,
                hasUpdate = hasAppUpdate,
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
                onAutoCheckBankChange = viewModel::setAutoCheckBank,
                onClearHistory = viewModel::clearHistory,
                onOpenUpdates = onOpenUpdates,
                contentPadding = innerPadding,
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    onRetryChallenge: (Int, List<String>) -> Unit,
    onCheckBankUpdate: () -> Unit,
    onInstallBankUpdate: () -> Unit,
    onRemovePackage: () -> Unit,
    onAutoCheckBankChange: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onOpenUpdates: () -> Unit,
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
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "并行发送三题",
                                    style = MaterialTheme.typography.bodyMedium,
                                    // 名字挂在开关上，标题就不再单独播报一遍，否则读屏会把
                                    // 同一句话念两次（标题一次、开关一次）。
                                    modifier = Modifier.clearAndSetSemantics {},
                                )
                            }
                            // 只有开关本身可切换：整行可点会带来两件用户不想要的事——点标题也
                            // 翻状态，以及在一整行上铺一层高光（看着像选中效果）。整行合并语义
                            // 试过不成立（开关自己就是合并节点，仍旧单独留在无障碍树里），所以
                            // 名字挂在开关上：不这样读屏只会念「开关，已开启」。
                            //
                            // 按下时的水波纹整个盖在圆钮上，就是用户说的「圆疙瘩上的虚影」，
                            // 而且按下态与选中态长得一样，看着像已经选中。这里把涟漪关掉：
                            // LocalRippleConfiguration 置 null 会让 M3 的开关直接不挂涟漪节点
                            // （DelegatingThemeAwareRippleNode 读到 null 就 removeRipple），
                            // 状态仍由滑块位置与轨道颜色如实表达，点击照常切换。
                            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                                Switch(
                                    checked = state.useParallel,
                                    onCheckedChange = onParallelChange,
                                    enabled = !state.isRunning,
                                    modifier = Modifier.semantics { contentDescription = "并行发送三题" },
                                )
                            }
                        }
                    } else {
                        Text(
                            "把三道题目发给目标模型，再把回答粘回对应输入框。",
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

        // 手动模式没有结果行，分析结果本身就是全部输出，单独渲染。
        // API 模式也有 analysis（单模型），但它属于那一行结果——行里展开就能看到同样的
        // 评价卡与候选榜；再在下边渲染一遍正是用户报的「重复」，所以这里加了空行条件。
        if (state.batchResults.isEmpty()) {
            state.analysis?.let { analysis ->
                item { DetectionResultCard(analysis) }
                item { CandidateList(analysis.candidates) }
            }
        }

        item {
            HistoryCard(
                entries = state.history,
                onClearHistory = onClearHistory,
            )
        }

        item {
            ReferenceBankCard(
                state = state,
                onCheckBankUpdate = onCheckBankUpdate,
                onInstallBankUpdate = onInstallBankUpdate,
                onRemovePackage = onRemovePackage,
                onAutoCheckBankChange = onAutoCheckBankChange,
                onOpenUpdates = onOpenUpdates,
            )
        }
    }
}

/**
 * The detection history: what was tested, what it looked like, and when.
 *
 * Lives next to the results rather than inside the detection-package card because it is
 * the panel's own output, not a fact about the reference data. Only the newest
 * [FingerprintHistoryStore.MAX_ENTRIES] are kept, so the list cannot grow without bound;
 * the count is stated so that cap is visible rather than looking like data loss.
 */
@Composable
private fun HistoryCard(
    entries: List<DetectionHistoryEntry>,
    onClearHistory: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    OutlinedCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "检测历史",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    // 空的时候不再解释这张卡是干什么的（用户要求去掉说明），留下的
                    // 一行是条数，会随检测变化。
                    if (entries.isNotEmpty()) {
                        Text(
                            "最近 ${entries.size} 条（最多保留 ${FingerprintHistoryStore.MAX_ENTRIES} 条）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { open = true },
                enabled = entries.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("查看历史记录") }
        }
    }

    if (open) {
        HistoryDialog(
            entries = entries,
            onRequestClear = { confirmClear = true },
            onDismiss = { open = false },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空检测历史？") },
            text = { Text("将删除全部 ${entries.size} 条记录，此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    onClearHistory()
                    confirmClear = false
                    open = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HistoryDialog(
    entries: List<DetectionHistoryEntry>,
    onRequestClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检测历史") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = HISTORY_DIALOG_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                entries.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            entry.model,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            historyOutcome(entry),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (entry.error == null) {
                                Color(0xFF15803D)
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            // 只有报错那一支降重，成功的绿色结论仍是标签的 Medium。
                            fontWeight = if (entry.error == null) null else ERROR_MESSAGE_WEIGHT,
                        )
                        Text(
                            buildString {
                                append(formatHistoryTime(entry.finishedAt))
                                if (entry.supplierName.isNotBlank()) {
                                    append(" · ")
                                    append(entry.supplierName)
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRequestClear) { Text("清空历史") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * One line saying what the detection concluded.
 *
 * A failed attempt says why instead of showing an empty ranking: "这个模型没测出来" and
 * "这个模型在 12 天前就返回 502" are different facts, and only the second one is worth
 * remembering.
 */
internal fun historyOutcome(entry: DetectionHistoryEntry): String {
    val candidate = listOfNotNull(entry.candidateName, entry.familyName).joinToString(" · ")
    val percent = entry.probability?.let { "（${(it * 100).toInt()}%）" }.orEmpty()
    return when {
        entry.error != null -> "失败：${entry.error}"
        candidate.isBlank() -> "未识别出候选"
        else -> "$candidate$percent · 有效回答 ${entry.usableAnswers}/${entry.submittedAnswers}"
    }
}

/** Local wall-clock stamp for a history row, e.g. `10-03 14:22`. */
internal fun formatHistoryTime(epochMillis: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMillis))

/**
 * Which detection package the panel is scoring with, and how to move it forward.
 *
 * The package is downloaded rather than shipped in the APK, so this card is also the
 * panel's provisioner: with nothing installed it explains what is missing and offers
 * the download. After that the check is a button rather than a background poll — the
 * one exception is noted on the card itself, because a panel that quietly talked to the
 * network would make its "检测不联网" promise a lie.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReferenceBankCard(
    state: FingerprintUiState,
    onCheckBankUpdate: () -> Unit,
    onInstallBankUpdate: () -> Unit,
    onRemovePackage: () -> Unit,
    onAutoCheckBankChange: (Boolean) -> Unit,
    onOpenUpdates: () -> Unit,
) {
    val context = LocalContext.current
    val busy = state.isCheckingBankUpdate || state.isInstallingBank || state.isRunning
    // 只有静默检查占用状态行：状态行是它唯一的迹象（这个标志以前没人读，一次静默检查看
    // 起来就像什么都没发生）。用户自己点的那次，进度只画在按钮上——两处同时转圈很难看，
    // 而状态行里「上次检查：…」比「正在检查更新…」信息更多，不该被顶掉。
    val isCheckingQuietly = state.isCheckingBankInBackground
    // Fixed for as long as the timestamp it describes: "今天 14:32" only changes when
    // midnight passes, and re-reading the clock on every recomposition buys nothing.
    val nowMillis = remember(state.bankCheckedAtMillis, state.bankEarlierCheckAtMillis) {
        System.currentTimeMillis()
    }
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

            UpdateStatusRow(
                text = updateCheckLine(
                    isChecking = isCheckingQuietly,
                    // This process's own finished check first: a check made here knows its
                    // verdict, the stamp off the disk does not.
                    checkedAtMillis = state.bankCheckedAtMillis ?: state.bankEarlierCheckAtMillis,
                    outcome = bankCheckOutcome(state),
                    nowMillis = nowMillis,
                ),
                isChecking = isCheckingQuietly,
            )

            state.bankDownloadProgress?.let { progress ->
                UpdateDownloadRow(progress = progress)
            }

            // The published package demands an app newer than this one, so no install
            // button is offered. The sentence is only useful if it can be acted on, so
            // the whole row opens the update page.
            state.bankNeedsNewerApp?.let { required ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenUpdates)
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        bankNeedsNewerAppLine(required),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "去更新软件",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            state.bankProblem?.let { problem ->
                Text(
                    problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // 「检查更新」与「删除检测包」并排两栏（等宽），「下载/更新检测包」是这张卡上
            // 唯一的主操作，单独占一行。
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
                    ButtonLabel("检查更新")
                }
                if (state.bankSource != BankSource.NOT_PROVISIONED) {
                    // 与「检查更新」同一个 M3 默认描边样式：两个按钮是一对并列动作，一个
                    // 有框一个没有，看着像没画完（用户反馈）。要能跟下面「查看支持的模型」
                    // 区分开，靠的是那个按钮的 primary 色描边加图标，不是这两栏的不对称。
                    OutlinedButton(
                        onClick = onRemovePackage,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        ButtonLabel("删除检测包")
                    }
                }
            }

            if (state.availableBankUpdate != null) {
                Button(
                    onClick = onInstallBankUpdate,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
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
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            SupportedModelsButton(models = state.bankModels)

            // 自动检测与它管的那件事贴在一起：开关只管「打开软件要不要自己查」，与上面的
            // 手动按钮是一件事的两条路径，所以留在同一张卡上。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "自动检查检测包更新",
                        style = MaterialTheme.typography.bodyMedium,
                        // 名字挂在开关上，标题就不再单独播报一遍，否则读屏会把同一句话念两次。
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                // 与「并行发送三题」同一个做法：只有开关本身可切换（整行可点会让点标题也翻
                // 状态），并按用户反馈把涟漪关掉——按下态的圆钮高光看着像已经选中。
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Switch(
                        checked = state.autoCheckBank,
                        onCheckedChange = onAutoCheckBankChange,
                        modifier = Modifier.semantics { contentDescription = "自动检查检测包更新" },
                    )
                }
            }

            // MIT 要求署名，这一行不是解释文案，压缩轮里保留原文。
            Text(
                "参考数据由 lm-detector (MIT) 提供。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 项目地址单独一行做成链接。行内链接（LinkAnnotation）要 material3 的
            // Text 支持 textLinkStyles，本项目的 material3 1.3.1 还没有这个重载，
            // 与其依赖一个可能不派发点击的版本，不如把链接做成一个明确的点击行。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { openUriSafely(context, LM_DETECTOR_URL) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    LM_DETECTOR_URL,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
    }
}

/**
 * A one-line button label that shrinks instead of being cut off.
 *
 * The card under 检测包 is the only place with two labels side by side in half-width columns, and
 * at the 2.0× system font scale both stop fitting: 「删除检测包」 renders as 「删除检测…」 and the
 * roster button as 「查看支持的模型（58 …」 (device measurement, API 35 emulator). These strings
 * are copy, not layout, so the label gives up size rather than a character. The measurement is
 * the one the 有效 8/8 column already does — Compose 1.7 has no scale-to-fit text and the app
 * carries no dependency that would add one. At the normal scale the text already fits, so the
 * style and therefore the rendering are untouched.
 */
@Composable
private fun ButtonLabel(text: String) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = LocalTextStyle.current
    BoxWithConstraints {
        val available = maxWidth
        val natural = with(density) {
            measurer.measure(text, style, maxLines = 1, softWrap = false).size.width.toDp()
        }
        val fitted = if (natural > available) {
            // 0.98 keeps the rounding of the scaled font from landing a hair over the edge
            // and clipping the last glyph.
            style.copy(fontSize = style.fontSize * (available / natural) * 0.98f)
        } else {
            style
        }
        Text(text, style = fitted, maxLines = 1, softWrap = false)
    }
}

/**
 * Which models the package in use can identify.
 *
 * 53 entries would push the rest of the panel off screen, so the roster lives in a dialog
 * opened from a bordered button: the card keeps one line, grouped by family (the package's
 * own dimension — GPT / Claude / Gemini …), and nothing is shown without a readable
 * package.
 */
@Composable
private fun SupportedModelsButton(models: List<BankModelInfo>) {
    if (models.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(
        onClick = { open = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.List,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        ButtonLabel("查看支持的模型（${models.size} 个）")
    }
    if (open) {
        SupportedModelsDialog(models = models, onDismiss = { open = false })
    }
}

@Composable
private fun SupportedModelsDialog(models: List<BankModelInfo>, onDismiss: () -> Unit) {
    // 按家族分段：groupBy 保留首次出现的顺序，所以分段不改变包内顺序。
    val families = remember(models) { models.groupBy { it.familyName }.entries.toList() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检测包支持的模型") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = MODELS_DIALOG_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "共 ${models.size} 个模型 · ${families.size} 个家族",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                families.forEach { (family, members) ->
                    Text(
                        "$family · ${members.size}",
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    members.forEach { model ->
                        Text(
                            model.displayName,
                            modifier = Modifier.padding(start = 12.dp, top = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
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
 * Model filter, its list dialog and the selection summary.
 *
 * The text field is a search key over the supplier's catalogue, not a model name; the
 * catalogue itself lives behind the list button, where ticking rows chooses what to
 * detect, in tick order. Keeping the list off the panel is what keeps the run button and
 * the challenge cards on screen. When the keyword matches nothing — including a supplier
 * whose models were never pulled — the typed name is offered as a model of its own, so
 * such a model stays reachable.
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
    var listOpen by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("模型", style = MaterialTheme.typography.labelLarge)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // 贴底对齐，不是居中：OutlinedTextField 的布局盒比它画出来的框高 8 dp
            // （带浮动标签时顶端要预留标签的位置），按布局盒居中会让按钮看着偏高
            // ——实测按钮圆心比输入框可见边框的圆心高 9.5 px。两个都贴底，圆钮再
            // 让出半个高度差，就与输入框的可见边框同心。
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = state.modelFilter,
                onValueChange = onFilterChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                singleLine = true,
                label = { Text("筛选模型") },
                // 没有 placeholder：聚焦时它会被挤成两行，而字段名已经说明这里是筛选用。
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.modelFilter.isNotEmpty()) {
                        IconButton(onClick = { onFilterChange("") }, enabled = enabled) {
                            Icon(Icons.Outlined.Close, contentDescription = "清空筛选")
                        }
                    }
                },
            )
            BadgedBox(
                badge = {
                    if (state.selectedModels.isNotEmpty()) {
                        Badge { Text("${state.selectedModels.size}") }
                    }
                },
                modifier = Modifier.padding(bottom = MODEL_LIST_BUTTON_INSET),
            ) {
                OutlinedIconButton(
                    onClick = { listOpen = true },
                    enabled = enabled,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.List,
                        contentDescription = "选择模型",
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Text(
            if (state.selectedModels.isEmpty()) {
                "勾一个测一个；多选按勾选顺序逐个检测。"
            } else {
                "将按顺序检测：" + state.selectedModels.joinToString(" → ")
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.selectedModels.isEmpty()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }

    if (listOpen) {
        ModelListDialog(
            matches = matches,
            unmatched = unmatched,
            catalogueEmpty = state.models.isEmpty(),
            selectedModels = state.selectedModels,
            enabled = enabled,
            onToggleModel = onToggleModel,
            onClearSelection = onClearSelection,
            onDismiss = { listOpen = false },
        )
    }
}

/**
 * The catalogue as a dialog, one row per model plus the typed fallback.
 *
 * The filter field above keeps working while this is open, so typing narrows the list
 * here rather than behind the dialog. "清空" lives here rather than on the panel because
 * this is the only place a tick can be undone.
 */
@Composable
private fun ModelListDialog(
    matches: List<String>,
    unmatched: String?,
    catalogueEmpty: Boolean,
    selectedModels: List<String>,
    enabled: Boolean,
    onToggleModel: (String) -> Unit,
    onClearSelection: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (catalogueEmpty && unmatched == null) {
                    Text(
                        "暂无模型；可在「模型测试」拉取，或直接输入模型名。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 一屏五行；其余在这个框里滚动，弹窗本身不会再长高。
                            .heightIn(max = MODEL_ROW_HEIGHT * VISIBLE_MODEL_ROWS)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        unmatched?.let { keyword ->
                            ModelRow(
                                label = keyword,
                                supporting = "该供应商的列表里没有这个名字，将直接用它检测",
                                order = selectedModels.indexOf(keyword).takeIf { it >= 0 }?.plus(1),
                                selected = keyword in selectedModels,
                                enabled = enabled,
                                onToggle = { onToggleModel(keyword) },
                            )
                        }
                        matches.forEach { model ->
                            ModelRow(
                                label = model,
                                supporting = null,
                                order = selectedModels.indexOf(model).takeIf { it >= 0 }?.plus(1),
                                selected = model in selectedModels,
                                enabled = enabled,
                                onToggle = { onToggleModel(model) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            if (selectedModels.isNotEmpty()) {
                TextButton(onClick = onClearSelection) { Text("清空") }
            }
        },
    )
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

/**
 * The batch output: one row per tested model, in the order they were run.
 *
 * A finished row opens on tap and shows that model's candidate ranking — the same list the
 * single-model analysis card shows, so a batch no longer tells you less about a model than
 * testing it alone did.
 */
@Composable
private fun BatchResultList(results: List<ModelFingerprintResult>) {
    val settled = settledModelCount(results)
    // 「有效 n/n」与右边那个百分比各占一个定宽槽位，两处都是这次修的同一个毛病：
    // 百分比原来只在有值时渲染，于是模型名的 weight(1f) 会把这个 Row 的右缘推到不同的
    // 位置，「有效 3/3」（有百分比）与「有效 1/3」（没有）就落在两个 x 上。槽位宽度按
    // 最宽的取值实排一次（「有效 8/8」「100%」），起点/终点因此对所有行一致，2.0× 字号
    // 也不会把字裁掉；宽度用 widthIn(min = …) 兜底，万一将来出现更长的取值只是把名字
    // 挤窄一点，而不是裁字。
    val countStyle = MaterialTheme.typography.labelSmall
    val scoreStyle = MaterialTheme.typography.titleSmall
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val countSlot = remember(textMeasurer, countStyle, density) {
        with(density) { textMeasurer.measure("有效 8/8", countStyle).size.width.toDp() }
    }
    val scoreSlot = remember(textMeasurer, scoreStyle, density) {
        with(density) { textMeasurer.measure("100%", scoreStyle).size.width.toDp() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("检测结果", style = MaterialTheme.typography.titleMedium)
        Text(
            "已出结果 $settled/${results.size} 个模型",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        results.forEachIndexed { index, row ->
            val expandable = row.status == ModelDetectionStatus.DONE
            var expanded by rememberSaveable(row.model, row.status) { mutableStateOf(false) }
            OutlinedCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (expandable) {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        role = Role.Button,
                                    ) { expanded = !expanded }
                                } else {
                                    Modifier
                                },
                            )
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    row.model,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // 有效回答 n/n 原来挤在下面那句候选说明的括号里，跟模型名
                                // 无关的一堆字混在一起。挪到模型名右边并换成对比色，一行
                                // 就看出「哪个模型·吃了几条」；定宽槽位保证每个 n/n 都在
                                // 同一个 x 上（用户明确要求 0/3…3/3 统一）。
                                if (row.status == ModelDetectionStatus.DONE) {
                                    Box(
                                        modifier = Modifier.widthIn(min = countSlot),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        Text(
                                            "有效 ${row.usableAnswers}/${row.submittedAnswers}",
                                            style = countStyle,
                                            color = when {
                                                row.usableAnswers < 3 -> MaterialTheme.colorScheme.error
                                                row.usableAnswers == row.submittedAnswers ->
                                                    MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.tertiary
                                            },
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                }
                            }
                            Text(
                                when (row.status) {
                                    ModelDetectionStatus.PENDING -> "等待检测"
                                    ModelDetectionStatus.RUNNING -> "正在检测…"
                                    ModelDetectionStatus.DONE ->
                                        listOfNotNull(row.candidateName, row.familyName)
                                            .joinToString(" · ")
                                    ModelDetectionStatus.FAILED -> row.error ?: "检测失败"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (row.status) {
                                    ModelDetectionStatus.DONE -> Color(0xFF15803D)
                                    ModelDetectionStatus.FAILED -> MaterialTheme.colorScheme.error
                                    ModelDetectionStatus.RUNNING -> Color(0xFF1D4ED8)
                                    ModelDetectionStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                // 同样是「只有报错那一支降重」：等待/进行中/结论这些标签
                                // 保持 Medium，失败理由跟着格子里的报错一起用 Normal。
                                fontWeight = if (row.status == ModelDetectionStatus.FAILED) {
                                    ERROR_MESSAGE_WEIGHT
                                } else {
                                    null
                                },
                                maxLines = 2,
                            )
                        }
                        if (row.status == ModelDetectionStatus.DONE) {
                            // 槽位常在：没有百分比的行也占住这块宽度，「有效 n/n」才不会
                            // 因此往右挪。
                            Box(
                                modifier = Modifier.widthIn(min = scoreSlot),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                row.probability?.let { probability ->
                                    Text(
                                        "${(probability * 100).toInt()}%",
                                        style = scoreStyle,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                            Icon(
                                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = if (expanded) "收起候选" else "展开候选",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (expanded) {
                        Column(
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 评价卡在上、候选榜在下：展开后先看「这个模型最像谁、吃了几条
                            // 有效回答」，再往下看整张排序。单模型检测此前在下面另起一份
                            // 评价卡＋候选榜，跟这里的展开内容重复——现在两处只有这一份。
                            ModelEvaluationCard(row)
                            if (row.candidates.isEmpty()) {
                                Text(
                                    "这个模型没有候选数据。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                CandidateList(
                                    candidates = row.candidates,
                                    showTitle = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Row height used to cap the model list at [VISIBLE_MODEL_ROWS] rows. */
private val MODEL_ROW_HEIGHT = 48.dp

/**
 * Floor for one question column.
 *
 * A third of the panel is about 121 dp wide, and the tallest cell is the failing one
 * (state line + count + two lines of reason + the retry button). The floor is what keeps
 * the ordinary cell from collapsing to its two short lines, so the three columns in a
 * block read as one row rather than three ragged strips.
 */
private val CHALLENGE_CELL_MIN_HEIGHT = 104.dp

/**
 * Touch strip for a failing cell's 「重试」.
 *
 * The strip spans the whole cell and keeps the 48 dp touch height Material 3 would have
 * given the button anyway; what changed is the drawn button inside it. The old control was
 * a `TextButton`: device measurement (10504) had uiautomator report its clickable node at
 * 58 × 48 dp starting at the cell's own left edge, with an 11 sp label in the middle of it
 * — a loose left-aligned word with blank above, below and to its right, which is what the
 * user saw. The chip is centred and 24 dp tall, so the blank is gone while the strip's own
 * target (whole cell wide, 48 dp tall) is larger than the old button's.
 */
private val CHALLENGE_RETRY_TOUCH_HEIGHT = 48.dp

/**
 * How far the model-list button is lifted off the text field's bottom edge.
 *
 * An outlined text field's layout box is 8 dp taller than the box it draws: with a
 * floating label the top 8 dp are kept clear of the border (device measurement at
 * 420 dpi: layout box 64 dp, drawn border 56 dp, both ending on the same bottom edge).
 * Centring a 52 dp button against that whole layout box therefore floats its circle
 * 4 dp above the border's centre — the "button sits a bit high" this fixes.
 *
 * Bottom-aligning the two boxes removes the label reserve; this constant then centres
 * the 52 dp circle inside the 56 dp container slot: (56 - 52) / 2.
 */
private val MODEL_LIST_BUTTON_INSET = 2.dp

/** Past this the dialog scrolls instead of growing past the screen. */
private val MODELS_DIALOG_MAX_HEIGHT = 380.dp

/** Past this the history list scrolls; 50 entries would never fit otherwise. */
private val HISTORY_DIALOG_MAX_HEIGHT = 420.dp

/**
 * The weight every line that reports a failure is drawn at.
 *
 * `labelSmall` carries Material 3's default `FontWeight.Medium`, and a Medium *CJK* face is
 * visibly heavier than a Medium Latin one — an error that mixes the two (「HTTP 403」 next to
 * a Chinese reason) came out looking like two different weights, with the Chinese side the
 * bold one. Dropping failure text to Normal makes both scripts render at the same weight, and
 * puts error lines on the same footing as the body text around them.
 */
private val ERROR_MESSAGE_WEIGHT = FontWeight.Normal

/** Where the detection package's reference data comes from. */
private const val LM_DETECTOR_URL = "https://github.com/Ikaleio/lm-detector"

@Composable
private fun ChallengeList(
    state: FingerprintUiState,
    onManualAnswerChange: (Int, String) -> Unit,
    onRetryChallenge: (Int, List<String>) -> Unit,
    onRegenerate: () -> Unit,
) {
    val context = LocalContext.current
    val manual = state.mode == DetectionMode.MANUAL
    // In manual mode nothing sets RECEIVED until analysis runs, so count pastes that
    // already clear the threshold; otherwise the header reads 0/3 while three usable
    // answers sit in the fields.
    val received = state.progress.count {
        it.parsedNumbers >= minimumNumbersFor(it.challenge.expectedCount, state.minimumValidNumbers)
    }
    // The bar tracks the whole round, not the current model's three questions: it used
    // to reset to empty at every model change, so a three-model run looked like it kept
    // restarting from nothing.
    val roundSize = roundQuestionsTotal(state.batchResults, state.progress)
    val roundDone = roundQuestionsDone(state.batchResults, state.progress)
    val copyPrompt: (Int) -> Unit = { index ->
        state.progress.getOrNull(index)?.let { entry ->
            copyToClipboard(context, entry.challenge.prompt, "已复制题目 ${index + 1} 的提示词")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("三道题目", style = MaterialTheme.typography.titleMedium)
                // 只留会变的那一行：手动模式收到几条有效回答是进度，不是说明；API 模式
                // 的版面解释已按用户要求去掉。
                if (manual) {
                    Text(
                        "已收到 $received/${state.progress.size} 条有效回答",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onRegenerate, enabled = !state.isRunning) {
                Icon(Icons.Outlined.Refresh, contentDescription = "重新生成题目")
            }
        }
        if (state.isRunning) {
            val fraction = if (roundSize > 0) roundDone.toFloat() / roundSize else 0f
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "整轮进度 $roundDone/$roundSize 题",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (manual) {
            // 手动模式仍是竖向的长卡片：每栏只有约 121dp，放不下提示词全文和粘贴框，
            // 而且手动粘贴没有「模型」可言，按模型分块无从谈起。
            state.progress.forEachIndexed { index, entry ->
                ManualChallengeCard(
                    index = index,
                    entry = entry,
                    enabled = !state.isRunning,
                    minimumNumbers = minimumNumbersFor(entry.challenge.expectedCount, state.minimumValidNumbers),
                    onCopy = { copyPrompt(index) },
                    onAnswerChange = { onManualAnswerChange(index, it) },
                )
            }
        } else if (state.batchResults.isEmpty()) {
            // 还没开跑：也给一组三栏，题目照旧可以先复制走。
            ChallengeBlock(
                ordinal = null,
                model = null,
                status = null,
                slots = state.progress,
                retryableIndexes = emptySet(),
                queuedIndexes = emptySet(),
                onCopy = copyPrompt,
                onRetry = {},
            )
        } else {
            state.batchResults.forEachIndexed { position, row ->
                ChallengeBlock(
                    ordinal = position + 1,
                    model = row.model,
                    status = row.status,
                    slots = slotsFor(state, row.model),
                    // 这一题的失败模型里包含本块的模型，这一格才长出重试入口；点它就是补发
                    // 这一格，所以不再需要「重试哪个模型」的选择弹窗。
                    retryableIndexes = state.retryableModels
                        .filterValues { models -> models.contains(row.model) }
                        .keys,
                    queuedIndexes = state.queuedRetries
                        .filter { it.first == row.model }
                        .map { it.second }
                        .toSet(),
                    onCopy = copyPrompt,
                    onRetry = { index -> onRetryChallenge(index, listOf(row.model)) },
                )
            }
        }
    }
}

/**
 * The three slots to draw for [model].
 *
 * The model being asked right now reads the screen-wide [FingerprintUiState.progress]:
 * that is the list the streaming counter is published into, so its cell counts integers
 * live. Every other model reads its own entry in [FingerprintUiState.modelSlots], which is
 * the cache that keeps all of them. A model whose round has not started has no entry yet,
 * so its cells are the template's untouched placeholders.
 */
private fun slotsFor(state: FingerprintUiState, model: String): List<ChallengeProgress> {
    if (state.isRunning && state.activeModel == model) return state.progress
    return state.modelSlots[model] ?: state.progress.map { ChallengeProgress(it.challenge) }
}

/**
 * One model's questions: a header (which model, how far it has got) and three columns.
 *
 * The panel used to show a single set of three cards for whichever model was being asked,
 * so a batch's earlier models vanished from the question area the moment the next one
 * started and the run could only be followed one model at a time. One block per model puts
 * the whole round on screen at once.
 *
 * With no model attached (nothing has run yet) it still renders the three columns without
 * a header, so the prompts stay readable and copyable before a round.
 */
@Composable
private fun ChallengeBlock(
    ordinal: Int?,
    model: String?,
    status: ModelDetectionStatus?,
    slots: List<ChallengeProgress>,
    retryableIndexes: Set<Int>,
    queuedIndexes: Set<Int>,
    onCopy: (Int) -> Unit,
    onRetry: (Int) -> Unit,
) {
    OutlinedCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (model != null && status != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$ordinal",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        model,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ModelStatusChip(status)
                }
            }
            // 三格等高：失败格多一行错误文本和重试按钮时，同组另外两格跟着一起长，
            // 横向的三栏才不会参差不齐。
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                slots.forEachIndexed { index, entry ->
                    ChallengeCell(
                        index = index,
                        entry = entry,
                        retryable = index in retryableIndexes,
                        queued = index in queuedIndexes,
                        onCopy = { onCopy(index) },
                        onRetry = { onRetry(index) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** Which model this is and how far its round has got, as the block's own one-word badge. */
@Composable
private fun ModelStatusChip(status: ModelDetectionStatus) {
    val text = when (status) {
        ModelDetectionStatus.DONE -> "已出结果"
        ModelDetectionStatus.FAILED -> "失败"
        ModelDetectionStatus.RUNNING -> "正在检测"
        ModelDetectionStatus.PENDING -> "等待检测"
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = when (status) {
            ModelDetectionStatus.DONE -> Color(0xFFDCFCE7)
            ModelDetectionStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
            ModelDetectionStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer
            ModelDetectionStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * One question of one model, in a column about a third of the panel wide.
 *
 * The card shrinks to a column by dropping what the wide card could afford: the title
 * carries only the question number, the status line is the compact wording, and the
 * expected count moves to its own small line. The copy button keeps its documented
 * behaviour — it is not gated on [enabled] anywhere, because copying the prompt while the
 * request is in flight is exactly when the user wants it.
 */
@Composable
private fun ChallengeCell(
    index: Int,
    entry: ChallengeProgress,
    retryable: Boolean,
    queued: Boolean,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier.heightIn(min = CHALLENGE_CELL_MIN_HEIGHT)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "题目 ${index + 1}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                QuietIconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "复制题目 ${index + 1} 的提示词",
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            stateColor(entry.state),
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                )
                Text(
                    // 逐题发送时的等待与「已经在接收」不是一回事，不能混为一谈。
                    if (queued) "排队中" else compactStateLabel(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (queued) MaterialTheme.colorScheme.onSurfaceVariant else stateColor(entry.state),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "需 ${entry.challenge.expectedCount} 个整数",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            entry.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = ERROR_MESSAGE_WEIGHT,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (retryable) {
                // 失败理由占 0/1/2 行，格子本身又靠 IntrinsicSize.Min 跟同组另外两格等
                // 高，所以上面那行一变长，「重试」就被顶下去 —— 三格里同一排按钮凑不到一
                // 个水平高度（用户反馈）。留白放在理由下面：弹簧吃掉剩余空间，按钮永远贴
                // 格子底边，三格等高时三枚按钮同高；理由多的格子空白自然就少。
                Spacer(Modifier.weight(1f))
                // 一枚居中的小芯片，住在一整格宽、48dp 高的触控条里。原来用 M3 的
                // TextButton：它把 11sp 的「重试」放进一个 58×48dp 的盒子，还按 Column
                // 的默认 Start 对齐贴着格子左缘 —— 于是看起来是一段悬在左边的小字，
                // 上下各空 19dp（设备实测：可点节点 58×48dp 从格子左缘起）。现在看得见
                // 的按钮只有 24dp 高且水平居中，触控面反而更大（整格宽 × 48dp）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CHALLENGE_RETRY_TOUCH_HEIGHT)
                        // 不要涟漪：默认涟漪会在这条整格宽的透明条上铺一层矩形高光，
                        // 像凭空多出一个框（与应用里「详情」等安静动作一致）。
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onRetry,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        Text(
                            "重试",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One question in manual mode: the prompt in full, and a box to paste the answer into.
 *
 * Manual mode keeps the wide vertical card the API round no longer uses: a third of the
 * panel cannot hold the prompt or a paste box, and with nothing but pasted text there is
 * no per-model grid to draw.
 */
@Composable
private fun ManualChallengeCard(
    index: Int,
    entry: ChallengeProgress,
    enabled: Boolean,
    minimumNumbers: Int,
    onCopy: () -> Unit,
    onAnswerChange: (String) -> Unit,
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
                // 复制不受检测状态限制：题目全文是静态的，检测中正要拿它去别处比对，
                // 所以这里不接 enabled。带涟漪的按钮在检测中会跟着禁用，正是用户
                // 报的「检测中复制按钮点不动」。
                QuietIconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(48.dp),
                ) {
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
            }

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

/**
 * Short status word for a challenge card.
 *
 * A rejection must not repeat [ChallengeProgress.error] here: the reason already
 * renders on its own line directly below, and echoing it in the status line prints
 * the same sentence twice on every failed card.
 *
 * While a request is in flight the line counts the integers that have actually
 * arrived, so a challenge that takes a minute reads as progress rather than a frozen
 * "正在接收…". The count is only shown once something countable has landed: the first
 * seconds of a request deliver prose, and "0 个整数" would look like a stall.
 */
internal fun stateLabel(entry: ChallengeProgress): String = when (entry.state) {
    ChallengeState.PENDING -> "等待发送"
    ChallengeState.REQUESTING ->
        if (entry.receivedNumbers > 0) {
            "正在接收… 已收到 ${entry.receivedNumbers} 个整数"
        } else {
            "正在接收…"
        }
    ChallengeState.RECEIVED -> "已接收 ${entry.parsedNumbers} 个有效数字"
    ChallengeState.REJECTED -> "未采用"
}

/**
 * The same states, in the width a third of the panel can carry.
 *
 * Measured against the real column rather than guessed: at 1080px/420dpi the three
 * columns leave roughly 121dp each, and the long wording above ("正在接收… 已收到 166
 * 个整数") wraps there and pushes the count out of the cell. The wording stays a prefix of
 * the long form so a screenshot of either reads the same way.
 */
internal fun compactStateLabel(entry: ChallengeProgress): String = when (entry.state) {
    ChallengeState.PENDING -> "等待发送"
    ChallengeState.REQUESTING ->
        if (entry.receivedNumbers > 0) "接收中 ${entry.receivedNumbers}" else "接收中…"
    ChallengeState.RECEIVED -> "已接收 ${entry.parsedNumbers}"
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
    EvaluationCard(
        displayName = analysis.prediction?.displayName,
        familyName = analysis.familyName,
        probability = analysis.prediction?.probability,
        usableAnswers = analysis.usableAnswers,
        submittedAnswers = analysis.submittedAnswers,
        calibrated = analysis.candidates.firstOrNull()?.probability != null,
    )
}

/**
 * The evaluation card for one settled batch row — the same content, from row fields.
 *
 * A batch row used to open straight onto the candidate ranking, while the single-model
 * run put this card above it. Both now show card-then-list, so testing a model alone and
 * testing it in a batch read identically (the user asked for exactly that unification).
 */
@Composable
private fun ModelEvaluationCard(row: ModelFingerprintResult) {
    EvaluationCard(
        displayName = row.candidateName,
        familyName = row.familyName.orEmpty(),
        probability = row.probability,
        usableAnswers = row.usableAnswers,
        submittedAnswers = row.submittedAnswers,
        calibrated = row.candidates.firstOrNull()?.probability != null,
    )
}

/**
 * The one "how close was this" card, shared by the single-model run and a batch row.
 *
 * Takes primitives rather than a [FingerprintAnalysis] so a row — which keeps only the
 * fields it needs to show — can render the identical card without faking an analysis.
 */
@Composable
private fun EvaluationCard(
    displayName: String?,
    familyName: String,
    probability: Double?,
    usableAnswers: Int,
    submittedAnswers: Int,
    calibrated: Boolean,
) {
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
                displayName ?: "暂不可评分",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("有效回答", style = MaterialTheme.typography.labelSmall)
                    // 少于 3 条就没有置信度（红）；全部采用（绿）与部分采用（青）分开，
                    // 与挑战行、状态词共用同一套语义色。
                    val usableColor = when {
                        usableAnswers < 3 -> MaterialTheme.colorScheme.error
                        usableAnswers == submittedAnswers -> Color(0xFF15803D)
                        else -> MaterialTheme.colorScheme.tertiary
                    }
                    Text(
                        "$usableAnswers/$submittedAnswers",
                        style = MaterialTheme.typography.titleSmall,
                        color = usableColor,
                    )
                }
                Column {
                    Text("家族", style = MaterialTheme.typography.labelSmall)
                    Text(familyName, style = MaterialTheme.typography.titleSmall)
                }
                probability?.let {
                    Column {
                        Text("置信", style = MaterialTheme.typography.labelSmall)
                        Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            if (!calibrated) {
                Text(
                    if (usableAnswers < 3) {
                        "仅 $usableAnswers/3 条有效回答：只给排序，无置信度。"
                    } else {
                        "本包无置信度标定，只给排序。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                "仅为库内最接近候选，非身份证明；相近版本需谨慎。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CandidateList(
    candidates: List<FingerprintCandidate>,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTitle) {
            Text("候选排序", style = MaterialTheme.typography.titleMedium)
        }
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
                            // 供应商/家族名原来跟其他说明文字一样是灰的，夹在候选名下面
                            // 看不出是另一类信息；换成 tertiary 让「名字·来源」分层。
                            color = MaterialTheme.colorScheme.tertiary,
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
