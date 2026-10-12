package com.relaytester.app

import com.relaytester.app.core.fingerprint.FingerprintScoring
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The package shape the pipeline published until now still scores with the v1 formula.
 *
 * `LMFPA003` carries the frequency side and nothing else: no positional head, no blend.
 * Its numbers were fitted against upstream's v1 ranking, which is the frequency composite
 * *without* a final z-score, so that is what this shape has to be scored with — a package
 * built for one formula scored with the other produces a plausible-looking ranking that is
 * simply wrong, and no amount of parsing care would catch it. Which formula runs is decided
 * by the package's own format (see [com.relaytester.app.core.fingerprint.SharedScoring]),
 * and this test is what keeps the v1 branch honest now that v2 is the current shape.
 *
 * The vectors were produced by upstream's own detector at the revision the fixture was
 * built from, when `LMFPA003` was the current shape; they are pinned, not regenerated from
 * the Kotlin code. The package itself is the fixture this repository used then, byte for
 * byte, so a change that only affects the newer path cannot silently move it.
 */
class FingerprintV1PackageTest {
    private val bank by lazy { BankFixtures.v1Bank() }

    @Test
    fun `the v1 package parses and keeps its six-model roster`() {
        assertEquals(6, bank.modelCount)
        assertTrue("v1 检测包的构建时间不应为空", bank.referenceBuiltAt.isNotBlank())
        // No schema field in this shape, and therefore no positional half: the reader has
        // to leave it absent rather than defaulting to an empty head, which would rank
        // everything as a tie.
        assertEquals("v1 包不应带 schema 字段", "", bank.detectorSchema)
    }

    @Test
    fun `the v1 package reproduces the vectors it was pinned with`() {
        assertGoldenVectors(bank, "fingerprint-golden-v1.json")
    }

    @Test
    fun `the v1 package keeps ranking and refusing probabilities the way it did`() {
        val stream = BankFixtures.loader.getResourceAsStream("fingerprint-golden-v1.json")
            ?: error("找不到 fingerprint-golden-v1.json 测试资源")
        val cases = JSONArray(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        val full = cases.getJSONObject(0)
        val answers = full.getJSONArray("answers").let { array -> List(array.length()) { array.getString(it) } }
        val counts = full.getJSONArray("expectedCounts").let { array -> List(array.length()) { array.getInt(it) } }

        val analysis = bank.analyze(answers, counts)
        assertEquals(FingerprintScoring.FULL, analysis.scoring)
        assertTrue("v1 完整路径仍应给出置信度", analysis.candidates.all { it.probability != null })
        assertEquals("置信度之和", 1.0, analysis.candidates.mapNotNull { it.probability }.sum(), 1e-9)

        val partial = bank.analyze(answers.take(2), counts.take(2))
        assertEquals(FingerprintScoring.PARTIAL, partial.scoring)
        assertNull("v1 部分样本路径不应给出置信度", partial.candidates.first().probability)
    }
}
