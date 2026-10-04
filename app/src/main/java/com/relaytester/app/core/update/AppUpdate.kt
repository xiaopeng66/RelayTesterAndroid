package com.relaytester.app.core.update

import androidx.compose.runtime.Immutable
import com.relaytester.app.core.fingerprint.sha256Hex
import java.io.File
import org.json.JSONObject

/** Anything that stops an app-update check or download, carrying a message for the panel. */
open class AppUpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * What the app-release feed advertises about the newest build.
 *
 * A file of our own rather than the GitHub releases API: the API needs a token or an
 * anonymous rate limit, and it reports a *tag* — this project republishes under the same
 * version number while the version code climbs, so a tag comparison would call a 10502
 * install "up to date". [versionCode] is the only signal that survives a republish.
 *
 * [packageName] is checked before anything is offered: an APK from another app (or a
 * differently-named build of this one) cannot be installed over this one, so it is
 * refused by name instead of failing in the system installer.
 */
@Immutable
data class AppUpdateManifest(
    val formatVersion: Int,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val minSdk: Int,
    val notesUrl: String,
    val publishedAt: String,
)

/** Reads the published app-release manifest; every failure is an [AppUpdateException]. */
object AppUpdateManifestParser {
    /**
     * The shape this build understands.
     *
     * Its own numbering, unrelated to the detection package's: the two feeds are published
     * by the same script but read by different code, and tying them together would make a
     * package-side bump look like an app-side one.
     */
    const val SUPPORTED_FORMAT = 1

    /** Ceiling for the APK itself; well past the current ~3 MB and short of absurd. */
    const val MAX_APK_BYTES = 64L * 1024 * 1024

    fun parse(text: String): AppUpdateManifest {
        val root = try {
            JSONObject(text)
        } catch (error: Exception) {
            throw AppUpdateException("更新信息无法解析")
        }
        val formatVersion = root.optInt("formatVersion", -1)
        if (formatVersion != SUPPORTED_FORMAT) {
            // Not the "app is too old" case the detection package can be: this feed
            // describes the app itself, and a newer shape here means the *reader* is
            // old — which is exactly what the update it advertises would fix. Said
            // plainly, with no version code to reach, because there is nowhere to read
            // one from a shape this build does not understand.
            throw AppUpdateException("更新信息的格式（$formatVersion）本版 App 读不了，请手动下载新版")
        }
        val versionCode = root.optLong("versionCode", -1)
        if (versionCode <= 0) {
            throw AppUpdateException("更新信息缺少版本代码")
        }
        val sizeBytes = root.optLong("sizeBytes", -1)
        if (sizeBytes <= 0 || sizeBytes > MAX_APK_BYTES) {
            throw AppUpdateException("更新信息里的安装包大小不合理（$sizeBytes 字节）")
        }
        val apkUrl = root.string("apkUrl", "安装包地址")
        if (!apkUrl.startsWith("http://") && !apkUrl.startsWith("https://")) {
            throw AppUpdateException("更新信息里的安装包地址必须是 HTTP(S)")
        }
        return AppUpdateManifest(
            formatVersion = formatVersion,
            packageName = root.string("packageName", "包名"),
            versionCode = versionCode,
            versionName = root.string("versionName", "版本名"),
            apkUrl = apkUrl,
            sizeBytes = sizeBytes,
            sha256 = root.digest("sha256"),
            minSdk = root.optInt("minSdk", 0),
            notesUrl = root.optString("notesUrl"),
            publishedAt = root.optString("publishedAt"),
        )
    }

    private fun JSONObject.string(key: String, what: String): String =
        optString(key).takeIf { it.isNotBlank() } ?: throw AppUpdateException("更新信息缺少$what")

    private fun JSONObject.digest(key: String): String {
        val value = optString(key).trim().lowercase()
        if (value.length != 64 || value.any { it !in "0123456789abcdef" }) {
            throw AppUpdateException("更新信息缺少有效的 SHA-256")
        }
        return value
    }
}

/** What a check found. */
sealed interface AppUpdateCheck {
    /** A newer build of this app is published. */
    data class Available(val manifest: AppUpdateManifest) : AppUpdateCheck

    /** This app is the newest published one. */
    data object UpToDate : AppUpdateCheck

    /**
     * The feed describes some other app, so nothing may be offered.
     *
     * [packageName] is what the feed called it: if it is a *different* name, this build is
     * a build of a different app (the debug variant, say) and the release APK would not
     * install over it — the user needs to know that rather than get a download that fails.
     */
    data class NotForThisPackage(val packageName: String) : AppUpdateCheck
}

/** Where the app-release feed lives. */
object AppUpdateDefaults {
    /** The `app` pre-release of our own repository; a plain asset URL, no API call, no token. */
    const val MANIFEST_URL =
        "https://github.com/xiaopeng66/RelayTesterAndroid/releases/download/app/app-latest.json"
}

/**
 * Checks what app build is published and downloads it once the user asks.
 *
 * [versionCode] — not the version name and not the tag — is the comparison: publishing
 * under an unchanged version name is normal here, and the version code is the only field
 * that always moves.
 */
class AppUpdateClient(
    private val fetcher: HttpFetcher,
    private val manifestUrl: String = AppUpdateDefaults.MANIFEST_URL,
    private val maxManifestBytes: Int = MAX_MANIFEST_BYTES,
) {
    /**
     * Compares the published build with the one running.
     *
     * @param installedVersionCode the version code of the running app, from the package
     *   manager; [packageName] is the one the running app was installed under.
     */
    suspend fun check(
        installedVersionCode: Long,
        packageName: String,
    ): AppUpdateCheck {
        val bytes = fetcher.fetch(manifestUrl, maxManifestBytes)
        val manifest = AppUpdateManifestParser.parse(String(bytes, Charsets.UTF_8))
        if (manifest.packageName != packageName) {
            return AppUpdateCheck.NotForThisPackage(manifest.packageName)
        }
        return if (manifest.versionCode > installedVersionCode) {
            AppUpdateCheck.Available(manifest)
        } else {
            AppUpdateCheck.UpToDate
        }
    }

    /**
     * Downloads [manifest]'s APK into [target], checking size and digest first.
     *
     * The bytes are verified *after* they are on disk and the file is deleted if they do
     * not match, so a truncated or tampered download never survives to be installed.
     */
    suspend fun download(
        manifest: AppUpdateManifest,
        target: File,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File {
        val downloaded = fetcher.downloadTo(
            url = manifest.apkUrl,
            target = target,
            maxBytes = AppUpdateManifestParser.MAX_APK_BYTES,
            onProgress = onProgress,
        )
        if (downloaded.length() != manifest.sizeBytes) {
            downloaded.delete()
            throw AppUpdateException("下载的安装包大小与更新信息不符（${downloaded.length()} ≠ ${manifest.sizeBytes}）")
        }
        val digest = sha256Hex(downloaded.readBytes())
        if (!digest.equals(manifest.sha256, ignoreCase = true)) {
            downloaded.delete()
            throw AppUpdateException("下载的安装包校验失败（SHA-256 不匹配）")
        }
        return downloaded
    }

    companion object {
        /** The feed is a small JSON document; this is generous for it and small for a lie. */
        const val MAX_MANIFEST_BYTES = 64 * 1024
    }
}
