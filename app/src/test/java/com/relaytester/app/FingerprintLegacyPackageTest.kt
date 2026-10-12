package com.relaytester.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The oldest package shape still scores exactly as it did.
 *
 * Upstream removed its verifier scorer, so a package built before that (`LMFPA002`) carries
 * a block the app no longer reads. The app skips it by length rather than parsing it, and
 * the only way to know the skip landed on the right byte is to score with the package
 * afterwards: the block sits between the shared references and the tail, so a skip that is
 * one field off would either desynchronise the rest of the file (and fail to parse) or
 * silently read the wrong tail.
 *
 * This shape predates the positional head as well, so it is scored with the v1 formula —
 * the same branch [FingerprintV1PackageTest] pins. What is on trial here is the skip.
 *
 * The vectors are the ones generated for this fixture when it was the current shape, from
 * upstream's own detector at that revision; they are pinned, not regenerated from the
 * Kotlin code, so this is a comparison against upstream and not against ourselves.
 */
class FingerprintLegacyPackageTest {
    private val bank by lazy { BankFixtures.legacyBank() }

    @Test
    fun `the legacy package parses and keeps its six-model roster`() {
        assertEquals(6, bank.modelCount)
        assertTrue("旧格式检测包的构建时间不应为空", bank.referenceBuiltAt.isNotBlank())
    }

    @Test
    fun `the legacy package reproduces the vectors it was pinned with`() {
        assertGoldenVectors(bank, "fingerprint-golden-legacy.json")
    }
}
