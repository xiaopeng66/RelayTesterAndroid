package com.relaytester.app.core.fingerprint

import android.content.Context
import java.io.IOException

/**
 * Loads the packed reference bank from assets and keeps it in memory.
 *
 * The asset is ~400 KB and parses in a few milliseconds, so it is cached for the
 * process lifetime on first use rather than re-read per detection. Loading is
 * triggered lazily: the app's own startup never touches this file.
 */
class FingerprintBank private constructor(
    val modelIds: List<String>,
    val displayNames: List<String>,
    val families: List<String>,
    val familyNames: List<String>,
    val referenceBuiltAt: String,
    val recommendedAnswers: Int,
    val minimumValidNumbers: Int,
    private val headMeans: Array<FloatArray>,
    private val headScales: Array<FloatArray>,
    private val ldaWeights: Array<FloatArray>,
    private val ldaBias: FloatArray,
    private val hellingerMean: FloatArray,
    private val hellingerScale: FloatArray,
    private val hellingerNuisance: Array<FloatArray>,
    private val hellingerCentroids: Array<FloatArray>,
    private val orderedWeight: Double,
    private val orderedMean: FloatArray,
    private val orderedScale: FloatArray,
    private val orderedNuisance: Array<FloatArray>,
    private val orderedCentroids: Array<FloatArray>,
    private val environmentCentroids: Array<Array<FloatArray>>,
    private val betas: DoubleArray,
    val calibrationAccuracy: DoubleArray,
) {
    val modelCount: Int get() = modelIds.size

    /**
     * Scores one round of answers and returns candidates ordered by ranking score.
     *
     * [expectedCounts] is the requested integer count per answer; an answer is only
     * used when it carries at least max(80, 55% of the request), matching upstream.
     *
     * Mirrors `rankSharedNumbers` with the kNN term removed. The kNN references are
     * 17.4 MB of the 19 MB ranker, and held-out testing found them to be a net
     * negative on this bank (dropping them scored 49/53 against 48/53 with them),
     * so the remaining two terms carry the ranking.
     */
    fun analyze(answerTexts: List<String>, expectedCounts: List<Int>): FingerprintAnalysis {
        require(answerTexts.isNotEmpty()) { "至少需要一条回答" }

        val parsed = answerTexts.map { NumberFeatures.parseNumbers(it) }
        val diagnostics = parsed.mapIndexed { index, numbers ->
            val expected = expectedCounts.getOrElse(index) { 0 }
            AnswerDiagnostic(
                index = index,
                parsedNumbers = numbers.size,
                minimumNumbers = minimumNumbersFor(expected),
            )
        }
        val usable = parsed.filterIndexed { index, _ -> diagnostics[index].accepted }
        require(usable.isNotEmpty()) { "没有可用回答：请粘贴完整数字序列；拒答或严重截断的回答不会计入" }

        val scores = usable.map(::scoreAnswer)
        val combined = DoubleArray(modelCount) { model ->
            var total = 0.0
            for (score in scores) total += score[model]
            total / scores.size
        }

        val normalized = zScore(combined)
        val probability = softmax(normalized, betas[usable.size.coerceIn(1, 3) - 1])
        val order = combined.indices.sortedByDescending { combined[it] }

        val candidates = order.map { index ->
            FingerprintCandidate(
                modelId = modelIds[index],
                displayName = displayNames[index],
                family = families[index],
                familyName = familyNames[index],
                rankingScore = combined[index],
                probability = probability?.get(index),
            )
        }

        val familyTotals = LinkedHashMap<String, Double>()
        val familyLabels = LinkedHashMap<String, String>()
        for (index in modelIds.indices) {
            val family = families[index]
            familyLabels[family] = familyNames[index]
            familyTotals[family] = (familyTotals[family] ?: 0.0) + (probability?.get(index) ?: 0.0)
        }
        val winningFamily = familyTotals.maxByOrNull { it.value }?.key

        return FingerprintAnalysis(
            candidates = candidates,
            familyName = familyLabels[winningFamily] ?: "",
            familyProbability = probability?.let { familyTotals[winningFamily] },
            usableAnswers = usable.size,
            submittedAnswers = answerTexts.size,
            diagnostics = diagnostics,
            referenceBuiltAt = referenceBuiltAt,
            answerCount = usable.size,
        )
    }

    /**
     * Smallest integer count an answer must carry to be scored.
     *
     * Public because the panel shows the same threshold while the user is still
     * pasting; deriving it in two places would let the preview and the scorer
     * disagree about whether an answer was used.
     */
    fun minimumNumbersFor(expectedCount: Int): Int =
        com.relaytester.app.core.fingerprint.minimumNumbersFor(expectedCount, minimumValidNumbers)

    /** One answer's fused nuisance-Hellinger + ordered-block score per model. */
    private fun scoreAnswer(numbers: IntArray): DoubleArray {
        val counts = NumberFeatures.countNumbers(numbers)
        val hellinger = NumberFeatures.hellingerFeature(counts)
        val ordered = NumberFeatures.orderedBlockFeature(numbers)

        val hellingerUnit = unit(
            subtractBasis(
                zScoreAgainst(hellinger, hellingerMean, hellingerScale),
                hellingerNuisance,
            ),
        )
        val marginal = DoubleArray(modelCount) { dot(hellingerUnit, hellingerCentroids[it]) }

        val orderedZ = zScoreAgainst(ordered, orderedMean, orderedScale)
        val orderedRawUnit = unit(orderedZ)
        val orderedProjectedUnit = unit(subtractBasis(orderedZ, orderedNuisance))

        val templates = DoubleArray(modelCount) { model ->
            var best = Double.NEGATIVE_INFINITY
            for (environment in environmentCentroids) {
                best = maxOf(best, dot(orderedRawUnit, environment[model]))
            }
            best
        }
        val nuisance = DoubleArray(modelCount) { dot(orderedProjectedUnit, orderedCentroids[it]) }

        val templateZ = zScore(templates)
        val nuisanceZ = zScore(nuisance)
        val orderedScore = zScore(DoubleArray(modelCount) { 0.5 * templateZ[it] + 0.5 * nuisanceZ[it] })
        val marginalZ = zScore(marginal)

        return DoubleArray(modelCount) { model ->
            val base = (1 - orderedWeight) * marginalZ[model] + orderedWeight * orderedScore[model]
            val lda = zScore(ldaScore(numbers))[model]
            // The upstream weighting is 0.5 LDA + 0.25 kNN + 0.25 centroid; with the
            // kNN term removed its mass is folded into the centroid term so the two
            // surviving terms stay 50/50.
            0.5 * lda + 0.5 * base
        }
    }

    private fun ldaScore(numbers: IntArray): DoubleArray {
        val head = if (numbers.size > 128) numbers.copyOfRange(0, 128) else numbers
        val blocks = arrayOf(
            NumberFeatures.hellingerFeature(NumberFeatures.countNumbers(head)),
            NumberFeatures.orderedBlockFeature(head),
        )
        val transformed = ArrayList<Double>()
        for (block in blocks.indices) {
            val scaled = zScoreAgainst(blocks[block], headMeans[block], headScales[block])
            val unit = unit(scaled)
            val weight = if (block == 0) 0.75 else 0.25
            for (value in unit) transformed.add(value * kotlin.math.sqrt(weight))
        }
        val x = transformed.toDoubleArray()
        return DoubleArray(modelCount) { model ->
            var total = ldaBias[model].toDouble()
            val row = ldaWeights[model]
            for (index in row.indices) total += row[index] * x[index]
            total
        }
    }

    private fun softmax(scores: DoubleArray, beta: Double): DoubleArray {
        val scaled = DoubleArray(scores.size) { scores[it] * beta }
        val maximum = scaled.max()
        var total = 0.0
        val weights = DoubleArray(scaled.size) {
            val value = kotlin.math.exp(scaled[it] - maximum)
            total += value
            value
        }
        return DoubleArray(weights.size) { weights[it] / total }
    }

    private fun zScoreAgainst(values: DoubleArray, mean: FloatArray, scale: FloatArray): DoubleArray =
        DoubleArray(values.size) { (values[it] - mean[it]) / scale[it] }

    private fun zScore(values: DoubleArray): DoubleArray {
        if (values.isEmpty()) return values
        var total = 0.0
        for (value in values) total += value
        val mean = total / values.size
        var variance = 0.0
        for (value in values) variance += (value - mean) * (value - mean)
        val deviation = maxOf(kotlin.math.sqrt(variance / values.size), 1e-12)
        return DoubleArray(values.size) { (values[it] - mean) / deviation }
    }

    private fun unit(values: DoubleArray): DoubleArray {
        var total = 0.0
        for (value in values) total += value * value
        var norm = kotlin.math.sqrt(total)
        if (norm < 1e-12) norm = 1e-12
        return DoubleArray(values.size) { values[it] / norm }
    }

    private fun unit(values: FloatArray): DoubleArray = unit(DoubleArray(values.size) { values[it].toDouble() })

    private fun subtractBasis(values: DoubleArray, basis: Array<FloatArray>): DoubleArray {
        var out = values
        for (row in basis) {
            val projection = dot(out, row)
            out = DoubleArray(out.size) { out[it] - projection * row[it] }
        }
        return out
    }

    private fun dot(left: DoubleArray, right: FloatArray): Double {
        var total = 0.0
        for (index in left.indices) total += left[index] * right[index]
        return total
    }

    companion object {
        const val ASSET_PATH = "lm-fingerprint/lite-bank.bin"

        /** Upstream accepts an answer at 55% of the requested integer count. */
        const val VALIDITY_RATIO = 0.55

        private const val MAGIC = "LMFPA001"

        @Volatile
        private var cached: FingerprintBank? = null

        /** Loads from assets once per process. Safe to call from any thread. */
        fun load(context: Context): FingerprintBank = cached ?: synchronized(this) {
            cached ?: readAsset(context.applicationContext).also { cached = it }
        }

        /** Parses the packed asset. Exposed so tests can load the shipped bytes directly. */
        fun fromAssetBytes(bytes: ByteArray): FingerprintBank = parseInts(bytes)

        private fun readAsset(context: Context): FingerprintBank {
            val bytes = try {
                context.assets.open(ASSET_PATH).use { it.readBytes() }
            } catch (error: IOException) {
                throw IllegalStateException("无法读取指纹参考库资产", error)
            }
            return parseInts(bytes)
        }

        private fun parseInts(bytes: ByteArray): FingerprintBank {
            val reader = BankReader(bytes)
            reader.expectMagic(MAGIC)
            reader.string() // source reference digest, informational
            val builtAt = reader.string()
            reader.string() // reference digest

            val modelCount = reader.u32()
            val ids = ArrayList<String>(modelCount)
            val displays = ArrayList<String>(modelCount)
            val families = ArrayList<String>(modelCount)
            val familyLabels = ArrayList<String>(modelCount)
            repeat(modelCount) {
                ids.add(reader.string())
                displays.add(reader.string())
                families.add(reader.string())
                familyLabels.add(reader.string())
            }

            val headBlocks = reader.u32()
            val headMeans = Array(headBlocks) { FloatArray(0) }
            val headScales = Array(headBlocks) { FloatArray(0) }
            for (block in 0 until headBlocks) {
                val size = reader.u32()
                headMeans[block] = reader.floats(size)
                headScales[block] = reader.floats(size)
            }

            val ldaRows = reader.u32()
            val ldaColumns = reader.u32()
            val ldaWeights = reader.matrix(ldaRows, ldaColumns)
            val ldaBias = reader.floats(reader.u32())

            val hellingerSize = reader.u32()
            val hellingerMean = reader.floats(hellingerSize)
            val hellingerScale = reader.floats(hellingerSize)
            val hellingerBasisRows = reader.u32()
            val hellingerNuisance = if (hellingerBasisRows > 0) {
                val columns = reader.u32()
                reader.matrix(hellingerBasisRows, columns)
            } else {
                emptyArray()
            }
            val hellingerCentroidRows = reader.u32()
            val hellingerCentroids = reader.matrix(hellingerCentroidRows, hellingerSize)

            val orderedWeight = reader.float64()
            val orderedSize = reader.u32()
            val orderedMean = reader.floats(orderedSize)
            val orderedScale = reader.floats(orderedSize)
            val orderedBasisRows = reader.u32()
            val orderedNuisance = if (orderedBasisRows > 0) {
                val columns = reader.u32()
                reader.matrix(orderedBasisRows, columns)
            } else {
                emptyArray()
            }
            val orderedCentroidRows = reader.u32()
            val orderedCentroids = reader.matrix(orderedCentroidRows, orderedSize)
            val environmentCount = reader.u32()
            val environmentCentroids = if (environmentCount > 0) {
                val models = reader.u32()
                val columns = reader.u32()
                Array(environmentCount) { reader.matrix(models, columns) }
            } else {
                emptyArray()
            }

            val betas = DoubleArray(3) { reader.float64() }
            val accuracy = DoubleArray(3) { reader.float64() }
            val recommended = reader.float64().toInt()
            val minimumValid = reader.float64().toInt()

            return FingerprintBank(
                modelIds = ids,
                displayNames = displays,
                families = families,
                familyNames = familyLabels,
                referenceBuiltAt = builtAt,
                recommendedAnswers = recommended,
                minimumValidNumbers = minimumValid,
                headMeans = headMeans,
                headScales = headScales,
                ldaWeights = ldaWeights,
                ldaBias = ldaBias,
                hellingerMean = hellingerMean,
                hellingerScale = hellingerScale,
                hellingerNuisance = hellingerNuisance,
                hellingerCentroids = hellingerCentroids,
                orderedWeight = orderedWeight,
                orderedMean = orderedMean,
                orderedScale = orderedScale,
                orderedNuisance = orderedNuisance,
                orderedCentroids = orderedCentroids,
                environmentCentroids = environmentCentroids,
                betas = betas,
                calibrationAccuracy = accuracy,
            )
        }
    }
}
