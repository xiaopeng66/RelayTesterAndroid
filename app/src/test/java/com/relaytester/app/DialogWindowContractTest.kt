package com.relaytester.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `the drag hangs on the list box, not on a row LazyColumn will recycle`() {
        // 拖动的承重件，一件错了都只有设备上才看得出来：
        //
        // ① 手势必须挂在**列表盒**上，不能挂在行上。Compose 只把事件送给按下那一刻命中的节点，
        //    而 LazyColumn 一旦把某行划出视口就把它整个丢出组合：行被回收，挂在它身上的手势跟着
        //    一起消失——手指还在屏幕上，行却不再跟手（用户报的「偶尔断触，拖不到最顶/最底」）。
        //    盒子在整个弹窗存活期内都在组合里，手势挂在这里，行的高矮进退与它无关。
        // ② 拖动期间必须关掉列表自己的用户滚动。盒子是列表的父节点，Compose 把指针事件先送子节点
        //    ——列表的滚动手势在盒子的手势之前，不关就会把手指的移动吃掉，盒子上的拖动起不来。
        // ③ 拖动期间顺序一个字节都不写，松手才写一次。以前每跨一格写一次，而 LazyColumn 会跟着
        //    「第一可见行」的 key 保持窗口位置：被拖的行换进第一格的那一次，窗口被拽下一格、行当场
        //    出视口（同一类断触）；为压住这次滚动又得逐帧把窗口「钉」回去，于是滚动与钉窗成了同一
        //    帧上的两个写手，谁最后落地取决于测量顺序——用户看到的就是边缘那一段一直闪（用户报的
        //    「到边缘还是闪屏」）。现在整次拖动只有一笔写。
        // ④ 被拖的行画在列表之外（浮层），列表里原来那一格留一个等高的空位：浮层不会被回收，列表
        //    的几何（因此还有判定用的那一帧）整次拖动都不变。
        // ⑤ 哪一行被拿起来由命中测试决定（supplierRowUnderFinger）：手势已经不在行上，事件落在谁
        //    身上不再说明任何事。
        //
        // 判定本身没有变：supplierSlotUnderFinger 拿按下时采下的那一帧算，指尖贴边时列表由
        // supplierAutoScrollStep 逐帧自己滚。能算的那部分在 SupplierOrderTest 的数值用例里，
        // 这里只钉「这几种写法必须继续存在于源码里」。
        val file = File(sourceRoot, "com/relaytester/app/feature/tester/TesterScreen.kt")
        assertTrue("找不到 TesterScreen.kt：${file.absolutePath}", file.isFile)
        val text = code(file)
        // ① 手势挂在列表盒上：同一个节点既知道自己在根坐标里的位置（浮层与命中测试的基准），
        //    又揣着拖动手势。
        assertTrue(
            "拖动手势没有挂在列表盒上（而是回了行上）：行会被 LazyColumn 回收，手势跟着一起死，" +
                "手指还在屏幕上、行却不再跟手",
            Regex("""\.onGloballyPositioned \{ boxTopInRoot = it\.positionInRoot\(\)\.y \}\s*\.then\(dragModifier\)""")
                .containsMatchIn(text),
        )
        val row = Regex("(?s)private fun SupplierOrderRow\\((.*?)\n}").find(text)?.groupValues?.get(1)
        assertNotNull("排序行的构造函数不在了", row)
        assertTrue(
            "行自己又带上了手势（pointerInput）：手势跟着挂载它的节点一起死，而行正是 LazyColumn " +
                "会回收的那个节点——「断触」就是这么来的",
            "pointerInput" !in row!!,
        )
        // ② 拖动期间关掉列表自己的滚动，否则子节点的滚动手势会先吃掉手指的移动。
        assertTrue(
            "拖动期间没有关掉列表的用户滚动：指针事件先送子节点（列表的滚动手势），手指的移动被它" +
                "吃掉，盒子上的拖动起不来",
            "userScrollEnabled = draggingId == null" in text,
        )
        // ③ 一次拖动只写一笔，写的是落点，且写的时候是松手。
        assertEquals(
            "拖动里出现了不止一处换位写入：拖动期间写列表会让 LazyColumn 跟着锚点挪窗口，" +
                "被拖的行会当场出视口（断触），为压住它又得逐帧钉窗（闪屏）",
            1, text.split("onMove(").size - 1,
        )
        assertTrue(
            "松手时的落点没有写进列表",
            Regex("""(?s)fun endDrag\(draggedId: String\?, commit: Boolean\) \{.*?onMove\(draggedId, targetSlot\)""")
                .containsMatchIn(text),
        )
        assertTrue(
            "自动滚动滚过的帧没有重新落格：手指停在边缘时没有 MOVE 事件，只认 onDrag 的判定永远不会" +
                "重跑，列表滚了、落点不动",
            "judgeSlot(pointerRootY)?.let { targetSlot = it }" in text,
        )
        // ④ 浮层在列表之外，原位留一个等高的空位。
        assertTrue(
            "被拖的行没有换成等高的空位：列表的几何会随拖动改变，判定用的那一帧就过期了",
            ".height(SUPPLIER_ORDER_ROW_HEIGHT)" in text &&
                "val dragged = suppliers.firstOrNull { it.id == draggingId }" in text,
        )
        assertTrue(
            "浮层没有画在列表之上（zIndex）：它会与列表里的行抢同一层，看起来像两行",
            "zIndex(1f)" in text,
        )
        // ⑤ 命中测试与判定基准。
        assertTrue(
            "拖动起点没有做命中测试：手势不在行上了，得由布局算出按下的是哪一行",
            "supplierRowUnderFinger(" in text,
        )
        assertTrue(
            "指尖与 itemsInfo 的 offset 没有换到同一基准：offset 从视口起点量起，向上还隔着列表的" +
                "上内边距，漏掉它每格会提前翻转",
            "- listTopInRoot + viewportStartOffset" in text,
        )
        assertTrue(
            "没有用四舍五入落格：半格处不换位，拖到两行之间时换位会迟一格",
            Regex("""\.roundToInt\(\)\.coerceIn\(0, lastIndex\)""").containsMatchIn(text),
        )
        assertTrue(
            "格子间距没有量自列表布局（相邻行的 offset 之差）：行距一改换算就失准",
            "visible[1].offset - visible[0].offset" in text,
        )
        assertTrue(
            "按下时没有采样判定帧：逐帧重读布局会把列表自己的滚动算进指尖位移，手指走一格、" +
                "判定说走两格",
            "val frame = currentSupplierDragFrame(listState, listTopInRoot, spacingPx)" in text,
        )
        // 指尖贴住边缘时列表要跟着手指滚（用户：「移出显示列表时列表应该往上或者往下拓展，直到
        // 顶部或者底部」）。三件事缺一不可：指尖夹进视口、按实际消费量逐帧滚、把滚过的位移补进判定。
        assertTrue(
            "指尖没有夹进视口：越出边缘的手指会被算成列表中很远的一格（甚至直接末格），" +
                "中间那些格子根本没进过画面",
            "fingerRootY.coerceIn(" in text,
        )
        assertTrue(
            "边缘滚动的方向与速度没有走那个纯函数：逐帧速度在设备上量不出来",
            "supplierAutoScrollStep(" in text,
        )
        assertTrue(
            "自动滚动没有逐帧步进：一次跳一屏会连着跳过好几格",
            "withFrameNanos {" in text,
        )
        assertTrue(
            "自动滚动没有按实际消费量累计：滚不动时（到顶/到底）还在长，判定会一直往前跑",
            "listState.scroll { consumed = scrollBy(step) }" in text,
        )
        assertTrue(
            "自动滚过的位移没有补进判定：内容从手指底下滑过去，指尖底下的格子必须跟着前进",
            "+ autoScrolledPx" in text,
        )
        // 钉窗那一整套（requestScrollToItem、frame.scrollIndex * pitch + scrollOffset + autoScrollPx）
        // 随「拖动期间不换位」一起退休：没有换位就没有锚点跟随要压，窗口除了指尖自己的滚动没有别的
        // 写手。它回来就等于换位又回到了拖动期间——断触与闪屏的根。
        assertFalse(
            "钉窗又回来了（requestScrollToItem）：它存在的唯一理由是拖动期间还在换位",
            "requestScrollToItem" in text,
        )
    }


    @Test
    fun `the animation toolbar is four bare icon buttons and no button keeps a press ghost`() {
        // 用户 1.7.17 的要求：动画面板底部左边四个按钮不写文字、去掉外边框、只留图标，
        // 重新排大小与间隔；而且「所有按钮不需要选中的虚影，包括其他地方的按钮」——
        // 按下时那层涟漪／状态层留下的半透明色，看着像选中态却又不代表任何状态。
        //
        // 渲染在 JVM 里量不到，能钉住的是源码里的三件事：按钮换成了 QuietIconButton
        // （现成的无涟漪件）、按钮里不再画 Text、以及主题层把涟漪的两个开关一起关了。
        val panel = code(File(sourceRoot, "com/relaytester/app/feature/fingerprint/AnimationPanel.kt"))
        assertTrue("找不到 AnimationPanel.kt", panel.isNotEmpty())
        val button = Regex("(?s)private fun AnimationActionButton\\((.*?)\\n}")
            .find(panel)?.groupValues?.get(1)
        assertNotNull("四个次要动作按钮的构造函数不在了", button)
        assertTrue(
            "动作按钮又画上文字了：四个按钮只留图标，文案只作为无障碍名（读屏照旧念得出）",
            "Text(" !in button!!,
        )
        assertTrue(
            "动作按钮没有用无涟漪的 QuietIconButton：按下会留下一层「虚影」",
            "QuietIconButton(" in button,
        )
        assertTrue(
            "图标没有把动作名当作无障碍标签：读屏会念成四个一模一样的匿名按钮",
            "contentDescription = label" in button,
        )
        assertTrue(
            "图标尺寸没有固定：方按钮里的图标要自己的尺寸，不随字体重排",
            "SECONDARY_ACTION_ICON_SIZE" in panel,
        )
        assertTrue(
            "四个方按钮没有固定等边边长：weight 分配出的宽度会随布局变化，四个按钮会被拉成大小不一的大方格",
            "Modifier.size(SECONDARY_ACTION_SIZE)" in panel,
        )
        assertTrue(
            "四个方按钮的边长常量不在了",
            "SECONDARY_ACTION_SIZE = 44.dp" in panel,
        )
        assertFalse(
            "「按宽度藏图标、缩字号」的老取舍逻辑又回来了：没有文案可切，这套规则不该复活",
            "secondaryActionFit" in panel,
        )
        val theme = code(File(sourceRoot, "com/relaytester/app/ui/theme/Theme.kt"))
        assertTrue("找不到 Theme.kt", theme.isNotEmpty())
        assertTrue(
            "主题层没有关掉 M3 组件的涟漪：按钮按下时的「虚影」会在所有页面复活",
            "LocalRippleConfiguration provides null" in theme,
        )
        assertTrue(
            "主题层没有关掉自绘 clickable 的默认涟漪：自绘按钮仍会留下「虚影」",
            "LocalIndication provides NoPressGhostIndication" in theme,
        )
    }

    @Test
    fun `the whole list area is the handle, and the grip stays a decoration`() {
        // 拖动「拖不动」的根因：手势原本挂在右侧那个手柄图标上，而它只有 44dp 宽——手指按住它再拖，
        // 起手的一点抖动就滑出它的边界，手势当场取消。修法是把整片列表区域都做成把手（见上一条），
        // 右端那个图标只剩 contentDescription = null 的装饰身份：它不是把手，是「这里可以拖」的提示。
        // JVM 里量不到命中区，能钉住的是源码事实。
        val file = File(sourceRoot, "com/relaytester/app/feature/tester/TesterScreen.kt")
        val text = code(file)
        assertTrue(
            "拖动手势没有挂在列表区域上：手柄只有 44dp，按住它拖会滑出边界，拖动又会断",
            ".then(dragModifier)" in text,
        )
        assertTrue(
            "行上的拖动语义没有声明：读屏用户听不到这一行可以拖动排序",
            "contentDescription = \"拖动排序 " in text,
        )
        assertTrue(
            "行高没有固定：名字长短不一时行高参差，「指尖落在哪一格」的几何随之失准",
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

    @Test
    fun `the animation panel is a card, not a full-bleed sheet`() {
        // 动画面板原来是一张铺满窗口的 surface：没有卡片边缘，也没有圆角，读起来像换了个
        // 页面而不是弹了个框。合同＝它和其它弹窗同一形状：屏宽 94% 的圆角卡片，高度随内容
        // 收缩但落在上下限之间（上限 0.9 屏高，与「关于与更新」同一条）。
        val file = File(
            sourceRoot,
            "com/relaytester/app/feature/fingerprint/AnimationPanel.kt",
        )
        assertTrue("找不到 AnimationPanel.kt：${file.absolutePath}", file.isFile)
        val text = code(file)
        assertTrue(
            "动画面板不再按屏宽 94% 收窄：又变成铺满窗口了",
            "fillMaxWidth(ANIMATION_CARD_WIDTH_FRACTION)" in text,
        )
        assertTrue(
            "动画面板的宽度比例不是 0.94",
            "ANIMATION_CARD_WIDTH_FRACTION = 0.94f" in text,
        )
        assertTrue(
            "动画面板没有高度上下限：收起提示词时会留一张空卡片，或者内容把按钮顶出屏幕",
            Regex("""heightIn\(\s*min = screenHeight \* ANIMATION_CARD_MIN_HEIGHT_FRACTION,\s*max = screenHeight \* ANIMATION_CARD_MAX_HEIGHT_FRACTION,""")
                .containsMatchIn(text),
        )
        assertTrue(
            "动画面板的高度上限不再是 0.9 屏高（与其它弹窗不一致）",
            "ANIMATION_CARD_MAX_HEIGHT_FRACTION = 0.9f" in text,
        )
        assertTrue(
            "动画面板不再铺满窗口时忘了圆角：卡片边缘看不出来",
            "shape = MaterialTheme.shapes.extraLarge" in text,
        )
    }

    @Test
    fun `the animation panel's prompt box collapses on request`() {
        // 长提示词加结果区在任何字号下都塞不进一张卡片；提示词框收起是给结果腾地方的手段。
        // 合同＝收起入口存在、状态是 saveable 的（旋转不丢），且收起态真的只有一行高。
        val file = File(
            sourceRoot,
            "com/relaytester/app/feature/fingerprint/AnimationPanel.kt",
        )
        val text = code(file)
        // 收起状态必须跟着当前展示的那一条记录走：卡片现在是两层弹窗，详情那一层的参数
        // 就是那条记录本身，键还写成常量的话，换一条记录时收起状态会串到上一条上（或者
        // 旋转移位后无法还原到它真正属于哪条记录）。键必须提到 sessionId，写法不限。
        val promptKey = Regex(
            """var promptExpanded by rememberSaveable\(([^)]*)\)""",
        ).find(text)?.groupValues?.get(1)
        assertNotNull(
            "提示词框没有收起状态（旋转会丢）",
            promptKey,
        )
        assertTrue(
            "提示词框的收起状态没有按记录分开：换一条记录会串到上一条上（键实际是「$promptKey」）",
            "sessionId" in promptKey!!,
        )
        assertTrue(
            "提示词区没有收起/展开入口",
            "onToggleExpanded" in text,
        )
        assertTrue(
            "收起态的提示词框没有收成一行",
            Regex("""min = PROMPT_COLLAPSED_HEIGHT, max = PROMPT_COLLAPSED_HEIGHT""")
                .containsMatchIn(text),
        )
        // 收起态必须真的收成单行：多行框即使压到 56dp 也还是多行控件，光标会跳行。
        assertTrue(
            "收起态的提示词框还在按多行渲染",
            "singleLine = !expanded" in text,
        )
        // 默认收起：面板打开时用户要看的是生成过程与结果，不是那段固定模板。
        val defaultState = Regex(
            """var promptExpanded by rememberSaveable\([^)]*\) \{ mutableStateOf\((\w+)\) \}""",
        ).find(text)?.groupValues?.get(1)
        assertEquals(
            "提示词又默认展开了：打开面板先占掉半屏给一段不用改的模板",
            "false",
            defaultState,
        )
    }

    @Test
    fun `the generation shows the document instead of a bare spinner`() {
        // 生成过程要可视化：一段大文档在写的时候，只给一个转圈等于什么都不说。合同＝
        // 生成中有专门的一段显示正在写入的文本，且完成/失败后这一段必须让位。
        val file = File(
            sourceRoot,
            "com/relaytester/app/feature/fingerprint/AnimationPanel.kt",
        )
        val text = code(file)
        assertTrue(
            "生成中不再显示正在写入的文档",
            "AnimationStreamingSection(" in text,
        )
        assertTrue(
            "生成中与结果区没有互斥：两者会同时出现，动画画完也顶不掉生成过程",
            "if (session.phase == AnimationPhase.GENERATING) {" in text,
        )
        assertTrue(
            "生成视图不读 streamingText：显示的还是写死的内容",
            "session.streamingText" in text,
        )
    }

    @Test
    fun `the animation test opens on its list, and a record is what opens the detail`() {
        // 入口点进去必须先看到「选模型 + 测试记录」，点进某一条才是测试界面：这两件事原来
        // 挤在同一张卡片里靠选中态切换，于是入口会直接落到上次看了一半的那条记录上。合同＝
        // 两个各管一件事的弹窗，列表那一层有选模型与记录列表，详情那一层有提示词与操作栏。
        val file = File(
            sourceRoot,
            "com/relaytester/app/feature/fingerprint/AnimationPanel.kt",
        )
        val text = code(file)
        val rawDialogs = Regex("(?m)^\\s*Dialog\\(").findAll(text).count()
        assertEquals(
            "动画测试不再是两层弹窗：入口要么直接弹进某条记录的详情，要么列表与详情又挤在一层里",
            2,
            rawDialogs,
        )
        val list = Regex("(?s)private fun AnimationTestListDialog\\((.*?)\n\\}\n")
            .find(text)?.groupValues?.get(1)
        assertNotNull("找不到列表那一层的实现", list)
        assertTrue(
            "列表那一层没有选供应商/模型与开始生成",
            "AnimationSetupSection(" in list!!,
        )
        assertTrue(
            "列表那一层没有测试记录列表：点进去看进度之后就回不到列表了",
            "AnimationSessionList(" in list,
        )
        assertTrue(
            "列表那一层混进了单条记录的东西：它只负责选模型与列记录",
            "AnimationActionBar(" !in list && "AnimationPromptSection(" !in list,
        )
        val detail = Regex("(?s)private fun AnimationTestDetailDialog\\((.*?)\n\\}\n")
            .find(text)?.groupValues?.get(1)
        assertNotNull("找不到详情那一层的实现", detail)
        assertTrue(
            "详情那一层没有提示词与操作栏：测试界面缺了它自己的两部分",
            "AnimationPromptSection(" in detail!! && "AnimationActionBar(" in detail,
        )
        assertTrue(
            "详情那一层混进了选模型与记录列表：它只负责一条记录",
            "AnimationSetupSection(" !in detail && "AnimationSessionList(" !in detail,
        )
        // 一次只弹一层：两层同开会让返回落到一个不存在的中间态。
        assertTrue(
            "两层弹窗不是互斥的：会同时画在屏幕上",
            Regex("(?s)if \\(selected == null\\) \\{\\s*AnimationTestListDialog\\(").containsMatchIn(text) &&
                Regex("(?s)\\} else \\{\\s*AnimationTestDetailDialog\\(").containsMatchIn(text),
        )
        // 入口必须落在列表那一层，否则这条合同只在「上次没看记录」时成立。
        val vm = File(
            sourceRoot,
            "com/relaytester/app/feature/fingerprint/FingerprintViewModel.kt",
        )
        val open = Regex("(?s)fun openAnimationPanel\\(\\) \\{(.*?)\\n    \\}").find(code(vm))
            ?.groupValues?.get(1)
        assertNotNull("找不到 openAnimationPanel 的实现", open)
        assertTrue(
            "入口打开时没有清掉上次选中的记录：点入口会直接弹进那条记录的详情",
            "selectedSessionId = null" in open!!,
        )
    }

    @Test
    fun `the animation entry sits next to the advanced test button`() {
        // 入口原先挤在指纹页模型名那一行上，用纯文字避开边框与涟漪。现在它搬到了模型测试
        // 页的「高级测试」旁边，和后者同规格：一个标准描边按钮。合同＝入口在 TesterScreen
        // 里、是 OutlinedButton、与高级测试并排，且生成中时按钮上有角标——否则用户以为
        // 卡片一关测试就没了。
        val fingerprint = File(
            sourceRoot,
            "com/relaytester/app/feature/fingerprint/FingerprintScreen.kt",
        )
        assertTrue(
            "动画测试入口还留在指纹页：模型卡那一行又会被挤出一个按钮",
            "AnimationTestButton" !in code(fingerprint) &&
                "onOpenAnimationTest" !in code(fingerprint),
        )

        val tester = File(
            sourceRoot,
            "com/relaytester/app/feature/tester/TesterScreen.kt",
        )
        val text = code(tester)
        val block = Regex(
            "(?s)private fun AnimationTestEntry\\(generating: Boolean, onClick: \\(\\) -> Unit\\) \\{(.*?)\\n\\}",
        ).find(text)?.groupValues?.get(1)
        assertNotNull("找不到 AnimationTestEntry 的实现", block)
        assertTrue(
            "动画测试入口不是与高级测试同规格的描边按钮",
            "OutlinedButton(" in block!!,
        )
        assertTrue(
            "入口丢了触摸高度下限：大字号下点不中",
            "heightIn(" in block,
        )
        assertTrue(
            "入口没有进行中角标：关掉卡片后看不出还有测试在跑",
            "generating" in block && "CircleShape" in text,
        )
        // 两个入口必须在同一处渲染，否则「并排」这条合同是假的。
        val header = Regex("(?s)private fun TestSettingsCard\\((.*?)\n\\}").find(text)
            ?.groupValues?.get(1)
        assertNotNull("找不到 TestSettingsCard 的实现", header)
        assertTrue(
            "动画测试入口没有与高级测试并排渲染",
            "AnimationTestEntry(" in header!! && "AdvancedCatalogButton(" in header,
        )
    }

    @Test
    fun `closing the animation card keeps the test running`() {
        // 卡片从「一关就整个会话没了」改成了列表：关掉卡片只是收起界面，进行中的生成必须
        // 继续跑，用户才能再点进来接着看。这条合同守卫的正是那个改动。
        val panel = File(
            sourceRoot,
            "com/relaytester/app/feature/fingerprint/AnimationPanel.kt",
        )
        val text = code(panel)
        assertTrue(
            "卡片不再有列表视图：关掉卡片后没有地方能重新看到进行中的测试",
            "AnimationSessionList(" in text,
        )
        assertTrue(
            "卡片不能从详情返回列表：点进去看进度之后就再也回不到列表了",
            "onOpenSession" in text && "ArrowBack" in text,
        )
        val vm = File(
            sourceRoot,
            "com/relaytester/app/feature/fingerprint/FingerprintViewModel.kt",
        )
        val vmText = code(vm)
        val close = Regex("(?s)fun closeAnimationPanel\\(\\) \\{(.*?)\\n    \\}").find(vmText)
            ?.groupValues?.get(1)
        assertNotNull("找不到 closeAnimationPanel 的实现", close)
        assertTrue(
            "关掉卡片时不再只是收起界面：它又去动了会话，进行中的测试会被掐掉",
            "panelOpen = false" in close!! && "cancel" !in close,
        )
    }

    @Test
    fun `the model list is filtered by one field, and it exists even with no catalogue`() {
        // 两件事都只有源码能钉：
        // 1. 曾经同时存在两个筛选词（页面级 + 弹窗级），弹窗里的列表是两者交集，而页面上那个
        //    在模态弹窗背后根本改不到——列表被一个看不见的词筛窄了。合同＝弹窗的筛选只认传入的
        //    那一份状态，不再自带一个 rememberSaveable 的 query。
        // 2. 目录为空时的提示写着「可直接输入模型名」，而输入框当时被 else 分支挡在外面，那句话
        //    自相矛盾。合同＝输入框无条件渲染。
        val screen = File(
            sourceRoot,
            "com/relaytester/app/feature/fingerprint/FingerprintScreen.kt",
        )
        val text = code(screen)
        val picker = Regex(
            "(?s)private fun ModelPicker\\((.*?)\\n\\}\\n",
        ).find(text)?.groupValues?.get(1)
        assertNotNull("找不到 ModelPicker 的实现", picker)
        val dialog = Regex(
            "(?s)private fun ModelListDialog\\((.*?)\\n\\}\\n",
        ).find(text)?.groupValues?.get(1)
        assertNotNull("找不到 ModelListDialog 的实现", dialog)

        assertTrue(
            "页面这一层又出现了筛选输入：屏幕上会有两个筛选词，其中一个藏在弹窗背后改不到",
            "OutlinedTextField(" !in picker!! && "onFilterChange" in picker,
        )
        assertTrue(
            "弹窗没有自己的筛选框：列表只能在页面上那个看不见的字段里筛",
            "OutlinedTextField(" in dialog!! && "filter" in dialog,
        )
        assertFalse(
            "弹窗又自带了一个筛选词：它和页面级的那份会互相盖住，列表成了两者的交集",
            "var query by rememberSaveable" in dialog,
        )
        // 输入框必须在 catalogueEmpty 的分支之外：空目录正是它唯一有用的时候。
        val emptyBranch = Regex(
            "(?s)catalogueEmpty && unmatched == null\\) \\{(.*?)\\n                \\}",
        ).find(dialog)?.groupValues?.get(1)
        assertNotNull("找不到目录为空时的提示分支", emptyBranch)
        assertTrue(
            "目录为空时只给了文字提示、没给输入框：让用户直接输模型名，却没有地方输",
            "OutlinedTextField(" !in emptyBranch!!,
        )
    }

    @Test
    fun `the parallel switch says out loud what it switches`() {
        // 开关旁边只有「并行发送三题」这个无障碍描述时，看得见的用户看不到它开的是什么——
        // 屏幕上只有一个没有标签的开关。合同＝同一行里有一句可读的文字，且它不重复播报。
        val screen = File(
            sourceRoot,
            "com/relaytester/app/feature/fingerprint/FingerprintScreen.kt",
        )
        val picker = Regex(
            "(?s)private fun ModelPicker\\((.*?)\\n\\}\\n",
        ).find(code(screen))?.groupValues?.get(1)
        assertNotNull("找不到 ModelPicker 的实现", picker)
        assertTrue(
            "并行开关没有可见的文字说明：标志词只存在于无障碍描述里",
            "三题并行" in picker!!,
        )
        assertTrue(
            "可见的那行字也参与了播报：读屏会把同一句话念两遍",
            "clearAndSetSemantics" in picker,
        )
    }
}
