package com.relaytester.app.core.network

/**
 * Removes field-labelled credentials and recognisable bare tokens from upstream errors.
 *
 * Four passes rather than one alternation, because they hide different parts of the text:
 * a labelled field keeps its label (`api_key=***`), a query string keeps its parameter name
 * (`?key=***`), a sentence keeps its word (`the key *** was rejected`), and a bare token
 * shape has nothing to keep. One regex with one replacement string could only do one of
 * those things, and the labels are worth keeping — they are how the user recognises which
 * of his settings the upstream is complaining about.
 *
 * This is a *shape* matcher, not a guarantee: a credential the upstream echoes with no
 * label, no prefix and fewer than [PROSE_VALUE_MIN_CHARS] characters has no shape to match
 * and is not covered. What it cannot cover is why the values that do get out are ones the
 * app itself built the message from (see `RelayApi.apiKeyProblem`).
 */
internal fun String.redactSecrets(): String =
    LABELLED_SECRET.replace(this, "$1***")
        .let { QUERY_PARAMETER.replace(it, "$1***") }
        .let { LABELLED_PROSE.replace(it, "$1 ***") }
        .let { BARE_TOKEN.replace(it, "***") }

/** `api_key=…`, `x-api-key: …`, `Bearer …`, and the "key is invalid: …" prose shape. */
private val LABELLED_SECRET = Regex(
    "(?i)(\\bbearer\\s+[\"']?" +
        "|\\b(?:x-api-key|api[_ -]?key|access[_ -]?token)[\"']?\\s*[=:：]\\s*[\"']?" +
        "|\\b(?:api\\s*key|access\\s*token|secret|token)\\s+" +
        "(?:provided|invalid|not\\s+valid|is\\s+invalid|revoked|expired)" +
        "(?:\\s*[:：]\\s*|\\s+)[\"']?)" +
        "[^\\s,}:：\"']+",
)

/**
 * A credential in a query string: `?key=…`, `&access_token=…`.
 *
 * Separate from [LABELLED_SECRET] because it has to keep the parameter name: this is the one
 * place a bare `key=` appears at all, and a URL in an error message is the shape an upstream
 * usually quotes back when it rejects a key (`…/balance?key=abc was rejected`).
 */
private val QUERY_PARAMETER = Regex(
    "(?i)([?&](?:key|token|api[_-]?key|apikey|access[_-]?token|auth)=)([^&\\s\"'#]+)",
)

/**
 * A sentence naming the field before a long opaque value: `key abc123def456ghi7 is revoked`.
 *
 * The minimum length is what keeps this from eating prose: "the key is invalid" has a
 * seven-letter word after it, while a real credential is long and unbroken. The cost is the
 * other direction — a sentence that does put a long unbroken word after one of these field
 * names loses that word — and a mangled explanation is a smaller harm than a printed key.
 */
private val LABELLED_PROSE = Regex(
    "(?i)\\b(key|token|secret|password|passphrase)\\b\\s*(?:is|=|:)?\\s*[\"']?" +
        "([A-Za-z0-9_-]{$PROSE_VALUE_MIN_CHARS,})",
)

/** Token shapes that identify themselves: OpenAI-style prefixes, GitHub PATs, Google keys. */
private val BARE_TOKEN = Regex(
    "(?i)\\b(?:sk|rk|pk)-(?:ant-)?[A-Za-z0-9_-]{8,}" +
        "|\\bgh[pousr]_[A-Za-z0-9]{16,}" +
        "|\\bgithub_pat_[A-Za-z0-9_]{20,}" +
        "|\\bAIza[A-Za-z0-9_-]{20,}",
)

/** Below this, a word after "key is" is more likely prose than a credential. */
private const val PROSE_VALUE_MIN_CHARS = 16
