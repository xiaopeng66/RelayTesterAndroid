package com.relaytester.app.core.fingerprint

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

    private val DIGITS = Regex("\\d+")

    /** True for any Unicode letter, matching the JS `/\p{L}/u` test. */
    private fun Char.isLetterAnyScript(): Boolean = Character.isLetter(this)

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
            if (current.isNotEmpty() && separator.any { it.isLetterAnyScript() }) {
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
}
