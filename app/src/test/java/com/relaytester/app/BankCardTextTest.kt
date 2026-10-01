package com.relaytester.app

import com.relaytester.app.core.fingerprint.BankSource
import com.relaytester.app.feature.fingerprint.bankSourceLabel
import com.relaytester.app.feature.fingerprint.bankUpdateOffer
import com.relaytester.app.feature.fingerprint.formatBankSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference card's wording.
 *
 * It is the only place that tells the user which copy of the bank is in use, so the
 * three sources have to read differently, and a size has to stay readable when the
 * published bank grows past what is packed today.
 */
class BankCardTextTest {
    @Test
    fun `the three bank sources read differently`() {
        val labels = listOf(
            bankSourceLabel(BankSource.BUILT_IN),
            bankSourceLabel(BankSource.INSTALLED),
            bankSourceLabel(BankSource.INSTALLED_UNREADABLE),
        )

        assertEquals(3, labels.toSet().size)
        assertTrue(labels[2].contains("无法读取"))
    }

    @Test
    fun `a packed bank's size reads as kilobytes`() {
        assertEquals("390 KB", formatBankSize(399_470))
    }

    @Test
    fun `a bank past a megabyte reads as megabytes`() {
        assertEquals("1.5 MB", formatBankSize(1_572_864))
        assertEquals("4.0 MB", formatBankSize(4L * 1024 * 1024))
    }

    @Test
    fun `an unknown size says so instead of zero`() {
        assertEquals("大小未知", formatBankSize(0))
        assertEquals("大小未知", formatBankSize(-1))
    }

    @Test
    fun `the offer line names the build, the size and the model count`() {
        val offer = bankUpdateOffer("2026-09-30T05:12:31+00:00", 53, 399_470)

        assertTrue(offer.contains("2026-09-30T05:12:31+00:00"))
        assertTrue(offer.contains("53"))
        assertTrue(offer.contains("390 KB"))
    }

    @Test
    fun `an offer without a build stamp still reads`() {
        val offer = bankUpdateOffer("", 53, 399_470)

        assertTrue(offer.contains("未知"))
        assertTrue(offer.contains("53"))
    }
}
