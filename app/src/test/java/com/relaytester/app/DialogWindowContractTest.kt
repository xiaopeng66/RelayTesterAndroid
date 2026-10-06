package com.relaytester.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contracts that only the sources can pin, because no JVM test has the thing they describe:
 * the dialogs' window geometry and dismiss scrims, the notes box's ceiling, and the activity
 * side of the update page that opens itself.
 *
 * Neither can be caught from here as behaviour: a JVM test has no Android window, so it cannot
 * measure where a dialog puts its content or watch a tap land outside the card. What it *can* do
 * is refuse the two source facts that produce the two bugs, which is what this file is for.
 *
 * 1. A dialog content root that is not sized by
 *    [com.relaytester.app.ui.components.rememberDialogWindowBox]. These dialogs keep
 *    `usePlatformDefaultWidth = false` (the 94%-wide card needs it), which also makes Compose
 *    measure the content against the whole **display** (2400px here) and ask for a window that
 *    tall — while the window can only be granted the area between the bars (`[0,128]-[1080,2337]`,
 *    measured). A root that fills the screen is therefore 191px taller than its window and its
 *    bottom is clipped: the card sits visibly low — centre measured 1328 instead of the visible
 *    centre 1232 — and anything pinned to the bottom of the box, like the tip card under the
 *    update check, lands at y 2402-2528, off the screen entirely. That is the
 *    「提示卡藏在了界面下边」 report.
 *
 * 2. A raw `Dialog` without a dismiss scrim. The platform reports an outside tap only while the
 *    tap is inside the window but outside the content; every dialog here fills its window, so
 *    `dismissOnClickOutside` never fires and the card cannot be closed by tapping outside it.
 *    Each raw dialog therefore draws its own
 *    [com.relaytester.app.ui.components.DialogDismissScrim], one per dialog.
 */
class DialogWindowContractTest {

    private val sourceRoot: File by lazy { locateSourceRoot() }
    private val sources: List<File> by lazy {
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.sorted().toList()
    }

    /**
     * The main source root, found by walking up from the test's working directory.
     *
     * Gradle runs these tests with the module directory (`app/`) as the working directory,
     * so `src/main/java` is the usual hit; the `app/src/main/java` spelling covers a run from
     * the repository root. Not finding either is an error rather than a skip: a contract test
     * that quietly checks nothing is worse than no contract test.
     */
    private fun locateSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, "src/main/java").takeIf { it.isDirectory }?.let { return it }
            File(dir, "app/src/main/java").takeIf { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        error("找不到 main 源码目录（user.dir=${System.getProperty("user.dir")}）")
    }

    /** Source text with comments removed, so prose about a flag is not read as the flag. */
    private fun code(file: File): String {
        val withoutBlocks = file.readText(Charsets.UTF_8)
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
        return withoutBlocks.lines().joinToString("\n") { it.substringBefore("//") }
    }

    @Test
    fun `every raw dialog sizes its box to the window it gets`() {
        assertTrue("源码根目录为空：$sourceRoot", sources.isNotEmpty())
        val rawDialog = Regex("(?m)^\\s*Dialog\\(")
        val sizedBox = "rememberDialogWindowBox()"
        val offenders = sources.mapNotNull { file ->
            val text = code(file)
            val dialogs = rawDialog.findAll(text).map { it.range.first }.toList()
            if (dialogs.isEmpty()) {
                // The helper's own file declares it and hosts no dialog.
                null
            } else {
                val boxes = generateSequence(text.indexOf(sizedBox)) { from ->
                    text.indexOf(sizedBox, from + 1).takeIf { it >= 0 }
                }.toList()
                val problems = mutableListOf<String>()
                if (boxes.size != dialogs.size) {
                    problems += "${file.name}: ${dialogs.size} 个原生弹窗 / ${boxes.size} 个定尺盒"
                }
                // 盒子的尺寸要在弹窗**外面**算：弹窗内容跑在它自己那个窗口的组合里，内边距一律
                // 读成 0，算出来就是整屏高。所以每处调用必须落在它那个 Dialog 之前。
                dialogs.forEachIndexed { index, dialog ->
                    val previous = if (index == 0) -1 else dialogs[index - 1]
                    if (boxes.none { it in (previous + 1) until dialog }) {
                        problems += "${file.name}: 第 ${index + 1} 个 Dialog 之前没有 rememberDialogWindowBox()"
                    }
                }
                problems.takeIf { it.isNotEmpty() }?.joinToString("；")
            }
        }
        assertEquals(
            "每个原生 Dialog 的内容盒都要用 rememberDialogWindowBox()（在弹窗外面调用）按窗口" +
                "定尺寸：不这么做内容盒按整屏测量，贴底的提示卡被裁到屏幕外（关于与更新那条" +
                "就是这么丢的）。",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `every raw dialog carries a dismiss scrim`() {
        assertTrue("源码根目录为空：$sourceRoot", sources.isNotEmpty())
        val rawDialog = Regex("(?m)^\\s*Dialog\\(")
        val scrim = "DialogDismissScrim("
        val offenders = sources.mapNotNull { file ->
            val text = code(file)
            val dialogs = rawDialog.findAll(text).count()
            if (dialogs == 0) {
                // The scrim's own file declares the composable and hosts no dialog.
                null
            } else {
                val scrims = text.split(scrim).size - 1
                if (dialogs == scrims) null else "${file.name}: $dialogs 个原生弹窗 / $scrims 个遮罩"
            }
        }
        assertEquals(
            "每个原生 Dialog 都要配一层自己画的遮罩（内容铺满窗口时平台不会上报「窗口内、卡片外」" +
                "的点击），否则点卡片外关不掉。",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the window box matches the window on both axes`() {
        // 宽和高一起才等于「系统给的那块窗口」。竖屏时左右系统栏都是 0，两条算式等价；横屏时
        // 竖屏的状态栏转到左边（本机 48.8dp = 128px），只按高度收的话 fillMaxWidth 拿到的是
        // 整屏宽（内容按整屏测量），占屏宽 94% 的卡片量到 200..2456——右边 21dp 连同圆角切在
        // 屏幕外。JVM 测试量不到窗口，只能钉住这两行算式。
        val file = File(sourceRoot, "com/relaytester/app/ui/components/DialogWindowBox.kt")
        assertTrue("找不到 DialogWindowBox.kt：${file.absolutePath}", file.isFile)
        val lines = code(file).lines().map(String::trim)
        val text = lines.joinToString("\n")
        assertTrue(
            "窗口盒没有按左右系统栏收宽：横屏下卡片右缘会被屏幕切掉",
            ".width(configuration.screenWidthDp.dp - barsWidth)" in lines,
        )
        assertTrue(
            "窗口盒没有按上下系统栏收高：贴底的提示卡会被裁到屏幕外",
            ".height(configuration.screenHeightDp.dp - barsHeight)" in lines,
        )
        assertTrue(
            "左右系统栏没有来源：val barsWidth = with(density) { ... }",
            "val barsWidth = with(density) {" in text,
        )
        assertTrue(
            "窗口盒读的不是安全绘图区：横屏时挖孔那块（本机 128px）不算进去，卡片右缘照样被切",
            "val insets = WindowInsets.safeDrawing" in text,
        )
    }

    @Test
    fun `the update dialog's notes box keeps a ceiling and its own scroll`() {
        // 说明文本来自清单，是远程输入，可以带上千字：没有上限它会把卡片撑满，把按钮和自动
        // 检查开关顶到看不见的地方——而弹窗本身还压着「屏高 90%」那条线。JVM 测试量不到高度，
        // 但可以钉住产生这个缺陷的两个源码事实：上限真的挂在这个盒子上，且它自己滚。
        val file = File(sourceRoot, "com/relaytester/app/feature/update/UpdateDialog.kt")
        assertTrue("找不到 UpdateDialog.kt：${file.absolutePath}", file.isFile)
        val lines = code(file).lines().map(String::trim)
        val text = lines.joinToString("\n")
        val capped = lines.indexOfFirst { it.startsWith(".heightIn(max = updateNotesBoxMaxHeight(") }
        assertTrue(
            "UpdateDialog.kt 的说明盒没有高度上限：说明一长卡片就被撑满",
            capped >= 0,
        )
        assertEquals(
            "说明盒的高度上限后面必须紧跟它自己的滚动（滚动状态用局部变量 scroll）",
            ".verticalScroll(scroll)",
            lines.getOrNull(capped + 1),
        )
        assertTrue(
            "说明盒的滚动状态没有来源：val scroll = rememberScrollState()",
            "val scroll = rememberScrollState()" in text,
        )
    }

    @Test
    fun `the activity opens the update page when a check nobody asked for finds one`() {
        // 自动检查发现新版本时「关于与更新」要自己弹出来——这一步发生在 Activity 里，JVM 测试
        // 跑不了 Compose，设备走查也不可能每次回归都做，所以钉住产生这条行为的源码事实：信号
        // 被一个以它为键的 LaunchedEffect 读到、读到就打开页面、并且立刻消费掉（一次性）。
        val file = File(sourceRoot, "com/relaytester/app/MainActivity.kt")
        assertTrue("找不到 MainActivity.kt：${file.absolutePath}", file.isFile)
        val text = code(file)
        val block = Regex(
            "(?s)LaunchedEffect\\(appUpdateState\\.autoOpenUpdate\\)\\s*\\{(.*?)\\n {8}\\}",
        ).find(text)?.groupValues?.get(1)
        assertNotNull(
            "MainActivity 里没有消费自动弹窗信号（appUpdateState.autoOpenUpdate）的 LaunchedEffect：" +
                "后台检查发现新版本时「关于与更新」不会自己弹出来",
            block,
        )
        assertTrue(
            "读到信号却没有打开「关于与更新」（showUpdates = true）",
            "showUpdates = true" in block!!,
        )
        assertTrue(
            "信号没有被消费：残留的一次性标志会在下一次重组或转屏时把页面再弹一次",
            "appUpdateViewModel.consumeAutoOpen()" in block,
        )
    }

    @Test
    fun `the drag converts its travel into rows, not into a fixed step`() {
        // 拖动的换位判据是「位移除以行高」，行高来自测量。JVM 测试没有指针也没有布局，
        // SupplierOrderTest 只能测原语，测不到这行换算——把它写死成 0（或任何常数）时那些
        // 测试仍然是绿的，而排序在设备上会彻底失灵。所以这里钉住的是那行换算本身：它必须读
        // 一个变化量、除以一个测量出来的高度，并且用四舍五入（半个行高＝两行中点）。
        val file = File(sourceRoot, "com/relaytester/app/feature/tester/TesterScreen.kt")
        assertTrue("找不到 TesterScreen.kt：${file.absolutePath}", file.isFile)
        val text = code(file)
        assertTrue(
            "排序面板里没有「位移 ÷ 行高」的换算：跨半行就换位这条手感失去了依据",
            Regex("""val steps = \(travelled / row\)\.roundToInt\(\)""").containsMatchIn(text),
        )
        assertTrue(
            "行高没有从测量拿到（onSizeChanged）：换算会一直用 0，拖动完全不动",
            "onSizeChanged { rowHeightPx = it.height }" in text,
        )
        assertTrue(
            "每次手势开始没有把位移归零：上一次的残留会让第一次换位提前触发",
            "travelled = 0f" in text,
        )
        assertTrue(
            "拖动方向丢了符号：abs 之后的步数分不出往上还是往下，往上拖会与下一行换位",
            "onMoveOver(supplier.id, steps)" in text,
        )
    }

    @Test
    fun `the drag handle is the whole row, not the grip icon`() {
        // 拖动「拖不动」的根因就在这里：手势原本挂在右侧那个手柄图标上，而它只有 44dp 宽。
        // 手指按住它再拖，起手的一点抖动就滑出它的边界，手势当场取消——表现就是按下去不动。
        // 修法是把 pointerInput 挂到整行。JVM 里量不到命中区，能钉住的是源码里的顺序：
        // dragModifier 必须加在行这个容器上，而右端那个图标只剩 contentDescription = null 的
        // 装饰身份（它不是把手，是「这里可以拖」的提示）。
        val file = File(sourceRoot, "com/relaytester/app/feature/tester/TesterScreen.kt")
        val text = code(file)
        assertTrue(
            "拖动手势没有挂在整行上：手柄只有 44dp，按住它拖会滑出边界，拖动又会断",
            ".then(dragModifier)" in text,
        )
        assertTrue(
            "行上的拖动语义没有声明：读屏用户听不到这一行可以拖动排序",
            "contentDescription = \"拖动排序 " in text,
        )
        assertTrue(
            "行高没有固定：名字长短不一时行高参差，「位移 ÷ 行高」的换算随之失准",
            "SUPPLIER_ORDER_ROW_HEIGHT" in text,
        )
    }

    @Test
    fun `saving a supplier closes its editor and toasts on the page`() {
        // 两个编辑弹窗（供应商配置、余额查询凭据）保存成功后必须自己关，提示条画在页面
        // 这一层——同一个 StatusToast、同一个位置。以前提示条要么住在弹窗里（跟着弹窗消失），
        // 要么走页面底部的 snackbar（被还开着的弹窗压在遮罩下），两边规则还不一致。
        // JVM 量不到窗口层次，钉得住的是源码事实：保存回调里两行相邻的「记时间戳＋关弹窗」，
        // 以及两页各有一个页面级的 StatusToast。
        val tester = code(File(sourceRoot, "com/relaytester/app/feature/tester/TesterScreen.kt"))
        val balance = code(File(sourceRoot, "com/relaytester/app/feature/balance/BalanceScreen.kt"))
        assertTrue(
            "供应商配置保存成功后没有关弹窗：用户还得再点一次关闭",
            Regex("""saveCompletedAt = System\.currentTimeMillis\(\)\s*configurationSupplierId = null""")
                .containsMatchIn(tester),
        )
        assertTrue(
            "余额凭据保存成功后没有关弹窗：用户还得再点一次关闭",
            Regex("""credentialsSavedAt = System\.currentTimeMillis\(\)\s*credentialsSupplierId = null""")
                .containsMatchIn(balance),
        )
        assertEquals(
            "「供应商配置已保存」应当只出现一次（页面层的提示条）；弹窗里再画一份就是旧规则复活",
            1, tester.split("供应商配置已保存").size - 1,
        )
        assertTrue(
            "模型测试页没有用共享的 StatusToast：两条保存提示的显示规则又分家了",
            "StatusToast(" in tester,
        )
        assertTrue(
            "余额查询页没有用共享的 StatusToast：两条保存提示的显示规则又分家了",
            "StatusToast(" in balance,
        )
        // Scaffold 的 content 层从窗口顶算起、不透明的顶栏画在它上面：提示条只写
        // top = 4.dp 时整条藏在顶栏背后，保存成功屏幕上什么也没发生（设备实测：
        // 6 秒录屏里弹窗关闭后连续 5.5 秒零像素变化）。纵坐标必须叠上 innerPadding。
        val toastTop = Regex("""\.align\(Alignment\.TopCenter\)\s*\.padding\(top = innerPadding\.calculateTopPadding\(\) \+ 4\.dp\)""")
        assertTrue(
            "模型测试页的保存提示条没叠 innerPadding：它会被顶栏盖住，保存成功也看不见",
            toastTop.containsMatchIn(tester),
        )
        assertTrue(
            "余额查询页的保存提示条没叠 innerPadding：它会被顶栏盖住，保存成功也看不见",
            toastTop.containsMatchIn(balance),
        )
    }

    @Test
    fun `reordering animates the rows it swaps`() {
        // 顺序是立刻换的（拖动必须跟手），但没有动画时第二行是「跳」过去的：用户看到顺序变了，
        // 看不出是谁换到了哪。animateItem 需要稳定的 key，而 key 已经在的话它就只做这一件事。
        val file = File(sourceRoot, "com/relaytester/app/feature/tester/TesterScreen.kt")
        val text = code(file)
        assertTrue(
            "排序行没有换位动画：两行交换位置时是跳过去的，看不出谁换到了哪",
            ".animateItem()" in text,
        )
        assertTrue(
            "排序列表没有稳定 key：animateItem 无从判断哪一行移动了",
            "key = { _, supplier -> supplier.id }" in text,
        )
    }

    // ---- 2026-10-06 审查修复的源码事实 ------------------------------------

    @Test
    fun `the unified run keeps a way out from the main screen`() {
        // 统一测试跑着时，关掉「高级测试」弹窗后主界面必须有回弹窗的路：以前这个按钮
        // 一起被禁，进度看不见、取消点不到，主界面所有按钮可点却全是静默无效。
        val tester = code(File(sourceRoot, "com/relaytester/app/feature/tester/TesterScreen.kt"))
        assertTrue(
            "统一测试运行中入口被禁用：关掉弹窗后用户没有任何办法回去取消",
            "enabled = enabled || isUnifiedTesting" in tester,
        )
        assertTrue(
            "统一测试运行中主界面没有取消入口",
            "取消统一测试" in tester,
        )
        assertTrue(
            "「统一测试中…」这个状态提示不存在：入口还是写着「高级测试」，看不出它在跑",
            "统一测试中…" in tester,
        )
    }

    @Test
    fun `controls that the view model refuses are also disabled on screen`() {
        // ViewModel 的守卫（runningOrInitializing）同时拒绝批量测试与统一测试；界面只
        // 认 isRunning 时，按钮/勾选框亮着而点击被吞 = 「点得动、没反应」。
        val tester = code(File(sourceRoot, "com/relaytester/app/feature/tester/TesterScreen.kt"))
        assertTrue(
            "结果区的勾选/全选只认 isRunning：统一测试进行中它们是假的",
            "val selectionLocked = state.isRunning || state.isUnifiedTesting" in tester,
        )
        assertTrue(
            "重测/导出只认 isRunning：统一测试进行中它们会被 ViewModel 丢回去",
            "val runLocked = state.isRunning || state.isUnifiedTesting" in tester,
        )
        assertTrue(
            "结果行的勾选框没有认统一测试",
            "enabled = !state.isRunning && !state.isUnifiedTesting" in tester,
        )
    }

    @Test
    fun `the balance tab says why a site switch is refused instead of silently dropping it`() {
        // 余额页只收 balanceUiState，看不见模型测试在跑；切换又要重写草稿与凭据，会被
        // ViewModel 拒掉。以前卡片亮着、点了没反应——现在拒绝时说出来。
        val balance = code(File(sourceRoot, "com/relaytester/app/feature/balance/BalanceScreen.kt"))
        assertTrue(
            "余额页没有把模型测试的运行状态收进来",
            "val testerState by viewModel.uiState.collectAsStateWithLifecycle()" in balance,
        )
        assertTrue(
            "被拒的切换没有告诉用户原因",
            "模型测试正在运行，暂时不能切换供应商" in balance,
        )
    }

    @Test
    fun `the two editors arm their dialog id only after the switch lands`() {
        // 两个编辑入口以前先记 id 再切：切换被拒/失败时 id 留着，之后这个站点因别的
        // 原因变成活动站点，编辑弹窗会自己蹦出来。修复＝先切、回报成功才记。
        val tester = code(File(sourceRoot, "com/relaytester/app/feature/tester/TesterScreen.kt"))
        val balance = code(File(sourceRoot, "com/relaytester/app/feature/balance/BalanceScreen.kt"))
        assertTrue(
            "供应商配置入口又变成先记 id 再切了",
            Regex("""selectSupplier\(supplierId\) \{ activated ->\s*if \(activated\) configurationSupplierId = supplierId""")
                .containsMatchIn(tester),
        )
        assertTrue(
            "余额凭据入口又变成先记 id 再切了",
            Regex("""onSelectSupplier\(supplierId\) \{ activated ->\s*if \(activated\) credentialsSupplierId = supplierId""")
                .containsMatchIn(balance),
        )
    }

    @Test
    fun `the catalog fetch answers inside the dialog that asked`() {
        // 拉取失败只写页面级 message 时，消息被弹窗自己的窗口盖住：用户看到转圈结束后
        // 什么也没发生。结论要落在弹窗渲染的那条状态上——并且要在同一次拉取的结论上，
        // 不能只在有检索结果时才渲染（检索区只在拿到结果后出现）。
        val tester = code(File(sourceRoot, "com/relaytester/app/feature/tester/TesterScreen.kt"))
        val vm = code(File(sourceRoot, "com/relaytester/app/feature/tester/TesterViewModel.kt"))
        assertTrue(
            "拉取结论没有落在弹窗自己的状态行上",
            "catalogPickerMessage = result.error.message" in vm,
        )
        assertTrue(
            "拉取结论没有被弹窗渲染出来",
            Regex("""if \(!state\.isCatalogFetching && state\.catalogPickerMessage != null\) \{\s*Text\(\s*state\.catalogPickerMessage,""")
                .containsMatchIn(tester),
        )
    }

    @Test
    fun `destructive deletes confirm first, on every surface`() {
        // 「删除检测包」以前点一下就执行，是全 App 唯一没有确认的破坏性动作；同页
        // 「清空历史」、别处的删除供应商/模板/模型来源都有。合同＝它也得先问一句。
        val fingerprint = code(File(sourceRoot, "com/relaytester/app/feature/fingerprint/FingerprintScreen.kt"))
        assertTrue(
            "删除检测包又没有二次确认了",
            "删除检测包？" in fingerprint,
        )
        assertTrue(
            "确认框的确认按钮没有接回真正的删除",
            Regex("""confirmRemove = false\s*onRemovePackage\(\)""").containsMatchIn(fingerprint),
        )
        assertTrue(
            "删除按钮又直接调 onRemovePackage 了（确认框成了摆设）",
            "onClick = onRemovePackage," !in fingerprint,
        )
    }

    @Test
    fun `rotation keeps the confirmations and the multi-selection`() {
        // 这些确认框/勾选态以前用 remember：弹窗活着（saveable 的兄弟态）而框里的东西
        // 旋转后没了，看起来像确认框自己消失了。与同文件的 saveable 状态对齐。
        val tester = code(File(sourceRoot, "com/relaytester/app/feature/tester/TesterScreen.kt"))
        val balance = code(File(sourceRoot, "com/relaytester/app/feature/balance/BalanceScreen.kt"))
        assertTrue(
            "快捷筛选词的待删除项又在旋转时丢了",
            "var quickFilterToDelete by rememberSaveable" in tester,
        )
        assertTrue(
            "检索勾选态又在旋转时丢了",
            "var selectedSearchKeys by rememberSaveable" in tester,
        )
        assertTrue(
            "待移除来源又在旋转时丢了",
            "var sourceToDeleteKey by rememberSaveable" in tester,
        )
        assertTrue(
            "待删除模型条目又在旋转时丢了",
            "var entryToDeleteId by rememberSaveable" in tester,
        )
        assertTrue(
            "待删除余额模板又在旋转时丢了",
            "var templateToDeleteId by rememberSaveable" in balance,
        )
    }

    @Test
    fun `the template editor hosts the messages its own save produces`() {
        // 编辑器拿到的 SnackbarHostState 以前没人托管：校验失败写进页面级宿主，而那个
        // 宿主在活动窗口里、被编辑器窗口盖着。与更新弹窗同一做法：弹窗自己托管。
        val balance = code(File(sourceRoot, "com/relaytester/app/feature/balance/BalanceScreen.kt"))
        assertTrue(
            "模板编辑器没有托管消息宿主：校验失败的消息看不见",
            Regex("""SnackbarHost\(\s*hostState = snackbarHostState,\s*modifier = Modifier\.align\(Alignment\.BottomCenter\)""")
                .containsMatchIn(balance),
        )
        assertTrue(
            "编辑器与页面同时托管同一个宿主：同一条消息会被渲染两次",
            "if (!editorOpen) {" in balance,
        )
    }
}
