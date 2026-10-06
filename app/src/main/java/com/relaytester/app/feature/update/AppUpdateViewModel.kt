package com.relaytester.app.feature.update

import android.content.ActivityNotFoundException
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
import java.io.File
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
     * Same split as the detection package's card, for the same reason: the launch check can
     * be skipped entirely (the switch is off, another check is in flight, or this is the
     * second start of the same process) and would otherwise fall back to 「尚未检查」 — which
     * is false, the feed was asked, just not now. The time is printed without a verdict;
     * [checkedAtMillis] wins whenever it exists.
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
    /**
     * Set when a check nobody asked for found an update: the app opens the page by itself.
     *
     * A one-shot like [message], and consumed the same way ([consumeAutoOpen]): a launch that
     * finds a new version has to *show* it, not just be able to say it. The dialog belongs to
     * the activity, so the view model asks for it rather than opening it.
     */
    val autoOpenUpdate: Boolean = false,
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

    /**
     * The version code the page was opened for by itself, so it is opened for each one once.
     *
     * A user who dismissed the news has answered it; a later automatic check of the same build
     * must not pull the page out from under whatever they are doing. A newer build is a new
     * question, and gets its own offer.
     */
    private var autoOpenedVersionCode: Long? = null

    /** Suspends until everything this view model started has settled; used by tests. */
    internal suspend fun awaitIdle() {
        checkJob?.join()
        downloadJob?.join()
        preferenceJob?.join()
    }

    /**
     * Stops the periodic check; used by tests.
     *
     * The timer is started by [checkOnLaunch] and lives as long as the view model does. Every
     * instance in a test class shares one virtual scheduler, so timers left running by the
     * tests that came before are still queued on it while later tests run. [onCleared] is
     * what does this in the app; it is protected, so tests need a door of their own rather
     * than a reflection trick.
     */
    internal fun cancelPeriodicCheck() {
        timerJob?.cancel()
    }

    override fun onCleared() {
        // The timer is the only thing here that would otherwise outlive the activity.
        timerJob?.cancel()
        super.onCleared()
    }

    /**
     * Reads the switch and, when it is on, checks once — every time the app comes up.
     *
     * Called when the app comes up, from a coroutine the caller already has. There is no
     * window: opening the app is the user asking whether there is a new version, and the row
     * that says when the last check ran has to move with it. It used to skip everything
     * inside six hours, which from the outside was a launch that checked nothing at all — the
     * row kept the previous session's time and no request was ever made.
     *
     * [LAUNCH_FLOOR_MILLIS] is not that window coming back. It only spans the same process's
     * own repeated calls — rotating the device recreates the activity and re-runs this — while
     * the check for an app that stays open is still the six-hourly one below.
     *
     * Starting the periodic check is part of coming up, not of this check succeeding: an app
     * launched with the switch off, or inside the floor, still has to be watching for the
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
        // Two calls a second apart are one launch: the activity is recreated — on a rotation,
        // on a theme change — and this runs again, but「又打开了一次软件」is not what happened.
        // The in-memory stamp counts as a reference because a check whose write failed is
        // still a check that was made.
        val reference = maxOf(stored.lastAppCheckAt, lastCheckAtMillis)
        if (reference > 0 && clock() - reference < LAUNCH_FLOOR_MILLIS) return false
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
        // A failed write is not reported — the in-memory stamp above already keeps the wake
        // arithmetic honest — but it must not escape either: an uncaught exception in
        // viewModelScope reaches the default handler and takes the app down, which is a far
        // worse way to say "the timestamp did not persist".
        runCatching { withContext(ioDispatcher) { preferences.setLastAppCheckAt(clock()) } }
    }

    private fun applyCheckResult(check: AppUpdateCheck, silent: Boolean) {
        _uiState.update { it.copy(checkedAtMillis = clock(), checkFailed = false) }
        when (check) {
            is AppUpdateCheck.Available -> {
                // 自动检查发现新版本：把「关于与更新」自己打开，「下载并安装」就摆在用户面前。
                // 用户自己按下检查时人已经在那一页上，没有要开的东西；同一个版本在一个进程里
                // 也只自动开一次——他关掉页面就是回答过了，定时器再查到同一个版本不该把页面
                // 从他手上拽走。更新的版本是另一个问题，另算一次。
                val openItself = silent && check.manifest.versionCode != autoOpenedVersionCode
                if (openItself) autoOpenedVersionCode = check.manifest.versionCode
                _uiState.update {
                    it.copy(
                        available = check.manifest,
                        otherPackage = null,
                        // Only ever set here: the flag is the activity's to clear, so a second
                        // check landing before it ran must not wipe a pending one.
                        autoOpenUpdate = it.autoOpenUpdate || openItself,
                    )
                }
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
                val target = installer.targetFile(manifest.versionCode)
                withContext(ioDispatcher) { pruneDownloadCache(target) }
                val apk = withContext(ioDispatcher) {
                    client.download(manifest, target) { progress ->
                        _uiState.update { it.copy(downloadProgress = progress) }
                    }
                }
                if (!withContext(ioDispatcher) { installer.isSignedLikeThisApp(apk) }) {
                    // The system installer would reject this too, but only after the download
                    // is already spent, and it fails with a message about the package rather
                    // than about where it came from. Checking first costs one manifest read.
                    apk.delete()
                    showMessage("下载的安装包签名与本应用不一致，已丢弃", isError = true)
                    return@launch
                }
                handToInstaller(apk, manifest)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // Download-phase failures already carry a message written for this screen
                // (the fetcher and the client both throw Chinese text); anything unexpected
                // still has to leave the panel with a reason rather than silence.
                showMessage(error.message ?: "下载安装包失败", isError = true)
            } finally {
                _uiState.update { it.copy(isDownloading = false, downloadProgress = null) }
            }
        }
    }

    /**
     * Hands a verified download to the system installer and reports how it went.
     *
     * Kept apart from the download so the two failure modes get different words: a download
     * that failed has an explanation of its own, while a hand-off that failed is a *download
     * that succeeded* — the user should be told the APK is on the device, not that the
     * update failed. A generic throwable keeps the platform's own text as the tail rather
     * than letting an English system string stand alone in a Chinese panel.
     */
    private fun handToInstaller(apk: File, manifest: AppUpdateManifest) {
        try {
            installer.install(apk)
            showMessage("已下载 ${manifest.versionName}，请在系统的安装界面确认", isError = false)
        } catch (error: ActivityNotFoundException) {
            // The file stays put on purpose: the install can still be finished from a file
            // manager, and the system installer is a different app that may have been
            // disabled rather than absent.
            showMessage("没有找到可以安装应用的界面，安装包已下载到缓存目录", isError = true)
        } catch (error: SecurityException) {
            showMessage("系统拒绝了安装请求，请检查「安装未知应用」权限", isError = true)
        } catch (error: Throwable) {
            val detail = error.message?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
            showMessage("安装包已下载，但交给系统安装界面失败$detail", isError = true)
        }
    }

    /**
     * Removes the leftovers of earlier downloads from the update cache.
     *
     * [keep] is the file about to be written. The APK that was installed is deliberately not
     * deleted right after the hand-off: the installer is a separate process reading the
     * content URI asynchronously, so removing the file the moment it was handed over is a
     * race against the install. Cleaning up before the *next* download is safe — nothing is
     * reading then — and it is what stops one leftover per version code from accumulating.
     */
    private fun pruneDownloadCache(keep: File) {
        val directory = keep.parentFile ?: return
        // One prefix covers both leftovers: a stale APK and a `.part` file are both named
        // after the update file they belong to, so anything else in the directory is not
        // this chain's to delete.
        val stale = directory.listFiles()?.filter {
            it.name != keep.name && it.name.startsWith(AndroidApkInstaller.UPDATE_FILE_PREFIX)
        } ?: return
        for (file in stale) file.delete()
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

    /**
     * Says the activity has opened the page for [AppUpdateUiState.autoOpenUpdate].
     *
     * Cleared by the same rule as [clearMessage]: the view model writes a request once, and
     * whoever acts on it clears it, so a recomposition or a rotation cannot replay it.
     */
    fun consumeAutoOpen() {
        _uiState.update { it.copy(autoOpenUpdate = false) }
    }

    private fun busy(): Boolean = checkJob?.isActive == true || downloadJob?.isActive == true

    private fun showMessage(message: String, isError: Boolean) {
        _uiState.update { it.copy(message = message, isMessageError = isError) }
    }

    companion object {
        /**
         * The shortest gap between two wake-ups by the launch check.
         *
         * Not the check's window — the window is gone, every launch checks — but the same
         * process can come up more than once: recreating the activity (a rotation, a theme
         * change) runs the launch check again, and that is one launch, not two. A minute is
         * far longer than any recreation and far shorter than any real relaunch.
         */
        internal const val LAUNCH_FLOOR_MILLIS = 60_000L

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
