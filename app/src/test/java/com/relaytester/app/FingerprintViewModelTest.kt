package com.relaytester.app

import com.relaytester.app.core.fingerprint.BankSource
import com.relaytester.app.core.fingerprint.BankUpdateClient
import com.relaytester.app.core.fingerprint.sha256Hex
import com.relaytester.app.core.fingerprint.FingerprintBank
import com.relaytester.app.core.fingerprint.minimumNumbersFor
import com.relaytester.app.core.model.ApiResult
import com.relaytester.app.core.model.ErrorKind
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestError
import com.relaytester.app.core.model.TestSettings
import com.relaytester.app.core.network.RelayApi
import com.relaytester.app.core.security.SecretStore
import com.relaytester.app.core.storage.SupplierStore
import com.relaytester.app.core.storage.SupplierStoreState
import com.relaytester.app.feature.fingerprint.ChallengeProgress
import com.relaytester.app.feature.fingerprint.ChallengeState
import com.relaytester.app.feature.fingerprint.DetectionMode
import com.relaytester.app.feature.fingerprint.FingerprintViewModel
import com.relaytester.app.feature.fingerprint.ModelDetectionStatus
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the panel state machine, which the pure-algorithm tests never touch.
 *
 * The shipped bug this guards against: the paste field displayed
 * `progress[i].answer` while `onValueChange` wrote a separate `manualAnswers`
 * list, so every keystroke was reverted on recomposition and manual detection
 * was unreachable. Nothing crashed, and the algorithm tests stayed green.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FingerprintViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    /** A published-bank build stamp; the same length as the packaged one by construction. */
    private val patchStamp = "2026-09-30T05:12:31.123456+00:00"

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun bank(): FingerprintBank = BankFixtures.bank()

    private fun viewModel(): FingerprintViewModel = FingerprintViewModel(
        supplierStore = EmptyStore(),
        secretStore = object : SecretStore {
            override fun put(value: String): String = "secret"
            override fun get(secretId: String): String = "api-key"
            override fun delete(secretId: String) = Unit
        },
        relayApiFactory = { throw AssertionError("本测试不应发起网络请求") },
        bankStore = MemoryBankFileSystem().store(),
        bankUpdateClient = BankUpdateClient(fetcher = FakeBankFetcher()),
        skipRestore = true,
    ).apply {
        // The real panel only shows itself once the bank is loaded; these tests skip the
        // store read, so they wait for the bank load instead of racing it.
        runBlocking { awaitIdle() }
    }

    /**
     * Drives the API path offline. The relay is always a fake, so nothing here
     * touches the network; `skipRestore` keeps the initial store read out of the way
     * and the supplier is injected through the store instead.
     */
    private fun apiViewModel(
        api: RelayApi,
        store: SupplierStore = SingleSupplierStore(testSupplier()),
        /** False runs the real startup read, for the tests that watch it complete. */
        skipRestore: Boolean = true,
        /** The main test dispatcher keeps a startup read deterministic; IO would not. */
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        bankFiles: MemoryBankFileSystem = MemoryBankFileSystem(),
        bankFetcher: FakeBankFetcher = FakeBankFetcher(),
        appVersionCode: Long = 10_400L,
    ): FingerprintViewModel = FingerprintViewModel(
        supplierStore = store,
        secretStore = object : SecretStore {
            override fun put(value: String): String = "secret-1"
            override fun get(secretId: String): String = "api-key"
            override fun delete(secretId: String) = Unit
        },
        relayApiFactory = { api },
        bankStore = bankFiles.store(),
        bankUpdateClient = BankUpdateClient(fetcher = bankFetcher),
        appVersionCode = appVersionCode,
        skipRestore = skipRestore,
        ioDispatcher = ioDispatcher,
    ).apply {
        // A skipped restore still loads the bank, and no test may race that.
        if (skipRestore) runBlocking { awaitIdle() }
    }

    /** A round ready to dispatch: supplier chosen, models ticked, challenges seeded. */
    private fun readyApiViewModel(
        api: RelayApi,
        parallel: Boolean = true,
        models: List<String> = listOf("test-model"),
        bankFetcher: FakeBankFetcher = FakeBankFetcher(),
    ): FingerprintViewModel = apiViewModel(api, bankFetcher = bankFetcher).apply {
        selectSupplier("sup-1")
        // Ticked one at a time, exactly as the checkboxes do; the tick order is what
        // the round follows.
        models.forEach { toggleModelSelection(it) }
        // Seeds the three challenges; without this the progress list is empty
        // because skipRestore skipped the initial load.
        selectMode(DetectionMode.API)
        updateParallel(parallel)
    }

    /**
     * A ready round whose supplier came from a real startup read.
     *
     * The store tests need a view model that is actually holding state, so they cannot
     * use `skipRestore`; the test dispatcher keeps that read on this thread.
     */
    private fun readyApiViewModelFor(
        store: SupplierStore,
        api: RelayApi,
        models: List<String> = listOf("test-model"),
        bankFiles: MemoryBankFileSystem = MemoryBankFileSystem(),
        bankFetcher: FakeBankFetcher = FakeBankFetcher(),
        appVersionCode: Long = 10_400L,
    ): FingerprintViewModel = apiViewModel(
        api = api,
        store = store,
        skipRestore = false,
        ioDispatcher = mainDispatcher,
        bankFiles = bankFiles,
        bankFetcher = bankFetcher,
        appVersionCode = appVersionCode,
    ).apply {
        models.forEach { toggleModelSelection(it) }
        selectMode(DetectionMode.API)
    }

    /** Waits for the batch list to leave its interim states, or fails on a stall. */
    private fun awaitBatchSettled(subject: FingerprintViewModel) {
        runBlocking {
            withTimeout(BATCH_SETTLE_TIMEOUT_MS) {
                while (subject.uiState.value.batchResults.any {
                        it.status == ModelDetectionStatus.PENDING ||
                            it.status == ModelDetectionStatus.RUNNING
                    }
                ) {
                    delay(10)
                }
            }
        }
    }

    @Test
    fun `manual paste survives in the same field the card renders`() {
        val subject = viewModel()
        subject.selectMode(DetectionMode.MANUAL)
        subject.regenerateChallenges()

        val answer = "[" + (1..300).joinToString(",") + "]"
        subject.updateManualAnswer(0, answer)

        val entry = subject.uiState.value.progress[0]
        assertEquals("输入必须落在卡片读取的字段上", answer, entry.answer)
        assertEquals(300, entry.parsedNumbers)
    }

    @Test
    fun `manual analysis scores a pasted answer without a network call`() {
        val subject = viewModel()
        subject.selectMode(DetectionMode.MANUAL)
        subject.regenerateChallenges()

        // 300 ascending values comfortably clear the 55% floor for any probe length.
        val answer = "[" + (1..300).joinToString(",") + "]"
        subject.updateManualAnswer(0, answer)
        subject.runManualAnalysis()

        val state = subject.uiState.value
        assertNotNull("粘贴的回答必须能产生分析结果", state.analysis)
        assertEquals(1, state.analysis!!.usableAnswers)
        assertEquals(ChallengeState.RECEIVED, state.progress[0].state)
        assertFalse(state.isRunning)
    }

    @Test
    fun `analysis is rejected when the paste is too short`() {
        val subject = viewModel()
        subject.selectMode(DetectionMode.MANUAL)
        subject.regenerateChallenges()

        subject.updateManualAnswer(0, "[1,2,3,4,5]")
        subject.runManualAnalysis()

        val state = subject.uiState.value
        // Every answer is below the floor, so the bank has nothing usable to score.
        assertNull("数字不足时不应给出候选", state.analysis)
        assertEquals(ChallengeState.REJECTED, state.progress[0].state)
        assertTrue(state.isMessageError)
    }

    @Test
    fun `regenerate really replaces the challenge set`() {
        val subject = viewModel()
        subject.regenerateChallenges()
        val before = subject.uiState.value.progress.map { it.challenge.id }

        subject.regenerateChallenges()

        val after = subject.uiState.value.progress.map { it.challenge.id }
        assertEquals("题目数量保持三题", 3, after.size)
        assertNotEquals("重新生成必须换题", before, after)
    }

    @Test
    fun `regenerate clears answers that belonged to the old challenges`() {
        val subject = viewModel()
        subject.regenerateChallenges()
        subject.updateManualAnswer(0, "[1,2,3]")

        subject.regenerateChallenges()

        assertEquals("", subject.uiState.value.progress[0].answer)
    }

    @Test
    fun `retry replaces only the targeted challenge and drops its answer`() {
        val subject = viewModel()
        subject.regenerateChallenges()
        subject.updateManualAnswer(0, "[1,2,3]")
        subject.updateManualAnswer(1, "[4,5,6]")
        val untouched = subject.uiState.value.progress[2].challenge.id

        subject.retryChallenge(0)

        val state = subject.uiState.value
        assertEquals("", state.progress[0].answer)
        assertEquals("其他题目的输入必须保留", "[4,5,6]", state.progress[1].answer)
        assertEquals(untouched, state.progress[2].challenge.id)
    }

    @Test
    fun `retry avoids repeating a length already in the set`() {
        val subject = viewModel()
        subject.regenerateChallenges()
        val used = subject.uiState.value.progress.map { it.challenge.expectedCount }

        subject.retryChallenge(0)

        val lengths = subject.uiState.value.progress.map { it.challenge.expectedCount }
        assertEquals("三条题目长度必须互不相同", 3, lengths.toSet().size)
        assertEquals(used.size, lengths.size)
    }

    @Test
    fun `retry beyond the list is ignored instead of throwing`() {
        val subject = viewModel()
        subject.regenerateChallenges()
        val before = subject.uiState.value.progress.map { it.challenge.id }

        subject.retryChallenge(99)

        assertEquals(before, subject.uiState.value.progress.map { it.challenge.id })
    }

    @Test
    fun `validity floor is shared between the bank and the paste preview`() {
        val subject = viewModel()
        val subjectBank = bank()
        val floor = subjectBank.minimumValidNumbers

        assertEquals(
            "面板与评分器必须使用同一个阈值",
            subjectBank.minimumNumbersFor(300),
            minimumNumbersFor(300, floor),
        )
        // 55% of 300 is 165, which is above the absolute floor of 80.
        assertEquals(165, subjectBank.minimumNumbersFor(300))
        assertEquals("短题目由绝对下限兜底", floor, subjectBank.minimumNumbersFor(10))
    }

    @Test
    fun `switching mode keeps the challenge set stable`() {
        val subject = viewModel()
        subject.regenerateChallenges()
        val ids = subject.uiState.value.progress.map { it.challenge.id }

        subject.selectMode(DetectionMode.MANUAL)
        subject.selectMode(DetectionMode.API)

        assertEquals(ids, subject.uiState.value.progress.map { it.challenge.id })
    }

    @Test
    fun `manual analysis needs at least one non-blank answer`() {
        val subject = viewModel()
        subject.selectMode(DetectionMode.MANUAL)
        subject.regenerateChallenges()

        subject.runManualAnalysis()

        val state = subject.uiState.value
        assertNull(state.analysis)
        assertTrue("空粘贴必须给出提示", state.isMessageError)
    }

    @Test
    fun `a partially pasted set scores only the answers that clear the floor`() {
        val subject = viewModel()
        subject.selectMode(DetectionMode.MANUAL)
        subject.regenerateChallenges()

        subject.updateManualAnswer(0, "[" + (1..300).joinToString(",") + "]")
        subject.updateManualAnswer(1, "[1,2,3]")
        subject.runManualAnalysis()

        val state = subject.uiState.value
        assertEquals("只有第一条回答可用于检测", 1, state.analysis?.usableAnswers)
        assertEquals(ChallengeState.RECEIVED, state.progress[0].state)
        assertEquals(ChallengeState.REJECTED, state.progress[1].state)
        // The third slot was never touched and stays pending rather than claiming rejection.
        assertEquals(ChallengeState.PENDING, state.progress[2].state)
        assertEquals(0, state.progress[2].parsedNumbers)
    }

    @Test
    fun `a failed api request keeps its reason and retry affordance`() {
        val api = FakeCompletionApi(
            answers = emptyList(),
            failure = TestError(ErrorKind.UPSTREAM, "上游返回 HTTP 502"),
        )
        val subject = readyApiViewModel(api)

        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertFalse(state.isRunning)
        assertEquals("三条题目各发一次请求", 3, api.calls.get())
        for (entry in state.progress) {
            // A blank answer and a failure look alike, so the reason must survive the
            // scoring pass; dropping it also drops the per-question retry button.
            assertEquals(ChallengeState.REJECTED, entry.state)
            assertEquals("失败原因必须留在卡片上", "上游返回 HTTP 502", entry.error)
        }
        assertNull("三条回答都没能用于检测", state.analysis)
        assertTrue(state.isMessageError)

        // 重试本题 = 只重问这一题，不再把整轮推倒：请求数只 +1，题目本身不换（换题会让
        // 同一轮里其它模型对这道题的回答失去可比性），并且立刻按新回答重新评分。
        val callsBeforeRetry = api.calls.get()
        val before = state.progress[0].challenge.id
        subject.retryChallenge(0)
        runBlocking { subject.awaitIdle() }

        assertEquals("失败后重试只重问这一题", callsBeforeRetry + 1, api.calls.get())
        assertEquals("重试的是同一道题", before, subject.uiState.value.progress[0].challenge.id)
        assertEquals("重试后仍要给出这轮的结论", 1, subject.uiState.value.batchResults.size)
    }

    @Test
    fun `retrying one question scores the interrupted round instead of restarting it`() {
        val api = FakeCompletionApi(
            answers = goldenCase().answers,
            failure = TestError(ErrorKind.UPSTREAM, "上游返回 HTTP 502"),
        )
        val subject = readyApiViewModel(api)
        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }
        assertEquals(ModelDetectionStatus.FAILED, subject.uiState.value.batchResults.single().status)

        // 上游恢复：从这一张卡片重试，这一轮必须接着跑完，而不是三题从头再来。
        api.failure = null
        subject.retryChallenge(0)
        runBlocking { subject.awaitIdle() }

        assertEquals("只补发这一题", 4, api.calls.get())
        val row = subject.uiState.value.batchResults.single()
        assertEquals(ModelDetectionStatus.DONE, row.status)
        assertNotNull("重试后这一行必须有候选", row.candidateName)
        assertNotNull("单模型重试要把候选榜放回面板", subject.uiState.value.analysis)
    }

    @Test
    fun `a single-question retry only touches the slot it was asked for`() {
        val api = FakeCompletionApi(goldenCase().answers)
        val subject = readyApiViewModel(api, models = listOf("m-one", "m-two"))
        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }
        assertEquals(6, api.calls.get())
        val idsBefore = subject.uiState.value.progress.map { it.challenge.id }

        subject.retryChallenge(1)
        runBlocking { subject.awaitIdle() }

        assertEquals("两个模型各补发这一题，其它两题不许重发", 8, api.calls.get())
        assertEquals("题目集合不动", idsBefore, subject.uiState.value.progress.map { it.challenge.id })
        assertTrue(
            "每行的结论都要按新回答刷新",
            subject.uiState.value.batchResults.all { it.status == ModelDetectionStatus.DONE },
        )
    }

    @Test
    fun `cancelling after two answers keeps them for a three-answer retry score`() {
        val golden = goldenCase()
        val api = GatedCompletionApi(golden.answers, gatedPosition = 2)
        val subject = readyApiViewModelFor(SingleSupplierStore(testSupplier()), api).apply {
            updateParallel(false)
        }
        subject.runApiDetection()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.started.await() } }
        val before = subject.uiState.value.progress
        assertEquals(listOf(ChallengeState.RECEIVED, ChallengeState.RECEIVED, ChallengeState.REQUESTING), before.map { it.state })

        subject.cancelRun()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }
        val cancelled = subject.uiState.value
        assertEquals(before.take(2), cancelled.progress.take(2))
        assertEquals(ChallengeState.PENDING, cancelled.progress[2].state)
        assertEquals(setOf(2), cancelled.retryableQuestionIndices)

        subject.retryChallenge(2)
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }

        val state = subject.uiState.value
        assertEquals("只补发中断的第三题", 4, api.models.size)
        assertEquals(before.map { it.challenge.id }, state.progress.map { it.challenge.id })
        assertEquals(before.take(2), state.progress.take(2))
        assertTrue(state.progress.all { it.state == ChallengeState.RECEIVED })
        assertEquals(3, state.analysis?.usableAnswers)
        assertEquals(3, state.batchResults.single().usableAnswers)
        assertEquals(ModelDetectionStatus.DONE, state.batchResults.single().status)
        assertEquals(emptySet<Int>(), state.retryableQuestionIndices)
    }

    @Test
    fun `an early failed model exposes retryable questions after a healthy last model`() {
        val failingModels = mutableSetOf("broken")
        val api = FakeCompletionApi(goldenCase().answers, failingModels = failingModels)
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            api,
            models = listOf("broken", "healthy"),
        )
        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertEquals(ModelDetectionStatus.FAILED, state.batchResults.first().status)
        assertEquals(ModelDetectionStatus.DONE, state.batchResults.last().status)
        assertTrue("最后一个模型的三题都健康，仍要能重试前一个模型", state.progress.all { it.state == ChallengeState.RECEIVED })
        assertEquals(setOf(0, 1, 2), state.retryableQuestionIndices)

        failingModels.clear()
        subject.retryChallenge(0)
        runBlocking { subject.awaitIdle() }

        val retried = subject.uiState.value
        assertEquals("显式重试仍为两个模型各补发一次", 8, api.calls.get())
        assertEquals(1, retried.batchResults.first().usableAnswers)
        assertEquals(3, retried.batchResults.last().usableAnswers)
        assertEquals(setOf(1, 2), retried.retryableQuestionIndices)
        subject.regenerateChallenges()
        assertEquals("换题后旧轮次的重试入口必须清除", emptySet<Int>(), subject.uiState.value.retryableQuestionIndices)
    }

    @Test
    fun `a batch row carries the candidate ranking the single-model card shows`() {
        val golden = goldenCase()
        val api = FakeCompletionApi(golden.answers)
        val subject = readyApiViewModel(api, models = listOf("first", "second"))

        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }

        for (row in subject.uiState.value.batchResults) {
            assertTrue(
                "整份候选榜要跟着行一起留下（不是只留冠军），实际 ${row.candidates.size} 条",
                row.candidates.size > 1,
            )
            assertEquals(golden.top1, row.candidates.first().displayName)
        }
    }

    @Test
    fun `retrying before any round still swaps the prompt`() {
        // 没有可续的一轮（还没跑过、或题目已重新生成）时，这个按钮的老含义保持不变：
        // 换一道不同长度的题。
        val subject = viewModel()
        subject.selectMode(DetectionMode.API)
        val before = subject.uiState.value.progress[1].challenge.id

        subject.retryChallenge(1)

        val after = subject.uiState.value.progress[1]
        assertNotEquals("没跑过时重试就是换题", before, after.challenge.id)
        assertEquals(ChallengeState.PENDING, after.state)
    }

    @Test
    fun `parallel detection issues the three requests concurrently`() {
        val api = FakeCompletionApi(goldenCase().answers)
        val subject = readyApiViewModel(api, parallel = true)

        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }

        assertEquals("并行模式下三条请求必须同时在飞", 3, api.maxConcurrent.get())
        assertEquals(3, api.calls.get())
        assertEquals(ChallengeState.RECEIVED, subject.uiState.value.progress[0].state)
    }

    @Test
    fun `sequential detection never overlaps requests`() {
        val api = FakeCompletionApi(goldenCase().answers)
        val subject = readyApiViewModel(api, parallel = false)

        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }

        assertEquals("顺序模式下同一时刻只能有一条请求在飞", 1, api.maxConcurrent.get())
        assertEquals(3, api.calls.get())
    }

    @Test
    fun `a successful round ranks the reference top candidate`() {
        val golden = goldenCase()
        assertEquals("夹具必须仍是文档记录的那一条样例", "gpt-5.4", golden.top1)
        val api = FakeCompletionApi(golden.answers)
        val subject = readyApiViewModel(api)

        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertNotNull("检测必须给出候选", state.analysis)
        assertEquals(3, state.analysis!!.usableAnswers)
        assertEquals(golden.top1, state.analysis!!.prediction?.modelId)
        assertTrue(state.progress.all { it.state == ChallengeState.RECEIVED })
    }

    @Test
    fun `detection refuses to run without a ticked model`() {
        val api = FakeCompletionApi(goldenCase().answers)
        val subject = apiViewModel(api).apply {
            selectSupplier("sup-1")
            selectMode(DetectionMode.API)
        }

        subject.runApiDetection()

        val state = subject.uiState.value
        // The field is a filter now, so a typed keyword cannot stand in for a choice:
        // the round runs on ticks, and typing is not one.
        assertEquals("请至少勾选一个模型", state.message)
        assertTrue(state.isMessageError)
        assertEquals("没有勾选模型就不该发出请求", 0, api.calls.get())
        assertFalse(state.isRunning)
    }

    @Test
    fun `a typed keyword is not a selection`() {
        val subject = apiViewModel(FakeCompletionApi(goldenCase().answers)).apply {
            selectSupplier("sup-1")
            selectMode(DetectionMode.API)
            updateModelFilter("test-model")
        }

        assertEquals("筛选词不得被当成模型名", emptyList<String>(), subject.uiState.value.selectedModels)
        assertTrue(subject.uiState.value.batchResults.isEmpty())
    }

    @Test
    fun `a batch tests the ticked models one after another in tick order`() {
        val api = FakeCompletionApi(goldenCase().answers)
        val subject = readyApiViewModel(
            api,
            models = listOf("zz-last", "aa-first"),
        )

        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }

        // Six requests: three challenges each, grouped by model. A model's three
        // challenges may interleave with each other, but never with the other model's.
        assertEquals(6, api.calls.get())
        assertEquals(
            listOf("zz-last", "zz-last", "zz-last", "aa-first", "aa-first", "aa-first"),
            api.models.toList(),
        )
    }

    @Test
    fun `a batch publishes one row per model and keeps the single ranking off screen`() {
        val golden = goldenCase()
        val api = FakeCompletionApi(golden.answers)
        val subject = readyApiViewModel(api, models = listOf("first", "second"))

        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertFalse(state.isRunning)
        assertNull("批次里每个模型的排名都会误导其他行，所以不进详情卡", state.analysis)

        assertEquals(listOf("first", "second"), state.batchResults.map { it.model })
        for (row in state.batchResults) {
            assertEquals(ModelDetectionStatus.DONE, row.status)
            assertEquals("每一行都要有自己的候选", golden.top1, row.candidateName)
            assertEquals(3, row.usableAnswers)
            assertNull(row.error)
        }
    }

    @Test
    fun `a batch continues past a failing model and records its reason`() {
        val golden = goldenCase()
        val api = FakeCompletionApi(golden.answers, failingModels = setOf("broken"))
        val subject = readyApiViewModel(api, models = listOf("broken", "healthy"))

        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }

        val rows = subject.uiState.value.batchResults
        assertEquals(2, rows.size)
        assertEquals(ModelDetectionStatus.FAILED, rows[0].status)
        assertEquals("上游返回 HTTP 502", rows[0].error)
        // The point of the batch: a bad model must not take the good ones with it.
        assertEquals(ModelDetectionStatus.DONE, rows[1].status)
        assertEquals(golden.top1, rows[1].candidateName)
    }

    @Test
    fun `cancelling mid batch closes every row that never got a verdict`() {
        val api = GatedCompletionApi(goldenCase().answers)
        val subject = readyApiViewModel(
            api,
            parallel = false,
            models = listOf("m-1", "m-2", "m-3"),
        )

        subject.runApiDetection()
        runBlocking { api.started.await() }
        subject.cancelRun()
        awaitBatchSettled(subject)

        val state = subject.uiState.value
        assertFalse(state.isRunning)
        assertNull(state.activeModel)
        assertEquals(3, state.batchResults.size)
        for (row in state.batchResults) {
            // Neither "running forever" nor silently dropped from the list.
            assertEquals("${row.model} 行必须收尾", ModelDetectionStatus.FAILED, row.status)
            assertEquals("已取消", row.error)
        }
        // Only the in-flight model ever reached the relay.
        assertEquals(listOf("m-1"), api.models.toList())
    }

    @Test
    fun `switching supplier drops the previous supplier's ticked models`() {
        val subject = apiViewModel(FakeCompletionApi(goldenCase().answers)).apply {
            selectSupplier("sup-1")
            selectMode(DetectionMode.API)
            toggleModelSelection("test-model")
            updateModelFilter("test")
        }

        subject.selectSupplier("sup-1")

        val state = subject.uiState.value
        assertEquals("换供应商后旧目录的勾选必须清空", emptyList<String>(), state.selectedModels)
        assertEquals("", state.modelFilter)
    }

    @Test
    fun `a single ticked model keeps its detailed ranking and gets one row`() {
        val golden = goldenCase()
        val api = FakeCompletionApi(golden.answers)
        val subject = readyApiViewModel(api, models = listOf("solo"))

        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertNotNull("单模型必须保留候选详情", state.analysis)
        assertEquals(golden.top1, state.analysis?.prediction?.displayName)
        assertEquals(1, state.batchResults.size)
        assertEquals(ModelDetectionStatus.DONE, state.batchResults[0].status)
    }

    @Test
    fun `a model outside the catalogue can still be detected after being ticked`() {
        // The fallback row in the picker: a supplier without a pulled catalogue must
        // not be un-testable.
        val golden = goldenCase()
        val api = FakeCompletionApi(golden.answers)
        val subject = readyApiViewModel(api, models = listOf("typed-by-hand"))

        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }

        assertEquals(listOf("typed-by-hand"), api.models.toSet().toList())
        assertEquals(ModelDetectionStatus.DONE, subject.uiState.value.batchResults[0].status)
    }

    @Test
    fun `re-entering the panel picks up a catalogue that was pulled afterwards`() {
        // The device path: this view model is built with the activity, the user pulls
        // models on the "模型测试" tab, then walks over here. Found on the emulator,
        // where the picker stayed empty with the catalogue already saved.
        val store = MutableSupplierStore(listOf(testSupplier()))
        val subject = apiViewModel(FakeCompletionApi(goldenCase().answers), store)
        assertTrue("起点的目录就是空的", subject.uiState.value.models.isEmpty())

        store.suppliers = listOf(testSupplier(models = listOf("m-one", "m-two")))
        subject.refreshCatalogue()
        runBlocking { subject.awaitIdle() }

        assertEquals(listOf("m-one", "m-two"), subject.uiState.value.models)
    }

    @Test
    fun `a catalogue refresh keeps the ticks and the filter`() {
        val store = MutableSupplierStore(listOf(testSupplier(models = listOf("m-one", "m-two"))))
        val subject = apiViewModel(FakeCompletionApi(goldenCase().answers), store).apply {
            selectSupplier("sup-1")
            selectMode(DetectionMode.API)
            toggleModelSelection("m-two")
            updateModelFilter("m-")
        }

        store.suppliers = listOf(testSupplier(models = listOf("m-one", "m-two", "m-three")))
        subject.refreshCatalogue()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertEquals(listOf("m-one", "m-two", "m-three"), state.models)
        assertEquals("刷新不得丢掉用户勾选的模型", listOf("m-two"), state.selectedModels)
        assertEquals("m-", state.modelFilter)
    }

    @Test
    fun `a catalogue refresh drops the ticks when the supplier itself is gone`() {
        val store = MutableSupplierStore(
            listOf(testSupplier(models = listOf("m-one")), otherSupplier()),
        )
        val subject = apiViewModel(FakeCompletionApi(goldenCase().answers), store).apply {
            selectSupplier("sup-1")
            selectMode(DetectionMode.API)
            toggleModelSelection("m-one")
        }

        // The configured supplier was deleted on the other tab.
        store.suppliers = listOf(otherSupplier())
        subject.refreshCatalogue()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertEquals("sup-2", state.selectedSupplierId)
        assertEquals(listOf("other-model"), state.models)
        assertEquals("换了供应商，旧目录的勾选必须让位", emptyList<String>(), state.selectedModels)
    }

    @Test
    fun `a failed startup read recovers when the panel is re-entered`() {
        // Without this the panel would sit on its error screen forever: refreshCatalogue
        // was the only thing that re-read the store, and it left loadError untouched.
        val store = MutableSupplierStore(listOf(testSupplier(models = listOf("m-one"))))
        store.failReads = true
        val subject = apiViewModel(
            api = FakeCompletionApi(goldenCase().answers),
            store = store,
            skipRestore = false,
            ioDispatcher = mainDispatcher,
        )
        runBlocking { subject.awaitIdle() }
        assertNotNull("启动读取失败必须报出来", subject.uiState.value.loadError)

        store.failReads = false
        subject.refreshCatalogue()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertNull("重进面板必须能摘掉错误页", state.loadError)
        assertFalse(state.isLoading)
        assertEquals(listOf("m-one"), state.models)
    }

    @Test
    fun `a refresh that fails leaves a working picker alone`() {
        // The other half of the same rule: a background refresh must never blank a panel
        // that is working, or a transient read error costs the user the whole screen.
        val store = MutableSupplierStore(listOf(testSupplier(models = listOf("m-one"))))
        val subject = apiViewModel(
            api = FakeCompletionApi(goldenCase().answers),
            store = store,
            skipRestore = false,
            ioDispatcher = mainDispatcher,
        )
        runBlocking { subject.awaitIdle() }
        assertNull(subject.uiState.value.loadError)

        store.failReads = true
        subject.refreshCatalogue()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertNull("刷新失败不得把可用面板变成错误页", state.loadError)
        assertEquals(listOf("m-one"), state.models)
    }

    @Test
    fun `a refresh does not race the startup read`() {
        val store = MutableSupplierStore(listOf(testSupplier(models = listOf("m-one"))))
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        val subject = apiViewModel(
            api = FakeCompletionApi(goldenCase().answers),
            store = store,
            skipRestore = false,
            ioDispatcher = mainDispatcher,
        )
        assertTrue("启动读取还挂在门上，面板应在加载态", subject.uiState.value.isLoading)

        subject.refreshCatalogue()

        assertEquals("在飞的读取还没落地，刷新不得抢着再读一次", 1, store.reads.get())
        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
        assertFalse(subject.uiState.value.isLoading)
        assertEquals(listOf("m-one"), subject.uiState.value.models)
    }

    @Test
    fun `a prefill clears a filter that would hide the model`() {
        val subject = apiViewModel(FakeCompletionApi(goldenCase().answers)).apply {
            selectSupplier("sup-1")
        }
        subject.updateModelFilter("zzz")

        // The tester tab prefills without a supplier id when no supplier is active.
        subject.prefill(null, "typed-by-hand")

        val state = subject.uiState.value
        assertEquals(listOf("typed-by-hand"), state.selectedModels)
        assertEquals("筛选框不能继续挡着刚预填的模型", "", state.modelFilter)
    }

    @Test
    fun `a prefill that arrives during the startup read still clears the filter`() {
        val store = MutableSupplierStore(listOf(testSupplier(models = listOf("m-one"))))
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        val subject = apiViewModel(
            api = FakeCompletionApi(goldenCase().answers),
            store = store,
            skipRestore = false,
            ioDispatcher = mainDispatcher,
        )
        subject.updateModelFilter("zzz")
        // The store has not answered yet, so the request is parked instead of dropped.
        subject.prefill("sup-1", "m-two")

        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertEquals(listOf("m-two"), state.selectedModels)
        assertEquals("预填落地时也要摘掉筛选", "", state.modelFilter)
    }

    @Test
    fun `prefill is ignored while credentials are preparing`() {
        val store = MutableSupplierStore(listOf(testSupplier(), otherSupplier()))
        val api = FakeCompletionApi(goldenCase().answers)
        val subject = readyApiViewModelFor(store, api, models = listOf("m-one", "m-two"))
        val before = subject.uiState.value
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        subject.runApiDetection()
        assertFalse("凭据尚未读完，界面运行标记还未置位", subject.uiState.value.isRunning)
        assertEquals(2, store.reads.get())
        try {
            subject.prefill(null, "replacement")
            subject.prefill("sup-2", "other-model")
            assertEquals("准备中的批次和题目必须保留", before, subject.uiState.value)
        } finally {
            gate.complete(Unit)
            runBlocking { subject.awaitIdle() }
        }
        assertEquals(listOf("m-one", "m-two"), subject.uiState.value.batchResults.map { it.model })
        assertEquals(6, api.calls.get())
    }

    @Test
    fun `prefill is ignored while an active batch is requesting`() {
        val api = GatedCompletionApi(goldenCase().answers)
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            api,
            models = listOf("m-one", "m-two"),
        ).apply { updateParallel(false) }
        subject.runApiDetection()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.started.await() } }
        val before = subject.uiState.value
        assertTrue(before.isRunning)
        try {
            subject.prefill(null, "replacement")
            subject.prefill("sup-2", "other-model")
            assertEquals("活动批次的选中模型、结果行和题目不得被预填覆盖", before, subject.uiState.value)
        } finally {
            subject.cancelRun()
            runBlocking { subject.awaitIdle() }
        }
    }

    @Test
    fun `prefill and new runs wait until cancellation cleanup completes`() {
        val cleanupGate = CompletableDeferred<Unit>()
        val api = GatedCompletionApi(goldenCase().answers, cancellationGate = cleanupGate)
        val subject = readyApiViewModelFor(SingleSupplierStore(testSupplier()), api).apply {
            updateParallel(false)
        }
        subject.runApiDetection()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.started.await() } }
        subject.cancelRun()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.cancellationStarted.await() } }
        val cancelled = subject.uiState.value
        assertFalse(cancelled.isRunning)
        try {
            subject.prefill(null, "replacement")
            subject.prefill("sup-2", "other-model")
            assertEquals("取消清理期间预填也必须拒绝", cancelled, subject.uiState.value)
            subject.runApiDetection()
            subject.retryChallenge(0)
            assertEquals("取消尚未完成时不能再发任何请求", 1, api.models.size)
            assertEquals(cancelled, subject.uiState.value)
        } finally {
            cleanupGate.complete(Unit)
            runBlocking { subject.awaitIdle() }
        }

        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }
        assertEquals("取消真正完成后新轮次才可开跑", 4, api.models.size)
        assertEquals(ModelDetectionStatus.DONE, subject.uiState.value.batchResults.single().status)
    }

    @Test
    fun `a throwing request fails only its own model and the batch keeps going`() {
        // An unexpected throw from one challenge used to escape as a sibling
        // cancellation: the panel stopped silently and the rows stayed on "正在检测…"
        // for good. It has to stay that challenge's failure, with the reason on screen.
        val subject = readyApiViewModel(
            ThrowingCompletionApi(IllegalStateException("参考库不可读")),
            models = listOf("m-one", "m-two"),
        )

        subject.runApiDetection()
        // Joins the round itself: polling batchResults would return before the rows
        // exist, because they are only created once the credential load has finished.
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertFalse("异常后不能停在运行中", state.isRunning)
        assertNull(state.activeModel)
        assertEquals(
            "两行都要有结论，第二行证明批次没有被打断",
            listOf(ModelDetectionStatus.FAILED, ModelDetectionStatus.FAILED),
            state.batchResults.map { it.status },
        )
        assertEquals("参考库不可读", state.batchResults.first().error)
        // The reason also lands on the challenge cards, which is where the user looks
        // for a per-question explanation, and no snackbar repeats it.
        assertTrue(state.progress.all { it.state == ChallengeState.REJECTED })
        assertEquals("参考库不可读", state.progress.first().error)
    }

    @Test
    fun `an unexpected failure before the first request releases the panel`() {
        // The other reachable throw: the store read that resolves the supplier. It sits
        // before the loop, so only the last-resort net can keep it from escaping the
        // view model scope and taking the app down.
        val store = MutableSupplierStore(listOf(testSupplier()))
        val subject = readyApiViewModelFor(store, FakeCompletionApi(goldenCase().answers))
        runBlocking { subject.awaitIdle() }
        // refreshCatalogue is what re-reads the store here; make that read fail.
        store.failReads = true

        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertFalse("异常后不能停在运行中", state.isRunning)
        assertNull(state.activeModel)
        assertTrue("必须把原因告诉用户", state.isMessageError)
        assertEquals("配置读取失败", state.message)
    }

    @Test
    fun `the panel describes the detection package it actually loaded`() {
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
        )
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertEquals(BankSource.INSTALLED, state.bankSource)
        assertEquals(shippedBankBuiltAt(), state.referenceBuiltAt)
        assertEquals(fixtureBankBytes().size.toLong(), state.bankSizeBytes)
        assertEquals(shippedBankModelCount(), state.modelCount)
        assertNull(state.bankProblem)
    }

    @Test
    fun `an installed bank is the one the panel describes and scores with`() {
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFiles = MemoryBankFileSystem(installed = bankWithMinimumValid(123)),
        )
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertEquals(BankSource.INSTALLED, state.bankSource)
        // The floor travels with the installed bank; a panel that kept the packaged
        // bank's floor would judge answers by a rule the active bank does not have.
        assertEquals(123, state.minimumValidNumbers)
    }

    @Test
    fun `a package that cannot be parsed leaves the panel asking for a fresh download`() {
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFiles = MemoryBankFileSystem(installed = "broken".toByteArray()),
        )
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertEquals(BankSource.INSTALLED_UNREADABLE, state.bankSource)
        // Not an error screen: the panel stays operable and says what to do about it.
        assertNull("坏包不能把面板判死", state.loadError)
        assertTrue("必须说明包已无法解析", state.bankProblem!!.contains("无法解析"))
        assertEquals("", state.referenceBuiltAt)
        assertEquals(0, state.modelCount)
    }

    @Test
    fun `a corrupt file falls back to the package it replaced`() {
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFiles = MemoryBankFileSystem(
                installed = "broken".toByteArray(),
                backup = fixtureBankBytes(),
            ),
        )
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertEquals(BankSource.INSTALLED, state.bankSource)
        assertTrue(state.bankUsedBackup)
        assertEquals(shippedBankBuiltAt(), state.referenceBuiltAt)
    }

    @Test
    fun `a device with no package is told to download one`() {
        val fetcher = FakeBankFetcher().apply { publish(fixtureBankBytes()) }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFiles = MemoryBankFileSystem(installed = null),
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }
        assertEquals("构造本身不联网：检查由进入面板触发", emptyList<String>(), fetcher.urls)

        subject.refreshBankOnEntry()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertEquals(BankSource.NOT_PROVISIONED, state.bankSource)
        // The panel is useless without a package, so entering it asks once by itself —
        // and only once: the manifest request is the whole of its unprompted networking.
        assertEquals(listOf(FakeBankFetcher.MANIFEST_URL), fetcher.urls)
        assertNotNull("未安装时必须把下载入口摆出来", state.availableBankUpdate)
        assertNull("自动检查不上气泡，下载入口在卡片上", state.message)
    }

    @Test
    fun `the panel lists the models the installed package supports`() {
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
        )
        runBlocking { subject.awaitIdle() }

        val models = subject.uiState.value.bankModels
        assertEquals("列表条数必须与包内模型数一致", shippedBankModelCount(), models.size)
        assertEquals(
            "按包内顺序给出显示名与家族名",
            listOf(
                "gpt-5.4" to "GPT",
                "claude-sonnet-4.6" to "Claude",
                "gemini-3.7-flash" to "Gemini",
                "grok-4.5" to "Grok",
                "glm-5.3" to "GLM",
                "deepseek-v4-pro" to "DeepSeek",
            ),
            models.map { it.displayName to it.familyName },
        )
    }

    @Test
    fun `a device with no package lists no models`() {
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFiles = MemoryBankFileSystem(installed = null),
        )
        runBlocking { subject.awaitIdle() }

        assertEquals(
            "没有检测包就没有可列出的模型",
            emptyList<Any>(),
            subject.uiState.value.bankModels,
        )
    }

    @Test
    fun `entering the panel looks for a newer package even when one is installed`() {
        val fetcher = FakeBankFetcher().apply {
            publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp)
        }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }
        assertEquals("装上以后不再自动联网，只有进面板才查", emptyList<String>(), fetcher.urls)

        subject.refreshBankOnEntry()
        runBlocking { subject.awaitIdle() }

        assertEquals(listOf(FakeBankFetcher.MANIFEST_URL), fetcher.urls)
        assertEquals(patchStamp, subject.uiState.value.availableBankUpdate?.builtAt)
        assertNull("自动检查只把按钮摆出来，不弹气泡", subject.uiState.value.message)
    }

    @Test
    fun `an entry check that fails does not greet the user with an error`() {
        val fetcher = FakeBankFetcher(failure = IOException("网络不可用"))
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }

        subject.refreshBankOnEntry()
        runBlocking { subject.awaitIdle() }

        assertEquals(listOf(FakeBankFetcher.MANIFEST_URL), fetcher.urls)
        assertNull("用户没点的检查失败了不该弹错", subject.uiState.value.message)
        assertNull(subject.uiState.value.availableBankUpdate)
        assertFalse(subject.uiState.value.isCheckingBankUpdate)
    }

    @Test
    fun `an entry check asked for during the startup read is replayed once it lands`() {
        // The screen asks on every panel entry, but never twice; if it is dropped while
        // the panel is still loading, the first visit silently loses it — and on a fresh
        // install that check is what puts the download button on the empty card.
        val store = MutableSupplierStore(listOf(testSupplier(models = listOf("m-one"))))
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        val fetcher = FakeBankFetcher().apply { publish(fixtureBankBytes()) }
        val subject = apiViewModel(
            api = FakeCompletionApi(goldenCase().answers),
            store = store,
            skipRestore = false,
            ioDispatcher = mainDispatcher,
            bankFiles = MemoryBankFileSystem(installed = null),
            bankFetcher = fetcher,
        )
        assertTrue("启动读取还挂在门上，面板应在加载态", subject.uiState.value.isLoading)

        // The panel becomes visible and asks for its entry check while the read is running.
        subject.refreshBankOnEntry()
        assertEquals("在飞的启动读取还没落地，检查只能先记住", emptyList<String>(), fetcher.urls)

        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }

        assertEquals("读取落地后必须补上这次检查，且只补一次", listOf(FakeBankFetcher.MANIFEST_URL), fetcher.urls)
        assertNotNull("未安装时必须把下载入口摆出来", subject.uiState.value.availableBankUpdate)
    }

    @Test
    fun `a second run is refused while the first is still dispatching`() {
        // The guard cannot read `isRunning` alone: it is published only after the
        // credentials are in, so a double tap lands inside that window and starts two rounds.
        val api = FakeCompletionApi(goldenCase().answers)
        val subject = readyApiViewModel(api)

        subject.runApiDetection()
        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }

        assertEquals("双击只允许一轮（三条题目各一次）", 3, api.calls.get())
    }

    @Test
    fun `a round starts even while the entry check is still in flight`() {
        val api = FakeCompletionApi(goldenCase().answers)
        val gate = CompletableDeferred<Unit>()
        val fetcher = FakeBankFetcher().apply {
            publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp)
            this.gate = gate
        }
        val subject = readyApiViewModel(api, bankFetcher = fetcher)
        runBlocking { subject.awaitIdle() }
        // 进面板的检查没人按过，只能占它自己的那个门：用户点下的检测必须照跑，
        // 不能被一个用户没请求的 HTTP 请求挡在门外。
        subject.refreshBankOnEntry()
        assertTrue("静默检查必须真的在飞", subject.uiState.value.isCheckingBankInBackground)

        subject.runApiDetection()
        // 只等这一轮自己跑完：等 gates 上的静默检查会让「拒绝开跑」的变异把测试卡死，
        // 而卡死既不是红也不是绿，等于没证明。
        runBlocking {
            withTimeout(BATCH_SETTLE_TIMEOUT_MS) {
                while (
                    subject.uiState.value.batchResults.isEmpty() ||
                    subject.uiState.value.batchResults.any {
                        it.status == ModelDetectionStatus.PENDING ||
                            it.status == ModelDetectionStatus.RUNNING
                    }
                ) {
                    delay(10)
                }
            }
        }

        assertEquals("静默检查不得挡住检测", 3, api.calls.get())
        assertFalse("静默检查要让位", subject.uiState.value.isCheckingBankInBackground)
        assertNull("没人按过的检查不该留话", subject.uiState.value.message)
        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
    }

    @Test
    fun `a round is refused while the package install is in flight`() {
        val api = FakeCompletionApi(goldenCase().answers)
        val fetcher = FakeBankFetcher().apply { publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp) }
        val subject = readyApiViewModel(api, bankFetcher = fetcher)
        runBlocking { subject.awaitIdle() }

        subject.checkBankUpdate()
        runBlocking { subject.awaitIdle() }
        assertNotNull("先要有可下载的检测包", subject.uiState.value.availableBankUpdate)

        // 换包才是真的危险：它把评分用的检测包换掉，一轮里的行就会带着两个不同检测包
        // 的排名。所以安装期间必须拒绝开跑，并且说清楚原因。
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate
        subject.installBankUpdate()
        assertTrue("安装必须真的在飞", subject.uiState.value.isInstallingBank)

        subject.runApiDetection()

        assertEquals("换包期间不得开跑", 0, api.calls.get())
        assertNotNull("必须告诉用户为什么没跑", subject.uiState.value.message)
        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
    }

    @Test
    fun `a check the user pressed takes over the entry check instead of queueing behind it`() {
        val api = FakeCompletionApi(goldenCase().answers)
        val gate = CompletableDeferred<Unit>()
        val fetcher = FakeBankFetcher().apply { publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp) }
        // mainDispatcher 作 IO：静默检查会在清单请求里确定性停住，取位一次算一次，
        // 不然「谁发了几次请求」变成调度竞速，断言会闪。
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            api,
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }

        fetcher.gate = gate
        subject.refreshBankOnEntry()
        assertTrue(subject.uiState.value.isCheckingBankInBackground)
        assertEquals("静默检查先发出一次清单请求并停在门上", 1, fetcher.urls.size)

        // 用户的请求优先：静默检查让位，按下的检查自己跑完并汇报结果，而不是排在一个
        // 用户没请求的请求后面等它超时。这一步之后的断言不再 awaitIdle：让位的语义要是
        // 退化成「排队」，awaitIdle 就会挂在那个永不放开的门上（测试卡死 = 没证明）。
        fetcher.gate = null
        subject.checkBankUpdate()

        assertFalse("静默检查已让位", subject.uiState.value.isCheckingBankInBackground)
        assertEquals(
            "让位之后按下的检查才是第二次清单请求",
            2,
            fetcher.urls.count { it == FakeBankFetcher.MANIFEST_URL },
        )
        assertNotNull("用户按下的检查必须汇报结果", subject.uiState.value.message)
        assertFalse(subject.uiState.value.isCheckingBankUpdate)
        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
    }

    @Test
    fun `reentering during a silent bank check keeps one request for manual takeover`() {
        val gate = CompletableDeferred<Unit>()
        val fetcher = FakeBankFetcher().apply {
            publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp)
            this.gate = gate
        }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
        )
        subject.refreshBankOnEntry()
        assertTrue(subject.uiState.value.isCheckingBankInBackground)
        assertEquals(listOf(FakeBankFetcher.MANIFEST_URL), fetcher.urls)

        try {
            // A second entry must keep the first check reachable rather than launch
            // another request and replace the job that manual takeover needs to cancel.
            subject.refreshBankOnEntry()
            assertEquals("重复进入只保留原来的静默清单请求", listOf(FakeBankFetcher.MANIFEST_URL), fetcher.urls)
            assertTrue(subject.uiState.value.isCheckingBankInBackground)
            assertFalse(subject.uiState.value.isCheckingBankUpdate)
            assertNull(subject.uiState.value.message)

            fetcher.gate = null
            subject.checkBankUpdate()
            assertEquals("手动接管才发出第二次请求", 2, fetcher.urls.size)
            assertEquals(patchStamp, subject.uiState.value.availableBankUpdate?.builtAt)
            assertNotNull(subject.uiState.value.message)
            assertFalse(subject.uiState.value.isCheckingBankInBackground)
            assertFalse(subject.uiState.value.isCheckingBankUpdate)
        } finally {
            gate.complete(Unit)
            runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }
        }
    }

    @Test
    fun `a round on a device with no package reports the missing package`() {
        val api = FakeCompletionApi(goldenCase().answers)
        val bare = apiViewModel(
            api = api,
            bankFiles = MemoryBankFileSystem(installed = null),
            bankFetcher = FakeBankFetcher(),
        ).apply {
            selectSupplier("sup-1")
            toggleModelSelection("test-model")
            selectMode(DetectionMode.API)
        }
        runBlocking { bare.awaitIdle() }

        bare.runApiDetection()
        runBlocking { bare.awaitIdle() }

        val state = bare.uiState.value
        assertTrue("必须说清楚是缺检测包，实际「${state.message}」", state.message!!.contains("检测包"))
        assertTrue(state.isMessageError)
        // Nothing can be scored without a package, so refusing up front must also mean
        // spending no request: a challenge-level rejection would blame the model.
        assertEquals("缺检测包时不得发出任何请求", 0, api.calls.get())
    }

    @Test
    fun `a check offers the published bank and installing it switches the panel over`() {
        val patched = bankWithBuiltAt(patchStamp)
        val fetcher = FakeBankFetcher().apply { publish(patched, builtAt = patchStamp) }
        val files = MemoryBankFileSystem()
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFiles = files,
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }

        subject.checkBankUpdate()
        runBlocking { subject.awaitIdle() }

        assertEquals(patchStamp, subject.uiState.value.availableBankUpdate?.builtAt)
        assertFalse(subject.uiState.value.isCheckingBankUpdate)

        subject.installBankUpdate()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertEquals(BankSource.INSTALLED, state.bankSource)
        assertEquals(patchStamp, state.referenceBuiltAt)
        assertNull("装上以后就没有待更新的版本了", state.availableBankUpdate)
        assertFalse(state.isInstallingBank)
        assertNotNull("文件必须真的写下去", files.installed)
    }

    @Test
    fun `the update survives into the next load of the panel`() {
        val patched = bankWithBuiltAt(patchStamp)
        val files = MemoryBankFileSystem()
        val subject = apiViewModel(
            api = FakeCompletionApi(goldenCase().answers),
            store = SingleSupplierStore(testSupplier()),
            skipRestore = false,
            ioDispatcher = mainDispatcher,
            bankFiles = files,
            bankFetcher = FakeBankFetcher().apply { publish(patched, builtAt = patchStamp) },
        )
        runBlocking { subject.awaitIdle() }
        subject.checkBankUpdate()
        runBlocking { subject.awaitIdle() }
        subject.installBankUpdate()
        runBlocking { subject.awaitIdle() }

        // Re-entering the panel re-reads the bank; it must find the installed one.
        subject.refreshCatalogue()
        runBlocking { subject.awaitIdle() }

        assertEquals(BankSource.INSTALLED, subject.uiState.value.bankSource)
        assertEquals(patchStamp, subject.uiState.value.referenceBuiltAt)
    }

    @Test
    fun `a check that finds the same bank keeps the card clean`() {
        val fetcher = FakeBankFetcher().apply { publish(fixtureBankBytes()) }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }

        subject.checkBankUpdate()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertNull("同一个包不该提示更新", state.availableBankUpdate)
        assertFalse("这不是错误", state.isMessageError)
        assertEquals("检测包已是最新", state.message)
    }

    @Test
    fun `an install that fails verification leaves the working bank alone`() {
        val fetcher = FakeBankFetcher().apply {
            publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp)
            // The server hands over something other than the manifest promised.
            bankBytes = bankWithBuiltAt("2026-10-01T00:00:00.000000+00:00")
        }
        val files = MemoryBankFileSystem()
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFiles = files,
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }

        subject.checkBankUpdate()
        runBlocking { subject.awaitIdle() }
        subject.installBankUpdate()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertTrue("必须说明为什么没装上", state.isMessageError)
        assertEquals(BankSource.INSTALLED, state.bankSource)
        assertEquals(shippedBankBuiltAt(), state.referenceBuiltAt)
        assertEquals("被拒绝的下载不得写进文件", sha256Hex(fixtureBankBytes()), sha256Hex(files.installed!!))
        assertFalse(state.isInstallingBank)
    }

    @Test
    fun `an install without a check does nothing`() {
        val fetcher = FakeBankFetcher().apply { publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp) }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }

        subject.installBankUpdate()
        runBlocking { subject.awaitIdle() }

        assertTrue("没有可装的版本，不该发起下载", fetcher.urls.isEmpty())
        assertEquals(BankSource.INSTALLED, subject.uiState.value.bankSource)
    }

    @Test
    fun `a check failure is reported and the spinner stops`() {
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = FakeBankFetcher(failure = IOException("连接中断")),
        )
        runBlocking { subject.awaitIdle() }

        subject.checkBankUpdate()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertFalse(state.isCheckingBankUpdate)
        assertTrue(state.isMessageError)
        assertEquals("连接中断", state.message)
    }

    @Test
    fun `a published bank that needs a newer app is refused`() {
        val patched = bankWithBuiltAt(patchStamp)
        val fetcher = FakeBankFetcher().apply {
            publish(patched, builtAt = patchStamp)
            manifestBody = manifestJson(patched, builtAt = patchStamp, minAppVersionCode = 10_500L)
        }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
            appVersionCode = 10_400L,
        )
        runBlocking { subject.awaitIdle() }

        subject.checkBankUpdate()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertNull("装不上的库不该出现更新按钮", state.availableBankUpdate)
        assertTrue(state.isMessageError)
    }

    @Test
    fun `the silent entry check also flags a package that needs a newer app`() {
        // A data-only upstream change rides the package channel, but a package whose
        // format moved cannot be installed by an older app. The user has to hear that
        // from the automatic per-entry check — someone who never presses the manual
        // button would otherwise see a panel that silently offers nothing.
        val patched = bankWithBuiltAt(patchStamp)
        val fetcher = FakeBankFetcher().apply {
            publish(patched, builtAt = patchStamp)
            manifestBody = manifestJson(patched, builtAt = patchStamp, minAppVersionCode = 10_500L)
        }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
            appVersionCode = 10_400L,
        )
        runBlocking { subject.awaitIdle() }

        subject.refreshBankOnEntry()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertNotNull("静默检查也必须把「先更新应用」摆出来", state.bankRequiringNewerApp)
        assertEquals(10_500L, state.bankRequiringNewerApp?.minAppVersionCode)
        assertNull("装不上的包不得出现更新按钮", state.availableBankUpdate)
        assertNull("静默检查不弹气泡", state.message)
    }

    @Test
    fun `an installable offer clears the newer-app requirement`() {
        // The flag describes the currently published manifest, not a permanent state:
        // once the publisher ships something this app can parse, the hint must go.
        val patched = bankWithBuiltAt(patchStamp)
        val fetcher = FakeBankFetcher().apply { publish(patched, builtAt = patchStamp) }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
            appVersionCode = 10_400L,
        )
        runBlocking { subject.awaitIdle() }
        fetcher.manifestBody =
            manifestJson(patched, builtAt = patchStamp, minAppVersionCode = 10_500L)
        subject.refreshBankOnEntry()
        runBlocking { subject.awaitIdle() }
        assertNotNull(subject.uiState.value.bankRequiringNewerApp)

        fetcher.manifestBody =
            manifestJson(patched, builtAt = patchStamp, minAppVersionCode = 10_400L)
        subject.refreshBankOnEntry()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertNull("能装的包一发布，「先更新应用」的提示必须消失", state.bankRequiringNewerApp)
        assertNotNull("可安装的更新照常摆出按钮", state.availableBankUpdate)
    }

    @Test
    fun `removing the package empties the panel`() {
        val files = MemoryBankFileSystem(installed = bankWithBuiltAt(patchStamp))
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFiles = files,
            // Removing is followed by a check, so the fake has to answer like a publisher.
            bankFetcher = FakeBankFetcher().apply {
                publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp)
            },
        )
        runBlocking { subject.awaitIdle() }
        assertEquals(BankSource.INSTALLED, subject.uiState.value.bankSource)

        subject.removeInstalledPackage()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertEquals(BankSource.NOT_PROVISIONED, state.bankSource)
        assertEquals("", state.referenceBuiltAt)
        assertEquals(0, state.modelCount)
        assertNull(files.installed)
        assertNull(files.backup)
        assertFalse(state.isMessageError)
    }

    @Test
    fun `deleting the package leaves the download one tap away`() {
        val fetcher = FakeBankFetcher().apply {
            publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp)
        }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFiles = MemoryBankFileSystem(installed = bankWithBuiltAt(patchStamp)),
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }
        assertEquals("已装有检测包时不该自动联网", emptyList<String>(), fetcher.urls)

        subject.removeInstalledPackage()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertEquals(BankSource.NOT_PROVISIONED, state.bankSource)
        assertNotNull("删除后「下载检测包」必须马上回来", state.availableBankUpdate)
        assertEquals(
            "删除成功只重查一次清单",
            listOf(FakeBankFetcher.MANIFEST_URL),
            fetcher.urls,
        )
    }

    @Test
    fun `a delete that failed does not re-check the manifest`() {
        val fetcher = FakeBankFetcher().apply {
            publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp)
        }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFiles = MemoryBankFileSystem(
                installed = bankWithBuiltAt(patchStamp),
                deleteFails = true,
            ),
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }

        subject.removeInstalledPackage()
        runBlocking { subject.awaitIdle() }

        assertTrue("删除失败时不该顺手联网", fetcher.urls.isEmpty())
        assertEquals(BankSource.INSTALLED, subject.uiState.value.bankSource)
    }

    @Test
    fun `a delete that fails is reported and the installed bank stays in use`() {
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFiles = MemoryBankFileSystem(
                installed = bankWithBuiltAt(patchStamp),
                deleteFails = true,
            ),
        )
        runBlocking { subject.awaitIdle() }

        subject.removeInstalledPackage()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertTrue("删不掉就必须说，不能假装已删除", state.isMessageError)
        assertEquals(BankSource.INSTALLED, state.bankSource)
    }

    @Test
    fun `a check is refused while a round is running`() {
        val api = GatedCompletionApi(goldenCase().answers)
        val fetcher = FakeBankFetcher().apply { publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp) }
        val subject = readyApiViewModel(
            api,
            parallel = false,
            models = listOf("m-1"),
            bankFetcher = fetcher,
        )
        subject.runApiDetection()
        runBlocking { api.started.await() }

        subject.checkBankUpdate()

        // Swapping the bank mid-round would leave the round scoring with one bank while
        // its rows report a ranking from another.
        assertTrue("检测进行中不该发起检查", fetcher.urls.isEmpty())
        assertFalse(subject.uiState.value.isCheckingBankUpdate)
        subject.cancelRun()
    }
}

private class EmptyStore : SupplierStore(null) {
    override suspend fun read() = SupplierStoreState(emptyList(), null)
    override suspend fun save(state: SupplierStoreState) = Unit
}

/** A store with one usable supplier so the API path can be driven offline. */
private class SingleSupplierStore(
    private val supplier: SupplierProfile,
) : SupplierStore(null) {
    override suspend fun read() = SupplierStoreState(listOf(supplier), supplier.id)
    override suspend fun save(state: SupplierStoreState) = Unit
}

/** A store whose contents the test can change, standing in for the other tab's edits. */
private class MutableSupplierStore(
    var suppliers: List<SupplierProfile>,
) : SupplierStore(null) {
    /** Makes every read fail, the way an unreadable DataStore file would. */
    var failReads = false

    /** Parks a read inside the store until the test releases it. */
    var gate: CompletableDeferred<Unit>? = null

    val reads = AtomicInteger(0)

    override suspend fun read(): SupplierStoreState {
        reads.incrementAndGet()
        gate?.await()
        if (failReads) error("配置读取失败")
        return SupplierStoreState(suppliers, suppliers.firstOrNull()?.id)
    }

    override suspend fun save(state: SupplierStoreState) = Unit
}

private fun testSupplier(models: List<String> = listOf("test-model")): SupplierProfile = SupplierProfile(
    id = "sup-1",
    name = "测试供应商",
    baseUrl = "https://relay.test/v1",
    protocol = RelayProtocol.CHAT_COMPLETIONS,
    apiKeySecretId = "secret-1",
    models = models,
    testSettings = TestSettings(),
)

private fun otherSupplier(): SupplierProfile = SupplierProfile(
    id = "sup-2",
    name = "另一个供应商",
    baseUrl = "https://other.test/v1",
    protocol = RelayProtocol.CHAT_COMPLETIONS,
    apiKeySecretId = "secret-2",
    models = listOf("other-model"),
    testSettings = TestSettings(),
)

/**
 * Answers every challenge from [answers] in call order, optionally failing instead.
 *
 * The hold inside the request is load bearing: without a suspension point the
 * unconfined main dispatcher runs each request to completion before the next one
 * starts, so a parallel round and a sequential round become indistinguishable and
 * both concurrency assertions pass for free.
 *
 * Every call records the model it was sent for, in arrival order: that list is how a
 * batch's ordering is asserted.
 */
private class FakeCompletionApi(
    private val answers: List<String>,
    /** Flippable: a test can let upstream recover between a round and its retry. */
    var failure: TestError? = null,
    private val failingModels: Set<String> = emptySet(),
    private val holdMs: Long = 20,
) : RelayApi() {
    val calls = AtomicInteger(0)
    val maxConcurrent = AtomicInteger(0)
    val models = Collections.synchronizedList(mutableListOf<String>())
    private val inFlight = AtomicInteger(0)

    override suspend fun completeText(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
    ): ApiResult<String> {
        // The index is taken on entry, so a parallel round still hands each challenge
        // a distinct answer even though the completion order is not fixed.
        val index = calls.getAndIncrement()
        models += model
        val now = inFlight.incrementAndGet()
        maxConcurrent.updateAndGet { current -> maxOf(current, now) }
        try {
            withContext(Dispatchers.Default) { delay(holdMs) }
        } finally {
            inFlight.decrementAndGet()
        }
        failure?.let { return ApiResult.Failure(it) }
        if (model in failingModels) {
            return ApiResult.Failure(TestError(ErrorKind.UPSTREAM, "上游返回 HTTP 502"))
        }
        return ApiResult.Success(answers[index % answers.size])
    }
}

/**
 * Parks one request until cancellation, optionally holding its cleanup open so the
 * panel can be exercised while the cancelled job is still completing.
 */
private class GatedCompletionApi(
    private val answers: List<String>,
    private val gatedPosition: Int = 0,
    private val cancellationGate: CompletableDeferred<Unit>? = null,
) : RelayApi() {
    /** Completed once the selected request is inside the relay and parked. */
    val started = CompletableDeferred<Unit>()
    val cancellationStarted = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()
    val models = Collections.synchronizedList(mutableListOf<String>())

    override suspend fun completeText(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
    ): ApiResult<String> {
        val position = models.size
        models += model
        if (position == gatedPosition) {
            started.complete(Unit)
            try {
                gate.await()
            } catch (error: CancellationException) {
                cancellationStarted.complete(Unit)
                cancellationGate?.let { cleanup ->
                    withContext(NonCancellable) { cleanup.await() }
                }
                throw error
            }
        }
        return ApiResult.Success(answers[position % answers.size])
    }
}

/**
 * Fails the way a corrupt asset or a scoring bug would: by throwing instead of
 * returning a failure, which is the case the batch loop has to contain.
 */
private class ThrowingCompletionApi(private val boom: Throwable) : RelayApi() {
    override suspend fun completeText(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
    ): ApiResult<String> = throw boom
}

/** Generous ceiling: only a genuinely stuck batch may hit it. */
private const val BATCH_SETTLE_TIMEOUT_MS = 10_000L

/** One recorded golden case: the three answers and the model they must rank first. */
private data class GoldenCase(val answers: List<String>, val top1: String)

private fun goldenCase(): GoldenCase {
    val text = (FingerprintViewModelTest::class.java.classLoader ?: ClassLoader.getSystemClassLoader())
        .getResourceAsStream("fingerprint-golden.json")
        ?.bufferedReader(Charsets.UTF_8)
        ?.use { it.readText() }
        ?: error("找不到 fingerprint-golden.json 测试资源")
    val first = JSONArray(text).getJSONObject(0)
    val answers = first.getJSONArray("answers").let { array ->
        List(array.length()) { array.getString(it) }
    }
    return GoldenCase(answers, first.getJSONObject("expected").getString("prediction"))
}

private fun assertNotNull(message: String, value: Any?) {
    org.junit.Assert.assertNotNull(message, value)
}
