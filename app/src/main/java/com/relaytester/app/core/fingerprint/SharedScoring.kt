package com.relaytester.app.core.fingerprint

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Direct port of upstream lm-detector's `shared/shared-detector.ts` (`shared-detector-v1`).
 *
 * Upstream scores the answers through one ranker and calibrates the ranking:
 *
 *   ranking = 0.5 · z(mean of per-answer LDA projections over the candidate models)
 *           + 0.25 · z(median over answers of the negative kNN distance)
 *           + 0.25 · z(mean of the centroid baseline)
 *
 * and, with exactly three usable answers, the calibrated probability is a softmax over
 * that ranking with a single fitted temperature [tau].
 *
 * Upstream used to run a second scorer — a logistic "verifier" that re-scored the same
 * candidates and was used only to flag whether its own best candidate agreed with the
 * ranking — and it was removed from the artifact (upstream `d53d3f5b`, 58 models). It
 * never reordered candidates nor changed a probability, so dropping it changes nothing
 * but the (now gone) agreement caveat. A package in the older format still carries the
 * block; [FingerprintBank] steps over it rather than scoring with it.
 *
 * A port like this fails quietly — a swapped coefficient still yields a plausible
 * ranking — so every constant here is the one upstream uses, and the golden-vector
 * test compares the full per-model score vector against vectors upstream itself
 * produced.
 *
 * Deliberate deviation: upstream gates the temperature softmax on hashes that bind the
 * artifact to the bank it was fitted with. Our package *is* that binding — it is built
 * from one pair of upstream files and carries their digests — so the gate collapses to
 * "the package parsed and carries a sane tau".
 */

/** Feature standardisation for one block (`head_params` / `full_params`). */
internal class FeatureParams(val mean: DoubleArray, val scale: DoubleArray)

/** A centroid feature bank: standardisation, the nuisance basis, and per-model centroids. */
internal class FeatureBank(
    val mean: DoubleArray,
    val scale: DoubleArray,
    val nuisanceBasis: Array<DoubleArray>,
    val centroids: Array<DoubleArray>,
)

/** Everything the shared detector needs, parsed from the package. */
internal class DetectorWeights(
    val headParams: Array<FeatureParams>,
    val fullParams: Array<FeatureParams>,
    val ldaWeights: Array<DoubleArray>,
    val ldaBias: DoubleArray,
    val hellinger: FeatureBank,
    val ordered: FeatureBank,
    /** `environment_centroids[environment][model]`, 74 values each. */
    val environments: Array<Array<DoubleArray>>,
    val references: List<QuantizedReferences>,
    val tau: Double,
)

/** Ranker output: the fused ranking plus the per-answer feature blocks it was built from. */
internal class Ranked(
    val ranking: DoubleArray,
    val blocks: List<Array<DoubleArray>>,
    val transformed: List<DoubleArray>,
)

internal object SharedScoring {

    /** [hellinger, orderedBlock] for one answer, the input to every transform below. */
    fun blocksOf(numbers: IntArray): Array<DoubleArray> = arrayOf(
        NumberFeatures.hellingerFeature(NumberFeatures.countNumbers(numbers)),
        NumberFeatures.orderedBlockFeature(numbers),
    )

    /** The ranker: per-answer LDA, kNN distance and centroid baseline, fused. */
    fun rank(numbers: List<IntArray>, weights: DetectorWeights): Ranked {
        require(numbers.isNotEmpty()) { "至少需要一条回答" }
        val full = numbers.map(::blocksOf)
        val transformed = full.map { transform(it, weights.fullParams) }

        val lda = numbers.map { numbers0 ->
            // Upstream reads only the first 128 integers for the LDA head.
            val head = if (numbers0.size > 128) numbers0.copyOf(128) else numbers0
            val x = transform(blocksOf(head), weights.headParams)
            z(DoubleArray(weights.ldaWeights.size) { dot(weights.ldaWeights[it], x) + weights.ldaBias[it] })
        }
        val fused = z(columnMean(lda))

        val near = z(
            weights.references.map { reference ->
                median(transformed.map { -nearest(it, reference) })
            }.toDoubleArray(),
        )

        val base = z(columnMean(full.map { baseline(it, weights) }))

        val ranking = DoubleArray(fused.size) {
            0.5 * fused[it] + 0.25 * near[it] + 0.25 * base[it]
        }
        require(ranking.all { it.isFinite() }) { "排名计算产生无效数值，请刷新后重试" }
        return Ranked(ranking, full, transformed)
    }

    /**
     * Closed-set probabilities from `softmax(tau · ranking)`, or null when the package
     * carries no usable temperature.
     *
     * Upstream's `calibrateRanking` returns null there (`probability_status: 'unavailable'`)
     * rather than inventing a number, and so does this: the caller then reports no
     * probability at all and the panel shows no percentage.
     */
    fun calibrate(ranking: DoubleArray, tau: Double): DoubleArray? {
        if (!tau.isFinite() || tau < 0.001 || tau > 1000.0) return null
        val logits = DoubleArray(ranking.size) { tau * ranking[it] }
        val maximum = logits.max()
        var total = 0.0
        val weights = DoubleArray(logits.size) {
            val value = exp(logits[it] - maximum)
            total += value
            value
        }
        return DoubleArray(weights.size) { weights[it] / total }
    }

    /** Concatenates the standardised, unit-normalised blocks with their fixed weights. */
    private fun transform(blocks: Array<DoubleArray>, params: Array<FeatureParams>): DoubleArray {
        var size = 0
        for (block in blocks) size += block.size
        val out = DoubleArray(size)
        var cursor = 0
        for (j in blocks.indices) {
            val block = blocks[j]
            val mean = params[j].mean
            val scale = params[j].scale
            val scaled = DoubleArray(block.size) { (block[it] - mean[it]) / scale[it] }
            val unit = unit(scaled)
            // Upstream hardcodes the two block weights: 0.75 for Hellinger, 0.25 for ordered.
            val weight = sqrt(if (j == 0) 0.75 else 0.25)
            for (value in unit) {
                out[cursor++] = value * weight
            }
        }
        return out
    }

    /**
     * The centroid baseline: a marginal Hellinger cosine, plus a maxima-of-environment
     * template cosine fused 50/50 with the projected nuisance cosine.
     */
    private fun baseline(blocks: Array<DoubleArray>, weights: DetectorWeights): DoubleArray {
        val hellinger = weights.hellinger
        val ordered = weights.ordered
        val hellingerUnit = unit(subtract(centered(blocks[0], hellinger), hellinger.nuisanceBasis))
        val marginal = DoubleArray(hellinger.centroids.size) { dot(hellingerUnit, hellinger.centroids[it]) }

        val orderedCentered = centered(blocks[1], ordered)
        val raw = unit(orderedCentered)
        val projected = unit(subtract(orderedCentered, ordered.nuisanceBasis))

        val templates = DoubleArray(ordered.centroids.size) { model ->
            var best = Double.NEGATIVE_INFINITY
            for (environment in weights.environments) {
                val value = dot(raw, environment[model])
                if (value > best) best = value
            }
            best
        }
        val nuisance = DoubleArray(ordered.centroids.size) { dot(projected, ordered.centroids[it]) }

        val templateZ = z(templates)
        val nuisanceZ = z(nuisance)
        val fusedOrdered = z(DoubleArray(ordered.centroids.size) {
            0.5 * templateZ[it] + 0.5 * nuisanceZ[it]
        })
        val marginalZ = z(marginal)
        return DoubleArray(ordered.centroids.size) { 0.5 * marginalZ[it] + 0.5 * fusedOrdered[it] }
    }

    /** Mean of the seven smallest distances from [vector] to the model's references. */
    private fun nearest(vector: DoubleArray, references: QuantizedReferences): Double {
        val norm = dot(vector, vector)
        val distances = DoubleArray(references.rows) {
            references.squaredDistance(vector, it, norm)
        }
        distances.sort()
        val take = minOf(7, distances.size)
        var total = 0.0
        for (index in 0 until take) total += distances[index]
        return total / take
    }

    private fun centered(values: DoubleArray, bank: FeatureBank): DoubleArray =
        DoubleArray(values.size) { (values[it] - bank.mean[it]) / bank.scale[it] }

    /**
     * Removes the nuisance subspace using one-shot coefficients, exactly as upstream:
     * every coefficient comes from the *original* vector, not from a running residual.
     */
    private fun subtract(values: DoubleArray, basis: Array<DoubleArray>): DoubleArray {
        val coefficients = DoubleArray(basis.size) { dot(values, basis[it]) }
        return DoubleArray(values.size) { i ->
            var total = values[i]
            for (j in basis.indices) total -= coefficients[j] * basis[j][i]
            total
        }
    }

    internal fun dot(left: DoubleArray, right: DoubleArray): Double {
        var total = 0.0
        for (index in left.indices) total += left[index] * right[index]
        return total
    }

    private fun mean(values: DoubleArray): Double {
        var total = 0.0
        for (value in values) total += value
        return total / values.size
    }

    private fun mean(values: List<Double>): Double {
        var total = 0.0
        for (value in values) total += value
        return total / values.size
    }

    private fun unit(values: DoubleArray): DoubleArray {
        var norm = sqrt(dot(values, values))
        if (norm < 1e-12) norm = 1e-12
        return DoubleArray(values.size) { values[it] / norm }
    }

    private fun z(values: DoubleArray): DoubleArray {
        if (values.isEmpty()) return values
        val mean = mean(values)
        var variance = 0.0
        for (value in values) variance += (value - mean) * (value - mean)
        var deviation = sqrt(variance / values.size)
        if (deviation < 1e-12) deviation = 1e-12
        return DoubleArray(values.size) { (values[it] - mean) / deviation }
    }

    private fun columnMean(rows: List<DoubleArray>): DoubleArray {
        val columns = rows[0].size
        return DoubleArray(columns) { column ->
            var total = 0.0
            for (row in rows) total += row[column]
            total / rows.size
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    /** Numerically safe form, as upstream; `exp` alone overflows for large negative logits. */
    internal fun sigmoid(score: Double): Double = if (score >= 0) {
        1.0 / (1.0 + exp(-score))
    } else {
        exp(score) / (1.0 + exp(score))
    }
}
