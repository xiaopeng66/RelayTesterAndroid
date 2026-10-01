package com.relaytester.app.core.fingerprint

/**
 * Fixed-point reader over the packed detection package produced by
 * `tools/build_fingerprint_asset.py`.
 *
 * Every dense scalar block is an int32 at [SCALE]. The value was chosen from a sweep
 * over the published reference answers: rounding to 1e-6 moves the per-model score
 * vector by at most 3.6e-5 and changes no ordering. The two reference tensors (1948
 * ranker rows and 1272 verifier rows of 429 values) are quantized per row instead:
 * as int32 they alone would be 5.5 MB, and 8-bit codes reorder near-tied candidates
 * (measured: 38 of 75,790 pairs), so the package ships them at 16 bits.
 */
internal class BankReader(private val bytes: ByteArray) {
    private var offset = 0

    /** Bytes not yet read; every count from the file is checked against it before use. */
    val remaining: Int get() = bytes.size - offset

    /** Bytes consumed so far; lets a test locate a header field without hard-coding it. */
    val consumed: Int get() = offset

    /** True when the reader consumed the package exactly, with no trailing byte. */
    val fullyRead: Boolean get() = offset == bytes.size

    /**
     * Rejects a count the file cannot possibly hold.
     *
     * Counts are read out of the file itself, and the package is downloaded, so a
     * truncated or hand-made one could otherwise ask for a multi-gigabyte allocation
     * and kill the process with an OutOfMemoryError before any format check ran.
     * Bounding every allocation by the file size keeps that impossible.
     */
    fun requireCapacity(elements: Int, bytesEach: Int) {
        val needed = elements.toLong() * bytesEach.toLong()
        if (elements < 0 || needed > remaining.toLong()) {
            throw IllegalArgumentException("指纹检测包已截断")
        }
    }

    fun u32(): Int {
        requireCapacity(1, 4)
        val value = int32At(offset)
        offset += 4
        return value
    }

    fun float64(): Double {
        requireCapacity(1, 8)
        var bits = 0L
        for (index in 7 downTo 0) {
            bits = (bits shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        offset += 8
        return Double.fromBits(bits)
    }

    fun float32(count: Int): FloatArray {
        requireCapacity(count, 4)
        val out = FloatArray(count)
        for (index in 0 until count) {
            out[index] = Float.fromBits(int32At(offset))
            offset += 4
        }
        return out
    }

    /** Reads [count] int32 values and lifts them back to doubles at [SCALE]. */
    fun floats(count: Int): DoubleArray {
        requireCapacity(count, 4)
        val out = DoubleArray(count)
        for (index in 0 until count) {
            out[index] = int32At(offset) / SCALE
            offset += 4
        }
        return out
    }

    fun int32(count: Int): IntArray {
        requireCapacity(count, 4)
        val out = IntArray(count)
        for (index in 0 until count) {
            out[index] = int32At(offset)
            offset += 4
        }
        return out
    }

    /** Reads a row-major [rows] x [columns] matrix of fixed-point values. */
    fun matrix(rows: Int, columns: Int): Array<DoubleArray> {
        // The rows array itself is sized from the file, so bound it before allocating.
        requireCapacity(rows, 8)
        return Array(rows) { floats(columns) }
    }

    fun bytes(count: Int): ByteArray {
        requireCapacity(count, 1)
        val out = bytes.copyOfRange(offset, offset + count)
        offset += count
        return out
    }

    fun string(): String {
        val length = u32()
        requireCapacity(length, 1)
        val value = String(bytes, offset, length, Charsets.UTF_8)
        offset += length
        return value
    }

    fun expectMagic(expected: String) {
        requireCapacity(expected.length, 1)
        val actual = String(bytes, 0, expected.length, Charsets.US_ASCII)
        require(actual == expected) { "指纹检测包格式不匹配" }
        offset = expected.length
    }

    /**
     * One quantized reference tensor: `[bits][columns][models]`, then per model
     * `[rows][float32 scales][packed codes]`.
     *
     * The code width is read from the file rather than assumed, because 8-bit is a
     * supported build of the same format and a reader that guessed 16 would silently
     * read every row at the wrong stride.
     */
    fun quantizedReferences(): List<QuantizedReferences> {
        val bits = u32()
        if (bits != 8 && bits != 16) {
            throw IllegalArgumentException("指纹检测包参考张量位宽不受支持（$bits）")
        }
        val columns = u32()
        val models = u32()
        // Each model costs at least its row count and one scale, so the count is bounded.
        requireCapacity(models, 8)
        val width = if (bits == 16) 2 else 1
        return List(models) {
            val rows = u32()
            requireCapacity(rows, 4)
            val scales = float32(rows)
            val data = bytes(rows * columns * width)
            QuantizedReferences(columns, rows, bits, scales, data)
        }
    }

    private fun int32At(at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    companion object {
        const val SCALE = 1_000_000.0
    }
}

/**
 * One model's reference vectors, kept as quantized codes.
 *
 * The row's own peak set its scale at build time, and the squared norm is precomputed
 * because the kNN term needs it for every query (upstream caches it the same way).
 * Rows are dequantized inside the dot loop rather than copied out first, so scoring a
 * whole answer costs no per-row allocation.
 */
internal class QuantizedReferences(
    val columns: Int,
    val rows: Int,
    val bits: Int,
    private val scales: FloatArray,
    private val data: ByteArray,
) {
    private val bytesPerValue = if (bits == 16) 2 else 1

    /** Squared L2 norm of every row, for upstream's `xx + ‖r‖² - 2·x·r` distance. */
    val rowNorms: DoubleArray = DoubleArray(rows) { row ->
        var total = 0.0
        for (column in 0 until columns) {
            val value = code(row, column) * scales[row]
            total += value * value
        }
        total
    }

    private fun code(row: Int, column: Int): Double {
        val index = (row * columns + column) * bytesPerValue
        return if (bits == 16) {
            (((data[index].toInt() and 0xFF) or (data[index + 1].toInt() shl 8)).toShort()).toDouble()
        } else {
            data[index].toDouble()
        }
    }

    /** Dequantized value at [row]/[column]; used by the format tests. */
    fun value(row: Int, column: Int): Double = code(row, column) * scales[row]

    fun dot(vector: DoubleArray, row: Int): Double {
        var total = 0.0
        val scale = scales[row].toDouble()
        for (column in 0 until columns) {
            total += code(row, column) * scale * vector[column]
        }
        return total
    }

    /** Distance from [vector] to [row], clamped at zero exactly as upstream does. */
    fun squaredDistance(vector: DoubleArray, row: Int, vectorNorm: Double): Double {
        val distance = vectorNorm + rowNorms[row] - 2.0 * dot(vector, row)
        return if (distance > 0.0) distance else 0.0
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
    /** Verifier logit, or null on the partial path (fewer than three answers). */
    val verificationScore: Double?,
    /** Share of the calibrated softmax mass, or null when the ranking is partial. */
    val probability: Double?,
)

/** Which of upstream's two scoring paths produced an analysis. */
enum class FingerprintScoring {
    /** Three usable answers: ranker + verifier + calibrated probability. */
    FULL,

    /** One or two usable answers: ranking only, no verifier score and no probability. */
    PARTIAL,
}

/** Whether a calibrated probability is available, mirroring upstream's status field. */
enum class ProbabilityStatus {
    /** `tau` was present and sane, so the softmax over the ranking is a real probability. */
    REFERENCE_CALIBRATED,

    /** No usable calibration: the panel must not show a percentage. */
    UNAVAILABLE,
}

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
    val scoring: FingerprintScoring,
    val probabilityStatus: ProbabilityStatus,
    /** Model the verifier scored highest, or null on the partial path. */
    val verificationTopModelId: String?,
    /** True when the verifier's best candidate is also the ranking's best. */
    val verifierAgrees: Boolean?,
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
