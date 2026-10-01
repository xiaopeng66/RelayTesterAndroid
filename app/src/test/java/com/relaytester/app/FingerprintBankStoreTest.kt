package com.relaytester.app

import com.relaytester.app.core.fingerprint.BankDiscardResult
import com.relaytester.app.core.fingerprint.BankInstallResult
import com.relaytester.app.core.fingerprint.BankReader
import com.relaytester.app.core.fingerprint.BankSource
import com.relaytester.app.core.fingerprint.FingerprintBankStore
import com.relaytester.app.core.fingerprint.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers what the panel finds when it looks for a detection package, and what a bad one
 * may cost.
 *
 * The rules these tests exist for: the package ships separately from the APK, so a
 * device with nothing installed is a normal state the panel has to report precisely
 * (not an error); a downloaded package is untrusted input, so it is parsed in full
 * before anything is written; and a file that stops working falls back to the package
 * the previous install replaced instead of leaving the panel unable to score at all.
 */
class FingerprintBankStoreTest {
    private val newStamp = "2026-09-30T05:12:31.123456+00:00"

    @Test
    fun `nothing installed leaves the panel unprovisioned`() {
        val files = MemoryBankFileSystem(installed = null)

        val loaded = files.store().load()

        assertEquals(BankSource.NOT_PROVISIONED, loaded.source)
        assertNull(loaded.loaded)
        assertEquals(0L, loaded.installedBytes)
        assertNull(loaded.problem)
        assertFalse(loaded.usedBackup)
    }

    @Test
    fun `the installed package is described by its own header`() {
        val loaded = MemoryBankFileSystem().store().load()

        assertEquals(BankSource.INSTALLED, loaded.source)
        assertNotNull(loaded.loaded)
        assertEquals(shippedBankBuiltAt(), loaded.loaded!!.identity.builtAt)
        assertEquals(shippedBankModelCount(), loaded.loaded!!.identity.modelCount)
        assertEquals(fixtureBankBytes().size.toLong(), loaded.loaded!!.identity.sizeBytes)
        assertEquals(sha256Hex(fixtureBankBytes()), loaded.loaded!!.identity.sha256)
    }

    @Test
    fun `installing keeps the outgoing package as a rollback copy`() {
        val files = MemoryBankFileSystem()
        val patched = bankWithBuiltAt(newStamp)

        assertTrue(files.store().install(patched) is BankInstallResult.Installed)

        assertEquals(sha256Hex(fixtureBankBytes()), sha256Hex(files.backup!!))
        // A new store stands for the next app run: the choice has to come from the file,
        // not from anything cached in memory.
        val reopened = files.store().load()
        assertEquals(BankSource.INSTALLED, reopened.source)
        assertEquals(newStamp, reopened.loaded!!.identity.builtAt)
        assertEquals(sha256Hex(patched), reopened.loaded!!.identity.sha256)
    }

    @Test
    fun `the installed package's own validity floor comes with it`() {
        val files = MemoryBankFileSystem()

        files.store().install(bankWithMinimumValid(123))

        assertEquals(123, files.store().load().loaded!!.bank.minimumValidNumbers)
    }

    @Test
    fun `a file that is not a package is unreadable and says so`() {
        val files = MemoryBankFileSystem(installed = "not a bank".toByteArray())

        val loaded = files.store().load()

        assertEquals(BankSource.INSTALLED_UNREADABLE, loaded.source)
        assertNull(loaded.loaded)
        assertTrue("必须说明无法解析", loaded.problem!!.contains("无法解析"))
        assertEquals("not a bank".length.toLong(), loaded.installedBytes)
    }

    @Test
    fun `a corrupt package falls back to the rollback copy`() {
        val files = MemoryBankFileSystem(installed = "broken".toByteArray(), backup = fixtureBankBytes())

        val loaded = files.store().load()

        assertEquals(BankSource.INSTALLED, loaded.source)
        assertTrue("必须说明已经回退", loaded.problem!!.contains("回退"))
        assertTrue(loaded.usedBackup)
        assertEquals(shippedBankBuiltAt(), loaded.loaded!!.identity.builtAt)
    }

    @Test
    fun `an installed file that cannot be read is unreadable`() {
        val files = MemoryBankFileSystem(installed = bankWithBuiltAt(newStamp), readFails = true)

        val loaded = files.store().load()

        assertEquals(BankSource.INSTALLED_UNREADABLE, loaded.source)
        assertNull(loaded.loaded)
    }

    @Test
    fun `an installed package larger than the cap is treated as unreadable`() {
        val oversize = ByteArray(FingerprintBankStore.MAX_INSTALLED_BYTES.toInt() + 1)
        val files = MemoryBankFileSystem(installed = oversize)

        val loaded = files.store().load()

        assertEquals(BankSource.INSTALLED_UNREADABLE, loaded.source)
        assertNull(loaded.loaded)
    }

    @Test
    fun `a truncated package is rejected and nothing is written`() {
        val files = MemoryBankFileSystem(installed = null)
        val shipped = fixtureBankBytes()
        val truncated = shipped.copyOf(shipped.size / 2)

        val result = files.store().install(truncated)

        assertTrue(result is BankInstallResult.Rejected)
        assertNull(files.installed)
        assertEquals(BankSource.NOT_PROVISIONED, files.store().load().source)
    }

    @Test
    fun `a package with a trailing byte is rejected`() {
        val files = MemoryBankFileSystem(installed = null)

        val result = files.store().install(bankWithTrailingByte())

        assertTrue(result is BankInstallResult.Rejected)
        assertNull(files.installed)
    }

    @Test
    fun `a file that is not a package is rejected`() {
        val files = MemoryBankFileSystem(installed = null)

        val result = files.store().install("<!doctype html><html>404</html>".toByteArray())

        assertTrue(result is BankInstallResult.Rejected)
        assertNull(files.installed)
    }

    @Test
    fun `a package larger than the cap is rejected`() {
        val files = MemoryBankFileSystem(installed = null)
        val oversize = ByteArray(FingerprintBankStore.MAX_INSTALLED_BYTES.toInt() + 1)

        val result = files.store().install(oversize)

        assertTrue(result is BankInstallResult.Rejected)
        assertTrue((result as BankInstallResult.Rejected).reason.contains("过大"))
        assertNull(files.installed)
    }

    @Test
    fun `a declared model count the file cannot hold is rejected instead of allocated`() {
        val files = MemoryBankFileSystem(installed = null)
        // Fifty-odd bytes: far below the store's size cap, so only the reader's own
        // bounds check can reject this, and it has to reject rather than allocate.
        val header = headerOnly(Int.MAX_VALUE)

        val result = files.store().install(header)

        assertTrue(result is BankInstallResult.Rejected)
        assertNull(files.installed)
    }

    @Test
    fun `a negative model count is rejected`() {
        val files = MemoryBankFileSystem(installed = null)

        val result = files.store().install(headerOnly(-1))

        assertTrue(result is BankInstallResult.Rejected)
        assertNull(files.installed)
    }

    @Test
    fun `a declared string longer than the file is rejected`() {
        val files = MemoryBankFileSystem(installed = null)
        val bytes = "LMFPA002".toByteArray() + byteArrayOf(0x10, 0, 0, 0)

        val result = files.store().install(bytes)

        assertTrue(result is BankInstallResult.Rejected)
        assertNull(files.installed)
    }

    @Test
    fun `a rejected install leaves the working package in place`() {
        val good = bankWithBuiltAt(newStamp)
        val files = MemoryBankFileSystem()
        files.store().install(good)

        val result = files.store().install("garbage".toByteArray())

        assertTrue(result is BankInstallResult.Rejected)
        val loaded = files.store().load()
        assertEquals(BankSource.INSTALLED, loaded.source)
        assertEquals(newStamp, loaded.loaded!!.identity.builtAt)
    }

    @Test
    fun `a write that fails is reported and nothing is adopted`() {
        val files = MemoryBankFileSystem(installed = null, writeFails = true)

        val result = files.store().install(bankWithBuiltAt(newStamp))

        assertTrue(result is BankInstallResult.Rejected)
        assertNull(files.installed)
        assertEquals(BankSource.NOT_PROVISIONED, files.store().load().source)
    }

    @Test
    fun `removing drops the installed package and its backup`() {
        val files = MemoryBankFileSystem()
        files.store().install(bankWithBuiltAt(newStamp))

        assertEquals(BankDiscardResult.Removed, files.store().discardInstalled())

        assertNull(files.installed)
        assertNull(files.backup)
        assertEquals(BankSource.NOT_PROVISIONED, files.store().load().source)
    }

    @Test
    fun `removing with nothing installed says so`() {
        assertEquals(
            BankDiscardResult.NothingInstalled,
            MemoryBankFileSystem(installed = null).store().discardInstalled(),
        )
    }

    @Test
    fun `removing an unreadable package clears it so a download starts clean`() {
        val files = MemoryBankFileSystem(installed = "broken".toByteArray())
        assertEquals(BankSource.INSTALLED_UNREADABLE, files.store().load().source)

        assertEquals(BankDiscardResult.Removed, files.store().discardInstalled())

        assertNull(files.installed)
        assertEquals(BankSource.NOT_PROVISIONED, files.store().load().source)
    }

    @Test
    fun `a delete that fails is reported and the installed package stays active`() {
        val files = MemoryBankFileSystem(deleteFails = true)
        files.store().install(bankWithBuiltAt(newStamp))

        assertEquals(BankDiscardResult.Failed, files.store().discardInstalled())

        val loaded = files.store().load()
        assertEquals(BankSource.INSTALLED, loaded.source)
        assertEquals(newStamp, loaded.loaded!!.identity.builtAt)
    }

    @Test
    fun `a package whose header count disagrees with its body is rejected`() {
        // The model count is the field every later array is sized against, so a package
        // that declares two models but carries six cannot parse into anything coherent.
        // Upstream expresses this as a hash binding; here it is structural.
        val files = MemoryBankFileSystem(installed = null)
        val reader = BankReader(fixtureBankBytes())
        reader.expectMagic("LMFPA002")
        reader.string() // source reference digest
        reader.string() // build stamp
        reader.string() // reference digest
        val at = reader.consumed
        val mangled = fixtureBankBytes()
        mangled[at] = 2
        mangled[at + 1] = 0
        mangled[at + 2] = 0
        mangled[at + 3] = 0

        val result = files.store().install(mangled)

        assertTrue("模型数量与包体不一致的包必须被拒绝", result is BankInstallResult.Rejected)
        assertNull(files.installed)
    }

    /** A package header that stops right after the model count, for the bounds checks. */
    private fun headerOnly(modelCount: Int): ByteArray {
        fun u32(value: Int) = byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

        fun text(value: String) = u32(value.length) + value.toByteArray(Charsets.UTF_8)

        return "LMFPA002".toByteArray() +
            text("a") + text("2026-09-30T00:00:00+00:00") + text("b") + u32(modelCount)
    }
}
