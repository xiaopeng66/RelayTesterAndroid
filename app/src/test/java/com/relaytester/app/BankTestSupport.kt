package com.relaytester.app

import com.relaytester.app.core.update.HttpFetcher
import com.relaytester.app.core.fingerprint.BankFileSystem
import com.relaytester.app.core.fingerprint.BankReader
import com.relaytester.app.core.fingerprint.BankUpdateDefaults
import com.relaytester.app.core.fingerprint.FingerprintBank
import com.relaytester.app.core.fingerprint.FingerprintBankStore
import com.relaytester.app.core.fingerprint.sha256Hex
import com.relaytester.app.core.storage.UpdatePreferences
import com.relaytester.app.core.storage.UpdatePreferencesState
import com.relaytester.app.core.update.DownloadProgress
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

/**
 * The detection package the tests score against.
 *
 * A committed fixture built from the same upstream files the shipped package is built
 * from, restricted to six models (`build_fingerprint_asset.py --pick ...`). It is small
 * enough to keep in the repository while still exercising every code path: the ranker's
 * three terms, the calibrated probability and the family roll-up (the roll-up needs at
 * least two families to mean anything). Only the six-model roster distinguishes it from
 * the published package — the algorithm is identical, which is what makes the golden
 * vectors below meaningful.
 *
 * `lm-fingerprint/lite-bank-legacy.bin` is the same kind of fixture in the *previous*
 * package shape (magic `LMFPA002`, which still carries upstream's removed verifier
 * block). It exists so the legacy read path keeps a full golden-vector comparison
 * instead of only a parse smoke test.
 *
 * It is a *test* resource rather than an APK asset on purpose: the app ships without a
 * package and downloads one.
 */
internal object BankFixtures {
    private const val PACKAGE_RESOURCE = "lm-fingerprint/lite-bank-small.bin"

    /** The same kind of fixture in the pre-verifier-removal package shape. */
    private const val LEGACY_PACKAGE_RESOURCE = "lm-fingerprint/lite-bank-legacy.bin"

    val loader: ClassLoader get() = BankFixtures::class.java.classLoader!!

    fun packageBytes(): ByteArray {
        val stream = loader.getResourceAsStream(PACKAGE_RESOURCE)
            ?: error("找不到检测包夹具 $PACKAGE_RESOURCE")
        return stream.use { it.readBytes() }
    }

    fun bank(bytes: ByteArray = packageBytes()): FingerprintBank = FingerprintBank.fromPackageBytes(bytes)

    fun legacyPackageBytes(): ByteArray {
        val stream = loader.getResourceAsStream(LEGACY_PACKAGE_RESOURCE)
            ?: error("找不到旧格式检测包夹具 $LEGACY_PACKAGE_RESOURCE")
        return stream.use { it.readBytes() }
    }

    fun legacyBank(): FingerprintBank = FingerprintBank.fromPackageBytes(legacyPackageBytes())

    fun builtAt(): String = bank().referenceBuiltAt

    fun modelCount(): Int = bank().modelCount

    fun minimumValidNumbers(): Int = bank().minimumValidNumbers
}

/** The fixture package's bytes; the name the older tests used. */
internal fun fixtureBankBytes(): ByteArray = BankFixtures.packageBytes()

/** The reference build stamp the fixture carries. */
internal fun shippedBankBuiltAt(): String = BankFixtures.builtAt()

internal fun shippedBankModelCount(): Int = BankFixtures.modelCount()

/**
 * The fixture with a different build stamp, same length so the layout is untouched.
 *
 * A same-length rewrite keeps every following offset valid, which is what makes this a
 * usable stand-in for "the publisher released a new package": it parses, it is a valid
 * package, and it is recognisably not the fixture.
 */
internal fun bankWithBuiltAt(stamp: String): ByteArray {
    val original = BankFixtures.builtAt()
    require(stamp.length == original.length) {
        "构建时间戳长度必须一致：${original.length} != ${stamp.length}"
    }
    val bytes = BankFixtures.packageBytes()
    val needle = original.toByteArray(Charsets.UTF_8)
    val at = bytes.indexOfSlice(needle)
    require(at >= 0) { "检测包里找不到构建时间戳" }
    require(bytes.indexOfSlice(needle, at + 1) < 0) { "构建时间戳在检测包里出现了多次" }
    stamp.toByteArray(Charsets.UTF_8).copyInto(bytes, at)
    // Self-check: a patch that landed somewhere else would produce a different stamp.
    require(FingerprintBank.fromPackageBytes(bytes).referenceBuiltAt == stamp) {
        "构建时间戳改写未生效"
    }
    return bytes
}

/** Index of [needle] in this array, or -1; the stdlib only searches single bytes. */
private fun ByteArray.indexOfSlice(needle: ByteArray, from: Int = 0): Int {
    if (needle.isEmpty()) return from
    outer@ for (start in from..(size - needle.size)) {
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) continue@outer
        }
        return start
    }
    return -1
}

/**
 * The fixture with a different validity floor.
 *
 * The floor is an f64 in the header, right after `tau` and `recommended_queries`, so the
 * offset is derived by walking the header rather than hard-coded: a hard-coded offset
 * would silently patch the wrong double the next time the header changes shape.
 */
internal fun bankWithMinimumValid(value: Int): ByteArray {
    val bytes = BankFixtures.packageBytes()
    val reader = BankReader(bytes)
    reader.expectMagic("LMFPA003")
    reader.string() // source reference digest
    reader.string() // build stamp
    reader.string() // reference digest
    val modelCount = reader.u32()
    repeat(modelCount) {
        reader.string()
        reader.string()
        reader.string()
        reader.string()
    }
    reader.float64() // tau
    reader.float64() // recommended queries
    val at = reader.consumed
    val bits = java.lang.Double.doubleToLongBits(value.toDouble())
    for (index in 0 until 8) {
        // The format stores f64 little-endian, so the first byte holds the low bits.
        val shift = 8 * index
        bytes[at + index] = ((bits shr shift) and 0xFF).toByte()
    }
    require(FingerprintBank.fromPackageBytes(bytes).minimumValidNumbers == value) {
        "有效数字下限改写未生效"
    }
    return bytes
}

/**
 * The fixture with its temperature replaced, for the calibration guard.
 *
 * `tau` sits in the header right after the model block, so the offset comes from walking
 * the header: a hard-coded one would patch the wrong double the next time the shape moves.
 */
internal fun bankWithTau(value: Double): ByteArray {
    val bytes = BankFixtures.packageBytes()
    val reader = BankReader(bytes)
    reader.expectMagic("LMFPA003")
    reader.string() // source reference digest
    reader.string() // build stamp
    reader.string() // reference digest
    repeat(reader.u32()) { repeat(4) { reader.string() } }
    val at = reader.consumed
    val bits = java.lang.Double.doubleToLongBits(value)
    for (index in 0 until 8) {
        // Little-endian f64: the first byte holds the low bits.
        bytes[at + index] = ((bits shr (8 * index)) and 0xFF).toByte()
    }
    return bytes
}

/** A copy of the fixture carrying one extra byte, which no reader should accept. */
internal fun bankWithTrailingByte(): ByteArray = BankFixtures.packageBytes() + byteArrayOf(0)

/**
 * The fixture re-packed with an environment section whose rows are [columns] wide.
 *
 * Patching only the width field would desynchronise the stream — every array is sized
 * from the file, so the rows behind it would be read at the wrong stride and the package
 * would be refused for "length does not match content" instead. The section is therefore
 * rebuilt at the new width, which is what a package assembled for another feature width
 * really looks like.
 */
private fun bankReaderAtEnvironments(bytes: ByteArray): BankReader {
    val reader = BankReader(bytes)
    reader.expectMagic("LMFPA003")
    reader.string() // source reference digest
    reader.string() // build stamp
    reader.string() // reference digest
    val modelCount = reader.u32()
    repeat(modelCount) { repeat(4) { reader.string() } }
    reader.float64() // tau
    reader.float64() // recommended queries
    reader.float64() // minimum valid numbers

    fun params() {
        val blocks = reader.u32()
        repeat(blocks) {
            val size = reader.u32()
            reader.floats(size)
            reader.floats(size)
        }
    }

    fun featureBank() {
        val size = reader.u32()
        reader.floats(size)
        reader.floats(size)
        val basisRows = reader.u32()
        if (basisRows > 0) reader.matrix(basisRows, reader.u32())
        reader.matrix(reader.u32(), size)
    }

    params() // LDA standardiser
    reader.matrix(reader.u32(), reader.u32()) // LDA weights
    reader.floats(reader.u32()) // LDA bias
    params() // full-feature standardiser
    featureBank() // Hellinger bank
    featureBank() // ordered bank
    return reader
}

internal fun bankWithEnvironmentColumns(columns: Int): ByteArray {
    val bytes = BankFixtures.packageBytes()
    val reader = bankReaderAtEnvironments(bytes)
    val environmentCount = reader.u32()
    val models = reader.u32()
    val storedColumns = reader.u32()
    val widthAt = reader.consumed - Int.SIZE_BYTES
    require(environmentCount > 0 && models > 0) { "夹具的环境模板段是空的，改写没有意义" }
    require(columns != storedColumns) { "改写后的宽度与夹具相同：$storedColumns" }
    val storedBytes = environmentCount * models * storedColumns * Int.SIZE_BYTES

    val out = java.io.ByteArrayOutputStream(bytes.size + environmentCount * models * columns * Int.SIZE_BYTES)
    out.write(bytes, 0, widthAt)
    for (index in 0 until Int.SIZE_BYTES) out.write((columns shr (8 * index)) and 0xFF)
    // Zero rows: the row count and width are what the reader takes from the file, and a
    // zero template still passes every finiteness check the package runs.
    out.write(ByteArray(environmentCount * models * columns * Int.SIZE_BYTES))
    out.write(bytes, widthAt + Int.SIZE_BYTES + storedBytes, bytes.size - widthAt - Int.SIZE_BYTES - storedBytes)
    require(out.size() != bytes.size) { "改写后的长度与夹具相同" }
    return out.toByteArray()
}

/**
 * An in-memory [BankFileSystem].
 *
 * The real one needs a Context, and none of this behaviour needs a device: whether a
 * file is present, whether reading it fails, whether a write lands or a delete fails
 * are all just states of this fake.
 *
 * [installed] defaults to the fixture, because "the app has a detection package" is the
 * ordinary state once a device has been provisioned; a test that wants the first-run
 * state passes null.
 */
internal class MemoryBankFileSystem(
    var installed: ByteArray? = BankFixtures.packageBytes(),
    /** The rollback copy, as [BankFileSystem.writeInstalled] keeps it. */
    var backup: ByteArray? = null,
    /** Reading the installed package throws, standing in for an unreadable private file. */
    var readFails: Boolean = false,
    /** Writing throws, standing in for a full disk. */
    var writeFails: Boolean = false,
    /** Deleting throws, standing in for a file that cannot be removed. */
    var deleteFails: Boolean = false,
    /** Only the rollback copy refuses to delete; the installed package still goes. */
    var deleteBackupFails: Boolean = false,
) : BankFileSystem {
    /** How many times the installed file's bytes were actually read. */
    var installedReads = 0
        private set

    override fun installedLength(): Long? = installed?.size?.toLong()

    override fun backupLength(): Long? = backup?.size?.toLong()

    override fun readInstalled(): ByteArray? {
        installedReads++
        if (installed != null && readFails) throw IOException("读取已安装检测包失败")
        return installed
    }

    override fun readBackup(): ByteArray? = backup

    override fun writeInstalled(bytes: ByteArray) {
        if (writeFails) throw IOException("写入检测包失败")
        // Mirrors AndroidBankFileSystem: the outgoing file becomes the rollback copy.
        installed?.let { backup = it }
        installed = bytes
    }

    override fun deleteInstalled() {
        if (deleteFails) throw IOException("删除检测包失败")
        installed = null
    }

    override fun deleteBackup() {
        if (deleteFails || deleteBackupFails) throw IOException("删除检测包备份失败")
        backup = null
    }

    fun store(maxInstalledBytes: Long = FingerprintBankStore.MAX_INSTALLED_BYTES) =
        FingerprintBankStore(this, maxInstalledBytes)
}

/** A manifest body for [bankBytes], as the publisher would write it. */
internal fun manifestJson(
    bankBytes: ByteArray,
    builtAt: String = "2026-09-30T05:12:31+00:00",
    url: String = "https://example.test/bank/lite-bank.bin",
    modelCount: Int = FingerprintBank.fromPackageBytes(bankBytes).modelCount,
    minAppVersionCode: Long = 0L,
    sha256: String = sha256Hex(bankBytes),
    sizeBytes: Long = bankBytes.size.toLong(),
    formatVersion: Int = 3,
): String = JSONObject().apply {
    put("formatVersion", formatVersion)
    put("builtAt", builtAt)
    put("referenceSha256", "0000000000000000000000000000000000000000000000000000000000000000")
    put("modelCount", modelCount)
    put("sizeBytes", sizeBytes)
    put("sha256", sha256)
    put("url", url)
    put("minAppVersionCode", minAppVersionCode)
}.toString()

/**
 * A [HttpFetcher] that answers from memory.
 *
 * Records the URLs it saw, so a test can tell "no check was attempted" from "a check
 * was attempted and failed".
 */
internal class FakeHttpFetcher(
    var manifestBody: String = "",
    /** The body served for a non-JSON URL: the detection package, or an app APK. */
    var body: ByteArray = ByteArray(0),
    /** When set, every fetch throws it. */
    var failure: Throwable? = null,
) : HttpFetcher {
    val urls = mutableListOf<String>()

    /**
     * How many fetches were cancelled while parked on [gate].
     *
     * A check that stands down for another one is cancelled rather than ignored, and the
     * difference is invisible from the outside: both leave the state looking the same. This
     * counter is what makes "the quiet check was actually taken down" assertable, because a
     * fetch that is merely left running would still answer its own request later.
     */
    var cancellations = 0

    /** Parks every fetch until the test releases it, so a job can be held in flight. */
    var gate: CompletableDeferred<Unit>? = null

    /**
     * Bodies by URL suffix, for a test that serves both feeds at once.
     *
     * The default routing (`.json` gets [manifestBody], anything else [body]) cannot tell
     * the two manifests apart, and the update page shows both channels together.
     */
    private val responses = mutableMapOf<String, ByteArray>()

    /** Serves [bytes] for any URL ending in [suffix], ahead of the default routing. */
    fun serve(suffix: String, bytes: ByteArray) {
        responses[suffix] = bytes
    }

    /** Serves [text] as UTF-8 for any URL ending in [suffix]. */
    fun serve(suffix: String, text: String) = serve(suffix, text.toByteArray(Charsets.UTF_8))

    private fun bodyFor(url: String): ByteArray =
        responses.entries.firstOrNull { url.endsWith(it.key) }?.value
            ?: if (url.endsWith(".json")) manifestBody.toByteArray(Charsets.UTF_8) else body

    override suspend fun fetch(
        url: String,
        maxBytes: Int,
        onProgress: (DownloadProgress) -> Unit,
    ): ByteArray {
        urls += url
        val bytes = bodyFor(url)
        // Reports the way the real fetcher does: the declared length before any body
        // bytes, then the finished count. A test can therefore catch the panel mid-download
        // by parking [gate] here, between the two.
        onProgress(DownloadProgress(bytesRead = 0, totalBytes = bytes.size.toLong()))
        try {
            gate?.await()
        } catch (error: CancellationException) {
            cancellations++
            throw error
        }
        failure?.let { throw it }
        onProgress(DownloadProgress(bytesRead = bytes.size.toLong(), totalBytes = bytes.size.toLong()))
        return bytes
    }

    override suspend fun downloadTo(
        url: String,
        target: File,
        maxBytes: Long,
        onProgress: (DownloadProgress) -> Unit,
    ): File {
        val bytes = fetch(url, maxBytes.toInt(), onProgress)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return target
    }

    /** Points the fake at a publisher that has [bytes] and describes them honestly. */
    fun publish(bytes: ByteArray, builtAt: String = "2026-09-30T05:12:31+00:00") {
        body = bytes
        manifestBody = manifestJson(bytes, builtAt = builtAt, url = BANK_URL)
    }

    companion object {
        const val BANK_URL = "https://example.test/bank/lite-bank.bin"
        val MANIFEST_URL: String get() = BankUpdateDefaults.MANIFEST_URL
    }
}

/**
 * An in-memory [UpdatePreferences].
 *
 * The real one needs a Context and a disk; the view model only asks what the two switches
 * are and records changes, so both live in a field here. [state] is exposed so a test can
 * assert what was actually written rather than what the panel is showing.
 */
internal class MemoryUpdatePreferences(
    var state: UpdatePreferencesState = UpdatePreferencesState(),
    /** When set, every write throws it, standing in for a disk that refuses. */
    var writeFails: Boolean = false,
    /**
     * When set, every write is accepted and then forgotten.
     *
     * A third state beside "writes land" and "writes throw": a store that keeps reporting the
     * value it had before the write, which is what the throttle sees when a write was lost.
     */
    var dropWrites: Boolean = false,
) : UpdatePreferences(null) {
    override suspend fun read(): UpdatePreferencesState = state

    override suspend fun setAutoCheckBank(enabled: Boolean) {
        if (writeFails) throw IOException("写入更新开关失败")
        if (dropWrites) return
        state = state.copy(autoCheckBank = enabled)
    }

    override suspend fun setAutoCheckApp(enabled: Boolean) {
        if (writeFails) throw IOException("写入更新开关失败")
        if (dropWrites) return
        state = state.copy(autoCheckApp = enabled)
    }

    override suspend fun setLastAppCheckAt(millis: Long) {
        if (writeFails) throw IOException("写入更新开关失败")
        if (dropWrites) return
        state = state.copy(lastAppCheckAt = millis)
    }
}
