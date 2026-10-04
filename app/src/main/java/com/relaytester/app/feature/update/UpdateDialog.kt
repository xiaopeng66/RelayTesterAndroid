package com.relaytester.app.feature.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.relaytester.app.ui.components.UpdateDownloadRow
import com.relaytester.app.ui.components.UpdateStatusRow
import com.relaytester.app.ui.components.openUriSafely
import com.relaytester.app.ui.components.updateCheckLine

/** The project's public repository, shown as the dialog's one outbound link. */
internal const val PROJECT_URL = "https://github.com/xiaopeng66/RelayTesterAndroid"
private const val PROJECT_URL_LABEL = "github.com/xiaopeng66/RelayTesterAndroid"

/**
 * The app's own update surface: the installed version, the feed's offer, and the install path.
 *
 * A dialog rather than a fourth tab. The update state lives in a view model that belongs to the
 * activity, so dismissing this does not stop a download — reopening it shows the same progress
 * bar, which is also why the entry is a header icon rather than a destination.
 *
 * The detection package used to have a second card here. It was removed: the package's card on
 * the fingerprint panel already owns its status, its buttons and its switch, and a copy on a
 * page that cannot install a package for a panel that is not on screen could only disagree.
 */
@Composable
fun UpdateDialog(
    appViewModel: AppUpdateViewModel,
    onDismiss: () -> Unit,
) {
    val state by appViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val nowMillis = remember(state.checkedAtMillis) { System.currentTimeMillis() }

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
                        Text(
                            "关于与更新",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineSmall,
                        )
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

                        ProjectLink(onOpen = { openUriSafely(context, PROJECT_URL) })
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

/**
 * The repository address as a whole-row link.
 *
 * A row rather than an inline link: the inline form needs a `Text` overload with
 * `textLinkStyles`, which this project's material3 (1.3.1) does not have yet, and a link that
 * may not dispatch clicks is worse than a row that obviously is one.
 */
@Composable
private fun ProjectLink(onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,
            ),
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
            PROJECT_URL_LABEL,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        )
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

            // 只有静默（启动/定时）检查占用状态行，它是那类检查唯一的迹象。用户自己点的
            // 那次进度只画在按钮上：两处同时转圈很难看，而「上次检查：…」比「正在检查更新…」
            // 信息更多，不该被顶掉。
            UpdateStatusRow(
                text = updateCheckLine(
                    isChecking = state.isCheckingQuietly,
                    checkedAtMillis = state.checkedAtMillis,
                    outcome = appCheckOutcome(state),
                    nowMillis = nowMillis,
                ),
                isChecking = state.isCheckingQuietly,
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
                    if (state.isChecking && !state.isCheckingQuietly) {
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
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  更新说明", textDecoration = TextDecoration.Underline, maxLines = 1)
                }
            }

            HorizontalDivider()

            AutoCheckRow(
                label = "自动检查软件更新",
                checked = state.autoCheckApp,
                onCheckedChange = onAutoCheckChange,
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
 * which would otherwise hear the same sentence twice. `LocalRippleConfiguration` is still an
 * experimental M3 surface in this library version, hence the opt-in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoCheckRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f).clearAndSetSemantics {},
            style = MaterialTheme.typography.bodyMedium,
        )
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
