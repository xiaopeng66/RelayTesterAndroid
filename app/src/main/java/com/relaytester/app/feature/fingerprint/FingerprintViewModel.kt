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
import com.relaytester.app.core.model.isTransient
import com.relaytester.app.core.network.NO_ANSWER_TEXT_MESSAGE
import com.relaytester.app.core.network.RelayApi
import com.relaytester.app.core.network.isMissingAnswerText
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
    /**
     * True from the second attempt on: this entry is an automatic re-ask, not a first send.
     *
     * 一次空白回答什么都结不了，于是同一题被再问一次（见 [CHALLENGE_MAX_ATTEMPTS]）。格子里的
     * 状态词靠它把「接收中…」换成「重试中…」：没有这一位，第二次等待和同一题卡住长得一模一样，
     * 而它其实要再花一次请求。第一次尝试永远是 false，结题时两个写入者都用新条目覆盖它，所以
     * 不需要自己清除。
     *
     * 批量里已经结题的行读的是自己的槽位缓存，那一行同样看不见活着的整数计数（见
     * [FingerprintUiState.modelSlots]），这一位按同一条规则只出现在「正在被问的那个模型」的
     * 卡上。
     */
    val retrying: Boolean = false,
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
    /**
     * The animation panel's state, entirely separate from the round above.
     *
     * A failed or ugly animation never touches answers, the retry list, the analysis or the
     * history; the two lifecycles only share the supplier a card was opened for.
     */
    val animation: AnimationUiState = AnimationUiState(),
) {
    /** True when the pending round covers several models, so its output reads as a list. */
    val isBatch: Boolean get() = selectedModels.size > 1
}

@Immutable
data class SupplierOption(
    val id: String,
    val name: String,
    /**
     * True when the supplier is marked 仅拉取模型.
     *
     * Carried into the list so the pickers can say so *before* the user picks it: the flag
     * now refuses a fingerprint round and an animation request, and a supplier that looks
     * ordinary until the button rejects it would read as a bug rather than as the setting
     * the user himself turned on.
     */
    val pullOnly: Boolean = false,
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
    /**
     * The wait before an automatic animation retry.
     *
     * Injected for the same reason [clock] and [ioDispatcher] are: the real wait is seconds of
     * wall time, and a test that had to sit through it — or that deadlocked against a virtual
     * clock nobody advances — would either be slow or hang. Tests pass an immediate no-op, and
     * the cancel-during-retry case passes one it can hold open.
     */
    private val animationRetryDelay: suspend (Long) -> Unit = { delay(it) },
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

    // Everything mutable below — this handle, retryJobs, roundAnswers, roundCredentials,
    // entryCheckPending — is touched only from coroutines started by `viewModelScope.launch`
    // with no dispatcher (the main thread) and from completion handlers of those same jobs,
    // which the platform also runs on the caller's thread. Slow work is pushed into
    // `withContext(ioDispatcher)` and nothing is assigned inside it; the only cross-thread
    // publication is `_uiState`, a MutableStateFlow. A `launch(ioDispatcher)` added here
    // would break that, so it has to bring its own confinement with it.
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
     * The round the retries and cached answers belong to, bumped on every context
     * replacement.
     *
     * A retry captures this number when it is queued. Every write it makes afterwards —
     * slot placeholders, cached answers, re-scores, history rows — happens only while the
     * number still matches, so a request that returns after 重新出题、切供应商、切模式、
     * 改勾选、新主轮或取消 can no longer claim a place in the round now on screen.
     * Cancelling the Job alone cannot give this guarantee: a cancelled request's cleanup
     * still runs, and a *completed* request the panel never cancelled does not even stop.
     */
    private var roundEpoch = 0

    /**
     * Invalidates the current round identity and stops every retry still in flight.
     *
     * Callers follow with whatever cleanup the replacement needs (answers, credentials,
     * rows). The queued-retry marks are cleared here because their owners just lost the
     * gate they were waiting on; the completion callbacks observe their own entry already
     * gone and leave the flag alone.
     */
    private fun invalidateRound() {
        roundEpoch++
        val jobs = retryJobs.values.toList()
        retryJobs.clear()
        jobs.forEach { it.cancel() }
        if (_uiState.value.queuedRetries.isNotEmpty()) {
            _uiState.update { it.copy(queuedRetries = emptySet()) }
        }
    }

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
     *
     * The handle is cleared by the job itself when it finishes (see [runApiDetection]), so this
     * reads null once the round — cancelled or not — has actually ended. Without that, the
     * handle stayed non-null forever and `isCompleted == false` could never become true
     * again, which left the panel permanently unable to start another round: the reported
     * 「取消以后无法再次开始」.
     */
    private fun runInFlight(): Boolean = _uiState.value.isRunning || runJob != null

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

    /** The tail of the history command chain, tracked so tests can join it. */
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
        // The animation generations are requests of their own, started from a card rather
        // than from the round: a test that closed a panel mid-flight would otherwise finish
        // while those requests were still on the wire. 批量测试 means several can run at
        // once, so every one of them is joined. Snapshot first: a joined job removes itself
        // from the map (same rule as retryJobs below). 只 join 未完成的作业——已完成却还在
        // map 里的残渣会让 join 立即返回、下一轮快照又捞到它，循环变成空转烧 CPU。
        while (true) {
            val pending = animationJobs.values.filter { it.isCompleted == false }
            if (pending.isEmpty()) break
            pending.forEach { it.join() }
        }
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
                        SupplierOption(item.id, item.name, item.isTestingDisabled)
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
                // A refresh that lands on a different supplier replaces the round's
                // context exactly like 切供应商 does: the old round's credentials and
                // questions cannot describe what is on screen now. The check runs
                // outside `_uiState.update` — the invalidation cancels jobs and bumps a
                // counter, and an update lambda may be retried under contention.
                val changed = candidateSupplierId(_uiState.value.selectedSupplierId, storeState) !=
                    _uiState.value.selectedSupplierId
                if (changed) dropRoundContext()
                _uiState.update { state ->
                    val supplierId = candidateSupplierId(state.selectedSupplierId, storeState)
                    val changed = supplierId != state.selectedSupplierId
                    state.withBank(bank).copy(
                        // A retry that succeeds has to lift the error screen, or a
                        // transient startup failure would strand the panel for good.
                        isLoading = false,
                        loadError = null,
                        suppliers = storeState.suppliers.map { item ->
                            SupplierOption(item.id, item.name, item.isTestingDisabled)
                        },
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
     * has already moved away from. The invalidation runs first — an in-flight retry that
     * returns during this cleanup must find its round already gone.
     */
    private fun dropRoundContext() {
        invalidateRound()
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
        // A retry may run *beside* a live round — its model has already settled, so re-asking
        // it must not wait for the round to finish. The one thing it must not do is slip in
        // while a round that is no longer publishing `isRunning` still has work on the wire:
        // that window is the round's startup, before the flag is set, and starting there would
        // put two rounds' requests on the same credentials.
        //
        // This deliberately reads the handle rather than [runInFlight]: that predicate also
        // treats a *running* round as a block, which would stop every retry the panel is
        // designed to allow beside it. The handle is safe to read now because it clears
        // itself when the round ends (see [runApiDetection]), so the cancelled-round case that
        // used to lock retries out for good no longer leaves a stale handle behind.
        if (!state.isRunning && runJob != null) return
        val failed = (models ?: state.retryableModels[index].orEmpty())
            .filter { candidate -> state.batchResults.any { it.model == candidate } }
        if (failed.isEmpty()) return
        // A fingerprint round owns its own state and may overlap animation sessions.
        if (refuseWhileBankJobRuns()) return
        if (bankOrReport() == null) return

        val template = state.progress.map { it.challenge }
        val challenge = template[index]
        // The round identity is fixed when the retry is queued. Everything the request
        // writes later — placeholders, cache entries, the re-score, history — is checked
        // against this number, so a context replacement (重新出题、切站、切模式、改勾选、
        // 新主轮、取消、目录刷新换供应商) invalidates the request wherever it is.
        val epoch = roundEpoch
        val fresh = failed.filter { (it to index) !in retryJobs }
        if (fresh.isEmpty()) return
        for (model in fresh) {
            val job = viewModelScope.launch {
                retrySlot(index, model, challenge, template, credentials, epoch)
            }
            retryJobs[model to index] = job
            job.invokeOnCompletion {
                // Only the job that still owns its registration may remove it: after a
                // context replacement cleared the map, a same-key retry may already be
                // registered, and this callback must not delete the newcomer's entry or
                // clear its queued mark.
                if (retryJobs[model to index] === job) {
                    retryJobs.remove(model to index)
                    // A job cancelled while it was still waiting for the gate never
                    // reaches the clearing inside retrySlot, and a stale 排队中 would
                    // outlive its request.
                    markRetryQueued(model, index, queued = false)
                }
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
        epoch: Int,
    ) {
        // The round was replaced between queueing and dispatch: the slot this retry was
        // aimed at no longer exists, so it writes nothing at all.
        if (epoch != roundEpoch) return
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
                stillCurrent = { epoch == roundEpoch },
            )
        }
        // The context was replaced while the request was out — a late answer from a round
        // the panel already left. It owns nothing now: no progress, no cache entry, no
        // re-score, no history. Dropping the value (instead of only the writes) is what
        // keeps an invalidated retry from re-opening the rows it used to belong to.
        if (epoch != roundEpoch) return
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
        // A fingerprint round owns its own state and may overlap animation sessions.
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

        val job = viewModelScope.launch {
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
        runJob = job
        // Released when the round really ends — completed, failed or cancelled — because
        // [runInFlight] reads this handle as the panel's lock. Registering it here instead of
        // clearing it inside the coroutine body matters: `runJob = launch {}` assigns the
        // handle only after the body has already started, so a `finally` in the body could
        // read a stale (or not yet assigned) handle and skip the clear, leaving the panel
        // locked forever — which is the reported 「取消以后无法再次开始」.
        //
        // The identity check keeps a round that was replaced by a newer one from unlocking
        // the newer one's slot.
        job.invokeOnCompletion { if (runJob === job) runJob = null }
    }

    private suspend fun runRounds(
        supplierId: String,
        models: List<String>,
        challenges: List<FingerprintChallenge>,
    ) {
        // A new round replaces the context the old single-question retries belong to:
        // their answers would land in this round's cache, rows and history. Invalidate
        // first, then park (the gate wait below runs under a fresh identity).
        invalidateRound()
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
        // 禁止测试：只拉模型，不发测试请求。 Checked against the supplier just read from the
        // store rather than the cached list the panel renders, for the reason in
        // [refuseIfPullOnly]. Before `isRunning = true` so a refusal leaves the panel idle
        // rather than looking busy.
        if (refuseIfPullOnly(supplier)) return
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
     * Chains a history write onto the previous one, in the order the calls were made.
     *
     * append and clear each used to launch their own job and overwrite [historyJob]:
     * two writes then hit the store's IO in whichever order the file lock granted them,
     * so a clear could land before the append the user issued first and the emptied
     * list grew the row right back — on disk as well as on screen, since the UI refresh
     * rode the same unordered write. Enqueueing happens here, at call time on the main
     * thread, so the order is fixed before any IO dispatcher gets a vote. Joining the
     * tail also gives [awaitIdle] every earlier operation for free: a job does not start
     * (let alone finish) until its predecessor has.
     */
    private fun enqueueHistoryOperation(block: suspend () -> Unit): Job {
        val previous = historyJob
        return viewModelScope.launch {
            previous?.join()
            block()
        }
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
        historyJob = enqueueHistoryOperation {
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
        historyJob = enqueueHistoryOperation {
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

    // ---- Animation test (R10) --------------------------------------------
    //
    // A separate feature that shares nothing with the round above except the card it was
    // opened from: its own state, its own job, its own request identity. Every write below
    // is guarded by the session and request ids, because cancelling the job cannot stop a
    // callback that is already past its last suspension point.

    /** Identifies the open panel; bumped on every open so a reopened panel is a new one. */
    private var animationSessionSequence = 0L

    /** Identifies the in-flight generation; bumped on every submit and every invalidation. */
    private var animationRequestSequence = 0L

    /**
     * The generation in flight per session.
     *
     * 批量测试（同供应商多选）和「一个供应商在测、再开另一个供应商的测试」都要求几个请求
     * 并行，所以作业按会话各挂各的；归属校验按会话自己的 requestId 走，互不串写。作业
     * 结束时由 invokeOnCompletion 自行摘除，map 里不留完成态的残渣。
     */
    private val animationJobs = mutableMapOf<Long, Job>()

    /**
     * The last draft, kept for the life of the view model.
     *
     * The requirement is "kept for this page session": a rotation recreates the composable
     * but not the view model, so holding it here is what makes the draft survive a rotate
     * and a close/reopen without writing it to disk or into the detection history.
     */
    private var animationDraft = DEFAULT_ANIMATION_PROMPT

    /**
     * Opens the card on its list view.
     *
     * Deliberately does nothing else: opening is not creating a test. The card's pickers
     * choose a supplier and model, and [startAnimationTest] is what makes a session.
     *
     * 选中的记录在这里一并清掉，入口因此永远落在「选模型 + 测试记录」那一层：卡片是两层
     * 弹窗，如果上次看到一半的记录还选着，点入口就会直接弹进那条记录的详情，而用户想点的
     * 是列表——尤其是他刚生成完、正想再开一条的时候。
     */
    fun openAnimationPanel() {
        // 目录要现读一次，不能等指纹页那次：入口在模型测试页，用户完全可能一次都没进过
        // 指纹页就点进来（「先拉模型，再直接生成动画」是最顺的一条路）。那时 knownSuppliers
        // 还是空的，卡片会显示「还没有可用的供应商」，而供应商列表就在它背后那一页上——
        // 弹窗里说没有、背后明明有，是这一处最糟的形态。
        refreshCatalogue()
        _uiState.update {
            it.copy(
                animation = it.animation.copy(panelOpen = true, selectedSessionId = null),
            )
        }
    }

    /**
     * Hides the card, leaving every test exactly as it is.
     *
     * Closing is not cancelling: a generation keeps running so the user can get on with
     * something else, and the entry button marks that it is still going. Cancelling is
     * [cancelAnimationGeneration], and discarding a record is [removeAnimationSession].
     */
    fun closeAnimationPanel() {
        _uiState.update { it.copy(animation = it.animation.copy(panelOpen = false)) }
    }

    /**
     * Opens the card on a test for one supplier and model, sending nothing yet.
     *
     * The one-call form of "open, then start": it exists because the fingerprint card used
     * to open a panel already bound to its own supplier and model, and that entry point
     * still has to mean the same thing while the card's own pickers are the new way in.
     */
    fun openAnimationTest(supplierId: String, model: String) {
        openAnimationPanel()
        startAnimationTest(supplierId, model, autoStart = false)
    }

    /**
     * Chooses the supplier the card's next animation test runs against.
     *
     * Its own field, not the detection panel's: the animation card and the three-question
     * panel are separate screens that happen to share a view model, and driving one from
     * the other's selection would reset ticked models and clear results on the other tab.
     */
    fun selectAnimationSupplier(supplierId: String) {
        val models = knownSuppliers.firstOrNull { it.id == supplierId }?.models.orEmpty()
        _uiState.update {
            it.copy(
                animation = it.animation.copy(
                    supplierId = supplierId,
                    model = null,
                    models = models,
                    // 批量勾选只在本供应商目录里有效：换供应商即整体作废，勾选永远指向
                    // 当前目录里真实存在的模型。
                    selectedModels = emptyList(),
                ),
            )
        }
    }

    /** Shows one record's detail view; null returns to the list. */
    fun selectAnimationSession(sessionId: Long?) {
        _uiState.update { state ->
            if (sessionId != null && state.animation.sessions.none { it.sessionId == sessionId }) {
                state
            } else {
                state.copy(animation = state.animation.copy(selectedSessionId = sessionId))
            }
        }
    }

    /**
     * Creates a session for one supplier and model, selects it and (by default) starts it.
     *
     * Reuses an existing record for the same supplier and model when there is one: pressing
     * 开始生成 again is a re-run of that test, not a second row saying the same thing.
     */
    fun startAnimationTest(
        supplierId: String,
        model: String,
        autoStart: Boolean = true,
    ) {
        val supplierName = resolveAnimationSupplierName(supplierId)
        if (supplierName == null) {
            showMessage("该供应商已不存在，无法进行动画测试", isError = true)
            return
        }
        if (model.isBlank()) {
            showMessage("请先选择一个模型", isError = true)
            return
        }
        val existing = _uiState.value.animation.sessions
            .firstOrNull { it.supplierId == supplierId && it.model == model }
        if (existing != null) {
            selectAnimationSession(existing.sessionId)
        } else {
            animationSessionSequence++
            val created = AnimationSession(
                sessionId = animationSessionSequence,
                createdAt = clock(),
                supplierId = supplierId,
                supplierName = supplierName,
                model = model,
                draftPrompt = animationDraft,
            )
            _uiState.update { state ->
                state.copy(
                    animation = state.animation.copy(
                        selectedSessionId = created.sessionId,
                        sessions = trimAnimationSessions(
                            listOf(created) + state.animation.sessions,
                            keep = created.sessionId,
                        ),
                    ),
                )
            }
        }
        if (autoStart) startAnimationGeneration()
    }

    /**
     * Discards the selected test and closes the card.
     *
     * The single-call form the fingerprint card's 关闭 used to be, where closing meant
     * dropping what the card was showing. The card's own 关闭 is [closeAnimationPanel],
     * which keeps the test running — this is the explicit "throw it away" path.
     */
    fun closeAnimationTest() {
        _uiState.value.animation.selectedSessionId?.let { removeAnimationSession(it) }
        closeAnimationPanel()
    }

    /**
     * Drops one record, cancelling it first if it is the one generating.
     *
     * The request id is invalidated rather than only the job cancelled, so a callback
     * already on its way cannot write into a record that no longer exists.
     */
    fun removeAnimationSession(sessionId: Long) {
        val target = _uiState.value.animation.sessions.firstOrNull { it.sessionId == sessionId }
        if (target?.phase == AnimationPhase.GENERATING) {
            cancelAnimationJob(sessionId)
        }
        _uiState.update { state ->
            val animation = state.animation
            val remaining = animation.sessions.filterNot { it.sessionId == sessionId }
            if (remaining.size == animation.sessions.size) {
                state
            } else {
                state.copy(
                    animation = animation.copy(
                        sessions = remaining,
                        selectedSessionId = animation.selectedSessionId
                            .takeIf { id -> remaining.any { it.sessionId == id } },
                    ),
                )
            }
        }
    }

    /** The display name for a supplier id, from the list the pickers are drawn from. */
    private fun resolveAnimationSupplierName(supplierId: String): String? =
        knownSuppliers.firstOrNull { it.id == supplierId }?.name
            ?: _uiState.value.suppliers.firstOrNull { it.id == supplierId }?.name

    /**
     * Applies [transform] to one session in the list, leaving the rest untouched.
     *
     * Every write into a session goes through here. Building an [AnimationUiState] from a
     * single captured session — the shape this replaced — would silently drop the other
     * records the moment a second test existed, so the list is only ever rebuilt as a
     * whole in one place.
     */
    private fun updateAnimationSession(
        sessionId: Long,
        transform: (AnimationSession) -> AnimationSession,
    ): Boolean {
        var applied = false
        _uiState.update { state ->
            val animation = state.animation
            if (animation.sessions.none { it.sessionId == sessionId }) return@update state
            applied = true
            state.copy(
                animation = animation.copy(
                    sessions = animation.sessions.map { session ->
                        if (session.sessionId == sessionId) transform(session) else session
                    },
                ),
            )
        }
        return applied
    }

    /**
     * Applies [transform] to the session the card is showing.
     *
     * The card's own actions (draft, view, cancel) act on what the user is looking at, and
     * a card with nothing selected has nothing to act on.
     */
    private fun updateSelectedAnimationSession(
        transform: (AnimationSession) -> AnimationSession,
    ): Boolean {
        val selected = _uiState.value.animation.selectedSessionId ?: return false
        return updateAnimationSession(selected, transform)
    }

    /** The editable prompt. Only the draft moves; a request already sent is unaffected. */
    fun updateAnimationPrompt(value: String) {
        animationDraft = value
        updateSelectedAnimationSession { it.copy(draftPrompt = value) }
    }

    /** Restores the default prompt. Resets the draft only: no request, no preview change. */
    fun restoreDefaultAnimationPrompt() {
        updateAnimationPrompt(DEFAULT_ANIMATION_PROMPT)
    }

    /** Switches between the rendered preview and its source text. */
    fun showAnimationView(view: AnimationView) {
        updateSelectedAnimationSession { it.copy(view = view) }
    }

    /**
     * Records whether the reader has scrolled the reasoning box back from the newest line.
     *
     * The panel owns the gesture and this only carries the decision to the one place that can
     * act on it: the growth path. While held, the reasoning window must stop trimming, because
     * a `takeLast` window slides as it grows and slides the text out from under a fixed scroll
     * offset. See [AnimationSession.reasoningHeld].
     *
     * Releasing trims at once rather than waiting for the next fragment: the reader is back at
     * the newest line, so the box should already be bounded again — and the next fragment may
     * be minutes away on a slow chain, which is exactly when an unbounded box would be noticed.
     */
    fun holdAnimationReasoning(held: Boolean) {
        updateSelectedAnimationSession { session ->
            if (session.reasoningHeld == held) {
                session
            } else {
                session.copy(
                    reasoningHeld = held,
                    reasoningText = if (held) {
                        session.reasoningText
                    } else {
                        session.reasoningText?.takeLast(ANIMATION_STREAM_TAIL_CHARS)
                    },
                )
            }
        }
    }

    /**
     * Reloads the stored HTML without calling the model.
     *
     * The whole point of "重新播放" is that it is free: it only re-runs a document that is
     * already on screen, so it must never touch the request path or the API.
     */
    fun replayAnimation() {
        updateSelectedAnimationSession { session ->
            // Nothing to replay without a document: the counter would only ask the preview
            // to reload something that is not there.
            if (session.html == null) session else session.copy(replayToken = session.replayToken + 1)
        }
    }

    /** The panel reports a render failure; the preview shows it where the user is looking. */
    fun reportAnimationRenderFailure(reason: String) {
        updateSelectedAnimationSession {
            it.copy(
                phase = AnimationPhase.FAILED,
                error = "页面渲染失败：$reason",
                // The HTML is kept: a render failure is not a reason to throw away a
                // document the user may still want to read as source.
            )
        }
    }

    /**
     * Stores the rendered frame the detail view captured, so the list can show it.
     *
     * Addressed by session id rather than "the selected one": the capture is asynchronous and
     * the user may have moved to another record (or deleted this one) before it lands. Only a
     * settled success owns a picture — a generation in flight would otherwise attach a frame of
     * the *previous* document to the new run.
     */
    fun saveAnimationSnapshot(sessionId: Long, png: ByteArray) {
        updateAnimationSession(sessionId) { current ->
            if (current.phase != AnimationPhase.READY) current else current.copy(snapshot = png)
        }
    }

    /**
     * Caps the record list, dropping the oldest entries.
     *
     * Each record holds its whole document ([ANIMATION_MAX_HTML_CHARS] is 2 MB), the tail of its
     * reasoning and now a PNG of the result, and nothing ever removed one except the user's own
     * delete — so a long session of generating animations grew the process without limit. The
     * list is a way back to recent work, not an archive: the newest [ANIMATION_MAX_SESSIONS] are
     * kept and the oldest fall off, which is the same shape the detection history already uses
     * (50 entries) and the balance of "still findable" against "not unbounded".
     *
     * [keep] is never dropped even when it is the oldest: the user may have that record open,
     * and evicting what is on screen would blank the panel on the next frame. When [keep] is not
     * among the newest, it is carried alongside them, so the list can hold one more than the cap
     * in that one case — an open record is worth one extra slot.
     */
    private fun trimAnimationSessions(
        sessions: List<AnimationSession>,
        keep: Long?,
    ): List<AnimationSession> {
        if (sessions.size <= ANIMATION_MAX_SESSIONS) return sessions
        val head = sessions.take(ANIMATION_MAX_SESSIONS).toMutableList()
        if (keep != null && head.none { it.sessionId == keep }) {
            sessions.firstOrNull { it.sessionId == keep }?.let { kept ->
                head[head.lastIndex] = kept
            }
        }
        return head
    }

    /** Sends the selected session's draft as one animation request. */
    fun startAnimationGeneration() {
        val session = _uiState.value.animation.selected ?: return
        launchAnimationGeneration(session)
    }

    /**
     * Ticks or unticks one model for the next 开始生成.
     *
     * 勾选只在本供应商的目录里有效（勾选状态在换供应商时整体清掉）。单选的 [model] 跟随
     * 勾选集合：始终是目录顺序里第一个被勾中的模型，一个都没勾时为空——界面上的
     * 「开始生成」一律按整个勾选集合启动，所以选的顺序不影响谁先跑（目录顺序为准）。
     */
    fun toggleAnimationModel(model: String) {
        _uiState.update { state ->
            val animation = state.animation
            if (!animation.models.contains(model)) return@update state
            val ticked = if (animation.selectedModels.contains(model)) {
                animation.selectedModels - model
            } else {
                animation.selectedModels + model
            }
            state.copy(
                animation = animation.copy(
                    selectedModels = ticked,
                    model = animation.models.firstOrNull { ticked.contains(it) },
                ),
            )
        }
    }

    /** Starts one independent generation for every checked model in catalogue order. */
    fun startSelectedAnimationTests(supplierId: String) {
        // Validate the supplier before touching anything: clearing the ticks and then reporting
        // "该供应商已不存在" would make the user re-tick every model to retry. The single-test
        // path ([startAnimationTest]) applies the same check.
        if (resolveAnimationSupplierName(supplierId) == null) {
            showMessage("该供应商已不存在，无法进行动画测试", isError = true)
            return
        }
        val animation = _uiState.value.animation
        val ticked = animation.models.filter { it in animation.selectedModels }
        if (ticked.isEmpty()) {
            showMessage("请先勾选要测试的模型", isError = true)
            return
        }
        ticked.forEach { model -> startAnimationTest(supplierId, model, autoStart = true) }
        val first = _uiState.value.animation.sessions
            .firstOrNull { it.supplierId == supplierId && it.model == ticked.first() }
        first?.sessionId?.let(::selectAnimationSession)
        _uiState.update { state ->
            state.copy(animation = state.animation.copy(selectedModels = emptyList()))
        }
    }

    private fun launchAnimationGeneration(session: AnimationSession) {
        if (session.phase == AnimationPhase.GENERATING) return
        val prompt = session.draftPrompt.trim()
        if (prompt.isEmpty()) {
            updateAnimationSession(session.sessionId) {
                it.copy(error = "提示词不能为空，请先填写再生成")
            }
            return
        }
        // A pasted megabyte is an accident, not a longer question: refuse it here, where the
        // blank check already lives, rather than spending the budget and the session's memory
        // on it (see [ANIMATION_MAX_PROMPT_CHARS]).
        if (prompt.length > ANIMATION_MAX_PROMPT_CHARS) {
            updateAnimationSession(session.sessionId) {
                it.copy(
                    error = "提示词最长 $ANIMATION_MAX_PROMPT_CHARS 个字符" +
                        "（当前 ${prompt.length}），请删减后再生成",
                )
            }
            return
        }
        // Animation and fingerprint detection have independent sessions and may run together.
        val sessionId = session.sessionId
        // 动画测试之间现在互相并行（批量、跨供应商都是用户明确要的），新会话不再取代正在
        // 跑的旧会话；归属仍由每个会话自己的 requestId 把关，回答只写进自己的会话。
        animationRequestSequence++
        val requestId = animationRequestSequence
        updateAnimationSession(sessionId) {
            it.copy(
                phase = AnimationPhase.GENERATING,
                submittedPrompt = prompt,
                requestId = requestId,
                error = null,
                // The previous attempt's counters go with it: leaving them would show the
                // new run starting at the old run's character count.
                streamingText = null,
                receivedChars = 0,
                reasoningText = null,
                reasoningChars = 0,
                startedAt = clock(),
                settledAt = null,
                // A fresh generation starts on the preview side so the result lands
                // where the user is looking; the old HTML stays until it is replaced.
                view = AnimationView.PREVIEW,
                // This is the user's own attempt, not a retry of one: the counter restarts
                // and any notice from the previous generation goes with it.
                attempt = 1,
                retryNotice = null,
                // The old HTML deliberately stays (a regeneration that fails must leave the
                // previous result readable), but its picture must not: the row shows the
                // snapshot, and keeping it would attribute the old frame to the run now
                // starting. A fresh capture replaces it once this document renders.
                snapshot = null,
            )
        }
        // 顺序不能反：Unconfined 调度下（单测）协程会在 launch 里同步跑完，若先注册
        // invokeOnCompletion 再放进 map，回调触发时条目还没进去，remove 是空操作，完成
        // 态的作业永远留在 map 里，awaitIdle 的 join 循环就会空转。先入册、后挂回调，
        // 回调里再校验身份，防止误删同一会话后来的新作业。
        val job = viewModelScope.launch {
            generateAnimation(sessionId, requestId, session.supplierId, session.model, prompt)
        }
        animationJobs[sessionId] = job
        job.invokeOnCompletion {
            if (animationJobs[sessionId] === job) animationJobs.remove(sessionId)
        }
    }

    /** Cancels the in-flight generation; the card says so and the record stays in the list. */
    fun cancelAnimationGeneration() {
        val session = _uiState.value.animation.selected ?: return
        if (session.phase != AnimationPhase.GENERATING) return
        // 只取消这一个会话的作业：批量测试时其他会话的请求不受影响。
        cancelAnimationJob(session.sessionId)
        updateAnimationSession(session.sessionId) { current ->
            current.copy(
                phase = if (current.html != null) AnimationPhase.READY else AnimationPhase.FAILED,
                requestId = null,
                // The cancelled request's partial document goes with it; a previous
                // successful result is untouched and keeps being shown.
                streamingText = null,
                receivedChars = 0,
                reasoningText = null,
                reasoningChars = 0,
                settledAt = clock(),
                // The retry that was waiting when this was cancelled must not leave its notice
                // behind: the generation is over, and "正在重试" on a cancelled record would
                // claim a request that is no longer coming.
                attempt = 1,
                retryNotice = null,
                error = if (current.html != null) {
                    "已取消重新生成；当前显示的是上一次成功的结果"
                } else {
                    "已取消生成"
                },
            )
        }
    }

    /**
     * Cancels one session's job, if it still has one.
     *
     * 并发按会话各管各的作业后，失效也只作用于目标会话：归属校验读的是该会话自己的
     * requestId，把会话移出列表（或把 requestId 置空）后，迟到的那半路回调自己就写不进
     * 去了，不需要再动全局序号。
     */
    private fun cancelAnimationJob(sessionId: Long) {
        animationJobs.remove(sessionId)?.cancel()
    }

    /**
     * Refuses a supplier the user marked "禁止测试：只拉模型，不发测试请求".
     *
     * The flag is a promise about this app's own behaviour, not a hint: the supplier list
     * in 模型测试 enforces it before any socket opens ([TesterViewModel] marks the run
     * SKIPPED), and the other two paths that spend the same key have to hold the same line
     * or the setting is only true on one of the screens. Nothing here is a *failure* of the
     * supplier — it is the user's own policy — so the reason names the switch to clear.
     *
     * The caller passes the supplier it is about to use, read from the store at that moment:
     * the switch is edited on another page, so a cached copy would miss a flag just turned on
     * (spending a request the user forbade) and keep refusing one just turned off.
     */
    private fun refuseIfPullOnly(
        supplier: com.relaytester.app.core.model.SupplierProfile,
    ): Boolean {
        if (!supplier.isTestingDisabled) return false
        showMessage("该供应商已设为仅拉取模型，不发送测试请求；请先在模型测试页取消该设置", isError = true)
        return true
    }

    private suspend fun generateAnimation(
        sessionId: Long,
        requestId: Long,
        supplierId: String,
        model: String,
        prompt: String,
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
            failAnimation(sessionId, requestId, "供应商配置已不存在")
            return
        }
        val (supplier, apiKey) = prepared
        // 禁止测试：只拉模型，不发测试请求 — the same policy the round start and the 模型测试
        // page hold. Read from the supplier just loaded, for the reason in [refuseIfPullOnly].
        if (supplier.isTestingDisabled) {
            failAnimation(
                sessionId,
                requestId,
                "该供应商已设为仅拉取模型，不发送测试请求；请先在模型测试页取消该设置",
            )
            return
        }
        if (apiKey.isBlank()) {
            failAnimation(sessionId, requestId, "该供应商没有可用的 API Key")
            return
        }
        // Retried here rather than inside the network layer, because what may be retried
        // depends on how far this generation got: see [startAnimationAttempt].
        var attempt = 1
        while (true) {
            val outcome = startAnimationAttempt(
                sessionId = sessionId,
                requestId = requestId,
                supplier = supplier,
                apiKey = apiKey,
                model = model,
                prompt = prompt,
            )
            // Nothing that arrives after a replacement or a cancel may act on this generation
            // — including the decision to retry, which would otherwise re-send a request the
            // user has already abandoned.
            if (!animationStillCurrent(sessionId, requestId)) return
            when (outcome) {
                is AnimationAttempt.Done -> return
                is AnimationAttempt.Cancelled -> return
                is AnimationAttempt.Retryable -> {
                    if (attempt > ANIMATION_MAX_RETRIES) {
                        failAnimation(sessionId, requestId, outcome.message)
                        return
                    }
                    // The retry is announced before the wait, so the panel says what is
                    // happening for the whole delay instead of appearing to stall.
                    updateAnimationSession(sessionId) { current ->
                        if (current.requestId != requestId) {
                            current
                        } else {
                            current.copy(
                                attempt = attempt + 1,
                                retryNotice = "${outcome.message}；正在重试（第 ${attempt + 1} 次，" +
                                    "共 ${ANIMATION_MAX_RETRIES + 1} 次）",
                            )
                        }
                    }
                    // Linear backoff, not zero: these failures are a dropped link or a busy
                    // upstream, and an immediate re-send is what makes both worse. Injected so
                    // a test does not have to sit through it (see [animationRetryDelay]).
                    animationRetryDelay(ANIMATION_RETRY_DELAY_MS * attempt)
                    if (!animationStillCurrent(sessionId, requestId)) return
                    attempt++
                    // Reset what the abandoned attempt left on screen. Its partial document
                    // and its reasoning belong to a request that is no longer running, and
                    // leaving them would make the retry look like it resumed rather than
                    // restarted — the counter in particular would never return to zero.
                    updateAnimationSession(sessionId) { current ->
                        if (current.requestId != requestId) {
                            current
                        } else {
                            current.copy(
                                streamingText = null,
                                receivedChars = 0,
                                reasoningText = null,
                                reasoningChars = 0,
                                // 「正在重试」的提示到这里的使命已经结束：新的一次尝试开始了，
                                // 提示再挂着，用户就会以为还在等（用户实测到的正是这个——
                                // 第 2 次尝试都开始吐字了，"网络连接失败；正在重试"还钉在
                                // 屏幕上）。次数本身不用清：attempt 记录的是"这是第几次"，
                                // 状态行里的「第 2 次尝试」说的是事实；归零发生在这一次
                                // 生成彻底结束时（成功、取消、失败都会写回 attempt = 1），
                                // 下一次网络中断从第 2 次重新计起。
                                retryNotice = null,
                            )
                        }
                    }
                }
            }
        }
    }

    /** What one attempt at an animation generation decided. */
    private sealed interface AnimationAttempt {
        /** The attempt produced a verdict — success or a failure that retrying cannot fix. */
        class Done : AnimationAttempt

        /** The request was superseded or cancelled while it ran. Nothing may be written. */
        class Cancelled : AnimationAttempt

        /** A transient failure before any usable text arrived: worth one more try. */
        class Retryable(val message: String) : AnimationAttempt
    }

    /**
     * Runs one animation attempt and decides whether it is worth repeating.
     *
     * Only a failure that is both transient ([isTransient]) **and** arrived before any usable
     * text is retryable:
     *  - transient, because authentication, quota and "no such model" are properties of the
     *    request itself — re-sending spends a second full budget to be refused identically;
     *  - before any usable text, because the caller is billed and made to wait for each
     *    attempt. A dropped connection in the middle of a 30 000-character document is exactly
     *    the case worth repeating; a finished document that merely failed to parse is not, and
     *    re-running it would discard work that had already succeeded.
     *
     * This is the answer to "思考一半就显示网络连接失败，没有自动重试吗": a connection lost while
     * the model was still thinking produced no document at all, so it qualifies, and the panel
     * now shows the retry instead of a dead end.
     */
    private suspend fun startAnimationAttempt(
        sessionId: Long,
        requestId: Long,
        supplier: com.relaytester.app.core.model.SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
    ): AnimationAttempt {
        // What this attempt produced, read back after it settles. Held here rather than in the
        // session so a retry decides on this attempt's own output, not on state the previous
        // one left behind.
        var answerChars = 0
        // Measured on a real relay (2026-10-10): its gateway cuts a stream after ~121 s, so a
        // model still thinking at that point is disconnected with reasoning on the wire and no
        // answer text at all. The panel must name that cause instead of implying the model
        // answered nothing — the two failures look identical in a body of zero text.
        var reasoningChars = 0
        val result = try {
            relayApi.completeTextStreaming(
                profile = supplier,
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                maxTokens = ANIMATION_MAX_TOKENS,
                timeoutSeconds = ANIMATION_TIMEOUT_SECONDS,
                // The document is shown being written rather than behind a bare spinner:
                // the answer is a large HTML+SVG document that takes a while, and watching
                // it arrive says the request is alive and what it is spending tokens on.
                onProgress = { partial ->
                    // Recorded for this attempt's own verdict, not read back from the session:
                    // a retry has to judge *its* output, and the session still holds the
                    // cleared counters the retry just reset.
                    answerChars = partial.length
                    updateAnimationSession(sessionId) { current ->
                        if (current.requestId != requestId) {
                            current
                        } else {
                            // Tail only: the end of the document is the interesting part, and
                            // holding the whole answer would copy the entire response into the
                            // UI state on every delta. The network layer already bounds the text.
                            // The full length is kept separately — that is what the header
                            // counts, and the tail's own length would report 4 000 for a
                            // finished 12 000-character document.
                            current.copy(
                                streamingText = partial.takeLast(ANIMATION_STREAM_TAIL_CHARS),
                                receivedChars = partial.length,
                            )
                        }
                    }
                },
                // 思考过程单独一条通道：推理模型可能在写第一个字符之前就把预算全花在思考
                // 上，这段时间若不显示，面板就是一句「等待响应…」，与请求卡死无从区分。
                // 它进的是 reasoningText，绝不进 html——思考不是答案。
                //
                // 网络层给的是**这一片**而不是累计值（理由见 completeTextStreaming：推理
                // 可以长到 38 000 token，逐片复制累计串是二次开销），所以累计在这里做。
                // 之前这里直接把分片当累计值写入，于是思考框永远只显示最后一个分片——屏幕
                // 上是「吧。」「嗯，但为了」这种残句，正是用户报的那个 bug。
                onReasoning = { fragment ->
                    reasoningChars += fragment.length
                    updateAnimationSession(sessionId) { current ->
                        if (current.requestId != requestId) {
                            current
                        } else {
                            // 显示只留尾部（和正文同一条规则）；计数单独累加，长度不受
                            // 截断影响。
                            //
                            // 读者上滑回看时必须停止修剪。takeLast 是**滑动**窗口：读到
                            // 某一行时新来的分片把前面的字挤掉，屏幕上的滚动位置没动，
                            // 底下的文字却整体前移了——设备实测到的就是框停在同一个像素
                            // 位置、内容前进了约五十行。保持窗口起点不动，追加只发生在
                            // 末尾，正在读的那一行才停在原处。
                            val grown = (current.reasoningText ?: "") + fragment
                            current.copy(
                                reasoningText = if (current.reasoningHeld) {
                                    grown
                                } else {
                                    grown.takeLast(ANIMATION_STREAM_TAIL_CHARS)
                                },
                                reasoningChars = current.reasoningChars + fragment.length,
                            )
                        }
                    }
                },
                // 不限制思考：动画测试要看的就是模型在无人干预时的表现，所以这里不发任何
                // 关闭/压低推理的字段，让它自己决定花多少预算在思考上。
                //
                // 代价是实测过的，写在这里以免以后被当成 bug：同一提示词下
                // deepseek-v4.1-flash 在 300 秒里写了 33 079 字思考、正文 0 字，被上游
                // 自己的 300 秒上限掐断；MiniMax-M2.7-highspeed 思考 44 145 字、正文 0 字。
                // 也就是说开启思考时，能不能出正文取决于模型本身，而不取决于本应用的
                // 边界值——所以这里的做法不是"把超时调够"，而是把边界放到足够宽
                // （ANIMATION_MAX_TOKENS / ANIMATION_TIMEOUT_SECONDS），让模型自由发挥，
                // 面板会把思考过程实时显示出来，用户能直接看到它在做什么。
                //
                // 断连抢救：上游掐断连接时，已经收到的字节不再被异常吞掉——读到哪里算
                // 哪里，按「流已结束」交还；是否构成完整文档由下面的解析规则决定，而解析
                // 失败本身也进了重试预算（见 Success 分支）。这样「思考 4 分钟后上游断开」
                // 的三种结局都是最有利的一种：文档已写完→直接出结果；文档写了一半→自动
                // 重试；空手而归→照旧重试。
                salvageOnDisconnect = true,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // A thrown error never carries an ApiResult, so its transience is judged from the
            // message the same way the network layer would classify it: anything reaching here
            // is an unexpected throw, so it is reported rather than retried.
            failAnimation(sessionId, requestId, error.message ?: "动画生成失败")
            return AnimationAttempt.Done()
        }
        // Ownership check before anything is written: a replaced session or request must not
        // be repainted by an answer that belongs to the one before it.
        if (!animationStillCurrent(sessionId, requestId)) return AnimationAttempt.Cancelled()
        return when (result) {
            is ApiResult.Success -> {
                // fold 的两个 lambda 都返回 Unit，重试决定必须落在捕获变量里带出去——
                // 在 onFailure 里直接 return 只会退出 lambda，解析失败就会静默变成「不再
                // 重试」，恰好是这次要拆掉的死局。
                var decision: AnimationAttempt = AnimationAttempt.Done()
                val document = parseAnimationDocument(
                    raw = result.value,
                    // The completion path returns text only, so a stop reason is not
                    // available here. An answer that fills the whole ceiling is the one
                    // signal that it was cut off rather than ended by the model.
                    truncated = result.value.length >= ANIMATION_MAX_TOKENS * TRUNCATION_CHARS_PER_TOKEN,
                )
                document.fold(
                    onSuccess = { parsed ->
                        updateAnimationSession(sessionId) { current ->
                            if (current.requestId != requestId) {
                                current
                            } else {
                                current.copy(
                                    phase = AnimationPhase.READY,
                                    requestId = null,
                                    html = parsed.html,
                                    // The finished animation replaces the generation
                                    // view: the partial text has done its job.
                                    streamingText = null,
                                    receivedChars = 0,
                                    reasoningText = null,
                                    reasoningChars = 0,
                                    settledAt = clock(),
                                    // The HTML is bound to the prompt that produced it, so
                                    // editing the draft afterwards cannot claim it.
                                    htmlPrompt = prompt,
                                    error = null,
                                    view = AnimationView.PREVIEW,
                                    replayToken = current.replayToken + 1,
                                    attempt = 1,
                                    retryNotice = null,
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        val kind = (error as? AnimationUnusable)?.kind
                        val detail = (error as? AnimationUnusable)?.message
                            ?: error.message
                            ?: "返回内容无法预览"
                        // 「已收到 N 字符」只有在中断的场合才有意义：一个完整的回答被解析
                        // 拒绝时（例如整篇是散文），字数帮不上忙；而一份没有结束标签的
                        // 文档配上字数，用户立刻知道是断在中途而不是模型拒绝了。
                        val message = if (answerChars > 0 && kind == AnimationFailure.NOT_A_DOCUMENT) {
                            "$detail（已收到 $answerChars 字符，多为上游中断）"
                        } else {
                            detail
                        }
                        if (kind == AnimationFailure.NOT_A_DOCUMENT) {
                            // 上游在输出中途断开、或整篇没给出文档，都属于「再发一次很可能
                            // 不一样」的失败——和空响应的瞬断一样进重试预算，而不是当场
                            // 终局。这正是用户截图里的死局：流被掐断后已收到的半篇文档按
                            // 「EOF 即答案」返回，解析不过，旧逻辑直接把它钉死在失败上。
                            // TRUNCATED 除外：那是打满了 max_tokens 的系统性截断，重发
                            // 一模一样的请求只会把同样的账单再付一遍。
                            decision = AnimationAttempt.Retryable(message)
                        } else {
                            failAnimation(sessionId, requestId, message)
                        }
                    },
                )
                decision
            }
            is ApiResult.Failure -> {
                val message = if (result.error.isMissingAnswerText()) {
                    blankAnimationReason(reasoningChars)
                } else {
                    result.error.message.ifBlank { "动画生成失败" }
                }
                if (
                    (result.error.kind.isTransient() || result.error.isMissingAnswerText()) &&
                        answerChars == 0
                ) {
                    // Nothing usable arrived and the failure is one that a second identical
                    // request can plausibly get past. Left unsettled on purpose: the retry
                    // writes its own notice, and reporting this one as a failure would flash
                    // an error the user is not meant to act on.
                    AnimationAttempt.Retryable(message)
                } else {
                    failAnimation(sessionId, requestId, message)
                    AnimationAttempt.Done()
                }
            }
        }
    }

    /**
     * Why an animation attempt produced no answer text.
     *
     * Two different failures arrive here with the same empty body, and naming the wrong one
     * sends the user in the wrong direction ("the model refused" vs "the relay cut the
     * stream"). Reasoning on the wire with no answer means the model was still thinking when
     * the connection ended, which is what a relay gateway's fixed wall-clock cap looks like:
     * measured 2026-10-10 on a real relay, its gateway drops the stream at ~121 s and the
     * body ends mid-thinking with no `[DONE]`, no `finish_reason` and no error frame.
     * Re-sending is the right advice there, because the second attempt may finish thinking
     * inside the window; a body with nothing on either channel is the upstream giving
     * nothing at all.
     */
    private fun blankAnimationReason(reasoningChars: Int): String =
        if (reasoningChars > 0) {
            "$NO_ANSWER_TEXT_MESSAGE；上游在思考阶段中断（已收到思考 $reasoningChars 字符，" +
                "正文 0 字符），多为中转网关的时长上限，可重试"
        } else {
            NO_ANSWER_TEXT_MESSAGE
        }

    private fun animationStillCurrent(sessionId: Long, requestId: Long): Boolean {
        val current = _uiState.value.animation.sessions.firstOrNull { it.sessionId == sessionId }
            ?: return false
        return current.requestId == requestId
    }

    /**
     * Records a failure without discarding a preview that already worked.
     *
     * A regeneration that fails must leave the previous animation viewable: the user asked
     * for a new one, not for the old one to be destroyed.
     */
    private fun failAnimation(sessionId: Long, requestId: Long, reason: String) {
        updateAnimationSession(sessionId) { current ->
            // A failure can arrive for a request that was already superseded; only the live
            // request may write. A null requestId means the record was already settled, which
            // is exactly the case a late failure must not reopen.
            if (current.requestId != requestId) {
                current
            } else {
                current.copy(
                    phase = if (current.html != null) AnimationPhase.READY else AnimationPhase.FAILED,
                    requestId = null,
                    // A failed request's partial document is discarded, not left on
                    // screen: half a page is not a preview, and keeping it would let a
                    // failure look like a result that merely stopped moving.
                    streamingText = null,
                    receivedChars = 0,
                    reasoningText = null,
                    reasoningChars = 0,
                    settledAt = clock(),
                    // The generation is over: the retry counter and any "正在重试" notice go
                    // with it, so the record does not claim a request still on the wire. The
                    // reason that ended it is in [error], which is what the panel shows.
                    attempt = 1,
                    retryNotice = null,
                    error = if (current.html != null) {
                        "$reason；仍显示上一次成功的结果"
                    } else {
                        reason
                    },
                )
            }
        }
    }

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
        /**
         * Round-ownership check for retry paths: false the moment the round the request
         * belongs to has been replaced. A stale request still gets its answer back — the
         * caller decides what to do with it — but none of this method's writes land.
         */
        stillCurrent: (() -> Boolean)? = null,
    ): ChallengeProgress {
        val current = { stillCurrent?.invoke() != false }
        var attempt = 1
        while (true) {
            if (current()) {
                updateProgress(index) {
                    it.copy(
                        state = ChallengeState.REQUESTING,
                        answer = "",
                        error = null,
                        receivedNumbers = 0,
                        // 第一位是「第一次发送」：重发进去的是第二次，状态词才会自称重试。
                        retrying = attempt > 1,
                    )
                }
            }
            // 这一轮尝试落在思考通道上的字数。它不参与判分，但一次空白回答是「模型只想不写」
            // 还是「上游什么都没给」，只有它能分开（见 [blankAnswerReason]）。
            var reasoningChars = 0
            val result = try {
                relayApi.completeTextStreaming(
                    profile = supplier,
                    apiKey = apiKey,
                    model = model,
                    prompt = challenge.prompt,
                    maxTokens = CHALLENGE_MAX_TOKENS,
                    timeoutSeconds = CHALLENGE_TIMEOUT_SECONDS,
                    // 思考分片只数长度，不留下文本：三题检测没有地方显示它，但「只出思考、
                    // 没有正文」的失败要说得清（见 [blankAnswerReason]）。
                    onReasoning = { fragment -> reasoningChars += fragment.length },
                    onProgress = { partial ->
                        // Only the count is published, and only when it grew: re-counting the
                        // same text would republish identical state several times a second and
                        // recompose the whole card for nothing.
                        val numbers = NumberFeatures.parseNumbers(partial).size
                        if (!current()) return@completeTextStreaming
                        updateProgress(index) { entry ->
                            if (entry.state != ChallengeState.REQUESTING || numbers <= entry.receivedNumbers) {
                                entry
                            } else {
                                entry.copy(receivedNumbers = numbers)
                            }
                        }
                    },
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
                if (current()) {
                    updateProgress(index) { rejected }
                    cacheAnswer(model, index, slots, rejected)
                }
                return rejected
            }
            if (result is ApiResult.Failure &&
                result.error.isMissingAnswerText() &&
                current() &&
                attempt < CHALLENGE_MAX_ATTEMPTS
            ) {
                // 空白回答不结题：它是上游自己的硬币，不是这次请求的属性，所以同一题再问一次
                // （见 [CHALLENGE_MAX_ATTEMPTS]）。过期的一轮不再发第二次——那张卡已经没人看，
                // 再花掉的是一次真实请求。
                attempt++
                continue
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
                    // 空白的理由要看思考通道说了什么；别的失败照原样上报。
                    error = if (result.error.isMissingAnswerText()) {
                        blankAnswerReason(reasoningChars, attempt)
                    } else {
                        result.error.message
                    },
                )
            }
            // A superseded request returns its answer for the caller to discard, but writes
            // none of it: the progress list and the cache now describe a different round.
            if (!current()) return progress
            updateProgress(index) { progress }
            cacheAnswer(model, index, slots, progress)
            return progress
        }
    }

    /**
     * What to say when the asks came back with no answer text at all.
     *
     * 两种空长得一模一样，只有这里分得开：一种是想思考模型把整段思路写完了就停下——真实供应商
     * 实测过「8192 的预算全部花在 reasoning_tokens、正文 0 字符、finish_reason: length」，模型把
     * 任务做进了思考通道；另一种是流里两条通道都没有文字，那是上游什么都没给。前一种要说清是
     * 模型自己这么做的，用户才知道「稍后重试或换模型」；后一种保持上游的原话。两种都带上自动
     * 重发的结论，因为那确实花掉了第二次请求。
     *
     * [attempts] 为 1 时说明自动重发根本没跑（过期的一轮不再发，见 [requestChallenge]），
     * 那就没有重试可报，只报失败本身。
     */
    private fun blankAnswerReason(reasoningChars: Int, attempts: Int): String {
        val retried = if (attempts > 1) "（自动重试 ${attempts - 1} 次仍未取得）" else ""
        return if (reasoningChars > 0) {
            "模型只输出了思考内容、没有给出正文$retried"
        } else {
            "$NO_ANSWER_TEXT_MESSAGE$retried"
        }
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
        // The retry keys are taken before the invalidation: their slots are the ones a
        // cancelled re-ask left as REQUESTING in the cache, and the reset below has to
        // know which ones to hand back to the retryable set.
        val pendingRetryKeys = retryJobs.keys.toList()
        // Retries are cancelled together with the round: 取消 means stop everything this
        // panel is sending, and the epoch bump keeps a request that was already answered
        // from writing into the round the user just stopped.
        invalidateRound()
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
        // A cancelled retry's REQUESTING placeholder would hide the slot from
        // [retryableFrom] forever — REQUESTING is "someone is on it", and nobody is any
        // more. Hand the slots back unanswered so 取消后重新检测 stays reachable.
        if (pendingRetryKeys.isNotEmpty()) {
            setRoundAnswers(resetRequestingSlots(roundAnswers, pendingRetryKeys))
        }
        abandonUnfinishedRows("已取消")
    }

    /**
     * Turns the REQUESTING placeholders left by cancelled retries back into unanswered
     * slots, keyed by the retries' own (model, question).
     */
    private fun resetRequestingSlots(
        answers: Map<String, List<ChallengeProgress>>,
        keys: Collection<Pair<String, Int>>,
    ): Map<String, List<ChallengeProgress>> {
        if (keys.isEmpty()) return answers
        val next = answers.toMutableMap()
        for ((model, index) in keys) {
            val slots = next[model]?.toMutableList() ?: continue
            if (index in slots.indices && slots[index].state == ChallengeState.REQUESTING) {
                slots[index] = ChallengeProgress(slots[index].challenge)
            }
            next[model] = slots
        }
        return next
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
     * The automatic check: once every time the app comes up, then every
     * [bankCheckIntervalMillis] for as long as the app stays open.
     *
     * Deliberately *not* tied to opening the panel. It used to run on every panel entry,
     * which meant switching between the three tabs re-asked the endpoint each time — the
     * card's "上次检查：…" moved with it, so the row looked like it reset itself. One check
     * per launch is what「打开软件查一次」means, and a launch is what the user does when they
     * want to know: it used to additionally wait out the six-hour window, so opening the app
     * inside that window asked nothing and the row kept the previous session's time — from
     * the outside, an app that had stopped checking altogether.
     *
     * [LAUNCH_FLOOR_MILLIS] is not that window coming back: it only spans the same process's
     * own repeated calls, because recreating the activity (a rotation) runs this again.
     *
     * Starting the periodic check is part of coming up, not of this check succeeding: an app
     * launched with the switch off, or inside the floor, still has to be watching for the
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
        // because the switch is off or a check is in flight still knows when the last check
        // was, and the stored stamp is the only thing that can say it.
        _uiState.update {
            it.copy(
                autoCheckBank = stored.autoCheckBank,
                bankEarlierCheckAtMillis = stored.lastBankCheckAt.takeIf { stamp -> stamp > 0 },
            )
        }
        // The switch is the whole point: off means this panel does not talk to the release
        // endpoint unless the user presses 「检查更新」.
        if (!stored.autoCheckBank) return false
        // Two calls a second apart are one launch: the activity is recreated — on a rotation,
        // on a theme change — and this runs again, but「又打开了一次软件」is not what happened.
        // The in-memory stamp counts as a reference because a check whose write failed is
        // still a check that was made.
        val reference = maxOf(stored.lastBankCheckAt, lastBankCheckAtMillis)
        if (reference > 0 && clock() - reference < LAUNCH_FLOOR_MILLIS) return false
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
        if (refuseWhileBankJobBusy()) return
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
        if (refuseWhileBankJobBusy()) return
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
     * Refuses a bank-package action that cannot start right now, saying why.
     *
     * [bankUpdateBusy] is the predicate the three package actions share; this is its
     * reporting form. It exists because the same window is reachable from the UI as
     * "enabled": the card's own `busy` flag is `isCheckingBankUpdate || isInstallingBank ||
     * isRunning` and does **not** include `isLoading`, so during the startup store read the
     * 「删除检测包」/「下载检测包」 buttons are live while [bankUpdateBusy] would refuse them —
     * a tap that quietly does nothing, which reads as a broken button.
     */
    private fun refuseWhileBankJobBusy(): Boolean {
        if (!bankUpdateBusy()) return false
        val state = _uiState.value
        // 说清是哪一种占用：换包/检查包与「三题检测在跑」要采取的动作不同（前者等几秒，
        // 后者要么等要么先取消）。启动读只是还没就绪，首屏自己写着「正在加载」，不再弹错。
        if (!state.isLoading) {
            val reason = when {
                state.isInstallingBank -> "检测包正在更新，请稍候再试"
                state.isCheckingBankUpdate -> "正在检查检测包更新，请稍候再试"
                else -> "检测正在进行，请稍候再试"
            }
            showMessage(reason, isError = true)
        }
        return true
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
        /** The challenge needs room for both a long reasoning trace and its numeric answer. */
        const val CHALLENGE_MAX_TOKENS = 32_768

        /**
         * The request timeout is an idle-read watchdog, not a whole-call deadline. Relays that
         * buffer a long completion can legitimately need well over a minute.
         */
        const val CHALLENGE_TIMEOUT_SECONDS = 240

        /**
         * How many times one challenge is sent before a blank answer is accepted as the verdict.
         *
         * Measured on a real supplier, not reasoned about: a thinking model sometimes spends
         * the whole [CHALLENGE_MAX_TOKENS] budget inside its reasoning channel and stops
         * (`finish_reason: length`, `reasoning_tokens` = the full ceiling, zero characters of
         * answer) — one blank in four identical asks of the same question, and the same
         * question re-sent produced a usable answer every other time. The blank is the
         * upstream's own coin toss, so a second ask is a real chance rather than the same
         * rejection paid for twice: at the measured rate it turns an every-fourth failure
         * into an every-sixteenth one, and the card says a retry is running while it does.
         *
         * Two, not more: each ask can cost the full token budget on the model's side, so the
         * bound has to stay small enough that a user is never paying for a loop. A question
         * that comes back blank twice is left for the user's own 「重试」 button.
         *
         * No backoff between the two: the animation path's delay exists for a dropped link or
         * a busy upstream, and this failure is neither — the model answered, it just answered
         * nothing — so waiting would only postpone the round the other two questions share.
         */
        const val CHALLENGE_MAX_ATTEMPTS = 2

        const val ANSWER_SLOTS = 3

        /**
         * Floor an answer has to clear when no detection package supplies its own.
         *
         * Same value upstream defaults `minimum_valid_numbers` to; a package overrides
         * it, and this is only used before one is installed.
         */
        const val DEFAULT_MINIMUM_VALID_NUMBERS = 80

        /**
         * The shortest gap between two wake-ups by the launch check.
         *
         * Not the check's window — the window is gone, every launch checks — but the same
         * process can come up more than once: recreating the activity (a rotation, a theme
         * change) runs the launch check again, and that is one launch, not two. A minute is
         * far longer than any recreation and far shorter than any real relaunch.
         */
        internal const val LAUNCH_FLOOR_MILLIS = 60_000L

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
