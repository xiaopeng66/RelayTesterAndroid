package com.relaytester.app.feature.update

import com.relaytester.app.core.update.AppUpdateManifest
import com.relaytester.app.core.update.formatByteSize
import com.relaytester.app.ui.components.UpdateCheckOutcome

/** "当前版本 1.5.0（版本代码 10505）", the line the update page opens with. */
internal fun appVersionLine(installed: InstalledAppVersion): String {
    val code = if (installed.versionCode > 0) installed.versionCode.toString() else "未知"
    val name = installed.versionName.ifBlank { "未知" }
    return "当前版本 $name（版本代码 $code）"
}

/**
 * What the published build would move this app to.
 *
 * The version code is printed next to the name because it is the field the comparison
 * actually used: this project republishes under an unchanged version name, so a name alone
 * would not explain why an update is being offered.
 */
internal fun appUpdateOffer(manifest: AppUpdateManifest): String =
    "可更新到 ${manifest.versionName}（版本代码 ${manifest.versionCode}）· " +
        formatByteSize(manifest.sizeBytes)

/**
 * The line shown when the feed describes a different app.
 *
 * Naming both sides is the only actionable form of this: it is what tells the user that
 * the build they are running is not the published one — the debug variant, or a build made
 * before the package name changed — rather than that the network is broken.
 */
internal fun appOtherPackageLine(installed: InstalledAppVersion, published: String): String =
    "线上版本属于另一个应用（${published}），本版（${installed.packageName}）装不了它。" +
        "请到发布页手动下载。"

/** The app channel's own view of the last check, mirroring the bank card's. */
internal fun appCheckOutcome(state: AppUpdateUiState): UpdateCheckOutcome = when {
    state.checkFailed -> UpdateCheckOutcome.FAILED
    state.otherPackage != null -> UpdateCheckOutcome.NEEDS_NEWER_APP
    state.available != null -> UpdateCheckOutcome.OFFER
    state.checkedAtMillis == null -> UpdateCheckOutcome.NEVER_CHECKED
    else -> UpdateCheckOutcome.UP_TO_DATE
}
