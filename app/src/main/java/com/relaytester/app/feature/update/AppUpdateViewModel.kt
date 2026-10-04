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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
    /**
     * True while the check in flight is the automatic one.
     *
     * The two surfaces draw different things for the two kinds: an automatic check owns the
     * status row ("正在检查更新…" plus its spinner, the only trace such a check leaves), while
     * the one the user asked for spins on the button alone — two spinners at once read as two
     * problems, and "上次检查：…" tells the user more than a second "正在检查…" would.
     */
    val isCheckingQuietly: Boolean = false,
    /** True while the offered build is downloading, or waiting on the installer. */
    val isDownloading: Boolean = false,
    val downloadProgress: DownloadProgress? = null,
    /** When the last finished check ran, or null before the first one. */
    val checkedAtMillis: Long? = null,
    /**
     * When the last check finished, if it did not run in this process.
     *
     * Same split as the detection package's card, for the same reason: the launch check is
     * throttled by a stamp on disk, so a launch inside the window runs no check and would
     * otherwise fall back to 「尚未检查」 — which is false, the feed was asked, just not now.
     * The time is printed without a verdict; [checkedAtMillis] wins whenever it exists.
     */
    val earlierCheckAtMillis: Long? = null,
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
    /**
     * How often the app checks on its own while it stays open.
     *
     * Injected so a test can drive the periodic check with virtual time instead of waiting
     * six hours; production always uses the stored preference's window.
     */
    private val checkIntervalMillis: Long = UpdatePreferencesState.APP_CHECK_INTERVAL_MILLIS,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppUpdateUiState(installed = installed))
    val uiState = _uiState

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var preferenceJob: Job? = null
    private var timerJob: Job? = null

    /**
     * When this process last finished a check.
     *
     * The loop's wake time is computed from the later of this and the stored timestamp: the
     * stored one is what survives a restart, and this one is what makes the loop safe when
     * that write did not land — see [ensureAutoCheckTimer].
     */
    private var lastCheckAtMillis = 0L

    /** Suspends until everything this view model started has settled; used by tests. */
    internal suspend fun awaitIdle() {
        checkJob?.join()
        downloadJob?.join()
        preferenceJob?.join()
    }

    override fun onCleared() {
        // The timer is the only thing here that would otherwise outlive the activity.
        timerJob?.cancel()
        super.onCleared()
    }

    /**
     * Reads the switch and, when it is on and the last check is stale, checks once.
     *
     * Called when the app comes up, from a coroutine the caller already has. The throttle
     * reads a *stored* timestamp: without that, every launch would be "the first check in
     * a while" and closing the app twice would be two requests.
     *
     * Starting the periodic check is part of coming up, not of this check succeeding: an app
     * launched with the switch off, or inside the window, still has to be watching for the
     * moment the window passes. It is started second for the reason in the body.
     *
     * @return true when a check was actually started.
     */
    suspend fun checkOnLaunch(): Boolean {
        // The check runs before the timer starts, never beside it: both read the same stored
        // timestamp, and a timer that started first would see the stale one too and race this
        // check for the same request. Started last, its first wake is a whole window away
        // because this check just wrote the timestamp it computes from.
        val started = runLaunchCheck()
        ensureAutoCheckTimer()
        return started
    }

    private suspend fun runLaunchCheck(): Boolean {
        val stored = withContext(ioDispatcher) { preferences.read() }
        // Same as the detection-package card: the stored stamp is what lets a launch that
        // checks nothing still say when the last check was.
        _uiState.update {
            it.copy(
                autoCheckApp = stored.autoCheckApp,
                earlierCheckAtMillis = stored.lastAppCheckAt.takeIf { stamp -> stamp > 0 },
            )
        }
        if (!stored.autoCheckApp) return false
        val since = clock() - stored.lastAppCheckAt
        if (stored.lastAppCheckAt > 0 && since < checkIntervalMillis) {
            return false
        }
        if (busy()) return false
        // Tracked like the button's job, so "busy" and the test join see one kind of check.
        checkJob = viewModelScope.launch { check(silent = true) }
        checkJob?.join()
        return true
    }

    /**
     * The check the app runs by itself while it stays open.
     *
     * One loop for the activity-scoped view model, and each wake waits until the next check
     * is *due* — the remaining time computed from the stored timestamp — rather than a fixed
     * period. That is what keeps a manual check and this one from stacking: if the user
     * checked two hours ago, this wakes in four, because the timestamp the manual check wrote
     * is also the one this reads.
     *
     * A wake that finds something else in flight steps back for a whole window instead of
     * retrying: the only way to make progress here is to wait, and retrying in a tight loop
     * would be a busy-wait on a disk read.
     */
    private fun ensureAutoCheckTimer() {
        if (timerJob?.isActive == true) return
        timerJob = viewModelScope.launch {
            while (true) {
                val stored = withContext(ioDispatcher) { preferences.read() }
                if (!stored.autoCheckApp) {
                    // Still wakes on schedule while the switch is off, so turning it back on
                    // does not inherit a wait that started before it was turned off.
                    delay(checkIntervalMillis)
                    continue
                }
                // `max` of the stored stamp and this process's own: the stored one is what
                // survives a restart, and the in-memory one is what keeps the loop honest if
                // that write never landed. Without it a lost write would make every wake
                // compute "due six hours ago" and re-check in a tight cycle — a request storm
                // instead of a six-hourly check.
                val reference = maxOf(stored.lastAppCheckAt, lastCheckAtMillis)
                val dueAt = if (reference > 0) reference + checkIntervalMillis else 0L
                val wait = dueAt - clock()
                if (wait > 0) {
                    // Not due yet: sleep towards it and decide again from scratch rather than
                    // treating the wake itself as "due". The bounds are the guard: whatever
                    // the timestamps say, a wake is never shorter than [MIN_WAKE_MILLIS] —
                    // which turns "some path computed a due time in the past" from a busy
                    // loop into a bounded, visible mistake — and never longer than a window.
                    // Both can move the wake off the exact due time, so by the time it fires
                    // the stamp may have moved on (a check the user pressed, or this loop's
                    // own previous one landing late); deciding again keeps the timing a
                    // property of the stamps rather than of when the loop happened to wake.
                    delay(wait.coerceIn(MIN_WAKE_MILLIS, checkIntervalMillis))
                    continue
                }
                if (busy()) {
                    delay(checkIntervalMillis)
                    continue
                }
                checkJob = viewModelScope.launch { check(silent = true) }
                // Waited for, so the next wake is computed from the stamp this check wrote.
                checkJob?.join()
                // And then the same floor as above, for the case a completed check would
                // otherwise decide again immediately: if neither timestamp moved — the write
                // failed *and* the in-memory copy is gone — the arithmetic says "due" forever.
                // One iteration per minute is a visible mistake; without this it is a tight
                // loop that no assertion can even reach, because it never suspends.
                delay(MIN_WAKE_MILLIS)
            }
        }
    }

    /** The button: both the news and the "已是最新" are reported. */
    fun checkNow() {
        val state = _uiState.value
        // Already the user's own check: a second press while it runs is what the disabled
        // button already prevents, and starting another would split one wait in two.
        if (state.isChecking && !state.isCheckingQuietly) return
        if (state.isDownloading) return
        // The automatic check stands down rather than the button waiting behind it: it owes
        // nothing the requested check will not also compute, and the user should not wait on
        // a request they did not make. Joined before the new one starts, because the old
        // job's cleanup clears the very flags the new one is about to set.
        val quiet = if (state.isCheckingQuietly) checkJob else null
        checkJob = viewModelScope.launch {
            quiet?.cancelAndJoin()
            check(silent = false)
        }
    }

    private suspend fun check(silent: Boolean) {
        _uiState.update { it.copy(isChecking = true, isCheckingQuietly = silent) }
        try {
            applyCheckResult(client.check(installed.versionCode, installed.packageName), silent)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // A failed check is a finished check, timestamp included; see the bank card.
            _uiState.update { it.copy(checkedAtMillis = clock(), checkFailed = true) }
            showMessage(error.message ?: "检查更新失败", isError = true)
        } finally {
            _uiState.update { it.copy(isChecking = false, isCheckingQuietly = false) }
        }
        // Recorded for every outcome: the throttle is about how often the endpoint is
        // asked, not about how the last answer went. The in-memory copy is written first, so
        // a failing write cannot leave the timer thinking this check never happened.
        lastCheckAtMillis = clock()
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
         * The shortest gap between two wakes of the periodic check.
         *
         * Far below the six-hour window, so it never delays a check anyone was waiting for;
         * its job is to bound the damage if some path ever computes a due time in the past —
         * without it that is a tight loop against the feed rather than a slow one.
         */
        private const val MIN_WAKE_MILLIS = 60_000L

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
