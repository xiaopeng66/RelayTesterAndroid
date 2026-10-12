package com.relaytester.app.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme

private val LightScheme = lightColorScheme(
    primary = Color(0xFF155EEF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE9FF),
    onPrimaryContainer = Color(0xFF0B3B93),
    secondary = Color(0xFF475467),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF0F8),
    onSecondaryContainer = Color(0xFF1D2939),
    tertiary = Color(0xFF078A78),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD4F6EE),
    onTertiaryContainer = Color(0xFF005143),
    error = Color(0xFFB42318),
    onError = Color.White,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF101828),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101828),
    surfaceVariant = Color(0xFFEAF0F8),
    onSurfaceVariant = Color(0xFF475467),
    outline = Color(0xFF98A2B3),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB4C7FF),
    onPrimary = Color(0xFF002D6F),
    primaryContainer = Color(0xFF1B4A9B),
    onPrimaryContainer = Color(0xFFDDE9FF),
    secondary = Color(0xFFC7D0DE),
    onSecondary = Color(0xFF29313D),
    secondaryContainer = Color(0xFF293544),
    onSecondaryContainer = Color(0xFFEAF0F8),
    tertiary = Color(0xFF65D8C4),
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF005143),
    onTertiaryContainer = Color(0xFFD4F6EE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFF3F5F8),
    surface = Color(0xFF111927),
    onSurface = Color(0xFFF3F5F8),
    surfaceVariant = Color(0xFF202B3A),
    onSurfaceVariant = Color(0xFFC7D0DE),
    outline = Color(0xFF8D99AA),
)

/**
 * 什么都不画的 Indication：按下不留痕迹。
 *
 * 实现为 [androidx.compose.foundation.IndicationNodeFactory]（新版库要求的写法，旧接口在
 * 本版已经是编译错误），返回一个不做任何事情的 Modifier.Node——涟漪是挂在节点上的一条绘制
 * 指令，不挂节点就是没有涟漪。
 */
private object NoPressGhostIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource) = NoPressGhostNode()

    // IndicationNodeFactory 的契约：实例按引用比较（无状态、可缓存）——不用默认的
    // equals 才能让 Modifier 组合树把「同一个指示」当作同一个实例去重。
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

private class NoPressGhostNode : Modifier.Node()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayTesterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // 按下按钮不再留「虚影」。Material 的按钮在按下时画两层东西：涟漪与状态层，抬指后
    // 那层半透明色还要留一会儿——用户把它读成「这个按钮被选中了」，可它不代表任何状态。
    // 两个开关一起关：
    //  * LocalRippleConfiguration = null：M3 组件不挂涟漪节点（Switch 与滑块此前为同一个
    //    观感各自关过一次，这里是把同一个开关放大到全应用）；
    //  * LocalIndication = NoPressGhostIndication：自绘的 clickable 也拿不到默认涟漪。
    // 动作本身照常：选中态由颜色、描边、文字如实表达，点按也照旧触发。
    CompositionLocalProvider(
        LocalRippleConfiguration provides null,
        LocalIndication provides NoPressGhostIndication,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = RelayTesterTypography,
            content = content,
        )
    }
}
