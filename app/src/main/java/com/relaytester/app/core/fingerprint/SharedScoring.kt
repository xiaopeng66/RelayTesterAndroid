package com.relaytester.app.core.fingerprint

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Direct port of upstream lm-detector's `shared/shared-detector.ts`, schema
 * `shared-detector-v2`.
 *
 * Upstream scores the answers through one ranker and calibrates the ranking:
 *
 *   frequency  = z( 0.5 · z(mean of per-answer LDA projections over the candidate models)
 *                 + 0.25 · z(median over answers of the negative kNN distance)
 *                 + 0.25 · z(mean of the centroid baseline) )
 *   positional = z(mean over answers of z(per-answer positional decision))
 *   ranking    = blend.frequency · frequency + blend.positional · positional
 *
 * and, with exactly three usable answers, the calibrated probability is a softmax over
 * that ranking with a single fitted temperature [tau] — which is why the two halves are
 * z-scored before the blend: `tau` was fitted on the blended signal's scale, so feeding
 * it an unnormalised score would rescale every probability.
 *
 * The positional half is the v2 addition. It is built from the answer text in
 * `shared/positional.ts` ([NumberFeatures.positionKernel] and
 * [NumberFeatures.sequenceFeatures]) and reads the answer's *order* and its transitions,
 * which the frequency features cannot see. `blend` is 0.5/0.5 upstream, but the weights
 * come out of the package rather than being hardcoded here.
 *
 * v1 packages stay supported: a package in the older format carries no positional head
 * and no blend, and its stored numbers were fitted for the v1 ranking, so it is scored
 * with the v1 formula (the frequency composite *is* the ranking — no final z, no
 * positional term). That is not a compatibility shim bolted on: it is what that package
 * means. Which path runs is decided by the package's own format, never by a guess.
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
 * tests compare the full per-model score vector against vectors upstream itself
 * produced (one file per package format, each generated from its own upstream revision).
 *
 * Deliberate deviation: upstream gates the temperature softmax on hashes that bind the
 * artifact to the bank it was fitted with. The package producer must establish those
 * model/calibration bindings before packing; parsing dimensions or comparing the two
 * reference digests does not prove provenance. This scorer checks tau and its arithmetic.
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

/**
 * The positional head: per-block standardisation, one weight row per model, and a bias.
 *
 * Only present in v2 packages; see [DetectorWeights.positional].
 */
internal class PositionalHead(
    val params: Array<FeatureParams>,
    val weights: Array<DoubleArray>,
    val bias: DoubleArray,
)

/** How the v2 ranking fuses its two halves; upstream fits 0.5/0.5. */
internal class RankerBlend(val frequency: Double, val positional: Double)

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
    /** Null for a v1 package: the ranker then has a frequency half only. */
    val positional: PositionalHead? = null,
    val blend: RankerBlend? = null,
    /** The upstream schema the package was built from; empty for packages that predate it. */
    val detectorSchema: String = "",
)

/** Ranker output: the fused ranking plus the per-answer feature blocks it was built from. */
internal class Ranked(
    val ranking: DoubleArray,
    val blocks: List<Array<DoubleArray>>,
    val transformed: List<DoubleArray>,
)

internal object SharedScoring {

    /**
     * Upstream's fixed weights over the positional head's four blocks: frequency,
     * ordered-block, position kernel, sequence.
     */
    private val POSITIONAL_BLOCK_WEIGHTS = doubleArrayOf(0.6, 0.15, 0.125, 0.125)

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

        val composite = DoubleArray(fused.size) {
            0.5 * fused[it] + 0.25 * near[it] + 0.25 * base[it]
        }
        val head = weights.positional
        val blend = weights.blend
        val ranking = if (head == null || blend == null) {
            // A v1 package: the composite is the ranking, as upstream's v1 code had it.
            composite
        } else {
            val frequency = z(composite)
            val positional = z(columnMean(numbers.map { z(positionalDecision(it, head)) }))
            DoubleArray(fused.size) {
                blend.frequency * frequency[it] + blend.positional * positional[it]
            }
        }
        require(ranking.all { it.isFinite() }) { "排名计算产生无效数值，请刷新后重试" }
        return Ranked(ranking, full, transformed)
    }

    /**
     * Upstream's `positionalDecision`: one decision value per model for one answer.
     *
     * The four blocks are standardised by the package's own parameters, unit-normalised,
     * scaled by a fixed per-block weight, and then projected onto each model's row. This
     * is the only place the positional head touches the answer text, and it runs once per
     * answer rather than once per model.
     */
    fun positionalDecision(numbers: IntArray, head: PositionalHead): DoubleArray {
        val blocks = arrayOf(
            NumberFeatures.hellingerFeature(NumberFeatures.countNumbers(numbers)),
            NumberFeatures.orderedBlockFeature(numbers),
            NumberFeatures.positionKernel(numbers),
            NumberFeatures.sequenceFeatures(numbers),
        )
        var size = 0
        for (block in blocks) size += block.size
        val x = DoubleArray(size)
        var cursor = 0
        for (j in blocks.indices) {
            val block = blocks[j]
            val mean = head.params[j].mean
            val scale = head.params[j].scale
            val scaled = DoubleArray(block.size) { (block[it] - mean[it]) / scale[it] }
            val unit = unit(scaled)
            val weight = sqrt(POSITIONAL_BLOCK_WEIGHTS[j])
            for (value in unit) x[cursor++] = value * weight
        }
        return DoubleArray(head.weights.size) { dot(head.weights[it], x) + head.bias[it] }
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
        // Finite ranking/blend values can still overflow when multiplied by tau.
        // Keep the ordering, but never label a NaN softmax as calibrated.
        if (logits.isEmpty() || logits.any { !it.isFinite() }) return null
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
