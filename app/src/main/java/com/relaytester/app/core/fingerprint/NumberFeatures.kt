package com.relaytester.app.core.fingerprint

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Direct port of `shared/fingerprint-core.js` from lm-detector (MIT).
 *
 * The run-splitting rule in [parseNumbers] is load-bearing and easy to get wrong:
 * a letter anywhere between two numbers starts a new run, and only the longest run
 * survives. Models frequently answer with prose or a JSON key before the list, so a
 * naive "take every number in the text" parse silently merges unrelated digits into
 * the sequence and shifts every downstream feature.
 */
object NumberFeatures {
    const val VALUE_MIN = 1
    const val VALUE_MAX = 355
    const val DIMENSION = VALUE_MAX - VALUE_MIN + 1
    const val ALPHA = 0.5
    const val ORDERED_BLOCK_WEIGHT = 0.25

    /** Width of the position kernel: 24 value centres plus the last digit, times 3 basis functions. */
    const val POSITION_KERNEL_DIMENSION = 34 * 3

    /** Width of the sequence block: 8 lags x 6 statistics, both transition tables, 5 + 3 tail. */
    const val SEQUENCE_DIMENSION = 8 * 6 + 8 * 8 + 10 * 10 + 5 + 3

    /** Lags the autocorrelation statistics are taken at, in order. */
    private val SEQUENCE_LAGS = intArrayOf(1, 2, 3, 5, 8, 13, 21, 34)

    /** Sliding-window widths the distinct-value statistics are averaged over, in order. */
    private val SEQUENCE_WINDOWS = intArrayOf(4, 8, 16)

    /** Value centres of the position kernel's radial basis functions. */
    private val KERNEL_CENTRE_VALUES = DoubleArray(24) { VALUE_MIN + it * (VALUE_MAX - VALUE_MIN) / 23.0 }

    private const val KERNEL_CENTRES = 24
    private const val KERNEL_DIGITS = 10
    private const val KERNEL_SIGMA = 20.0

    private val DIGITS = Regex("\\d+")

    /** Unicode code points, not UTF-16 halves, matching the JS `/\p{L}/u` test. */
    private fun String.containsLetterAnyScript(): Boolean {
        var index = 0
        while (index < length) {
            val codePoint = Character.codePointAt(this, index)
            if (Character.isLetter(codePoint)) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    /**
     * Returns the longest run of in-range integers. Runs break on letters, so a
     * stray "355" in a sentence does not extend the answer sequence.
     */
    fun parseNumbers(text: String): IntArray {
        var best = IntArray(0)
        var current = ArrayList<Int>()
        var previousEnd = 0
        for (match in DIGITS.findAll(text)) {
            val separator = text.substring(previousEnd, match.range.first)
            val value = match.value.toIntOrNull()
            if (current.isNotEmpty() && separator.containsLetterAnyScript()) {
                if (current.size > best.size) best = current.toIntArray()
                current = ArrayList()
            }
            if (value != null && value in VALUE_MIN..VALUE_MAX) current.add(value)
            previousEnd = match.range.last + 1
        }
        if (current.size > best.size) best = current.toIntArray()
        return best
    }

    fun countNumbers(numbers: IntArray): FloatArray {
        val counts = FloatArray(DIMENSION)
        for (number in numbers) counts[number - VALUE_MIN] += 1f
        return counts
    }

    /**
     * 355-dim smoothed frequency feature: sqrt((c + alpha) / (N + alpha * D)).
     *
     * Computed in double precision to match the reference implementation; the
     * downstream z-score/unit-normalise chain amplifies a float rounding error
     * well past the quantisation step of the stored parameters.
     */
    fun hellingerFeature(counts: FloatArray): DoubleArray {
        var total = ALPHA * DIMENSION
        for (count in counts) total += count
        return DoubleArray(DIMENSION) { index -> sqrt((counts[index] + ALPHA) / total) }
    }

    /** 4 position blocks x 16 value bins, plus the final-digit distribution: 74 dims. */
    fun orderedBlockFeature(numbers: IntArray): DoubleArray {
        val out = DoubleArray(4 * 16 + 10)
        var cursor = 0
        val base = numbers.size / 4
        val remainder = numbers.size % 4
        for (block in 0 until 4) {
            val size = base + if (block < remainder) 1 else 0
            val bins = DoubleArray(16) { 0.5 }
            for (index in cursor until cursor + size) {
                val value = numbers[index]
                bins[min(15, floor((value - 1) / 355.0 * 16.0).toInt())] += 1.0
            }
            var total = 0.0
            for (bin in bins) total += bin
            for (bin in 0 until 16) out[block * 16 + bin] = sqrt(bins[bin] / total)
            cursor += size
        }
        val lastDigits = DoubleArray(10) { 0.5 }
        for (number in numbers) lastDigits[number % 10] += 1.0
        var lastTotal = 0.0
        for (digit in lastDigits) lastTotal += digit
        for (digit in 0 until 10) out[64 + digit] = sqrt(lastDigits[digit] / lastTotal)
        return out
    }

    /**
     * 102-dim position kernel: where in the answer each value sits, weighted by three
     * Legendre polynomials over the normalised position.
     *
     * 24 radial basis functions over the value range plus the last-digit indicator (34
     * rows), each projected onto [1, t, ½(3t²-1), ½(5t³-3t)]. The answer's *order* is
     * what this block sees: the frequency feature is a bag of counts and cannot tell a
     * ramp from a shuffle. Blocks are averaged over positions, so the block does not
     * depend on the answer's length beyond that normalisation.
     */
    fun positionKernel(numbers: IntArray): DoubleArray {
        val out = DoubleArray(POSITION_KERNEL_DIMENSION)
        val size = numbers.size
        if (size == 0) return out
        for (position in 0 until size) {
            val value = numbers[position].toDouble()
            val t = (position + 0.5) * 2.0 / size - 1.0
            val basis = doubleArrayOf(t, 0.5 * (3.0 * t * t - 1.0), 0.5 * (5.0 * t * t * t - 3.0 * t))
            for (centre in 0 until KERNEL_CENTRES) {
                val distance = (value - KERNEL_CENTRE_VALUES[centre]) / KERNEL_SIGMA
                val weight = exp(-0.5 * distance * distance) / size
                for (j in 0..2) out[centre * 3 + j] += weight * basis[j]
            }
            val digit = numbers[position] % 10
            for (candidate in 0 until KERNEL_DIGITS) {
                if (candidate != digit) continue
                for (j in 0..2) out[(KERNEL_CENTRES + candidate) * 3 + j] += basis[j] / size
            }
        }
        return out
    }

    /**
     * 220-dim sequence feature: how the answer *moves*, not just what it contains.
     *
     * Per lag in [SEQUENCE_LAGS]: the autocorrelation of the z-scored sequence and five
     * fractions of the differences (mean absolute step, rising, flat, small, medium).
     * Then the transitions between quantised values and between last digits, each
     * centred by subtracting the outer product of its own marginals, five run-quality
     * fractions (sign persistence, repeated step, same last digit, large z-steps), and
     * the mean distinct-value count over sliding windows of 4, 8 and 16.
     *
     * Runs of zero length are skipped rather than divided by zero: upstream would emit
     * NaN for an answer with fewer numbers than the largest lag, and this app rejects
     * such an answer long before it is scored (`minimumNumbersFor` never goes below 80),
     * so producing a number instead of a NaN cannot mask a difference upstream would see.
     */
    fun sequenceFeatures(numbers: IntArray): DoubleArray {
        val out = DoubleArray(SEQUENCE_DIMENSION)
        val size = numbers.size
        if (size == 0) return out
        var total = 0.0
        for (value in numbers) total += value
        val average = total / size
        var variance = 0.0
        for (value in numbers) variance += (value - average) * (value - average)
        val deviation = maxOf(sqrt(variance / size), 1.0)
        val zs = DoubleArray(size) { (numbers[it] - average) / deviation }

        var cursor = 0
        for (lag in SEQUENCE_LAGS) {
            val count = size - lag
            if (count > 0) {
                var cross = 0.0
                var step = 0.0
                var rising = 0
                var flat = 0
                var small = 0
                var medium = 0
                for (index in 0 until count) {
                    val difference = numbers[index + lag] - numbers[index]
                    val magnitude = abs(difference)
                    cross += zs[index + lag] * zs[index]
                    step += magnitude
                    if (difference > 0) rising++
                    if (difference == 0) flat++
                    if (magnitude <= 5) small++
                    if (magnitude <= 20) medium++
                }
                out[cursor++] = cross / count
                out[cursor++] = step / count / VALUE_MAX
                out[cursor++] = rising.toDouble() / count
                out[cursor++] = flat.toDouble() / count
                out[cursor++] = small.toDouble() / count
                out[cursor++] = medium.toDouble() / count
            } else {
                cursor += 6
            }
        }

        val coarser = IntArray(size) { minOf(7, (numbers[it] - VALUE_MIN) * 8 / VALUE_MAX) }
        val transitions = centredTransitions(coarser, 8)
        for (value in transitions) out[cursor++] = value
        val digits = IntArray(size) { numbers[it] % 10 }
        for (value in centredTransitions(digits, 10)) out[cursor++] = value

        val difference = IntArray(size - 1) { numbers[it + 1] - numbers[it] }
        out[cursor++] = fraction(size - 2) { index ->
            Integer.signum(difference[index + 1]) == Integer.signum(difference[index])
        }
        out[cursor++] = fraction(size - 2) { index -> difference[index + 1] == difference[index] }
        out[cursor++] = fraction(size - 1) { index -> numbers[index] % 10 == numbers[index + 1] % 10 }
        out[cursor++] = fraction(size - 1) { index -> abs(zs[index + 1] - zs[index]) > 1.0 }
        out[cursor++] = fraction(size - 1) { index -> abs(zs[index + 1] - zs[index]) > 2.0 }

        val buckets = IntArray(size) { minOf(15, (numbers[it] - VALUE_MIN) * 16 / VALUE_MAX) }
        for (width in SEQUENCE_WINDOWS) {
            var sum = 0.0
            if (size >= width) {
                for (start in 0..size - width) {
                    sum += distinct(buckets, start, width).toDouble() / width
                }
                out[cursor++] = sum / (size - width + 1)
            } else {
                cursor++
            }
        }
        return out
    }

    /**
     * A symbol-pair table with each cell's independence residual.
     *
     * The table starts at a half count per cell (so an unobserved pair is a small
     * positive number rather than a hole), is normalised by the row total, and then has
     * `row × column` subtracted: what is left is how much more often a pair occurs than
     * the two symbols occurring independently. Centring matters because the marginal
     * symbol frequencies are themselves model signal — without it this block would
     * mostly re-measure the frequency feature.
     */
    private fun centredTransitions(symbols: IntArray, bins: Int): DoubleArray {
        val joint = DoubleArray(bins * bins) { 0.5 }
        val total = (symbols.size - 1) + 0.5 * bins * bins
        for (index in 1 until symbols.size) {
            joint[symbols[index - 1] * bins + symbols[index]] += 1.0
        }
        for (index in joint.indices) joint[index] /= total
        val rows = DoubleArray(bins) { row ->
            var sum = 0.0
            for (column in 0 until bins) sum += joint[row * bins + column]
            sum
        }
        val columns = DoubleArray(bins) { column ->
            var sum = 0.0
            for (row in 0 until bins) sum += joint[row * bins + column]
            sum
        }
        return DoubleArray(joint.size) { joint[it] - rows[it / bins] * columns[it % bins] }
    }

    /** Fraction of [count] indices satisfying [test]; zero when there is no index. */
    private fun fraction(count: Int, test: (Int) -> Boolean): Double {
        if (count <= 0) return 0.0
        var hits = 0
        for (index in 0 until count) if (test(index)) hits++
        return hits.toDouble() / count
    }

    /** Distinct values in `values[start until start + width]`. */
    private fun distinct(values: IntArray, start: Int, width: Int): Int {
        var mask = 0
        for (index in start until start + width) mask = mask or (1 shl values[index])
        return Integer.bitCount(mask)
    }
}
