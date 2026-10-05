package com.relaytester.app.feature.update

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relaytester.app.ui.components.DialogDismissScrim
import com.relaytester.app.ui.components.UpdateDownloadRow
import com.relaytester.app.ui.components.UpdateStatusRow
import com.relaytester.app.ui.components.openUriSafely
import com.relaytester.app.ui.components.rememberDialogWindowBox
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
    val nowMillis = remember(state.checkedAtMillis, state.earlierCheckAtMillis) {
        System.currentTimeMillis()
    }

    // This page is a window of its own, so the host MainActivity keeps for the launch-time
    // news sits behind it: the failures and messages produced while it is open need a host
    // inside it. The two hosts are mutually exclusive (MainActivity only consumes while this
    // page is closed), so a message is shown exactly once.
    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        appViewModel.clearMessage()
    }

    // 卡片要占屏宽 94%（平台默认窗口只给约 320dp），所以得关掉平台的默认宽度——代价是 Compose
    // 会按整屏测量内容，内容盒比窗口还高，贴底的东西会被裁到屏幕外。盒子的大小因此自己说：
    // Modifier.dialogWindowBox() 把内容盒定成窗口真正能占的那块（[0,128]-[1080,2337]）。
    // decorFitsSystemWindows 与这套几何无关：本机 ≥S 时它只影响窗口主题，<S 才影响软键盘模式
    // 与 windowIsFloating，所以这里按默认值走。
    // 在弹窗**外面**算好这块盒子的尺寸：弹窗自己的窗口夹在状态栏与导航栏之间，在里面读内边距
    // 一律是 0，算出来就是整屏高（这个修复就没用了）。返回值是普通 Modifier，可以带进弹窗内容。
    val windowBox = rememberDialogWindowBox()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 高度随内容收缩，只留一个上限：以前这里写死 0.9 屏高，检测包卡片搬走以后内容少
        // 了一大块，窗口却还是那么大，底下空一片。上限之外的部分交给内容自己的滚动。
        val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
        Box(modifier = windowBox, contentAlignment = Alignment.Center) {
            DialogDismissScrim(onDismiss = onDismiss)
            Surface(
                modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = maxSheetHeight),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
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
                            .fillMaxWidth()
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
                    // This process's own check first; the stamp off the disk has no verdict.
                    checkedAtMillis = state.checkedAtMillis ?: state.earlierCheckAtMillis,
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

            // 说明文本就在清单里（发布器把发行说明压成纯文本写进去），卡片内直接印；清单里没有
            // 文本的老版本清单只剩发布页 URL，那种情况退回一枚链接。
            state.available?.let { manifest ->
                if (manifest.notes.isNotBlank()) {
                    UpdateNotesBox(
                        notes = manifest.notes,
                        notesUrl = manifest.notesUrl,
                        onOpenNotes = onOpenNotes,
                    )
                } else if (manifest.notesUrl.isNotBlank()) {
                    TextButton(
                        onClick = { onOpenNotes(manifest.notesUrl) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("  更新说明", textDecoration = TextDecoration.Underline, maxLines = 1)
                    }
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
 * 说明盒最多占屏幕的这个比例。
 *
 * 定比例而不是定 dp，因为要护住的是弹窗自己那条「屏高 90%」的上限：卡片的固定部分
 * （版本行、状态行、两枚按钮、自动检查开关）大约 400dp，0.9 − 0.3 在任何机型上都还
 * 留得下它；屏幕再矮则由卡片外层那圈滚动兜底。
 */
internal const val UPDATE_NOTES_SCREEN_FRACTION = 0.3f

/** 说明盒的高度上限；[screenHeightDp] 用窗口（屏高减系统栏）即可，比例本身就是余量。 */
internal fun updateNotesBoxMaxHeight(screenHeightDp: Int): Dp =
    (screenHeightDp * UPDATE_NOTES_SCREEN_FRACTION).dp

/**
 * 发行说明，直接印在卡片里，而不是藏在链接后面。
 *
 * 盒子有自己的上限和自己的滚动：说明可以上千字，没有上限就会把按钮与自动检查开关顶到
 * 折叠线以下——而这个弹窗本身还有「屏高 90%」的上限。有了它，卡片在有说明时也保持没有
 * 说明时的形状，说明在自己那一小块里随手指滚。带链接的完整版仍留一键（在浏览器打开）。
 */
@Composable
private fun UpdateNotesBox(
    notes: String,
    notesUrl: String,
    onOpenNotes: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("更新说明", style = MaterialTheme.typography.bodyMedium)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            // 长文本的盒子，下沿一定裁在某一行中间：一条渐隐让「还能往下滚」看得出来，而不是
            // 看起来像说明本身被截断了。只在确实还有内容时画。
            val scroll = rememberScrollState()
            Box {
                Text(
                    notes,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = updateNotesBoxMaxHeight(LocalConfiguration.current.screenHeightDp))
                        .verticalScroll(scroll)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (scroll.canScrollForward) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(14.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceVariant),
                                ),
                            ),
                    )
                }
            }
        }
        if (notesUrl.isNotBlank()) {
            TextButton(
                onClick = { onOpenNotes(notesUrl) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("  在浏览器打开", textDecoration = TextDecoration.Underline, maxLines = 1)
            }
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
