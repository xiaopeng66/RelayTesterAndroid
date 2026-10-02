package com.relaytester.app.core.fingerprint

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Where the package in use came from. */
enum class BankSource {
    /** A package the user downloaded and installed. */
    INSTALLED,

    /**
     * A file is installed but no longer parses, and no usable backup is left.
     * The panel cannot score anything and has to offer a fresh download.
     */
    INSTALLED_UNREADABLE,

    /** Nothing is installed; the panel is unusable until a package is downloaded. */
    NOT_PROVISIONED,
}

/** What the panel shows about the package it is scoring with. */
data class BankIdentity(
    val source: BankSource,
    /** Reference build stamp from upstream, e.g. `2026-09-29T19:11:50+00:00`. */
    val builtAt: String,
    val modelCount: Int,
    val sizeBytes: Long,
    val sha256: String,
)

/** A parsed package together with where it came from. */
data class LoadedBank(
    val bank: FingerprintBank,
    val identity: BankIdentity,
)

/**
 * What one load found.
 *
 * [loaded] is null when nothing usable is installed; [source] then says whether the
 * device is empty or holding a file that no longer parses, which is the difference
 * between "download the detection package" and "your file is broken, download again".
 */
data class BankLoadResult(
    val loaded: LoadedBank?,
    val source: BankSource,
    /** Bytes of the installed file, or 0 when there is none. */
    val installedBytes: Long,
    /** True when the primary file was unusable and its backup was used instead. */
    val usedBackup: Boolean,
    /** User-facing reason an installed file was refused, or null when there is none. */
    val problem: String?,
)

/** Outcome of an install attempt. */
sealed interface BankInstallResult {
    data class Installed(val identity: BankIdentity) : BankInstallResult

    /** The bytes were refused; [reason] is user-facing and nothing was written. */
    data class Rejected(val reason: String) : BankInstallResult
}

/** Outcome of deleting the installed package. */
sealed interface BankDiscardResult {
    /** The installed package (and its backup) are gone; the panel is unprovisioned. */
    data object Removed : BankDiscardResult

    /** Nothing was installed, so there was nothing to remove. */
    data object NothingInstalled : BankDiscardResult

    /** The file could not be removed; it is still the installed package. */
    data object Failed : BankDiscardResult
}

/**
 * Where the installed package and its backup live.
 *
 * An interface so the store's policy — what counts as valid, how a swap stays
 * crash-safe, when the backup is used — can be tested without an Android context.
 */
interface BankFileSystem {
    /** Length of the installed package, or null when none exists. */
    fun installedLength(): Long?

    /** Length of the rollback copy, or null when there is none. */
    fun backupLength(): Long?

    /** The installed package, or null when none exists. */
    fun readInstalled(): ByteArray?

    /** The previous package kept for rollback, or null when there is none. */
    fun readBackup(): ByteArray?

    /**
     * Replaces the installed package with [bytes].
     *
     * A reader never sees a half-written file, and the package being replaced is kept
     * as the backup first, so a corrupt install can always fall back one step.
     */
    fun writeInstalled(bytes: ByteArray)

    fun deleteInstalled()

    fun deleteBackup()
}

/** The real filesystem: one private file for the package, one for the rollback copy. */
class AndroidBankFileSystem(context: Context) : BankFileSystem {
    private val appContext = context.applicationContext
    private val installed = File(appContext.filesDir, FingerprintBank.INSTALLED_FILE_NAME)
    private val backup = File(installed.parentFile, "${installed.name}.bak")

    override fun installedLength(): Long? = if (installed.isFile) installed.length() else null

    override fun backupLength(): Long? = if (backup.isFile) backup.length() else null

    override fun readInstalled(): ByteArray? =
        if (installed.isFile) installed.readBytes() else null

    override fun readBackup(): ByteArray? =
        if (backup.isFile) backup.readBytes() else null

    override fun writeInstalled(bytes: ByteArray) {
        val directory = installed.parentFile
        if (directory != null && !directory.isDirectory && !directory.mkdirs()) {
            throw IOException("无法创建检测包目录")
        }
        // Staged next to the target so the rename below stays within one filesystem.
        val staging = File(directory, "${installed.name}.staging")
        try {
            staging.writeBytes(bytes)
            rotateBackup()
            move(staging, installed)
        } catch (error: IOException) {
            staging.delete()
            throw error
        }
    }

    override fun deleteInstalled() {
        if (installed.isFile && !installed.delete()) {
            throw IOException("无法删除已安装的检测包")
        }
    }

    override fun deleteBackup() {
        if (backup.isFile && !backup.delete()) {
            throw IOException("无法删除检测包备份")
        }
    }

    /** Keeps the outgoing package as the backup; best effort, never blocks the install. */
    private fun rotateBackup() {
        if (!installed.isFile) return
        try {
            backup.delete()
            Files.move(installed.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (error: IOException) {
            // Losing the rollback copy is survivable; failing the install is not.
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
 * Owns the installed detection package: validating it, swapping it, and telling the
 * panel when there is nothing to score with.
 *
 * The package ships separately from the APK, so the panel has to work before anything
 * is installed — its job here is to report that state precisely enough for the UI to
 * offer the download rather than an error screen. Nothing is written until the whole
 * file has been validated and the write itself is a rename, so an interrupted install
 * leaves the previous package (or the previous nothing) intact.
 */
class FingerprintBankStore(
    private val files: BankFileSystem,
    /** Ceiling for an installed package; a download is not trusted to be small. */
    private val maxInstalledBytes: Long = MAX_INSTALLED_BYTES,
) {
    private class Cached(
        val bank: FingerprintBank,
        val sizeBytes: Long,
        val sha256: String,
    )

    @Volatile
    private var cached: Cached? = null

    @Volatile
    private var source = BankSource.NOT_PROVISIONED

    @Volatile
    private var installedBytes = 0L

    @Volatile
    private var usedBackup = false

    @Volatile
    private var problem: String? = null

    @Volatile
    private var probed = false

    /** The package to score with, plus where it came from, or the reason there is none. */
    @Synchronized
    fun load(): BankLoadResult {
        if (!probed) probe()
        val active = cached
        return BankLoadResult(
            loaded = active?.let {
                LoadedBank(
                    bank = it.bank,
                    identity = BankIdentity(
                        source = BankSource.INSTALLED,
                        builtAt = it.bank.referenceBuiltAt,
                        modelCount = it.bank.modelCount,
                        sizeBytes = it.sizeBytes,
                        sha256 = it.sha256,
                    ),
                )
            },
            source = source,
            installedBytes = installedBytes,
            usedBackup = usedBackup,
            problem = problem,
        )
    }

    /**
     * Validates [bytes] and installs them as the active package.
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
            ?: return BankInstallResult.Rejected("文件不是有效的检测包，已放弃安装")
        val digest = sha256Hex(bytes)
        try {
            files.writeInstalled(bytes)
        } catch (error: IOException) {
            return BankInstallResult.Rejected(error.message ?: "写入检测包失败")
        }
        val fresh = Cached(bank, bytes.size.toLong(), digest)
        cached = fresh
        source = BankSource.INSTALLED
        installedBytes = fresh.sizeBytes
        usedBackup = false
        problem = null
        probed = true
        return BankInstallResult.Installed(
            BankIdentity(
                source = BankSource.INSTALLED,
                builtAt = bank.referenceBuiltAt,
                modelCount = bank.modelCount,
                sizeBytes = fresh.sizeBytes,
                sha256 = digest,
            ),
        )
    }

    /**
     * Deletes the installed package so the panel is unprovisioned again.
     *
     * A file that cannot be removed stays the active package, which the panel has to
     * say instead of claiming the removal happened.
     *
     * The rollback copy counts as a package here: when the installed file is missing it is
     * the one [probe] adopts, so removing only the installed file would leave the panel
     * empty until the next launch, when the copy would be adopted again — the deletion the
     * user asked for would silently undo itself.
     */
    @Synchronized
    fun discardInstalled(): BankDiscardResult {
        val installedPresent = present { files.installedLength() }
        val backupPresent = present { files.backupLength() }
        // Remove the rollback copy first: a surviving copy would be adopted at next launch.
        if (backupPresent) {
            try {
                files.deleteBackup()
            } catch (error: IOException) {
                return BankDiscardResult.Failed
            }
        }
        if (installedPresent) {
            try {
                files.deleteInstalled()
            } catch (error: IOException) {
                return BankDiscardResult.Failed
            }
        }
        cached = null
        source = BankSource.NOT_PROVISIONED
        installedBytes = 0L
        usedBackup = false
        problem = null
        probed = true
        return if (installedPresent || backupPresent) {
            BankDiscardResult.Removed
        } else {
            BankDiscardResult.NothingInstalled
        }
    }

    /** An unreadable path is treated as absent, as [probe] does. */
    private inline fun present(length: () -> Long?): Boolean = try {
        length() != null
    } catch (error: IOException) {
        false
    }

    /**
     * Reads the installed file once and records why it could not be used.
     *
     * The backup is tried before giving up: it is the package the previous install
     * replaced, so a file that is corrupted (or a package built by an incompatible
     * revision) still leaves the panel able to score.
     */
    private fun probe() {
        probed = true
        val length = try {
            files.installedLength()
        } catch (error: IOException) {
            null
        }
        if (length == null) {
            // No installed file, but the rollback copy may still hold the package: an
            // install whose final rename failed leaves exactly this shape. Reporting
            // "nothing installed" would demand a fresh download for a package that is
            // sitting on disk, which is what the class promises not to do.
            if (adoptBackup("上次安装未完成，已回退到上一份")) return
            source = BankSource.NOT_PROVISIONED
            return
        }
        installedBytes = length
        // A read that fails is a file that cannot be used, not a crash: the panel has to
        // keep working and say so.
        var unreadable = false
        // The ceiling is checked before reading, not after: it exists to bound memory, and
        // materialising an oversized file first would defeat the point. Writes are capped,
        // so this needs a file dropped into private storage by a restore or by hand.
        val oversized = length > maxInstalledBytes
        val primary = if (oversized) {
            unreadable = true
            null
        } else {
            try {
                files.readInstalled()
            } catch (error: IOException) {
                unreadable = true
                null
            }
        }
        if (installFrom(primary, backup = false)) return

        if (adoptBackup("已安装的检测包不可用，已回退到上一份")) return
        source = BankSource.INSTALLED_UNREADABLE
        problem = when {
            oversized -> "已安装的检测包超出大小上限，请重新下载"
            unreadable -> "已安装的检测包无法读取，请重新下载"
            else -> "已安装的检测包无法解析，请重新下载"
        }
    }

    /** Adopts the rollback copy, reporting [reason]; false when there is no usable one. */
    private fun adoptBackup(reason: String): Boolean {
        val fallback = try {
            files.readBackup()
        } catch (error: IOException) {
            null
        }
        if (!installFrom(fallback, backup = true)) return false
        usedBackup = true
        problem = reason
        source = BankSource.INSTALLED
        return true
    }

    /** Parses [bytes] and adopts them when they form a usable package. */
    private fun installFrom(bytes: ByteArray?, backup: Boolean): Boolean {
        if (bytes == null || bytes.size.toLong() > maxInstalledBytes) return false
        val bank = parse(bytes) ?: return false
        cached = Cached(bank, bytes.size.toLong(), sha256Hex(bytes))
        source = BankSource.INSTALLED
        if (backup) installedBytes = bytes.size.toLong()
        return true
    }

    /**
     * Parses the packed format, treating every failure as "not a package".
     *
     * An installed package is untrusted input: a truncated file, a stray HTML error
     * page or a hand-made one all have to end up as a rejection rather than a crash.
     */
    private fun parse(bytes: ByteArray): FingerprintBank? = try {
        FingerprintBank.fromPackageBytes(bytes)
    } catch (error: Exception) {
        null
    }

    companion object {
        /**
         * Ceiling for an installed package.
         *
         * The full package is ~3.5 MB today (53 models, 16-bit references); the ceiling
         * leaves room for upstream to roughly double the roster before a legitimate
         * download would be refused.
         */
        const val MAX_INSTALLED_BYTES = 8L * 1024 * 1024
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
