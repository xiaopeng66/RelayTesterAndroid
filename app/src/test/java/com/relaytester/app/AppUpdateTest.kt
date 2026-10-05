package com.relaytester.app

import com.relaytester.app.core.update.AppUpdateCheck
import com.relaytester.app.core.update.AppUpdateClient
import com.relaytester.app.core.update.AppUpdateDefaults
import com.relaytester.app.core.update.AppUpdateException
import com.relaytester.app.core.update.AppUpdateManifestParser
import com.relaytester.app.core.fingerprint.sha256Hex
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the app-release feed: what the parser accepts as a manifest, what the client
 * calls newer, and what it refuses to hand over after a download.
 *
 * The feed describes the app that is *running*, so a wrong answer here installs the wrong
 * thing. Everything is therefore treated as a claim to be checked — the package name, the
 * size, the digest — and each refusal is pinned with the field that caused it.
 */
class AppUpdateTest {
    private val apk = "fake release apk bytes".toByteArray(Charsets.UTF_8)

    private fun client(fetcher: FakeHttpFetcher) = AppUpdateClient(fetcher = fetcher)

    private fun parserFailure(text: String): AppUpdateException =
        runCatching { AppUpdateManifestParser.parse(text) }.exceptionOrNull()
            as? AppUpdateException
            ?: throw AssertionError("这份清单本该被拒绝：$text")

    // ---- the manifest ------------------------------------------------------

    @Test
    fun `a manifest that describes a release parses`() {
        val manifest = AppUpdateManifestParser.parse(appManifestJson(apk))

        assertEquals(AppUpdateManifestParser.SUPPORTED_FORMAT, manifest.formatVersion)
        assertEquals(RELEASE_PACKAGE, manifest.packageName)
        assertEquals(10_600L, manifest.versionCode)
        assertEquals("1.6.0", manifest.versionName)
        assertEquals(APP_APK_URL, manifest.apkUrl)
        assertEquals(apk.size.toLong(), manifest.sizeBytes)
        assertEquals(sha256Hex(apk), manifest.sha256)
        assertEquals(26, manifest.minSdk)
        assertEquals(APP_NOTES_URL, manifest.notesUrl)
    }

    @Test
    fun `the format this build understands is one`() {
        // Its own numbering: the detection package's format has nothing to do with this,
        // and a package-side bump must not look like an app-side one.
        assertEquals(1, AppUpdateManifestParser.SUPPORTED_FORMAT)
    }

    @Test
    fun `a document that is not json is refused`() {
        assertEquals("更新信息无法解析", parserFailure("<html>not json</html>").message)
    }

    @Test
    fun `a format that is not the one this build reads is refused`() {
        for (version in listOf(0, 2, 99)) {
            val failure = parserFailure(appManifestJson(apk, formatVersion = version))
            // Said plainly, and with the number: there is no version code to send the user
            // to, because a shape this build cannot read has no fields it can trust.
            assertTrue(
                "格式 $version 的提示没有说出格式号：${failure.message}",
                failure.message.orEmpty().contains("$version"),
            )
        }
    }

    @Test
    fun `a manifest without a version code is refused`() {
        assertEquals("更新信息缺少版本代码", parserFailure(appManifestJson(apk, versionCode = 0)).message)
        assertEquals("更新信息缺少版本代码", parserFailure(appManifestJson(apk, versionCode = -5)).message)
        assertEquals(
            "更新信息缺少版本代码",
            parserFailure(appManifestJson(apk, omit = setOf("versionCode"))).message,
        )
    }

    @Test
    fun `a manifest naming neither a package nor a version is refused`() {
        assertEquals(
            "更新信息缺少包名",
            parserFailure(appManifestJson(apk, packageName = " ")).message,
        )
        assertEquals(
            "更新信息缺少版本名",
            parserFailure(appManifestJson(apk, omit = setOf("versionName"))).message,
        )
        assertEquals(
            "更新信息缺少包名",
            parserFailure(appManifestJson(apk, omit = setOf("packageName"))).message,
        )
    }

    @Test
    fun `a manifest without a usable download address is refused`() {
        assertEquals(
            "更新信息缺少安装包地址",
            parserFailure(appManifestJson(apk, omit = setOf("apkUrl"))).message,
        )
        // A bare path or a `file:` URL is not something the app can fetch, and treating it
        // as one would fail later with a worse message.
        for (url in listOf("app/RelayTester.apk", "file:///sdcard/RelayTester.apk")) {
            assertEquals(
                "更新信息里的安装包地址必须是发布站点的 HTTPS 地址",
                parserFailure(appManifestJson(apk, apkUrl = url)).message,
            )
        }
    }

    @Test
    fun `an address that is not a release host is refused`() {
        // The digest travels in the same document as the address, so a feed that could name
        // any host could vouch for anything. Clear text is refused with it: there the bytes
        // and the digest are both on the wire for a middleman to rewrite together.
        val refused = listOf(
            "https://example.test/app/RelayTester.apk",
            "http://github.com/xiaopeng66/RelayTesterAndroid/releases/download/v1.6.0/a.apk",
            "https://github.com.evil.test/app/RelayTester.apk",
            "https://github.com@evil.test/app/RelayTester.apk",
        )
        for (url in refused) {
            assertEquals(
                "这个地址本该被拒绝：$url",
                "更新信息里的安装包地址必须是发布站点的 HTTPS 地址",
                parserFailure(appManifestJson(apk, apkUrl = url)).message,
            )
        }
    }

    @Test
    fun `an address on an allowed host that is not the release path still parses`() {
        // The allow-list is about the host, not the path: the path is whatever the publisher
        // wrote, and pinning it here would break the next layout change of the release page.
        val manifest = AppUpdateManifestParser.parse(
            appManifestJson(apk, apkUrl = "https://objects.githubusercontent.com/some/asset.apk"),
        )
        assertEquals("https://objects.githubusercontent.com/some/asset.apk", manifest.apkUrl)
    }

    @Test
    fun `a notes link that is not a release address is dropped, not fatal`() {
        // The notes link is cosmetic; failing the whole feed over it would trade an update
        // for a missing hyperlink. Dropping it also keeps a feed from launching anything.
        for (bad in listOf("intent://scan/#Intent;scheme=zxing;end", "file:///etc/hosts", "http://github.com/x")) {
            val manifest = AppUpdateManifestParser.parse(appManifestJson(apk, notesUrl = bad))
            assertEquals("这个说明链接本该被丢掉：$bad", "", manifest.notesUrl)
            assertEquals(10_600L, manifest.versionCode)
        }
    }

    @Test
    fun `a manifest whose size is impossible is refused`() {
        assertEquals(
            "更新信息里的安装包大小不合理（0 字节）",
            parserFailure(appManifestJson(apk, sizeBytes = 0)).message,
        )
        val overCeiling = AppUpdateManifestParser.MAX_APK_BYTES + 1
        assertEquals(
            "更新信息里的安装包大小不合理（$overCeiling 字节）",
            parserFailure(appManifestJson(apk, sizeBytes = overCeiling)).message,
        )
    }

    @Test
    fun `a manifest without a real digest is refused`() {
        for (bad in listOf("", "abc", "z".repeat(64), sha256Hex(apk).dropLast(1))) {
            assertEquals(
                "更新信息缺少有效的 SHA-256",
                parserFailure(appManifestJson(apk, sha256 = bad)).message,
            )
        }
    }

    @Test
    fun `a digest written in capitals is the same digest`() {
        val manifest = AppUpdateManifestParser.parse(
            appManifestJson(apk, sha256 = sha256Hex(apk).uppercase()),
        )
        assertEquals(sha256Hex(apk), manifest.sha256)
    }

    @Test
    fun `the optional fields may be absent`() {
        val manifest = AppUpdateManifestParser.parse(
            appManifestJson(apk, omit = setOf("minSdk", "notesUrl", "notes", "publishedAt")),
        )
        assertEquals(0, manifest.minSdk)
        assertEquals("", manifest.notesUrl)
        // A feed without the text is an older one, not a broken one: the dialog falls back
        // to the notes link in exactly this case.
        assertEquals("", manifest.notes)
        assertEquals("", manifest.publishedAt)
    }

    // ---- the notes text the dialog prints ----------------------------------

    @Test
    fun `the feed's notes reach the manifest`() {
        val manifest = AppUpdateManifestParser.parse(appManifestJson(apk))
        assertEquals(APP_NOTES_TEXT, manifest.notes)
    }

    @Test
    fun `notes keep their line breaks and lose their edges`() {
        // Line breaks are the structure of the text; leading and trailing whitespace is
        // not, and the dialog would show it as a gap.
        val manifest = AppUpdateManifestParser.parse(
            appManifestJson(apk, notes = "\n  优化\n\n· 甲\n· 乙\n\n  "),
        )
        assertEquals("优化\n\n· 甲\n· 乙", manifest.notes)
    }

    @Test
    fun `notes with windows line endings are normalised`() {
        // The field is remote input: a CR that survives shows up as a stray glyph.
        val manifest = AppUpdateManifestParser.parse(
            appManifestJson(apk, notes = "甲\r\n乙\r丙"),
        )
        assertEquals("甲\n乙\n丙", manifest.notes)
    }

    @Test
    fun `notes past the ceiling are cut with an ellipsis`() {
        val over = "说".repeat(AppUpdateManifestParser.MAX_NOTES_CHARS + 500)
        val manifest = AppUpdateManifestParser.parse(appManifestJson(apk, notes = over))
        assertEquals(AppUpdateManifestParser.MAX_NOTES_CHARS + 1, manifest.notes.length)
        assertTrue("截断处没有省略号：${manifest.notes.takeLast(1)}", manifest.notes.endsWith("…"))
        // The publisher truncates to the same ceiling, so this is the defence, not the
        // normal path — but a feed that ignores the ceiling must not become a wall of text.
        assertEquals(
            AppUpdateManifestParser.MAX_NOTES_CHARS,
            manifest.notes.dropLast(1).length,
        )
    }

    @Test
    fun `notes that are only whitespace are empty`() {
        val manifest = AppUpdateManifestParser.parse(appManifestJson(apk, notes = " \n\t\n "))
        assertEquals("", manifest.notes)
    }

    // ---- what counts as an update -----------------------------------------

    @Test
    fun `a newer published build is offered`() = runBlocking {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_600L) }
        val result = client(fetcher).check(installedVersionCode = 10_505L, packageName = RELEASE_PACKAGE)
        val available = result as? AppUpdateCheck.Available
        assertTrue("更新的版本代码没有被当成新版本：$result", available != null)
        assertEquals(10_600L, available?.manifest?.versionCode)
        assertEquals("1.6.0", available?.manifest?.versionName)
    }

    @Test
    fun `the same version code is up to date`() = runBlocking {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_600L) }
        val result = client(fetcher).check(10_600L, RELEASE_PACKAGE)
        assertTrue("同一个版本代码不该被当成新版本：$result", result is AppUpdateCheck.UpToDate)
    }

    @Test
    fun `a published build older than this one is not an offer`() = runBlocking {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk, versionCode = 10_400L) }
        val result = client(fetcher).check(10_600L, RELEASE_PACKAGE)
        // A republish under a lower code is not a downgrade offer: installing it would
        // replace a newer app with an older one.
        assertTrue("已发布版本更低却给出更新：$result", result is AppUpdateCheck.UpToDate)
    }

    @Test
    fun `a feed for another app is refused by name`() = runBlocking {
        val fetcher = FakeHttpFetcher().apply {
            serve(
                "app-latest.json",
                appManifestJson(apk, packageName = "com.relaytester.app.debug", versionCode = 10_600L),
            )
        }
        val result = client(fetcher).check(10_505L, RELEASE_PACKAGE)
        assertEquals(AppUpdateCheck.NotForThisPackage("com.relaytester.app.debug"), result)
    }

    @Test
    fun `the check only asks the configured manifest url`() = runBlocking {
        val fetcher = FakeHttpFetcher().apply { publishApp(apk) }
        client(fetcher).check(10_505L, RELEASE_PACKAGE)
        assertEquals(listOf(AppUpdateDefaults.MANIFEST_URL), fetcher.urls)
    }

    @Test
    fun `a build that needs a newer android is refused before any bytes are spent`() = runBlocking {
        // The feed states the API its APK needs. Downloading three megabytes to be told by
        // the system installer that the package cannot be parsed is the user's data spent on
        // something the app already knew.
        val fetcher = FakeHttpFetcher().apply {
            serve("app-latest.json", appManifestJson(apk, versionCode = 10_600L, minSdk = 33))
        }
        val failure = runCatching {
            AppUpdateClient(fetcher = fetcher, sdkInt = { 26 }).check(10_505L, RELEASE_PACKAGE)
        }.exceptionOrNull() as? AppUpdateException

        assertTrue("需要更高 Android 的包没有被挡住：$failure", failure != null)
        assertTrue("提示里没有说要哪个 API：${failure?.message}", failure?.message.orEmpty().contains("33"))
        assertTrue("提示里没有说本机是哪个 API：${failure?.message}", failure?.message.orEmpty().contains("26"))
    }

    @Test
    fun `a build the device can run is still offered`() = runBlocking {
        val fetcher = FakeHttpFetcher().apply {
            serve("app-latest.json", appManifestJson(apk, versionCode = 10_600L, minSdk = 33))
        }
        val result = AppUpdateClient(fetcher = fetcher, sdkInt = { 35 }).check(10_505L, RELEASE_PACKAGE)

        assertTrue("设备跑得动的包被挡住了：$result", result is AppUpdateCheck.Available)
    }

    @Test
    fun `an up to date feed is not judged against the device api level`() = runBlocking {
        // Nothing would be installed, so what the published APK requires is not this
        // device's problem — reporting it would turn "已是最新" into an error.
        val fetcher = FakeHttpFetcher().apply {
            serve("app-latest.json", appManifestJson(apk, versionCode = 10_600L, minSdk = 33))
        }
        val result = AppUpdateClient(fetcher = fetcher, sdkInt = { 26 }).check(10_600L, RELEASE_PACKAGE)

        assertTrue("同一个版本代码却按 API 要求报错：$result", result is AppUpdateCheck.UpToDate)
    }

    @Test
    fun `a check that cannot reach the feed says so`() = runBlocking {
        val fetcher = FakeHttpFetcher().apply { failure = IOException("连不上") }
        val failure = runCatching { client(fetcher).check(10_505L, RELEASE_PACKAGE) }.exceptionOrNull()
        assertTrue("网络失败被吞掉了：$failure", failure is IOException)
    }

    // ---- the download ------------------------------------------------------

    private fun manifestFor(bytes: ByteArray, sizeBytes: Long = bytes.size.toLong(), sha256: String = sha256Hex(bytes)) =
        AppUpdateManifestParser.parse(appManifestJson(bytes, sizeBytes = sizeBytes, sha256 = sha256))

    @Test
    fun `a download that matches its manifest is returned verified`() = runBlocking {
        val directory = tempDirectory("app-update-ok")
        val fetcher = FakeHttpFetcher().apply { serve(".apk", apk) }
        val target = File(directory, "relay-tester-10600.apk")

        val downloaded = client(fetcher).download(manifestFor(apk), target)

        assertEquals(target.absolutePath, downloaded.absolutePath)
        assertEquals(apk.toList(), downloaded.readBytes().toList())
    }

    @Test
    fun `a download of the wrong size is refused and deleted`() = runBlocking {
        val directory = tempDirectory("app-update-size")
        val fetcher = FakeHttpFetcher().apply { serve(".apk", apk) }
        val target = File(directory, "relay-tester-10600.apk")

        val failure = runCatching {
            client(fetcher).download(manifestFor(apk, sizeBytes = apk.size + 7L), target)
        }.exceptionOrNull() as? AppUpdateException

        assertTrue("大小不符没有被拒绝：$failure", failure != null)
        assertTrue("提示里没有两个尺寸：${failure?.message}", failure?.message.orEmpty().contains("${apk.size + 7}"))
        assertFalse("校验失败的安装包还留在盘上", target.exists())
    }

    @Test
    fun `a download whose digest does not match is refused and deleted`() = runBlocking {
        val directory = tempDirectory("app-update-digest")
        val fetcher = FakeHttpFetcher().apply { serve(".apk", apk) }
        val target = File(directory, "relay-tester-10600.apk")

        val failure = runCatching {
            client(fetcher).download(manifestFor(apk, sha256 = "0".repeat(64)), target)
        }.exceptionOrNull() as? AppUpdateException

        assertEquals("下载的安装包校验失败（SHA-256 不匹配）", failure?.message)
        assertFalse("摘要不符的安装包还留在盘上", target.exists())
    }

    @Test
    fun `a download reports its progress`() = runBlocking {
        val directory = tempDirectory("app-update-progress")
        val fetcher = FakeHttpFetcher().apply { serve(".apk", apk) }
        val seen = mutableListOf<Long>()

        client(fetcher).download(manifestFor(apk), File(directory, "a.apk")) { progress ->
            seen += progress.bytesRead
        }

        assertEquals(0L, seen.first())
        assertEquals(apk.size.toLong(), seen.last())
    }
}
