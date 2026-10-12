package com.relaytester.app.feature.fingerprint

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.relaytester.app.ui.components.DialogDismissScrim
import com.relaytester.app.ui.components.PullOnlyMarker
import com.relaytester.app.ui.components.QuietIconButton
import com.relaytester.app.ui.components.rememberDialogWindowBox
import android.view.View
import com.relaytester.app.core.media.AnimationRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The animation test's two cards, opened from the 模型测试 header.
 *
 * It is two dialogs, not one card with two contents. The first is the way in: which
 * supplier and model the next test runs against, and every test so far. Tapping a record
 * in that list is what opens the second — one test's own screen, where the document is
 * written and then played. Splitting them is what makes the entry always land on the list
 * rather than on whatever test was last looked at, and it gives each screen the one job
 * its title says.
 *
 * Both are card dialogs in the same shape as the app's other dialogs — 94% of the screen
 * wide, a rounded elevated surface whose height follows its content between a floor and
 * the usual ceiling — rather than a bare full-bleed sheet. Closing the card keeps every
 * test, including one still generating — the entry button is what says so.
 */
@Composable
fun AnimationTestPanel(
    state: AnimationUiState,
    suppliers: List<SupplierOption>,
    onSupplierChange: (String) -> Unit,
    /** 勾选/取消一个模型，作为开始生成时要跑的候选。 */
    onModelToggle: (String) -> Unit,
    /** Starts every checked model for the selected supplier. */
    onStartSelected: (String) -> Unit,
    onOpenSession: (Long?) -> Unit,
    onRemoveSession: (Long) -> Unit,
    onPromptChange: (String) -> Unit,
    onRestoreDefault: () -> Unit,
    onRegenerate: () -> Unit,
    onCancel: () -> Unit,
    onReplay: () -> Unit,
    /** Hands the current document to a browser; false when the device has none. */
    onOpenInBrowser: () -> Boolean,
    onViewChange: (AnimationView) -> Unit,
    onRenderFailure: (String) -> Unit,
    /** Receives the list thumbnail once a successful result has been captured. */
    onSnapshot: (Long, ByteArray) -> Unit,
    /** Whether the reader has scrolled the reasoning box away from the newest line. */
    onReasoningHold: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // 一次只有一层：选中哪条记录决定弹出哪一层，而不是在同一张卡片里换内容。返回列表是把
    // 详情那一层收掉，列表重新组合一次（它的滚动位置会回到顶部，输入框里的选择不会丢）。
    val selected = state.selected
    if (selected == null) {
        AnimationTestListDialog(
            suppliers = suppliers,
            selectedSupplierId = state.supplierId,
            models = state.models,
            selectedModel = state.model,
            tickedModels = state.selectedModels,
            sessions = state.sessions,
            onSupplierChange = onSupplierChange,
            onModelToggle = onModelToggle,
            onStartSelected = onStartSelected,
            onOpenSession = onOpenSession,
            onRemoveSession = onRemoveSession,
            onDismiss = onDismiss,
        )
    } else {
        AnimationTestDetailDialog(
            session = selected,
            onPromptChange = onPromptChange,
            onRestoreDefault = onRestoreDefault,
            onRegenerate = onRegenerate,
            onCancel = onCancel,
            onReplay = onReplay,
            onOpenInBrowser = onOpenInBrowser,
            onViewChange = onViewChange,
            onRenderFailure = onRenderFailure,
            onSnapshot = onSnapshot,
            onReasoningHold = onReasoningHold,
            onBack = { onOpenSession(null) },
            onDismiss = onDismiss,
        )
    }
}

/**
 * The card every animation dialog is drawn on.
 *
 * 宽度与其它弹窗一致（屏宽 94%）；高度随内容走，只留上下限：提示词收起且还没结果时不该
 * 撑出一张空卡片，结果区展开后也不该把按钮顶到屏幕外。上限之外的部分交给内容自己的滚动。
 *
 * 定尺盒与遮罩不在这里，而在每个弹窗自己那一层：盒子的尺寸要在弹窗**外面**算，遮罩要
 * 按「窗口内、卡片外」的点击来工作，两者都是那个弹窗窗口的属性，不是卡片的属性。
 */
@Composable
private fun AnimationCard(content: @Composable ColumnScope.() -> Unit) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Surface(
        modifier = Modifier
            .fillMaxWidth(ANIMATION_CARD_WIDTH_FRACTION)
            .heightIn(
                min = screenHeight * ANIMATION_CARD_MIN_HEIGHT_FRACTION,
                max = screenHeight * ANIMATION_CARD_MAX_HEIGHT_FRACTION,
            ),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * The way in: the supplier and model the next test runs against, and every test so far.
 *
 * 这一层没有「返回」，它就是最外层：关闭、点卡片外、系统返回都关上它。
 */
@Composable
private fun AnimationTestListDialog(
    suppliers: List<SupplierOption>,
    selectedSupplierId: String?,
    models: List<String>,
    selectedModel: String?,
    tickedModels: List<String>,
    sessions: List<AnimationSession>,
    onSupplierChange: (String) -> Unit,
    onModelToggle: (String) -> Unit,
    onStartSelected: (String) -> Unit,
    onOpenSession: (Long?) -> Unit,
    onRemoveSession: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // 盒子的尺寸要在弹窗**外面**算：弹窗内容跑在它自己那个窗口的组合里，内边距一律读成 0，
    // 算出来就是整屏高（贴底的东西会被裁到屏幕外）。卡片自身的尺寸见 AnimationCard。
    val windowBox = rememberDialogWindowBox()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(modifier = windowBox, contentAlignment = Alignment.Center) {
            // 「点卡片外关掉」只能自己实现：平台只在点击落在窗口内、卡片外时才上报。
            // 见 DialogDismissScrim。
            DialogDismissScrim(onDismiss = onDismiss)
            AnimationCard {
                AnimationPanelHeader(
                    subtitle = null,
                    onBack = null,
                    onDismiss = onDismiss,
                )
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AnimationSetupSection(
                        suppliers = suppliers,
                        selectedSupplierId = selectedSupplierId,
                        models = models,
                        selectedModel = selectedModel,
                        tickedModels = tickedModels,
                        onSupplierChange = onSupplierChange,
                        onModelToggle = onModelToggle,
                        onStartSelected = onStartSelected,
                    )
                    AnimationSessionList(
                        sessions = sessions,
                        onOpen = onOpenSession,
                        onRemove = onRemoveSession,
                    )
                }
            }
        }
    }
}

/**
 * One test's own screen: its prompt, the document being written, and the animation.
 *
 * 返回（左上角箭头、系统返回、点卡片外）都是退回列表那一层；只有关闭按钮关掉整层。列表是
 * 这层唯一的来路，退回它比连列表一起关掉更接近用户按返回时的预期，也让「再看一眼别的记录」
 * 少一次重新打开。
 */
@Composable
private fun AnimationTestDetailDialog(
    session: AnimationSession,
    onPromptChange: (String) -> Unit,
    onRestoreDefault: () -> Unit,
    onRegenerate: () -> Unit,
    onCancel: () -> Unit,
    onReplay: () -> Unit,
    onOpenInBrowser: () -> Boolean,
    onViewChange: (AnimationView) -> Unit,
    onRenderFailure: (String) -> Unit,
    onSnapshot: (Long, ByteArray) -> Unit,
    onReasoningHold: (Boolean) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    // 提示词默认收起。面板打开时用户要看的是生成过程与结果，不是那段固定模板；展开是
    // 一个主动动作。状态按会话记录，换一条记录重开也回到收起。
    var promptExpanded by rememberSaveable(session.sessionId) { mutableStateOf(false) }
    val trimmedPrompt = session.draftPrompt.trim()
    val canSubmit = trimmedPrompt.isNotEmpty() &&
        session.phase != AnimationPhase.GENERATING

    // A preview left running in the background keeps burning CPU on a loop animation, so
    // the page is paused whenever the app is not in the foreground. The document stays
    // loaded: this is a pause, not a teardown.
    var foreground by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { foreground = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { foreground = false }

    // The preview hands its WebView up through here so the GIF recorder can draw it. Held as
    // state rather than passed down, because the view is created *by* the composable below
    // this one — the only way for the dialog to hold something its child produced.
    var previewView by remember { mutableStateOf<View?>(null) }
    var recording by remember { mutableStateOf(false) }
    val recorderScope = rememberCoroutineScope()
    // 录制要连画 2 秒，必须离开主线程；预览本身还在主线程上播放，两者不冲突。
    val context = LocalContext.current

    val windowBox = rememberDialogWindowBox()
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(modifier = windowBox, contentAlignment = Alignment.Center) {
            DialogDismissScrim(onDismiss = onBack)
            AnimationCard {
                AnimationPanelHeader(
                    subtitle = "${session.supplierName} · ${session.model}",
                    onBack = onBack,
                    onDismiss = onDismiss,
                )
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AnimationPromptSection(
                        session = session,
                        canSubmit = canSubmit,
                        expanded = promptExpanded,
                        onToggleExpanded = { promptExpanded = !promptExpanded },
                        onPromptChange = onPromptChange,
                        onRestoreDefault = onRestoreDefault,
                    )
                    // 生成中显示正在写入的文档，完成后的动画把这一块整段替掉：
                    // 两者是同一块位置的前后两个状态，不是上下并列的两段内容。
                    if (session.phase == AnimationPhase.GENERATING) {
                        AnimationStreamingSection(
                            session = session,
                            onReasoningHold = onReasoningHold,
                        )
                    } else {
                        AnimationStatusSection(session = session)
                        AnimationResultSection(
                            session = session,
                            foreground = foreground,
                            clipboard = clipboard,
                            previewView = previewView,
                            onPreviewViewChange = { previewView = it },
                            recording = recording,
                            onSnapshot = { png -> onSnapshot(session.sessionId, png) },
                            onViewChange = onViewChange,
                            onRenderFailure = onRenderFailure,
                        )
                    }
                }
                HorizontalDivider()
                // 打不开浏览器时说一句，而不是静默什么都不发生：没有浏览器是精简模拟器镜像
                // 的正常状态，用户点了按钮却毫无反应才是真正让人困惑的。
                var browserRefused by remember { mutableStateOf(false) }
                // 录制是异步的（跑 2 秒），拒绝只有两种：录不出帧、或者设备上没有能接图的
                // 应用。两者都该说话，理由同浏览器那条。
                var shareRefused by remember { mutableStateOf(false) }
                AnimationActionBar(
                    session = session,
                    canSubmit = canSubmit,
                    density = density,
                    onStart = onRegenerate,
                    onRegenerate = onRegenerate,
                    onCancel = onCancel,
                    onReplay = onReplay,
                    onCopy = {
                        session.html?.let { html ->
                            clipboard.setText(AnnotatedString(html))
                        }
                    },
                    onOpenInBrowser = {
                        browserRefused = session.html?.let { onOpenInBrowser() } == false
                    },
                    onShareGif = {
                        // 只有预览那一层有可录的画面。源码视图下按钮本来就禁用着，这里再挡
                        // 一道是因为「有没有 View」才是真正的条件，而不是「当前哪个 tab」。
                        val view = previewView
                        if (view == null || recording) {
                            false
                        } else {
                            shareRefused = false
                            recording = true
                            recorderScope.launch {
                                // 采样在主线程（View.draw 只能在那里），编码由 recorder 自己
                                // 落到后台线程，这里不要再套一层 Dispatchers.Default。
                                val gif = AnimationRecorder.recordGif(view)
                                recording = false
                                // 拒绝发生在协程里（录不出帧、或设备上没有接图的应用），
                                // 所以这个标志必须在这里写；外面那个返回值只表示「已受理」。
                                shareRefused = gif == null || !AnimationRecorder.share(context, gif)
                            }
                            true
                        }
                    },
                )
                if (browserRefused) {
                    Text(
                        "没有找到可以打开网页的应用，请先安装浏览器。",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (shareRefused) {
                    Text(
                        "分享失败：没有录到画面，或设备上没有可以接收图片的应用。",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * The supplier and model the next test runs against, plus the one button that starts it.
 *
 * Deliberately its own small form rather than reusing the detection panel's pickers: the
 * card is opened from the 模型测试 tab, where nothing of the animation test is selected yet
 * and borrowing the detection panel's choice would couple two unrelated screens.
 */
@Composable
private fun AnimationSetupSection(
    suppliers: List<SupplierOption>,
    selectedSupplierId: String?,
    models: List<String>,
    selectedModel: String?,
    tickedModels: List<String>,
    onSupplierChange: (String) -> Unit,
    onModelToggle: (String) -> Unit,
    onStartSelected: (String) -> Unit,
) {
    if (suppliers.isEmpty()) {
        Text(
            "还没有可用的供应商，请先在「供应商配置」里添加一个。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // A stored selection can outlive the list it came from (a supplier deleted while the
    // card was open), so neither value is trusted without checking it still exists.
    val supplierId = selectedSupplierId?.takeIf { id -> suppliers.any { it.id == id } }
    val model = selectedModel?.takeIf { models.contains(it) }
    val canStart = supplierId != null && tickedModels.any { it in models }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SupplierPicker(
            suppliers = suppliers,
            selectedId = supplierId,
            onSelect = onSupplierChange,
        )
        ModelPicker(
            models = models,
            selected = model,
            ticked = tickedModels.filter { models.contains(it) },
            onToggle = onModelToggle,
            onStartSelected = {
                if (supplierId != null) onStartSelected(supplierId)
            },
        )
        Button(
            onClick = { if (supplierId != null && canStart) onStartSelected(supplierId) },
            enabled = canStart,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        ) { Text("开始生成") }
    }
}

/**
 * One row of a [PickerDialog].
 *
 * [pullOnly] is carried as a field rather than folded into [label] because the marker is
 * drawn, not written: the label used to end in "· 仅拉取", which the 模型测试 page expresses
 * with a red bar instead. A flag keeps the two consistent and keeps the name itself intact
 * for the rows where it is already long enough to be ellipsized.
 */
internal data class PickerOption(
    val value: String,
    val label: String,
    val pullOnly: Boolean = false,
)

/**
 * The supplier list as [PickerOption]s.
 *
 * Internal rather than private so a test can pin the one invariant that matters here: a
 * pull-only supplier's [PickerOption.label] is its plain name, and the state rides in the
 * flag. Spelling it into the label is exactly what this round removed, and a test on the
 * view model cannot see it because the suffix was applied in the composable.
 */
internal fun supplierPickerOptions(suppliers: List<SupplierOption>): List<PickerOption> =
    suppliers.map { supplier ->
        PickerOption(value = supplier.id, label = supplier.name, pullOnly = supplier.pullOnly)
    }

/** Picks which supplier the next test runs against. */
@Composable
private fun SupplierPicker(
    suppliers: List<SupplierOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = suppliers.firstOrNull { it.id == selectedId }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("供应商", style = MaterialTheme.typography.labelMedium)
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            if (selected?.pullOnly == true) {
                PullOnlyMarker()
                Spacer(Modifier.width(6.dp))
            }
            Text(
                selected?.name ?: "请选择供应商",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
    }
    if (expanded) {
        PickerDialog(
            title = "选择供应商",
            options = supplierPickerOptions(suppliers),
            onPick = { onSelect(it); expanded = false },
            onDismiss = { expanded = false },
        )
    }
}

@Composable
private fun ModelPicker(
    models: List<String>,
    selected: String?,
    ticked: List<String>,
    onToggle: (String) -> Unit,
    onStartSelected: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // 按钮上的文案：勾了两个以上显示数量（多选进行中的事实），其余显示单选指向的模型。
    // 单选模型是勾选集合里目录顺序第一个（VM 保证），所以勾一个时它就是那个模型。
    val label = if (ticked.size >= 2) "已选 ${ticked.size} 个模型" else selected
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("模型", style = MaterialTheme.typography.labelMedium)
        OutlinedButton(
            onClick = { expanded = true },
            enabled = models.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text(
                label ?: if (models.isEmpty()) "该供应商没有模型" else "请选择模型",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
    }
    if (expanded) {
        MultiModelPickerDialog(
            title = "选择模型",
            options = models.map { PickerOption(value = it, label = it) },
            ticked = ticked.toSet(),
            onToggle = onToggle,
            onStartSelected = {
                onStartSelected()
                expanded = false
            },
            onDismiss = { expanded = false },
        )
    }
}

/**
 * Multi-select model picker with an inline search box.
 *
 * The filter is local: it hides rows, it never unticks anything, so a keyword typed while
 * accumulating a batch cannot silently drop a model from the selection. It also costs no
 * request — the catalogue is whatever the supplier already returned.
 *
 * 「开始生成」is the one start action: it runs every ticked model, so one tick and many
 * ticks are the same flow with different counts.
 */
@Composable
private fun MultiModelPickerDialog(
    title: String,
    options: List<PickerOption>,
    ticked: Set<String>,
    onToggle: (String) -> Unit,
    onStartSelected: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val visible = if (terms.isEmpty()) {
        options
    } else {
        options.filter { option -> terms.all { it in option.label.lowercase() } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索模型") },
                    placeholder = { Text("例如 glm、flash") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
                            }
                        }
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (terms.isEmpty()) {
                        "共 ${options.size} 个模型，已选 ${ticked.size} 个"
                    } else {
                        "匹配 ${visible.size} / ${options.size} 个，已选 ${ticked.size} 个"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = ANIMATION_PICKER_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (visible.isEmpty()) {
                        Text(
                            "没有匹配的模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    visible.forEach { option ->
                        val checked = ticked.contains(option.value)
                        TextButton(
                            onClick = { onToggle(option.value) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { onToggle(option.value) })
                            Text(
                                option.label,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Start,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onStartSelected,
                enabled = ticked.isNotEmpty(),
            ) { Text("开始生成") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * A plain single-choice list in a dialog.
 *
 * A dropdown menu anchored to a button inside a scrolled card would be clipped by the
 * card's own bounds, so the choice is made in its own dialog where the whole list fits.
 */
@Composable
private fun PickerDialog(
    title: String,
    options: List<PickerOption>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = ANIMATION_PICKER_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { option ->
                    TextButton(
                        onClick = { onPick(option.value) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    ) {
                        if (option.pullOnly) {
                            PullOnlyMarker()
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            option.label,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("取消")
            }
        },
    )
}

/** One record: what it is, what it is doing, and where to open it. */
@Composable
private fun AnimationSessionList(
    sessions: List<AnimationSession>,
    onOpen: (Long?) -> Unit,
    onRemove: (Long) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "测试记录",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (sessions.isEmpty()) {
            Text(
                "还没有测试记录。选好供应商和模型，点「开始生成」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        sessions.forEach { session ->
            AnimationSessionRow(
                session = session,
                onOpen = { onOpen(session.sessionId) },
                onRemove = { onRemove(session.sessionId) },
            )
        }
    }
}

/**
 * A record's row.
 *
 * The whole row opens the detail view — the record is what the user wants to look at, and
 * a small "open" affordance on a dense row is a smaller target for no gain. The delete
 * control is the one thing that must not be part of that target.
 */
@Composable
private fun AnimationSessionRow(
    session: AnimationSession,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ANIMATION_ROW_MIN_HEIGHT)
                .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimationPhaseMark(session.phase)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.model,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    session.supplierName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // A finished test shows what it made instead of the word for "finished": the row
            // used to say 「已完成」, which tells the user nothing about the animation and made
            // every successful record look alike. The picture takes exactly the space that
            // status word occupied — the row's own width and height are unchanged, so the model
            // name and the delete control keep their places. Failures keep their reason text: a
            // red line says what went wrong, and a thumbnail of a broken render would hide it.
            session.snapshot?.let { png ->
                AnimationSnapshotThumbnail(png)
            } ?: Text(
                animationPhaseLabel(session).orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = animationPhaseColor(session.phase),
                maxLines = 1,
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "删除这条测试记录",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** The row's leading mark: a dot while generating, a tick or cross once it has settled. */
@Composable
private fun AnimationPhaseMark(phase: AnimationPhase) {
    val color = animationPhaseColor(phase)
    Surface(
        modifier = Modifier.size(ANIMATION_ROW_MARK_SIZE),
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.16f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.size(8.dp),
                shape = MaterialTheme.shapes.small,
                color = color,
            ) {}
        }
    }
}

/**
 * The captured frame a finished record shows in the list.
 *
 * Sized from the row rather than from the source bitmap: a fixed 16:9 box the width of the
 * label block it replaces, so every row lines up whatever the capture happened to be. The
 * document may be any aspect; `ContentScale.Crop` fills the box and keeps a frame recognisable
 * at a glance instead of letterboxing it into a stripe.
 *
 * Decoding happens in a `remember` keyed on the bytes, and on the default dispatcher's
 * `BitmapFactory` path — it is a small PNG (a device-sized WebView at the preview's own
 * scale, a few tens of KB), so the decode is cheap, but it must not repeat on every
 * recomposition of a list that scrolls.
 */
@Composable
private fun AnimationSnapshotThumbnail(png: ByteArray) {
    val image = remember(png) {
        android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
    }
    Surface(
        modifier = Modifier
            .width(ANIMATION_SNAPSHOT_WIDTH)
            .aspectRatio(ANIMATION_SNAPSHOT_ASPECT_RATIO)
            .clipToBounds(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "该动画的截图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Undecodable bytes are not worth a message of their own: the row still opens the
            // result, which is where the animation actually lives.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "已生成",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The record's own one-line state, in words rather than a bare icon. */
internal fun animationPhaseLabel(session: AnimationSession): String? = when (session.phase) {
    AnimationPhase.GENERATING -> {
        val received = session.streamingText?.length ?: 0
        if (received > 0) "生成中 · $received 字符" else "生成中"
    }
    // No "done" wording: a successful record shows its picture instead (see
    // [AnimationSnapshotThumbnail]). This is the fallback for a result whose frame has not been
    // captured yet, and it points at where the animation is rather than repeating that it
    // finished.
    AnimationPhase.READY -> null
    AnimationPhase.FAILED -> session.error?.take(ANIMATION_ROW_ERROR_CHARS) ?: "失败"
    AnimationPhase.EDITING -> "未开始"
}

@Composable
private fun animationPhaseColor(phase: AnimationPhase) = when (phase) {
    AnimationPhase.GENERATING -> MaterialTheme.colorScheme.primary
    AnimationPhase.READY -> MaterialTheme.colorScheme.primary
    AnimationPhase.FAILED -> MaterialTheme.colorScheme.error
    AnimationPhase.EDITING -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 卡片占屏宽的比例，与 App 其它弹窗同一条。 */
private const val ANIMATION_CARD_WIDTH_FRACTION = 0.94f

/** 卡片高度下限：收起提示词、还没结果时，卡片也要有正常的弹窗高度。 */
private const val ANIMATION_CARD_MIN_HEIGHT_FRACTION = 0.45f

/** 卡片高度上限，与「关于与更新」等弹窗同一条；超出部分由内容自己滚动。 */
private const val ANIMATION_CARD_MAX_HEIGHT_FRACTION = 0.9f

/** A record row's touch height, so the whole row is a comfortable target. */
private val ANIMATION_ROW_MIN_HEIGHT = 56.dp

/** The leading phase mark's box. */
private val ANIMATION_ROW_MARK_SIZE = 28.dp

/**
 * The list row's snapshot.
 *
 * Sized to the row it sits in, not the other way round: the row keeps its original height
 * (the leading phase mark's box) and the picture takes the space the old status word used,
 * as a small 16:9 card. A wider thumbnail pushed the model name into an ellipsis and made
 * every row taller, which is a layout regression for the sake of a preview that the row
 * already opens.
 *
 * 16:9 matches the preview pane the capture came from, so a frame is never letterboxed.
 */
private val ANIMATION_SNAPSHOT_WIDTH = 64.dp
private const val ANIMATION_SNAPSHOT_ASPECT_RATIO = 16f / 9f

/**
 * How long to wait after the result appears before capturing its picture.
 *
 * The WebView reports neither "load finished" nor "first paint" through this panel, so a
 * capture taken the instant the composable enters would photograph an empty page. One beat is
 * enough for the first frame of a local document; if it is ever not, the row simply falls back
 * to its wording rather than showing a blank thumbnail as if that were the animation.
 */
private const val ANIMATION_SNAPSHOT_DELAY_MS = 600L

/** How much of a failure reason a list row shows before the detail view's full text. */
private const val ANIMATION_ROW_ERROR_CHARS = 12

/** Ceiling for a picker's option list, so a long model list still fits on a short screen. */
private val ANIMATION_PICKER_MAX_HEIGHT = 320.dp

/** Title bar: native, above the WebView, with the card's only close control. */
@Composable
private fun AnimationPanelHeader(
    subtitle: String?,
    /** Non-null in the detail view: returns to the list. */
    onBack: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回测试列表")
                }
            }
            Text(
                "动画测试",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭动画测试")
            }
        }
        // 供应商与完整模型名：模型名不省略，这个面板要能回答「这到底是哪个模型」。
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The editable prompt, with the two prompt-only actions that never send a request.
 *
 * The box collapses to a single line on request. A long prompt plus a result area does not
 * fit a card at any font scale, and the collapsed state is what lets the result — the part
 * the user came back to look at — have the room; the text itself is never lost, and the
 * field keeps its value while collapsed because only its height changes.
 */
@Composable
private fun AnimationPromptSection(
    session: AnimationSession,
    canSubmit: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onPromptChange: (String) -> Unit,
    onRestoreDefault: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "提示词",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            TextButton(
                onClick = onRestoreDefault,
                modifier = Modifier.heightIn(min = 40.dp),
            ) { Text("恢复默认") }
            // 收起/展开：收起后只剩一行高度，把纵向空间让给结果区。
            TextButton(
                onClick = onToggleExpanded,
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (expanded) "收起" else "展开")
            }
        }
        OutlinedTextField(
            value = session.draftPrompt,
            onValueChange = onPromptChange,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (expanded) {
                        // 展开态给一个范围而不是定高：短提示词不至于空一大块，长提示词的
                        // 编辑靠它自己的滚动，不把卡片撑长。
                        Modifier.heightIn(min = PROMPT_EXPANDED_MIN_HEIGHT, max = PROMPT_EXPANDED_MAX_HEIGHT)
                    } else {
                        // 收起态：一行高度，超出部分仍可在这个框里横向滚动编辑。
                        Modifier.heightIn(min = PROMPT_COLLAPSED_HEIGHT, max = PROMPT_COLLAPSED_HEIGHT)
                    },
                ),
            enabled = true,
            label = { Text("发送给模型的提示词") },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = !expanded,
            isError = session.draftPrompt.trim().isEmpty(),
        )
        if (session.draftPrompt.trim().isEmpty()) {
            Text(
                "提示词不能为空",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (session.phase == AnimationPhase.GENERATING) {
            Text(
                "正在生成；此时改动草稿不会影响已经发出的请求。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (session.htmlPrompt != null && session.htmlPrompt != session.draftPrompt) {
            Text(
                "草稿已改动，当前预览仍属于上一次提交的提示词；点「重新生成」才会用新草稿。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 提示词输入框收起时的高度：一行。 */
private val PROMPT_COLLAPSED_HEIGHT = 56.dp

/** 展开时的上下限：下限保证能看见几行，上限之外交给输入框自己的滚动。 */
private val PROMPT_EXPANDED_MIN_HEIGHT = 120.dp
private val PROMPT_EXPANDED_MAX_HEIGHT = 280.dp

/**
 * The generation itself, shown as the document being written.
 *
 * This replaces the indeterminate spinner this panel used to show during a request. A
 * spinner says only "something is happening"; the answer here is a large HTML+SVG document
 * that takes a while to arrive, so showing its tail tells the user the request is alive,
 * roughly how far along it is, and what it is spending tokens on — and it is the same text
 * the source view will show once it lands.
 *
 * The layout follows what a coding agent's own output does, because that is the shape the
 * user already reads this kind of stream in:
 *  - the reasoning is a *secondary*, explicitly labelled block above the answer, folded
 *    away as soon as the answer starts — thinking is the process, the document is the
 *    deliverable, and the deliverable keeps the space;
 *  - the answer is the primary block, in monospace, with its tail always in view;
 *  - every line carries the numbers that answer "should I keep waiting": how much has
 *    arrived and how long it has been. Before this the header counted the *tail* — a
 *    finished 12 000-character document displayed as "已收到 4 000 字符" — and showed no
 *    elapsed time at all, so a model thinking for three minutes was indistinguishable from
 *    a hung request;
 *  - the empty box with a placeholder sentence in it is gone. A full-height box holding no
 *    content is pure noise; before the first character there is one line saying what is
 *    being waited for, and the boxes appear when there is something in them.
 *
 * The text is the tail kept in [AnimationSession.streamingText]; it is scrolled to the end
 * on every update so the newest characters are the ones on screen, and the whole box scrolls
 * so the user can look back at what has already arrived.
 */
@Composable
private fun AnimationStreamingSection(
    session: AnimationSession,
    onReasoningHold: (Boolean) -> Unit,
) {
    val tail = session.streamingText
    val reasoning = session.reasoningText
    // Follows the end as the document grows, and holds position while the reader looks back.
    // Keyed on the cumulative count, not on the displayed text's length: the box shows a
    // fixed-size tail, so once the document passes that size the length stops changing.
    val scroll = rememberFollowEndScrollState(session.receivedChars)
    // A one-second clock for the elapsed readout. It ticks inside this section only, which is
    // composed while a request is in flight — so the number stops by construction when the
    // section leaves the composition, without a second piece of state to keep in sync.
    // The primitive state holder, not `mutableStateOf`: this is written every second for the
    // whole length of a generation, and the boxed one allocates on each tick.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.requestId) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    // The reasoning starts folded. It is process, not the deliverable: the panel's job while a
    // request runs is to show that something is happening — the status line and the counters
    // already do that — and an auto-expanding chain of tens of thousands of characters pushed
    // the output box off screen. The user opens it when they want to read it. The choice
    // sticks across recompositions, and it is no longer auto-folded on answer arrival because
    // it never starts open.
    var reasoningExpanded by rememberSaveable(session.sessionId) {
        mutableStateOf(ANIMATION_REASONING_EXPANDED_BY_DEFAULT)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "正在生成",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                animationStreamingStatus(session, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        // Why the counters just went back to zero. Without this line an automatic retry is
        // indistinguishable from the same attempt losing its progress, which is the kind of
        // thing that gets reported as a bug in the counting rather than as a retry.
        session.retryNotice?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (tail == null) {
            Text(
                animationStreamingHint(session),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 思考块：二级内容，带自己的标题与折叠开关。字号、颜色都比正文低一档——它是过程，
        // 不是交付物；正文一到就自动收起，需要回看时点标题展开。
        if (reasoning != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { reasoningExpanded = !reasoningExpanded }
                            .padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (reasoningExpanded) {
                                Icons.Outlined.ExpandLess
                            } else {
                                Icons.Outlined.ExpandMore
                            },                            contentDescription = if (reasoningExpanded) "收起思考内容" else "展开思考内容",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            animationReasoningLabel(session),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (reasoningExpanded) {
                        // Its own scroll state, same follow-the-end rule as the output box. The
                        // state is created once and held across recompositions; created inline
                        // (as it was) the box re-mounted at offset 0 on every delta, so the
                        // *top* of the chain stayed on screen and the newest line was pushed out
                        // of view — which is what "思考的最后一行只能显示一半" was.
                        //
                        // The pin decision is also published outward, because holding the offset
                        // is not enough on its own: the text is a `takeLast` window that slides
                        // as it grows, so a held offset would keep its pixel position while the
                        // content under it advanced. The ViewModel stops trimming while the
                        // reader is away from the end, which is what actually keeps the line
                        // being read on screen.
                        val reasoningScroll = rememberFollowEndScrollState(
                            contentKey = session.reasoningChars,
                            onPinnedChange = { pinned -> onReasoningHold(!pinned) },
                        )
                        val maxHeight = reasoningMaxHeight()
                        // The cap is a whole number of line-heights, and both the padding and the
                        // clip sit *outside* the scrollable. Padding inside it shifts the text
                        // down by its own height while the clip boundary stays put, so the last
                        // line loses exactly that much off its bottom — which is the half-glyph
                        // still visible here. Order matters: pad, then clip, then scroll.
                        Text(
                            // 思考也按代码读：散文保持原样，``` 围栏里的代码按语言上色。
                            text = rememberCodeText(reasoning) { reasoningSpans(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = maxHeight)
                                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                                .clipToBounds()
                                .verticalScroll(reasoningScroll),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // 正文：一级内容。等宽字体是因为这是模型正在吐出的源码，不是给人读的散文；等宽也让
        // 行宽稳定，滚动时不会因为字符宽度变化而抖动。
        if (tail != null) {
            Text(
                "输出",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Same rule as the reasoning box above: the height is a whole number of monospace
            // lines and the padding is outside the scrollable, so the bottom edge of the pane
            // falls between two lines and the newest one is never cut through the middle. The
            // padding has to come before the clip: inside the scroll it shifts every line down
            // while the clip boundary stays put, and the last line is sliced by exactly its
            // height.
            val outputMaxHeight = monospaceMaxHeight(ANIMATION_OUTPUT_MAX_LINES)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = outputMaxHeight * ANIMATION_OUTPUT_MIN_LINE_FRACTION, max = outputMaxHeight),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    // 正在写入的就是 HTML 源码，按源码上色：标签、属性、字符串各有其色，
                    // 用户不用在一片灰字里找结构。tail 只有 4 000 字符，每次重算没有成本。
                    text = rememberCodeText(tail) { markupSpans(it) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .clipToBounds()
                        .verticalScroll(scroll),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Text(
            "动画绘制完成后会自动替换这段内容。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The live state line: what the request is doing, with the numbers that say whether to keep
 * waiting. Extracted so the wording is testable without a Compose host.
 */
internal fun animationStreamingStatus(session: AnimationSession, now: Long): String {
    val elapsed = animationElapsedLabel(session.startedAt?.let { now - it } ?: 0L)
    val phase = when {
        session.streamingText != null -> "输出中 · ${session.receivedChars} 字符"
        session.reasoningText != null -> "思考中 · ${session.reasoningChars} 字"
        else -> "等待响应"
    }
    // The attempt number only appears when there has been more than one, so an ordinary run
    // reads exactly as it did before and a retry is labelled rather than silently repeated.
    val attempt = if (session.attempt > 1) " · 第 ${session.attempt} 次尝试" else ""
    return "$phase · $elapsed$attempt"
}

/** The one line shown before the answer starts; the boxes appear with their content. */
internal fun animationStreamingHint(session: AnimationSession): String =
    if (session.reasoningText != null) {
        "HTML 会在思考结束后开始输出。"
    } else {
        "已发出请求，正在等待上游的第一个分片…"
    }

/** The reasoning block's own title: how much thinking has arrived, even while folded. */
internal fun animationReasoningLabel(session: AnimationSession): String =
    "思考 · ${session.reasoningChars} 字"

/**
 * Elapsed time as a compact Chinese label: "42 秒", "2 分 05 秒".
 *
 * Seconds are padded once minutes are in play so the width stops changing — a label that
 * reflows every second is worse than one that is slightly wider.
 */
internal fun animationElapsedLabel(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000).coerceAtLeast(0L)
    return if (seconds < 60) {
        "$seconds 秒"
    } else {
        String.format(java.util.Locale.ROOT, "%d 分 %02d 秒", seconds / 60, seconds % 60)
    }
}

/**
 * How tall an expanded reasoning block may grow, as a whole number of text lines.
 *
 * Expressed in lines rather than dp because the complaint it fixes is a *half* line: the box
 * is a scrollable, so a cap that lands mid-line clips the newest line through the middle
 * vertically. Line height scales with the system font size while a `dp` cap does not, so a
 * fixed 150 dp was already wrong at the default scale (150 / ~17 dp ≈ 8.8 lines) and worse at
 * larger ones. Multiplying the style's own line height by an integer means the clip always
 * falls in the gap between two lines, and [reasoningMaxHeight] turns that into the dp value
 * Compose needs.
 */
internal const val ANIMATION_REASONING_MAX_LINES = 8

/**
 * The dp cap for the reasoning box, from the current text style's line height.
 *
 * The padding is added on top of the whole number of lines, because the padding sits outside the
 * scrollable. Leaving it out shrinks the text viewport by exactly the padding, so the cap lands
 * mid-line again and the newest line is sliced by that much — the same half-glyph in a new
 * disguise. The viewport is what must be a whole number of lines; the box is that plus padding.
 *
 * Falls back to the previous fixed value when the style reports no usable line height, so a
 * theme without `lineHeight` still gets a bounded box rather than an unbounded one.
 */
@Composable
private fun reasoningMaxHeight(): Dp {
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    // `TextUnit.isSpecified` is false for `TextUnit.Unspecified`, which is what a type scale
    // reports when it leaves the line height to the font metrics.
    val lineHeightPx = if (style.lineHeight.isSpecified) {
        with(density) { style.lineHeight.toPx() }
    } else {
        with(density) { style.fontSize.toPx() } * ANIMATION_REASONING_FALLBACK_LINE_FACTOR
    }
    return with(density) { (lineHeightPx * ANIMATION_REASONING_MAX_LINES).toDp() } + ANIMATION_BOX_PADDING
}

/** The inner padding of both streaming boxes; part of their height budget, not extra. */
private val ANIMATION_BOX_PADDING = 10.dp

/** Used only when the type scale declares no line height; the body default is ~1.4× font. */
private const val ANIMATION_REASONING_FALLBACK_LINE_FACTOR = 1.4f

/**
 * Whether the thinking block starts open.
 *
 * Off by default: the chain routinely runs to tens of thousands of characters, and an
 * auto-expanded block pushed the output box below the fold — so the thing the user was
 * waiting for was the thing they could not see. The header row stays visible with the
 * character count, so "it is thinking, here is how much" is still readable at a glance.
 */
internal const val ANIMATION_REASONING_EXPANDED_BY_DEFAULT = false

/** How many lines of the in-flight document the output box shows before it scrolls. */
internal const val ANIMATION_OUTPUT_MAX_LINES = 14

/** The closed-height fraction of that cap: two-fifths, ~6 lines, so a short tail is not padded. */
private const val ANIMATION_OUTPUT_MIN_LINE_FRACTION = 0.4f

/** The source pane's height floor, in whole lines, for the rare very short document. */
internal const val ANIMATION_SOURCE_MIN_LINES = 4

/** The source pane's height cap, in whole lines: same half-line fix as the output box. */
internal const val ANIMATION_SOURCE_MAX_LINES = 14

/**
 * A dp height that is a whole number of monospace lines at the current font scale.
 *
 * The output box's old cap was a flat `180.dp … 320.dp`, which is 10–19 lines at the default
 * scale and fewer at a larger one — so the same half-line problem the reasoning box had
 * applied here too, just at a different number.
 *
 * 返回值按「四边都有 10dp 内边距」的盒子计：内边距在滚动视口外面，Surface 的高度里必须
 * 把上下两段都补上，视口才恰好是整数行。少补一段，视口就短一个 padding，底边切进下一行
 * ——源码窗格 1.7.17 首版正是这么露出半行的（思考框只有 bottom 一段 padding，所以它自己的
 * [reasoningMaxHeight] 只加一份）。
 */
@Composable
private fun monospaceMaxHeight(lines: Int): Dp {
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    val lineHeightPx = if (style.lineHeight.isSpecified) {
        with(density) { style.lineHeight.toPx() }
    } else {
        with(density) { style.fontSize.toPx() } * ANIMATION_REASONING_FALLBACK_LINE_FACTOR
    }
    // Padding included for the same reason as the reasoning box: it sits outside the scrollable,
    // so the viewport — not the outer box — is what has to be a whole number of lines.
    return with(density) { (lineHeightPx * lines).toDp() } + ANIMATION_BOX_PADDING * 2
}

/**
 * Rounding slack when asking whether a scroll reached the end, in pixels.
 *
 * A follow-scroll lands exactly at the end, so this only absorbs the sub-pixel residue the
 * scrollable leaves behind. It is kept small on purpose: it is also the distance a reader can
 * nudge the box upwards while it still counts as "at the end", and a large value would make
 * that nudge snap back visibly.
 */
internal const val FOLLOW_END_SLACK_PX = 8

/**
 * The pin decision implied by one observed scroll position, or null when it implies nothing.
 *
 * A scroll is only the reader's own doing when the content had the same extent before and
 * after: growth changes the extent by itself, and the follow-scroll always lands exactly at
 * the end — so neither can be mistaken for the reader scrolling away. Extracted from the
 * composable because this rule *is* the behaviour the user asked for ("默认跟随最新一行，
 * 但手动上滑就停在原地"), and it is worth testing without a Compose host.
 *
 * Returns the new pinned state, or null to leave it alone.
 */
internal fun followEndObservation(
    offset: Int,
    extent: Int,
    previousExtent: Int,
    slack: Int = FOLLOW_END_SLACK_PX,
): Boolean? {
    // The extent changed: the text grew (or the tail window slid). No reader evidence.
    if (extent != previousExtent) return null
    // Nothing to scroll: the content fits, so it is showing its end by definition.
    if (extent == 0) return true
    return offset >= extent - slack
}

/**
 * A scroll state that keeps the newest line on screen but yields to the reader.
 *
 * The box is asked to show the newest line of text that is still arriving:
 *
 *  - while the reader leaves it alone, every update lands at the end, so the newest line is
 *    fully visible instead of being cut in half at the bottom edge;
 *  - once the reader scrolls up, the box stays where it was put and new text no longer moves
 *    it — the line being read stays on screen;
 *  - scrolling back down to the end resumes following.
 *
 * [contentKey] must change on every update, so a cumulative count
 * ([AnimationSession.reasoningChars] / [AnimationSession.receivedChars]) rather than the text
 * length: the displayed text is a fixed-size tail window, so on a long chain its length stops
 * changing while the text keeps sliding.
 *
 * [onPinnedChange] reports the same decision outward, for the one caller that has to act on it:
 * a tail window *slides* as it grows, so holding the scroll offset is not enough to hold what
 * the reader is looking at. The reasoning box uses this to stop the window trimming while the
 * reader is away from the end (see [AnimationSession.reasoningHeld]).
 */
@Composable
private fun rememberFollowEndScrollState(
    contentKey: Int,
    onPinnedChange: (Boolean) -> Unit = {},
): ScrollState {
    val scroll = rememberScrollState()
    // Following is the resting state: a request that is still running is being watched, not
    // read back.
    val pinned = remember { mutableStateOf(true) }
    // The two effects below run in *different* coroutines, and the pin state is written by one
    // and read by the other. Reading the `mutableStateOf` directly from the reader's closure
    // would capture the value from the composition that started it — and because the growth
    // effect is keyed only on `contentKey`, its coroutine is not restarted when the reader
    // scrolls, so the captured value stayed `true` forever and every new delta re-pinned the box
    // to the bottom. That is what "上滑之后，生成新内容时我的显示内容被同步下移" was.
    // A plain box holding the latest decision is the bridge: the writer updates it on every
    // observation, and the reader reads it *at scroll time* rather than at capture time.
    val pinnedNow = remember { booleanArrayOf(true) }
    // Same reason as `pinnedNow`: the collector below outlives the composition that started it,
    // so it must not capture the lambda from that composition.
    val report = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    report[0] = onPinnedChange
    LaunchedEffect(scroll) {
        var previousExtent = scroll.maxValue
        snapshotFlow { scroll.value to scroll.maxValue }.collect { (offset, extent) ->
            followEndObservation(offset, extent, previousExtent)?.let {
                pinned.value = it
                pinnedNow[0] = it
                report[0]?.invoke(it)
            }
            previousExtent = extent
        }
    }
    LaunchedEffect(contentKey) {
        // Wait for the frame that lays the new text out. Before it, `maxValue` still describes
        // the previous text, and scrolling there would leave the newest line one update below
        // the bottom edge — a line cut in half, which is exactly the report being fixed.
        withFrameNanos { }
        // Re-read after the wait: a drag processed in between outranks this update. Reading the
        // *current* decision (not the one captured when this coroutine started) is what lets a
        // reader's scroll-up win.
        if (pinnedNow[0]) scroll.scrollTo(scroll.maxValue)
    }
    return scroll
}

/**
 * Width-to-height ratio of the result panes: 16:9, the desktop canvas.
 *
 * The animated documents are composed against a desktop viewport, so a pane shaped like a
 * phone screen cuts a wide scene off at the sides. A fixed ratio is used rather than a
 * fill-the-height box because it gives the WebView a predictable viewport to fit into, and
 * the page is laid out at desktop width and zoomed to fit ([WebView.configureSandbox]), so
 * the whole document is visible inside it.
 */
private const val ANIMATION_PREVIEW_ASPECT_RATIO = 16f / 9f

/**
 * Smallest height the preview may take, whatever the ratio works out to.
 *
 * Ratio-only sizing made the pane 231 dp tall on a 411 dp-wide phone, and the sandbox lays the
 * page out at a desktop width and zooms the result down to fit — so a scene with small details
 * shrank until it could not be read ("我画的图都看不全" was the same complaint in the other
 * direction: first cut off at the sides, then too small to see). The earlier code carried this
 * same floor and it was inert, because a `heightIn` chained *after* `aspectRatio` is clamped
 * away by the ratio's own height — the comment recording that is what made the floor look like
 * the problem, when the problem was where it was applied. The floor has to constrain the ratio
 * itself, which is what [animationPreviewAspectRatio] does.
 */
private val ANIMATION_PREVIEW_MIN_HEIGHT = 220.dp

/**
 * The preview's aspect ratio, raised when the plain 16:9 would come out shorter than the floor.
 *
 * Both panes take their height from the width, so the floor is expressed as a smaller ratio
 * instead. On a 411 dp-wide phone 16:9 gives 231 dp and the floor asks for about 1.27; above
 * roughly 640 dp of width 16:9 is already taller than the floor and is returned untouched, so a
 * tablet still gets the full desktop canvas.
 */
internal fun animationPreviewAspectRatio(
    width: Dp,
    minHeight: Dp,
): Float {
    if (width.value <= 0f || minHeight.value <= 0f) return ANIMATION_PREVIEW_ASPECT_RATIO
    return minOf(ANIMATION_PREVIEW_ASPECT_RATIO, width.value / minHeight.value)
}

/**
 * How long the last generation took, or null when it never settled.
 *
 * Null is not zero: a record that never ran, or whose request is still in flight, has no
 * duration to report, and printing "耗时 0 秒" would invent one.
 */
internal fun AnimationSession.generationDurationMs(): Long? {
    val started = startedAt ?: return null
    val settled = settledAt ?: return null
    return (settled - started).coerceAtLeast(0L)
}

/** Progress, cancellation state and the failure reason, all inside the panel. */
@Composable
private fun AnimationStatusSection(session: AnimationSession) {
    when (session.phase) {
        AnimationPhase.GENERATING -> Unit

        AnimationPhase.READY -> if (session.error != null) {
            AnimationNotice(session.error, isError = true)
        }

        AnimationPhase.FAILED -> session.error?.let { AnimationNotice(it, isError = true) }

        AnimationPhase.EDITING -> if (session.error != null) {
            AnimationNotice(session.error, isError = true)
        }
    }
}

@Composable
private fun AnimationNotice(message: String, isError: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            message,
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * The preview or the source, never both and never the source through a WebView.
 *
 * The source view is a plain selectable text block: running the model's output as code to
 * display it would defeat the whole point of having a restricted preview.
 */
@Composable
private fun AnimationResultSection(
    session: AnimationSession,
    foreground: Boolean,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    /** The live preview WebView, or null while there is none to record. */
    previewView: View?,
    onPreviewViewChange: (View?) -> Unit,
    recording: Boolean,
    /** Hands the list its thumbnail once the rendered result has been captured. */
    onSnapshot: (ByteArray) -> Unit,
    onViewChange: (AnimationView) -> Unit,
    onRenderFailure: (String) -> Unit,
) {
    val html = session.html ?: return
    // Capture the picture the list row shows, once an un-captured successful result is on
    // screen. Two conditions carry this, and both are load-bearing:
    //  * `session.view == PREVIEW`. Only the preview branch composes the WebView, so in 源码
    //    there is nothing to draw — and the key below includes the view for exactly that
    //    reason. Named explicitly rather than inferred from `previewView != null`, so a future
    //    change that keeps the view alive in the background cannot start photographing a view
    //    the user is not looking at.
    //  * `previewView != null`, which is the same statement at the level of the actual View:
    //    the preview hands it up on attach and null on release, so a destroyed WebView is never
    //    drawn.
    // The delay is not decoration either: `view.draw` right after the composable appears
    // catches a WebView that has laid out but not painted, i.e. a blank frame. The effect only
    // runs while there is still no picture, so returning to a captured record costs nothing.
    LaunchedEffect(
        session.sessionId,
        session.snapshot == null,
        session.view,
        previewView != null,
        html,
    ) {
        if (session.snapshot != null) return@LaunchedEffect
        if (session.view != AnimationView.PREVIEW) return@LaunchedEffect
        val view = previewView ?: return@LaunchedEffect
        delay(ANIMATION_SNAPSHOT_DELAY_MS)
        AnimationRecorder.captureStill(view)?.let(onSnapshot)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("结果", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            // 生成耗时留在结果上：等待时它是「还要不要等」的依据，拿到结果后它是这次生成
            // 的成本。两者是同一个数字，所以用的是同一段格式。
            session.generationDurationMs()?.let { duration ->
                Text(
                    "耗时 ${animationElapsedLabel(duration)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            ViewToggle(
                current = session.view,
                onSelect = onViewChange,
            )
        }
        when (session.view) {
            AnimationView.PREVIEW -> BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // A desktop canvas, not a tall phone slab. The prompt asks the model for a
                // scene laid out for a desktop preview area, and a scene drawn to a wide
                // viewBox shown in a narrow portrait box is cropped at the sides — which is
                // what "我画的图都看不全" was. 16:9 is the ratio those documents are composed
                // against, and the WebView is told to lay the page out at a desktop width and
                // zoom the whole thing to fit (`useWideViewPort` + `loadWithOverviewMode`), so
                // the entire document is visible at once instead of only its top-left corner.
                //
                // The floor is folded into the ratio rather than chained as a `heightIn`,
                // because a `heightIn` after `aspectRatio` is clamped away by the ratio's own
                // height: on a 411 dp phone that would be a 231 dp pane with the floor doing
                // nothing, and the zoomed-down page would be too small to read. See
                // [animationPreviewAspectRatio] for the arithmetic.
                val paneRatio = animationPreviewAspectRatio(maxWidth, ANIMATION_PREVIEW_MIN_HEIGHT)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(paneRatio)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.shapes.small,
                        ),
                ) {
                    if (foreground) {
                        AnimationPreview(
                            html = html,
                            replayToken = session.replayToken,
                            onRenderFailure = onRenderFailure,
                            onLoadError = onRenderFailure,
                            // The recorder needs the actual WebView: a WebView has no frame
                            // API, so the only way to sample the animation is to draw the
                            // view that is on screen. This is also the teardown signal — null
                            // arrives before `releasePreview`, so a recording can never draw
                            // a destroyed WebView.
                            onViewAvailable = onPreviewViewChange,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        // 切到后台就暂停动画：循环动画在后台继续跑会一直吃 CPU。文档仍
                        // 加载在案，回到前台就继续，不需要重新生成。
                        Text(
                            "动画已暂停（应用不在前台）",
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 录制要 2 秒，期间预览照常播放（录的就是它在动），只叠一层遮罩说明
                    // 正在进行，免得用户以为点空了又点一次。
                    if (recording) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = Color.White,
                                    strokeWidth = 3.dp,
                                )
                                Text(
                                    "正在录制动图…",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }

            AnimationView.SOURCE -> {
                // 与思考框、输出框同一条整行规则：高度是"整行数 × 行高 + 内边距"，内边距在
                // 滚动视口**外面**。之前这里用 aspectRatio 匹配预览窗格的高度——比例值几乎
                // 总是落在某一行文字的中间，于是最后一行被切成半行；padding 又写在
                // verticalScroll 之后（即滚动内容内部），一滚动上边距就跟着内容走掉了，
                // 上下页边距永远不一样。生成出来的源码几乎总是长文档，实际高度都会顶到
                // 上限；min 只防极短文档时窗格塌成一行。
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(
                            min = monospaceMaxHeight(ANIMATION_SOURCE_MIN_LINES),
                            max = monospaceMaxHeight(ANIMATION_SOURCE_MAX_LINES),
                        ),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    SelectionContainer {
                        Text(
                            // 源码按代码上色：标签、属性、字符串、注释各有其色，大文档
                            // 由 MAX_SPANS 封顶，超出部分保持纯文本。换行显示（与思考框、
                            // 输出框一致）：实测单行 HTML 配横向滚动时整块面板只剩一行字，
                            // 手机上读不了代码；去掉横向滚动后长行自动折行，纵向滚动阅读。
                            text = rememberCodeText(html) { markupSpans(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                                .clipToBounds()
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/** Preview / source switch, as two small buttons rather than a segmented control. */
@Composable
private fun ViewToggle(current: AnimationView, onSelect: (AnimationView) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ToggleChip("预览", current == AnimationView.PREVIEW) { onSelect(AnimationView.PREVIEW) }
        ToggleChip("源码", current == AnimationView.SOURCE) { onSelect(AnimationView.SOURCE) }
    }
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = Modifier.heightIn(min = 40.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.heightIn(min = 40.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
    }
}

/**
 * The bottom action row, outside the WebView so a page cannot cover or imitate it.
 *
 * 开始生成 / 重新生成 are the only controls that cost money, so they are the only ones
 * that call the model; 重新播放 and 复制 are free and stay available while a preview exists.
 */
@Composable
private fun AnimationActionBar(
    session: AnimationSession,
    canSubmit: Boolean,
    density: androidx.compose.ui.unit.Density,
    onStart: () -> Unit,
    onRegenerate: () -> Unit,
    onCancel: () -> Unit,
    onReplay: () -> Unit,
    onCopy: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onShareGif: () -> Unit,
) {
    val generating = session.phase == AnimationPhase.GENERATING
    val hasHtml = session.html != null
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 320dp / 大字号下四个动作挤不进一行：主生成动作独占一行，次要动作另排一行。
        // 与高级目录的操作栏同一条规则、同一个阈值。
        val stacked = maxWidth < ACTION_BAR_STACK_WIDTH ||
            density.fontScale >= ACTION_BAR_STACK_FONT_SCALE
        if (stacked) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrimaryAnimationAction(
                    session = session,
                    canSubmit = canSubmit,
                    onStart = onStart,
                    onRegenerate = onRegenerate,
                    onCancel = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryAnimationActions(
                    hasHtml = hasHtml,
                    generating = generating,
                    onReplay = onReplay,
                    onCopy = onCopy,
                    onOpenInBrowser = onOpenInBrowser,
                    onShareGif = onShareGif,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                // 12dp：四个次要按钮一组、主按钮一个，两组之间留出可见的间隔。8dp 是组内
                // 按钮的距离，组间更宽才读得出"这是一组、那是一个"。
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryAnimationActions(
                    hasHtml = hasHtml,
                    generating = generating,
                    onReplay = onReplay,
                    onCopy = onCopy,
                    onOpenInBrowser = onOpenInBrowser,
                    onShareGif = onShareGif,
                    modifier = Modifier.weight(1f),
                )
                PrimaryAnimationAction(
                    session = session,
                    canSubmit = canSubmit,
                    onStart = onStart,
                    onRegenerate = onRegenerate,
                    onCancel = onCancel,
                )
            }
        }
    }
}

@Composable
private fun PrimaryAnimationAction(
    session: AnimationSession,
    canSubmit: Boolean,
    onStart: () -> Unit,
    onRegenerate: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val generating = session.phase == AnimationPhase.GENERATING
    // 高度与四个次要按钮一致（min 44dp）：1.7.16 时这里是 48dp，一排按钮里主按钮独自
    // 高出一截，用户指出样式不统一。填充色保留——它是唯一的付费动作，颜色是强调层级，
    // 统一的是尺寸与圆角，不是让主按钮也退成描边样式。
    if (generating) {
        OutlinedButton(
            onClick = onCancel,
            modifier = modifier.heightIn(min = PRIMARY_ACTION_MIN_HEIGHT),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("取消生成")
        }
        return
    }
    val hasHtml = session.html != null
    Button(
        onClick = if (hasHtml) onRegenerate else onStart,
        enabled = canSubmit,
        modifier = modifier.heightIn(min = PRIMARY_ACTION_MIN_HEIGHT),
    ) {
        Text(if (hasHtml) "重新生成" else "开始生成", maxLines = 1, softWrap = false)
    }
}

@Composable
private fun SecondaryAnimationActions(
    hasHtml: Boolean,
    generating: Boolean,
    onReplay: () -> Unit,
    onCopy: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onShareGif: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 四个动作只剩图标：文案搬进无障碍语义（读屏照旧念得出哪个动作），画面里一排四个
    // 正方形方按钮。等宽来自**固定边长**，不是 weight：weight 的宽度由父级分配，窄屏的
    // 两行布局里那一行会被拉满，四个方按钮跟着涨成 70dp 的大方格，与同屏 36dp 的主按钮
    // 一比就是「大小不统一」（用户截图里正是这样）。固定 44dp 后无论哪种布局都等大，
    // 也仍然是 Material 的最小触摸目标。
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SECONDARY_ACTION_GAP),
    ) {
        AnimationActionButton(
            Modifier.size(SECONDARY_ACTION_SIZE),
            Icons.Outlined.Replay,
            "重新播放",
            enabled = hasHtml,
            onClick = onReplay,
        )
        AnimationActionButton(
            Modifier.size(SECONDARY_ACTION_SIZE),
            Icons.Outlined.ContentCopy,
            "复制源码",
            enabled = hasHtml,
            onClick = onCopy,
        )
        AnimationActionButton(
            Modifier.size(SECONDARY_ACTION_SIZE),
            Icons.Outlined.OpenInBrowser,
            "浏览器打开",
            enabled = hasHtml,
            onClick = onOpenInBrowser,
        )
        AnimationActionButton(
            Modifier.size(SECONDARY_ACTION_SIZE),
            Icons.Outlined.Share,
            "分享动图",
            // 分享录的是预览里那个正在跑的 WebView，没有它就录不出东西。
            enabled = hasHtml && !generating,
            onClick = onShareGif,
        )
    }
}

/**
 * One of the four secondary actions: a square icon button — no label, no border, no ripple.
 *
 * [label] is not drawn anywhere; it is the icon's accessibility name, so the four controls
 * still announce themselves to a screen reader. The border and the press ripple both came
 * out on the user's call: an outlined button plus Material's pressed state layer left a
 * translucent "ghost" behind on the toolbar that read as a selection which never went away.
 * [QuietIconButton] is the same no-ripple construction the dimmed press targets already use.
 */
@Composable
private fun AnimationActionButton(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    QuietIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.aspectRatio(1f),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                // 与 Material 自己的禁用前景一致（按钮禁用时内容色 = onSurface 38%）：
                // 换成别的灰会让「不可用」和「图标本来就淡」分不出来。
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(SECONDARY_ACTION_ICON_SIZE),
        )
    }
}

/** 主生成按钮的可见高度；触摸高度仍由 Material 的 48dp 目标兜底。 */
private val PRIMARY_ACTION_MIN_HEIGHT = 36.dp

/** 四个次要动作按钮的边长：固定的等边方块，也是触摸目标的下限。 */
internal val SECONDARY_ACTION_SIZE = 44.dp

/** 方按钮之间以及图标与按钮边缘的留白。 */
private val SECONDARY_ACTION_GAP = 10.dp
private val SECONDARY_ACTION_ICON_SIZE = 22.dp

/**
 * The highlighted text for a code view, computed once per (text, theme) pair.
 *
 * The spans come from the caller's tokenizer ([markupSpans] for documents, [reasoningSpans]
 * for the chain of thought); the palette follows the theme's surface brightness so the same
 * token colours read on both. Remembered on the text itself: a streamed tail changes every
 * delta but is small, and the source view's large document is highlighted once per document.
 */
@Composable
private fun rememberCodeText(
    text: String,
    spansOf: (String) -> List<CodeSpan>,
): AnnotatedString {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(text, dark) { annotatedCode(text, spansOf(text), codePalette(dark)) }
}

/** Same stacking floor as the catalog dialog's action bar, for the same reason. */
private val ACTION_BAR_STACK_WIDTH = 342.dp

/** At this font scale or above the action bar stacks into two rows. */
private const val ACTION_BAR_STACK_FONT_SCALE = 1.3f
