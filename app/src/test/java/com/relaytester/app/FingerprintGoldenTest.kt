package com.relaytester.app

import com.relaytester.app.core.fingerprint.ChallengeGenerator
import com.relaytester.app.core.fingerprint.FingerprintBank
import com.relaytester.app.core.fingerprint.NumberFeatures
import java.io.File
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Kotlin port against vectors produced by the reference
 * implementation (`shared/fingerprint-core.js` + `shared-detector.ts`).
 *
 * A numerical port fails quietly: a swapped coefficient or an off-by-one index
 * still yields a plausible-looking ranking. Asserting the full per-model score
 * vector against the reference is the only check that catches it.
 */
class FingerprintGoldenTest {
    private val bank: FingerprintBank by lazy {
        FingerprintBank.fromAssetBytes(assetFile().readBytes())
    }

    private fun assetFile(): File {
        // Gradle runs tests with the module directory as the working directory, but
        // a root-project run does not; try both so the test is not path-sensitive.
        val candidates = listOf(
            File("app/src/main/assets/lm-fingerprint/lite-bank.bin"),
            File("src/main/assets/lm-fingerprint/lite-bank.bin"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("找不到指纹资产文件，检查测试工作目录：${File(".").absolutePath}")
    }

    private fun golden(): JSONArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("fingerprint-golden.json")
            ?: error("找不到 fingerprint-golden.json 测试资源")
        return JSONArray(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    @Test
    fun `asset loads with the published model roster`() {
        assertEquals(53, bank.modelCount)
        assertTrue("参考库构建时间不应为空", bank.referenceBuiltAt.isNotBlank())
        assertEquals("参考库推荐三条回答", 3, bank.recommendedAnswers)
    }

    @Test
    fun `ranking matches the reference implementation`() {
        val cases = golden()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val answers = case.getJSONArray("answers").let { array ->
                List(array.length()) { array.getString(it) }
            }
            val expected = case.getJSONObject("expected")
            val analysis = bank.analyze(answers, List(answers.size) { 300 })

            assertEquals("$name：首位候选不一致", expected.getString("top1"), analysis.prediction?.modelId)

            val top5 = expected.getJSONArray("top5").let { array ->
                List(array.length()) { array.getString(it) }
            }
            assertEquals(
                "$name：前五候选顺序不一致",
                top5,
                analysis.candidates.take(5).map { it.modelId },
            )

            val expectedScores = expected.getJSONArray("candidateScores")
            val byModel = analysis.candidates.associateBy { it.modelId }
            for (scoreIndex in 0 until expectedScores.length()) {
                val entry = expectedScores.getJSONObject(scoreIndex)
                val model = entry.getString("model")
                val actual = byModel.getValue(model)
                assertEquals(
                    "$name / $model：排名分数偏离参考实现",
                    entry.getDouble("score"),
                    actual.rankingScore,
                    SCORE_TOLERANCE,
                )
                assertEquals(
                    "$name / $model：库内置信偏离参考实现",
                    entry.getDouble("probability"),
                    actual.probability ?: Double.NaN,
                    PROBABILITY_TOLERANCE,
                )
            }
        }
    }

    @Test
    fun `parser keeps only the longest run and breaks on letters`() {
        // A stray number in prose must not extend the answer sequence; this is the
        // rule the whole feature pipeline depends on.
        val parsed = NumberFeatures.parseNumbers("Here are 300 numbers:\n\n1, 2, 3\n\nLet me know.")
        assertEquals(listOf(1, 2, 3), parsed.toList())

        val longer = NumberFeatures.parseNumbers("prefix 5, 6 numbers: 10, 20, 30, 40 suffix")
        assertEquals(listOf(10, 20, 30, 40), longer.toList())

        // Out-of-range values are dropped but do not split the run.
        val dropped = NumberFeatures.parseNumbers("0, 1, 356, 2, 355, 999")
        assertEquals(listOf(1, 2, 355), dropped.toList())
    }

    @Test
    fun `ordered block feature is 74 dims and hellinger is 355`() {
        val numbers = NumberFeatures.parseNumbers((1..300).joinToString(","))
        assertEquals(355, NumberFeatures.hellingerFeature(NumberFeatures.countNumbers(numbers)).size)
        assertEquals(74, NumberFeatures.orderedBlockFeature(numbers).size)
    }

    @Test
    fun `challenge lengths stay in range and stay unique per round`() {
        repeat(50) {
            val challenges = ChallengeGenerator.generate(3)
            assertEquals(3, challenges.size)
            assertEquals("每轮长度必须互不相同", 3, challenges.map { it.expectedCount }.distinct().size)
            for (challenge in challenges) {
                assertTrue(
                    "题面长度必须落在 292..332，实际 ${challenge.expectedCount}",
                    challenge.expectedCount in 292..332,
                )
                assertTrue("题面不应为空", challenge.prompt.isNotBlank())
                assertTrue(
                    "题面必须写明本次数量",
                    challenge.prompt.contains(challenge.expectedCount.toString()),
                )
            }
        }
    }

    @Test
    fun `challenge retry avoids the lengths already in the round`() {
        val used = listOf(300, 310, 320)
        repeat(20) {
            val replacement = ChallengeGenerator.generate(1, usedLengths = used).single()
            assertTrue(
                "重试题目不应与仍在场上的题目同长度",
                replacement.expectedCount !in used,
            )
        }
    }

    @Test
    fun `analyze rejects answers below the validity floor`() {
        val short = "1, 2, 3, " + (1..50).joinToString(",")
        val good = (1..300).joinToString(",")
        val analysis = bank.analyze(listOf(short, good, good), listOf(300, 300, 300))
        assertEquals("只有两条回答有效", 2, analysis.usableAnswers)
        assertEquals(3, analysis.submittedAnswers)
        assertTrue("第一条应判定为无效", !analysis.diagnostics[0].accepted)
        assertTrue("第二条应判定为有效", analysis.diagnostics[1].accepted)
    }

    @Test
    fun `analyze throws when no answer carries enough numbers`() {
        val error = runCatching {
            bank.analyze(listOf("1, 2, 3", "4, 5", "6"), listOf(300, 300, 300))
        }.exceptionOrNull()
        assertTrue("应抛出可用性错误，实际 $error", error is IllegalArgumentException)
    }

    private companion object {
        /**
         * The asset quantises every float to 1e-6, and the score vector is a chain of
         * z-scores and unit normalisations over those values, so accumulated error is
         * several orders of magnitude above the quantisation step. 1e-4 is still far
         * tighter than the gap between any two distinct models.
         */
        const val SCORE_TOLERANCE = 1.0e-4
        const val PROBABILITY_TOLERANCE = 1.0e-4
    }
}
