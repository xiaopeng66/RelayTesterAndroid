package com.relaytester.app.core.fingerprint

/**
 * Fixed-point reader over the packed bank asset produced by
 * `tools/build_fingerprint_asset.py`.
 *
 * Every float in the asset is stored as an int32 at [SCALE]. The value was chosen
 * from a sweep over the published reference answers: rounding to 1e-6 moves the
 * per-model score vector by at most 3.6e-5 but changes neither the top-1 candidate
 * nor the full ordering for any of the 53 reference models, while cutting the
 * asset from 906 KB (gzipped JSON) to 399 KB with no JSON parse at startup.
 */
internal class BankReader(private val bytes: ByteArray) {
    private var offset = 0

    fun u32(): Int {
        val value = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        offset += 4
        return value
    }

    fun float64(): Double {
        var bits = 0L
        for (index in 7 downTo 0) {
            bits = (bits shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        offset += 8
        return Double.fromBits(bits)
    }

    /** Reads [count] int32 values and lifts them back to floats at [SCALE]. */
    fun floats(count: Int): FloatArray {
        val out = FloatArray(count)
        for (index in 0 until count) {
            val raw = (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
            offset += 4
            out[index] = raw / SCALE
        }
        return out
    }

    /** Reads a nested matrix laid out row-major with [rows] x [columns] shape. */
    fun matrix(rows: Int, columns: Int): Array<FloatArray> =
        Array(rows) { floats(columns) }

    fun string(): String {
        val length = u32()
        val value = String(bytes, offset, length, Charsets.UTF_8)
        offset += length
        return value
    }

    fun expectMagic(expected: String) {
        val actual = String(bytes, 0, expected.length, Charsets.US_ASCII)
        require(actual == expected) { "指纹库资产格式不匹配" }
        offset = expected.length
    }

    companion object {
        const val SCALE = 1_000_000f
    }
}

/** One candidate model with its resolved ranking scores. */
data class FingerprintCandidate(
    val modelId: String,
    val displayName: String,
    val family: String,
    val familyName: String,
    /** Weighted ranker score; the candidate order follows this value. */
    val rankingScore: Double,
    /** Share of the calibrated softmax mass, or null when calibration is absent. */
    val probability: Double?,
)

/** Outcome of scoring one round of answers. */
data class FingerprintAnalysis(
    val candidates: List<FingerprintCandidate>,
    val familyName: String,
    val familyProbability: Double?,
    /** How many of the submitted answers carried enough valid integers. */
    val usableAnswers: Int,
    val submittedAnswers: Int,
    val diagnostics: List<AnswerDiagnostic>,
    val referenceBuiltAt: String,
    val answerCount: Int,
) {
    val prediction: FingerprintCandidate? get() = candidates.firstOrNull()
}

/** Per-answer validity report, surfaced so a single retry can be targeted. */
data class AnswerDiagnostic(
    val index: Int,
    val parsedNumbers: Int,
    val minimumNumbers: Int,
) {
    val accepted: Boolean get() = parsedNumbers >= minimumNumbers
}

/**
 * Smallest integer count an answer must carry to be scored.
 *
 * Kept as a free function so the scorer, the API path and the paste preview all
 * apply the identical rule; deriving it in more than one place lets the preview
 * disagree with what the analyzer actually used.
 */
fun minimumNumbersFor(expectedCount: Int, minimumValidNumbers: Int): Int =
    maxOf(minimumValidNumbers, kotlin.math.ceil(expectedCount * FingerprintBank.VALIDITY_RATIO).toInt())
