package com.relaytester.app

import com.relaytester.app.core.update.AppUpdateManifestParser
import com.relaytester.app.feature.update.AppUpdateUiState
import com.relaytester.app.feature.update.InstalledAppVersion
import com.relaytester.app.feature.update.appCheckOutcome
import com.relaytester.app.feature.update.appOtherPackageLine
import com.relaytester.app.feature.update.appUpdateOffer
import com.relaytester.app.feature.update.appVersionLine
import com.relaytester.app.ui.components.UpdateCheckOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lines the update page prints, checked as values.
 *
 * The version code appears in most of them on purpose: this project republishes under an
 * unchanged version name, so a name alone would not explain why an update is being offered
 * or why one is being refused.
 */
class AppUpdateTextTest {
    private val installed = InstalledAppVersion(
        packageName = RELEASE_PACKAGE,
        versionCode = 10_505L,
        versionName = "1.5.0",
    )

    private fun state(
        checkedAtMillis: Long? = 1_791_009_120_000L,
        checkFailed: Boolean = false,
        availableVersionCode: Long? = null,
        otherPackage: String? = null,
    ) = AppUpdateUiState(
        installed = installed,
        checkedAtMillis = checkedAtMillis,
        checkFailed = checkFailed,
        available = availableVersionCode?.let {
            AppUpdateManifestParser.parse(appManifestJson("apk".toByteArray(), versionCode = it, versionName = "1.6.0"))
        },
        otherPackage = otherPackage,
    )

    @Test
    fun `the version line names both halves of the installed build`() {
        assertEquals("当前版本 1.5.0（版本代码 10505）", appVersionLine(installed))
    }

    @Test
    fun `an unreadable version is shown as unknown rather than as zero`() {
        val line = appVersionLine(installed.copy(versionCode = 0L, versionName = ""))
        assertEquals("当前版本 未知（版本代码 未知）", line)
    }

    @Test
    fun `an offer names the version code and the size`() {
        val manifest = AppUpdateManifestParser.parse(
            appManifestJson(ByteArray(2_937_319), versionCode = 10_600L, versionName = "1.6.0"),
        )
        val line = appUpdateOffer(manifest)
        assertTrue("没有版本名：$line", line.contains("1.6.0"))
        assertTrue("没有版本代码：$line", line.contains("10600"))
        assertTrue("没有大小：$line", line.contains("2.8 MB"))
    }

    @Test
    fun `the other-package line names both applications`() {
        val line = appOtherPackageLine(installed, "com.relaytester.app.debug")
        assertTrue("没有说出线上的包名：$line", line.contains("com.relaytester.app.debug"))
        assertTrue("没有说出本版的包名：$line", line.contains(RELEASE_PACKAGE))
    }

    @Test
    fun `a failure outranks everything the same check found`() {
        // An offer left over from an earlier check must not look like the state of this
        // one: what the row describes is the last check, and that check failed.
        assertEquals(
            UpdateCheckOutcome.FAILED,
            appCheckOutcome(state(checkFailed = true, availableVersionCode = 10_600L)),
        )
    }

    @Test
    fun `another app is reported before an offer`() {
        assertEquals(
            UpdateCheckOutcome.NEEDS_NEWER_APP,
            appCheckOutcome(state(otherPackage = "com.relaytester.app.debug", availableVersionCode = 10_600L)),
        )
    }

    @Test
    fun `an offer is reported after a finished check`() {
        assertEquals(UpdateCheckOutcome.OFFER, appCheckOutcome(state(availableVersionCode = 10_600L)))
    }

    @Test
    fun `a finished check with nothing new is up to date`() {
        assertEquals(UpdateCheckOutcome.UP_TO_DATE, appCheckOutcome(state()))
    }

    @Test
    fun `a check that never finished is not up to date`() {
        // The two are different claims: "nothing is new" must never be printed for a check
        // that has not happened.
        assertEquals(UpdateCheckOutcome.NEVER_CHECKED, appCheckOutcome(state(checkedAtMillis = null)))
    }
}
