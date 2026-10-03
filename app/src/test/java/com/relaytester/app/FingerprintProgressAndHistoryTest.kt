package com.relaytester.app

import com.relaytester.app.core.fingerprint.DetectionHistoryEntry
import com.relaytester.app.core.fingerprint.FingerprintHistoryStore
import com.relaytester.app.core.fingerprint.HistoryFileSystem
import com.relaytester.app.core.model.ApiResult
import com.relaytester.app.core.model.ErrorKind
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestError
import com.relaytester.app.core.network.RelayApi
import com.relaytester.app.feature.fingerprint.ChallengeProgress
import com.relaytester.app.feature.fingerprint.ChallengeState
import com.relaytester.app.feature.fingerprint.FingerprintViewModel
import com.relaytester.app.feature.fingerprint.ModelDetectionStatus
import com.relaytester.app.feature.fingerprint.ModelFingerprintResult
import com.relaytester.app.feature.fingerprint.activeModelPosition
import com.relaytester.app.feature.fingerprint.historyOutcome
import com.relaytester.app.feature.fingerprint.roundQuestionsDone
import com.relaytester.app.feature.fingerprint.roundQuestionsTotal
import com.relaytester.app.feature.fingerprint.stateLabel
import com.relaytester.app.core.fingerprint.FingerprintChallenge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The round-level display and the history write, checked as pure functions.
 *
 * The progress arithmetic is the one the user sees while three models run, and the
 * version it replaces reset at every model change; these cases pin the whole-round
 * semantics without rendering anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FingerprintProgressAndHistoryTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun progress(vararg states: ChallengeState) = states.map { state ->
        ChallengeProgress(
            challenge = FingerprintChallenge("c", 300, "prompt"),
            state = state,
        )
    }

    private fun rows(vararg statuses: ModelDetectionStatus) = statuses.mapIndexed { index, status ->
        ModelFingerprintResult(model = "model-$index", status = status)
    }

    // ---- whole-round progress ----------------------------------------------

    @Test
    fun `the round totals every model's questions`() {
        val total = roundQuestionsTotal(
            rows(ModelDetectionStatus.PENDING, ModelDetectionStatus.PENDING, ModelDetectionStatus.PENDING),
            progress(ChallengeState.PENDING, ChallengeState.PENDING, ChallengeState.PENDING),
        )

        assertEquals(9, total)
    }

    @Test
    fun `a finished model contributes all of its questions`() {
        val done = roundQuestionsDone(
            rows(ModelDetectionStatus.DONE, ModelDetectionStatus.RUNNING, ModelDetectionStatus.PENDING),
            progress(ChallengeState.RECEIVED, ChallengeState.RECEIVED, ChallengeState.RECEIVED),
        )

        // One model is settled (its three questions), and the running model's three
        // answers are already in — the header is showing them.
        assertEquals(6, done)
    }

    @Test
    fun `a settled last model does not push the bar past the end`() {
        val results = rows(ModelDetectionStatus.DONE, ModelDetectionStatus.DONE)
        val allIn = progress(ChallengeState.RECEIVED, ChallengeState.RECEIVED, ChallengeState.RECEIVED)

        // Both models settled, and the progress list still holds the last one's answers.
        // Counting those again on top of the settled total would read 9/6.
        assertEquals(roundQuestionsTotal(results, allIn), roundQuestionsDone(results, allIn))
    }

    @Test
    fun `an in-flight question is not credited early`() {
        val done = roundQuestionsDone(
            rows(ModelDetectionStatus.RUNNING, ModelDetectionStatus.PENDING),
            progress(ChallengeState.REQUESTING, ChallengeState.REQUESTING, ChallengeState.PENDING),
        )

        // Counting REQUESTING would make the bar jump to 2/6 the instant the requests
        // leave, before any answer has landed.
        assertEquals(0, done)
    }

    @Test
    fun `the bar never resets when the round moves to the next model`() {
        // The real transition: model one has settled and its answers are still in the
        // progress list for an instant, then the loop resets the list for model two.
        val results = rows(ModelDetectionStatus.DONE, ModelDetectionStatus.RUNNING)

        val whileFirstAnswersStillShown = roundQuestionsDone(
            results = rows(ModelDetectionStatus.DONE, ModelDetectionStatus.PENDING),
            progress = progress(ChallengeState.RECEIVED, ChallengeState.RECEIVED, ChallengeState.RECEIVED),
        )
        val afterSecondModelStarts = roundQuestionsDone(
            results,
            progress(ChallengeState.PENDING, ChallengeState.PENDING, ChallengeState.PENDING),
        )

        // This is the regression the user saw: the reading must not drop when the round
        // advances, or a three-model run looks like it restarts from nothing each time.
        assertEquals(3, whileFirstAnswersStillShown)
        assertEquals(3, afterSecondModelStarts)
        assertTrue(
            "换模型时进度不得回退",
            afterSecondModelStarts >= whileFirstAnswersStillShown,
        )
    }

    @Test
    fun `the round's own progress grows monotonically across models`() {
        // The whole-round reading for a two-model run, sampled where the round really
        // is: model one settled with model two running and partway answered.
        val results = rows(ModelDetectionStatus.DONE, ModelDetectionStatus.RUNNING)

        val firstAnswerInSecond = roundQuestionsDone(
            results,
            progress(ChallengeState.RECEIVED, ChallengeState.REQUESTING, ChallengeState.PENDING),
        )
        val allSecondIn = roundQuestionsDone(
            results,
            progress(ChallengeState.RECEIVED, ChallengeState.RECEIVED, ChallengeState.RECEIVED),
        )

        assertEquals(4, firstAnswerInSecond)
        assertEquals(6, allSecondIn)
        assertTrue(allSecondIn > firstAnswerInSecond)
    }

    @Test
    fun `a settled failure counts as progress`() {
        val done = roundQuestionsDone(
            rows(ModelDetectionStatus.FAILED, ModelDetectionStatus.PENDING),
            progress(ChallengeState.PENDING, ChallengeState.PENDING, ChallengeState.PENDING),
        )

        // A failed model is finished, not pending: leaving it out would park the bar
        // short of the end for the rest of the round.
        assertEquals(3, done)
    }

    @Test
    fun `an empty round does not divide by zero`() {
        assertEquals(0, roundQuestionsTotal(emptyList(), emptyList()))
        assertEquals(0, roundQuestionsDone(emptyList(), emptyList()))
    }

    // ---- which model is being tested --------------------------------------

    @Test
    fun `the active model's place comes from the result rows`() {
        val results = rows(
            ModelDetectionStatus.DONE,
            ModelDetectionStatus.RUNNING,
            ModelDetectionStatus.PENDING,
        ).toMutableList().also { it[2] = ModelFingerprintResult("third") }

        assertEquals(1, activeModelPosition(results, "model-0"))
        assertEquals(2, activeModelPosition(results, "model-1"))
        assertEquals(3, activeModelPosition(results, "third"))
    }

    @Test
    fun `a model outside the round has no place`() {
        assertNull(activeModelPosition(rows(ModelDetectionStatus.RUNNING), "not-in-this-round"))
        assertNull(activeModelPosition(rows(ModelDetectionStatus.RUNNING), null))
    }

    // ---- the live integer count -------------------------------------------

    @Test
    fun `the count appears only once something countable has arrived`() {
        val waiting = ChallengeProgress(
            challenge = FingerprintChallenge("c", 300, "prompt"),
            state = ChallengeState.REQUESTING,
            receivedNumbers = 0,
        )
        val arriving = waiting.copy(receivedNumbers = 42)

        // Zero would read as a stall during the prose preamble that every challenge
        // starts with.
        assertEquals("正在接收…", stateLabel(waiting))
        assertEquals("正在接收… 已收到 42 个整数", stateLabel(arriving))
    }

    @Test
    fun `the count is not shown once the answer is settled`() {
        val received = ChallengeProgress(
            challenge = FingerprintChallenge("c", 300, "prompt"),
            state = ChallengeState.RECEIVED,
            parsedNumbers = 300,
            receivedNumbers = 291,
        )

        // The final, validated number is the authority; the running count was a
        // snapshot mid-stream and would disagree with it.
        assertEquals("已接收 300 个有效数字", stateLabel(received))
    }

    // ---- how a history row reads ------------------------------------------

    @Test
    fun `a successful row names the candidate and the answer count`() {
        val text = historyOutcome(
            DetectionHistoryEntry(
                finishedAt = 1L,
                supplierName = "sup",
                model = "m",
                candidateName = "GPT-4o",
                familyName = "GPT",
                probability = 0.873,
                usableAnswers = 3,
                submittedAnswers = 3,
            ),
        )

        assertEquals("GPT-4o · GPT（87%） · 有效回答 3/3", text)
    }

    @Test
    fun `a failed row says why instead of showing an empty ranking`() {
        val text = historyOutcome(
            DetectionHistoryEntry(
                finishedAt = 1L,
                supplierName = "sup",
                model = "m",
                error = "上游返回 HTTP 502",
            ),
        )

        assertEquals("失败：上游返回 HTTP 502", text)
    }

    @Test
    fun `a row with no candidate and no error says so`() {
        val text = historyOutcome(
            DetectionHistoryEntry(finishedAt = 1L, supplierName = "sup", model = "m"),
        )

        assertEquals("未识别出候选", text)
    }

    // ---- the round writes history once ------------------------------------

    /** An API that answers every challenge with a usable list. */
    private class ConstantCompletionApi(private val answer: String) : RelayApi() {
        override suspend fun completeTextStreaming(
            profile: SupplierProfile,
            apiKey: String,
            model: String,
            prompt: String,
            maxTokens: Int,
            timeoutSeconds: Int,
            onProgress: suspend (String) -> Unit,
        ): ApiResult<String> = ApiResult.Success(answer)
    }

    private class MemoryHistoryFiles(var content: String? = null) : HistoryFileSystem {
        override fun read(): String? = content

        override fun write(text: String) {
            content = text
        }
    }

    /**
     * A panel whose store read, round and history write all run on the test dispatcher.
     *
     * The real IO dispatcher would put the round on another thread, and every assertion
     * here would be racing it; `awaitIdle` can only join jobs that have started.
     */
    private fun panel(
        api: RelayApi,
        history: FingerprintHistoryStore,
    ): FingerprintViewModel = FingerprintViewModel(
        supplierStore = SingleModelSupplierStore(),
        secretStore = FixedSecretStore(),
        relayApiFactory = { api },
        bankStore = MemoryBankFileSystem().store(),
        bankUpdateClient = com.relaytester.app.core.fingerprint.BankUpdateClient(
            fetcher = FakeBankFetcher(),
        ),
        appVersionCode = 10_400L,
        historyStore = history,
        skipRestore = false,
        ioDispatcher = mainDispatcher,
    ).apply {
        // The startup read seeds the supplier and the challenge set; without it the
        // round has nothing to send.
        mainDispatcher.scheduler.runCurrent()
        selectMode(com.relaytester.app.feature.fingerprint.DetectionMode.API)
    }

    @Test
    fun `a finished round appends one record per model`() {
        val store = FingerprintHistoryStore(MemoryHistoryFiles())
        val subject = panel(ConstantCompletionApi(usableAnswer()), store)

        subject.toggleModelSelection("alpha-gpt-4o-preview")
        subject.toggleModelSelection("beta-claude-3-opus")
        subject.runApiDetection()
        mainDispatcher.scheduler.advanceUntilIdle()
        kotlinx.coroutines.runBlocking { subject.awaitIdle() }

        val stored = store.load()
        assertEquals(2, stored.size)
        // The most recently finished model is first; the round walks its models in tick
        // order, so the second ticked one is the newer record.
        assertEquals(listOf("beta-claude-3-opus", "alpha-gpt-4o-preview"), stored.map { it.model })
        assertTrue("每条都该带上供应商名", stored.all { it.supplierName == "测试供应商" })
        assertTrue("成功的记录不该带错误", stored.all { it.error == null })
        assertEquals("历史必须同步到面板", 2, subject.uiState.value.history.size)
    }

    @Test
    fun `a cancelled round writes nothing`() {
        val store = FingerprintHistoryStore(MemoryHistoryFiles())
        val subject = panel(ParkedCompletionApi(), store)

        subject.toggleModelSelection("alpha-gpt-4o-preview")
        subject.runApiDetection()
        mainDispatcher.scheduler.runCurrent()
        subject.cancelRun()
        mainDispatcher.scheduler.advanceUntilIdle()
        kotlinx.coroutines.runBlocking { subject.awaitIdle() }

        // A round that never produced a verdict has nothing to record; writing a
        // "failed" row here would blame the model for the user's own cancel.
        assertEquals(emptyList<DetectionHistoryEntry>(), store.load())
    }

    @Test
    fun `clearing the history empties the panel and the file`() {
        val files = MemoryHistoryFiles()
        val store = FingerprintHistoryStore(files)
        store.append(
            listOf(
                DetectionHistoryEntry(finishedAt = 1L, supplierName = "sup", model = "old"),
            ),
        )
        val subject = panel(ConstantCompletionApi(usableAnswer()), store)

        assertEquals(1, subject.uiState.value.history.size)
        subject.clearHistory()
        mainDispatcher.scheduler.advanceUntilIdle()
        kotlinx.coroutines.runBlocking { subject.awaitIdle() }

        assertEquals(emptyList<DetectionHistoryEntry>(), subject.uiState.value.history)
        assertEquals(emptyList<DetectionHistoryEntry>(), store.load())
    }

    // ---- the live count, driven by a stream --------------------------------

    @Test
    fun `a streaming answer publishes the running integer count`() {
        // Four chunks of 75 integers each: the last one is released only after the
        // third has been observed, so every observation lands while the request is
        // still open. (Releasing the final chunk finishes the request in the same
        // frame, which legitimately resets the live counter — the validated total takes
        // over at that point, and that is asserted at the end.)
        val chunkSize = 75
        val chunks = (0 until 4).map { block ->
            (1..chunkSize).joinToString(" ") { ((block * chunkSize + it) % 355 + 1).toString() } + " "
        }
        val api = SteppedStreamingApi(chunks)
        val subject = panel(api, FingerprintHistoryStore(MemoryHistoryFiles()))
        subject.toggleModelSelection("alpha-gpt-4o-preview")

        subject.runApiDetection()
        mainDispatcher.scheduler.runCurrent()

        // Read the count after each released chunk; it must track what has arrived.
        val counts = mutableListOf<Int>()
        counts += subject.uiState.value.progress[0].receivedNumbers
        api.releaseNext()
        mainDispatcher.scheduler.runCurrent()
        counts += subject.uiState.value.progress[0].receivedNumbers
        api.releaseNext()
        mainDispatcher.scheduler.runCurrent()
        counts += subject.uiState.value.progress[0].receivedNumbers

        assertEquals("每来一段，计数就该跟着涨", listOf(75, 150, 225), counts)
        assertEquals(
            "计数期间状态必须仍是接收中",
            ChallengeState.REQUESTING,
            subject.uiState.value.progress[0].state,
        )

        // Let the stream finish; the validated total replaces the live count.
        api.releaseRest()
        mainDispatcher.scheduler.advanceUntilIdle()
        runBlocking { subject.awaitIdle() }

        assertEquals(300, subject.uiState.value.progress[0].parsedNumbers)
        assertEquals(ChallengeState.RECEIVED, subject.uiState.value.progress[0].state)
    }

    /** 300 distinct in-range integers, which clears any packaged floor. */
    private fun usableAnswer(): String =
        (1..300).joinToString(" ") { ((it % 355) + 1).toString() }
}

/**
 * Streams [chunks] one at a time, each released by the test.
 *
 * A gate rather than a delay: the assertion is "when a chunk arrives, the panel counts
 * it", and tying that to a sleep would make the test measure its own timing. There is
 * one deferred per chunk after the first, so each [releaseNext] lets exactly one more
 * chunk through and the test can read the panel's count in between.
 */
private class SteppedStreamingApi(private val chunks: List<String>) : RelayApi() {
    private val gates = List((chunks.size - 1).coerceAtLeast(0)) { kotlinx.coroutines.CompletableDeferred<Unit>() }
    private var released = 0

    /** Lets the next chunk through, if one is still gated. */
    fun releaseNext() {
        gates.getOrNull(released)?.let { gate ->
            released++
            if (!gate.isCompleted) gate.complete(Unit)
        }
    }

    /** Releases everything that is left, so the stream can finish. */
    fun releaseRest() {
        gates.drop(released).forEach { if (!it.isCompleted) it.complete(Unit) }
        released = gates.size
    }

    override suspend fun completeTextStreaming(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        onProgress: suspend (String) -> Unit,
    ): ApiResult<String> {
        val accumulated = StringBuilder()
        // The first chunk is delivered before the stream waits, so the very first
        // observation already has something to count.
        accumulated.append(chunks.first())
        onProgress(accumulated.toString())
        for (index in 1 until chunks.size) {
            gates[index - 1].await()
            accumulated.append(chunks[index])
            onProgress(accumulated.toString())
        }
        return ApiResult.Success(accumulated.toString())
    }
}

/** A store holding one supplier with two models, for the history round trip. */
private class SingleModelSupplierStore : com.relaytester.app.core.storage.SupplierStore(null) {
    override suspend fun read(): com.relaytester.app.core.storage.SupplierStoreState =
        com.relaytester.app.core.storage.SupplierStoreState(
            suppliers = listOf(
                SupplierProfile(
                    id = "sup-1",
                    name = "测试供应商",
                    baseUrl = "https://relay.test/v1",
                    protocol = com.relaytester.app.core.model.RelayProtocol.CHAT_COMPLETIONS,
                    apiKeySecretId = "secret-1",
                    models = listOf("alpha-gpt-4o-preview", "beta-claude-3-opus"),
                    testSettings = com.relaytester.app.core.model.TestSettings(),
                ),
            ),
            activeSupplierId = "sup-1",
        )

    override suspend fun save(state: com.relaytester.app.core.storage.SupplierStoreState) = Unit
}

private class FixedSecretStore : com.relaytester.app.core.security.SecretStore {
    override fun put(value: String): String = "secret-1"
    override fun get(secretId: String): String = "api-key"
    override fun delete(secretId: String) = Unit
}

/** Never answers; the request stays in flight until it is cancelled. */
private class ParkedCompletionApi : RelayApi() {
    override suspend fun completeTextStreaming(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        onProgress: suspend (String) -> Unit,
    ): ApiResult<String> {
        kotlinx.coroutines.awaitCancellation()
    }
}
