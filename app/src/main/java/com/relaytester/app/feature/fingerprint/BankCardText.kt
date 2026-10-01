package com.relaytester.app.feature.fingerprint

import com.relaytester.app.core.fingerprint.BankSource
import java.util.Locale

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
 * A package's size, as the card shows it.
 *
 * The detection package is a few megabytes, but the same field has to survive a much
 * larger published one, so it switches to megabytes past a megabyte instead of
 * printing thousands of kilobytes.
 */
internal fun formatBankSize(bytes: Long): String = when {
    bytes <= 0 -> "大小未知"
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
}
