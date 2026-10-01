package com.relaytester.app.feature.fingerprint

import com.relaytester.app.core.fingerprint.BankSource
import java.util.Locale

/** The badge next to "参考库": where the copy in use came from. */
internal fun bankSourceLabel(source: BankSource): String = when (source) {
    BankSource.BUILT_IN -> "内置"
    BankSource.INSTALLED -> "已安装"
    BankSource.INSTALLED_UNREADABLE -> "已安装但无法读取"
}

/** The "可更新到" line for a bank the publisher has released. */
internal fun bankUpdateOffer(builtAt: String, modelCount: Int, sizeBytes: Long): String =
    "可更新到：构建于 ${builtAt.ifBlank { "未知" }} · $modelCount 个模型 · ${formatBankSize(sizeBytes)}"

/**
 * A bank's size, as the card shows it.
 *
 * The packed bank is a few hundred kilobytes, but the same field has to survive a
 * much larger published one, so it switches to megabytes instead of printing 4096 KB.
 */
internal fun formatBankSize(bytes: Long): String = when {
    bytes <= 0 -> "大小未知"
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
}
