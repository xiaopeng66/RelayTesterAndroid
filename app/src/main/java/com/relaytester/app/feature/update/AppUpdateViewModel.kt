package com.relaytester.app.feature.update

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.relaytester.app.core.storage.UpdatePreferences
import com.relaytester.app.core.storage.UpdatePreferencesState
import com.relaytester.app.core.update.AndroidApkInstaller
import com.relaytester.app.core.update.ApkInstaller
import com.relaytester.app.core.update.AppUpdateCheck
import com.relaytester.app.core.update.AppUpdateClient
import com.relaytester.app.core.update.AppUpdateManifest
import com.relaytester.app.core.update.DownloadProgress
import com.relaytester.app.core.update.OkHttpFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The version this app was installed as; both halves are shown, one is compared. */
@Immutable
data class InstalledAppVersion(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
)

/**
 * The app-update surface: what is published, how far a download got, and the switch.
 */
@Immutable
data class AppUpdateUiState(
    val installed: InstalledAppVersion,
    val isChecking: Boolean = false,
    /** True while the offered build is downloading, or waiting on the installer. */
    val isDownloading: Boolean = false,
    val downloadProgress: DownloadProgress? = null,
    /** When the last finished check ran, or null before the first one. */
    val checkedAtMillis: Long? = null,
    /** True when the last check ended in an error; see the bank card for why it is stored. */
    val checkFailed: Boolean = false,
    val available: AppUpdateManifest? = null,
    /**
     * Set when the feed describes a different app than this build.
     *
     * Only possible for a build installed under another package name — the debug variant,
     * or an `optimized` build made before the package name changed. The value is the name
     * the feed used, so the message can put the two side by side.
     */
    val otherPackage: String? = null,
    /** Whether coming back to the app checks the feed by itself. */
    val autoCheckApp: Boolean = true,
    val message: String? = null,
    val isMessageError: Boolean = false,
)

/**
 * Drives the app-update channel.
 *
 * Kept apart from the detection package's view model on purpose: these are two feeds with
 * two different consequences (this one installs an APK), and the panel that scores
 * detections must not grow a second reason to talk to the network.
 *
 * The install leg is the only part that hands anything to the system, and it goes through
 * [ApkInstaller] so the decisions around it — permission before bytes, verified bytes only,
 * one download at a time — are testable without a device.
 */
class AppUpdateViewModel(
    private val client: AppUpdateClient,
    private val installer: ApkInstaller,
    private val preferences: UpdatePreferences,
    private val installed: InstalledAppVersion,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Overridden by tests so the throttle and the timestamps are values, not the clock. */
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppUpdateUiState(installed = installed))
    val uiState = _uiState

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var preferenceJob: Job? = null

    /** Suspends until everything this view model started has settled; used by tests. */
    internal suspend fun awaitIdle() {
        checkJob?.join()
        downloadJob?.join()
        preferenceJob?.join()
    }

    /**
     * Reads the switch and, when it is on and the last check is stale, checks once.
     *
     * Called when the app comes up, from a coroutine the caller already has. The throttle
     * reads a *stored* timestamp: without that, every launch would be "the first check in
     * a while" and closing the app twice would be two requests.
     *
     * @return true when a check was actually started.
     */
    suspend fun checkOnLaunch(): Boolean {
        val stored = withContext(ioDispatcher) { preferences.read() }
        _uiState.update { it.copy(autoCheckApp = stored.autoCheckApp) }
        if (!stored.autoCheckApp) return false
        val since = clock() - stored.lastAppCheckAt
        if (stored.lastAppCheckAt > 0 &&
            since < UpdatePreferencesState.APP_CHECK_INTERVAL_MILLIS
        ) {
            return false
        }
        if (busy()) return false
        // Tracked like the button's job, so "busy" and the test join see one kind of check.
        checkJob = viewModelScope.launch { check(silent = true) }
        checkJob?.join()
        return true
    }

    /** The button: both the news and the "已是最新" are reported. */
    fun checkNow() {
        if (busy()) return
        checkJob = viewModelScope.launch { check(silent = false) }
    }

    private suspend fun check(silent: Boolean) {
        _uiState.update { it.copy(isChecking = true) }
        try {
            applyCheckResult(client.check(installed.versionCode, installed.packageName), silent)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // A failed check is a finished check, timestamp included; see the bank card.
            _uiState.update { it.copy(checkedAtMillis = clock(), checkFailed = true) }
            showMessage(error.message ?: "检查更新失败", isError = true)
        } finally {
            _uiState.update { it.copy(isChecking = false) }
        }
        // Recorded for every outcome: the throttle is about how often the endpoint is
        // asked, not about how the last answer went.
        withContext(ioDispatcher) { preferences.setLastAppCheckAt(clock()) }
    }

    private fun applyCheckResult(check: AppUpdateCheck, silent: Boolean) {
        _uiState.update { it.copy(checkedAtMillis = clock(), checkFailed = false) }
        when (check) {
            is AppUpdateCheck.Available -> {
                _uiState.update { it.copy(available = check.manifest, otherPackage = null) }
                // News, so it is worth saying even when nobody asked. This is the whole
                // point of the launch check: the user is told by the app, not by a release
                // page they would have to think to visit.
                showMessage(
                    "发现新版本 ${check.manifest.versionName}（版本代码 ${check.manifest.versionCode}）",
                    isError = false,
                )
            }

            AppUpdateCheck.UpToDate -> {
                _uiState.update { it.copy(available = null, otherPackage = null) }
                // The status row already says "已是最新"; a snackbar for it would be the
                // app interrupting to report that nothing happened.
                if (!silent) showMessage("已是最新版本", isError = false)
            }

            is AppUpdateCheck.NotForThisPackage -> {
                _uiState.update { it.copy(available = null, otherPackage = check.packageName) }
                showMessage(
                    "线上是另一个应用（${check.packageName}），本版装不了它",
                    isError = true,
                )
            }
        }
    }

    /**
     * Downloads the offered build and hands it to the system installer.
     *
     * The permission is asked for *before* the download: three megabytes spent to then be
     * told to grant a permission that was missing all along is the user's data wasted on a
     * fact the app already knew.
     */
    fun downloadAndInstall() {
        val manifest = _uiState.value.available ?: return
        if (busy()) return
        if (!installer.canInstallPackages()) {
            installer.openInstallPermissionSettings()
            showMessage("请先允许本应用安装应用，回来后再点一次更新", isError = true)
            return
        }
        downloadJob = viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, downloadProgress = null) }
            try {
                // The file is named after the version code, so a re-download of the same
                // build overwrites its own part file rather than a different build's.
                val apk = withContext(ioDispatcher) {
                    client.download(manifest, installer.targetFile(manifest.versionCode)) { progress ->
                        _uiState.update { it.copy(downloadProgress = progress) }
                    }
                }
                installer.install(apk)
                showMessage("已下载 ${manifest.versionName}，请在系统的安装界面确认", isError = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showMessage(error.message ?: "下载安装包失败", isError = true)
            } finally {
                _uiState.update { it.copy(isDownloading = false, downloadProgress = null) }
            }
        }
    }

    /**
     * Turns the launch check on or off, and remembers the choice.
     *
     * Like the detection package's switch, the state moves first: the write is a disk hop,
     * and a switch that waits for it looks stuck. A failed write is not reported either —
     * the only consequence is that the old choice comes back on the next launch.
     */
    fun setAutoCheckApp(enabled: Boolean) {
        _uiState.update { it.copy(autoCheckApp = enabled) }
        preferenceJob = viewModelScope.launch { preferences.setAutoCheckApp(enabled) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null, isMessageError = false) }
    }

    private fun busy(): Boolean = checkJob?.isActive == true || downloadJob?.isActive == true

    private fun showMessage(message: String, isError: Boolean) {
        _uiState.update { it.copy(message = message, isMessageError = isError) }
    }

    companion object {
        /**
         * The running build, read from the package manager.
         *
         * This project has `buildConfig` off, so the version code is not a constant; the
         * package manager is the only source, and its result is also what the system
         * installer will compare when the APK arrives.
         */
        @Suppress("DEPRECATION")
        internal fun installedVersion(context: Context): InstalledAppVersion = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            InstalledAppVersion(
                packageName = context.packageName,
                versionCode = PackageInfoCompat.getLongVersionCode(info),
                versionName = info.versionName.orEmpty(),
            )
        } catch (error: Exception) {
            // Unreadable means unknown: the version code is 0, so nothing published can be
            // judged newer than it and the app never offers an install on a guess.
            InstalledAppVersion(packageName = context.packageName, versionCode = 0L, versionName = "")
        }

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras,
            ): T {
                val applicationContext = context.applicationContext
                return AppUpdateViewModel(
                    client = AppUpdateClient(fetcher = OkHttpFetcher()),
                    installer = AndroidApkInstaller(applicationContext),
                    preferences = UpdatePreferences(applicationContext),
                    installed = installedVersion(applicationContext),
                ) as T
            }
        }
    }
}
