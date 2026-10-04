package com.relaytester.app

import com.relaytester.app.core.update.DownloadProgress
import com.relaytester.app.ui.components.UpdateCheckOutcome
import com.relaytester.app.ui.components.downloadProgressLine
import com.relaytester.app.ui.components.formatCheckStamp
import com.relaytester.app.ui.components.outcomeLabel
import com.relaytester.app.ui.components.updateCheckLine
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status line both update surfaces print.
 *
 * The panel used to run its entry check with nothing on screen saying so: a check in
 * flight, a check that found nothing and a check that never ran all looked identical.
 * These cover the sentence each of those states turns into.
 */
class UpdateStatusRowTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-10-03 14:32 in [zone]; "now" for the assertions about "今天". */
    private val now = 1_791_009_120_000L

    /** 2026-10-03 09:05 in [zone]. */
    private val earlierToday = 1_790_989_500_000L
    /** 2026-10-02 14:32 in [zone], so the "another day" case is one day back, not one minute. */
    private val yesterday = 1_790_922_720_000L

    private fun line(
        isChecking: Boolean = false,
        checkedAtMillis: Long? = null,
        outcome: UpdateCheckOutcome = UpdateCheckOutcome.NEVER_CHECKED,
    ): String = updateCheckLine(
        isChecking = isChecking,
        checkedAtMillis = checkedAtMillis,
        outcome = outcome,
        nowMillis = now,
        zone = zone,
    )

    @Test
    fun `an unfinished check says so`() {
        assertEquals("正在检查更新…", line(isChecking = true))
    }

    @Test
    fun `a check that never ran says so instead of claiming a result`() {
        assertEquals("尚未检查", line())
    }

    @Test
    fun `a finished check names the time and the result`() {
        assertEquals(
            "上次检查：今天 14:32 · 已是最新",
            line(checkedAtMillis = now, outcome = UpdateCheckOutcome.UP_TO_DATE),
        )
        assertEquals(
            "上次检查：今天 09:05 · 发现新版本",
            line(checkedAtMillis = earlierToday, outcome = UpdateCheckOutcome.OFFER),
        )
    }

    @Test
    fun `a check from an earlier day shows the date`() {
        // "今天 14:32" for a check two days ago would be a lie; the date is the whole
        // point of the line, so the day it happened on has to be printed.
        assertEquals(
            "上次检查：10-02 14:32 · 需要更新 App",
            line(checkedAtMillis = yesterday, outcome = UpdateCheckOutcome.NEEDS_NEWER_APP),
        )
    }

    @Test
    fun `a failed check is a finished check`() {
        // The failure reason is a snackbar and goes away; the row is what is left, so a
        // failure that did finish must not read as "尚未检查".
        val finished = line(checkedAtMillis = now, outcome = UpdateCheckOutcome.FAILED)

        assertTrue(finished.startsWith("上次检查："))
        assertTrue(finished.endsWith("失败"))
    }

    @Test
    fun `a check that failed before anything else ever finished still says so`() {
        assertEquals("上次检查失败", line(outcome = UpdateCheckOutcome.FAILED))
    }

    @Test
    fun `checking outranks whatever the last result was`() {
        // Both are set while a re-check runs: the spinner and "正在检查更新…" describe
        // what is happening, and the stale result is what the line goes back to.
        val line = line(isChecking = true, checkedAtMillis = now, outcome = UpdateCheckOutcome.FAILED)

        assertEquals("正在检查更新…", line)
        assertFalse(line.contains("失败"))
    }

    @Test
    fun `every outcome has its own label`() {
        val labels = UpdateCheckOutcome.entries.map { outcomeLabel(it) }

        assertEquals(labels.size, labels.toSet().size)
        assertEquals("已是最新", outcomeLabel(UpdateCheckOutcome.UP_TO_DATE))
    }

    @Test
    fun `a stamp is formatted in the reader's zone`() {
        // The same instant is 14:32 in Shanghai and 06:32 in UTC; the panel shows local
        // time and the stored value is an instant, so the zone is an input.
        assertEquals("今天 14:32", formatCheckStamp(now, now, zone))
        assertEquals("今天 06:32", formatCheckStamp(now, now, ZoneId.of("UTC")))
        assertEquals("10-02 14:32", formatCheckStamp(yesterday, now, zone))
    }

    @Test
    fun `a download in flight prints how far it got`() {
        assertEquals(
            "正在下载 65% · 1.3/2.0 MB",
            downloadProgressLine(DownloadProgress(bytesRead = 1_363_149, totalBytes = 2_097_152)),
        )
        assertEquals(
            "正在下载 0% · 0/390 KB",
            downloadProgressLine(DownloadProgress(bytesRead = 0, totalBytes = 399_470)),
        )
        // Both numbers in the larger unit: the pair is one quantity, not two measurements.
        assertEquals(
            "正在下载 11% · 0.4/3.3 MB",
            downloadProgressLine(DownloadProgress(bytesRead = 399_470, totalBytes = 3_476_998)),
        )
    }

    @Test
    fun `a download without a declared length prints bytes, not a percentage`() {
        // Nothing said how big the body is, so there is no honest percentage to print.
        val line = downloadProgressLine(DownloadProgress(bytesRead = 1_572_864, totalBytes = -1))

        assertEquals("正在下载 1.5 MB", line)
        assertFalse(line.contains("%"))
    }
}
