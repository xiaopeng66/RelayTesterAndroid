package com.relaytester.app

import com.relaytester.app.core.fingerprint.BankFetcher
import com.relaytester.app.core.fingerprint.BankFileSystem
import com.relaytester.app.core.fingerprint.BankUpdateDefaults
import com.relaytester.app.core.fingerprint.FingerprintBank
import com.relaytester.app.core.fingerprint.FingerprintBankStore
import com.relaytester.app.core.fingerprint.sha256Hex
import java.io.File
import java.io.IOException
import org.json.JSONObject

/**
 * The bytes of the bank actually packaged in the APK.
 *
 * Read from the module rather than from a fixture: the update policy is only worth
 * testing against the artifact that ships, and a parse failure here is a failure of
 * the artifact itself.
 */
internal fun shippedBankBytes(): ByteArray {
    val candidates = listOf(
        File("app/src/main/assets/lm-fingerprint/lite-bank.bin"),
        File("src/main/assets/lm-fingerprint/lite-bank.bin"),
    )
    val file = candidates.firstOrNull(File::isFile)
        ?: error("找不到指纹资产文件：${File(".").absolutePath}")
    return file.readBytes()
}

/** The reference build stamp the packaged bank carries. */
internal fun shippedBankBuiltAt(): String = FingerprintBank.fromAssetBytes(shippedBankBytes()).referenceBuiltAt

/**
 * The packaged bank with a different build stamp, same length so the layout is untouched.
 *
 * A same-length rewrite keeps every following offset valid, which is what makes this a
 * usable stand-in for "the publisher released a new bank": it parses, it is a valid
 * bank, and it is recognisably not the packaged one.
 */
internal fun bankWithBuiltAt(stamp: String): ByteArray {
    val original = shippedBankBuiltAt()
    require(stamp.length == original.length) {
        "构建时间戳长度必须一致：${original.length} != ${stamp.length}"
    }
    val bytes = shippedBankBytes()
    val needle = original.toByteArray(Charsets.UTF_8)
    val at = bytes.indexOfSlice(needle)
    require(at >= 0) { "资产里找不到构建时间戳" }
    require(bytes.indexOfSlice(needle, at + 1) < 0) { "构建时间戳在资产里出现了多次" }
    stamp.toByteArray(Charsets.UTF_8).copyInto(bytes, at)
    // Self-check: a patch that landed somewhere else would produce a different stamp.
    require(FingerprintBank.fromAssetBytes(bytes).referenceBuiltAt == stamp) { "构建时间戳改写未生效" }
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
 * The packaged bank with a different validity floor.
 *
 * The floor is the last value in the asset, so this rewrites the final eight bytes.
 * It stands in for a published bank that asks for more or fewer numbers per answer.
 */
internal fun bankWithMinimumValid(value: Int): ByteArray {
    val bytes = shippedBankBytes()
    val bits = java.lang.Double.doubleToLongBits(value.toDouble())
    for (index in 0 until 8) {
        // The format stores f64 little-endian, so the first byte holds the low bits.
        val shift = 8 * index
        bytes[bytes.size - 8 + index] = ((bits shr shift) and 0xFF).toByte()
    }
    require(FingerprintBank.fromAssetBytes(bytes).minimumValidNumbers == value) {
        "有效数字下限改写未生效"
    }
    return bytes
}

/** A copy of the packaged bank carrying one extra byte, which no reader should accept. */
internal fun bankWithTrailingByte(): ByteArray = shippedBankBytes() + byteArrayOf(0)

/**
 * An in-memory [BankFileSystem].
 *
 * The real one needs a Context, and none of this behaviour needs a device: whether a
 * file is present, whether reading it fails, whether a write lands or a delete fails
 * are all just states of this fake.
 */
internal class MemoryBankFileSystem(
    private val packaged: ByteArray = shippedBankBytes(),
    /** The installed bank, or null when none is installed. */
    var installed: ByteArray? = null,
    /** Reading the installed bank throws, standing in for an unreadable private file. */
    var readFails: Boolean = false,
    /** Writing throws, standing in for a full disk. */
    var writeFails: Boolean = false,
    /** Deleting throws, standing in for a file that cannot be removed. */
    var deleteFails: Boolean = false,
) : BankFileSystem {
    override fun readBuiltIn(): ByteArray = packaged

    override fun installedLength(): Long? = installed?.size?.toLong()

    override fun readInstalled(): ByteArray? {
        if (installed != null && readFails) throw IOException("读取已安装参考库失败")
        return installed
    }

    override fun writeInstalled(bytes: ByteArray) {
        if (writeFails) throw IOException("写入参考库失败")
        installed = bytes
    }

    override fun deleteInstalled() {
        if (deleteFails) throw IOException("删除参考库失败")
        installed = null
    }

    fun store(maxInstalledBytes: Long = FingerprintBankStore.MAX_INSTALLED_BYTES) =
        FingerprintBankStore(this, maxInstalledBytes)
}

/** A manifest body for [bankBytes], as the publisher would write it. */
internal fun manifestJson(
    bankBytes: ByteArray,
    builtAt: String = "2026-09-30T05:12:31+00:00",
    url: String = "https://example.test/bank/lite-bank.bin",
    modelCount: Int = FingerprintBank.fromAssetBytes(bankBytes).modelCount,
    minAppVersionCode: Long = 0L,
    sha256: String = sha256Hex(bankBytes),
    sizeBytes: Long = bankBytes.size.toLong(),
    formatVersion: Int = 1,
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

    override suspend fun fetch(url: String, maxBytes: Int): ByteArray {
        urls += url
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
