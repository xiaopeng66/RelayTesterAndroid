package com.relaytester.app.feature.fingerprint

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.relaytester.app.core.fingerprint.AnswerDiagnostic
import com.relaytester.app.core.fingerprint.ChallengeGenerator
import com.relaytester.app.core.fingerprint.FingerprintAnalysis
import com.relaytester.app.core.fingerprint.FingerprintBank
import com.relaytester.app.core.fingerprint.FingerprintChallenge
import com.relaytester.app.core.model.ApiResult
import com.relaytester.app.core.model.ErrorKind
import com.relaytester.app.core.network.RelayApi
import com.relaytester.app.core.security.KeystoreSecretStore
import com.relaytester.app.core.security.SecretStore
import com.relaytester.app.core.storage.SupplierStore
import com.relaytester.app.core.storage.SupplierStoreState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lifecycle of a single challenge inside a detection round. */
enum class ChallengeState {
    PENDING,
    REQUESTING,
    RECEIVED,
    REJECTED,
}

@Immutable
data class ChallengeProgress(
    val challenge: FingerprintChallenge,
    val state: ChallengeState = ChallengeState.PENDING,
    val answer: String = "",
    val parsedNumbers: Int = 0,
    val error: String? = null,
)

/** Which answer source the panel is driving. */
enum class DetectionMode {
    API,
    MANUAL,
}

/** Lifecycle of one model inside a detection round. */
enum class ModelDetectionStatus {
    /** Queued; this model's round has not started yet. */
    PENDING,
    RUNNING,
    DONE,
    FAILED,
}

/**
 * One model's outcome within a round.
 *
 * A round keeps one of these per ticked model, so a model that fails neither hides
 * the models that already succeeded nor stops the ones still queued.
 */
@Immutable
data class ModelFingerprintResult(
    val model: String,
    val status: ModelDetectionStatus = ModelDetectionStatus.PENDING,
    val candidateName: String? = null,
    val candidateId: String? = null,
    val familyName: String? = null,
    val probability: Double? = null,
    val usableAnswers: Int = 0,
    val submittedAnswers: Int = 0,
    val error: String? = null,
)

@Immutable
data class FingerprintUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val referenceBuiltAt: String = "",
    val modelCount: Int = 0,
    val suppliers: List<SupplierOption> = emptyList(),
    val selectedSupplierId: String? = null,
    /** Filters the supplier's model list; it is no longer a free-text model name. */
    val modelFilter: String = "",
    /** Ticked models, in the order they were ticked. */
    val selectedModels: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    val mode: DetectionMode = DetectionMode.API,
    val useParallel: Boolean = true,
    val isRunning: Boolean = false,
    val progress: List<ChallengeProgress> = emptyList(),
    /** Floor from the reference bank; the panel previews pastes against it. */
    val minimumValidNumbers: Int = 80,
    val analysis: FingerprintAnalysis? = null,
    /** One row per ticked model; this is the panel's output when several are ticked. */
    val batchResults: List<ModelFingerprintResult> = emptyList(),
    /** The model whose challenges are on screen right now, if a round is running. */
    val activeModel: String? = null,
    val message: String? = null,
    val isMessageError: Boolean = false,
) {
    /** True when the pending round covers several models, so its output reads as a list. */
    val isBatch: Boolean get() = selectedModels.size > 1
}

@Immutable
data class SupplierOption(
    val id: String,
    val name: String,
)

/**
 * Drives model-fingerprint detection.
 *
 * Kept separate from TesterViewModel: a round is three long requests with its own
 * progress model, its own retry unit (a single challenge), and it never touches the
 * batch test result list. Sharing that state machine would couple two unrelated
 * lifecycles in an already large view model.
 */
class FingerprintViewModel(
    private val supplierStore: SupplierStore,
    private val secretStore: SecretStore,
    private val relayApiFactory: () -> RelayApi,
    private val bankFactory: () -> FingerprintBank,
    private val skipRestore: Boolean = false,
    /** Overridden by tests so a round can be observed without racing real threads. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val relayApi: RelayApi by lazy { relayApiFactory() }
    private val bank: FingerprintBank by lazy { bankFactory() }

    private val _uiState = MutableStateFlow(FingerprintUiState())
    val uiState = _uiState

    private var runJob: Job? = null

    /** The catalogue refresh, tracked so tests can join it deterministically. */
    private var refreshJob: Job? = null

    /** The startup read, tracked so a test can tell a parked load from a finished one. */
    private var loadJob: Job? = null

    /**
     * Suspends until anything the view model started has settled.
     *
     * Tests drive a round through a dispatcher they control and need a
     * deterministic join; polling the UI state would be racy.
     */
    internal suspend fun awaitIdle() {
        loadJob?.join()
        refreshJob?.join()
        runJob?.join()
    }

    /**
     * Cached alongside UI state so synchronous callbacks (chip taps) never need to
     * re-read DataStore. Refreshed whenever the store is read.
     */
    private var knownSuppliers: List<com.relaytester.app.core.model.SupplierProfile> = emptyList()

    /** Supplier/model pair requested before the store finished loading. */
    private var pendingPrefill: Pair<String?, String>? = null

    init {
        if (skipRestore) {
            _uiState.update { it.copy(isLoading = false) }
        } else {
            loadJob = viewModelScope.launch { load() }
        }
    }

    private suspend fun load() {
        val loaded = withContext(ioDispatcher) {
            runCatching {
                val bank = bank
                val state = supplierStore.read()
                bank to state
            }
        }
        loaded.onSuccess { (bank, storeState) ->
            knownSuppliers = storeState.suppliers
            // A prefill that arrived while the store was still loading must survive it.
            val supplierId = pendingPrefill?.first
                ?: storeState.activeSupplierId
                ?: storeState.suppliers.firstOrNull()?.id
            val supplier = storeState.suppliers.firstOrNull { it.id == supplierId }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    loadError = null,
                    referenceBuiltAt = bank.referenceBuiltAt,
                    modelCount = bank.modelCount,
                    minimumValidNumbers = bank.minimumValidNumbers,
                    suppliers = storeState.suppliers.map { item ->
                        SupplierOption(item.id, item.name)
                    },
                    selectedSupplierId = supplierId,
                    models = supplier?.models.orEmpty(),
                    selectedModels = pendingPrefill?.second?.let { listOf(it) } ?: it.selectedModels,
                    // A prefill came with a model the user must be able to see and
                    // re-tick; a filter left over from earlier typing can hide it.
                    modelFilter = if (pendingPrefill != null) "" else it.modelFilter,
                )
            }
            pendingPrefill = null
            refreshProgress()
        }.onFailure { error ->
            _uiState.update {
                it.copy(isLoading = false, loadError = error.message ?: "参考库加载失败")
            }
        }
    }

    /**
     * Prepares the panel for one supplier/model pair and switches to API mode.
     *
     * Called when the user jumps here from a test result. It may run before the
     * initial store read finishes, so the request is parked in [pendingPrefill]
     * rather than dropped.
     */
    fun prefill(supplierId: String?, model: String) {
        if (supplierId == null) {
            _uiState.update {
                it.copy(
                    mode = DetectionMode.API,
                    selectedModels = listOf(model),
                    // The prefilled model is on screen from this moment, so any filter
                    // that would hide it has to go with the old selection.
                    modelFilter = "",
                    analysis = null,
                    batchResults = emptyList(),
                )
            }
            pendingPrefill = Pair(null, model)
            return
        }
        val supplier = knownSuppliers.firstOrNull { it.id == supplierId }
        if (supplier == null && _uiState.value.isLoading) {
            pendingPrefill = Pair(supplierId, model)
            _uiState.update { it.copy(mode = DetectionMode.API, analysis = null) }
            return
        }
        _uiState.update { state ->
            state.copy(
                mode = DetectionMode.API,
                analysis = null,
                batchResults = emptyList(),
                selectedSupplierId = supplierId,
                models = supplier?.models.orEmpty(),
                selectedModels = listOf(model),
                modelFilter = "",
            )
        }
        refreshProgress()
    }

    fun selectMode(mode: DetectionMode) {
        if (_uiState.value.isRunning) return
        // The challenge set is re-rolled for the new mode, so old per-model verdicts
        // can no longer describe what is on screen.
        _uiState.update { it.copy(mode = mode, analysis = null, batchResults = emptyList()) }
        refreshProgress()
    }

    fun selectSupplier(supplierId: String) {
        if (_uiState.value.isRunning) return
        val supplier = knownSuppliers.firstOrNull { it.id == supplierId }
        _uiState.update {
            it.copy(
                selectedSupplierId = supplierId,
                models = supplier?.models.orEmpty(),
                // The ticked models belong to the previous supplier's catalogue.
                selectedModels = emptyList(),
                modelFilter = "",
                analysis = null,
                batchResults = emptyList(),
            )
        }
    }

    /**
     * Re-reads the reference bank, the suppliers and the current one's model catalogue.
     *
     * This view model is built with the activity, so the catalogue it read at startup
     * is often older than the one the "模型测试" tab has since pulled; without this the
     * picker would show an empty list until the app was restarted. It is also the only
     * way back from a startup read that failed: nothing else ever clears [loadError].
     */
    fun refreshCatalogue() {
        // A read already in flight observes the same store and its result is the newer
        // one; letting this one write too would risk the older snapshot landing last.
        val current = _uiState.value
        if (current.isRunning || current.isLoading) return
        refreshJob = viewModelScope.launch {
            val read = withContext(ioDispatcher) {
                runCatching {
                    val bank = bank
                    bank to supplierStore.read()
                }
            }
            read.onSuccess { (bank, storeState) ->
                knownSuppliers = storeState.suppliers
                _uiState.update { state ->
                    val supplierId = candidateSupplierId(state.selectedSupplierId, storeState)
                    val changed = supplierId != state.selectedSupplierId
                    state.copy(
                        // A retry that succeeds has to lift the error screen, or a
                        // transient startup failure would strand the panel for good.
                        isLoading = false,
                        loadError = null,
                        referenceBuiltAt = bank.referenceBuiltAt,
                        modelCount = bank.modelCount,
                        minimumValidNumbers = bank.minimumValidNumbers,
                        suppliers = storeState.suppliers.map { item -> SupplierOption(item.id, item.name) },
                        selectedSupplierId = supplierId,
                        models = storeState.suppliers.firstOrNull { it.id == supplierId }?.models.orEmpty(),
                        // A refresh must never silently drop the user's ticks: the picker
                        // also offers a hand-typed model that no catalogue carries. Only a
                        // supplier change invalidates them.
                        selectedModels = if (changed) emptyList() else state.selectedModels,
                        modelFilter = if (changed) "" else state.modelFilter,
                        analysis = if (changed) null else state.analysis,
                        batchResults = if (changed) emptyList() else state.batchResults,
                    )
                }
            }.onFailure { error ->
                // A failed refresh must not blank a panel that is working fine, but it
                // should refresh the reason on one that is already showing the error.
                _uiState.update { state ->
                    if (state.loadError == null) {
                        state
                    } else {
                        state.copy(loadError = error.message ?: "参考库加载失败")
                    }
                }
            }
        }
    }

    /** Keeps the selection if it still exists, else falls back the way startup does. */
    private fun candidateSupplierId(
        current: String?,
        storeState: SupplierStoreState,
    ): String? = current?.takeIf { id -> storeState.suppliers.any { it.id == id } }
        ?: storeState.activeSupplierId?.takeIf { id -> storeState.suppliers.any { it.id == id } }
        ?: storeState.suppliers.firstOrNull()?.id

    /** Filters the model list. The text is a search key, never a model name itself. */
    fun updateModelFilter(value: String) {
        if (_uiState.value.isRunning) return
        _uiState.update { it.copy(modelFilter = value) }
    }

    /** Ticks or unticks [model]; the tick order is the order the round will follow. */
    fun toggleModelSelection(model: String) {
        if (_uiState.value.isRunning) return
        _uiState.update {
            it.copy(
                selectedModels = toggleModel(it.selectedModels, model),
                analysis = null,
                batchResults = emptyList(),
            )
        }
    }

    fun clearModelSelection() {
        if (_uiState.value.isRunning) return
        _uiState.update { it.copy(selectedModels = emptyList(), analysis = null, batchResults = emptyList()) }
    }

    fun updateParallel(value: Boolean) {
        if (_uiState.value.isRunning) return
        _uiState.update { it.copy(useParallel = value) }
    }

    /**
     * Stores a pasted answer on the challenge entry itself.
     *
     * The text field is bound to `progress[index].answer`, so the edit must land
     * there; keeping a second copy would leave the field unable to display what the
     * user typed.
     */
    fun updateManualAnswer(index: Int, value: String) {
        _uiState.update { state ->
            if (index !in state.progress.indices) return@update state
            state.copy(
                progress = state.progress.toMutableList().also { list ->
                    list[index] = list[index].copy(
                        answer = value,
                        parsedNumbers = com.relaytester.app.core.fingerprint.NumberFeatures
                            .parseNumbers(value).size,
                    )
                },
                analysis = null,
            )
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null, isMessageError = false) }
    }

    /** Re-rolls the challenge set and clears the answers that belonged to it. */
    fun regenerateChallenges() {
        if (_uiState.value.isRunning) return
        _uiState.update {
            it.copy(
                analysis = null,
                // Results belong to the challenge set that produced them.
                batchResults = emptyList(),
                progress = ChallengeGenerator.generate(ANSWER_SLOTS).map { challenge ->
                    ChallengeProgress(challenge)
                },
            )
        }
    }

    /** Replaces the challenge at [index] with a fresh one of a different length. */
    fun retryChallenge(index: Int) {
        if (_uiState.value.isRunning) return
        val state = _uiState.value
        if (index !in state.progress.indices) return
        val used = state.progress.map { it.challenge.expectedCount }
        val replacement = ChallengeGenerator.generate(count = 1, usedLengths = used).first()
        _uiState.update {
            it.copy(
                analysis = null,
                batchResults = emptyList(),
                progress = it.progress.toMutableList().also { list ->
                    list[index] = ChallengeProgress(replacement)
                },
            )
        }
    }

    fun runApiDetection() {
        if (_uiState.value.isRunning) return
        val state = _uiState.value
        val supplierId = state.selectedSupplierId
        val models = state.selectedModels
        if (supplierId == null) {
            showMessage("请先选择一个供应商", isError = true)
            return
        }
        if (models.isEmpty()) {
            showMessage("请至少勾选一个模型", isError = true)
            return
        }
        val challenges = state.progress.map { it.challenge }
        if (challenges.isEmpty()) {
            showMessage("题目尚未准备好", isError = true)
            return
        }

        runJob = viewModelScope.launch {
            try {
                runRounds(supplierId, models, challenges)
            } catch (error: CancellationException) {
                // The user cancelled: the cancelled state is the whole outcome, and
                // swallowing this would break structured cancellation.
                _uiState.update { it.copy(isRunning = false, activeModel = null) }
                throw error
            } catch (error: Throwable) {
                // Last-resort net, the same shape as the batch runner in TesterViewModel:
                // an unexpected failure must release the panel and say what happened
                // rather than escape the view model scope and take the app down.
                val reason = error.message ?: "检测中断"
                _uiState.update { it.copy(isRunning = false, activeModel = null, analysis = null) }
                abandonUnfinishedRows(reason)
                showMessage(reason, isError = true)
            }
        }
    }

    private suspend fun runRounds(
        supplierId: String,
        models: List<String>,
        challenges: List<FingerprintChallenge>,
    ) {
        val prepared = withContext(ioDispatcher) {
            val supplier = suppliers().firstOrNull { it.id == supplierId }
            if (supplier == null) {
                null
            } else {
                val apiKey = supplier.apiKeySecretId
                    ?.let { secretId -> secretStore.get(secretId) }
                    .orEmpty()
                supplier to apiKey
            }
        }
        if (prepared == null) {
            showMessage("供应商配置已不存在", isError = true)
            return
        }
        val (supplier, apiKey) = prepared
        if (apiKey.isBlank()) {
            showMessage("该供应商没有可用的 API Key", isError = true)
            return
        }

        _uiState.update {
            it.copy(
                isRunning = true,
                // Models are always tested one after another: the rounds share the
                // provider's rate limit, and a serial order is what makes the result
                // list readable top to bottom.
                batchResults = models.map { model -> ModelFingerprintResult(model) },
                activeModel = models.first(),
                analysis = null,
                progress = freshProgress(it.progress.map { entry -> entry.challenge }),
            )
        }

        for (model in models) {
            _uiState.update {
                it.copy(
                    activeModel = model,
                    progress = freshProgress(it.progress.map { entry -> entry.challenge }),
                )
            }
            updateResult(model) { it.copy(status = ModelDetectionStatus.RUNNING) }

            val results = requestRound(challenges, supplier, apiKey, model)
            recordRound(model, results, challenges)
        }

        _uiState.update { it.copy(isRunning = false, activeModel = null) }
    }

    /** Sends every challenge for one model, in parallel or one at a time. */
    private suspend fun requestRound(
        challenges: List<FingerprintChallenge>,
        supplier: com.relaytester.app.core.model.SupplierProfile,
        apiKey: String,
        model: String,
    ): List<ChallengeProgress> {
        // Read the switch at dispatch time: the round starts inside a coroutine
        // after credential loading, so the captured state can be stale.
        val parallel = _uiState.value.useParallel
        return if (parallel) {
            coroutineScope {
                challenges.indices.map { index ->
                    async { requestChallenge(index, supplier, apiKey, model, challenges[index]) }
                }.awaitAll()
            }
        } else {
            challenges.indices.map { index ->
                requestChallenge(index, supplier, apiKey, model, challenges[index])
            }
        }
    }

    /** Scores one model's round and files its row in the result list. */
    private fun recordRound(
        model: String,
        results: List<ChallengeProgress>,
        challenges: List<FingerprintChallenge>,
    ) {
        val analysis = analyzeAnswers(results.map { it.answer }, challenges.map { it.expectedCount })
        // A single-model round keeps the detailed candidate ranking on screen; a batch
        // leaves it empty because that ranking would belong to only one of the rows.
        if (!_uiState.value.isBatch) {
            _uiState.update { it.copy(analysis = analysis) }
        }
        val top = analysis?.prediction
        updateResult(model) {
            it.copy(
                status = if (analysis != null) ModelDetectionStatus.DONE else ModelDetectionStatus.FAILED,
                candidateName = top?.displayName,
                candidateId = top?.modelId,
                familyName = analysis?.familyName,
                probability = top?.probability,
                usableAnswers = analysis?.usableAnswers ?: 0,
                submittedAnswers = analysis?.submittedAnswers ?: challenges.size,
                error = if (analysis == null) roundFailureReason(results) else null,
            )
        }
    }

    /**
     * Why a round produced no score.
     *
     * The per-challenge messages carry the real cause ("上游返回 HTTP 502" and the
     * like), which the aggregate bank error does not.
     */
    private fun roundFailureReason(results: List<ChallengeProgress>): String =
        results.firstNotNullOfOrNull { entry -> entry.error?.takeIf { it.isNotBlank() } }
            ?: "没有可用于检测的回答"

    private fun updateResult(model: String, transform: (ModelFingerprintResult) -> ModelFingerprintResult) {
        _uiState.update { state ->
            state.copy(
                batchResults = state.batchResults.map { row ->
                    if (row.model == model) transform(row) else row
                },
            )
        }
    }

    private fun freshProgress(challenges: List<FingerprintChallenge>): List<ChallengeProgress> =
        challenges.map { challenge -> ChallengeProgress(challenge) }

    private suspend fun requestChallenge(
        index: Int,
        supplier: com.relaytester.app.core.model.SupplierProfile,
        apiKey: String,
        model: String,
        challenge: FingerprintChallenge,
    ): ChallengeProgress {
        updateProgress(index) { it.copy(state = ChallengeState.REQUESTING, answer = "", error = null) }
        val result = try {
            relayApi.completeText(
                profile = supplier,
                apiKey = apiKey,
                model = model,
                prompt = challenge.prompt,
                maxTokens = CHALLENGE_MAX_TOKENS,
                timeoutSeconds = CHALLENGE_TIMEOUT_SECONDS,
            )
        } catch (error: CancellationException) {
            // A cancelled round is not a failed answer.
            throw error
        } catch (error: Throwable) {
            // One challenge's unexpected failure stays that challenge's failure. Letting
            // it escape would cancel its parallel siblings and take the rest of the
            // batch with it, so the user would lose three models to one bad request.
            val rejected = ChallengeProgress(
                challenge = challenge,
                state = ChallengeState.REJECTED,
                error = error.message ?: "请求异常",
            )
            updateProgress(index) { rejected }
            return rejected
        }
        val progress = when (result) {
            is ApiResult.Success -> {
                val numbers = com.relaytester.app.core.fingerprint.NumberFeatures
                    .parseNumbers(result.value).size
                val minimum = bank.minimumNumbersFor(challenge.expectedCount)
                if (numbers < minimum) {
                    ChallengeProgress(
                        challenge = challenge,
                        state = ChallengeState.REJECTED,
                        answer = result.value,
                        parsedNumbers = numbers,
                        error = "有效数字不足（$numbers/$minimum）",
                    )
                } else {
                    ChallengeProgress(
                        challenge = challenge,
                        state = ChallengeState.RECEIVED,
                        answer = result.value,
                        parsedNumbers = numbers,
                    )
                }
            }

            is ApiResult.Failure -> ChallengeProgress(
                challenge = challenge,
                state = ChallengeState.REJECTED,
                error = result.error.message,
            )
        }
        updateProgress(index) { progress }
        return progress
    }

    /** Scores the manual answers; the same validity rule applies as for API answers. */
    fun runManualAnalysis() {
        val state = _uiState.value
        val answers = state.progress.map { it.answer }
        val counts = state.progress.map { it.challenge.expectedCount }
        if (counts.isEmpty() || counts.size != answers.size) {
            showMessage("题目尚未准备好", isError = true)
            return
        }
        if (answers.all { it.isBlank() }) {
            showMessage("请先粘贴回答", isError = true)
            return
        }
        _uiState.update {
            it.copy(
                isRunning = true,
                analysis = null,
                progress = it.progress.mapIndexed { index, entry ->
                    val numbers = com.relaytester.app.core.fingerprint.NumberFeatures
                        .parseNumbers(answers[index]).size
                    entry.copy(answer = answers[index], parsedNumbers = numbers)
                },
            )
        }
        val analysis = analyzeAnswers(answers, counts)
        _uiState.update { it.copy(isRunning = false, analysis = analysis) }
    }

    /**
     * Applies the per-challenge verdicts and returns the round's ranking.
     *
     * The analysis is returned rather than stored so a batch can keep it off screen:
     * one model's candidate ranking would misrepresent the other rows. The callers
     * that own the screen state publish it themselves.
     */
    private fun analyzeAnswers(answers: List<String>, expectedCounts: List<Int>): FingerprintAnalysis? {
        // The per-answer verdict does not depend on whether anything ends up usable, so
        // it is derived here and applied on both paths. A slot the user never filled
        // stays PENDING: reporting it as a rejected answer would blame them for a
        // question that was never attempted.
        val verdicts = answers.mapIndexed { index, answer ->
            val expected = expectedCounts.getOrElse(index) { 0 }
            val parsed = com.relaytester.app.core.fingerprint.NumberFeatures.parseNumbers(answer).size
            val minimum = bank.minimumNumbersFor(expected)
            AnswerDiagnostic(index = index, parsedNumbers = parsed, minimumNumbers = minimum)
        }

        val analysis = runCatching { bank.analyze(answers, expectedCounts) }
        _uiState.update { state ->
            state.copy(
                progress = state.progress.mapIndexed { index, entry ->
                    val verdict = verdicts.getOrNull(index)
                    when {
                        verdict == null -> entry
                        // A failed API request also leaves the answer blank, but it
                        // carries an error worth keeping: clearing it would hide the
                        // reason and take the per-question retry button with it.
                        entry.answer.isBlank() && entry.error != null -> entry
                        entry.answer.isBlank() -> entry.copy(
                            state = ChallengeState.PENDING,
                            parsedNumbers = 0,
                            error = null,
                        )
                        verdict.accepted -> entry.copy(
                            state = ChallengeState.RECEIVED,
                            parsedNumbers = verdict.parsedNumbers,
                            error = null,
                        )
                        else -> entry.copy(
                            state = ChallengeState.REJECTED,
                            parsedNumbers = verdict.parsedNumbers,
                            error = "有效数字不足（${verdict.parsedNumbers}/${verdict.minimumNumbers}）",
                        )
                    }
                },
            )
        }
        if (analysis.isFailure) {
            // The cards now carry the reason; the message names the aggregate outcome.
            showMessage(
                analysis.exceptionOrNull()?.message ?: "评分失败",
                isError = true,
            )
        }
        return analysis.getOrNull()
    }

    fun cancelRun() {
        runJob?.cancel()
        runJob = null
        _uiState.update {
            it.copy(
                isRunning = false,
                activeModel = null,
                progress = it.progress.map { entry ->
                    if (entry.state == ChallengeState.REQUESTING) {
                        entry.copy(state = ChallengeState.PENDING, error = null)
                    } else {
                        entry
                    }
                },
            )
        }
        abandonUnfinishedRows("已取消")
    }

    /**
     * Closes every row that never got a verdict.
     *
     * Rows that are still queued or in flight must say so, instead of sitting on
     * "running" forever or silently disappearing from the list.
     */
    private fun abandonUnfinishedRows(reason: String) {
        _uiState.update { state ->
            state.copy(
                batchResults = state.batchResults.map { row ->
                    if (row.status == ModelDetectionStatus.PENDING ||
                        row.status == ModelDetectionStatus.RUNNING
                    ) {
                        row.copy(status = ModelDetectionStatus.FAILED, error = reason)
                    } else {
                        row
                    }
                },
            )
        }
    }

    private fun updateProgress(index: Int, transform: (ChallengeProgress) -> ChallengeProgress) {
        _uiState.update { state ->
            state.copy(
                progress = state.progress.toMutableList().also { list ->
                    if (index in list.indices) list[index] = transform(list[index])
                },
            )
        }
    }

    private fun refreshProgress() {
        val current = _uiState.value.progress.map { it.challenge }
        val challenges = if (current.size == ANSWER_SLOTS) {
            current
        } else {
            ChallengeGenerator.generate(ANSWER_SLOTS)
        }
        _uiState.update { it.copy(progress = challenges.map { challenge -> ChallengeProgress(challenge) }) }
    }

    private suspend fun suppliers(): List<com.relaytester.app.core.model.SupplierProfile> =
        withContext(ioDispatcher) { supplierStore.read().suppliers }.also { knownSuppliers = it }

    private fun showMessage(message: String, isError: Boolean) {
        _uiState.update { it.copy(message = message, isMessageError = isError) }
    }

    companion object {
        /** Upstream prompts ask for ~300 integers; 8192 covers that with headroom. */
        const val CHALLENGE_MAX_TOKENS = 8192

        /**
         * Upstream allows 250 s per answer. Relays that buffer a long completion can
         * legitimately need well over a minute, so this stays close to that ceiling.
         */
        const val CHALLENGE_TIMEOUT_SECONDS = 240

        const val ANSWER_SLOTS = 3

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras,
            ): T {
                val applicationContext = context.applicationContext
                return FingerprintViewModel(
                    supplierStore = SupplierStore(applicationContext),
                    secretStore = KeystoreSecretStore(applicationContext),
                    relayApiFactory = { RelayApi() },
                    bankFactory = { FingerprintBank.load(applicationContext) },
                ) as T
            }
        }
    }
}
