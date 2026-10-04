package com.relaytester.app

import com.relaytester.app.core.fingerprint.sha256Hex
import com.relaytester.app.core.update.ApkInstaller
import java.io.File
import java.io.IOException
import org.json.JSONObject

/** The name the published release APK carries; what a check compares against. */
internal const val RELEASE_PACKAGE = "com.relaytester.app"

/** The URL the app-release feed is published under, as the manifest would carry it. */
internal const val APP_APK_URL = "https://example.test/app/RelayTester-1.6.0.apk"

/**
 * An app-release manifest body, as `publish_release_apk.py` would write it.
 *
 * Every field is a parameter so a test can break exactly one of them: the cases that
 * matter are the refusals (a missing package name, a digest that is not a digest, a size
 * past the ceiling, a URL that is not HTTP), and those only stay honest if the honest
 * document is the default.
 */
internal fun appManifestJson(
    apkBytes: ByteArray,
    formatVersion: Int = 1,
    packageName: String = RELEASE_PACKAGE,
    versionCode: Long = 10_600L,
    versionName: String = "1.6.0",
    apkUrl: String = APP_APK_URL,
    sizeBytes: Long = apkBytes.size.toLong(),
    sha256: String = sha256Hex(apkBytes),
    minSdk: Int = 26,
    notesUrl: String = "https://example.test/notes/1.6.0",
    publishedAt: String = "2026-10-04T09:00:00+00:00",
    omit: Set<String> = emptySet(),
): String = JSONObject().apply {
    fun putUnlessOmitted(key: String, value: Any?) {
        if (key !in omit) put(key, value)
    }
    putUnlessOmitted("formatVersion", formatVersion)
    putUnlessOmitted("packageName", packageName)
    putUnlessOmitted("versionCode", versionCode)
    putUnlessOmitted("versionName", versionName)
    putUnlessOmitted("apkUrl", apkUrl)
    putUnlessOmitted("sizeBytes", sizeBytes)
    putUnlessOmitted("sha256", sha256)
    putUnlessOmitted("minSdk", minSdk)
    putUnlessOmitted("notesUrl", notesUrl)
    putUnlessOmitted("publishedAt", publishedAt)
}.toString()

/** Serve an APK the manifest describes honestly. */
internal fun FakeHttpFetcher.publishApp(apkBytes: ByteArray, versionCode: Long = 10_600L) {
    serve("app-latest.json", appManifestJson(apkBytes, versionCode = versionCode))
    serve(".apk", apkBytes)
}

/**
 * An in-memory [ApkInstaller].
 *
 * The real one talks to the package manager, the settings app and the system installer;
 * what the view model decides — whether the permission is asked for before any bytes are
 * spent, whether the file is kept, whether the install is attempted at all — is state this
 * fake records. [installed] holds the files the view model actually handed over.
 */
internal class FakeApkInstaller(
    var canInstall: Boolean = true,
    /** When set, handing the file to the installer throws it. */
    var installFails: Throwable? = null,
) : ApkInstaller {
    /** The directory downloads land in; see [root]. */
    private lateinit var root: File

    /** Where [targetFile] puts things; assigned by [at]. */
    fun at(directory: File): FakeApkInstaller = apply { root = directory }

    /** Every APK that reached the installer, in order. */
    val installed = mutableListOf<File>()

    /** How many times the settings page was opened. */
    var permissionPrompts = 0
        private set

    /** Set when the settings page could not be opened, like a device without one. */
    var permissionPromptFails: Throwable? = null

    override fun targetFile(versionCode: Long): File =
        File(File(root, "updates"), "relay-tester-$versionCode.apk")

    override fun canInstallPackages(): Boolean = canInstall

    override fun openInstallPermissionSettings() {
        permissionPrompts++
        permissionPromptFails?.let { throw it }
    }

    override fun install(apk: File) {
        installFails?.let { throw it }
        installed += apk
    }
}

/** A directory that cleans itself up, for the fakes that write files. */
internal fun tempDirectory(name: String): File =
    File(File(System.getProperty("java.io.tmpdir"), "relay-tester-tests"), name).apply {
        deleteRecursively()
        mkdirs()
        if (!isDirectory) throw IOException("临时目录建不出来：$absolutePath")
    }
