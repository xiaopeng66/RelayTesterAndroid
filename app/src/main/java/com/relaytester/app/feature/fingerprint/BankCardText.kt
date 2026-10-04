package com.relaytester.app.feature.fingerprint

import com.relaytester.app.core.fingerprint.BankSource
import com.relaytester.app.core.update.formatByteSize
import com.relaytester.app.ui.components.UpdateCheckOutcome

/** The badge next to "检测包": where the copy in use came from. */
internal fun bankSourceLabel(source: BankSource): String = when (source) {
    BankSource.NOT_PROVISIONED -> "未安装"
    BankSource.INSTALLED -> "已安装"
    BankSource.INSTALLED_UNREADABLE -> "已安装但无法读取"
}

/** The "可更新到" line for a package the publisher has released. */
internal fun bankUpdateOffer(builtAt: String, modelCount: Int, sizeBytes: Long): String =
    "可更新到：构建于 ${builtAt.ifBlank { "未知" }} · $modelCount 个模型 · ${formatBankSize(sizeBytes)}"

/** What the card says about the package actually in use. */
internal fun bankSummaryLine(builtAt: String, modelCount: Int, sizeBytes: Long): String =
    "构建于 ${builtAt.ifBlank { "未知" }} · $modelCount 个模型 · ${formatBankSize(sizeBytes)}"

/**
 * The card's status sentence, which is the same question the badge answers: is there a
 * package, is it usable, and if so which one.
 *
 * An unreadable package has no build stamp, model count or size to report, so it must
 * not fall through to the "构建于 未知 · 0 个模型 · 0 KB" shape — that reads like a real
 * (if empty) package instead of a broken one.
 */
internal fun bankStateLine(
    source: BankSource,
    builtAt: String,
    modelCount: Int,
    sizeBytes: Long,
): String = when (source) {
    BankSource.NOT_PROVISIONED -> "尚未安装检测包，检测功能暂不可用。"
    BankSource.INSTALLED_UNREADABLE -> "当前检测包无法读取，检测功能暂不可用。"
    BankSource.INSTALLED -> bankSummaryLine(builtAt, modelCount, sizeBytes)
}

/**
 * The line the card shows when the published package needs an app newer than this one.
 *
 * Two different causes reach it — a package format this build cannot read, and a manifest
 * whose `minAppVersionCode` is above this app's — and both are one sentence to the user,
 * because both mean "update the app". The number is printed only when the publisher
 * actually stated it; "需要版本代码 ≥ 0" would be worse than saying nothing.
 */
internal fun bankNeedsNewerAppLine(requiredVersionCode: Long): String = when {
    requiredVersionCode > 0 ->
        "线上检测包需要更新版本的 App 才能使用（需要版本代码 ≥ $requiredVersionCode）。"

    else -> "线上检测包需要更新版本的 App 才能使用。"
}

/**
 * A package's size, as the card shows it.
 *
 * The detection package is a few megabytes, but the same field has to survive a much
 * larger published one, so it switches to megabytes past a megabyte instead of
 * printing thousands of kilobytes. "大小未知" is this card's wording for a size the
 * package did not report; the number itself is formatted by the shared
 * [formatByteSize], so a download in progress prints the same units.
 */
internal fun formatBankSize(bytes: Long): String =
    if (bytes <= 0) "大小未知" else formatByteSize(bytes)

/**
 * What the last finished package check ended in.
 *
 * Derived from the fields the check itself writes, in the order those fields win: a failed
 * check outranks an offer because the offer it left behind is from an earlier run and the
 * newest fact about the endpoint is that it could not be reached. Nothing is stored twice
 * here — a second "last outcome" field would be able to disagree with the buttons the row
 * sits above.
 */
internal fun bankCheckOutcome(state: FingerprintUiState): UpdateCheckOutcome = when {
    state.bankCheckFailed -> UpdateCheckOutcome.FAILED
    state.bankNeedsNewerApp != null -> UpdateCheckOutcome.NEEDS_NEWER_APP
    state.availableBankUpdate != null -> UpdateCheckOutcome.OFFER
    state.bankCheckedAtMillis == null -> UpdateCheckOutcome.NEVER_CHECKED
    else -> UpdateCheckOutcome.UP_TO_DATE
}
