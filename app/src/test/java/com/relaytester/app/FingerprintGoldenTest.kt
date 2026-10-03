package com.relaytester.app

import com.relaytester.app.core.fingerprint.ChallengeGenerator
import com.relaytester.app.core.fingerprint.FingerprintBank
import com.relaytester.app.core.fingerprint.FingerprintScoring
import com.relaytester.app.core.fingerprint.NumberFeatures
import com.relaytester.app.core.fingerprint.ProbabilityStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Kotlin port against vectors produced by upstream's own detector.
 *
 * The vectors in `fingerprint-golden.json` are not hand-written: they come from running
 * upstream lm-detector's `shared/shared-detector.ts` (`analyzeSharedOutputs`) over the
 * same six models this fixture package carries, then keeping the model's reply text as
 * the answer. The two files are generated from one pair of upstream artifacts, and the
 * generator refuses to run when the package's build stamp and the detector's disagree —
 * a mismatch there would silently fall back to upstream's legacy ranker, and the vectors
 * would then describe a different algorithm.
 *
 * A numerical port fails quietly: a swapped coefficient or an off-by-one index still
 * yields a plausible-looking ranking. Asserting the candidate order *and* the per-model
 * scores is what catches that.
 */
class FingerprintGoldenTest {
    private val bank: FingerprintBank by lazy { BankFixtures.bank() }

    private fun golden(): JSONArray {
        val stream = BankFixtures.loader.getResourceAsStream("fingerprint-golden.json")
            ?: error("找不到 fingerprint-golden.json 测试资源")
        return JSONArray(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    private fun answers(case: JSONObject): List<String> =
        case.getJSONArray("answers").let { array -> List(array.length()) { array.getString(it) } }

    private fun expectedCounts(case: JSONObject): List<Int> =
        case.getJSONArray("expectedCounts").let { array -> List(array.length()) { array.getInt(it) } }

    @Test
    fun `fixture loads with the six-model roster`() {
        assertEquals(6, bank.modelCount)
        assertTrue("检测包构建时间不应为空", bank.referenceBuiltAt.isNotBlank())
        assertEquals("检测包推荐三条回答", 3, bank.recommendedAnswers)
    }

    @Test
    fun `golden vectors cover both scoring paths`() {
        val cases = golden()
        val methods = (0 until cases.length()).map {
            cases.getJSONObject(it).getJSONObject("expected").getString("method")
        }
        // A port that only ever took the full path would never prove the partial one.
        assertTrue("向量必须覆盖部分样本路径", methods.any { it == "shared-ranker-partial-v1" })
        assertTrue("向量必须覆盖完整三条回答路径", methods.any { it == "shared-detector-v1" })
    }

    @Test
    fun `candidate order matches the upstream detector`() {
        val cases = golden()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val expected = case.getJSONObject("expected")
            val analysis = bank.analyze(answers(case), expectedCounts(case))

            val order = expected.getJSONArray("order").let { array ->
                List(array.length()) { array.getString(it) }
            }
            assertEquals(
                "$name：候选顺序与上游不一致",
                order,
                analysis.candidates.map { it.modelId },
            )
            assertEquals(
                "$name：首位候选不一致",
                expected.getString("prediction"),
                analysis.prediction?.modelId,
            )

            // Upstream reports both the family slug (`family_prediction`) and its display
            // label (`family_prediction_name`); the panel shows the label, so the golden's
            // slug is checked against the roster entry the winner points at.
            val winner = bank.modelIds.indexOf(analysis.prediction?.modelId)
            assertTrue("$name：首位候选不在检测包名单内", winner >= 0)
            assertEquals("$name：家族判定不一致", expected.getString("familyPrediction"), bank.families[winner])
            assertEquals("$name：家族显示名不一致", bank.familyNames[winner], analysis.familyName)
        }
    }

    @Test
    fun `scores and probabilities match the upstream detector`() {
        val cases = golden()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val expected = case.getJSONObject("expected")
            val analysis = bank.analyze(answers(case), expectedCounts(case))
            val byModel = analysis.candidates.associateBy { it.modelId }

            val expectedScores = expected.getJSONArray("candidateScores")
            for (scoreIndex in 0 until expectedScores.length()) {
                val entry = expectedScores.getJSONObject(scoreIndex)
                val model = entry.getString("model")
                val actual = byModel.getValue(model)
                assertEquals(
                    "$name / $model：排名分数偏离上游",
                    entry.getDouble("rankingScore"),
                    actual.rankingScore,
                    SCORE_TOLERANCE,
                )
                if (entry.isNull("probability")) {
                    assertNull("$name / $model：上游没有置信度，本实现不得给出", actual.probability)
                } else {
                    assertEquals(
                        "$name / $model：置信度偏离上游",
                        entry.getDouble("probability"),
                        actual.probability ?: Double.NaN,
                        PROBABILITY_TOLERANCE,
                    )
                }
            }
        }
    }

    @Test
    fun `scoring path and calibration status match`() {
        val cases = golden()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val expected = case.getJSONObject("expected")
            val analysis = bank.analyze(answers(case), expectedCounts(case))

            val full = expected.getString("method") == "shared-detector-v1"
            assertEquals(
                "$name：评分路径不一致",
                if (full) FingerprintScoring.FULL else FingerprintScoring.PARTIAL,
                analysis.scoring,
            )
            val expectedStatus = if (expected.optString("probabilityStatus") == "reference_calibrated") {
                ProbabilityStatus.REFERENCE_CALIBRATED
            } else {
                ProbabilityStatus.UNAVAILABLE
            }
            assertEquals("$name：置信度状态不一致", expectedStatus, analysis.probabilityStatus)
            if (full) {
                assertNotNull("$name：完整路径必须给出置信度", analysis.candidates.first().probability)
            } else {
                assertNull("$name：部分样本路径不应给出置信度", analysis.candidates.first().probability)
            }
        }
    }

    @Test
    fun `calibrated probabilities sum to one and rank with the scores`() {
        val case = golden().getJSONObject(0)
        val analysis = bank.analyze(answers(case), expectedCounts(case))
        val probabilities = analysis.candidates.mapNotNull { it.probability }
        assertEquals("完整路径的每条候选都应有置信度", analysis.candidates.size, probabilities.size)
        assertEquals(1.0, probabilities.sum(), 1e-9)
        // The softmax is monotone in the ranking, so the order cannot disagree with it.
        assertEquals(
            analysis.candidates.map { it.modelId },
            analysis.candidates.sortedByDescending { it.rankingScore }.map { it.modelId },
        )
    }

    @Test
    fun `an unusable temperature gives ordering but no probability`() {
        // Upstream's calibrateRanking returns null when its gate does not hold, and the
        // panel then shows no percentage at all. With the hash binding collapsed (see the
        // file header of SharedScoring), the only remaining input that can make the
        // calibration unusable is tau itself, so both ends of its accepted range are
        // pinned here: a package with a silly temperature must not produce a number.
        for (tau in listOf(0.0, -1.0, 1.0e9)) {
            val patched = FingerprintBank.fromPackageBytes(bankWithTau(tau))
            val case = golden().getJSONObject(0)
            val analysis = patched.analyze(answers(case), expectedCounts(case))

            assertEquals("tau=$tau：置信度状态", ProbabilityStatus.UNAVAILABLE, analysis.probabilityStatus)
            assertNull("tau=$tau：不得给出置信度", analysis.candidates.first().probability)
            assertTrue("tau=$tau：仍必须给出候选排序", analysis.candidates.isNotEmpty())
        }
        // And the shipped fixture's own temperature is inside the range.
        val analysis = bank.analyze(answers(golden().getJSONObject(0)), expectedCounts(golden().getJSONObject(0)))
        assertEquals(ProbabilityStatus.REFERENCE_CALIBRATED, analysis.probabilityStatus)
    }

    @Test
    fun `a two-answer round ranks but refuses to claim a probability`() {
        val case = golden().getJSONObject(0)
        val analysis = bank.analyze(answers(case).take(2), expectedCounts(case).take(2))

        assertEquals(FingerprintScoring.PARTIAL, analysis.scoring)
        assertEquals(ProbabilityStatus.UNAVAILABLE, analysis.probabilityStatus)
        assertEquals(2, analysis.usableAnswers)
        assertNull(analysis.candidates.first().probability)
        assertTrue("部分样本仍必须给出完整候选排序", analysis.candidates.isNotEmpty())
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
    fun `feature sizes match what the package was built for`() {
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

    @Test
    fun `the validity floor follows the requested length and the package floor`() {
        assertEquals(165, bank.minimumNumbersFor(300))
        assertEquals(bank.minimumValidNumbers, bank.minimumNumbersFor(10))
    }

    @Test
    fun `a package with a different validity floor is honoured`() {
        val patched = FingerprintBank.fromPackageBytes(bankWithMinimumValid(120))
        assertEquals(120, patched.minimumValidNumbers)
        assertEquals("题目数量下限由绝对下限兜底", 120, patched.minimumNumbersFor(10))
        assertEquals("题目数量下限更高时按 55% 计", 165, patched.minimumNumbersFor(300))
    }

    @Test
    fun `a truncated package is refused instead of scoring nonsense`() {
        val bytes = BankFixtures.packageBytes()
        val truncated = bytes.copyOf(bytes.size / 2)
        assertTrue(
            "截断的检测包必须被拒绝",
            runCatching { FingerprintBank.fromPackageBytes(truncated) }.isFailure,
        )
        assertTrue(
            "多一个字节的检测包必须被拒绝",
            runCatching { FingerprintBank.fromPackageBytes(bankWithTrailingByte()) }.isFailure,
        )
        assertTrue(
            "换掉魔数的检测包必须被拒绝",
            runCatching {
                FingerprintBank.fromPackageBytes(bytes.copyOf().also { it[3] = 'X'.code.toByte() })
            }.isFailure,
        )
    }

    private companion object {
        /**
         * The package quantises every dense float to 1e-6 and the reference tensors to
         * 16 bits per row, and the score vector is a chain of z-scores and unit
         * normalisations over those values, so accumulated error is orders of magnitude
         * above the quantisation step. The measured worst deviation on this fixture is
         * 2.5e-5 in a ranking score and 1.2e-5 in a probability; 1e-4 is still far
         * tighter than the gap between any two distinct models on these vectors.
         */
        const val SCORE_TOLERANCE = 1.0e-4
        const val PROBABILITY_TOLERANCE = 1.0e-4
    }
}
