package com.relaytester.app.core.fingerprint

/**
 * Parses the packed detection package and scores a round of answers with it.
 *
 * The package is 2–4 MB and parses in a few tens of milliseconds, so it is parsed once
 * per process rather than per detection. Which copy gets parsed — the installed one or
 * nothing — is [FingerprintBankStore]'s decision, not this class's.
 *
 * Scoring is a port of upstream's `shared-detector-v1`: see [SharedScoring] for the
 * algorithm and its two deviations, both of which only drop upstream's artifact-hash
 * binding (our package *is* that binding).
 */
class FingerprintBank private constructor(
    val modelIds: List<String>,
    val displayNames: List<String>,
    val families: List<String>,
    val familyNames: List<String>,
    /** Reference build stamp the package was compiled from. */
    val referenceBuiltAt: String,
    val recommendedAnswers: Int,
    val minimumValidNumbers: Int,
    private val weights: DetectorWeights,
) {
    val modelCount: Int get() = modelIds.size

    /**
     * Scores one round of answers and returns candidates ordered by ranking score.
     *
     * [expectedCounts] is the requested integer count per answer; an answer is only used
     * when it carries at least max(80, 55% of the request), matching upstream.
     *
     * Three usable answers take the full path: ranking plus a calibrated probability. One
     * or two take the partial path, which returns a ranking and no probability — upstream
     * refuses to put a closed-set percentage on a sample the calibration was never fitted
     * for.
     */
    fun analyze(answerTexts: List<String>, expectedCounts: List<Int>): FingerprintAnalysis {
        require(answerTexts.isNotEmpty()) { "至少需要一条回答" }

        val diagnostics = answerTexts.mapIndexed { index, text ->
            AnswerDiagnostic(
                index = index,
                parsedNumbers = NumberFeatures.parseNumbers(text).size,
                minimumNumbers = minimumNumbersFor(expectedCounts.getOrElse(index) { 0 }),
            )
        }
        val usable = answerTexts.filterIndexed { index, _ -> diagnostics[index].accepted }
            .map(NumberFeatures::parseNumbers)
        require(usable.isNotEmpty()) {
            "没有可用回答：拒答或严重截断的回答不计入"
        }

        val complete = usable.size == 3 && answerTexts.size == 3
        val ranking = SharedScoring.rank(usable, weights).ranking
        val probabilities = if (complete) SharedScoring.calibrate(ranking, weights.tau) else null
        val order = ranking.indices.sortedByDescending { ranking[it] }

        val candidates = order.map { index ->
            FingerprintCandidate(
                modelId = modelIds[index],
                displayName = displayNames[index],
                family = families[index],
                familyName = familyNames[index],
                rankingScore = ranking[index],
                probability = probabilities?.get(index),
            )
        }

        val winner = order.first()
        return FingerprintAnalysis(
            candidates = candidates,
            // Upstream reports the winning family but no family probability on this path.
            familyName = familyNames[winner],
            familyProbability = null,
            usableAnswers = usable.size,
            submittedAnswers = answerTexts.size,
            diagnostics = diagnostics,
            referenceBuiltAt = referenceBuiltAt,
            answerCount = usable.size,
            scoring = if (complete) FingerprintScoring.FULL else FingerprintScoring.PARTIAL,
            probabilityStatus = if (probabilities != null) {
                ProbabilityStatus.REFERENCE_CALIBRATED
            } else {
                ProbabilityStatus.UNAVAILABLE
            },
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

    companion object {
        /** Where an installed package lives under the app's private files directory. */
        const val INSTALLED_FILE_NAME = "lm-fingerprint/lite-bank.bin"

        /** Upstream accepts an answer at 55% of the requested integer count. */
        const val VALIDITY_RATIO = 0.55

        private const val MAGIC = "LMFPA003"

        /**
         * The format published before upstream dropped its verifier scorer.
         *
         * A device that has not re-downloaded its package still holds one of these, and
         * because the package is sequential the block has to be stepped over exactly (see
         * [skipVerifier]). Nothing is scored from it: the verifier only ever chose the
         * (now removed) "ranking and verification agree" caveat.
         */
        private const val LEGACY_MAGIC = "LMFPA002"

        /** Width of the ordered-block half of the feature vector upstream's detector emits. */
        private const val ORDERED_DIMENSION = 74
        private const val FEATURE_DIMENSION = NumberFeatures.DIMENSION + ORDERED_DIMENSION
        /**
         * Parses packed package bytes.
         *
         * Every field is bounds-checked against the file itself, the reader has to consume
         * it exactly, and the per-model arrays have to agree on one model count. Bytes
         * that are truncated, padded, or built from two different upstream revisions fail
         * here instead of scoring nonsense later.
         */
        fun fromPackageBytes(bytes: ByteArray): FingerprintBank {
            val reader = BankReader(bytes)
            val magic = reader.magic()
            if (magic != MAGIC && magic != LEGACY_MAGIC) throw IllegalArgumentException("指纹检测包格式不匹配")
            reader.expectMagic(magic)
            val legacyVerifier = magic == LEGACY_MAGIC
            reader.string() // source reference digest, informational
            val builtAt = reader.string()
            reader.string() // reference digest

            val modelCount = reader.u32()
            // Four string lists are pre-sized from this count before anything is read,
            // so it has to be plausible for the file that declared it.
            reader.requireCapacity(modelCount, 32)
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

            val tau = reader.float64()
            val recommended = reader.float64().toInt()
            val minimumValid = reader.float64().toInt()

            val headParams = readParams(reader, "LDA")
            val ldaRows = reader.u32()
            val ldaColumns = reader.u32()
            val ldaWeights = reader.matrix(ldaRows, ldaColumns)
            val ldaBias = reader.floats(reader.u32())
            val fullParams = readParams(reader, "全量特征")

            val hellinger = readFeatureBank(reader)
            val ordered = readFeatureBank(reader)
            val environmentCount = reader.u32()
            reader.requireCapacity(environmentCount, 8)
            val environments = if (environmentCount > 0) {
                val models = reader.u32()
                val columns = reader.u32()
                Array(environmentCount) { reader.matrix(models, columns) }
            } else {
                emptyArray()
            }

            val references = reader.quantizedReferences()

            if (legacyVerifier) skipVerifier(reader)

            // Every count above came out of the file, so a file that stops early or
            // carries trailing junk is rejected here instead of scoring nonsense later.
            require(reader.fullyRead) { "指纹检测包长度与内容不一致" }

            val weights = DetectorWeights(
                headParams = headParams,
                fullParams = fullParams,
                ldaWeights = ldaWeights,
                ldaBias = ldaBias,
                hellinger = hellinger,
                ordered = ordered,
                environments = environments,
                references = references,
                tau = tau,
            )
            validate(ids, weights, modelCount)

            return FingerprintBank(
                modelIds = ids,
                displayNames = displays,
                families = families,
                familyNames = familyLabels,
                referenceBuiltAt = builtAt,
                recommendedAnswers = recommended,
                minimumValidNumbers = minimumValid,
                weights = weights,
            )
        }

        /**
         * Steps over the verifier block of a legacy package, reading nothing into memory.
         *
         * The field order is the one the old reader consumed, field for field; the arrays
         * are skipped rather than built because nothing downstream uses them any more.
         * Every skip is capacity-checked, so a truncated legacy package still fails here
         * instead of being accepted with a misaligned tail.
         */
        private fun skipVerifier(reader: BankReader) {
            val blocks = reader.u32()
            reader.requireCapacity(blocks, 16)
            repeat(blocks) {
                val size = reader.u32()
                reader.skip(size, 4) // mean
                reader.skip(size, 4) // scale
            }
            reader.skip(1, 8) // unit scale
            reader.skipFloats() // origin
            reader.skipMatrix() // projection basis
            reader.skipFloats() // mu
            skipGaussian(reader) // new_joint

            val candidates = reader.u32()
            reader.requireCapacity(candidates, 16)
            repeat(candidates) {
                reader.skipFloats() // per-candidate mean
                repeat(4) { skipGaussian(reader) } // same/alternative, joint/single
            }
            reader.skipQuantizedReferences() // the verifier's own kNN references

            val active = reader.u32()
            reader.u32() // the same count again; the old builder wrote it twice
            reader.skip(active, 4)
            // One length prefix covers all three head vectors, as the old writer emitted it.
            val head = reader.u32()
            reader.skip(head, 4) // mean
            reader.skip(head, 4) // scale
            reader.skip(head, 4) // weights
            reader.skip(1, 8) // bias
        }

        /** One `constant - ½·δᵀ P δ` form: a squared matrix and a float64 constant. */
        private fun skipGaussian(reader: BankReader) {
            reader.skipMatrix()
            reader.skip(1, 8)
        }

        /**
         * The integrity gate that upstream expresses as hash bindings.
         *
         * Upstream refuses to use a detector whose recorded base/verifier digests do not
         * match the artifact it was fitted with. That cannot happen to a single packed
         * package, so the equivalent check is structural: every per-model array must have
         * exactly the declared number of models, and the verifier's matrices must line up
         * with the 429-dimensional feature vector the ranker produces. A package
         * assembled from two upstream revisions breaks one of these.
         */
        private fun validate(ids: List<String>, weights: DetectorWeights, modelCount: Int) {
            // A package with no models satisfies every dimension check below (empty arrays
            // satisfy all of them) and then ranks nothing: the round would end on
            // `order.first()` with an internal "List is empty." in the panel.
            require(modelCount > 0) { "指纹检测包没有模型" }
            require(weights.headParams.size == 2 && weights.fullParams.size == 2) {
                "指纹检测包的特征标准化块数量不正确"
            }
            require(weights.headParams[0].mean.size == NumberFeatures.DIMENSION) {
                "指纹检测包的 Hellinger 维度不是 ${NumberFeatures.DIMENSION}"
            }
            require(weights.headParams[1].mean.size == ORDERED_DIMENSION) {
                "指纹检测包的头部有序分块维度不是 $ORDERED_DIMENSION"
            }
            require(weights.fullParams[0].mean.size == NumberFeatures.DIMENSION) {
                "指纹检测包的完整 Hellinger 维度不是 ${NumberFeatures.DIMENSION}"
            }
            require(weights.fullParams[1].mean.size == ORDERED_DIMENSION) {
                "指纹检测包的有序分块维度不是 $ORDERED_DIMENSION"
            }
            require(weights.ldaWeights.size == modelCount && weights.ldaBias.size == modelCount) {
                "指纹检测包的 LDA 权重与模型数量不一致"
            }
            require(weights.ldaWeights.all { it.size == FEATURE_DIMENSION }) {
                "指纹检测包的 LDA 权重维度不是 $FEATURE_DIMENSION"
            }
            // The two feature banks are indexed by the transformed blocks at scoring time
            // (355 Hellinger / 74 ordered). A bank whose own width disagrees installs
            // fine and then either throws mid-round or silently drops coordinates.
            requireFeatureBank(weights.hellinger, NumberFeatures.DIMENSION, modelCount, "Hellinger")
            requireFeatureBank(weights.ordered, ORDERED_DIMENSION, modelCount, "有序分块")
            require(weights.environments.all { it.size == modelCount }) {
                "指纹检测包的环境模板与模型数量不一致"
            }
            require(weights.environments.all { environment -> environment.all { it.size == ORDERED_DIMENSION } }) {
                "指纹检测包的环境模板维度不是 $ORDERED_DIMENSION"
            }
            require(weights.references.size == modelCount) {
                "指纹检测包的 kNN 参考与模型数量不一致"
            }
            require(weights.references.all { it.columns == FEATURE_DIMENSION }) {
                "指纹检测包的 kNN 参考维度不是 $FEATURE_DIMENSION"
            }
            require(ids.distinct().size == modelCount) { "指纹检测包的模型 id 有重复" }
        }

        private fun readParams(reader: BankReader, what: String): Array<FeatureParams> {
            val blocks = reader.u32()
            reader.requireCapacity(blocks, 16)
            return Array(blocks) {
                val size = reader.u32()
                FeatureParams(reader.floats(size), reader.floats(size))
            }
        }

        private fun readFeatureBank(reader: BankReader): FeatureBank {
            val size = reader.u32()
            val mean = reader.floats(size)
            val scale = reader.floats(size)
            val basisRows = reader.u32()
            val basis = if (basisRows > 0) {
                val columns = reader.u32()
                reader.matrix(basisRows, columns)
            } else {
                emptyArray()
            }
            val centroidCount = reader.u32()
            val centroids = reader.matrix(centroidCount, size)
            return FeatureBank(mean, scale, basis, centroids)
        }

        /**
         * A feature bank whose own width disagrees with the vector it is applied to is
         * the one inconsistency a single packed package can still carry: the reader sizes
         * `mean`/`scale`/`centroids` from the file, so a package assembled for another
         * feature width installs cleanly and then throws (or silently drops coordinates)
         * at scoring time. Every array in a bank shares one width, so checking `mean` is
         * enough to pin the rest.
         */
        private fun requireFeatureBank(
            bank: FeatureBank,
            dimension: Int,
            modelCount: Int,
            what: String,
        ) {
            require(bank.mean.size == dimension && bank.scale.size == dimension) {
                "指纹检测包的$what 标准化维度不是 $dimension"
            }
            require(bank.centroids.size == modelCount) {
                "指纹检测包的$what 质心与模型数量不一致"
            }
            require(bank.centroids.all { it.size == dimension }) {
                "指纹检测包的$what 质心维度不是 $dimension"
            }
            require(bank.nuisanceBasis.all { it.size == dimension }) {
                "指纹检测包的$what 干扰基维度不是 $dimension"
            }
        }
    }
}
