package com.relaytester.app

import com.relaytester.app.core.fingerprint.BankFetcher
import com.relaytester.app.core.fingerprint.BankFileSystem
import com.relaytester.app.core.fingerprint.BankReader
import com.relaytester.app.core.fingerprint.BankUpdateDefaults
import com.relaytester.app.core.fingerprint.FingerprintBank
import com.relaytester.app.core.fingerprint.FingerprintBankStore
import com.relaytester.app.core.fingerprint.sha256Hex
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

/**
 * The detection package the tests score against.
 *
 * A committed fixture built from the same upstream files the shipped package is built
 * from, restricted to six models (`build_fingerprint_asset.py --pick ...`). It is small
 * enough to keep in the repository while still exercising every code path: the ranker's
 * three terms, the verifier (whose ranking-margin feature needs at least two candidates)
 * and the family roll-up. Only the six-model roster distinguishes it from the published
 * package — the algorithm is identical, which is what makes the golden vectors below
 * meaningful.
 *
 * It is a *test* resource rather than an APK asset on purpose: the app ships without a
 * package and downloads one.
 */
internal object BankFixtures {
    private const val PACKAGE_RESOURCE = "lm-fingerprint/lite-bank-small.bin"

    val loader: ClassLoader get() = BankFixtures::class.java.classLoader!!

    fun packageBytes(): ByteArray {
        val stream = loader.getResourceAsStream(PACKAGE_RESOURCE)
            ?: error("找不到检测包夹具 $PACKAGE_RESOURCE")
        return stream.use { it.readBytes() }
    }

    fun bank(bytes: ByteArray = packageBytes()): FingerprintBank = FingerprintBank.fromPackageBytes(bytes)

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
    reader.expectMagic("LMFPA002")
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

/** A copy of the fixture carrying one extra byte, which no reader should accept. */
internal fun bankWithTrailingByte(): ByteArray = BankFixtures.packageBytes() + byteArrayOf(0)

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
    formatVersion: Int = 2,
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
 * A [BankFetcher] that answers from memory.
 *
 * Records the URLs it saw, so a test can tell "no check was attempted" from "a check
 * was attempted and failed".
 */
internal class FakeBankFetcher(
    var manifestBody: String = "",
    var bankBytes: ByteArray = ByteArray(0),
    /** When set, every fetch throws it. */
    var failure: Throwable? = null,
) : BankFetcher {
    val urls = mutableListOf<String>()

    /** Parks every fetch until the test releases it, so a job can be held in flight. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun fetch(url: String, maxBytes: Int): ByteArray {
        urls += url
        gate?.await()
        failure?.let { throw it }
        return if (url.endsWith(".json")) manifestBody.toByteArray(Charsets.UTF_8) else bankBytes
    }

    /** Points the fake at a publisher that has [bytes] and describes them honestly. */
    fun publish(bytes: ByteArray, builtAt: String = "2026-09-30T05:12:31+00:00") {
        bankBytes = bytes
        manifestBody = manifestJson(bytes, builtAt = builtAt, url = BANK_URL)
    }

    companion object {
        const val BANK_URL = "https://example.test/bank/lite-bank.bin"
        val MANIFEST_URL: String get() = BankUpdateDefaults.MANIFEST_URL
    }
}
