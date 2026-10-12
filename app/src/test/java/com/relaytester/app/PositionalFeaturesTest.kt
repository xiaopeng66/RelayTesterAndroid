package com.relaytester.app

import com.relaytester.app.core.fingerprint.NumberFeatures
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v2 positional feature blocks against vectors upstream itself produced.
 *
 * The ranking golden in [FingerprintGoldenTest] would catch a wrong *value* in either
 * block, but it can only say that the whole positional half disagrees; these vectors say
 * *which* block. They come from upstream's own `shared/positional.ts` via
 * `tools/make_positional_vectors.ts` — the two functions are module-private there, so the
 * generator imports a copy of the file with `export` added to those two declarations and
 * nothing else changed. The cases are chosen for the shapes that make the arithmetic
 * degenerate in different ways: a strictly increasing ramp, one repeated value (zero
 * variance, and a zero standard deviation that must fall back to 1), a cycled-last-digit
 * sequence, and an answer shorter than the largest of the eight lags.
 *
 * Only the blocks are pinned here; the standardisation, unit normalisation and per-model
 * projection on top of them are the package's own numbers, which the golden vectors cover.
 */
class PositionalFeaturesTest {
    private fun vectors(): JSONArray {
        val stream = BankFixtures.loader.getResourceAsStream("positional-feature-blocks.json")
            ?: error("找不到 positional-feature-blocks.json 测试资源")
        return JSONArray(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    private fun numbers(case: JSONObject): IntArray {
        val array = case.getJSONArray("numbers")
        return IntArray(array.length()) { array.getInt(it) }
    }

    private fun doubles(case: JSONObject, key: String): DoubleArray {
        val array = case.getJSONArray(key)
        return DoubleArray(array.length()) { array.getDouble(it) }
    }

    @Test
    fun `the vectors cover both blocks at their declared width`() {
        val cases = vectors()
        assertTrue("特征向量不应为空", cases.length() > 0)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertEquals(
                "${case.getString("name")}：位置核宽度",
                NumberFeatures.POSITION_KERNEL_DIMENSION,
                case.getJSONArray("positionKernel").length(),
            )
            assertEquals(
                "${case.getString("name")}：序列特征宽度",
                NumberFeatures.SEQUENCE_DIMENSION,
                case.getJSONArray("sequenceFeatures").length(),
            )
        }
    }

    @Test
    fun `the position kernel matches upstream`() {
        val cases = vectors()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val actual = NumberFeatures.positionKernel(numbers(case))
            val expected = doubles(case, "positionKernel")
            for (dimension in expected.indices) {
                assertEquals(
                    "$name：位置核第 $dimension 维偏离上游",
                    expected[dimension],
                    actual[dimension],
                    TOLERANCE,
                )
            }
        }
    }

    @Test
    fun `the sequence features match upstream`() {
        val cases = vectors()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val actual = NumberFeatures.sequenceFeatures(numbers(case))
            val expected = doubles(case, "sequenceFeatures")
            for (dimension in expected.indices) {
                assertEquals(
                    "$name：序列特征第 $dimension 维偏离上游",
                    expected[dimension],
                    actual[dimension],
                    TOLERANCE,
                )
            }
        }
    }

    @Test
    fun `an answer shorter than the largest lag stays finite`() {
        // Upstream divides by a zero-length run here and emits NaN; this implementation
        // skips the run instead. The app rejects an answer like this long before it is
        // scored (`minimumNumbersFor` never goes below 80), so what matters is that the
        // guard is deliberate: a NaN would poison the whole ranking through the z-scores
        // if a caller ever did reach this with a short sequence.
        for (size in 1..3) {
            val numbers = IntArray(size) { it + 1 }
            assertTrue("长度 $size 的位置核必须全部有限", NumberFeatures.positionKernel(numbers).all { it.isFinite() })
            assertTrue("长度 $size 的序列特征必须全部有限", NumberFeatures.sequenceFeatures(numbers).all { it.isFinite() })
        }
    }

    private companion object {
        /**
         * Both sides are plain double arithmetic in the same order, so the only difference
         * the comparison should see is the rounding of the vectors' 13-digit decimal
         * representation.
         */
        const val TOLERANCE = 1.0e-9
    }
}
