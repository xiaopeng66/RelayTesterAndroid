package com.relaytester.app.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relaytester.app.core.fingerprint.BankSource
import com.relaytester.app.feature.fingerprint.FingerprintUiState
import com.relaytester.app.feature.fingerprint.bankCheckOutcome
import com.relaytester.app.feature.fingerprint.bankNeedsNewerAppLine
import com.relaytester.app.feature.fingerprint.bankStateLine
import com.relaytester.app.ui.components.UpdateDownloadRow
import com.relaytester.app.ui.components.UpdateStatusRow
import com.relaytester.app.ui.components.openUriSafely
import com.relaytester.app.ui.components.updateCheckLine

/**
 * The whole update surface: both channels, their switches, and the app's own install path.
 *
 * A dialog rather than a fourth tab. The update state lives in view models that belong to
 * the activity, so dismissing this does not stop a download — reopening it shows the same
 * progress bar, which is also why the entry is a header icon rather than a destination.
 *
 * The two channels are shown together even though their switches are not: the switch for
 * the detection package belongs to the card that owns that package (the fingerprint
 * panel), and this page points at it instead of growing a second one.
 */
@Composable
fun UpdateDialog(
    appViewModel: AppUpdateViewModel,
    bankState: FingerprintUiState,
    onCheckBankUpdate: () -> Unit,
    onInstallBankUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by appViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val nowMillis = remember(state.checkedAtMillis, bankState.bankCheckedAtMillis) {
        System.currentTimeMillis()
    }
    val bankChecking = bankState.isCheckingBankUpdate || bankState.isCheckingBankInBackground

    // This page is a window of its own, so the host MainActivity keeps for the launch-time
    // news sits behind it: the failures and messages produced while it is open need a host
    // inside it. The two hosts are mutually exclusive (MainActivity only consumes while this
    // page is closed), so a message is shown exactly once.
    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        appViewModel.clearMessage()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("关于与更新", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "两个更新渠道各自独立，可分别关闭自动检查。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("关闭")
                    }
                }
                HorizontalDivider()

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppUpdateCard(
                        state = state,
                        nowMillis = nowMillis,
                        onCheck = appViewModel::checkNow,
                        onInstall = appViewModel::downloadAndInstall,
                        onAutoCheckChange = appViewModel::setAutoCheckApp,
                        onOpenNotes = { url -> openUriSafely(context, url) },
                    )

                    BankUpdateCard(
                        state = bankState,
                        nowMillis = nowMillis,
                        bankChecking = bankChecking,
                        onCheck = onCheckBankUpdate,
                        onInstall = onInstallBankUpdate,
                    )

                    Text(
                        "检测包是模型指纹检测用的参考数据，不装它检测就没法打分；" +
                            "软件更新是可选的，只在你想升级时才下载。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun AppUpdateCard(
    state: AppUpdateUiState,
    nowMillis: Long,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onAutoCheckChange: (Boolean) -> Unit,
    onOpenNotes: (String) -> Unit,
) {
    OutlinedCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("软件更新", style = MaterialTheme.typography.bodyMedium)
            Text(
                appVersionLine(state.installed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.available?.let { manifest ->
                Text(
                    appUpdateOffer(manifest),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            state.otherPackage?.let { published ->
                Text(
                    appOtherPackageLine(state.installed, published),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            UpdateStatusRow(
                text = updateCheckLine(
                    isChecking = state.isChecking,
                    checkedAtMillis = state.checkedAtMillis,
                    outcome = appCheckOutcome(state),
                    nowMillis = nowMillis,
                ),
                isChecking = state.isChecking,
            )

            state.downloadProgress?.let { progress ->
                UpdateDownloadRow(progress = progress)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onCheck,
                    enabled = !state.isChecking && !state.isDownloading,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    if (state.isChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("检查更新", maxLines = 1)
                    }
                }
                if (state.available != null) {
                    Button(
                        onClick = onInstall,
                        enabled = !state.isDownloading && !state.isChecking,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text(
                            if (state.isDownloading) "正在下载…" else "下载并安装",
                            maxLines = 1,
                        )
                    }
                }
            }

            // 更新说明是发布页链接：清单里没有说明文本，只有 URL。
            state.available?.notesUrl?.takeIf { it.isNotBlank() }?.let { url ->
                TextButton(
                    onClick = { onOpenNotes(url) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                ) {
                    Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  更新说明", textDecoration = TextDecoration.Underline, maxLines = 1)
                }
            }

            HorizontalDivider()

            AutoCheckRow(
                label = "自动检查软件更新",
                detail = "打开 App 时自动检查，最多每 6 小时一次",
                checked = state.autoCheckApp,
                onCheckedChange = onAutoCheckChange,
            )
        }
    }
}

@Composable
private fun BankUpdateCard(
    state: FingerprintUiState,
    nowMillis: Long,
    bankChecking: Boolean,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
) {
    val busy = bankChecking || state.isInstallingBank || state.isRunning
    OutlinedCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("检测包更新", style = MaterialTheme.typography.bodyMedium)
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

            state.bankNeedsNewerApp?.let { required ->
                Text(
                    bankNeedsNewerAppLine(required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            UpdateStatusRow(
                text = updateCheckLine(
                    isChecking = bankChecking,
                    checkedAtMillis = state.bankCheckedAtMillis,
                    outcome = bankCheckOutcome(state),
                    nowMillis = nowMillis,
                ),
                isChecking = bankChecking,
            )

            state.bankDownloadProgress?.let { progress ->
                UpdateDownloadRow(progress = progress)
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
                    onClick = onCheck,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("检查更新", maxLines = 1) }
                if (state.availableBankUpdate != null) {
                    Button(
                        onClick = onInstall,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text(
                            if (state.bankSource == BankSource.NOT_PROVISIONED) "下载检测包" else "更新检测包",
                            maxLines = 1,
                        )
                    }
                }
            }

            HorizontalDivider()

            // A pointer rather than a second switch: one setting gets one control, and this
            // one belongs to the card that owns the package. Two switches writing the same
            // key would drift apart whenever one of them was stale.
            Text(
                "自动检查检测包更新的开关在「指纹检测」面板的检测包卡片里。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A labelled switch, built the way the panel's other switches are.
 *
 * Only the switch itself toggles: a clickable row would flip the state when the user aimed
 * at the label, and would draw a highlight across the whole row that reads as a selection.
 * The name is on the switch because the visible label is silenced for the screen reader,
 * which would otherwise hear the same sentence twice.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AutoCheckRow(
    label: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The ripple on the thumb is what the user described as "the smear on the knob":
        // it covers the thumb, and the pressed state looks like the selected state.
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
    }
}
