package com.relaytester.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.relaytester.app.core.update.DownloadProgress
import com.relaytester.app.core.update.formatByteSize
import com.relaytester.app.core.update.formatByteSizePair
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * What a check last ended in.
 *
 * One value covers both channels: the detection package's card and the app's update page
 * print the same sentence shape, and a check that fails has to leave a trace — the notice
 * it shows at the moment is a snackbar that goes away.
 */
enum class UpdateCheckOutcome {
    NEVER_CHECKED,
    UP_TO_DATE,

    /** The endpoint offers something this app can install. */
    OFFER,

    /** The endpoint's package needs a newer app than this one. */
    NEEDS_NEWER_APP,
    FAILED,
}

/**
 * The status line both update surfaces show above their buttons.
 *
 * A pure function on purpose: the wording ("今天 14:32") depends on the clock and the
 * zone, and a composable cannot be asked what it would print at another time.
 *
 * @param nowMillis the current time, so "今天" is decided by a value a test controls.
 */
fun updateCheckLine(
    isChecking: Boolean,
    checkedAtMillis: Long?,
    outcome: UpdateCheckOutcome,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String = when {
    isChecking -> "正在检查更新…"
    // A failure before any check ever finished still has a reason to print: the user
    // pressed the button, so the row must not fall back to "尚未检查".
    checkedAtMillis == null && outcome == UpdateCheckOutcome.FAILED -> "上次检查失败"
    checkedAtMillis == null -> "尚未检查"
    else -> "上次检查：${formatCheckStamp(checkedAtMillis, nowMillis, zone)} · ${outcomeLabel(outcome)}"
}

/**
 * The short label for a finished check, used after the timestamp.
 *
 * "发现新版本" rather than the version number: the offer line right above it already names
 * the build, and printing it twice would read as two different facts.
 */
fun outcomeLabel(outcome: UpdateCheckOutcome): String = when (outcome) {
    UpdateCheckOutcome.NEVER_CHECKED -> "尚无结果"
    UpdateCheckOutcome.UP_TO_DATE -> "已是最新"
    UpdateCheckOutcome.OFFER -> "发现新版本"
    UpdateCheckOutcome.NEEDS_NEWER_APP -> "需要更新 App"
    UpdateCheckOutcome.FAILED -> "失败"
}

/**
 * A check timestamp as a reader thinks of it: a time for today, a date for anything older.
 *
 * The panel shows local time; a stored check is an instant, so both the day and the
 * clock have to be computed in the reader's zone.
 */
fun formatCheckStamp(millis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val stamp = Instant.ofEpochMilli(millis).atZone(zone)
    val time = stamp.format(TIME_ONLY)
    return if (stamp.toLocalDate() == Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()) {
        "今天 $time"
    } else {
        stamp.format(DATE_AND_TIME)
    }
}

private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_AND_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * The download row: a bar and where the bytes are.
 *
 * Separate from [UpdateStatusRow] because it answers a different question — a check is a
 * wait with no measurable middle, a download has a middle — and because the two are never
 * on screen at once.
 */
@Composable
fun UpdateDownloadRow(
    progress: DownloadProgress,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // A bar needs a denominator. A server that did not declare a length leaves the
        // fraction null, and an indeterminate bar plus the byte count is the honest
        // version of that: it says "moving" without inventing a percentage.
        LinearProgressIndicator(
            progress = { progress.fraction ?: 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            downloadProgressLine(progress),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The one line a download prints, e.g. "正在下载 62% · 1.3/2.0 MB".
 *
 * The percentage is only printed when the total is known; without it the line falls back
 * to the byte count alone rather than to a made-up 0%.
 */
fun downloadProgressLine(progress: DownloadProgress): String {
    val fraction = progress.fraction
        ?: return "正在下载 ${formatByteSize(progress.bytesRead)}"
    val percent = (fraction * 100).roundToInt()
    return "正在下载 $percent% · ${formatByteSizePair(progress.bytesRead, progress.totalBytes)}"
}

@Composable
fun UpdateStatusRow(
    text: String,
    isChecking: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isChecking) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
