package com.relaytester.app

import com.relaytester.app.core.fingerprint.BankDiscardResult
import com.relaytester.app.core.fingerprint.BankInstallResult
import com.relaytester.app.core.fingerprint.BankSource
import com.relaytester.app.core.fingerprint.FingerprintBankStore
import com.relaytester.app.core.fingerprint.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers which copy of the reference bank wins, and what a bad one may cost.
 *
 * The rule these tests exist for: a downloaded bank is untrusted input, so it is
 * parsed in full before anything is written, and a file that stops working falls back
 * to the packaged copy instead of leaving the panel unable to score at all.
 */
class FingerprintBankStoreTest {
    private val newStamp = "2026-09-30T05:12:31.123456+00:00"

    @Test
    fun `the packaged bank is used when nothing is installed`() {
        val store = MemoryBankFileSystem().store()

        val loaded = store.load()

        assertEquals(BankSource.BUILT_IN, loaded.identity.source)
        assertEquals(shippedBankBuiltAt(), loaded.identity.builtAt)
        assertEquals(53, loaded.identity.modelCount)
        assertEquals(shippedBankBytes().size.toLong(), loaded.identity.sizeBytes)
        assertEquals(sha256Hex(shippedBankBytes()), loaded.identity.sha256)
    }

    @Test
    fun `an installed bank wins over the packaged one`() {
        val patched = bankWithBuiltAt(newStamp)
        val files = MemoryBankFileSystem()
        assertTrue(files.store().install(patched) is BankInstallResult.Installed)

        // A new store stands for the next app run: the choice has to come from the file,
        // not from anything cached in memory.
        val reopened = files.store().load()

        assertEquals(BankSource.INSTALLED, reopened.identity.source)
        assertEquals(newStamp, reopened.identity.builtAt)
        assertEquals(sha256Hex(patched), reopened.identity.sha256)
    }

    @Test
    fun `the installed bank's own validity floor comes with it`() {
        val files = MemoryBankFileSystem()

        files.store().install(bankWithMinimumValid(123))

        assertEquals(123, files.store().load().bank.minimumValidNumbers)
    }

    @Test
    fun `an unparseable installed file falls back to the packaged bank`() {
        val files = MemoryBankFileSystem(installed = "not a bank".toByteArray())

        val loaded = files.store().load()

        assertEquals(BankSource.INSTALLED_UNREADABLE, loaded.identity.source)
        assertEquals(shippedBankBuiltAt(), loaded.identity.builtAt)
        assertEquals(53, loaded.identity.modelCount)
    }

    @Test
    fun `an installed file that cannot be read counts as unreadable`() {
        val files = MemoryBankFileSystem(installed = bankWithBuiltAt(newStamp), readFails = true)

        val loaded = files.store().load()

        assertEquals(BankSource.INSTALLED_UNREADABLE, loaded.identity.source)
        assertEquals(shippedBankBuiltAt(), loaded.identity.builtAt)
    }

    @Test
    fun `an installed bank larger than the cap is treated as unreadable`() {
        val oversize = ByteArray(FingerprintBankStore.MAX_INSTALLED_BYTES.toInt() + 1)
        val files = MemoryBankFileSystem(installed = oversize)

        val loaded = files.store().load()

        assertEquals(BankSource.INSTALLED_UNREADABLE, loaded.identity.source)
        assertEquals(53, loaded.identity.modelCount)
    }

    @Test
    fun `a truncated bank is rejected and nothing is written`() {
        val files = MemoryBankFileSystem()
        val shipped = shippedBankBytes()
        val truncated = shipped.copyOf(shipped.size / 2)

        val result = files.store().install(truncated)

        assertTrue(result is BankInstallResult.Rejected)
        assertNull(files.installed)
        assertEquals(BankSource.BUILT_IN, files.store().load().identity.source)
    }

    @Test
    fun `a bank with a trailing byte is rejected`() {
        val files = MemoryBankFileSystem()

        val result = files.store().install(bankWithTrailingByte())

        assertTrue(result is BankInstallResult.Rejected)
        assertNull(files.installed)
    }

    @Test
    fun `a file that is not a bank is rejected`() {
        val files = MemoryBankFileSystem()

        val result = files.store().install("<!doctype html><html>404</html>".toByteArray())

        assertTrue(result is BankInstallResult.Rejected)
        assertNull(files.installed)
    }

    @Test
    fun `a bank larger than the cap is rejected`() {
        val files = MemoryBankFileSystem()
        val oversize = ByteArray(FingerprintBankStore.MAX_INSTALLED_BYTES.toInt() + 1)

        val result = files.store().install(oversize)

        assertTrue(result is BankInstallResult.Rejected)
        assertTrue((result as BankInstallResult.Rejected).reason.contains("过大"))
        assertNull(files.installed)
    }

    @Test
    fun `a declared model count the file cannot hold is rejected instead of allocated`() {
        val files = MemoryBankFileSystem()
        // Fifty-odd bytes: far below the store's size cap, so only the reader's own
        // bounds check can reject this, and it has to reject rather than allocate.
        val header = headerOnly(Int.MAX_VALUE)

        val result = files.store().install(header)

        assertTrue(result is BankInstallResult.Rejected)
        assertNull(files.installed)
    }

    @Test
    fun `a negative model count is rejected`() {
        val files = MemoryBankFileSystem()

        val result = files.store().install(headerOnly(-1))

        assertTrue(result is BankInstallResult.Rejected)
        assertNull(files.installed)
    }

    @Test
    fun `a declared string longer than the file is rejected`() {
        val files = MemoryBankFileSystem()
        val bytes = "LMFPA001".toByteArray() + byteArrayOf(0x10, 0, 0, 0)

        val result = files.store().install(bytes)

        assertTrue(result is BankInstallResult.Rejected)
        assertNull(files.installed)
    }

    @Test
    fun `a rejected install leaves the working bank in place`() {
        val good = bankWithBuiltAt(newStamp)
        val files = MemoryBankFileSystem()
        files.store().install(good)

        val result = files.store().install("garbage".toByteArray())

        assertTrue(result is BankInstallResult.Rejected)
        val loaded = files.store().load()
        assertEquals(BankSource.INSTALLED, loaded.identity.source)
        assertEquals(newStamp, loaded.identity.builtAt)
    }

    @Test
    fun `a write that fails is reported and the old bank survives`() {
        val files = MemoryBankFileSystem(writeFails = true)

        val result = files.store().install(bankWithBuiltAt(newStamp))

        assertTrue(result is BankInstallResult.Rejected)
        assertNull(files.installed)
        assertEquals(BankSource.BUILT_IN, files.store().load().identity.source)
    }

    @Test
    fun `restoring drops the installed bank`() {
        val files = MemoryBankFileSystem()
        files.store().install(bankWithBuiltAt(newStamp))

        assertEquals(BankDiscardResult.Restored, files.store().discardInstalled())
        assertNull(files.installed)
        assertEquals(BankSource.BUILT_IN, files.store().load().identity.source)
    }

    @Test
    fun `restoring with nothing installed says so`() {
        assertEquals(BankDiscardResult.NothingInstalled, MemoryBankFileSystem().store().discardInstalled())
    }

    @Test
    fun `restoring an unreadable file clears the fallback`() {
        val files = MemoryBankFileSystem(installed = "broken".toByteArray())
        assertEquals(BankSource.INSTALLED_UNREADABLE, files.store().load().identity.source)

        assertEquals(BankDiscardResult.Restored, files.store().discardInstalled())

        assertNull(files.installed)
        assertEquals(BankSource.BUILT_IN, files.store().load().identity.source)
    }

    @Test
    fun `a delete that fails is reported and the installed bank stays active`() {
        val files = MemoryBankFileSystem(deleteFails = true)
        files.store().install(bankWithBuiltAt(newStamp))

        assertEquals(BankDiscardResult.Failed, files.store().discardInstalled())

        val loaded = files.store().load()
        assertEquals(BankSource.INSTALLED, loaded.identity.source)
        assertEquals(newStamp, loaded.identity.builtAt)
    }

    @Test
    fun `the packaged asset is a bank and is described by its own header`() {
        val loaded = FingerprintBankStore(MemoryBankFileSystem()).load()

        assertNotNull(loaded.bank)
        assertTrue(loaded.identity.modelCount > 0)
        assertTrue(loaded.identity.builtAt.isNotBlank())
    }

    /** A bank header that stops right after the model count, for the bounds checks. */
    private fun headerOnly(modelCount: Int): ByteArray {
        fun u32(value: Int) = byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

        fun text(value: String) = u32(value.length) + value.toByteArray(Charsets.UTF_8)

        return "LMFPA001".toByteArray() +
            text("a") + text("2026-09-30T00:00:00+00:00") + text("b") + u32(modelCount)
    }
}
