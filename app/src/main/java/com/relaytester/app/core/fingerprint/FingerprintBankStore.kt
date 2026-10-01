package com.relaytester.app.core.fingerprint

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Which copy of the reference bank a load resolved to. */
enum class BankSource {
    /** The copy packaged in the APK. */
    BUILT_IN,

    /** A copy the user installed over the network. */
    INSTALLED,

    /** A file is installed but no longer usable; the panel fell back to the packaged copy. */
    INSTALLED_UNREADABLE,
}

/** What the panel shows about the bank it is scoring with. */
data class BankIdentity(
    val source: BankSource,
    /** Reference build stamp from upstream, e.g. `2026-09-29T19:11:50+00:00`. */
    val builtAt: String,
    val modelCount: Int,
    val sizeBytes: Long,
    val sha256: String,
)

/** A parsed bank together with where it came from. */
data class LoadedBank(
    val bank: FingerprintBank,
    val identity: BankIdentity,
)

/** Outcome of an install attempt. */
sealed interface BankInstallResult {
    data class Installed(val identity: BankIdentity) : BankInstallResult

    /** The bytes were refused; [reason] is user-facing and nothing was written. */
    data class Rejected(val reason: String) : BankInstallResult
}

/** Outcome of dropping the installed bank. */
sealed interface BankDiscardResult {
    /** The packaged bank is the active one again. */
    data object Restored : BankDiscardResult

    /** Nothing was installed, so there was nothing to drop. */
    data object NothingInstalled : BankDiscardResult

    /** The file could not be removed; the installed bank is still the active one. */
    data object Failed : BankDiscardResult
}

/**
 * Where the two copies of the bank live.
 *
 * An interface so the store's policy — which copy wins, what counts as valid, how a
 * swap stays crash-safe — can be tested without an Android context.
 */
interface BankFileSystem {
    /** The bank packaged in the APK. */
    fun readBuiltIn(): ByteArray

    /** Length of an installed bank, or null when none exists. */
    fun installedLength(): Long?

    /** An installed bank, or null when none exists. */
    fun readInstalled(): ByteArray?

    /** Replaces the installed bank with [bytes]; a reader never sees a half-written file. */
    fun writeInstalled(bytes: ByteArray)

    fun deleteInstalled()
}

/** The real filesystem: assets for the packaged copy, a private file for the installed one. */
class AndroidBankFileSystem(context: Context) : BankFileSystem {
    private val appContext = context.applicationContext
    private val installed = File(appContext.filesDir, FingerprintBank.ASSET_PATH)

    override fun readBuiltIn(): ByteArray = try {
        appContext.assets.open(FingerprintBank.ASSET_PATH).use { it.readBytes() }
    } catch (error: IOException) {
        throw IllegalStateException("无法读取指纹参考库资产", error)
    }

    override fun installedLength(): Long? = if (installed.isFile) installed.length() else null

    override fun readInstalled(): ByteArray? =
        if (installed.isFile) installed.readBytes() else null

    override fun writeInstalled(bytes: ByteArray) {
        val directory = installed.parentFile
        if (directory != null && !directory.isDirectory && !directory.mkdirs()) {
            throw IOException("无法创建参考库目录")
        }
        // Staged next to the target so the rename below stays within one filesystem.
        val staging = File(directory, "${installed.name}.staging")
        try {
            staging.writeBytes(bytes)
            move(staging, installed)
        } catch (error: IOException) {
            staging.delete()
            throw error
        }
    }

    override fun deleteInstalled() {
        if (installed.isFile && !installed.delete()) {
            throw IOException("无法删除已安装的参考库")
        }
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * Picks between the packaged bank and one the user installed, and swaps them safely.
 *
 * An installed bank wins as long as it parses; if it stops parsing the packaged copy is
 * used and the panel is told, so a bad download can never leave the panel worse off than
 * a fresh install. Nothing is written until the whole file has been validated, and the
 * write itself is a rename, so an interrupted install leaves the previous bank intact.
 */
class FingerprintBankStore(
    private val files: BankFileSystem,
    /** Ceiling for an installed bank; the packaged one is ~400 KB and a download is not trusted. */
    private val maxInstalledBytes: Long = MAX_INSTALLED_BYTES,
) {
    private class Cached(
        val bank: FingerprintBank,
        val sizeBytes: Long,
        val sha256: String,
    )

    @Volatile
    private var builtIn: Cached? = null

    @Volatile
    private var installed: Cached? = null

    /** Set when a file is installed but unusable, so the panel can offer to drop it. */
    @Volatile
    private var installedUnreadable = false

    @Volatile
    private var probed = false

    /** The bank to score with, plus where it came from. */
    @Synchronized
    fun load(): LoadedBank {
        if (!probed) probe()
        val active = installed
        val source = when {
            active != null -> BankSource.INSTALLED
            installedUnreadable -> BankSource.INSTALLED_UNREADABLE
            else -> BankSource.BUILT_IN
        }
        val cached = active ?: packaged()
        return LoadedBank(
            bank = cached.bank,
            identity = BankIdentity(
                source = source,
                builtAt = cached.bank.referenceBuiltAt,
                modelCount = cached.bank.modelCount,
                sizeBytes = cached.sizeBytes,
                sha256 = cached.sha256,
            ),
        )
    }

    /**
     * Validates [bytes] and installs them as the active bank.
     *
     * The file is parsed in full before anything is written, so a rejected download
     * leaves the panel on the copy it was already using.
     */
    @Synchronized
    fun install(bytes: ByteArray): BankInstallResult {
        if (bytes.size.toLong() > maxInstalledBytes) {
            return BankInstallResult.Rejected(
                "文件过大（${bytes.size} 字节），超过 ${maxInstalledBytes / 1024 / 1024} MB 上限",
            )
        }
        val bank = parse(bytes)
            ?: return BankInstallResult.Rejected("文件不是有效的参考库，已放弃安装")
        val digest = sha256Hex(bytes)
        try {
            files.writeInstalled(bytes)
        } catch (error: IOException) {
            return BankInstallResult.Rejected(error.message ?: "写入参考库失败")
        }
        val cached = Cached(bank, bytes.size.toLong(), digest)
        installed = cached
        installedUnreadable = false
        probed = true
        return BankInstallResult.Installed(
            BankIdentity(
                source = BankSource.INSTALLED,
                builtAt = bank.referenceBuiltAt,
                modelCount = bank.modelCount,
                sizeBytes = cached.sizeBytes,
                sha256 = digest,
            ),
        )
    }

    /**
     * Drops the installed bank so the packaged one is used again.
     *
     * A file that cannot be removed stays the active bank, which the panel has to say
     * instead of claiming the rollback happened.
     */
    @Synchronized
    fun discardInstalled(): BankDiscardResult {
        val present = try {
            files.installedLength() != null
        } catch (error: IOException) {
            false
        }
        if (present) {
            try {
                files.deleteInstalled()
            } catch (error: IOException) {
                return BankDiscardResult.Failed
            }
        }
        installed = null
        installedUnreadable = false
        probed = true
        return if (present) BankDiscardResult.Restored else BankDiscardResult.NothingInstalled
    }

    private fun probe() {
        probed = true
        val present = try {
            files.installedLength() != null
        } catch (error: IOException) {
            false
        }
        if (!present) return
        val bytes = try {
            files.readInstalled()
        } catch (error: IOException) {
            null
        }
        val bank = bytes
            ?.takeIf { it.size.toLong() <= maxInstalledBytes }
            ?.let { parse(it) }
        if (bytes == null || bank == null) {
            installedUnreadable = true
            return
        }
        installed = Cached(bank, bytes.size.toLong(), sha256Hex(bytes))
    }

    private fun packaged(): Cached = builtIn ?: run {
        val bytes = files.readBuiltIn()
        val bank = parse(bytes) ?: throw IllegalStateException("内置参考库无法解析")
        Cached(bank, bytes.size.toLong(), sha256Hex(bytes)).also { builtIn = it }
    }

    /**
     * Parses the packed format, treating every failure as "not a bank".
     *
     * An installed bank is untrusted input: a truncated file, a stray HTML error page
     * or a hand-made one all have to end up as a rejection rather than a crash.
     */
    private fun parse(bytes: ByteArray): FingerprintBank? = try {
        FingerprintBank.fromAssetBytes(bytes)
    } catch (error: Exception) {
        null
    }

    companion object {
        const val MAX_INSTALLED_BYTES = 4L * 1024 * 1024
    }
}

/** Lowercase hex SHA-256 of [bytes], used to compare a download with its manifest. */
internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val out = StringBuilder(digest.size * 2)
    for (byte in digest) {
        val value = byte.toInt() and 0xFF
        out.append(HEX[value shr 4]).append(HEX[value and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
