package com.relaytester.app.feature.fingerprint

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                onModelChange = viewModel::updateModel,
                onParallelChange = viewModel::updateParallel,
                onManualAnswerChange = viewModel::updateManualAnswer,
                onRun = viewModel::runApiDetection,
                onAnalyze = viewModel::runManualAnalysis,
                onCancel = viewModel::cancelRun,
                onRegenerate = viewModel::regenerateChallenges,
                onRetryChallenge = viewModel::retryChallenge,
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
    onModelChange: (String) -> Unit,
    onParallelChange: (Boolean) -> Unit,
    onManualAnswerChange: (Int, String) -> Unit,
    onRun: () -> Unit,
    onAnalyze: () -> Unit,
    onCancel: () -> Unit,
    onRegenerate: () -> Unit,
    onRetryChallenge: (Int) -> Unit,
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
                        FilterChip(
                            selected = state.mode == DetectionMode.API,
                            onClick = { onModeChange(DetectionMode.API) },
                            enabled = !state.isRunning,
                            label = { Text("API 直连") },
                        )
                        FilterChip(
                            selected = state.mode == DetectionMode.MANUAL,
                            onClick = { onModeChange(DetectionMode.MANUAL) },
                            enabled = !state.isRunning,
                            label = { Text("手动粘贴") },
                        )
                    }

                    if (state.mode == DetectionMode.API) {
                        SupplierModelPicker(
                            state = state,
                            onSupplierChange = onSupplierChange,
                            onModelChange = onModelChange,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("并行发送三题", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "关闭后逐题发送，慢速中转站更稳",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.useParallel,
                                onCheckedChange = onParallelChange,
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
                    enabled = canRun,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(if (state.mode == DetectionMode.API) "开始检测" else "分析粘贴的回答")
                }
            }
        }

        state.analysis?.let { analysis ->
            item { DetectionResultCard(analysis) }
            item { CandidateList(analysis.candidates) }
        }

        item {
            Text(
                "参考库构建于 ${state.referenceBuiltAt.ifBlank { "未知" }} · " +
                    "${state.modelCount} 个模型标签 · 由 lm-detector (MIT) 提供参考数据",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
                    "把结果与参考库比对就能看出后端最接近哪个模型。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SupplierModelPicker(
    state: FingerprintUiState,
    onSupplierChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.suppliers.forEach { supplier ->
                    FilterChip(
                        selected = state.selectedSupplierId == supplier.id,
                        onClick = { onSupplierChange(supplier.id) },
                        enabled = !state.isRunning,
                        label = {
                            Text(
                                supplier.name,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = state.selectedModel,
            onValueChange = onModelChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isRunning,
            singleLine = true,
            label = { Text("模型名") },
            placeholder = { Text("例如 claude-opus-5") },
        )
        if (state.models.isNotEmpty()) {
            Text(
                "该供应商已拉取的模型：" + state.models.take(6).joinToString("、") +
                    if (state.models.size > 6) " 等 ${state.models.size} 个" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
                    "已收到 $received/${state.progress.size} 条有效回答",
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
                        Text("库内置信", style = MaterialTheme.typography.labelSmall)
                        Text("${(probability * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                "这是参考库内的封闭集合排序：不在库中的模型同样会得到一个最接近的候选，" +
                    "分数不是身份证明。同家族相邻版本的区分尤其需要谨慎。",
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
