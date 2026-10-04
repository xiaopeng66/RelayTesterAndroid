package com.relaytester.app

import com.relaytester.app.core.fingerprint.BankManifest
import com.relaytester.app.core.fingerprint.BankSource
import com.relaytester.app.feature.fingerprint.FingerprintUiState
import com.relaytester.app.feature.fingerprint.bankCheckOutcome
import com.relaytester.app.feature.fingerprint.bankNeedsNewerAppLine
import com.relaytester.app.feature.fingerprint.bankSourceLabel
import com.relaytester.app.feature.fingerprint.bankStateLine
import com.relaytester.app.feature.fingerprint.bankUpdateOffer
import com.relaytester.app.feature.fingerprint.formatBankBuiltAt
import com.relaytester.app.feature.fingerprint.formatBankSize
import com.relaytester.app.ui.components.UpdateCheckOutcome
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detection-package card's wording.
 *
 * It is the only place that tells the user which copy of the package is in use, so the
 * three states have to read differently, and a size has to stay readable now that the
 * package is megabytes rather than bytes.
 */
class BankCardTextTest {
    @Test
    fun `the three package states read differently`() {
        val labels = listOf(
            bankSourceLabel(BankSource.NOT_PROVISIONED),
            bankSourceLabel(BankSource.INSTALLED),
            bankSourceLabel(BankSource.INSTALLED_UNREADABLE),
        )

        assertEquals(3, labels.toSet().size)
        assertEquals("未安装", labels[0])
        assertTrue(labels[2].contains("无法读取"))
    }

    @Test
    fun `a package under a megabyte reads as kilobytes`() {
        assertEquals("390 KB", formatBankSize(399_470))
    }

    @Test
    fun `a package past a megabyte reads as megabytes`() {
        assertEquals("1.5 MB", formatBankSize(1_572_864))
        assertEquals("4.0 MB", formatBankSize(4L * 1024 * 1024))
        // The published package is what the card actually shows.
        assertEquals("3.3 MB", formatBankSize(3_476_998))
    }

    @Test
    fun `an unknown size says so instead of zero`() {
        assertEquals("大小未知", formatBankSize(0))
        assertEquals("大小未知", formatBankSize(-1))
    }

    @Test
    fun `the offer line names the build, the size and the model count`() {
        val offer = bankUpdateOffer("2026-09-30T05:12:31+00:00", 53, 3_476_998, ZoneOffset.UTC)

        assertTrue(offer.contains("2026-09-30 05:12"))
        assertTrue(offer.contains("53"))
        assertTrue(offer.contains("3.3 MB"))
    }

    @Test
    fun `an offer without a build stamp still reads`() {
        val offer = bankUpdateOffer("", 53, 3_476_998)

        assertTrue(offer.contains("未知"))
        assertTrue(offer.contains("53"))
    }

    @Test
    fun `the installed line reads as prose`() {
        // A device run showed this line rendering "53 个模型标签 · 3.3 MB" — it is
        // user-visible prose, so it is assembled in a testable function rather than
        // inline in the composable where nothing could check it. The zone is a parameter
        // so this can pin one: the stamp a reader sees must not depend on where CI runs.
        val summary = bankStateLine(
            BankSource.INSTALLED,
            "2026-09-30T03:01:12.803902+00:00",
            53,
            3_476_998,
            ZoneOffset.UTC,
        )

        assertEquals("构建于 2026-09-30 03:01 · 53 个模型 · 3.3 MB", summary)
    }

    @Test
    fun `the build stamp is converted into the reader's zone`() {
        // The published stamp is UTC; the card is read next to "上次检查：今天 14:32", which
        // is local time. Both timestamps on this card have to speak the same clock.
        assertEquals(
            "2026-10-03 15:40",
            formatBankBuiltAt("2026-10-03T07:40:28.536363+00:00", ZoneId.of("Asia/Shanghai")),
        )
    }

    @Test
    fun `a stamp that does not parse is trimmed rather than blanked`() {
        // The publisher's string is not ours to format; a shape we cannot parse must still
        // be shown, minus the fractional seconds and the offset that made it unreadable.
        assertEquals("2026-10-03 07:40:28", formatBankBuiltAt("2026-10-03T07:40:28.5", ZoneOffset.UTC))
        assertEquals(
            "2026-10-03 07:40:28",
            formatBankBuiltAt("2026-10-03 07:40:28.536363+00:00", ZoneOffset.UTC),
        )
        assertEquals("未知", formatBankBuiltAt("   ", ZoneOffset.UTC))
    }

    @Test
    fun `a stamp with a Z offset is an instant like any other`() {
        assertEquals(
            "2026-10-03 15:40",
            formatBankBuiltAt("2026-10-03T07:40:28Z", ZoneId.of("Asia/Shanghai")),
        )
    }

    @Test
    fun `an installed package without a build stamp still reads`() {
        assertEquals(
            "构建于 未知 · 53 个模型 · 大小未知",
            bankStateLine(BankSource.INSTALLED, "", 53, 0),
        )
    }

    @Test
    fun `a broken package does not read as an empty one`() {
        // The same device run rendered "构建于 未知 · 0 个模型标签 · 0 KB" here, which
        // looks like a real (if empty) package rather than a broken one.
        val line = bankStateLine(BankSource.INSTALLED_UNREADABLE, "", 0, 0)

        assertEquals("当前检测包无法读取，检测功能暂不可用。", line)
        assertFalse(line.contains("构建于"))
    }

    @Test
    fun `no package says so`() {
        assertEquals(
            "尚未安装检测包，检测功能暂不可用。",
            bankStateLine(BankSource.NOT_PROVISIONED, "", 0, 0),
        )
    }

    @Test
    fun `the newer-app line names the version code the publisher asked for`() {
        // Two causes share this line (a package format too new to read, and a manifest
        // whose minAppVersionCode is above this app's), and the number is the actionable
        // part: it is what the user has to reach, so it must survive into the sentence.
        assertEquals(
            "线上检测包需要更新版本的 App 才能使用（需要版本代码 ≥ 10505）。",
            bankNeedsNewerAppLine(10_505L),
        )
    }

    @Test
    fun `the newer-app line still reads when no version code was stated`() {
        // A manifest too new to parse carries no requirement this build can read; the
        // line has to stay a sentence rather than print "≥ 0".
        val line = bankNeedsNewerAppLine(0L)

        assertEquals("线上检测包需要更新版本的 App 才能使用。", line)
        assertFalse(line.contains("版本代码"))
    }

    @Test
    fun `a panel that never checked reports no outcome`() {
        assertEquals(UpdateCheckOutcome.NEVER_CHECKED, bankCheckOutcome(FingerprintUiState()))
    }

    @Test
    fun `a finished check with nothing to offer is up to date`() {
        val state = FingerprintUiState(bankCheckedAtMillis = 1_791_009_120_000L)

        assertEquals(UpdateCheckOutcome.UP_TO_DATE, bankCheckOutcome(state))
    }

    @Test
    fun `a stamp from an earlier launch is a time without a verdict`() {
        // 窗口内的这次启动不查，于是盘上那个时间戳是本进程唯一知道的事实。把它读成
        // 「从未查过」是错的（端点问过，只是不在这次启动），读成「已是最新」同样是错的
        // （结论本进程没看到过）。
        assertEquals(
            UpdateCheckOutcome.EARLIER_CHECK,
            bankCheckOutcome(FingerprintUiState(bankEarlierCheckAtMillis = 1_791_009_120_000L)),
        )
    }

    @Test
    fun `a check this process ran outranks the stamp it inherited`() {
        val state = FingerprintUiState(
            bankCheckedAtMillis = 1_791_009_120_000L,
            bankEarlierCheckAtMillis = 1_791_005_000_000L,
        )

        assertEquals(UpdateCheckOutcome.UP_TO_DATE, bankCheckOutcome(state))
    }

    @Test
    fun `an offer and a newer-app demand each map to their own outcome`() {
        assertEquals(
            UpdateCheckOutcome.OFFER,
            bankCheckOutcome(FingerprintUiState(availableBankUpdate = manifest())),
        )
        assertEquals(
            UpdateCheckOutcome.NEEDS_NEWER_APP,
            bankCheckOutcome(FingerprintUiState(bankNeedsNewerApp = 10_505L)),
        )
    }

    @Test
    fun `a failure outranks the offer it left behind`() {
        // A check that fails does not retract the last successful offer — it stays on the
        // card — but the newest fact is that the endpoint could not be reached, so that is
        // what the status line has to report.
        val state = FingerprintUiState(
            bankCheckedAtMillis = 1_791_009_120_000L,
            bankCheckFailed = true,
            availableBankUpdate = manifest(),
        )

        assertEquals(UpdateCheckOutcome.FAILED, bankCheckOutcome(state))
    }

    private fun manifest() = BankManifest(
        formatVersion = 3,
        builtAt = "2026-09-30T05:12:31+00:00",
        referenceSha256 = "reference",
        modelCount = 53,
        sizeBytes = 3_476_998,
        sha256 = "digest",
        url = "https://example.test/bank/lite-bank.bin",
        minAppVersionCode = 0,
    )
}
