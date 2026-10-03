package com.relaytester.app

import com.relaytester.app.core.fingerprint.BankDiscardResult
import com.relaytester.app.core.fingerprint.BankInstallResult
import com.relaytester.app.core.fingerprint.BankReader
import com.relaytester.app.core.fingerprint.BankSource
import com.relaytester.app.core.fingerprint.FingerprintBank
import com.relaytester.app.core.fingerprint.FingerprintBankStore
import com.relaytester.app.core.fingerprint.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        assertTrue("必须说明超出上限", loaded.problem!!.contains("大小上限"))
    }

    @Test
    fun `an oversize installed package is refused before its bytes are read`() {
        // The cap exists to bound memory, so a package over it must not be materialised
        // first: the whole point is to never hold an attacker-sized buffer.
        val oversize = ByteArray(FingerprintBankStore.MAX_INSTALLED_BYTES.toInt() + 1)
        val files = MemoryBankFileSystem(installed = oversize)

        files.store().load()

        assertEquals("超限文件不得被读入内存", 0, files.installedReads)
    }

    @Test
    fun `a missing installed file still adopts the rollback copy`() {
        // An install whose final rename failed leaves the new bytes staged and the old
        // package as the only rollback: the panel must use it instead of claiming there
        // is nothing and demanding a fresh download.
        val files = MemoryBankFileSystem(installed = null, backup = fixtureBankBytes())

        val loaded = files.store().load()

        assertEquals(BankSource.INSTALLED, loaded.source)
        assertTrue(loaded.usedBackup)
        assertNotNull(loaded.loaded)
        assertEquals(shippedBankBuiltAt(), loaded.loaded!!.identity.builtAt)
        assertTrue("必须说明已经回退", loaded.problem!!.contains("回退"))
    }

    @Test
    fun `removing a primary package needs no backup delete when no copy exists`() {
        val files = MemoryBankFileSystem(deleteBackupFails = true)
        assertEquals(BankDiscardResult.Removed, files.store().discardInstalled())
        assertNull(files.installed)
        assertEquals(BankSource.NOT_PROVISIONED, files.store().load().source)
    }

    @Test
    fun `a surviving rollback copy prevents a false successful removal`() {
        val primary = bankWithBuiltAt(newStamp)
        val files = MemoryBankFileSystem(
            installed = primary,
            backup = fixtureBankBytes(),
            deleteBackupFails = true,
        )
        val store = files.store()
        assertEquals(newStamp, store.load().loaded!!.identity.builtAt)

        assertEquals(BankDiscardResult.Failed, store.discardInstalled())

        assertTrue(primary.contentEquals(files.installed))
        assertNotNull(files.backup)
        assertEquals(newStamp, store.load().loaded!!.identity.builtAt)
        assertEquals(newStamp, files.store().load().loaded!!.identity.builtAt)
    }

    @Test
    fun `removing the package in use also removes the rollback copy it fell back to`() {
        // The copy is the active package in this state (see the adoption test above), so a
        // removal that left it behind would be undone by the next probe: the package would
        // come back after a restart even though the panel said it was gone.
        val files = MemoryBankFileSystem(installed = null, backup = fixtureBankBytes())
        val store = files.store()
        assertEquals(BankSource.INSTALLED, store.load().source)

        assertEquals(BankDiscardResult.Removed, store.discardInstalled())

        assertNull("回退副本必须一起删除", files.backup)
        // A fresh store stands in for the next launch: nothing may be adopted again.
        assertEquals(BankSource.NOT_PROVISIONED, files.store().load().source)
    }

    @Test
    fun `a copy that cannot be removed keeps the panel provisioned`() {
        // The other side of the same rule: when the copy carries the panel and it cannot be
        // deleted, the removal did not happen and the panel must not claim otherwise.
        val files = MemoryBankFileSystem(
            installed = null,
            backup = fixtureBankBytes(),
            deleteBackupFails = true,
        )

        assertEquals(BankDiscardResult.Failed, files.store().discardInstalled())

        assertNotNull(files.backup)
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
        val bytes = "LMFPA003".toByteArray() + byteArrayOf(0x10, 0, 0, 0)

        val result = files.store().install(bytes)

        assertTrue(result is BankInstallResult.Rejected)
        assertNull(files.installed)
    }

    @Test
    fun `an environment template of another width is rejected`() {
        // Both sides of the width: a package assembled for a wider or narrower feature
        // vector would otherwise install and then throw - or silently drop coordinates -
        // while scoring, which is the one inconsistency a single packed file can hide.
        for (width in listOf(78, 70)) {
            // Asserted on the parse, because that is where the dimension is named: the
            // store deliberately answers a download with one generic message, and a
            // desynchronised stream would fail here with "长度与内容不一致" instead.
            val error = runCatching { FingerprintBank.fromPackageBytes(bankWithEnvironmentColumns(width)) }
                .exceptionOrNull()

            assertTrue("宽度 $width 未被拒绝", error != null)
            assertTrue(
                "宽度 $width 的拒绝原因不是维度校验：${error?.message}",
                error?.message.orEmpty().contains("环境模板维度"),
            )
            val files = MemoryBankFileSystem(installed = null)
            assertTrue(files.store().install(bankWithEnvironmentColumns(width)) is BankInstallResult.Rejected)
            assertNull(files.installed)
        }
    }

    @Test
    fun `the fixture itself is accepted at the width the checker expects`() {
        // The rewrite above only means something while this holds: the checker accepts
        // exactly one width, so a fixture that parses proves its own environment
        // templates agree with it and the rejection above cannot be an artefact of the
        // fixture being wrong in the first place.
        assertTrue(BankFixtures.bank().modelCount > 0)
    }

    @Test
    fun `a legacy package truncated inside its verifier block is rejected`() {
        // The legacy shape carries a verifier block the app no longer scores, so the skip
        // steps over it by length. A truncated one must still be refused rather than
        // walked off the end of: the skip goes through the same capacity check every other
        // read does, so no fraction of the file can desynchronise into a valid package.
        val legacy = BankFixtures.legacyPackageBytes()
        for (fraction in listOf(0.5, 0.6, 0.75, 0.9)) {
            val cut = legacy.copyOf((legacy.size * fraction).toInt())
            val error = runCatching { FingerprintBank.fromPackageBytes(cut) }.exceptionOrNull()
            assertTrue(
                "截断到 ${(fraction * 100).toInt()}% 的旧格式包未被拒绝",
                error is IllegalArgumentException,
            )
            val files = MemoryBankFileSystem(installed = null)
            assertTrue(files.store().install(cut) is BankInstallResult.Rejected)
            assertNull(files.installed)
        }
        // The whole file is accepted, so the loop above is not just proving the fixture
        // is unreadable in the first place.
        assertEquals(6, FingerprintBank.fromPackageBytes(legacy).modelCount)
    }

    @Test
    fun `a reference tensor in an unsupported code width is rejected`() {
        // The reference tensor's `[bits][columns][models]` header is read from the file, so
        // a package claiming a width other than the two that exist has to be refused. Both
        // readers are pinned: the current one builds the tensor, the legacy verifier skip
        // only steps over it, and a guessed stride in either would desynchronise the rest
        // of the package instead of failing here.
        for (bits in listOf(0, 3, 32)) {
            val buildError = assertThrows(IllegalArgumentException::class.java) {
                BankReader(referenceTensorHeader(bits)).quantizedReferences()
            }
            val skipError = assertThrows(IllegalArgumentException::class.java) {
                BankReader(referenceTensorHeader(bits)).skipQuantizedReferences()
            }
            assertTrue("位宽 $bits 的构读未被拒绝：${buildError.message}", buildError.message.orEmpty().contains("位宽"))
            assertTrue("位宽 $bits 的跳读未被拒绝：${skipError.message}", skipError.message.orEmpty().contains("位宽"))
        }
    }

    /** Enough of `[bits][columns][models]` for either reader to reach the width check. */
    private fun referenceTensorHeader(bits: Int): ByteArray {
        val bytes = ByteArray(64)
        for (index in 0 until 4) {
            bytes[index] = ((bits ushr (index * 8)) and 0xFF).toByte()
            bytes[4 + index] = ((4 ushr (index * 8)) and 0xFF).toByte()
            bytes[8 + index] = 1.toByte()
        }
        return bytes
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
        reader.expectMagic("LMFPA003")
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

        return "LMFPA003".toByteArray() +
            text("a") + text("2026-09-30T00:00:00+00:00") + text("b") + u32(modelCount)
    }
}
