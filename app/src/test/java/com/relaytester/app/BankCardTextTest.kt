package com.relaytester.app

import com.relaytester.app.core.fingerprint.BankSource
import com.relaytester.app.feature.fingerprint.bankSourceLabel
import com.relaytester.app.feature.fingerprint.bankStateLine
import com.relaytester.app.feature.fingerprint.bankUpdateOffer
import com.relaytester.app.feature.fingerprint.formatBankSize
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
        val offer = bankUpdateOffer("2026-09-30T05:12:31+00:00", 53, 3_476_998)

        assertTrue(offer.contains("2026-09-30T05:12:31+00:00"))
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
        // inline in the composable where nothing could check it.
        val summary = bankStateLine(
            BankSource.INSTALLED,
            "2026-09-30T03:01:12.803902+00:00",
            53,
            3_476_998,
        )

        assertEquals("构建于 2026-09-30T03:01:12.803902+00:00 · 53 个模型 · 3.3 MB", summary)
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
}
