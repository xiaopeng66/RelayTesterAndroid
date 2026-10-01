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

@Immutable
data class FingerprintUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val referenceBuiltAt: String = "",
    val modelCount: Int = 0,
    val suppliers: List<SupplierOption> = emptyList(),
    val selectedSupplierId: String? = null,
    val selectedModel: String = "",
    val models: List<String> = emptyList(),
    val mode: DetectionMode = DetectionMode.API,
    val useParallel: Boolean = true,
    val isRunning: Boolean = false,
    val progress: List<ChallengeProgress> = emptyList(),
    /** Floor from the reference bank; the panel previews pastes against it. */
    val minimumValidNumbers: Int = 80,
    val analysis: FingerprintAnalysis? = null,
    val message: String? = null,
    val isMessageError: Boolean = false,
)

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

    /**
     * Suspends until an in-flight detection round settles.
     *
     * Tests drive a round through a dispatcher they control and need a
     * deterministic join; polling the UI state would be racy.
     */
    internal suspend fun awaitIdle() {
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
            viewModelScope.launch { load() }
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
                    selectedModel = pendingPrefill?.second ?: it.selectedModel,
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
            _uiState.update { it.copy(mode = DetectionMode.API, selectedModel = model, analysis = null) }
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
                selectedSupplierId = supplierId,
                models = supplier?.models.orEmpty(),
                selectedModel = model,
            )
        }
        refreshProgress()
    }

    fun selectMode(mode: DetectionMode) {
        if (_uiState.value.isRunning) return
        _uiState.update { it.copy(mode = mode, analysis = null) }
        refreshProgress()
    }

    fun selectSupplier(supplierId: String) {
        if (_uiState.value.isRunning) return
        val supplier = knownSuppliers.firstOrNull { it.id == supplierId }
        _uiState.update {
            it.copy(
                selectedSupplierId = supplierId,
                models = supplier?.models.orEmpty(),
                selectedModel = it.selectedModel.takeIf { model -> supplier?.models?.contains(model) == true }.orEmpty(),
            )
        }
    }

    fun updateModel(value: String) {
        if (_uiState.value.isRunning) return
        _uiState.update { it.copy(selectedModel = value) }
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
        val model = state.selectedModel.trim()
        if (supplierId == null) {
            showMessage("请先选择一个供应商", isError = true)
            return
        }
        if (model.isEmpty()) {
            showMessage("请填写要检测的模型名", isError = true)
            return
        }
        val challenges = state.progress.map { it.challenge }
        if (challenges.isEmpty()) {
            showMessage("题目尚未准备好", isError = true)
            return
        }

        runJob = viewModelScope.launch {
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
                return@launch
            }
            val (supplier, apiKey) = prepared
            if (apiKey.isBlank()) {
                showMessage("该供应商没有可用的 API Key", isError = true)
                return@launch
            }

            _uiState.update {
                it.copy(
                    isRunning = true,
                    analysis = null,
                    progress = it.progress.map { entry ->
                        entry.copy(state = ChallengeState.PENDING, answer = "", parsedNumbers = 0, error = null)
                    },
                )
            }

            val results = try {
                // Read the switch at dispatch time: the round starts inside a coroutine
                // after credential loading, so the captured state can be stale.
                if (_uiState.value.useParallel) {
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
            } catch (error: CancellationException) {
                _uiState.update { it.copy(isRunning = false) }
                throw error
            }

            _uiState.update { it.copy(isRunning = false) }
            analyzeAnswers(results.map { it.answer }, challenges.map { it.expectedCount })
        }
    }

    private suspend fun requestChallenge(
        index: Int,
        supplier: com.relaytester.app.core.model.SupplierProfile,
        apiKey: String,
        model: String,
        challenge: FingerprintChallenge,
    ): ChallengeProgress {
        updateProgress(index) { it.copy(state = ChallengeState.REQUESTING, answer = "", error = null) }
        val result = relayApi.completeText(
            profile = supplier,
            apiKey = apiKey,
            model = model,
            prompt = challenge.prompt,
            maxTokens = CHALLENGE_MAX_TOKENS,
            timeoutSeconds = CHALLENGE_TIMEOUT_SECONDS,
        )
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
        analyzeAnswers(answers, counts)
        _uiState.update { it.copy(isRunning = false) }
    }

    private fun analyzeAnswers(answers: List<String>, expectedCounts: List<Int>) {
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
                analysis = analysis.getOrNull(),
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
    }

    fun cancelRun() {
        runJob?.cancel()
        runJob = null
        _uiState.update {
            it.copy(
                isRunning = false,
                progress = it.progress.map { entry ->
                    if (entry.state == ChallengeState.REQUESTING) {
                        entry.copy(state = ChallengeState.PENDING, error = null)
                    } else {
                        entry
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
