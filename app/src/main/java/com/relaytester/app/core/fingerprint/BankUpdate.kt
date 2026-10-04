package com.relaytester.app.core.fingerprint

import androidx.compose.runtime.Immutable
import com.relaytester.app.core.update.DownloadProgress
import com.relaytester.app.core.update.HttpFetcher
import org.json.JSONObject

/**
 * Anything that stops a check or an install, carrying a message meant for the panel.
 *
 * Open so a caller that catches this one still catches every parse or download failure,
 * including the typed "the package shape is newer than this build" case below.
 */
open class BankUpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The published manifest describes a package shape this build cannot score.
 *
 * Raised while parsing, so it carries the app version the publisher asked for: the new
 * shape's fields are unreadable, but `minAppVersionCode` is read *before* the format range
 * is judged exactly so this can become "update the app" instead of a raw format message.
 * `requiredVersionCode` is 0 when the manifest does not carry the field.
 */
class BankFormatTooNewException(
    val formatVersion: Int,
    val requiredVersionCode: Long,
) : BankUpdateException("更新清单的格式过新（$formatVersion），本版 App 读不了")

/**
 * What the release endpoint advertises about the newest reference bank.
 *
 * The digest identifies a bank: it is compared with the digest of the copy in use, so
 * a re-published bank reads as "already current" instead of being downloaded again.
 */
@Immutable
data class BankManifest(
    val formatVersion: Int,
    /** Reference build stamp, e.g. `2026-09-30T05:12:31+00:00`. */
    val builtAt: String,
    /** Reference digest upstream reports for the same data. */
    val referenceSha256: String,
    val modelCount: Int,
    val sizeBytes: Long,
    val sha256: String,
    val url: String,
    val minAppVersionCode: Long,
)

/**
 * Reads the published manifest; every failure is a [BankUpdateException].
 */
object BankManifestParser {
    /**
     * Package shapes this app can install, oldest first.
     *
     * Two are live while the fleet upgrades: format 2 is the pre-verifier-removal package
     * and format 3 dropped that block. A manifest may advertise either, and both are
     * installable — the app detects the actual shape from the file's magic, so accepting
     * the older manifest only avoids making a fleet-wide upgrade out of a republish. An
     * app older than this build sees `minAppVersionCode` and offers the app update instead.
     *
     * A shape *newer* than [MAX_SUPPORTED_FORMAT] is not a plain error either: the
     * publisher bumps the format when it needs app support for it, so it is reported as
     * "this app is too old" with the version code the manifest asks for.
     */
    const val MIN_SUPPORTED_FORMAT = 2

    /** Newest package shape this app understands. */
    const val MAX_SUPPORTED_FORMAT = 3

    fun parse(text: String): BankManifest {
        val root = try {
            JSONObject(text)
        } catch (error: Exception) {
            throw BankUpdateException("更新清单无法解析")
        }
        val formatVersion = root.optInt("formatVersion", -1)
        // The app requirement is read *before* the format is judged. A manifest whose
        // shape this build cannot score is far more often "this app is old" than "the
        // publisher is wrong", and the version code is the only part of it that still
        // means something here — without it the user gets a format number and no action.
        val minAppVersionCode = root.optLong("minAppVersionCode", 0L)
        if (formatVersion > MAX_SUPPORTED_FORMAT) {
            throw BankFormatTooNewException(formatVersion, minAppVersionCode)
        }
        if (formatVersion < MIN_SUPPORTED_FORMAT) {
            throw BankUpdateException("更新清单的格式过旧（$formatVersion），无法使用")
        }
        val builtAt = root.string("builtAt", "构建时间")
        val sizeBytes = root.optLong("sizeBytes", -1)
        if (sizeBytes <= 0 || sizeBytes > FingerprintBankStore.MAX_INSTALLED_BYTES) {
            throw BankUpdateException("更新清单的库大小不合理（$sizeBytes 字节）")
        }
        val modelCount = root.optInt("modelCount", -1)
        if (modelCount <= 0) {
            throw BankUpdateException("更新清单的模型数量不合理（$modelCount）")
        }
        val url = root.string("url", "下载地址")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw BankUpdateException("更新清单的下载地址必须是 HTTP(S)")
        }
        return BankManifest(
            formatVersion = formatVersion,
            builtAt = builtAt,
            referenceSha256 = root.string("referenceSha256", "参考数据摘要"),
            modelCount = modelCount,
            sizeBytes = sizeBytes,
            sha256 = root.digest("sha256"),
            url = url,
            minAppVersionCode = minAppVersionCode,
        )
    }

    private fun JSONObject.string(key: String, what: String): String =
        optString(key).takeIf { it.isNotBlank() } ?: throw BankUpdateException("更新清单缺少$what")

    private fun JSONObject.digest(key: String): String {
        val value = optString(key).trim().lowercase()
        if (value.length != 64 || value.any { it !in "0123456789abcdef" }) {
            throw BankUpdateException("更新清单缺少有效的 SHA-256")
        }
        return value
    }
}

/** Outcome of a manual check. */
sealed interface BankUpdateCheck {
    /** A different bank is published. */
    data class Available(val manifest: BankManifest) : BankUpdateCheck

    /** The published bank is the one already in use. */
    data object UpToDate : BankUpdateCheck

    /**
     * The published package needs a newer app, so installing it here would be wrong.
     *
     * [requiredVersionCode] is what the publisher asks for, or 0 when the manifest is a
     * shape too new to read and does not carry the field.
     */
    data class NeedsNewerApp(val requiredVersionCode: Long) : BankUpdateCheck
}

/** Where the published bank lives. */
object BankUpdateDefaults {
    /** The `bank` pre-release of our own repository; a plain asset URL, no API call, no token. */
    const val MANIFEST_URL =
        "https://github.com/xiaopeng66/RelayTesterAndroid/releases/download/bank/latest.json"
}

/**
 * Checks what is published and downloads it once the user asks for it.
 *
 * Nothing here installs anything: [download] returns verified bytes and the store
 * decides what to do with them.
 */
class BankUpdateClient(
    private val fetcher: HttpFetcher,
    private val manifestUrl: String = BankUpdateDefaults.MANIFEST_URL,
    private val maxManifestBytes: Int = MAX_MANIFEST_BYTES,
) {
    /**
     * Compares what is published with the package in use.
     *
     * [localSha256] is null when nothing is installed — the panel is unprovisioned, so
     * whatever is published is by definition an install rather than an update.
     */
    suspend fun check(localSha256: String?, appVersionCode: Long): BankUpdateCheck {
        val bytes = fetcher.fetch(manifestUrl, maxManifestBytes)
        val manifest = try {
            BankManifestParser.parse(String(bytes, Charsets.UTF_8))
        } catch (tooNew: BankFormatTooNewException) {
            // A shape this build cannot read is not something to report as a parse
            // failure: the publisher's own app requirement says what to do about it, and
            // the panel turns this into an app-update prompt rather than a format number.
            return BankUpdateCheck.NeedsNewerApp(tooNew.requiredVersionCode)
        }
        return when {
            // Identity comes first: a package we already have is nothing to do, whatever
            // the manifest asks of the app.
            localSha256 != null && manifest.sha256 == localSha256 -> BankUpdateCheck.UpToDate
            manifest.minAppVersionCode > appVersionCode ->
                BankUpdateCheck.NeedsNewerApp(manifest.minAppVersionCode)

            else -> BankUpdateCheck.Available(manifest)
        }
    }

    /**
     * Fetches the package [manifest] advertises.
     *
     * The size and the digest are checked here, so bytes that do not match what the
     * manifest promised never reach the store.
     *
     * [onProgress] reports bytes as they arrive. The server's declared length is used when
     * it sent one and the manifest's own size when it did not, so a panel that wants to
     * print "1.3/2.0 MB" always has a denominator: the two must agree anyway, because a
     * mismatch here is rejected below.
     */
    suspend fun download(
        manifest: BankManifest,
        maxBytes: Int = FingerprintBankStore.MAX_INSTALLED_BYTES.toInt(),
        onProgress: (DownloadProgress) -> Unit = {},
    ): ByteArray {
        val bytes = fetcher.fetch(manifest.url, maxBytes) { progress ->
            onProgress(
                if (progress.totalBytes > 0) progress
                else progress.copy(totalBytes = manifest.sizeBytes),
            )
        }
        if (bytes.size.toLong() != manifest.sizeBytes) {
            throw BankUpdateException("下载的检测包大小与清单不符（${bytes.size} ≠ ${manifest.sizeBytes}）")
        }
        if (!sha256Hex(bytes).equals(manifest.sha256, ignoreCase = true)) {
            throw BankUpdateException("下载的检测包校验失败（SHA-256 不匹配）")
        }
        return bytes
    }

    companion object {
        const val MAX_MANIFEST_BYTES = 256 * 1024
    }
}
