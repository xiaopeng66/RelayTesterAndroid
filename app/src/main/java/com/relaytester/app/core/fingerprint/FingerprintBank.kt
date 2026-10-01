package com.relaytester.app.core.fingerprint

/**
 * Parses the packed detection package and scores a round of answers with it.
 *
 * The package is ~3.5 MB and parses in a few tens of milliseconds, so it is parsed once
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
     * Three usable answers take the full path: ranking, verifier logits and a calibrated
     * probability. One or two take the partial path, which returns a ranking and no
     * probability — upstream deliberately refuses to put a percentage on a sample that
     * the verifier was never fitted for.
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
            "没有可用回答：请粘贴完整数字序列；拒答或严重截断的回答不会计入"
        }

        val complete = usable.size == 3 && answerTexts.size == 3
        val ranking: DoubleArray
        val verification: DoubleArray?
        if (complete) {
            val verified = SharedScoring.score(usable, weights)
            ranking = verified.ranking
            verification = verified.scores
        } else {
            ranking = SharedScoring.rank(usable, weights).ranking
            verification = null
        }

        val calibration = verification?.let { SharedScoring.calibrate(ranking, it, weights.tau) }
        val order = ranking.indices.sortedByDescending { ranking[it] }

        val candidates = order.map { index ->
            FingerprintCandidate(
                modelId = modelIds[index],
                displayName = displayNames[index],
                family = families[index],
                familyName = familyNames[index],
                rankingScore = ranking[index],
                verificationScore = verification?.get(index),
                probability = calibration?.takeIf { it.calibrated }?.values?.get(index),
            )
        }

        val winner = order.first()
        val verificationTop = verification?.let { scores ->
            // First maximum, matching upstream's indexOf(max) so a tie keeps the lower index.
            scores.indices.maxByOrNull { scores[it] }
        }
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
            probabilityStatus = when {
                calibration == null -> ProbabilityStatus.UNAVAILABLE
                calibration.calibrated -> ProbabilityStatus.REFERENCE_CALIBRATED
                else -> ProbabilityStatus.UNAVAILABLE
            },
            verificationTopModelId = verificationTop?.let { modelIds[it] },
            verifierAgrees = verificationTop?.let { it == winner },
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

        private const val MAGIC = "LMFPA002"
        private const val FEATURE_DIMENSION = NumberFeatures.DIMENSION + 74

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
            reader.expectMagic(MAGIC)
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

            val verifierPreprocessing = readParams(reader, "核验器")
            val unitScale = reader.float64()
            val origin = reader.floats(reader.u32())
            val basisRows = reader.u32()
            val basisColumns = reader.u32()
            val basis = reader.matrix(basisRows, basisColumns)
            val mu = reader.floats(reader.u32())
            val newJoint = readGaussian(reader)

            val candidateCount = reader.u32()
            reader.requireCapacity(candidateCount, 16)
            val candidates = ArrayList<VerifierCandidate>(candidateCount)
            repeat(candidateCount) {
                val mean = reader.floats(reader.u32())
                candidates.add(
                    VerifierCandidate(
                        mean = mean,
                        sameJoint = readGaussian(reader),
                        alternativeJoint = readGaussian(reader),
                        sameSingle = readGaussian(reader),
                        alternativeSingle = readGaussian(reader),
                    ),
                )
            }
            val verifierReferences = reader.quantizedReferences()

            val activeCount = reader.u32()
            reader.u32() // the same count again; the builder writes it twice
            val active = reader.int32(activeCount)
            val headSize = reader.u32()
            val headMean = reader.floats(headSize)
            val headScale = reader.floats(headSize)
            val headWeights = reader.floats(headSize)
            val headBias = reader.float64()

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
                verifier = VerifierWeights(
                    preprocessing = verifierPreprocessing,
                    unitScale = unitScale,
                    origin = origin,
                    basis = basis,
                    mu = mu,
                    newJoint = newJoint,
                    candidates = candidates,
                    references = verifierReferences,
                    activeFeatures = active,
                    mean = headMean,
                    scale = headScale,
                    weights = headWeights,
                    bias = headBias,
                ),
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
            require(weights.headParams.size == 2 && weights.fullParams.size == 2) {
                "指纹检测包的特征标准化块数量不正确"
            }
            require(weights.headParams[0].mean.size == NumberFeatures.DIMENSION) {
                "指纹检测包的 Hellinger 维度不是 ${NumberFeatures.DIMENSION}"
            }
            require(weights.fullParams[1].mean.size == 74) {
                "指纹检测包的有序分块维度不是 74"
            }
            require(weights.ldaWeights.size == modelCount && weights.ldaBias.size == modelCount) {
                "指纹检测包的 LDA 权重与模型数量不一致"
            }
            require(weights.ldaWeights.all { it.size == FEATURE_DIMENSION }) {
                "指纹检测包的 LDA 权重维度不是 $FEATURE_DIMENSION"
            }
            require(weights.hellinger.centroids.size == modelCount) {
                "指纹检测包的 Hellinger 质心与模型数量不一致"
            }
            require(weights.ordered.centroids.size == modelCount) {
                "指纹检测包的有序分块质心与模型数量不一致"
            }
            require(weights.environments.all { it.size == modelCount }) {
                "指纹检测包的环境模板与模型数量不一致"
            }
            require(weights.references.size == modelCount) {
                "指纹检测包的 kNN 参考与模型数量不一致"
            }
            val verifier = weights.verifier
            require(verifier.preprocessing.size == 2) {
                "指纹检测包的核验器标准化块数量不正确"
            }
            require(verifier.basis.size == FEATURE_DIMENSION && verifier.origin.size == FEATURE_DIMENSION) {
                "指纹检测包的核验器投影维度不是 $FEATURE_DIMENSION"
            }
            require(verifier.basis.all { it.size == verifier.mu.size }) {
                "指纹检测包的核验器投影基与均值维度不一致"
            }
            require(verifier.candidates.size == modelCount && verifier.references.size == modelCount) {
                "指纹检测包的核验器与模型数量不一致"
            }
            require(verifier.activeFeatures.size == verifier.mean.size &&
                verifier.mean.size == verifier.scale.size &&
                verifier.scale.size == verifier.weights.size) {
                "指纹检测包的核验器损失函数长度不一致"
            }
            require(verifier.activeFeatures.all { it in 0 until VERIFIER_FEATURES }) {
                "指纹检测包的核验器特征下标越界"
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

        private fun readGaussian(reader: BankReader): Gaussian {
            val rows = reader.u32()
            val columns = reader.u32()
            return Gaussian(reader.matrix(rows, columns), reader.float64())
        }

        /** The number of features upstream's verifier logistic head consumes. */
        private const val VERIFIER_FEATURES = 6
    }
}
