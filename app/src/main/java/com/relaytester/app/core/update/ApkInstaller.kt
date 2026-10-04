package com.relaytester.app.core.update

import android.content.Context
import android.content.Intent
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
        File(File(context.cacheDir, UPDATE_DIRECTORY), "relay-tester-$versionCode.apk")

    override fun canInstallPackages(): Boolean =
        // Below Oreo every app could install packages, so there is nothing to ask for. The
        // project's minSdk is 26, so this branch is unreachable in practice; it is written
        // out rather than left to the platform because the call itself is API 26+.
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    override fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
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

        /** Must match the authority declared in the manifest's provider. */
        const val AUTHORITY_SUFFIX = ".updates"

        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
