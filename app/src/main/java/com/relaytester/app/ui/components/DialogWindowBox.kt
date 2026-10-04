package com.relaytester.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
 * 所以盒子的高度这里自己算：屏幕高 − 状态栏 − 导航栏（本机 914dp − 48.8dp − 24dp ≈ 2209px，
 * 与平台量给窗口的那块完全一致）。盒子的下沿正好落在导航栏上沿，紧贴盒底摆放的提示卡就和
 * App 里其它提示卡的落点一样；居中摆放的卡片也回到可见区中心。
 *
 * **必须在弹窗外面调用**（就是创建 Dialog 的那层组合，比如弹窗函数自己的函数体）：读的是这个
 * 组合里的内边距，而弹窗自己的窗口夹在状态栏与导航栏之间，在它内部 WindowInsets 一律报 0，
 * 那样算出来就等于整屏高，这个修复就白做了。传进去的 Modifier 是普通值，可以跨进弹窗内容。
 */
@Composable
fun rememberDialogWindowBox(): Modifier {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val insets = WindowInsets.systemBars
    val barsHeight = with(density) {
        (insets.getTop(density) + insets.getBottom(density)).toDp()
    }
    return remember(configuration, barsHeight) {
        Modifier
            .fillMaxWidth()
            .height(configuration.screenHeightDp.dp - barsHeight)
    }
}
