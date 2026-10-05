package com.relaytester.app.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Where the updater puts an APK and how it hands the file to the system installer.
 *
 * An interface because every method here is an Android effect — a settings page, an
 * activity start, a content URI — and the view model's decisions (ask for permission
 * first? keep the file? retry?) are worth testing without a device. The device walkthrough
 * covers the implementation; the unit tests cover what the view model does around it.
 */
interface ApkInstaller {
    /** Where the download for [versionCode] goes; overwritten if that build is re-fetched. */
    fun targetFile(versionCode: Long): File

    /** True when Android is already allowed to hand this app's APKs to the installer. */
    fun canInstallPackages(): Boolean

    /** Opens the settings page where the user grants that. */
    fun openInstallPermissionSettings()

    /**
     * True when [apk] is signed by a certificate the installed app is also signed with.
     *
     * The system installer enforces this anyway — an APK signed by anyone else cannot replace
     * this app — but it enforces it *after* a few megabytes have been spent and by failing
     * with a system message. Asking here turns that into an early, explicable refusal, and it
     * is the one thing that would catch a feed pointed at a different (unsigned or
     * re-signed) build before the bytes reach the installer at all.
     *
     * The comparison is against the *running* app's certificates rather than a fingerprint
     * baked into this file: a constant would have to be updated by hand and would block
     * every future build the day the signing key is rotated with a v3 lineage.
     */
    fun isSignedLikeThisApp(apk: File): Boolean

    /**
     * Hands [apk] to the system installer.
     *
     * Through a content URI, never a `file://` one: sharing a file path across processes
     * has been refused by the platform since Android 7, and the installer runs in another
     * process.
     */
    fun install(apk: File)
}

/**
 * [ApkInstaller] over the real platform.
 *
 * The APK lives in the cache directory: it is a download the system may reclaim, and
 * after the install the file is worthless (the next check finds the new version code).
 */
class AndroidApkInstaller(private val context: Context) : ApkInstaller {
    override fun targetFile(versionCode: Long): File =
        File(File(context.cacheDir, UPDATE_DIRECTORY), "$UPDATE_FILE_PREFIX$versionCode.apk")

    override fun canInstallPackages(): Boolean =
        // No version check: minSdk is 26, the API the call itself needs, so "below Oreo every
        // app could install packages" describes a device this build cannot run on. The
        // condition that used to express it was unreachable, and lint read it that way.
        context.packageManager.canRequestPackageInstalls()

    override fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun isSignedLikeThisApp(apk: File): Boolean {
        // Both sides are read through the platform rather than compared with a constant: the
        // question is "would the installer accept this over the app that is running", and the
        // running app's certificates are the only thing that answers it.
        val mine = certificatesOf(context.packageName) ?: return false
        val theirs = certificatesOfArchive(apk) ?: return false
        // Any shared certificate counts, on both sides: a rotation lineage has the old key in
        // the history and the new one as the signer, and the installer accepts either match.
        return mine.any { candidate -> theirs.any { it.contentEquals(candidate) } }
    }

    private fun certificatesOf(installedPackage: String): List<ByteArray>? = try {
        certificatesOf(context.packageManager.getPackageInfo(installedPackage, CERTIFICATE_FLAG))
    } catch (error: Exception) {
        // Unreadable means unknown; the answer this method gives is "not verified", and the
        // install is refused rather than waved through on a guess.
        null
    }

    private fun certificatesOfArchive(apk: File): List<ByteArray>? = try {
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, CERTIFICATE_FLAG)
            ?.let(::certificatesOf)
    } catch (error: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun certificatesOf(info: PackageInfo): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // The signing history as well as the current signer: that is the set the installer
            // itself consults when deciding whether a rotation lineage makes an update legal.
            val signingInfo = info.signingInfo
            if (signingInfo == null) {
                emptyList()
            } else {
                (signingInfo.signingCertificateHistory.asList() + signingInfo.apkContentsSigners)
                    .map { it.toByteArray() }
            }
        } else {
            info.signatures?.map { it.toByteArray() }.orEmpty()
        }

    override fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    companion object {
        const val UPDATE_DIRECTORY = "updates"

        /**
         * The name every downloaded update file starts with.
         *
         * Public because the cache is pruned from outside this class, and "which files are
         * mine" has to be one fact rather than two spellings of it.
         */
        const val UPDATE_FILE_PREFIX = "relay-tester-"

        /** Must match the authority declared in the manifest's provider. */
        const val AUTHORITY_SUFFIX = ".updates"

        /**
         * The flag that fills the certificate field on this platform.
         *
         * `GET_SIGNING_CERTIFICATES` is API 28+, and on Android 8.x asking for it is not
         * merely a no-op: the request is answered with the legacy `signatures` field left
         * **unfilled**, so [certificatesOf] would read null, [isSignedLikeThisApp] would say
         * "not verified", and every update would be refused as "signed differently" on the
         * oldest devices this app supports (minSdk 26). The flag therefore follows the
         * platform, and the legacy constant — deprecated, but the only one that fills that
         * field below 28 — is asked for where it is the one that works.
         */
        @Suppress("DEPRECATION")
        private val CERTIFICATE_FLAG: Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }

        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
