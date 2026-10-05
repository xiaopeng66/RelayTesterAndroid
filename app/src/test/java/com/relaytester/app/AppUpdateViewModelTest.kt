package com.relaytester.app

import android.content.ActivityNotFoundException
import com.relaytester.app.core.storage.UpdatePreferencesState
import com.relaytester.app.core.update.AppUpdateClient
import com.relaytester.app.feature.update.AppUpdateUiState
import com.relaytester.app.feature.update.appCheckOutcome
import com.relaytester.app.ui.components.UpdateCheckOutcome
import com.relaytester.app.feature.update.AppUpdateViewModel
import com.relaytester.app.feature.update.InstalledAppVersion
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the app-update surface's decisions: when a check happens by itself, what it says,
 * and what the install leg does before and after any bytes are spent.
 *
 * The Android effects live behind [FakeApkInstaller], so the parts that would otherwise
 * need a device — the permission prompt, the file handed to the installer — are asserted
 * here, and the real implementations are covered by the device walkthrough.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val apk = "fake release apk bytes".toByteArray(Charsets.UTF_8)

    private val fixedNow = 1_791_009_120_000L
    private var now = fixedNow

    private val installed = InstalledAppVersion(
        packageName = RELEASE_PACKAGE,
        versionCode = 10_505L,
        versionName = "1.5.0",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        now = fixedNow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        fetcher: FakeHttpFetcher = FakeHttpFetcher(),
        preferences: MemoryUpdatePreferences = MemoryUpdatePreferences(),
        installer: FakeApkInstaller = FakeApkInstaller().at(tempDirectory("app-update-vm")),
        installedVersion: InstalledAppVersion = installed,
    ) = AppUpdateViewModel(
        client = AppUpdateClient(fetcher = fetcher),
        installer = installer,
        preferences = preferences,
        installed = installedVersion,
        ioDispatcher = dispatcher,
        clock = { now },
    )

    private fun settle(subject: AppUpdateViewModel) = runBlocking { subject.awaitIdle() }

    // ---- when a check happens by itself ------------------------------------

    @Test
    fun `the update switches default to on`() {
        // Both channels default to on, and that is a decision worth pinning: for the
        // detection package it is what a fresh install already does, and for the app it is
        // the only way a user who never opens the update page hears about a new version.
        val stored = UpdatePreferencesState()
        assertTrue("检测包自动检查默认应为开", stored.autoCheckBank)
        assertTrue("软件更新自动检查默认应为开", stored.autoCheckApp)
        assertTrue("界面初始状态也应为开", AppUpdateUiState(installed = installed).autoCheckApp)
        assertEquals(6L * 60 * 60 * 1000, UpdatePreferencesState.APP_CHECK_INTERVAL_MILLIS)
    }

    @Test
    fun `the launch check does nothing while the switch is off`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk) }
        val preferences = MemoryUpdatePreferences(UpdatePreferencesState(autoCheckApp = false))
        val subject = viewModel(fetcher = fetcher, preferences = preferences)

        val started = runBlocking { subject.checkOnLaunch() }

        assertFalse("开关关着还去查了", started)
        assertEquals(emptyList<String>(), fetcher.urls)
        assertFalse("关着开关不该在界面上显示成打开", subject.uiState.value.autoCheckApp)
    }

    @Test
    fun `the launch check runs when the last one is stale`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk) }
        val preferences = MemoryUpdatePreferences(
            UpdatePreferencesState(lastAppCheckAt = fixedNow - 7 * 60 * 60 * 1000L),
        )
        val subject = viewModel(fetcher = fetcher, preferences = preferences)

        val started = runBlocking { subject.checkOnLaunch() }

        assertTrue("过了节流期却没有检查", started)
        assertEquals(1, fetcher.urls.size)
        assertEquals(fixedNow, preferences.state.lastAppCheckAt)
    }

    @Test
    fun `the launch check skips the feed inside the interval`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk) }
        val preferences = MemoryUpdatePreferences(
            UpdatePreferencesState(lastAppCheckAt = fixedNow - 60 * 60 * 1000L),
        )
        val subject = viewModel(fetcher = fetcher, preferences = preferences)

        val started = runBlocking { subject.checkOnLaunch() }

        assertFalse("一小时内又查了一次", started)
        assertEquals(emptyList<String>(), fetcher.urls)
    }

    @Test
    fun `the interval is measured on a stored timestamp, not on the process`() {
        // The point of persisting it: without a stored value every launch would be "the
        // first check in a while", and closing the app twice would be two requests. The
        // same store is handed to both view models, which is what a restart looks like.
        val fetcher = FakeHttpFetcher().apply { publishApp(apk) }
        val preferences = MemoryUpdatePreferences()
        val first = viewModel(fetcher = fetcher, preferences = preferences)

        runBlocking { first.checkOnLaunch() }
        assertEquals(1, fetcher.urls.size)

        // A second launch of the same app, ten minutes later.
        now = fixedNow + 10 * 60 * 1000L
        val second = viewModel(fetcher = fetcher, preferences = preferences)
        val started = runBlocking { second.checkOnLaunch() }

        assertFalse("第二次启动又查了一遍", started)
        assertEquals(1, fetcher.urls.size)
    }

    // ---- the periodic check --------------------------------------------------

    @Test
    fun `coming up makes one request, not one from the launch check and one from the timer`() {
        // The launch check and the timer read the same stored timestamp and would both see
        // the stale one. The timer is therefore started after the check has written the new
        // timestamp; if it started first, one launch would be two requests.
        val fetcher = FakeHttpFetcher().apply { publishApp(apk) }
        val preferences = MemoryUpdatePreferences(
            UpdatePreferencesState(lastAppCheckAt = fixedNow - 7 * 60 * 60 * 1000L),
        )
        val subject = viewModel(fetcher = fetcher, preferences = preferences)

        runBlocking { subject.checkOnLaunch() }
        // Anything the timer had scheduled for "now" runs here.
        dispatcher.scheduler.runCurrent()
        assertEquals("一次启动发了两个请求", 1, fetcher.urls.size)

        // The launch check just wrote a fresh timestamp, so the timer's first wake is a while
        // window away. A timer started *before* that check would instead have read the stale
        // stamp and — with the due time in the past — fired at its minimum wait. A minute of
        // virtual time is what tells the two orders apart.
        now = fixedNow + 61_000L
        dispatcher.scheduler.advanceTimeBy(61_000L)

        assertEquals("启动后一分钟内又查了一次", 1, fetcher.urls.size)
    }

    @Test
    fun `the periodic check wakes when the window is due`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_505L) }
        val preferences = MemoryUpdatePreferences(
            UpdatePreferencesState(lastAppCheckAt = fixedNow - 60 * 60 * 1000L),
        )
        val subject = viewModel(fetcher = fetcher, preferences = preferences)

        runBlocking { subject.checkOnLaunch() }
        assertEquals("窗口内不该查", emptyList<String>(), fetcher.urls)

        // Five hours later the six-hour window is up. Virtual time is what lets the timer be
        // observed without waiting six real hours; the injected clock moves with it, because
        // the due time is computed from that clock.
        now = fixedNow + 5 * 60 * 60 * 1000L
        dispatcher.scheduler.advanceTimeBy(5 * 60 * 60 * 1000L + 1_000L)

        assertEquals("到点没有自动检查", 1, fetcher.urls.size)
        assertEquals(now, preferences.state.lastAppCheckAt)
        assertNull("常开时的自动检查报了消息", subject.uiState.value.message)
    }

    @Test
    fun `a launch inside the window still says when the last check was`() {
        // Same as the detection package's card: no check this launch, but the stored stamp is
        // still a fact worth printing, and printing it must not claim a verdict.
        val lastCheck = fixedNow - 60 * 60 * 1000L
        val fetcher = FakeHttpFetcher().apply { publishApp(apk) }
        val preferences = MemoryUpdatePreferences(UpdatePreferencesState(lastAppCheckAt = lastCheck))
        val subject = viewModel(fetcher = fetcher, preferences = preferences)

        assertFalse("窗口内不该查", runBlocking { subject.checkOnLaunch() })
        assertEquals(emptyList<String>(), fetcher.urls)
        assertEquals(lastCheck, subject.uiState.value.earlierCheckAtMillis)
        assertEquals(UpdateCheckOutcome.EARLIER_CHECK, appCheckOutcome(subject.uiState.value))
    }

    @Test
    fun `a check whose timestamp is lost does not become a request storm`() {
        // DataStore writes can fail, and a store that keeps handing back the value it had
        // before the write is what the wake-time arithmetic sees then. If that were the only
        // reference, every wake would compute "due six hours ago": the timer would re-check
        // immediately, forever. The in-memory stamp is what makes that a six-hourly check.
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_505L) }
        val preferences = MemoryUpdatePreferences(dropWrites = true)
        val subject = viewModel(fetcher = fetcher, preferences = preferences)

        runBlocking { subject.checkOnLaunch() }
        assertEquals("启动那一次照常发", 1, fetcher.urls.size)

        // A window of virtual time, during which a storm would have sent many requests.
        now = fixedNow + 6 * 60 * 60 * 1000L
        dispatcher.scheduler.advanceTimeBy(6 * 60 * 60 * 1000L + 1_000L)

        assertEquals("时间戳没落地就退化成死循环式重查", 2, fetcher.urls.size)
    }

    @Test
    fun `the periodic check respects the switch`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_505L) }
        val preferences = MemoryUpdatePreferences(UpdatePreferencesState(autoCheckApp = false))
        val subject = viewModel(fetcher = fetcher, preferences = preferences)

        runBlocking { subject.checkOnLaunch() }
        now += 24 * 60 * 60 * 1000L
        dispatcher.scheduler.advanceTimeBy(24 * 60 * 60 * 1000L + 1_000L)

        assertEquals("关着开关还是查了", emptyList<String>(), fetcher.urls)
    }

    @Test
    fun `a wake never re-checks inside the timer's minimum wait`() {
        // The floor exists for the case where the wake arithmetic says "due before now": a
        // wake stays at least a minute out, so an arithmetic bug costs one late check instead
        // of a busy loop. Thirty seconds past due is the only window where it changes the
        // answer, which is why the test drives exactly that.
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_505L) }
        val preferences = MemoryUpdatePreferences(
            UpdatePreferencesState(lastAppCheckAt = fixedNow - 6 * 60 * 60 * 1000L + 30_000L),
        )
        val subject = viewModel(fetcher = fetcher, preferences = preferences)

        runBlocking { subject.checkOnLaunch() }
        assertEquals("窗口没到就查了", emptyList<String>(), fetcher.urls)

        // 45 s: past the arithmetic's due time, inside the floor.
        now = fixedNow + 45_000L
        dispatcher.scheduler.advanceTimeBy(45_000L)
        assertEquals("最小间隔没兜住提前的唤醒", emptyList<String>(), fetcher.urls)

        now = fixedNow + 61_000L
        dispatcher.scheduler.advanceTimeBy(16_000L)
        assertEquals("过了最小间隔还是没有自动检查", 1, fetcher.urls.size)
    }

    @Test
    fun `a check in flight keeps the timer from starting another`() {
        // Two checks at once would be two requests for one window. The timer is the only leg
        // that can run into a check it did not start, so this is where the guard is load
        // bearing: a wake that finds one in flight waits out another window.
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_505L) }
        val subject = viewModel(fetcher = fetcher)

        runBlocking { subject.checkOnLaunch() }
        assertEquals("启动那一次照常发", 1, fetcher.urls.size)

        // The user's own check, parked in flight.
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate
        subject.checkNow()
        assertEquals("按钮的检查没发出去", 2, fetcher.urls.size)

        // A window of virtual time, with that check still parked: the timer wakes into it.
        now = fixedNow + 6 * 60 * 60 * 1000L
        dispatcher.scheduler.advanceTimeBy(6 * 60 * 60 * 1000L + 1_000L)

        assertEquals("已有检查在飞时定时器又开了一个", 2, fetcher.urls.size)

        fetcher.gate = null
        gate.complete(Unit)
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `a check running by itself owns the status row`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_505L) }
        val subject = viewModel(fetcher = fetcher)
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate

        val launcher = CoroutineScope(dispatcher).launch { subject.checkOnLaunch() }

        // The status row is the only sign a check the user did not ask for is happening, so
        // this flag is what draws it. Nothing else may be drawn while it runs.
        assertTrue("静默检查没有占住状态行", subject.uiState.value.isCheckingQuietly)
        assertTrue(subject.uiState.value.isChecking)

        gate.complete(Unit)
        runBlocking { launcher.join() }

        assertFalse("检查结束后仍标着静默", subject.uiState.value.isCheckingQuietly)
        assertFalse(subject.uiState.value.isChecking)
    }

    @Test
    fun `the button takes over from a check running by itself`() {
        // The user pressed the button, so the requested check must not queue behind the quiet
        // one: it cancels it instead. A quiet check answers nothing (no "已是最新版本"), so
        // the message is the proof that the *requested* check is the one that finished — and
        // it can only have finished if the parked quiet one was cancelled.
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_505L) }
        val subject = viewModel(fetcher = fetcher)
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate

        val launcher = CoroutineScope(dispatcher).launch { subject.checkOnLaunch() }
        assertTrue("静默检查没有占住状态行", subject.uiState.value.isCheckingQuietly)
        fetcher.gate = null

        subject.checkNow()

        assertFalse("按钮的检查被静默检查挡住了", subject.uiState.value.isCheckingQuietly)
        assertEquals("按钮的检查没有走完", "已是最新版本", subject.uiState.value.message)
        // And it was cancelled, not merely ignored: a parked quiet check left running would
        // still answer its own request later, which is one press of the button paying for two.
        assertEquals("静默检查没有被取消，而是与新检查并行", 1, fetcher.cancellations)

        gate.complete(Unit)
        runBlocking { launcher.join() }
    }

    // ---- what a check says --------------------------------------------------

    @Test
    fun `a launch that finds nothing new says nothing`() = runBlocking {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_505L) }
        val subject = viewModel(fetcher = fetcher)

        subject.checkOnLaunch()

        assertNull("静默检查报了一次「已是最新」", subject.uiState.value.message)
        assertEquals(fixedNow, subject.uiState.value.checkedAtMillis)
        assertFalse(subject.uiState.value.checkFailed)
    }

    @Test
    fun `a launch that finds a new version says so`() = runBlocking {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_600L) }
        val subject = viewModel(fetcher = fetcher)

        subject.checkOnLaunch()

        val message = subject.uiState.value.message.orEmpty()
        // News is worth interrupting for: this is the whole point of checking at launch.
        assertTrue("没有说出新版本名：$message", message.contains("1.6.0"))
        assertTrue("没有说出新版本代码：$message", message.contains("10600"))
        assertEquals(10_600L, subject.uiState.value.available?.versionCode)
    }

    @Test
    fun `the button reports that nothing is new`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_505L) }
        val subject = viewModel(fetcher = fetcher)

        subject.checkNow()
        settle(subject)

        // Asked for, so answered either way; the status row says the same thing, but a
        // button that answers with silence is indistinguishable from a broken one.
        assertEquals("已是最新版本", subject.uiState.value.message)
        assertFalse(subject.uiState.value.isMessageError)
    }

    @Test
    fun `a check that fails leaves both a timestamp and a failure`() {
        val fetcher = FakeHttpFetcher().apply { failure = IOException("连不上") }
        val preferences = MemoryUpdatePreferences()
        val subject = viewModel(fetcher = fetcher, preferences = preferences)

        subject.checkNow()
        settle(subject)

        assertTrue("失败没有被记下来", subject.uiState.value.checkFailed)
        assertEquals(fixedNow, subject.uiState.value.checkedAtMillis)
        assertTrue(subject.uiState.value.isMessageError)
        assertEquals("连不上", subject.uiState.value.message)
        // The throttle counts requests, not answers: a failure still counts as a check.
        assertEquals(fixedNow, preferences.state.lastAppCheckAt)
    }

    @Test
    fun `a feed for another app is explained instead of offered`() {
        val fetcher = FakeHttpFetcher().apply {
            serve(
                "app-latest.json",
                appManifestJson(apk, packageName = "com.relaytester.app.debug", versionCode = 10_600L),
            )
        }
        val subject = viewModel(fetcher = fetcher)

        subject.checkNow()
        settle(subject)

        assertEquals("com.relaytester.app.debug", subject.uiState.value.otherPackage)
        assertNull("装不了的包还被当成可更新", subject.uiState.value.available)
        assertTrue(subject.uiState.value.isMessageError)
    }

    // ---- the switch ---------------------------------------------------------

    @Test
    fun `flipping the switch is written down`() {
        val preferences = MemoryUpdatePreferences()
        val subject = viewModel(preferences = preferences)

        subject.setAutoCheckApp(false)
        settle(subject)

        assertFalse(subject.uiState.value.autoCheckApp)
        assertFalse("开关没有落盘", preferences.state.autoCheckApp)
    }

    @Test
    fun `the switch moves before the write lands`() {
        // A disk hop between the tap and the thumb is what makes a switch look stuck.
        val preferences = MemoryUpdatePreferences()
        val subject = viewModel(preferences = preferences)

        subject.setAutoCheckApp(false)

        assertFalse(subject.uiState.value.autoCheckApp)
    }

    // ---- the install leg ----------------------------------------------------

    @Test
    fun `downloading without the install permission opens the settings page and fetches nothing`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_600L) }
        val installer = FakeApkInstaller(canInstall = false).at(tempDirectory("app-update-perm"))
        val subject = viewModel(fetcher = fetcher, installer = installer)

        subject.checkNow()
        settle(subject)
        subject.downloadAndInstall()
        settle(subject)

        assertEquals(1, installer.permissionPrompts)
        assertEquals(emptyList<Any>(), installer.installed)
        // Three megabytes spent to then be told a permission was missing all along is the
        // user's data wasted on a fact the app already knew.
        assertTrue("缺少安装权限却先下了安装包：${fetcher.urls}", fetcher.urls.none { it.endsWith(".apk") })
        assertTrue(subject.uiState.value.isMessageError)
    }

    @Test
    fun `a verified download is handed to the installer`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_600L) }
        val installer = FakeApkInstaller().at(tempDirectory("app-update-install"))
        val subject = viewModel(fetcher = fetcher, installer = installer)

        subject.checkNow()
        settle(subject)
        subject.downloadAndInstall()
        settle(subject)

        assertEquals(1, installer.installed.size)
        val apkFile = installer.installed.single()
        assertEquals("relay-tester-10600.apk", apkFile.name)
        assertEquals(apk.toList(), apkFile.readBytes().toList())
        assertTrue(subject.uiState.value.message.orEmpty().contains("1.6.0"))
        assertFalse(subject.uiState.value.isMessageError)
    }

    @Test
    fun `the download is checked against this app's signer before the installer sees it`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_600L) }
        val installer = FakeApkInstaller(signatureMatches = false).at(tempDirectory("app-update-signature"))
        val subject = viewModel(fetcher = fetcher, installer = installer)

        subject.checkNow()
        settle(subject)
        subject.downloadAndInstall()
        settle(subject)

        assertEquals("签名不符的安装包没被检查", 1, installer.signatureChecks.size)
        assertEquals("签名不符还是交给了安装器", emptyList<Any>(), installer.installed)
        assertTrue(subject.uiState.value.isMessageError)
        assertFalse(subject.uiState.value.isDownloading)
    }

    @Test
    fun `a download with the wrong signer leaves no file behind`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_600L) }
        val installer = FakeApkInstaller(signatureMatches = false).at(tempDirectory("app-update-signature-file"))
        val subject = viewModel(fetcher = fetcher, installer = installer)

        subject.checkNow()
        settle(subject)
        subject.downloadAndInstall()
        settle(subject)

        val checked = installer.signatureChecks.single()
        assertFalse("被丢弃的安装包还留在缓存里：${checked.absolutePath}", checked.exists())
    }

    @Test
    fun `a matching signature is what reaches the installer`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_600L) }
        val installer = FakeApkInstaller(signatureMatches = true).at(tempDirectory("app-update-signature-ok"))
        val subject = viewModel(fetcher = fetcher, installer = installer)

        subject.checkNow()
        settle(subject)
        subject.downloadAndInstall()
        settle(subject)

        assertEquals(1, installer.installed.size)
        assertFalse(subject.uiState.value.isMessageError)
    }

    @Test
    fun `starting a new download clears the leftovers of earlier ones`() {
        val directory = tempDirectory("app-update-prune")
        val updates = File(directory, "updates").apply { mkdirs() }
        // A finished APK and an abandoned part file from earlier versions, plus a file this
        // chain does not own: the first two are this code's garbage, the third is not.
        val staleApk = File(updates, "relay-tester-10500.apk").apply { writeBytes(byteArrayOf(1)) }
        val stalePart = File(updates, "relay-tester-10500.apk.part").apply { writeBytes(byteArrayOf(2)) }
        val foreign = File(updates, "keep-me.txt").apply { writeBytes(byteArrayOf(3)) }
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_600L) }
        val installer = FakeApkInstaller().at(directory)
        val subject = viewModel(fetcher = fetcher, installer = installer)

        subject.checkNow()
        settle(subject)
        subject.downloadAndInstall()
        settle(subject)

        assertFalse("上一版的安装包没被回收：${staleApk.absolutePath}", staleApk.exists())
        assertFalse("残留的 .part 没被回收：${stalePart.absolutePath}", stalePart.exists())
        assertTrue("不属于本链的文件被误删：${foreign.absolutePath}", foreign.exists())
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun `a download in flight shows how far it got`() {        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_600L) }
        val installer = FakeApkInstaller().at(tempDirectory("app-update-flight"))
        val subject = viewModel(fetcher = fetcher, installer = installer)

        subject.checkNow()
        settle(subject)
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate

        subject.downloadAndInstall()

        assertTrue("下载中却没有标记", subject.uiState.value.isDownloading)
        assertNotNull("下载中却没有进度", subject.uiState.value.downloadProgress)

        fetcher.gate = null
        gate.complete(Unit)
        settle(subject)

        assertFalse(subject.uiState.value.isDownloading)
        assertNull("下载结束后进度行还在", subject.uiState.value.downloadProgress)
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun `a failed download takes its progress row with it`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_600L) }
        val installer = FakeApkInstaller().at(tempDirectory("app-update-fail"))
        val subject = viewModel(fetcher = fetcher, installer = installer)

        subject.checkNow()
        settle(subject)
        fetcher.failure = IOException("下到一半断了")

        subject.downloadAndInstall()
        settle(subject)

        assertNull("失败后进度条还留着", subject.uiState.value.downloadProgress)
        assertFalse(subject.uiState.value.isDownloading)
        assertEquals(emptyList<Any>(), installer.installed)
        assertEquals("下到一半断了", subject.uiState.value.message)
        assertTrue(subject.uiState.value.isMessageError)
    }

    @Test
    fun `an install the system refuses is reported`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_600L) }
        val installer = FakeApkInstaller(installFails = IOException("没有处理这个意图的应用"))
            .at(tempDirectory("app-update-refused"))
        val subject = viewModel(fetcher = fetcher, installer = installer)

        subject.checkNow()
        settle(subject)
        subject.downloadAndInstall()
        settle(subject)

        // The download succeeded, so the panel says the file is on the device instead of
        // reporting a download failure; the platform text stays as the tail.
        val message = subject.uiState.value.message.orEmpty()
        assertTrue("安装失败没有说清安装包已下载：$message", message.contains("安装包已下载"))
        assertTrue("系统原文被整句顶掉：$message", message.contains("没有处理这个意图的应用"))
        assertTrue(subject.uiState.value.isMessageError)
        assertFalse(subject.uiState.value.isDownloading)
    }

    @Test
    fun `a missing installer app is reported as a missing screen, not a failure`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_600L) }
        val installer = FakeApkInstaller(installFails = ActivityNotFoundException("nope"))
            .at(tempDirectory("app-update-no-activity"))
        val subject = viewModel(fetcher = fetcher, installer = installer)

        subject.checkNow()
        settle(subject)
        subject.downloadAndInstall()
        settle(subject)

        assertEquals("没有找到可以安装应用的界面，安装包已下载到缓存目录", subject.uiState.value.message)
        assertTrue(subject.uiState.value.isMessageError)
    }

    @Test
    fun `a rejected install permission is named as such`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_600L) }
        val installer = FakeApkInstaller(installFails = SecurityException("denied"))
            .at(tempDirectory("app-update-security"))
        val subject = viewModel(fetcher = fetcher, installer = installer)

        subject.checkNow()
        settle(subject)
        subject.downloadAndInstall()
        settle(subject)

        assertEquals("系统拒绝了安装请求，请检查「安装未知应用」权限", subject.uiState.value.message)
        assertTrue(subject.uiState.value.isMessageError)
    }

    @Test
    fun `a second check does not start while one is in flight`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_600L) }
        val subject = viewModel(fetcher = fetcher)
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate

        subject.checkNow()
        subject.checkNow()
        subject.checkNow()

        assertEquals("同一次检查被并发发起了多次", 1, fetcher.urls.size)
        fetcher.gate = null
        gate.complete(Unit)
        settle(subject)
    }

    @Test
    fun `a download is not attempted before a check offered one`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk) }
        val installer = FakeApkInstaller().at(tempDirectory("app-update-none"))
        val subject = viewModel(fetcher = fetcher, installer = installer)

        subject.downloadAndInstall()
        settle(subject)

        assertEquals(emptyList<Any>(), installer.installed)
        assertNull(subject.uiState.value.message)
    }
}
