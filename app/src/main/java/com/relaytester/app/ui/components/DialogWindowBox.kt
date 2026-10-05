package com.relaytester.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/**
 * 把弹窗的内容盒定成「系统真正给它的那块窗口」，而不是整块屏幕。
 *
 * 这三个弹窗都要 `usePlatformDefaultWidth = false`（卡片要占屏宽 94%，平台默认值只给约 320dp），
 * 而那个标记还有第二个后果：Compose 会拿**整屏**尺寸当约束来测量内容，再要求窗口也这么高。本机
 * 屏幕上量到的是——内容盒 2400px，窗口却只能占 [0,128]-[1080,2337]（状态栏之下、导航栏之上，
 * 就这一块），于是盒子底部 191px 被裁掉：贴底摆放的提示卡落进被裁掉的那截，屏幕上永远看不见
 * （「关于与更新界面点击检查更新后，下边的黑色提示卡藏在了界面下边」就是这么来的），卡片本身
 * 也跟着下移 96px（实测卡心 1328，可见区中心 1232.5）。
 *
 * 所以盒子的高宽这里都自己算：屏幕尺寸 − **安全绘图区**（系统栏 ∪ 屏幕挖孔）。竖屏本机是
 * 914dp − 48.8dp − 24dp ≈ 2209px 高、1080px 宽，与平台量给窗口的那块完全一致；盒子的下沿正好
 * 落在导航栏上沿，紧贴盒底摆放的提示卡就和 App 里其它提示卡的落点一样，居中摆放的卡片也回到
 * 可见区中心。
 *
 * 宽也照算，是因为**横屏**下这条同样成立：横过来以后窗口只剩 128..2400 这块，而 fillMaxWidth
 * 拿到的是整屏宽（内容按整屏测量），占屏宽 94% 的卡片量到 200..2456——右边 56px（21dp）连同
 * 圆角一起切在屏幕外（本机实测；横屏下四个弹窗都一样）。减掉左右那两块以后卡片是 196..2332，
 * 整个落在窗口里。
 *
 * 左边这块**不在 `systemBars` 里**：`dumpsys window displays` 量到的是
 * `type=displayCutout frame=[0,0][128,1080]`（横屏时挖孔在左），`safeDrawing` 才是系统栏与挖孔
 * 的并集，也就是平台真正拿来缩窗口的那一份。竖屏时两者相同（挖孔落在状态栏里），所以这条只
 * 改横屏的落点。
 *
 * **必须在弹窗外面调用**（就是创建 Dialog 的那层组合，比如弹窗函数自己的函数体）：读的是这个
 * 组合里的内边距，而弹窗自己的窗口夹在状态栏与导航栏之间，在它内部 WindowInsets 一律报 0，
 * 那样算出来就等于整屏尺寸，这个修复就白做了。传进去的 Modifier 是普通值，可以跨进弹窗内容。
 */
@Composable
fun rememberDialogWindowBox(): Modifier {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    val barsHeight = with(density) {
        (insets.getTop(density) + insets.getBottom(density)).toDp()
    }
    val barsWidth = with(density) {
        (insets.getLeft(density, layoutDirection) + insets.getRight(density, layoutDirection)).toDp()
    }
    return remember(configuration, barsHeight, barsWidth) {
        Modifier
            .width(configuration.screenWidthDp.dp - barsWidth)
            .height(configuration.screenHeightDp.dp - barsHeight)
    }
}
