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
}
