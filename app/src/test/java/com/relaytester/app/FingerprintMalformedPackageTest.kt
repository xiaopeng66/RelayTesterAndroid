package com.relaytester.app

import com.relaytester.app.core.fingerprint.BankFileSystem
import com.relaytester.app.core.fingerprint.BankInstallResult
import com.relaytester.app.core.fingerprint.BankReader
import com.relaytester.app.core.fingerprint.BankSource
import com.relaytester.app.core.fingerprint.FingerprintBank
import com.relaytester.app.core.fingerprint.FingerprintBankStore
import com.relaytester.app.core.fingerprint.FingerprintScoring
import com.relaytester.app.core.fingerprint.NumberFeatures
import com.relaytester.app.core.fingerprint.ProbabilityStatus
import com.relaytester.app.core.fingerprint.QuantizedReferences
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Malformed inputs must fail at the package boundary, not poison an installed ranker. */
class FingerprintMalformedPackageTest {
    @Test
    fun `environment widths are refused before reading their missing payload`() {
        // Deliberately tiny: the old reader reaches a bogus reference header, not OOM.
        // This pins the early dimension gate without making a test worker allocate a bomb.
        for (columns in listOf(0, 70, 78)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                FingerprintBank.fromPackageBytes(environmentPrefix(4, 6, columns, 64))
            }
            assertTrue("width $columns: ${error.message}", error.message.orEmpty().contains("环境模板维度"))
        }
    }

    @Test
    fun `environment model counts are refused before reading their missing payload`() {
        for (models in listOf(0, 1)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                FingerprintBank.fromPackageBytes(environmentPrefix(4, models, 74, 64))
            }
            assertTrue("models $models: ${error.message}", error.message.orEmpty().contains("环境模板与模型数量"))
        }
    }

    @Test
    fun `all environment matrices must fit before any matrix is built`() {
        // Enough for one matrix, not four. The error must name the outer section rather
        // than fail while reading a later row. All counts stay small on the old reader.
        val error = assertThrows(IllegalArgumentException::class.java) {
            FingerprintBank.fromPackageBytes(environmentPrefix(4, 6, 74, 6 * 74 * 4 + 16))
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("环境模板已截断"))
    }

    @Test
    fun `a zero scale in any standardizer is rejected without replacing the installed bank`() {
        val bytes = BankFixtures.packageBytes()
        for ((name, at) in offsets(bytes).scales) {
            val patched = bytes.copyOf().also { putInt(it, at, 0) }
            assertRejectedPreservingBank(patched, name)
        }
    }

    @Test
    fun `invalid reference distance arithmetic is not a perfect match`() {
        val reference = QuantizedReferences(1, 1, 8, floatArrayOf(1.0f), byteArrayOf(1))
        assertTrue(reference.squaredDistance(doubleArrayOf(Double.NaN), 0, Double.NaN).isNaN())
        assertEquals(0.0, reference.squaredDistance(doubleArrayOf(1.0), 0, 1.0), 0.0)
    }

    @Test
    fun `contradictory source and reference digests are rejected in every supported format`() {
        for (bytes in packages()) {
            val reader = headerReader(bytes)
            reader.string()
            reader.string()
            val referenceAt = reader.consumed + Int.SIZE_BYTES
            val patched = bytes.copyOf().also {
                it[referenceAt] = if (it[referenceAt] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte()
            }
            assertRejectedPreservingBank(patched, String(bytes, 0, 8, Charsets.US_ASCII))
        }
    }

    @Test
    fun `an absent source digest does not invent a provenance requirement`() {
        for (bytes in packages()) {
            val reader = headerReader(bytes)
            reader.string()
            val withoutSource = bytes.copyOfRange(0, 8) + byteArrayOf(0, 0, 0, 0) +
                bytes.copyOfRange(reader.consumed, bytes.size)
            assertEquals(6, FingerprintBank.fromPackageBytes(withoutSource).modelCount)
        }
    }

    @Test
    fun `an empty environment section is rejected without replacing the installed bank`() {
        val bytes = BankFixtures.packageBytes()
        val layout = offsets(bytes)
        val patched = bytes.copyOfRange(0, layout.environment) + byteArrayOf(0, 0, 0, 0) +
            bytes.copyOfRange(layout.environmentEnd, bytes.size)
        assertRejectedPreservingBank(patched, "empty environments")
    }

    @Test
    fun `the validity floor must be a positive finite integer before conversion`() {
        val bytes = BankFixtures.packageBytes()
        val at = offsets(bytes).scalars + 16
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            -1.0, 0.0, 80.5, Int.MAX_VALUE.toDouble() + 1.0)) {
            assertRejectedPreservingBank(bytes.copyOf().also { putDouble(it, at, value) }, "floor $value")
        }
    }

    @Test
    fun `finite huge blend scores cannot claim calibrated NaN probabilities`() {
        val bytes = BankFixtures.packageBytes()
        val layout = offsets(bytes)
        putDouble(bytes, layout.blend, 1.0e306)
        putDouble(bytes, layout.blend + 8, 0.0)
        putDouble(bytes, layout.scalars, 1000.0)
        val bank = FingerprintBank.fromPackageBytes(bytes)
        val analysis = bank.analyze(List(3) { (1..300).joinToString(",") }, List(3) { 300 })

        assertEquals(FingerprintScoring.FULL, analysis.scoring)
        assertTrue(analysis.candidates.isNotEmpty())
        assertTrue(analysis.candidates.all { it.rankingScore.isFinite() })
        assertEquals(ProbabilityStatus.UNAVAILABLE, analysis.probabilityStatus)
        assertTrue(analysis.candidates.all { it.probability == null })
    }

    @Test
    fun `an unusable tau still installs and ranks without a probability`() {
        val answers = List(3) { (1..300).joinToString(",") }
        for (tau in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0, 1.0e9)) {
            val bytes = bankWithTau(tau)
            val files = MemoryBankFileSystem(installed = null)
            val store = files.store()
            assertTrue("tau $tau", store.install(bytes) is BankInstallResult.Installed)
            val analysis = store.load().loaded!!.bank.analyze(answers, List(3) { 300 })
            assertEquals(ProbabilityStatus.UNAVAILABLE, analysis.probabilityStatus)
            assertTrue(analysis.candidates.isNotEmpty())
            assertTrue(analysis.candidates.all { it.rankingScore.isFinite() && it.probability == null })
        }
    }

    @Test
    fun `an oversized backup is not read when the primary is missing`() {
        assertOversizedBackupNotRead(null, BankSource.NOT_PROVISIONED)
    }

    @Test
    fun `an oversized backup is not read when the primary is broken`() {
        assertOversizedBackupNotRead("broken".toByteArray(), BankSource.INSTALLED_UNREADABLE)
    }

    @Test
    fun `supplementary letters split runs but supplementary punctuation does not`() {
        for (letter in listOf(0x1D400, 0x20000)) {
            val separator = String(Character.toChars(letter))
            assertArrayEquals(intArrayOf(3, 4, 5), NumberFeatures.parseNumbers("1,2 $separator 3,4,5"))
        }
        val separator = String(Character.toChars(0x1F642))
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5), NumberFeatures.parseNumbers("1,2 $separator 3,4,5"))
    }

    @Test
    fun `two short runs separated by a supplementary letter cannot pass the validity floor`() {
        val short = (1..100).joinToString(",")
        val answer = "$short ${String(Character.toChars(0x1D400))} $short"
        assertThrows(IllegalArgumentException::class.java) {
            BankFixtures.bank().analyze(List(3) { answer }, List(3) { 300 })
        }
    }

    private fun assertRejectedPreservingBank(bytes: ByteArray, description: String) {
        val good = BankFixtures.packageBytes()
        val files = MemoryBankFileSystem(installed = good)
        val store = files.store()
        val identity = store.load().loaded!!.identity
        assertTrue(description, store.install(bytes) is BankInstallResult.Rejected)
        assertArrayEquals(description, good, files.installed)
        val reopened = files.store().load()
        assertEquals(description, identity, reopened.loaded!!.identity)
        assertFalse(description, reopened.usedBackup)
    }

    private fun assertOversizedBackupNotRead(primary: ByteArray?, expectedSource: BankSource) {
        val memory = MemoryBankFileSystem(installed = primary, backup = BankFixtures.packageBytes())
        var reads = 0
        val files = object : BankFileSystem by memory {
            override fun backupLength(): Long = FingerprintBankStore.MAX_INSTALLED_BYTES + 1
            override fun readBackup(): ByteArray? {
                reads++
                return memory.readBackup()
            }
        }
        val result = FingerprintBankStore(files).load()
        assertNull("oversized backup must not be adopted", result.loaded)
        assertEquals(expectedSource, result.source)
        assertEquals("size ceiling must precede the read", 0, reads)
    }

    private fun packages(): List<ByteArray> = listOf(
        BankFixtures.packageBytes(), BankFixtures.v1PackageBytes(), BankFixtures.legacyPackageBytes(),
    )

    private fun headerReader(bytes: ByteArray) = BankReader(bytes).apply { expectMagic(magic()) }

    private fun putInt(bytes: ByteArray, at: Int, value: Int) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value)
    }

    private fun putDouble(bytes: ByteArray, at: Int, value: Double) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putDouble(at, value)
    }

    private fun environmentPrefix(environments: Int, models: Int, columns: Int, padding: Int): ByteArray {
        val bytes = BankFixtures.packageBytes()
        val at = offsets(bytes).environment
        return bytes.copyOf(at + 12 + padding).also {
            it.fill(0, at)
            putInt(it, at, environments)
            putInt(it, at + 4, models)
            putInt(it, at + 8, columns)
        }
    }

    private data class Offsets(
        val scalars: Int,
        val scales: List<Pair<String, Int>>,
        val environment: Int,
        val environmentEnd: Int,
        val blend: Int,
    )

    /** Walk only the valid fixture; malformed packages are always exercised through the real parser/store. */
    private fun offsets(bytes: ByteArray): Offsets {
        val reader = headerReader(bytes)
        repeat(3) { reader.string() }
        repeat(reader.u32()) { repeat(4) { reader.string() } }
        val scalars = reader.consumed
        repeat(3) { reader.float64() }
        val scales = mutableListOf<Pair<String, Int>>()
        fun params(name: String) {
            repeat(reader.u32()) { index ->
                val width = reader.u32()
                reader.skip(width, 4)
                scales += "$name $index" to reader.consumed
                reader.skip(width, 4)
            }
        }
        params("head")
        reader.skipMatrix()
        reader.skipFloats()
        params("full")
        repeat(2) { index ->
            val width = reader.u32()
            reader.skip(width, 4)
            scales += "feature bank $index" to reader.consumed
            reader.skip(width, 4)
            val rows = reader.u32()
            if (rows > 0) reader.skip(rows * reader.u32(), 4)
            reader.skip(reader.u32() * width, 4)
        }
        val environment = reader.consumed
        val environments = reader.u32()
        val models = reader.u32()
        val columns = reader.u32()
        reader.skip(environments * models * columns, 4)
        val environmentEnd = reader.consumed
        reader.skipQuantizedReferences()
        val blend = reader.consumed
        repeat(2) { reader.float64() }
        params("positional")
        return Offsets(scalars, scales, environment, environmentEnd, blend)
    }
}
