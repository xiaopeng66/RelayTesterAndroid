package com.relaytester.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The save-confirmation bar, shared by every editor dialog's host page.
 *
 * It lives on the PAGE, not inside the dialog that produced it: a successful save closes
 * that dialog, and a toast drawn in the dialog's window would vanish with it. Both pages
 * that show one (供应商配置、余额查询凭据) use this same component at the same spot with the
 * same lifetime, which is the whole point — one display rule, not two.
 *
 * The dark translucent surface matches the system toast idiom; [onToastShown] fires once
 * the bar's short lifetime is over so the host can drop its timestamp.
 */
@Composable
fun StatusToast(
    message: String,
    modifier: Modifier = Modifier,
    onToastShown: () -> Unit,
) {
    LaunchedEffect(message) {
        delay(1600L)
        onToastShown()
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = Color(0xE6000000),
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        Text(
            message,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            maxLines = 1,
            softWrap = false,
        )
    }
}
