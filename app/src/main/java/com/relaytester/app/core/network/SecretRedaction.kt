package com.relaytester.app.core.network

/** Removes field-labelled credentials and recognisable bare tokens from upstream errors. */
internal fun String.redactSecrets(): String = SECRET_TEXT.replace(this, "$1***")

private val SECRET_TEXT = Regex(
    "(?i)(\\bbearer\\s+[\"']?" +
        "|\\b(?:x-api-key|api[_ -]?key|access[_ -]?token)[\"']?\\s*[=:：]\\s*[\"']?" +
        "|\\b(?:api\\s*key|access\\s*token|secret|token)\\s+" +
        "(?:provided|invalid|not\\s+valid|is\\s+invalid|revoked|expired)" +
        "(?:\\s*[:：]\\s*|\\s+)[\"']?)" +
        "[^\\s,}:：\"']+" +
        "|\\b(?:sk|rk|pk)-(?:ant-)?[A-Za-z0-9_\\-]{8,}" +
        "|\\bgh[pousr]_[A-Za-z0-9]{16,}" +
        "|\\bgithub_pat_[A-Za-z0-9_]{20,}",
)
