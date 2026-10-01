package com.relaytester.app

import com.relaytester.app.core.fingerprint.BankFetcher
import com.relaytester.app.core.fingerprint.BankManifest
import com.relaytester.app.core.fingerprint.BankManifestParser
import com.relaytester.app.core.fingerprint.BankUpdateCheck
import com.relaytester.app.core.fingerprint.BankUpdateClient
import com.relaytester.app.core.fingerprint.BankUpdateDefaults
import com.relaytester.app.core.fingerprint.BankUpdateException
import com.relaytester.app.core.fingerprint.sha256Hex
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers what the panel accepts as an update.
 *
 * The manifest is fetched over the network and the package follows from it, so both
 * are treated as claims: the digest identifies a package, the size and digest of the
 * download are checked before the store ever sees it, and a manifest that asks for a
 * newer app is refused rather than half-honoured.
 */
class BankUpdateTest {
    private val patchStamp = "2026-09-30T05:12:31.123456+00:00"

    /** The digest of the package in use; null would stand for "nothing installed". */
    private fun digestOf(bytes: ByteArray) = sha256Hex(bytes)

    private fun client(fetcher: BankFetcher) = BankUpdateClient(fetcher = fetcher)

    @Test
    fun `a manifest that describes a package parses`() {
        val patched = bankWithBuiltAt(patchStamp)
        val manifest = BankManifestParser.parse(manifestJson(patched, builtAt = patchStamp))

        assertEquals(2, manifest.formatVersion)
        assertEquals(patchStamp, manifest.builtAt)
        assertEquals(shippedBankModelCount(), manifest.modelCount)
        assertEquals(patched.size.toLong(), manifest.sizeBytes)
        assertEquals(sha256Hex(patched), manifest.sha256)
        assertEquals(FakeBankFetcher.BANK_URL, manifest.url)
        assertEquals(0L, manifest.minAppVersionCode)
    }

    @Test
    fun `a manifest from another format version is refused`() {
        val failure = runCatching {
            BankManifestParser.parse(manifestJson(fixtureBankBytes(), formatVersion = 1))
        }.exceptionOrNull()

        assertTrue(failure is BankUpdateException)
    }

    @Test
    fun `a manifest without a digest is refused`() {
        val failure = runCatching {
            BankManifestParser.parse(manifestJson(fixtureBankBytes(), sha256 = "abc"))
        }.exceptionOrNull()

        assertTrue(failure is BankUpdateException)
    }

    @Test
    fun `a manifest without a build stamp is refused`() {
        val failure = runCatching {
            BankManifestParser.parse(manifestJson(fixtureBankBytes(), builtAt = ""))
        }.exceptionOrNull()

        assertTrue(failure is BankUpdateException)
    }

    @Test
    fun `a manifest pointing somewhere that is not http is refused`() {
        val failure = runCatching {
            BankManifestParser.parse(manifestJson(fixtureBankBytes(), url = "file:///etc/passwd"))
        }.exceptionOrNull()

        assertTrue(failure is BankUpdateException)
    }

    @Test
    fun `a manifest with an impossible size is refused`() {
        val failure = runCatching {
            BankManifestParser.parse(manifestJson(fixtureBankBytes(), sizeBytes = -1))
        }.exceptionOrNull()

        assertTrue(failure is BankUpdateException)
    }

    @Test
    fun `a manifest that is not json is refused`() {
        val failure = runCatching { BankManifestParser.parse("<html>404</html>") }.exceptionOrNull()

        assertTrue(failure is BankUpdateException)
    }

    @Test
    fun `a check reports a different package as available`() = runBlocking {
        val patched = bankWithBuiltAt(patchStamp)
        val fetcher = FakeBankFetcher().apply { publish(patched, builtAt = patchStamp) }

        val result = client(fetcher).check(digestOf(fixtureBankBytes()), appVersionCode = 10_400L)

        assertTrue(result is BankUpdateCheck.Available)
        assertEquals(patchStamp, (result as BankUpdateCheck.Available).manifest.builtAt)
    }

    @Test
    fun `a check reports the published package as current when the digest matches`() = runBlocking {
        val shipped = fixtureBankBytes()
        val fetcher = FakeBankFetcher().apply { publish(shipped) }

        val result = client(fetcher).check(digestOf(shipped), appVersionCode = 10_400L)

        assertEquals(BankUpdateCheck.UpToDate, result)
    }

    @Test
    fun `a check without an installed package always offers the published one`() = runBlocking {
        // Nothing installed means the panel cannot score at all, so whatever is
        // published is an install rather than an update.
        val shipped = fixtureBankBytes()
        val fetcher = FakeBankFetcher().apply { publish(shipped) }

        val result = client(fetcher).check(localSha256 = null, appVersionCode = 10_400L)

        assertTrue(result is BankUpdateCheck.Available)
    }

    @Test
    fun `a check refuses a package that needs a newer app`() = runBlocking {
        val patched = bankWithBuiltAt(patchStamp)
        val fetcher = FakeBankFetcher().apply { publish(patched, builtAt = patchStamp) }
        fetcher.manifestBody = manifestJson(patched, minAppVersionCode = 10_500L)

        val result = client(fetcher).check(digestOf(fixtureBankBytes()), appVersionCode = 10_400L)

        assertTrue(result is BankUpdateCheck.NeedsNewerApp)
    }

    @Test
    fun `a package already in use is current even when the manifest asks for a newer app`() = runBlocking {
        // Nothing to install, so there is nothing for the app-version gate to refuse: an
        // "update your app" prompt here would be noise.
        val shipped = fixtureBankBytes()
        val fetcher = FakeBankFetcher().apply { publish(shipped) }
        fetcher.manifestBody = manifestJson(shipped, minAppVersionCode = 10_500L)

        val result = client(fetcher).check(digestOf(shipped), appVersionCode = 10_400L)

        assertEquals(BankUpdateCheck.UpToDate, result)
    }

    @Test
    fun `a download that does not match its digest is refused`() = runBlocking {
        val patched = bankWithBuiltAt(patchStamp)
        val fetcher = FakeBankFetcher().apply {
            publish(patched, builtAt = patchStamp)
            // The server hands over something else than the manifest promised.
            bankBytes = bankWithBuiltAt("2026-10-01T00:00:00.000000+00:00")
        }
        val manifest = BankManifestParser.parse(fetcher.manifestBody)

        val failure = runCatching { client(fetcher).download(manifest) }.exceptionOrNull()

        assertTrue(failure is BankUpdateException)
    }

    @Test
    fun `a download of the wrong size is refused`() = runBlocking {
        val patched = bankWithBuiltAt(patchStamp)
        val fetcher = FakeBankFetcher().apply { publish(patched, builtAt = patchStamp) }
        val manifest = BankManifestParser.parse(fetcher.manifestBody)
            .copy(sizeBytes = patched.size.toLong() + 1)

        val failure = runCatching { client(fetcher).download(manifest) }.exceptionOrNull()

        assertTrue(failure is BankUpdateException)
    }

    @Test
    fun `a download that matches its manifest is returned`() = runBlocking {
        val patched = bankWithBuiltAt(patchStamp)
        val fetcher = FakeBankFetcher().apply { publish(patched, builtAt = patchStamp) }
        val manifest = BankManifestParser.parse(fetcher.manifestBody)

        val bytes = client(fetcher).download(manifest)

        assertEquals(patched.size, bytes.size)
        assertEquals(sha256Hex(patched), sha256Hex(bytes))
    }

    @Test
    fun `a check reports a network failure instead of pretending nothing is published`() = runBlocking {
        val fetcher = FakeBankFetcher(failure = IOException("连接中断"))

        val failure = runCatching { client(fetcher).check(digestOf(fixtureBankBytes()), 10_400L) }
            .exceptionOrNull()

        assertTrue(failure is IOException)
    }

    @Test
    fun `the check only asks the configured manifest url`() = runBlocking {
        val fetcher = FakeBankFetcher().apply { publish(fixtureBankBytes()) }

        client(fetcher).check(digestOf(fixtureBankBytes()), appVersionCode = 10_400L)

        assertEquals(listOf(BankUpdateDefaults.MANIFEST_URL), fetcher.urls)
    }

    @Test
    fun `the published manifest url is https`() {
        // The digest in the manifest is the trust anchor for the bank it points at, so
        // the manifest itself has to come from a transport nobody can rewrite.
        assertTrue(BankUpdateDefaults.MANIFEST_URL.startsWith("https://"))
    }

    @Test
    fun `the manifest url and the download url are different endpoints`() {
        val manifest: BankManifest = BankManifestParser.parse(manifestJson(fixtureBankBytes()))

        assertTrue(manifest.url != BankUpdateDefaults.MANIFEST_URL)
    }
}
