package com.relaytester.app

import com.relaytester.app.core.storage.UpdatePreferencesState
import com.relaytester.app.core.update.AppUpdateClient
import com.relaytester.app.feature.update.AppUpdateUiState
import com.relaytester.app.feature.update.AppUpdateViewModel
import com.relaytester.app.feature.update.InstalledAppVersion
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun `a download in flight shows how far it got`() {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_600L) }
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

        assertEquals("没有处理这个意图的应用", subject.uiState.value.message)
        assertTrue(subject.uiState.value.isMessageError)
        assertFalse(subject.uiState.value.isDownloading)
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
