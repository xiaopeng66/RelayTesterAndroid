package com.relaytester.app

import com.relaytester.app.core.fingerprint.FingerprintScoring
import com.relaytester.app.core.fingerprint.ProbabilityStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The previous package shape still scores exactly as it did.
 *
 * Upstream removed its verifier scorer, so a package built before that (`LMFPA002`) carries
 * a block the app no longer reads. The app skips it by length rather than parsing it, and
 * the only way to know the skip landed on the right byte is to score with the package
 * afterwards: the block sits between the shared references and the tail, so a skip that is
 * one field off would either desynchronise the rest of the file (and fail to parse) or
 * silently read the wrong tail.
 *
 * The vectors are the ones generated for this fixture when it was the current shape, from
 * upstream's own detector at that revision; they are pinned, not regenerated from the
 * Kotlin code, so this is a comparison against upstream and not against ourselves.
 */
class FingerprintLegacyPackageTest {
    private val bank by lazy { BankFixtures.legacyBank() }

    private fun golden(): JSONArray {
        val stream = BankFixtures.loader.getResourceAsStream("fingerprint-golden-legacy.json")
            ?: error("找不到 fingerprint-golden-legacy.json 测试资源")
        return JSONArray(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    private fun answers(case: JSONObject): List<String> =
        case.getJSONArray("answers").let { array -> List(array.length()) { array.getString(it) } }

    private fun expectedCounts(case: JSONObject): List<Int> =
        case.getJSONArray("expectedCounts").let { array -> List(array.length()) { array.getInt(it) } }

    @Test
    fun `the legacy package parses and keeps its six-model roster`() {
        assertEquals(6, bank.modelCount)
        assertTrue("旧格式检测包的构建时间不应为空", bank.referenceBuiltAt.isNotBlank())
    }

    @Test
    fun `the legacy package reproduces the vectors it was pinned with`() {
        val cases = golden()
        assertTrue("旧格式向量不应为空", cases.length() > 0)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val expected = case.getJSONObject("expected")
            val analysis = bank.analyze(answers(case), expectedCounts(case))

            val order = expected.getJSONArray("order").let { array ->
                List(array.length()) { array.getString(it) }
            }
            assertEquals("$name：候选顺序与上游不一致", order, analysis.candidates.map { it.modelId })
            assertEquals("$name：首位候选不一致", expected.getString("prediction"), analysis.prediction?.modelId)
            assertEquals(
                "$name：评分路径不一致",
                if (expected.getString("method") == "shared-detector-v1") {
                    FingerprintScoring.FULL
                } else {
                    FingerprintScoring.PARTIAL
                },
                analysis.scoring,
            )
            assertEquals(
                "$name：置信度状态不一致",
                if (expected.optString("probabilityStatus") == "reference_calibrated") {
                    ProbabilityStatus.REFERENCE_CALIBRATED
                } else {
                    ProbabilityStatus.UNAVAILABLE
                },
                analysis.probabilityStatus,
            )

            val byModel = analysis.candidates.associateBy { it.modelId }
            val scores = expected.getJSONArray("candidateScores")
            for (scoreIndex in 0 until scores.length()) {
                val entry = scores.getJSONObject(scoreIndex)
                val actual = byModel.getValue(entry.getString("model"))
                assertEquals(
                    "$name / ${entry.getString("model")}：排名分数偏离上游",
                    entry.getDouble("rankingScore"),
                    actual.rankingScore,
                    SCORE_TOLERANCE,
                )
                if (entry.isNull("probability")) {
                    assertNull("$name / ${entry.getString("model")}：上游没有置信度", actual.probability)
                } else {
                    assertEquals(
                        "$name / ${entry.getString("model")}：置信度偏离上游",
                        entry.getDouble("probability"),
                        actual.probability ?: Double.NaN,
                        SCORE_TOLERANCE,
                    )
                }
            }
        }
    }

    private companion object {
        /** Same tolerance as the current-shape golden test: quantization is the only error. */
        const val SCORE_TOLERANCE = 1.0e-4
    }
}
