package com.relaytester.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * 弹窗背后那层「点到卡片外就关掉」的区域。
 *
 * 平台只在点击落在窗口内、内容之外时才上报（`DialogProperties.dismissOnClickOutside`）。本项目
 * 三个弹窗的内容根都铺满整个窗口（要的是「卡片居中 + 底部提示卡贴在导航栏上方」），平台于是永远
 * 认为点在了内容里，那个回调不会来——遮罩只能自己画：整层不可见、可点，点它调 [onDismiss]。
 *
 * 用在卡片的兄弟位置、**先声明**即可：命中测试按绘制顺序从后往前找，卡片（M3 `Surface`
 * 会吃掉落在自己身上的触摸）先被命中，落在卡片里的点击不会漏到这一层；卡片之外才轮到它。
 */
@Composable
fun DialogDismissScrim(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            // 读屏不该多读出一个没有名字的「可点击」节点：平台自带的遮罩本来就不在无障碍树
            // 里，自绘的也不该进去。清语义只影响无障碍，命中测试与点击都在。
            .clearAndSetSemantics {},
    )
}
