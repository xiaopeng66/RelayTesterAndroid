package com.relaytester.app.core.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which hosts the two update feeds may talk to.
 *
 * Both feeds live in this project's GitHub releases, so the manifest URLs are constants. The
 * *download* URLs are not: the app-release manifest carries its own `apkUrl`, and the
 * detection-package manifest carries its own package URL. Both also have their digest
 * travelling in the same document, so a feed an attacker can edit could otherwise hand over
 * the bytes *and* the checksum that vouches for them — the URL is the only part an allow-list
 * can still tie down.
 *
 * A set rather than one host because GitHub answers a release-asset request with a redirect
 * to a second host; see [MAX_UPDATE_REDIRECT_HOPS]. The redirect target is checked against
 * this same list, so a feed (or a hostile proxy) cannot steer the download elsewhere.
 */
object UpdateHosts {
    private val ALLOWED = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
    )

    /** How far the update chain follows a redirect; GitHub's asset hop is one. */
    const val MAX_UPDATE_REDIRECT_HOPS = 3

    /** True when [host] is one of the release hosts, ignoring case. */
    fun allows(host: String?): Boolean = host != null && host.lowercase() in ALLOWED

    /**
     * Parses [url] and returns it only when it is an HTTPS URL on an allowed host.
     *
     * Parsing rather than a prefix test: `https://github.com.evil.test/` and
     * `https://github.com@evil.test/` both *start* with the right characters and both point
     * somewhere else. A null answer means "not this chain's business".
     */
    fun httpsOrNull(url: String): HttpUrl? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        return parsed.takeIf { it.scheme == "https" && allows(it.host) }
    }
}
