package com.relaytester.app.feature.fingerprint

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.relaytester.app.core.fingerprint.AnswerDiagnostic
import com.relaytester.app.core.fingerprint.AndroidBankFileSystem
import com.relaytester.app.core.fingerprint.AndroidHistoryFileSystem
import com.relaytester.app.core.fingerprint.BankDiscardResult
import com.relaytester.app.core.fingerprint.BankInstallResult
import com.relaytester.app.core.fingerprint.BankLoadResult
import com.relaytester.app.core.fingerprint.BankManifest
import com.relaytester.app.core.fingerprint.BankSource
import com.relaytester.app.core.fingerprint.BankUpdateCheck
import com.relaytester.app.core.fingerprint.BankUpdateClient
import com.relaytester.app.core.fingerprint.ChallengeGenerator
import com.relaytester.app.core.fingerprint.DetectionHistoryEntry
import com.relaytester.app.core.fingerprint.FingerprintAnalysis
import com.relaytester.app.core.fingerprint.FingerprintBank
import com.relaytester.app.core.fingerprint.FingerprintBankStore
import com.relaytester.app.core.fingerprint.FingerprintCandidate
import com.relaytester.app.core.fingerprint.FingerprintChallenge
import com.relaytester.app.core.fingerprint.FingerprintHistoryStore
import com.relaytester.app.core.fingerprint.LoadedBank
import com.relaytester.app.core.fingerprint.NumberFeatures
import com.relaytester.app.core.fingerprint.minimumNumbersFor
import com.relaytester.app.core.model.ApiResult
import com.relaytester.app.core.model.ErrorKind
import com.relaytester.app.core.network.RelayApi
import com.relaytester.app.core.security.KeystoreSecretStore
import com.relaytester.app.core.security.SecretStore
import com.relaytester.app.core.storage.SupplierStore
import com.relaytester.app.core.storage.SupplierStoreState
import com.relaytester.app.core.storage.UpdatePreferences
import com.relaytester.app.core.storage.UpdatePreferencesState
import com.relaytester.app.core.update.OkHttpFetcher
import com.relaytester.app.core.update.DownloadProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /**
     * Integers seen in the text that has arrived so far, while [state] is [ChallengeState.REQUESTING].
     *
     * A challenge asks for a long list and the answer arrives over a minute or more, so
     * the panel counts what the stream has already delivered instead of showing a bare
     * "正在接收…". Zero when nothing countable has arrived yet, which is the normal state
     * for the first seconds of a request.
     */
    val receivedNumbers: Int = 0,
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
    /**
     * This model's full ranking, so a finished row can be opened and read.
     *
     * A batch used to keep only the winner, which meant testing a model inside a batch
     * told you less than testing it alone. The list is the same one the single-model card
     * renders, so the two views agree by construction.
     */
    val candidates: List<FingerprintCandidate> = emptyList(),
)

@Immutable
data class FingerprintUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    /**
     * Where the detection package in use came from.
     *
     * The package is downloaded instead of being packaged in the APK, so
     * [BankSource.NOT_PROVISIONED] is a normal first-run state rather than an error:
     * the panel stays usable and offers the download.
     */
    val bankSource: BankSource = BankSource.NOT_PROVISIONED,
    /** Set when an installed package could not be used; the panel offers a re-download. */
    val bankProblem: String? = null,
    /** True when the installed file was unusable and its backup is carrying the panel. */
    val bankUsedBackup: Boolean = false,
    val referenceBuiltAt: String = "",
    val bankSha256: String = "",
    val bankSizeBytes: Long = 0,
    val modelCount: Int = 0,
    /**
     * The models the installed package supports, in the package's own order.
     *
     * Carried so the panel can list what it can actually identify; empty when nothing is
     * installed, which is what hides the list instead of showing an empty one.
     */
    val bankModels: List<BankModelInfo> = emptyList(),
    /** True while a user-requested check is talking to the release endpoint. */
    val isCheckingBankUpdate: Boolean = false,
    /**
     * True while the panel's own entry check is in flight.
     *
     * Kept apart from [isCheckingBankUpdate] on purpose: the entry check is nobody's
     * button, so it must not put the card's controls into a waiting state, block a manual
     * check or hold up a detection round. It only decides whether the delay is worth a
     * silent retry.
     */
    val isCheckingBankInBackground: Boolean = false,
    /** True while the checked bank is downloading and being installed. */
    val isInstallingBank: Boolean = false,
    /**
     * Bytes of the package download in flight; null when none is running.
     *
     * A 3 MB download over a phone connection is tens of seconds of "installing", which
     * without this is indistinguishable from a hang.
     */
    val bankDownloadProgress: DownloadProgress? = null,
    /**
     * When the last package check finished, in epoch millis; null before the first one.
     *
     * The panel's own copy: a check used to leave either an offer or nothing at all, so a
     * silent entry check that found no update was indistinguishable from one that never
     * ran. This is what the status row prints, and it is written by both paths.
     */
    val bankCheckedAtMillis: Long? = null,
    /**
     * When the last check finished, if it did not run in this process.
     *
     * The automatic check is throttled by a stamp on disk, so an app opened again inside the
     * window runs no check and has no verdict of its own — and the row would fall back to
     * 「尚未检查」, which is false: the endpoint was asked, just not now. The stored stamp is
     * kept apart from [bankCheckedAtMillis] (this process's own finished check, which always
     * wins) so the row can print the time without claiming a result it never saw.
     */
    val bankEarlierCheckAtMillis: Long? = null,
    /**
     * True when the last package check ended in an error.
     *
     * Kept because a failed check leaves no other trace: the reason is a snackbar, and the
     * snackbar is gone the moment the user stops looking at it. The row has to be able to
     * say the last check failed instead of falling back to "尚未检查".
     */
    val bankCheckFailed: Boolean = false,
    /**
     * Whether entering the panel checks the release endpoint on its own.
     *
     * Stored, not in-memory: a user who turned the automatic check off should not get it
     * back by reopening the app. Off means the card's own 「检查更新」 is the only way to
     * reach the endpoint.
     */
    val autoCheckBank: Boolean = true,
    /** Set when a check found a different published bank. */
    val availableBankUpdate: BankManifest? = null,
    /**
     * Set when the published package cannot be used by this app — the value is the app
     * version code the publisher asks for, or 0 when the manifest did not say.
     *
     * Two different answers land here: a manifest whose package format is newer than this
     * build can read, and one whose `minAppVersionCode` is above this app's. They mean the
     * same thing to the user ("update the app"), so the panel says it once instead of
     * printing a format number. The update offer stays hidden either way — a package this
     * app cannot score must not be installable — and the silent entry check sets this too,
     * so the hint is there on every entry rather than only after pressing a button.
     */
    val bankNeedsNewerApp: Long? = null,
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
    /**
     * The questions on screen, in one screen-wide list.
     *
     * This is the manual mode's answer sheet and the source of the round's challenge
     * template; during an API round it holds the model currently being asked, because each
     * model resets it. Anything that has to speak about every model at once reads
     * [modelSlots] instead.
     */
    val progress: List<ChallengeProgress> = emptyList(),
    /**
     * Every model's three slots, by model, in the round's own order.
     *
     * Published so the panel can draw one block per model — a multi-model run has to show
     * each model's progress at once to be readable, and [progress] can only ever describe
     * one of them. Filled from the same cache the retry path re-scores from, so the grid
     * and the retry agree by construction.
     */
    val modelSlots: Map<String, List<ChallengeProgress>> = emptyMap(),
    /**
     * Questions with something to re-ask, mapped to the models that failed them.
     *
     * The retry button sits on the challenge card, but a batch can fail the same question
     * on several models — and the user picks which one to re-ask, so the entry carries the
     * model list rather than a bare "retryable" flag. Empty until a settled model lacks an
     * answer for a slot; a slot still being requested never appears here.
     */
    val retryableModels: Map<Int, List<String>> = emptyMap(),
    /**
     * Slots whose retry is waiting for the serial gate, as (model, question).
     *
     * Queued is not the same as requested: with 逐题发送 on, the re-ask cannot go on the
     * wire until the question the round is already asking comes back, and a cell that said
     * "正在接收" through that wait would be lying. Cleared the moment the gate is entered.
     */
    val queuedRetries: Set<Pair<String, Int>> = emptySet(),
    /** Floor from the reference bank; the panel previews pastes against it. */
    val minimumValidNumbers: Int = 80,
    val analysis: FingerprintAnalysis? = null,
    /** One row per ticked model; this is the panel's output when several are ticked. */
    val batchResults: List<ModelFingerprintResult> = emptyList(),
    /** The model whose challenges are on screen right now, if a round is running. */
    val activeModel: String? = null,
    val message: String? = null,
    val isMessageError: Boolean = false,
    /**
     * Past detections, newest first, most recent [FingerprintHistoryStore.MAX_ENTRIES].
     *
     * Loaded with the panel rather than on demand: the history dialog is one tap away
     * and a dialog that opens empty and fills in a moment later reads as broken.
     */
    val history: List<DetectionHistoryEntry> = emptyList(),
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
 * One model the installed package can identify, as the panel lists it.
 *
 * The package carries both names per model, so the panel can answer "which models are in
 * the library I am using" without shipping a second copy of the roster.
 */
@Immutable
data class BankModelInfo(
    val displayName: String,
    val familyName: String,
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
    private val bankStore: FingerprintBankStore,
    private val bankUpdateClient: BankUpdateClient,
    /** The two update switches, which outlive the process. */
    private val updatePreferences: UpdatePreferences,
    /** Installed version code, read by the factory from the package manager. */
    private val appVersionCode: Long = 0L,
    /** Past detections, capped on write; null in tests that do not exercise it. */
    private val historyStore: FingerprintHistoryStore? = null,
    private val skipRestore: Boolean = false,
    /** Overridden by tests so a round can be observed without racing real threads. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Overridden by tests so a check's timestamp is a value, not "whenever this ran". */
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * How often the panel looks for a newer package while the app stays open.
     *
     * Injected so a test can drive the periodic check with virtual time instead of waiting
     * six hours; production uses the same window as the app's own channel.
     */
    private val bankCheckIntervalMillis: Long = UpdatePreferencesState.APP_CHECK_INTERVAL_MILLIS,
) : ViewModel() {
    private val relayApi: RelayApi by lazy { relayApiFactory() }

    /**
     * The newest readable bank, cached for the round in progress.
     *
     * Re-read after an install or a rollback: the copy behind it changes, and a round
     * must never score against the bank that was active when the panel was opened.
     */
    @Volatile
    private var loadedBank: LoadedBank? = null

    /**
     * The active package, or null after telling the user why nothing can be scored.
     *
     * A round without a package cannot be scored at all, so it is refused up front
     * rather than one rejected challenge per model: the request would still be sent,
     * and a mid-round failure would blame the challenge instead of the missing package.
     */
    private fun bankOrReport(): FingerprintBank? = loadedBank?.bank ?: run {
        showMessage(
            if (_uiState.value.bankSource == BankSource.NOT_PROVISIONED) {
                "检测包尚未安装：请在下方「检测包」卡片下载后重试"
            } else {
                "检测包无法读取：请在下方「检测包」卡片重新下载"
            },
            isError = true,
        )
        null
    }

    /** Validity floor for one challenge, from the installed package when there is one. */
    private fun challengeFloor(expectedCount: Int): Int = loadedBank?.bank
        ?.minimumNumbersFor(expectedCount)
        ?: minimumNumbersFor(expectedCount, DEFAULT_MINIMUM_VALID_NUMBERS)

    private val _uiState = MutableStateFlow(FingerprintUiState())
    val uiState = _uiState

    private var runJob: Job? = null

    /**
     * Single-question retries in flight, by (model, question).
     *
     * Kept apart from [runJob] on purpose: a retry must be able to run **alongside** a
     * round — the failed model has already settled, so its slot can be re-asked while the
     * round works through the next one — and cancelling the round must not kill a retry
     * the user explicitly asked for. With 逐题发送 on, "alongside" means queued behind
     * whatever single request is on the wire, not beside it (see [serialGate]). A new
     * round, on the other hand, waits for these.
     */
    private val retryJobs = mutableMapOf<Pair<String, Int>, Job>()

    /**
     * The one-at-a-time gate the panel uses when it is set to 逐题发送.
     *
     * That switch exists for providers that cannot take concurrent requests, so a
     * single-question retry must not slip in beside the question the round is already
     * asking. A fair (FIFO) mutex gives exactly the order the panel promises: the retry
     * waits for the in-flight question, then goes ahead of the round's next one, which is
     * what "排到当前那一题之后" means. With the parallel switch on nothing takes the gate,
     * so both the round's questions and any retry go out at once.
     */
    private val serialGate = Mutex()

    /** Runs [block] behind the serial gate unless the panel is sending in parallel. */
    private suspend fun <T> serialized(block: suspend () -> T): T =
        if (_uiState.value.useParallel) block() else serialGate.withLock { block() }

    /**
     * Publishes whether a slot's retry is still waiting for the gate.
     *
     * The slot itself already reads REQUESTING from the moment the retry is queued — that
     * placeholder is what stops the entry from being offered twice — so the wait has to be
     * carried separately or the cell could not tell queued from receiving.
     */
    private fun markRetryQueued(model: String, index: Int, queued: Boolean) {
        _uiState.update { state ->
            val key = model to index
            val next = if (queued) state.queuedRetries + key else state.queuedRetries - key
            if (next == state.queuedRetries) state else state.copy(queuedRetries = next)
        }
    }

    /**
     * Every model's answers from the last round, by model.
     *
     * Re-scoring after a single-question retry needs the answers that model gave to the
     * other questions, and [FingerprintUiState.progress] cannot supply them: it is one
     * screen-wide list that each model overwrites as the batch walks down the rows, so by
     * the end it holds only the last model's answers.
     */
    private var roundAnswers: Map<String, List<ChallengeProgress>> = emptyMap()

    /**
     * Stores one slot of one model's round, as soon as the request that slot owns returns.
     *
     * Written per challenge rather than once per model on purpose: a round that is
     * cancelled with two answers already in hand must keep them, or the retry of the
     * interrupted slot would have to start the whole round over.
     */
    private fun cacheAnswer(
        model: String,
        index: Int,
        challenges: List<FingerprintChallenge>,
        answer: ChallengeProgress,
    ) {
        val slots = roundAnswers[model]?.toMutableList()
            ?.takeIf { it.size == challenges.size }
            ?: challenges.map { challenge -> ChallengeProgress(challenge) }.toMutableList()
        if (index !in slots.indices) return
        slots[index] = answer
        setRoundAnswers(roundAnswers + (model to slots))
    }

    /** Publishes the answers, the panel's per-model view of them, and the retry entries. */
    private fun setRoundAnswers(next: Map<String, List<ChallengeProgress>>) {
        roundAnswers = next
        _uiState.update { it.copy(modelSlots = next) }
        publishRetryable()
    }

    /**
     * Folds a round pass's answers into what the cache already holds.
     *
     * A successful answer is never downgraded: the pass's list was captured before any
     * single-question retry that landed while this model was still being asked, so it can
     * still carry the rejection the retry just repaired. Everything else — a fresh
     * rejection, a fresh success, an untouched placeholder — is taken from the pass.
     */
    private fun mergeChallengeAnswers(
        cached: List<ChallengeProgress>?,
        fresh: List<ChallengeProgress>,
    ): List<ChallengeProgress> {
        if (cached == null || cached.size != fresh.size) return fresh
        return fresh.mapIndexed { index, entry ->
            val existing = cached[index]
            if (existing.state == ChallengeState.RECEIVED && entry.state != ChallengeState.RECEIVED) {
                existing
            } else {
                entry
            }
        }
    }

    /**
     * Recomputes the retry entries from the cache and the rows that have settled.
     *
     * Called after every row-status change as well as after every cache write: a model
     * only becomes a retry candidate once its own round part is over, and that fact lives
     * in [FingerprintUiState.batchResults], not in the cache.
     */
    private fun publishRetryable() {
        val settled = _uiState.value.batchResults
            .filter {
                it.status == ModelDetectionStatus.DONE || it.status == ModelDetectionStatus.FAILED
            }
            .map { it.model }
            .toSet()
        val retryable = retryableFrom(roundAnswers, settled)
        _uiState.update { it.copy(retryableModels = retryable) }
    }

    /**
     * Which question slots still have something to finish, and on which models.
     *
     * Derived across every model of the round, not from the progress list: that list ends
     * up holding only the last model's answers, so a model that failed early would lose
     * its "重试本题" button the moment a healthy model finished.
     *
     * A slot counts as failed for a model in exactly two ways:
     *
     *  * its cached verdict is a rejection — a definitive "this question got no usable
     *    answer", which is offerable the moment it lands, even while the other questions
     *    of the same model are still in flight (the user asked for that explicitly);
     *  * it is still the untouched placeholder and the model has already settled — an
     *    interrupted round's leftover (cancel, crash, a slot that never ran).
     *
     * A slot being requested right now is neither: the round's in-flight question has no
     * cached entry yet, and a retry's own placeholder is [ChallengeState.REQUESTING], so
     * the button never shows while something is still receiving and cannot be double-fired.
     */
    private fun retryableFrom(
        cached: Map<String, List<ChallengeProgress>>,
        settledModels: Set<String>,
    ): Map<Int, List<String>> {
        if (cached.isEmpty()) return emptyMap()
        val slots = cached.values.maxOf { it.size }
        val entries = mutableMapOf<Int, List<String>>()
        for (index in 0 until slots) {
            val models = cached.mapNotNull { (model, answers) ->
                val entry = answers.getOrNull(index) ?: return@mapNotNull null
                val failed = when (entry.state) {
                    ChallengeState.REJECTED -> true
                    ChallengeState.PENDING -> model in settledModels
                    ChallengeState.RECEIVED, ChallengeState.REQUESTING -> false
                }
                if (failed) model else null
            }
            if (models.isNotEmpty()) entries[index] = models
        }
        return entries
    }

    /**
     * True while a round owns the panel, including a cancelled round's tail.
     *
     * `isRunning` alone is not enough: it is only a published flag, while a job that was
     * just cancelled may still be running its cleanup (a request releasing its connection,
     * a NonCancellable block). Starting anything in that window would overlap two rounds
     * and let the dying one write over the new one's state.
     */
    private fun runInFlight(): Boolean = _uiState.value.isRunning || runJob?.isCompleted == false

    /**
     * The supplier and key the last round used, so one question can be re-asked.
     *
     * Held from the round itself rather than re-read at retry time: a retry continues the
     * round that is on screen, and re-reading could pick up a supplier the user changed in
     * between.
     */
    private var roundCredentials: Pair<com.relaytester.app.core.model.SupplierProfile, String>? = null

    /** The catalogue refresh, tracked so tests can join it deterministically. */
    private var refreshJob: Job? = null

    /** The startup read, tracked so a test can tell a parked load from a finished one. */
    private var loadJob: Job? = null

    /** A check, a download or a rollback; one at a time, tracked so tests can join it. */
    private var bankUpdateJob: Job? = null

    /** A history read or write, tracked so tests can join it. */
    private var historyJob: Job? = null

    /** The switch write, tracked so a test can tell "flipped" from "flipped and stored". */
    private var preferenceJob: Job? = null

    /** The periodic package check; one loop for the life of the activity. */
    private var bankTimerJob: Job? = null

    /**
     * When this process last finished a package check.
     *
     * The loop's wake time is computed from the later of this and the stored timestamp: the
     * stored one is what survives a restart, and this one is what keeps the loop honest when
     * that write did not land — without it a lost write would make every wake compute "due
     * long ago" and re-check in a tight cycle.
     */
    private var lastBankCheckAtMillis = 0L

    override fun onCleared() {
        // The timer is the only thing here that would otherwise outlive the activity.
        bankTimerJob?.cancel()
        super.onCleared()
    }

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
        bankUpdateJob?.join()
        historyJob?.join()
        preferenceJob?.join()
        // A single-question retry outlives the round that spawned the failed model, so the
        // round's join alone would let a test finish while a re-ask it triggered is still in
        // flight. Snapshot first: a joined job removes itself from the map.
        while (true) {
            val pending = retryJobs.values.toList()
            if (pending.isEmpty()) break
            pending.forEach { it.join() }
        }
    }

    /**
     * Cached alongside UI state so synchronous callbacks (chip taps) never need to
     * re-read DataStore. Refreshed whenever the store is read.
     */
    private var knownSuppliers: List<com.relaytester.app.core.model.SupplierProfile> = emptyList()

    /** Supplier/model pair requested before the store finished loading. */
    private var pendingPrefill: Pair<String?, String>? = null

    /**
     * Set when the launch check was asked for while a startup read was still running.
     *
     * [startBankCheck] drops a check while anything else is in flight, and the launch check
     * runs once per activity, so without this the first launch could lose it entirely —
     * including the first-run case where that check is what puts the download button on an
     * empty card. Replayed as soon as the read lands.
     */
    private var entryCheckPending = false

    init {
        if (skipRestore) {
            _uiState.update { it.copy(isLoading = false) }
            // Skipping the restore is about the supplier store; every action on the panel
            // still scores against a package, so the package is loaded either way.
            loadJob = viewModelScope.launch {
                val (result, updates) = withContext(ioDispatcher) {
                    // The switches are read on this path too: skipping the supplier read
                    // must not mean skipping the user's "do not check on its own" choice.
                    bankStore.load() to updatePreferences.read()
                }
                loadedBank = result.loaded
                _uiState.update { it.withBank(result).copy(autoCheckBank = updates.autoCheckBank) }
                loadHistory()
                replayPendingEntryCheck()
            }
        } else {
            loadJob = viewModelScope.launch { load() }
        }
    }

    /**
     * Reads the stored history into the panel.
     *
     * Called on both startup paths, and again after each write: the panel's copy is a
     * snapshot, and letting it drift from the file would make the dialog show a record
     * the user cannot find anywhere else, or miss the one just written.
     */
    private suspend fun loadHistory() {
        val store = historyStore ?: return
        val entries = withContext(ioDispatcher) { runCatching { store.load() }.getOrDefault(emptyList()) }
        _uiState.update { it.copy(history = entries) }
    }

    private suspend fun load() {
        val loaded = withContext(ioDispatcher) {
            runCatching {
                val bank = bankStore.load()
                val state = supplierStore.read()
                // Read with the rest of the startup work: the entry check it gates runs
                // in the same launch, and a switch read afterwards would come too late.
                val updates = updatePreferences.read()
                Triple(bank, state, updates)
            }
        }
        loaded.onSuccess { startup ->
            val (bank, storeState, updateState) = startup
            loadedBank = bank.loaded
            knownSuppliers = storeState.suppliers
            // A prefill that arrived while the store was still loading must survive it.
            val supplierId = pendingPrefill?.first
                ?: storeState.activeSupplierId
                ?: storeState.suppliers.firstOrNull()?.id
            val supplier = storeState.suppliers.firstOrNull { it.id == supplierId }
            _uiState.update {
                it.withBank(bank).copy(
                    isLoading = false,
                    loadError = null,
                    autoCheckBank = updateState.autoCheckBank,
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
            loadHistory()
            replayPendingEntryCheck()
        }.onFailure { error ->
            _uiState.update {
                it.copy(isLoading = false, loadError = error.message ?: "检测包加载失败")
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
        if (runInFlight()) return
        dropRoundContext()
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
        dropRoundContext()
        _uiState.update { it.copy(mode = mode, analysis = null, batchResults = emptyList()) }
        refreshProgress()
    }

    fun selectSupplier(supplierId: String) {
        if (_uiState.value.isRunning) return
        val supplier = knownSuppliers.firstOrNull { it.id == supplierId }
        // The next round will use another supplier, so the last one's key must not be
        // what a retry reaches for.
        dropRoundContext()
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
                    val bank = bankStore.load()
                    bank to supplierStore.read()
                }
            }
            read.onSuccess { (bank, storeState) ->
                loadedBank = bank.loaded
                knownSuppliers = storeState.suppliers
                _uiState.update { state ->
                    val supplierId = candidateSupplierId(state.selectedSupplierId, storeState)
                    val changed = supplierId != state.selectedSupplierId
                    state.withBank(bank).copy(
                        // A retry that succeeds has to lift the error screen, or a
                        // transient startup failure would strand the panel for good.
                        isLoading = false,
                        loadError = null,
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
                        state.copy(loadError = error.message ?: "检测包加载失败")
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
        // Clearing the rows clears the round they described, retry context included.
        dropRoundContext()
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
        dropRoundContext()
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
        // The questions a round was built on are gone, so a retry has no round to continue.
        dropRoundContext()
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

    /**
     * Forgets the round a single-question retry could continue.
     *
     * Called wherever the result rows are cleared, and wherever the supplier or the model
     * selection changes: with no rows on screen there is no round to finish, and a stale
     * credential would let a later retry spend a request against a configuration the user
     * has already moved away from.
     */
    private fun dropRoundContext() {
        setRoundAnswers(emptyMap())
        roundCredentials = null
    }

    /**
     * Re-asks one question for the models the user picked, keeping the round it belongs to.
     *
     * This used to only swap in a fresh prompt and drop every result, so a round that
     * tripped on one question had to be restarted from the first model. When there is a
     * round to continue — a supplier and key in hand, and a result row to put the verdict
     * on — only the chosen models' slot is requested again, and each is re-scored from its
     * own answers, so the panel converges on a result instead of starting over. The models
     * come from the caller: every failed cell carries its own model, so the re-ask lands on
     * exactly the models the user pointed at and never on one that already answered.
     *
     * A running round does not block this any more: the failed model has already settled,
     * so its slot can be re-asked while the round works on the next one. What is still
     * refused is a round that is *dying* — cancelled, with cleanup in flight — because its
     * last writes would land on top of the retry's.
     *
     * When it actually goes on the wire depends on the send mode: with 逐题发送 on it is
     * queued behind whichever question the round is already asking (see [serialGate]), and
     * with the parallel switch on it goes out immediately beside the round.
     *
     * With nothing to continue (manual mode, or before the first round) a fresh prompt is
     * the whole retry, which is what this button has always meant there.
     */
    fun retryChallenge(index: Int, models: List<String>? = null) {
        val state = _uiState.value
        if (index !in state.progress.indices) return

        val credentials = roundCredentials
        if (credentials == null || state.batchResults.isEmpty()) {
            replaceChallenge(index)
            return
        }
        if (!state.isRunning && runJob?.isCompleted == false) return
        val failed = (models ?: state.retryableModels[index].orEmpty())
            .filter { candidate -> state.batchResults.any { it.model == candidate } }
        if (failed.isEmpty()) return
        if (refuseWhileBankJobRuns()) return
        if (bankOrReport() == null) return

        val template = state.progress.map { it.challenge }
        val challenge = template[index]
        val fresh = failed.filter { (it to index) !in retryJobs }
        if (fresh.isEmpty()) return
        for (model in fresh) {
            val job = viewModelScope.launch {
                retrySlot(index, model, challenge, template, credentials)
            }
            retryJobs[model to index] = job
            job.invokeOnCompletion {
                retryJobs.remove(model to index)
                // A job cancelled while it was still waiting for the gate never reaches the
                // clearing inside retrySlot, and a stale 排队中 would outlive its request.
                markRetryQueued(model, index, queued = false)
            }
        }
    }

    /**
     * One model's single re-asked slot, from request to re-scored verdict.
     *
     * The row is not flipped to "running" for this: the whole-round progress bar counts
     * settled models, and bouncing a settled row would march the bar backwards. The slot
     * on the challenge card shows the request's own progress instead, and the row updates
     * once when the new answer lands.
     *
     * The re-score is skipped while the round still owns this model: its own completion
     * re-scores from the cache, which already holds the new answer, and scoring here too
     * would have two writers racing the same row. The moment the round moves past the
     * model, this re-score runs and the row converges.
     */
    private suspend fun retrySlot(
        index: Int,
        model: String,
        challenge: FingerprintChallenge,
        template: List<FingerprintChallenge>,
        credentials: Pair<com.relaytester.app.core.model.SupplierProfile, String>,
    ) {
        // A REQUESTING slot in the cache withdraws the retry entry for as long as this
        // re-ask runs, so the button cannot be double-fired into two parallel requests.
        cacheAnswer(model, index, template, ChallengeProgress(challenge, state = ChallengeState.REQUESTING))
        // 逐题发送时这条重试要等当前那一题发完才能上线路（见 serialGate）；等待期间格子靠这个
        // 标记显示「排队中」，不然它会拿 REQUESTING 假装已经在接收。
        markRetryQueued(model, index, queued = !_uiState.value.useParallel)
        val answered = serialized {
            markRetryQueued(model, index, queued = false)
            requestChallenge(
                index = index,
                supplier = credentials.first,
                apiKey = credentials.second,
                model = model,
                challenge = challenge,
                slots = template,
            )
        }
        // The other slots keep that model's own answers; a model whose round never got
        // that far is filled with the placeholder slots the retry is about to score, so
        // the re-scoring never mixes two editors' answers.
        val previous = roundAnswers[model]
        val answers = (if (previous != null && previous.size == template.size) {
            previous.toMutableList()
        } else {
            template.map { ChallengeProgress(it) }.toMutableList()
        }).also { list -> list[index] = answered }
        setRoundAnswers(roundAnswers + (model to answers))
        val stateNow = _uiState.value
        if (!(stateNow.isRunning && stateNow.activeModel == model)) {
            recordRound(model, answers, template)
        }
    }

    /**
     * Swaps in a fresh prompt for [index], clearing what the swap invalidated.
     *
     * The manual path: the panel is scoring by hand, so a different question means the
     * verdicts on screen no longer describe the questions under them.
     */
    private fun replaceChallenge(index: Int) {
        val used = _uiState.value.progress.map { it.challenge.expectedCount }
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
        // The job handle, not the flag: `isRunning` is only published once the credentials
        // are in, and the second tap of a double tap lands inside that window. Without
        // this both launches survive, both spend upstream requests, and `cancelRun` can
        // only reach the newer one.
        if (runInFlight()) return
        if (refuseWhileBankJobRuns()) return
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
        // Nothing to score with, so the requests would be spent for nothing.
        if (bankOrReport() == null) return

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
        // The round's own credentials and answers: a single-question retry continues this
        // round, and must do it with the supplier it actually ran against.
        roundCredentials = supplier to apiKey
        setRoundAnswers(emptyMap())

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
            // The round's own list is not the whole truth any more: a single-question retry
            // may have landed for this model while its questions were still being asked, and
            // this list was fixed before that. Merging (never downgrading a successful
            // answer) keeps the retry's answer instead of overwriting it with the stale
            // rejection the user just fixed.
            val merged = mergeChallengeAnswers(roundAnswers[model], results)
            setRoundAnswers(roundAnswers + (model to merged))
            recordRound(model, merged, challenges)
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
                    async { requestChallenge(index, supplier, apiKey, model, challenges[index], challenges) }
                }.awaitAll()
            }
        } else {
            // 逐题发送：整轮的每一题也走闸门，于是排队中的重试恰好插在当前那一题之后、
            // 下一题之前——并行开关打开时闸门不参与，这里与并发分支等价。
            challenges.indices.map { index ->
                serialized { requestChallenge(index, supplier, apiKey, model, challenges[index], challenges) }
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
                // The full ranking travels with the row: a batch row opens onto the same
                // candidate list the single-model card shows, instead of telling the user
                // less for testing more models.
                candidates = analysis?.candidates.orEmpty(),
            )
        }
        // The one funnel every finished model passes through — the batch loop and the
        // single-question retry both land here — so a record cannot be missed by a new
        // caller, and a cancelled round (which never reaches this) is not recorded.
        appendHistory(model)
    }

    /**
     * Files the model's current row in the history.
     *
     * Written off the UI thread: it is a small file, but the caller is a coroutine that
     * may be holding the panel's run and a blocking write there would show up as a
     * stutter between models. A failure is swallowed on purpose — the detection it
     * describes already succeeded, and losing a history row must not fail it.
     */
    private fun appendHistory(model: String) {
        val store = historyStore ?: return
        val row = _uiState.value.batchResults.firstOrNull { it.model == model } ?: return
        val entry = DetectionHistoryEntry(
            finishedAt = System.currentTimeMillis(),
            supplierName = roundCredentials?.first?.name.orEmpty(),
            model = row.model,
            candidateName = row.candidateName,
            familyName = row.familyName,
            probability = row.probability,
            usableAnswers = row.usableAnswers,
            submittedAnswers = row.submittedAnswers,
            error = row.error,
        )
        historyJob = viewModelScope.launch {
            withContext(ioDispatcher) {
                runCatching { store.append(listOf(entry)) }
                    .onSuccess { written ->
                        if (written) {
                            _uiState.update { it.copy(history = store.load()) }
                        }
                    }
            }
        }
    }

    /** Empties the history after the user confirms it. */
    fun clearHistory() {
        val store = historyStore ?: return
        historyJob = viewModelScope.launch {
            val cleared = withContext(ioDispatcher) { runCatching { store.clear() }.getOrDefault(false) }
            if (cleared) {
                _uiState.update { it.copy(history = emptyList()) }
            } else {
                showMessage("历史记录清空失败", isError = true)
            }
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
        // A row status change is what moves a model from "still being tested" to "settled",
        // and settling is exactly what makes its failed questions re-askable. Republish on
        // every write so the button appears the moment the model's own round part ends —
        // not when the whole batch does.
        publishRetryable()
    }

    private fun freshProgress(challenges: List<FingerprintChallenge>): List<ChallengeProgress> =
        challenges.map { challenge -> ChallengeProgress(challenge) }

    private suspend fun requestChallenge(
        index: Int,
        supplier: com.relaytester.app.core.model.SupplierProfile,
        apiKey: String,
        model: String,
        challenge: FingerprintChallenge,
        /** The whole round's slots, so a finished answer is cached in its own place. */
        slots: List<FingerprintChallenge>,
    ): ChallengeProgress {
        updateProgress(index) {
            it.copy(
                state = ChallengeState.REQUESTING,
                answer = "",
                error = null,
                receivedNumbers = 0,
            )
        }
        val result = try {
            relayApi.completeTextStreaming(
                profile = supplier,
                apiKey = apiKey,
                model = model,
                prompt = challenge.prompt,
                maxTokens = CHALLENGE_MAX_TOKENS,
                timeoutSeconds = CHALLENGE_TIMEOUT_SECONDS,
            ) { partial ->
                // Only the count is published, and only when it grew: re-counting the
                // same text would republish identical state several times a second and
                // recompose the whole card for nothing.
                val numbers = NumberFeatures.parseNumbers(partial).size
                updateProgress(index) { entry ->
                    if (entry.state != ChallengeState.REQUESTING || numbers <= entry.receivedNumbers) {
                        entry
                    } else {
                        entry.copy(receivedNumbers = numbers)
                    }
                }
            }
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
            cacheAnswer(model, index, slots, rejected)
            return rejected
        }
        val progress = when (result) {
            is ApiResult.Success -> {
                val numbers = NumberFeatures.parseNumbers(result.value).size
                val minimum = challengeFloor(challenge.expectedCount)
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
        cacheAnswer(model, index, slots, progress)
        return progress
    }

    /** Scores the manual answers; the same validity rule applies as for API answers. */
    fun runManualAnalysis() {
        if (runJob?.isActive == true) return
        if (refuseWhileBankJobRuns()) return
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
        // Called straight from a button rather than a coroutine, so a package that is not
        // installed yet has to be reported, not thrown.
        val bank = bankOrReport() ?: return null
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
        // The handle is deliberately kept: the cancelled job may still be finishing its
        // cleanup, and until it really completes a new round must not start, or the dying
        // one could write its finale over the new one's state.
        runJob?.cancel()
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
        // The rows that just settled are exactly the ones whose unfinished questions become
        // re-askable; without this the button would wait for an unrelated cache write.
        publishRetryable()
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

    /**
     * The state fields that describe the detection package in use.
     *
     * One place, so no caller can publish a package and forget to describe it.
     * [FingerprintUiState.minimumValidNumbers] is part of it: a downloaded package
     * carries its own floor, and keeping the old one would judge answers by the old
     * package's rule. When nothing is installed the identity fields are cleared, which
     * is what makes the panel show its empty state instead of a stale one.
     */
    private fun FingerprintUiState.withBank(result: BankLoadResult): FingerprintUiState = copy(
        bankSource = result.source,
        bankProblem = result.problem,
        bankUsedBackup = result.usedBackup,
        referenceBuiltAt = result.loaded?.identity?.builtAt.orEmpty(),
        bankSha256 = result.loaded?.identity?.sha256.orEmpty(),
        bankSizeBytes = result.loaded?.identity?.sizeBytes ?: result.installedBytes,
        modelCount = result.loaded?.identity?.modelCount ?: 0,
        bankModels = result.loaded?.bank?.let { bank ->
            bank.modelIds.indices.map { index ->
                BankModelInfo(bank.displayNames[index], bank.familyNames[index])
            }
        }.orEmpty(),
        minimumValidNumbers = result.loaded?.bank?.minimumValidNumbers ?: DEFAULT_MINIMUM_VALID_NUMBERS,
    )

    /**
     * Asks the release endpoint which detection package is published, and says what it
     * found.
     *
     * The button path: the user pressed "检查更新", so both the offer and the outcome are
     * reported — including "已是最新" and any failure.
     */
    fun checkBankUpdate() = startBankCheck(silent = false)

    /**
     * The automatic check: once when the app comes up, then every [bankCheckIntervalMillis]
     * for as long as the app stays open.
     *
     * Deliberately *not* tied to opening the panel. It used to run on every panel entry,
     * which meant switching between the three tabs re-asked the endpoint each time — the
     * card's "上次检查：…" moved with it, so the row looked like it reset itself. One check
     * per launch, timed from a *stored* timestamp, is what "只自动检测一次" means: without
     * the stored stamp every launch would be "the first check in a while" and closing the
     * app twice would be two requests.
     *
     * Starting the periodic check is part of coming up, not of this check succeeding: an app
     * launched with the switch off, or inside the window, still has to be watching for the
     * moment the window passes.
     *
     * @return true when a check was actually started.
     */
    suspend fun checkBankOnLaunch(): Boolean {
        // The check runs before the timer starts, never beside it: both read the same stored
        // timestamp, and a timer that started first would see the stale one too and race this
        // check for the same request. Started last, its first wake is a whole window away
        // because this check just wrote the timestamp it computes from.
        val started = runLaunchBankCheck()
        ensureBankAutoCheckTimer()
        return started
    }

    private suspend fun runLaunchBankCheck(): Boolean {
        val stored = withContext(ioDispatcher) { updatePreferences.read() }
        // Handed to the row before anything can return early: a launch that checks nothing
        // because the window has not passed still knows when the last check was, and the
        // stored stamp is the only thing that can say it.
        _uiState.update {
            it.copy(
                autoCheckBank = stored.autoCheckBank,
                bankEarlierCheckAtMillis = stored.lastBankCheckAt.takeIf { stamp -> stamp > 0 },
            )
        }
        // The switch is the whole point: off means this panel does not talk to the release
        // endpoint unless the user presses 「检查更新」.
        if (!stored.autoCheckBank) return false
        val since = clock() - stored.lastBankCheckAt
        if (stored.lastBankCheckAt > 0 && since < bankCheckIntervalMillis) return false
        // Remembered rather than dropped when the startup read is still running: on a fresh
        // install this check is what puts the download button on an empty card.
        entryCheckPending = true
        if (!startBankCheck(silent = true)) return false
        // Waited for, so the timer that starts next computes its first due time from a
        // timestamp this check has already written. Started beside it instead, that wake
        // could read the stale stamp, decide the check was due long ago, and ask the
        // endpoint a second time a minute later — two requests where the design is one.
        val attempt = bankUpdateJob
        attempt?.join()
        if (attempt?.isCancelled == true) {
            // Something claimed the slot while this check was dialing — the user pressed
            // 「检查更新」, or a round started. The endpoint *was* asked, so the window starts
            // from this attempt: without it the timer would begin with no reference at all,
            // read that as 「从没查过」, and ask again the moment it started. A takeover writes
            // its own, later stamp a moment after this one, and the later one wins.
            lastBankCheckAtMillis = clock()
            withContext(ioDispatcher) { updatePreferences.setLastBankCheckAt(clock()) }
        }
        return true
    }

    /**
     * The check the app runs by itself while it stays open.
     *
     * One loop for the activity-scoped view model, and each wake waits until the next check
     * is *due* — the remaining time computed from the stored timestamp — rather than a fixed
     * period. That is what keeps a manual check and this one from stacking: if the user
     * pressed 「检查更新」 two hours ago, this wakes in four, because the timestamp that check
     * wrote is also the one this reads.
     *
     * A wake that finds something else in flight steps back for a whole window instead of
     * retrying: the only way to make progress here is to wait, and retrying in a tight loop
     * would be a busy-wait on a disk read.
     */
    private fun ensureBankAutoCheckTimer() {
        if (bankTimerJob?.isActive == true) return
        bankTimerJob = viewModelScope.launch {
            while (true) {
                val stored = withContext(ioDispatcher) { updatePreferences.read() }
                if (!stored.autoCheckBank) {
                    // Still wakes on schedule while the switch is off, so turning it back on
                    // does not inherit a wait that started before it was turned off.
                    delay(bankCheckIntervalMillis)
                    continue
                }
                // `max` of the stored stamp and this process's own: the stored one is what
                // survives a restart, and the in-memory one is what keeps the loop honest if
                // that write never landed.
                val reference = maxOf(stored.lastBankCheckAt, lastBankCheckAtMillis)
                val dueAt = if (reference > 0) reference + bankCheckIntervalMillis else 0L
                val wait = dueAt - clock()
                if (wait > 0) {
                    // Not due yet: sleep towards it — never less than [MIN_WAKE_MILLIS],
                    // never more than a window — and then decide again from scratch rather
                    // than treating the wake itself as "due". The bounds can move the wake
                    // off the exact due time in either direction, and by then the stamp may
                    // have moved on (a check the user pressed, or this loop's own previous
                    // one landing late); deciding again is what keeps the loop's timing a
                    // property of the stamps rather than of when it happened to wake.
                    delay(wait.coerceIn(MIN_WAKE_MILLIS, bankCheckIntervalMillis))
                    continue
                }
                if (!startBankCheck(silent = true)) {
                    // A wake that finds the slot taken — a round in flight, the startup read
                    // still running, another check dialing — steps back for a whole window
                    // instead of retrying: the only way to make progress is to wait, and
                    // retrying in a tight loop would be a busy-wait on a disk read.
                    delay(bankCheckIntervalMillis)
                    continue
                }
                // Waited for, so the loop's next wake is computed from the timestamp this
                // check wrote: recomputing while it was still in flight would find the old
                // stamp, call the window "due", and sleep a whole extra window.
                bankUpdateJob?.join()
                // And then the same floor as above, for the case a completed check would
                // otherwise decide again immediately: if neither timestamp moved — the write
                // failed *and* the in-memory copy is gone — the arithmetic says "due" forever.
                // One iteration per minute is a visible mistake; without this it is a tight
                // loop that no assertion can even reach, because it never suspends.
                delay(MIN_WAKE_MILLIS)
            }
        }
    }

    /**
     * Turns the panel's automatic entry check on or off, and remembers the choice.
     *
     * The state flips first: the write is a disk hop, and a switch that waits for it would
     * look stuck. A failed write is not reported — the switch already moved, and the only
     * consequence is that the old choice comes back on the next launch.
     */
    fun setAutoCheckBank(enabled: Boolean) {
        _uiState.update { it.copy(autoCheckBank = enabled) }
        preferenceJob = viewModelScope.launch { updatePreferences.setAutoCheckBank(enabled) }
    }

    /** @return true when a check was launched, false when something else owned the slot. */
    private fun startBankCheck(silent: Boolean): Boolean {
        val state = _uiState.value
        // A round in flight wins: swapping the package underneath it would leave it scoring
        // with one package while its rows report a ranking from another.
        if (state.isRunning || state.isLoading || state.isInstallingBank) {
            // Said out loud: a button that quietly does nothing reads as a broken button.
            if (!silent) showMessage("检测正在进行，请稍候再试", isError = true)
            return false
        }
        if (state.isCheckingBankUpdate) return false
        // A second automatic check while the first one is still dialing: keep the job the
        // takeover path knows how to cancel instead of stranding it and opening a duplicate
        // request nobody owns. The check in flight already owes the offer, so nothing is
        // left pending either.
        if (silent && state.isCheckingBankInBackground) {
            entryCheckPending = false
            return false
        }
        if (!silent) standDownBackgroundCheck()
        entryCheckPending = false
        bankUpdateJob = viewModelScope.launch { checkBankUpdateNow(silent) }
        return true
    }

    /**
     * Stands the quiet entry check down so a job the user asked for can own the slot.
     *
     * Cancelling it loses nothing: its only output is the update offer, and the job that
     * replaces it computes that same offer. What it does buy is that the user never waits
     * on a request they did not make — and that a stale offer cannot be republished over a
     * package a newer job just installed or removed.
     */
    private fun standDownBackgroundCheck() {
        if (!_uiState.value.isCheckingBankInBackground) return
        bankUpdateJob?.cancel()
        bankUpdateJob = null
        _uiState.update { it.copy(isCheckingBankInBackground = false) }
    }

    /**
     * Runs an entry check that arrived before the panel was ready.
     *
     * Called wherever the startup read finishes; a no-op when no entry check is owed or
     * when something else claimed the bank slot in the meantime.
     */
    private fun replayPendingEntryCheck() {
        if (entryCheckPending) startBankCheck(silent = true)
    }

    /**
     * The check itself, callable from a job that is already running.
     *
     * Split out of [startBankCheck] for the removal path: it runs inside the job that
     * deleted the package, and launching a second job there would move [bankUpdateJob]
     * out from under whoever is waiting on it. [silent] suppresses the messages, not the
     * state: [FingerprintUiState.availableBankUpdate] is set either way.
     */
    private suspend fun checkBankUpdateNow(silent: Boolean = false) {
        _uiState.update {
            if (silent) it.copy(isCheckingBankInBackground = true) else it.copy(isCheckingBankUpdate = true)
        }
        try {
            val loaded = withContext(ioDispatcher) { bankStore.load() }.also { loadedBank = it.loaded }
            _uiState.update { it.withBank(loaded) }
            applyCheckResult(
                bankUpdateClient.check(loaded.loaded?.identity?.sha256, appVersionCode),
                silent = silent,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // A failed check is a finished check: the row has to show when it last ran and
            // that it failed, because the snackbar carrying the reason is transient.
            _uiState.update { it.copy(bankCheckedAtMillis = clock(), bankCheckFailed = true) }
            if (!silent) showMessage(error.message ?: "检查更新失败", isError = true)
        } finally {
            _uiState.update {
                if (silent) it.copy(isCheckingBankInBackground = false) else it.copy(isCheckingBankUpdate = false)
            }
        }
        // Recorded for every outcome: the throttle is about how often the endpoint is asked,
        // not about how the last answer went. A cancelled check never reaches this line, which
        // is what keeps "让位」 from counting as a check. The in-memory copy is written first,
        // so a failing write cannot leave the timer thinking this check never happened.
        lastBankCheckAtMillis = clock()
        withContext(ioDispatcher) { updatePreferences.setLastBankCheckAt(clock()) }
    }

    private fun applyCheckResult(check: BankUpdateCheck, silent: Boolean) {
        // Every branch here is a *finished* check, so when it ran and that it did not fail
        // are recorded once, above them, instead of in each one.
        _uiState.update { it.copy(bankCheckedAtMillis = clock(), bankCheckFailed = false) }
        when (check) {
            is BankUpdateCheck.Available -> {
                _uiState.update {
                    it.copy(availableBankUpdate = check.manifest, bankNeedsNewerApp = null)
                }
                if (!silent) {
                    showMessage(
                        "发现新的检测包：构建于 ${formatBankBuiltAt(check.manifest.builtAt)}，" +
                            "${check.manifest.modelCount} 个模型",
                        isError = false,
                    )
                }
            }

            BankUpdateCheck.UpToDate -> {
                _uiState.update {
                    it.copy(availableBankUpdate = null, bankNeedsNewerApp = null)
                }
                if (!silent) showMessage("检测包已是最新", isError = false)
            }

            is BankUpdateCheck.NeedsNewerApp -> {
                _uiState.update {
                    it.copy(
                        availableBankUpdate = null,
                        bankNeedsNewerApp = check.requiredVersionCode,
                    )
                }
                if (!silent) {
                    showMessage("线上检测包需要更新版本的 App 才能使用", isError = true)
                }
            }
        }
    }

    /** Downloads the detection package a check found and makes it the one the panel scores with. */
    fun installBankUpdate() {
        val manifest = _uiState.value.availableBankUpdate ?: return
        if (bankUpdateBusy()) return
        // Before the field is reassigned: an entry check left running could republish the
        // offer for the very package this install is about to make current.
        standDownBackgroundCheck()
        bankUpdateJob = viewModelScope.launch {
            _uiState.update { it.copy(isInstallingBank = true, bankDownloadProgress = null) }
            try {
                // Progress is reported from the fetch itself: a 3 MB body arrives in dozens
                // of chunks, and the window between "pressed" and "installed" is otherwise
                // silent for as long as the connection is slow.
                val bytes = bankUpdateClient.download(manifest) { progress ->
                    _uiState.update { it.copy(bankDownloadProgress = progress) }
                }
                when (val result = withContext(ioDispatcher) { bankStore.install(bytes) }) {
                    is BankInstallResult.Installed -> {
                        val loaded = withContext(ioDispatcher) { bankStore.load() }
                        loadedBank = loaded.loaded
                        _uiState.update { it.withBank(loaded).copy(availableBankUpdate = null) }
                        showMessage(
                            "检测包已安装：构建于 ${result.identity.builtAt}，" +
                                "${result.identity.modelCount} 个模型",
                            isError = false,
                        )
                    }

                    is BankInstallResult.Rejected -> showMessage(result.reason, isError = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showMessage(error.message ?: "安装检测包失败", isError = true)
            } finally {
                // Cleared here rather than after the download: a bar left at 62% next to a
                // failure message would read as a download still running.
                _uiState.update { it.copy(isInstallingBank = false, bankDownloadProgress = null) }
            }
        }
    }

    /**
     * Deletes the installed detection package, returning the panel to its empty state.
     *
     * Nothing else can produce that state, so it is also the way out of a file that no
     * longer parses: the download that follows starts from a clean slate.
     *
     * A removal that succeeded is followed by a check, so the download the empty card
     * advertises is actually offered. It is the silent kind: the removal's own message
     * ("已删除检测包…") is the one the user needs to read, and the offer it produces
     * shows up on the card as a button rather than as a second snackbar.
     */
    fun removeInstalledPackage() {
        if (bankUpdateBusy()) return
        // Same reason as the install: the removal's own trailing check is the one whose
        // result should stand.
        standDownBackgroundCheck()
        bankUpdateJob = viewModelScope.launch {
            _uiState.update { it.copy(isInstallingBank = true) }
            var removed = false
            try {
                val result = withContext(ioDispatcher) { bankStore.discardInstalled() }
                val loaded = withContext(ioDispatcher) { bankStore.load() }
                loadedBank = loaded.loaded
                _uiState.update { it.withBank(loaded) }
                when (result) {
                    BankDiscardResult.Failed -> showMessage("无法删除已安装的检测包", isError = true)
                    BankDiscardResult.NothingInstalled ->
                        showMessage("当前没有已安装的检测包", isError = false)

                    BankDiscardResult.Removed -> {
                        removed = true
                        showMessage(
                            "已删除检测包，检测功能需要重新下载才能使用",
                            isError = false,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showMessage(error.message ?: "删除检测包失败", isError = true)
            } finally {
                _uiState.update { it.copy(isInstallingBank = false) }
            }
            // After the flag is cleared: the check refuses to start while one is set.
            if (removed) checkBankUpdateNow(silent = true)
        }
    }

    /**
     * True when a package swap is in flight, so a round must not start.
     *
     * A round resolves the package once per model through [bankOrReport]; if an install
     * swapped the package underneath it, the rows of one batch would carry rankings and
     * reference stamps from two different packages. The reverse direction is already
     * covered — [bankUpdateBusy] refuses to start an install during a round — so refusing
     * here closes the pair.
     *
     * Only the swap counts. A check in flight — the entry one or one the user pressed —
     * writes nothing but the offer, and a detection must not be held up by it: the entry
     * check stands down instead, which is the whole point of its own busy flag.
     */
    private fun refuseWhileBankJobRuns(): Boolean {
        if (_uiState.value.isInstallingBank) {
            showMessage("检测包正在更新，请稍候再试", isError = true)
            return true
        }
        standDownBackgroundCheck()
        return false
    }

    /**
     * True when the panel must not start another bank job.
     *
     * A round in flight wins: swapping the bank underneath it would leave it scoring
     * with one bank while the rows report a ranking from another. The quiet entry check is
     * deliberately not part of this: a manual check, an install and a removal all stand it
     * down and then run, so it cannot make the user wait for something they did not ask
     * for.
     */
    private fun bankUpdateBusy(): Boolean {
        val state = _uiState.value
        return state.isRunning || state.isLoading ||
            state.isCheckingBankUpdate || state.isInstallingBank
    }

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

        /**
         * Floor an answer has to clear when no detection package supplies its own.
         *
         * Same value upstream defaults `minimum_valid_numbers` to; a package overrides
         * it, and this is only used before one is installed.
         */
        const val DEFAULT_MINIMUM_VALID_NUMBERS = 80

        /**
         * The shortest gap between two wakes of the periodic package check.
         *
         * Far below the six-hour window, so it never delays a check anyone was waiting for;
         * its job is to bound the damage if some path ever computes a due time in the past —
         * without it that is a tight loop against the feed rather than a slow one.
         */
        private const val MIN_WAKE_MILLIS = 60_000L

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
                    bankStore = FingerprintBankStore(AndroidBankFileSystem(applicationContext)),
                    bankUpdateClient = BankUpdateClient(fetcher = OkHttpFetcher()),
                    updatePreferences = UpdatePreferences(applicationContext),
                    appVersionCode = installedVersionCode(applicationContext),
                    historyStore = FingerprintHistoryStore(AndroidHistoryFileSystem(applicationContext)),
                ) as T
            }
        }

        /**
         * The installed version code.
         *
         * This project switches BuildConfig off, so it comes from the package manager;
         * a manifest that demands a newer app is compared against this.
         */
        @Suppress("DEPRECATION")
        private fun installedVersionCode(context: Context): Long = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(info)
        } catch (error: Exception) {
            // Unreadable means "old": a published bank that demands a newer app is then
            // refused rather than installed on a guess.
            0L
        }
    }
}
