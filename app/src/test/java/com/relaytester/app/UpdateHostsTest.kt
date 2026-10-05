package com.relaytester.app

import com.relaytester.app.core.update.UpdateHosts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allow-list both update feeds put their download addresses through.
 *
 * The interesting cases are the addresses that *look* right: a prefix test would accept every
 * one of them, and each is a different way of pointing the download somewhere else while the
 * digest that vouches for it still comes from the same document.
 */
class UpdateHostsTest {
    @Test
    fun `the release hosts are allowed whatever the case`() {
        for (host in listOf("github.com", "GitHub.com", "objects.githubusercontent.com", "OBJECTS.GITHUBUSERCONTENT.COM")) {
            assertTrue("$host 该被允许", UpdateHosts.allows(host))
        }
    }

    @Test
    fun `anything else is not a release host`() {
        for (host in listOf(null, "", "evil.test", "github.com.evil.test", "notgithub.com", "githubusercontent.com")) {
            assertFalse("$host 不该被允许", UpdateHosts.allows(host))
        }
    }

    @Test
    fun `an https address on a release host parses`() {
        val url = UpdateHosts.httpsOrNull("https://github.com/x/y/releases/download/v1/a.apk")

        assertNotNull(url)
        assertEquals("github.com", url?.host)
        assertEquals("https", url?.scheme)
    }

    @Test
    fun `an address that only looks like one does not parse`() {
        val refused = listOf(
            // Clear text: here the bytes and the digest are both on the wire together.
            "http://github.com/a.apk",
            // The allowed name is a prefix, not the host.
            "https://github.com.evil.test/a.apk",
            // Everything before the @ is userinfo; the host is what follows it.
            "https://github.com@evil.test/a.apk",
            "https://objects.githubusercontent.com.evil.test/a.apk",
            "not a url",
            "",
        )
        for (url in refused) {
            assertNull("这个地址本该被拒绝：$url", UpdateHosts.httpsOrNull(url))
        }
    }
}
