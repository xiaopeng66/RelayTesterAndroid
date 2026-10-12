package com.relaytester.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The red bar that marks a supplier set to 只拉取模型（禁止测试）.
 *
 * One definition because four surfaces draw it — the 模型测试 supplier cards, the 指纹检测
 * supplier chips, and the 动画测试 supplier button plus its dialog rows. A marker that meant
 * the same thing in four slightly different shapes would read as four different things.
 *
 * It carries its own description rather than leaving it to each caller: on the pickers the
 * bar is now the *only* indication (the "· 仅拉取" wording came out of the labels), so a
 * screen reader has to get the meaning from here. Merged into the surrounding chip or
 * button, it is announced together with the supplier's name.
 */
@Composable
fun PullOnlyMarker(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            // Width/height rather than `size(3.dp)`: the bar is deliberately taller than
            // it is wide, so it reads as a marker rather than as a dot.
            .size(width = PULL_ONLY_MARKER_WIDTH, height = PULL_ONLY_MARKER_HEIGHT)
            .background(
                color = MaterialTheme.colorScheme.error,
                shape = MaterialTheme.shapes.extraSmall,
            )
            .semantics { contentDescription = PULL_ONLY_MARKER_DESCRIPTION },
    )
}

private val PULL_ONLY_MARKER_WIDTH = 3.dp

/**
 * Matched to the 模型测试 cards' original marker, which sits beside a `titleSmall` name —
 * about 1.3× the text size. The chips and buttons draw it beside `labelLarge` text, the
 * same ratio.
 */
private val PULL_ONLY_MARKER_HEIGHT = 18.dp

private const val PULL_ONLY_MARKER_DESCRIPTION = "仅拉取模型"
