package com.relaytester.app

import com.relaytester.app.core.fingerprint.BankManifestParser
import com.relaytester.app.core.fingerprint.BankSource
import com.relaytester.app.core.fingerprint.BankUpdateClient
import com.relaytester.app.core.fingerprint.DetectionHistoryEntry
import com.relaytester.app.core.fingerprint.FingerprintHistoryStore
import com.relaytester.app.core.fingerprint.HistoryFileSystem
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
import com.relaytester.app.core.storage.UpdatePreferencesState
import com.relaytester.app.feature.fingerprint.bankCheckOutcome
import com.relaytester.app.ui.components.UpdateCheckOutcome
import com.relaytester.app.feature.fingerprint.ChallengeProgress
import com.relaytester.app.feature.fingerprint.ChallengeState
import com.relaytester.app.feature.fingerprint.DetectionMode
import com.relaytester.app.feature.fingerprint.FingerprintViewModel
import com.relaytester.app.feature.fingerprint.ModelDetectionStatus
import com.relaytester.app.feature.fingerprint.compactStateLabel
import com.relaytester.app.feature.fingerprint.formatBankBuiltAt
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /**
     * The clock the view models read, wound from the test scheduler's virtual time.
     *
     * That is what lets a test walk a whole six-hour window with `advanceTimeBy` while the
     * loop's arithmetic sees the clock move with it — a frozen clock would make every wake
     * compute "due long ago" and the window would never be crossed. Nothing advances time
     * in the tests that do not care, so they still read exactly [FIXED_NOW_MILLIS].
     */
    private fun virtualClock(): Long = FIXED_NOW_MILLIS + mainDispatcher.scheduler.currentTime

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
        bankUpdateClient = BankUpdateClient(fetcher = FakeHttpFetcher()),
        updatePreferences = MemoryUpdatePreferences(),
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
        bankFetcher: FakeHttpFetcher = FakeHttpFetcher(),
        appVersionCode: Long = 10_400L,
        updatePreferences: MemoryUpdatePreferences = MemoryUpdatePreferences(),
        /** Follows the test scheduler's virtual time; see [virtualClock]. */
        clock: () -> Long = { virtualClock() },
        bankCheckIntervalMillis: Long = UpdatePreferencesState.APP_CHECK_INTERVAL_MILLIS,
        /** Set by the retry-isolation tests that assert on written history rows. */
        historyStore: FingerprintHistoryStore? = null,
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
        updatePreferences = updatePreferences,
        appVersionCode = appVersionCode,
        historyStore = historyStore,
        skipRestore = skipRestore,
        ioDispatcher = ioDispatcher,
        clock = clock,
        bankCheckIntervalMillis = bankCheckIntervalMillis,
    ).apply {
        // A skipped restore still loads the bank, and no test may race that.
        if (skipRestore) runBlocking { awaitIdle() }
    }

    /** A round ready to dispatch: supplier chosen, models ticked, challenges seeded. */
    private fun readyApiViewModel(
        api: RelayApi,
        parallel: Boolean = true,
        models: List<String> = listOf("test-model"),
        bankFetcher: FakeHttpFetcher = FakeHttpFetcher(),
        /** The main test dispatcher keeps the check deterministic; IO would not. */
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        historyStore: FingerprintHistoryStore? = null,
    ): FingerprintViewModel = apiViewModel(
        api = api,
        bankFetcher = bankFetcher,
        ioDispatcher = ioDispatcher,
        historyStore = historyStore,
    ).apply {
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
        bankFetcher: FakeHttpFetcher = FakeHttpFetcher(),
        appVersionCode: Long = 10_400L,
        updatePreferences: MemoryUpdatePreferences = MemoryUpdatePreferences(),
        /** Set by the retry-isolation tests that assert on written history rows. */
        historyStore: FingerprintHistoryStore? = null,
    ): FingerprintViewModel = apiViewModel(
        api = api,
        store = store,
        skipRestore = false,
        ioDispatcher = mainDispatcher,
        bankFiles = bankFiles,
        bankFetcher = bankFetcher,
        appVersionCode = appVersionCode,
        updatePreferences = updatePreferences,
        historyStore = historyStore,
    ).apply {
        models.forEach { toggleModelSelection(it) }
        selectMode(DetectionMode.API)
    }

    /**
     * Waits for the batch list to leave its interim states, or fails on a stall.
     *
     * The launch is not synchronous with the call that starts it: [runApiDetection]
     * publishes the rows from inside the round's coroutine, so a bare poll can look at
     * an empty list, see nothing PENDING or RUNNING, and return while the round it is
     * supposed to be waiting for has not even started. Waiting for the panel to go busy
     * first closes that window — only then does "no row is waiting" mean the round ended.
     */
    private fun awaitBatchSettled(subject: FingerprintViewModel) {
        runBlocking {
            withTimeout(BATCH_SETTLE_TIMEOUT_MS) {
                while (!subject.uiState.value.isRunning && subject.uiState.value.batchResults.isEmpty()) {
                    delay(10)
                }
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
    fun `a blank answer is asked again instead of ending the challenge`() {
        // 真实供应商实测：同一道题四次里有一次 HTTP 200、整段流、正文 0 字符（模型把整个
        // 8192 的预算花在思考通道里，finish_reason: length），而同一请求其余三次都拿到了
        // 正文。空白是上游自己的硬币，不是这次请求的属性，所以这一题必须自动再问一次——
        // 这条测试钉的就是「空白不是终局」。
        val api = BlankAskThenAnswerApi(answers = listOf(numbersAnswer()))
        val subject = readyApiViewModel(api)

        subject.runApiDetection()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }

        val state = subject.uiState.value
        assertEquals("三题 + 那次自动重发", 4, api.calls.get())
        assertTrue(
            "重发之后不该有任何一题停在失败上：${state.progress.map { it.state }}",
            state.progress.all { it.state == ChallengeState.RECEIVED },
        )
    }

    @Test
    fun `a model that only thinks is reported as such after both asks`() {
        // 两次都空白、两次的思考通道都有内容：重发没救回来。报错要说清是「模型只想不写」，
        // 用户据此决定稍后再试还是换模型，而不是以为应用坏了。
        val api = BlankAskThenAnswerApi(
            answers = listOf(numbersAnswer()),
            blankAsks = setOf(0, 1),
            blankReasoning = "让我先规划一下：逐项取值、不要规律……",
        )
        val subject = readyApiViewModelFor(SingleSupplierStore(testSupplier()), api).apply {
            updateParallel(false)
        }

        subject.runApiDetection()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }

        val entry = subject.uiState.value.progress[0]
        assertEquals("空白那题问了两次，另外两题各一次", 4, api.calls.get())
        assertEquals(ChallengeState.REJECTED, entry.state)
        val error = entry.error.orEmpty()
        assertTrue("要说清模型只输出了思考：$error", error.contains("思考"))
        assertTrue("也要说清已经自动重发过：$error", error.contains("自动重试 1 次"))
        assertTrue(
            "其余两题不受影响",
            subject.uiState.value.progress.drop(1).all { it.state == ChallengeState.RECEIVED },
        )
    }

    @Test
    fun `a silent blank keeps the upstream wording after both asks`() {
        // 思考通道也没有内容的空白：不能反过来谎称「模型只想不写」，只能说上游没给正文，
        // 并且同样要说清自动重发已经跑过。
        val api = BlankAskThenAnswerApi(answers = listOf(numbersAnswer()), blankAsks = setOf(0, 1))
        val subject = readyApiViewModelFor(SingleSupplierStore(testSupplier()), api).apply {
            updateParallel(false)
        }

        subject.runApiDetection()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }

        val error = subject.uiState.value.progress[0].error.orEmpty()
        assertTrue("保持上游原话：$error", error.contains("没有可用的文本内容"))
        assertFalse("没有思考就不得声称模型只想不写：$error", error.contains("思考"))
        assertTrue("自动重发的事实必须在：$error", error.contains("自动重试 1 次"))
        assertEquals(4, api.calls.get())
    }

    @Test
    fun `only a blank answer is worth re-asking`() {
        // 502 这类失败重发同样的请求只会再被拒一次，自动重发的入口必须精确等于空白；
        // 认宽了，一次坏上游就会把每一题都变成两次请求。
        val api = BlankAskThenAnswerApi(
            answers = listOf(numbersAnswer()),
            blankAsks = setOf(0, 1, 2),
            failure = TestError(ErrorKind.UPSTREAM, "上游返回 HTTP 502"),
        )
        val subject = readyApiViewModel(api)

        subject.runApiDetection()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }

        assertEquals("三题各问一次，没有重发", 3, api.calls.get())
        assertTrue(
            "每一题都保留上游的原话",
            subject.uiState.value.progress.all { it.error == "上游返回 HTTP 502" },
        )
    }

    @Test
    fun `the automatic re-ask announces itself while it runs`() {
        val api = ParkedReaskApi(listOf(numbersAnswer()))
        val subject = readyApiViewModelFor(SingleSupplierStore(testSupplier()), api).apply {
            updateParallel(false)
        }
        val prompt0 = subject.uiState.value.progress[0].challenge.prompt

        subject.runApiDetection()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.reaskParked.await() } }

        val mid = subject.uiState.value
        assertTrue("整轮仍在跑", mid.isRunning)
        assertEquals("空白不是终局：这一题还在被问", ChallengeState.REQUESTING, mid.progress[0].state)
        assertTrue("第二次等待必须自称重试", mid.progress[0].retrying)
        assertEquals("重试中…", compactStateLabel(mid.progress[0]))
        assertNull("重试期间不挂上一次的空手失败", mid.progress[0].error)
        assertEquals("重发问的还是同一道题", listOf(prompt0, prompt0), api.prompts.take(2))

        api.release()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }

        assertEquals("三题 + 一次重发", 4, api.calls.get())
        val settled = subject.uiState.value.progress[0]
        assertEquals(ChallengeState.RECEIVED, settled.state)
        assertFalse("结题后不再自称重试", settled.retrying)
    }

    @Test
    fun `cancelling while the automatic re-ask is in flight sends nothing more`() {
        // 重发是一次真实请求，取消它必须和取消首问一样干净：不再补发、不把「已接收」写回
        // 一张已经没人看的卡，也不给缓存留一个占着槽位的结果。这一条钉的是 1.7.19 新增的
        // 那道 `current()` 守卫——它挡在重发决定之前。
        val api = ParkedReaskApi(listOf(numbersAnswer()))
        val subject = readyApiViewModelFor(SingleSupplierStore(testSupplier()), api).apply {
            updateParallel(false)
        }

        subject.runApiDetection()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.reaskParked.await() } }
        assertEquals("首问空白 + 一次重发已经在飞", 2, api.calls.get())

        subject.cancelRun()
        api.release()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }

        assertEquals("取消之后不得再有第三次请求", 2, api.calls.get())
        assertFalse("整轮已经停下", subject.uiState.value.isRunning)
        assertEquals(
            "被取消的一题退回等待发送，而不是写回结果",
            ChallengeState.PENDING,
            subject.uiState.value.progress[0].state,
        )
        assertNull("取消后不留错误话术", subject.uiState.value.progress[0].error)
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
    fun `a question every model answered offers no retry and sends nothing`() {
        val api = FakeCompletionApi(goldenCase().answers)
        val subject = readyApiViewModel(api, models = listOf("m-one", "m-two"))
        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }
        assertEquals(6, api.calls.get())
        val idsBefore = subject.uiState.value.progress.map { it.challenge.id }
        assertEquals("每个模型都答对的题不该有重试入口", emptyMap<Int, List<String>>(), subject.uiState.value.retryableModels)

        subject.retryChallenge(1)
        runBlocking { subject.awaitIdle() }

        // 重试只针对**失败**的模型；一个答案健康的题被要求重试时必须什么都不发，
        // 否则「重试本题」会变成「把所有模型都再问一遍」——正是用户报的那个毛病。
        assertEquals("健康的题不许重发", 6, api.calls.get())
        assertEquals("题目集合不动", idsBefore, subject.uiState.value.progress.map { it.challenge.id })
        assertTrue(
            "每行的结论保持不变",
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
        assertEquals(setOf(2), cancelled.retryableModels.keys)
        assertEquals("入口要指明是哪个模型这一题没有跑完", listOf("test-model"), cancelled.retryableModels[2])

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
        assertEquals(emptyMap<Int, List<String>>(), state.retryableModels)
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
        assertEquals(setOf(0, 1, 2), state.retryableModels.keys)
        assertEquals("每一题都只应重试失败的那个模型", List(3) { listOf("broken") }, state.retryableModels.values.toList())

        failingModels.clear()
        val callsBeforeRetry = api.calls.get()
        subject.retryChallenge(0)
        runBlocking { subject.awaitIdle() }

        val retried = subject.uiState.value
        // 用户要求「哪个模型有问题就重试哪个模型」：健康的那个不许被重新问一遍，
        // 否则一次单题重试会白花掉另一个模型的请求。
        assertEquals("只重试 broken 这一题", callsBeforeRetry + 1, api.calls.get())
        assertEquals("只重试 broken 这一题", "broken", api.models.last())
        assertEquals(1, retried.batchResults.first().usableAnswers)
        assertEquals(3, retried.batchResults.last().usableAnswers)
        assertEquals(setOf(1, 2), retried.retryableModels.keys)
        assertEquals(listOf("broken"), retried.retryableModels[1])
        subject.regenerateChallenges()
        assertEquals("换题后旧轮次的重试入口必须清除", emptyMap<Int, List<String>>(), subject.uiState.value.retryableModels)
    }

    @Test
    fun `a chosen model is the only one re-asked for a failed question`() {
        val failingModels = mutableSetOf("m-one", "m-two")
        val api = FakeCompletionApi(goldenCase().answers, failingModels = failingModels)
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            api,
            models = listOf("m-one", "m-two"),
        )
        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }
        assertEquals(6, api.calls.get())
        assertEquals("两个模型都失败，两题入口都要列出它们", listOf("m-one", "m-two"), subject.uiState.value.retryableModels[0])

        failingModels.clear()
        val callsBeforeRetry = api.calls.get()
        // 用户在多模型失败时要能选：只补 m-two 这一题，m-one 一动不动。
        subject.retryChallenge(0, listOf("m-two"))
        runBlocking { subject.awaitIdle() }

        assertEquals("只补发被选中的那个模型", callsBeforeRetry + 1, api.calls.get())
        assertEquals("被选中的是 m-two", 4, api.models.count { it == "m-two" })
        assertEquals("没被选中的模型不许被重问", 3, api.models.count { it == "m-one" })
        val rows = subject.uiState.value.batchResults.associateBy { it.model }
        assertEquals("没被选中的模型保持失败", ModelDetectionStatus.FAILED, rows.getValue("m-one").status)
        assertEquals("被选中的模型重答后出结论", ModelDetectionStatus.DONE, rows.getValue("m-two").status)
    }

    @Test
    fun `a retry in flight withdraws its own entry and cannot be double-fired`() {
        val api = ParkedRetryApi(goldenCase().answers)
        val subject = readyApiViewModelFor(SingleSupplierStore(testSupplier()), api).apply {
            updateParallel(false)
        }
        val prompt0 = subject.uiState.value.progress[0].challenge.prompt

        subject.runApiDetection()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }
        assertEquals(setOf(0), subject.uiState.value.retryableModels.keys)

        subject.retryChallenge(0)
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.retryParked.await() } }

        // 正在接收＝入口必须让位：补发在飞时这一题不再算「可重试」，否则按钮会在
        // 请求已发出的情况下继续显示「重试本题」。
        assertTrue("补发在飞时这一题要从入口里消失", 0 !in subject.uiState.value.retryableModels.keys)
        // 排队标记只属于「等闸门」那一段：闸门是空的，所以请求已经上线，标记必须已经收掉，
        // 否则格子会在整个接收期间谎称自己还在排队。
        assertTrue("上线之后不该再显示排队中", subject.uiState.value.queuedRetries.isEmpty())

        subject.retryChallenge(0)
        // 显式点名（多模型时「全部重试」走的就是这条路）也要被挡住：入口虽然已经撤下，
        // 但调用方仍可能指定模型，去重必须自己成立。
        subject.retryChallenge(0, listOf("test-model"))
        runBlocking { delay(300) }
        assertEquals("同一模型同一题不许并发补发", 2, api.prompts.count { it == prompt0 })

        api.release()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }
        assertEquals("补发成功后入口清空", emptyMap<Int, List<String>>(), subject.uiState.value.retryableModels)
    }

    @Test
    fun `with one question on the wire a serial retry queues behind it`() {
        val api = FirstSlotFailsThenParksApi(goldenCase().answers)
        val subject = readyApiViewModelFor(SingleSupplierStore(testSupplier()), api).apply {
            updateParallel(false)
        }
        val prompts = subject.uiState.value.progress.map { it.challenge.prompt }

        subject.runApiDetection()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.parked.await() } }

        val mid = subject.uiState.value
        assertTrue("这一轮还在跑", mid.isRunning)
        assertEquals(ChallengeState.REJECTED, mid.progress[0].state)
        // 用户要求「失败了就可以点击了，不用等其他的」：第 1 题已判失败，入口立刻在，
        // 而第 2 题正在接收——它不能出现在入口里（否则就是「正在接收时就显示」）。
        assertEquals("只有已失败的那一题出现", setOf(0), mid.retryableModels.keys)
        assertEquals(listOf("test-model"), mid.retryableModels[0])

        subject.retryChallenge(0)
        runBlocking { delay(200) }

        // 逐题发送时这条重试排到当前那一题之后（用户要求）：第 2 题还在飞，它就不许上线，
        // 而且必须如实说「排队中」，不能拿 REQUESTING 假装已经在接收。
        assertEquals("等待期间标成排队", setOf("test-model" to 0), subject.uiState.value.queuedRetries)
        assertEquals("当前那一题没回来之前不许抢跑", prompts.take(2), api.prompts.toList())
        assertTrue("整轮仍在跑", subject.uiState.value.isRunning)
        // 这一轮仍然持有这个模型的结果行：补发不该把它翻成「已出结果」，否则整轮进度
        // 会往回跳，而且这一行会被两个写者同时改。
        assertEquals(
            "补发期间结果行仍归整轮所有",
            ModelDetectionStatus.RUNNING,
            subject.uiState.value.batchResults.single().status,
        )

        api.release()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }

        // 闸门一放开，排队的那条抢在整轮下一题之前上线——「插在当前那一题之后」的全部
        // 含义就在这个顺序里：第 3 题排在它后面，而不是它排在整轮后面。
        assertEquals("重试插在第 2 题之后、第 3 题之前", prompts.take(2) + prompts[0] + prompts[2], api.prompts.toList())
        val done = subject.uiState.value
        assertTrue(done.progress.all { it.state == ChallengeState.RECEIVED })
        assertEquals(ModelDetectionStatus.DONE, done.batchResults.single().status)
        assertEquals(3, done.batchResults.single().usableAnswers)
        assertEquals("全部答出来后入口清空", emptyMap<Int, List<String>>(), done.retryableModels)
        assertTrue("排队标记必须随请求上线一起收掉", done.queuedRetries.isEmpty())
    }

    @Test
    fun `with the parallel switch on a retry goes out beside the round`() {
        val api = FirstSlotFailsThenParksApi(goldenCase().answers)
        val subject = readyApiViewModelFor(SingleSupplierStore(testSupplier()), api).apply {
            updateParallel(true)
        }
        val prompt0 = subject.uiState.value.progress[0].challenge.prompt

        subject.runApiDetection()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.parked.await() } }
        assertEquals("并行：第 1 题已失败、第 2 题在接收", setOf(0), subject.uiState.value.retryableModels.keys)

        subject.retryChallenge(0)
        runBlocking { delay(200) }

        // 并行模式没有闸门：入口一出现、点下去就上线，与逐题发送的排队形成对照。
        // 两条路径各有一条测试钉住，闸门被误用到并行分支上时这里会红。
        assertEquals("补发已经发出", 2, api.prompts.count { it == prompt0 })
        assertTrue("并行模式不该出现排队标记", subject.uiState.value.queuedRetries.isEmpty())
        assertTrue("整轮仍在跑", subject.uiState.value.isRunning)

        api.release()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }
        assertEquals(ModelDetectionStatus.DONE, subject.uiState.value.batchResults.single().status)
    }

    @Test
    fun `every model keeps its own three slots once the round moves on`() {
        val api = FakeCompletionApi(goldenCase().answers)
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            api,
            models = listOf("m-one", "m-two"),
        ).apply { updateParallel(false) }

        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }

        // 面板按模型分块，所以每个模型的三格都必须留在 UI 状态里；progress 是屏幕级单
        // 列表，整轮跑完只剩最后一个模型的答案，单独看它会把前面的模型画成一片空白。
        val slots = subject.uiState.value.modelSlots
        assertEquals("两个模型各留一组三栏", setOf("m-one", "m-two"), slots.keys)
        for (model in listOf("m-one", "m-two")) {
            val answers = slots.getValue(model)
            assertEquals("$model 的三格都在", 3, answers.size)
            assertTrue("$model 的三格都是有效回答", answers.all { it.state == ChallengeState.RECEIVED })
        }
    }

    @Test
    fun `a failed model's retry queues behind the healthy model's in-flight question`() {
        val api = FirstFailsThenParksApi(goldenCase().answers, failingModel = "broken")
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            api,
            models = listOf("broken", "slow"),
        ).apply { updateParallel(false) }

        subject.runApiDetection()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.parked.await() } }

        val mid = subject.uiState.value
        assertTrue("整批还在跑", mid.isRunning)
        assertEquals(ModelDetectionStatus.FAILED, mid.batchResults.first().status)
        assertEquals(ModelDetectionStatus.RUNNING, mid.batchResults.last().status)
        // 用户要求「失败了就可以点击了，不用等其他的」：入口必须在这一刻就在，
        // 而不是等 slow 也跑完。
        assertEquals("失败的模型落定即可重试", setOf(0, 1, 2), mid.retryableModels.keys)
        assertEquals("仍在接收的模型不许出现在入口里", List(3) { listOf("broken") }, mid.retryableModels.values.toList())

        val brokenBefore = api.models.count { it == "broken" }
        subject.retryChallenge(0)
        runBlocking { delay(200) }

        // 逐题发送时补发同样排在当前那一题之后：slow 还在飞，broken 的第 4 次请求就不该出现。
        assertEquals("失败模型的补发同样排队", setOf("broken" to 0), subject.uiState.value.queuedRetries)
        assertEquals("slow 那一题还在飞，补发不许抢跑", brokenBefore, api.models.count { it == "broken" })

        api.release()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }

        // 到达顺序就是「插在当前那一题之后」的证据：slow 的第 1 题 → broken 的补发 →
        // slow 剩下两题。若补发被压到整批之后，broken 的第 4 次会排在最末尾。
        assertEquals(
            "补发插在 slow 第 1 题之后、第 2 题之前",
            listOf("broken", "broken", "broken", "slow", "broken", "slow", "slow"),
            api.models.toList(),
        )
        // 这一题的补发又失败了（这个假上游对 broken 一失败到底），所以入口仍在，
        // 这正是「失败就可重试」的另一面：只要还有结论可捞，按钮就不该消失。
        assertEquals(setOf(0, 1, 2), subject.uiState.value.retryableModels.keys)
        assertEquals(ModelDetectionStatus.FAILED, subject.uiState.value.batchResults.first().status)
        assertTrue("排队标记随请求上线收掉", subject.uiState.value.queuedRetries.isEmpty())
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
    fun `a finished round can be started again`() {
        // [runInFlight] now asks whether the handle exists at all, so it reports "busy" for
        // as long as the finished round's handle is still in place. That must not wedge the
        // panel: 再点一次开始 has to go out again once the round really ended.
        val api = FakeCompletionApi(goldenCase().answers)
        val subject = readyApiViewModel(api, models = listOf("test-model"))

        subject.runApiDetection()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }
        val afterFirst = api.calls.get()

        subject.runApiDetection()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }

        assertEquals(
            "跑完的一轮不能把面板锁死（再点开始没有发出任何请求）",
            afterFirst + 3,
            api.calls.get(),
        )
    }

    @Test
    fun `a cancelled round can be started again`() {
        // 用户报告：检测中点取消以后，再点开始没有任何反应。取消只是把这一轮停掉，
        // 不是把面板锁死——取消清理结束之后必须能立刻重开一轮。
        val api = GatedCompletionApi(goldenCase().answers)
        val subject = readyApiViewModel(api, parallel = false, models = listOf("m-1"))

        subject.runApiDetection()
        runBlocking { api.started.await() }
        // 第一轮只发出第一题就停在这里，取消后它剩下的两题不会再上线。
        assertEquals("取消前应该只有一题在线上", 1, api.models.size)

        subject.cancelRun()
        // 让被取消的 job 连同它的清理真正跑完，这才是「用户又点了一次」的时刻。
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }

        assertFalse(subject.uiState.value.isRunning)

        // 再点一次开始：这一轮自己的三题都必须重新发出。计数用「这一轮自己的题数」，
        // 而不是「比上次多一题」——一轮本来就是三题。
        subject.runApiDetection()
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { subject.awaitIdle() } }

        assertEquals(
            "取消后必须还能重新开始检测（再点开始没有发出任何请求）",
            1 + subject.uiState.value.progress.size,
            api.models.size,
        )
        assertTrue(
            "重开的一轮必须真的跑完，而不是停在 RUNNING",
            subject.uiState.value.batchResults.none {
                it.status == ModelDetectionStatus.RUNNING || it.status == ModelDetectionStatus.PENDING
            },
        )
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
    fun `a pull-only supplier is refused before any request is sent`() {
        // 禁止测试：只拉模型，不发测试请求 is a promise about this app's behaviour. The 模型测试
        // page enforced it and the fingerprint page did not, so a user who set it precisely
        // because the station is expensive still had a three-question round spent on its key.
        // The assertion is on the fake's call count: the switch has to be honoured *before*
        // the socket, not explained afterwards.
        val api = FakeCompletionApi(goldenCase().answers)
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier().copy(isTestingDisabled = true)),
            api,
        )
        runBlocking { subject.awaitIdle() }

        subject.runApiDetection()
        runBlocking { subject.awaitIdle() }

        assertEquals("仅拉取供应商不得发出任何请求", 0, api.calls.get())
        val state = subject.uiState.value
        assertFalse("拒绝后不能停在运行中", state.isRunning)
        assertTrue("必须说明是用户自己的设置拦下了它", state.isMessageError)
        assertTrue(
            "理由要点明是仅拉取：${state.message}",
            state.message?.contains("仅拉取") == true,
        )
    }

    @Test
    fun `a pull-only supplier is reported as such in the panel list`() {
        // The refusal above must not be a surprise: the chip carries the red pull-only
        // marker before the user picks it, so the setting is visible where it applies
        // rather than only where it bites. This flag is what the chip draws that mark from.
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier().copy(isTestingDisabled = true)),
            FakeCompletionApi(goldenCase().answers),
        )
        runBlocking { subject.awaitIdle() }

        val option = subject.uiState.value.suppliers.single()
        assertTrue("列表必须带上仅拉取标记", option.pullOnly)
    }

    @Test
    fun `a normal supplier is not marked pull-only`() {
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
        )
        runBlocking { subject.awaitIdle() }

        assertFalse(subject.uiState.value.suppliers.single().pullOnly)
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
        val fetcher = FakeHttpFetcher().apply { publish(fixtureBankBytes()) }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFiles = MemoryBankFileSystem(installed = null),
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }
        assertEquals("构造本身不联网：检查由启动那一次触发", emptyList<String>(), fetcher.urls)

        runBlocking { subject.checkBankOnLaunch() }
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertEquals(BankSource.NOT_PROVISIONED, state.bankSource)
        // The panel is useless without a package, so entering it asks once by itself —
        // and only once: the manifest request is the whole of its unprompted networking.
        assertEquals(listOf(FakeHttpFetcher.MANIFEST_URL), fetcher.urls)
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
    fun `the launch check looks for a newer package even when one is installed`() {
        val fetcher = FakeHttpFetcher().apply {
            publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp)
        }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }
        assertEquals("装上以后不联网：自动检查只由启动那一次触发", emptyList<String>(), fetcher.urls)

        runBlocking { subject.checkBankOnLaunch() }
        runBlocking { subject.awaitIdle() }

        assertEquals(listOf(FakeHttpFetcher.MANIFEST_URL), fetcher.urls)
        assertEquals(patchStamp, subject.uiState.value.availableBankUpdate?.builtAt)
        assertNull("自动检查只把按钮摆出来，不弹气泡", subject.uiState.value.message)
    }

    @Test
    fun `an automatic check that fails does not greet the user with an error`() {
        val fetcher = FakeHttpFetcher(failure = IOException("网络不可用"))
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }

        runBlocking { subject.checkBankOnLaunch() }
        runBlocking { subject.awaitIdle() }

        assertEquals(listOf(FakeHttpFetcher.MANIFEST_URL), fetcher.urls)
        assertNull("用户没点的检查失败了不该弹错", subject.uiState.value.message)
        assertNull(subject.uiState.value.availableBankUpdate)
        assertFalse(subject.uiState.value.isCheckingBankUpdate)
    }

    @Test
    fun `the launch check asked for during the startup read is replayed once it lands`() {
        // The screen asks on every panel entry, but never twice; if it is dropped while
        // the panel is still loading, the first visit silently loses it — and on a fresh
        // install that check is what puts the download button on the empty card.
        val store = MutableSupplierStore(listOf(testSupplier(models = listOf("m-one"))))
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        val fetcher = FakeHttpFetcher().apply { publish(fixtureBankBytes()) }
        val subject = apiViewModel(
            api = FakeCompletionApi(goldenCase().answers),
            store = store,
            skipRestore = false,
            ioDispatcher = mainDispatcher,
            bankFiles = MemoryBankFileSystem(installed = null),
            bankFetcher = fetcher,
        )
        assertTrue("启动读取还挂在门上，面板应在加载态", subject.uiState.value.isLoading)

        // The app comes up and asks for its launch check while the read is still running.
        runBlocking { subject.checkBankOnLaunch() }
        assertEquals("在飞的启动读取还没落地，检查只能先记住", emptyList<String>(), fetcher.urls)

        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }

        assertEquals("读取落地后必须补上这次检查，且只补一次", listOf(FakeHttpFetcher.MANIFEST_URL), fetcher.urls)
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
    fun `a round starts even while the automatic check is still in flight`() {
        val api = FakeCompletionApi(goldenCase().answers)
        val gate = CompletableDeferred<Unit>()
        val fetcher = FakeHttpFetcher().apply {
            publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp)
            this.gate = gate
        }
        // mainDispatcher 作 IO：静默检查会在清单请求里确定性停住，取位一次算一次。
        val subject = readyApiViewModel(api, bankFetcher = fetcher, ioDispatcher = mainDispatcher)
        runBlocking { subject.awaitIdle() }
        // 自动检查没人按过，只能占它自己的那个门：用户点下的检测必须照跑，
        // 不能被一个用户没请求的 HTTP 请求挡在门外。启动那一次自己会等到检查落地，
        // 所以这里必须用一条自己的协程发起它，不能在 runBlocking 里等它返回。
        val launcher = CoroutineScope(mainDispatcher).launch { subject.checkBankOnLaunch() }
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
        runBlocking { launcher.join() }
    }

    @Test
    fun `a round is refused while the package install is in flight`() {
        val api = FakeCompletionApi(goldenCase().answers)
        val fetcher = FakeHttpFetcher().apply { publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp) }
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
    fun `an install in flight shows how far the download got`() {
        // A 3 MB body on a slow connection is a long silent wait otherwise: the button
        // says "installing" and nothing moves.
        val fetcher = FakeHttpFetcher().apply { publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp) }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }
        subject.checkBankUpdate()
        runBlocking { subject.awaitIdle() }
        assertNull("还没开始下载就没有进度行", subject.uiState.value.bankDownloadProgress)

        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate
        subject.installBankUpdate()

        val parked = subject.uiState.value.bankDownloadProgress
        assertNotNull("下载在飞时进度行必须在", parked)
        assertEquals("分母来自包的声明大小", bankWithBuiltAt(patchStamp).size.toLong(), parked?.totalBytes)
        assertEquals(0f, parked?.fraction)

        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
        assertNull("装完进度行必须收起来", subject.uiState.value.bankDownloadProgress)
        assertFalse(subject.uiState.value.isInstallingBank)
    }

    @Test
    fun `a failed download takes its progress row with it`() {
        // A bar left sitting at 0% next to a failure message reads as a download that is
        // still running, which is the one thing it is not.
        val fetcher = FakeHttpFetcher().apply { publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp) }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }
        subject.checkBankUpdate()
        runBlocking { subject.awaitIdle() }

        fetcher.failure = IOException("connection reset")
        subject.installBankUpdate()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertNull("失败之后不得留着进度行", state.bankDownloadProgress)
        assertFalse(state.isInstallingBank)
        assertTrue("失败要说出来", state.isMessageError)
    }

    @Test
    fun `a check the user pressed takes over the automatic check instead of queueing behind it`() {
        val api = FakeCompletionApi(goldenCase().answers)
        val gate = CompletableDeferred<Unit>()
        val fetcher = FakeHttpFetcher().apply { publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp) }
        // mainDispatcher 作 IO：静默检查会在清单请求里确定性停住，取位一次算一次，
        // 不然「谁发了几次请求」变成调度竞速，断言会闪。
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            api,
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }

        fetcher.gate = gate
        val launcher = CoroutineScope(mainDispatcher).launch { subject.checkBankOnLaunch() }
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
            fetcher.urls.count { it == FakeHttpFetcher.MANIFEST_URL },
        )
        assertNotNull("用户按下的检查必须汇报结果", subject.uiState.value.message)
        assertFalse(subject.uiState.value.isCheckingBankUpdate)
        gate.complete(Unit)
        runBlocking { launcher.join() }
    }

    @Test
    fun `a second automatic check while one is in flight keeps one request for manual takeover`() {
        val gate = CompletableDeferred<Unit>()
        val fetcher = FakeHttpFetcher().apply {
            publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp)
            this.gate = gate
        }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
        )
        val launcher = CoroutineScope(mainDispatcher).launch { subject.checkBankOnLaunch() }
        assertTrue(subject.uiState.value.isCheckingBankInBackground)
        assertEquals(listOf(FakeHttpFetcher.MANIFEST_URL), fetcher.urls)

        try {
            // A second automatic ask must keep the first check reachable rather than launch
            // another request and replace the job that manual takeover needs to cancel.
            runBlocking { subject.checkBankOnLaunch() }
            assertEquals("重复的自动检查只保留原来的静默清单请求", listOf(FakeHttpFetcher.MANIFEST_URL), fetcher.urls)
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
            runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { launcher.join() } }
        }
    }

    @Test
    fun `a round on a device with no package reports the missing package`() {
        val api = FakeCompletionApi(goldenCase().answers)
        val bare = apiViewModel(
            api = api,
            bankFiles = MemoryBankFileSystem(installed = null),
            bankFetcher = FakeHttpFetcher(),
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
        val fetcher = FakeHttpFetcher().apply { publish(patched, builtAt = patchStamp) }
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
            bankFetcher = FakeHttpFetcher().apply { publish(patched, builtAt = patchStamp) },
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
        val fetcher = FakeHttpFetcher().apply { publish(fixtureBankBytes()) }
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
    fun `the offer in the bar prints the stamp the way the card prints it`() {
        // The bar and the card are read one after the other, so the same publisher stamp must
        // not appear as a shortened date in one place and a raw ISO string in the other.
        val fetcher = FakeHttpFetcher().apply { publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp) }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }

        subject.checkBankUpdate()
        runBlocking { subject.awaitIdle() }

        val message = subject.uiState.value.message.orEmpty()
        assertTrue(
            "气泡里的构建时间没有走卡片那套格式化：$message",
            message.contains(formatBankBuiltAt(patchStamp)),
        )
        assertFalse("气泡里还印着带小数位与偏移的原始串：$message", message.contains(patchStamp))
    }

    @Test
    fun `an install that fails verification leaves the working bank alone`() {
        val fetcher = FakeHttpFetcher().apply {
            publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp)
            // The server hands over something other than the manifest promised.
            body = bankWithBuiltAt("2026-10-01T00:00:00.000000+00:00")
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
        val fetcher = FakeHttpFetcher().apply { publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp) }
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
            bankFetcher = FakeHttpFetcher(failure = IOException("连接中断")),
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
        val fetcher = FakeHttpFetcher().apply {
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
    fun `the automatic check also flags a package that needs a newer app`() {
        // A data-only upstream change rides the package channel, but a package whose
        // format moved cannot be installed by an older app. The user has to hear that
        // from the automatic per-entry check — someone who never presses the manual
        // button would otherwise see a panel that silently offers nothing.
        val patched = bankWithBuiltAt(patchStamp)
        val fetcher = FakeHttpFetcher().apply {
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

        runBlocking { subject.checkBankOnLaunch() }
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertNotNull("静默检查也必须把「先更新应用」摆出来", state.bankNeedsNewerApp)
        assertEquals(10_500L, state.bankNeedsNewerApp)
        assertNull("装不上的包不得出现更新按钮", state.availableBankUpdate)
        assertNull("静默检查不弹气泡", state.message)
    }

    @Test
    fun `the automatic check explains an unreadable format as an app update`() {
        // Reported from a device: the panel answered a format-3 manifest with
        // 「更新清单的格式版本 3 不受支持」 — a format number, and nothing the user could
        // do — even though the manifest stated the app version that would fix it. The
        // requirement has to reach the panel even though the rest of the manifest cannot
        // be read at all, and it has to reach it through the automatic entry check.
        val patched = bankWithBuiltAt(patchStamp)
        val fetcher = FakeHttpFetcher().apply {
            publish(patched, builtAt = patchStamp)
            manifestBody = manifestJson(
                patched,
                builtAt = patchStamp,
                formatVersion = BankManifestParser.MAX_SUPPORTED_FORMAT + 1,
                minAppVersionCode = 10_600L,
            )
        }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
            appVersionCode = 10_505L,
        )
        runBlocking { subject.awaitIdle() }

        runBlocking { subject.checkBankOnLaunch() }
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertEquals(10_600L, state.bankNeedsNewerApp)
        assertNull("读不了的格式不得留下两台提示", state.availableBankUpdate)
        assertNull("静默检查不弹气泡", state.message)
    }

    @Test
    fun `a finished check leaves a timestamp the card can print`() {
        // The automatic check used to be invisible: it either produced an offer or left no
        // trace at all, so "checked, nothing new" and "never checked" looked the same.
        val fetcher = FakeHttpFetcher().apply { publish(fixtureBankBytes()) }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }
        assertNull("构造本身不是一次检查", subject.uiState.value.bankCheckedAtMillis)

        runBlocking { subject.checkBankOnLaunch() }
        runBlocking { subject.awaitIdle() }

        assertEquals(FIXED_NOW_MILLIS, subject.uiState.value.bankCheckedAtMillis)
        assertFalse(subject.uiState.value.bankCheckFailed)
    }

    @Test
    fun `a check that could not reach the endpoint still leaves a timestamp`() {
        // The reason is a snackbar and the snackbar goes away, so without this the row
        // would fall back to "尚未检查" and the failure would look like it never happened.
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = FakeHttpFetcher().apply { failure = IOException("connection refused") },
        )
        runBlocking { subject.awaitIdle() }

        runBlocking { subject.checkBankOnLaunch() }
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertEquals(FIXED_NOW_MILLIS, state.bankCheckedAtMillis)
        assertTrue("静默检查也要记下它失败了", state.bankCheckFailed)
        assertNull("静默检查不上气泡", state.message)
    }

    @Test
    fun `a check that succeeds clears the earlier failure`() {
        val fetcher = FakeHttpFetcher().apply { failure = IOException("connection refused") }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
        )
        runBlocking { subject.awaitIdle() }
        runBlocking { subject.checkBankOnLaunch() }
        runBlocking { subject.awaitIdle() }
        assertTrue(subject.uiState.value.bankCheckFailed)

        // The button, not the automatic path: the launch check wrote the throttle, so a
        // second automatic ask inside the same window would be refused on purpose. What this
        // test is about is the state the *next finished check* leaves behind.
        fetcher.failure = null
        fetcher.publish(fixtureBankBytes())
        subject.checkBankUpdate()
        runBlocking { subject.awaitIdle() }

        assertFalse("第二次成功之后不能再报失败", subject.uiState.value.bankCheckFailed)
    }

    @Test
    fun `the automatic check is off when the stored switch says so`() {
        val fetcher = FakeHttpFetcher().apply { publish(fixtureBankBytes()) }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
            updatePreferences = MemoryUpdatePreferences(UpdatePreferencesState(autoCheckBank = false)),
        )
        runBlocking { subject.awaitIdle() }
        assertFalse("启动读到的开关要落到面板上", subject.uiState.value.autoCheckBank)

        runBlocking { subject.checkBankOnLaunch() }
        runBlocking { subject.awaitIdle() }
        assertEquals("关掉之后启动也不得联网", emptyList<String>(), fetcher.urls)

        // The button is still the way in, and it is the same request.
        subject.checkBankUpdate()
        runBlocking { subject.awaitIdle() }
        assertEquals(listOf(FakeHttpFetcher.MANIFEST_URL), fetcher.urls)
    }

    @Test
    fun `every launch checks, and a relaunch inside the floor does not`() {
        // 这就是用户要的语义：打开软件就查一次，卡片上的「上次检查」跟着这次启动走。以前还
        // 要多等过一个六小时窗口，窗口里的启动一次都不问——从外面看就是这个应用不再检查了。
        // 闸只挡「同一分钟里的又一次 Activity 创建」（转屏就是），那不是又一次打开软件。
        val fetcher = FakeHttpFetcher().apply { publish(fixtureBankBytes()) }
        val preferences = MemoryUpdatePreferences()
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
            updatePreferences = preferences,
        )
        runBlocking { subject.awaitIdle() }

        assertTrue("启动那一次没有查", runBlocking { subject.checkBankOnLaunch() })
        assertEquals(listOf(FakeHttpFetcher.MANIFEST_URL), fetcher.urls)
        assertEquals(
            "查完的时间必须落盘，否则「上次检查」重开就丢",
            FIXED_NOW_MILLIS,
            preferences.state.lastBankCheckAt,
        )

        // 同一分钟里重开：新实例的内存是空的，但盘里的时间戳还在一分钟内，所以不问第二遍。
        val relaunched = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
            updatePreferences = preferences,
        )
        runBlocking { relaunched.awaitIdle() }
        assertFalse("一分钟里重开又查了一次", runBlocking { relaunched.checkBankOnLaunch() })
        assertEquals("闸里的第二次启动不得联网", 1, fetcher.urls.size)

        // 十分钟后再打开软件：那是又一次「打开软件」，该问 —— 判据是这次启动，不是窗口。
        mainDispatcher.scheduler.advanceTimeBy(10 * 60 * 1000L)
        val restarted = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
            updatePreferences = preferences,
        )
        runBlocking { restarted.awaitIdle() }
        assertTrue("过了闸重开却没查", runBlocking { restarted.checkBankOnLaunch() })
        assertEquals("重开一次就该是两次请求", 2, fetcher.urls.size)
    }

    @Test
    fun `a launch inside the floor still says when the last check was`() {
        // 这一次启动不查（离上次不到一分钟），但卡片不能因此退回「尚未检查」：上一次检查的
        // 时间是盘上就写着的事实，只是它的结论本进程没看到过，所以只印时间、不印结论。
        val lastCheck = FIXED_NOW_MILLIS - 30 * 1000L
        val fetcher = FakeHttpFetcher().apply { publish(fixtureBankBytes()) }
        val subject = apiViewModel(
            api = FakeCompletionApi(goldenCase().answers),
            ioDispatcher = mainDispatcher,
            bankFetcher = fetcher,
            updatePreferences = MemoryUpdatePreferences(
                UpdatePreferencesState(lastBankCheckAt = lastCheck),
            ),
        )

        assertFalse("一分钟内又查了一次", runBlocking { subject.checkBankOnLaunch() })
        assertEquals("闸里的启动不该联网", emptyList<String>(), fetcher.urls)
        assertEquals(lastCheck, subject.uiState.value.bankEarlierCheckAtMillis)
        assertEquals(UpdateCheckOutcome.EARLIER_CHECK, bankCheckOutcome(subject.uiState.value))
    }

    @Test
    fun `the timer checks again while the app stays open`() {
        val fetcher = FakeHttpFetcher().apply { publish(fixtureBankBytes()) }
        val subject = apiViewModel(
            api = FakeCompletionApi(goldenCase().answers),
            ioDispatcher = mainDispatcher,
            bankFetcher = fetcher,
        )
        runBlocking { subject.checkBankOnLaunch() }
        assertEquals("启动那次照常发", 1, fetcher.urls.size)

        // 常开一路走过去，没到窗口就不查。
        mainDispatcher.scheduler.advanceTimeBy(2 * 60 * 60 * 1000L)
        assertEquals("窗口没到就查了", 1, fetcher.urls.size)

        // 跨过窗口，常开也要自己查一次；而且只能一次 —— 定时器醒来时若把「刚查完」
        // 当成「到期了」，一个窗口里就会问端点两遍。
        mainDispatcher.scheduler.advanceTimeBy(UpdatePreferencesState.APP_CHECK_INTERVAL_MILLIS)
        assertEquals("常开过了一个窗口却没自动查", 2, fetcher.urls.size)

        // 再走一个窗口，还是一个窗口一次。
        mainDispatcher.scheduler.advanceTimeBy(UpdatePreferencesState.APP_CHECK_INTERVAL_MILLIS)
        assertEquals("第二个窗口的次数不对", 3, fetcher.urls.size)
    }

    @Test
    fun `a wake that finds the window 30 seconds away still waits out the minimum`() {
        // 「窗口只差一点点」只出现在时间被往回调过之后：上一次检查的时间戳落在未来（这里 30
        // 秒之后），启动那一次被闸挡住不查，定时器的第一次唤醒算出「还差 6 小时半」而被上限
        // 压回 6 小时，第二次醒来时只差 30 秒 —— 唯一一段最小间隔会改变答案的地方。有它在，
        // 算术出错只花掉一次迟到的检查，而不是一个紧循环。
        val fetcher = FakeHttpFetcher().apply { publish(fixtureBankBytes()) }
        val subject = apiViewModel(
            api = FakeCompletionApi(goldenCase().answers),
            ioDispatcher = mainDispatcher,
            bankFetcher = fetcher,
            updatePreferences = MemoryUpdatePreferences(
                UpdatePreferencesState(lastBankCheckAt = FIXED_NOW_MILLIS + 30_000L),
            ),
        )

        assertFalse("落在未来的时间戳不该让启动检查联网", runBlocking { subject.checkBankOnLaunch() })
        assertEquals(emptyList<String>(), fetcher.urls)

        // 六小时后定时器醒来，把「还差 30 秒」推到一分钟外；没有那道闸，这里就有一次请求。
        mainDispatcher.scheduler.advanceTimeBy(6 * 60 * 60 * 1000L + 59_000L)
        assertEquals("最小间隔没兜住提前的唤醒", emptyList<String>(), fetcher.urls)

        mainDispatcher.scheduler.advanceTimeBy(2_000L)
        assertEquals("过了最小间隔还是没有自动检查", 1, fetcher.urls.size)
    }

    @Test
    fun `a check in flight keeps the timer from starting another`() {
        // 一个窗口两次请求就是白花一次：定时器是唯一会撞上「不是我发起的检查」的那条腿。
        val fetcher = FakeHttpFetcher().apply { publish(fixtureBankBytes()) }
        val subject = apiViewModel(
            api = FakeCompletionApi(goldenCase().answers),
            ioDispatcher = mainDispatcher,
            bankFetcher = fetcher,
        )
        runBlocking { subject.checkBankOnLaunch() }
        assertEquals("启动那次照常发", 1, fetcher.urls.size)

        // 用户按下的那次停在门上。
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate
        subject.checkBankUpdate()
        assertEquals("按钮的检查没发出去", 2, fetcher.urls.size)

        // 一个窗口的虚拟时间过去，那次检查还停在门上，定时器醒来撞上它。
        mainDispatcher.scheduler.advanceTimeBy(UpdatePreferencesState.APP_CHECK_INTERVAL_MILLIS + 1_000L)
        assertEquals("已有检查在飞时定时器又开了一个", 2, fetcher.urls.size)

        fetcher.gate = null
        gate.complete(Unit)
        mainDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `the timer respects the switch`() {
        // 开关关的不是「启动那一次」，是整条自动检查：常开一路也不能绕过去。
        val fetcher = FakeHttpFetcher().apply { publish(fixtureBankBytes()) }
        val subject = apiViewModel(
            api = FakeCompletionApi(goldenCase().answers),
            ioDispatcher = mainDispatcher,
            bankFetcher = fetcher,
            updatePreferences = MemoryUpdatePreferences(UpdatePreferencesState(autoCheckBank = false)),
        )
        assertFalse("关着开关却查了", runBlocking { subject.checkBankOnLaunch() })

        mainDispatcher.scheduler.advanceTimeBy(
            UpdatePreferencesState.APP_CHECK_INTERVAL_MILLIS * 3 + 1_000L,
        )
        assertEquals("关着开关的常开一路还是查了", emptyList<String>(), fetcher.urls)

        // 打开之后，最近的这次唤醒就会查 —— 关着的时候从没写过时间戳，所以没有可等窗口。
        subject.setAutoCheckBank(true)
        runBlocking { subject.awaitIdle() }
        mainDispatcher.scheduler.advanceTimeBy(
            UpdatePreferencesState.APP_CHECK_INTERVAL_MILLIS + 1_000L,
        )
        assertEquals("打开开关后不再自己查", 1, fetcher.urls.size)
    }

    @Test
    fun `a check whose timestamp is lost does not become a request storm`() {
        // 落盘可能失败，而只递回旧值的存储正是唤醒算术看到的东西。要是只认落盘那份，每次
        // 唤醒都会算出「早就该查了」，一个窗口里会问端点无数次；进程内那份才是它变成
        // 「六小时一次」的原因。
        val fetcher = FakeHttpFetcher().apply { publish(fixtureBankBytes()) }
        val subject = apiViewModel(
            api = FakeCompletionApi(goldenCase().answers),
            ioDispatcher = mainDispatcher,
            bankFetcher = fetcher,
            updatePreferences = MemoryUpdatePreferences(dropWrites = true),
        )
        runBlocking { subject.checkBankOnLaunch() }
        assertEquals("启动那次照常发", 1, fetcher.urls.size)

        // 一个窗口的虚拟时间；要是退化成死循环式重查，这里会是一堆请求。
        mainDispatcher.scheduler.advanceTimeBy(UpdatePreferencesState.APP_CHECK_INTERVAL_MILLIS + 1_000L)
        assertEquals("时间戳没落地就退化成死循环式重查", 2, fetcher.urls.size)
    }

    @Test
    fun `flipping the switch is written down`() {
        val preferences = MemoryUpdatePreferences()
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = FakeHttpFetcher().apply { publish(fixtureBankBytes()) },
            updatePreferences = preferences,
        )
        runBlocking { subject.awaitIdle() }
        assertTrue("默认开", subject.uiState.value.autoCheckBank)

        subject.setAutoCheckBank(false)
        runBlocking { subject.awaitIdle() }

        assertFalse(subject.uiState.value.autoCheckBank)
        assertFalse("开关要落盘，否则重启就变回来", preferences.state.autoCheckBank)
    }

    @Test
    fun `an installable offer clears the newer-app requirement`() {
        // The flag describes the currently published manifest, not a permanent state:
        // once the publisher ships something this app can parse, the hint must go.
        val patched = bankWithBuiltAt(patchStamp)
        val fetcher = FakeHttpFetcher().apply { publish(patched, builtAt = patchStamp) }
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFetcher = fetcher,
            appVersionCode = 10_400L,
        )
        runBlocking { subject.awaitIdle() }
        fetcher.manifestBody =
            manifestJson(patched, builtAt = patchStamp, minAppVersionCode = 10_500L)
        runBlocking { subject.checkBankOnLaunch() }
        runBlocking { subject.awaitIdle() }
        assertNotNull(subject.uiState.value.bankNeedsNewerApp)

        // A second look at what the publisher ships now; the button, because the automatic
        // path is throttled to one attempt per window and this test is about the state.
        fetcher.manifestBody =
            manifestJson(patched, builtAt = patchStamp, minAppVersionCode = 10_400L)
        subject.checkBankUpdate()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertNull("能装的包一发布，「先更新应用」的提示必须消失", state.bankNeedsNewerApp)
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
            bankFetcher = FakeHttpFetcher().apply {
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
        val fetcher = FakeHttpFetcher().apply {
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
            listOf(FakeHttpFetcher.MANIFEST_URL),
            fetcher.urls,
        )
    }

    @Test
    fun `a delete that failed does not re-check the manifest`() {
        val fetcher = FakeHttpFetcher().apply {
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
        val fetcher = FakeHttpFetcher().apply { publish(bankWithBuiltAt(patchStamp), builtAt = patchStamp) }
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

    @Test
    fun `a package action during the startup read refuses without claiming the package is updating`() {
        // 启动读期间 isLoading = true：ViewModel 会拒绝包操作（快照还没就绪），这是对的；
        // 但它不得谎报「检测包正在更新」——首屏自己写着「正在加载」，再弹一条错误是误导。
        // 真正要守的是另一半：卡片上的按钮必须按同一条件变灰，不能可点却无反应
        // （FingerprintScreen 的 busy 里补上了 isLoading，见那里的说明）。
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFiles = MemoryBankFileSystem(installed = bankWithBuiltAt(patchStamp)),
        )
        setLoadingForTest(subject, loading = true)
        subject.clearMessage()

        subject.removeInstalledPackage()
        runBlocking { subject.awaitIdle() }

        assertFalse("启动读期间不得把包操作执行下去", subject.uiState.value.isInstallingBank)
        assertFalse(
            "启动读期间不得谎报检测包正在更新：${subject.uiState.value.message}",
            subject.uiState.value.message?.contains("更新") == true,
        )
    }

    @Test
    fun `a package action during a real install says why`() {
        // 反向：真正在换包时必须出声说明，否则用户以为按钮坏了。用 isInstallingBank 模拟
        // 「正在更新」这个真实忙碌窗口。
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            FakeCompletionApi(goldenCase().answers),
            bankFiles = MemoryBankFileSystem(installed = bankWithBuiltAt(patchStamp)),
        )
        setInstallingForTest(subject, installing = true)
        subject.clearMessage()

        subject.removeInstalledPackage()

        assertTrue(
            "换包期间被拒绝必须说明原因：${subject.uiState.value.message}",
            subject.uiState.value.message?.contains("更新") == true,
        )
    }

    /** Forces the startup-read flag, the window where the package actions are refused. */
    private fun setLoadingForTest(subject: FingerprintViewModel, loading: Boolean) {
        setUiStateFlagForTest(subject) { it.copy(isLoading = loading) }
    }

    private fun setInstallingForTest(subject: FingerprintViewModel, installing: Boolean) {
        setUiStateFlagForTest(subject) { it.copy(isInstallingBank = installing) }
    }

    private fun setUiStateFlagForTest(
        subject: FingerprintViewModel,
        transform: (com.relaytester.app.feature.fingerprint.FingerprintUiState) ->
            com.relaytester.app.feature.fingerprint.FingerprintUiState,
    ) {
        val field = FingerprintViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(subject) as kotlinx.coroutines.flow.MutableStateFlow<
            com.relaytester.app.feature.fingerprint.FingerprintUiState
            >
        state.value = transform(state.value)
    }

    // ---- R02：单题重试与轮次的隔离 ------------------------------------------
    //
    // 每条用例同一个骨架：主轮跑完且第一题失败 → 重试挂起在假 API 上 → 执行一个
    // 会使轮次上下文失效的动作 → 放行旧请求 → 断言旧请求什么都没写回。挂起点是
    // CompletableDeferred，不是 sleep，所以调度是确定的。

    /** 主轮跑完、第一题已失败、重试已挂起。返回 (viewModel, fakeApi)。 */
    private fun parkedRetryState(api: RelayApi): FingerprintViewModel {
        // The store-backed harness is the one the other parked-retry cases use: it holds a
        // real supplier and key, so the round actually dispatches and settles into the
        // FAILED row this scenario needs before the retry is aimed at it.
        val subject = readyApiViewModelFor(SingleSupplierStore(testSupplier()), api).apply {
            updateParallel(false)
        }
        subject.runApiDetection()
        awaitBatchSettled(subject)
        return subject
    }

    @Test
    fun `a retry answered after the challenges are regenerated writes nothing`() {
        val api = ParkedRetryApi(goldenCase().answers)
        val subject = parkedRetryState(api)
        assertTrue("第一题应当可重试", subject.uiState.value.retryableModels.containsKey(0))

        subject.retryChallenge(0)
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.retryParked.await() } }

        subject.regenerateChallenges()
        api.release()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertTrue("重新出题后旧结果不得回来", state.batchResults.isEmpty())
        assertTrue("旧答案不得回到任何模型格", state.modelSlots.isEmpty())
        assertTrue(state.retryableModels.isEmpty())
        assertTrue(state.queuedRetries.isEmpty())
        state.progress.forEach { entry ->
            assertEquals("新题目不得被旧答案占据", ChallengeState.PENDING, entry.state)
            assertEquals("新题目不得带上旧答案", "", entry.answer)
        }
    }

    @Test
    fun `a retry answered after a supplier switch writes nothing`() {
        val api = ParkedRetryApi(goldenCase().answers)
        val subject = parkedRetryState(api)
        subject.retryChallenge(0)
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.retryParked.await() } }

        subject.selectSupplier("sup-1")
        api.release()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertTrue("切站后旧结果不得回来", state.batchResults.isEmpty())
        assertTrue("旧答案不得回到任何模型格", state.modelSlots.isEmpty())
        assertTrue(state.queuedRetries.isEmpty())
    }

    @Test
    fun `a retry answered after a mode switch writes nothing`() {
        val api = ParkedRetryApi(goldenCase().answers)
        val subject = parkedRetryState(api)
        subject.retryChallenge(0)
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.retryParked.await() } }

        subject.selectMode(DetectionMode.MANUAL)
        api.release()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertTrue("切模式后旧结果不得回来", state.batchResults.isEmpty())
        assertTrue("旧答案不得回到任何模型格", state.modelSlots.isEmpty())
        assertTrue(state.queuedRetries.isEmpty())
    }

    @Test
    fun `a retry answered after the model selection changes writes nothing`() {
        val api = ParkedRetryApi(goldenCase().answers)
        val subject = parkedRetryState(api)
        subject.retryChallenge(0)
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.retryParked.await() } }

        subject.toggleModelSelection("test-model")
        api.release()
        runBlocking { subject.awaitIdle() }

        val state = subject.uiState.value
        assertTrue("改勾选后旧结果不得回来", state.batchResults.isEmpty())
        assertTrue("旧答案不得回到任何模型格", state.modelSlots.isEmpty())
        assertTrue(state.queuedRetries.isEmpty())
    }

    @Test
    fun `a retry answered after a new round started writes nothing into it`() {
        val api = ParkedRetryApi(goldenCase().answers)
        val history = FingerprintHistoryStore(MemoryHistoryFiles())
        val subject = readyApiViewModelFor(
            SingleSupplierStore(testSupplier()),
            api,
            historyStore = history,
        ).apply { updateParallel(false) }
        subject.runApiDetection()
        awaitBatchSettled(subject)

        subject.retryChallenge(0)
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.retryParked.await() } }

        // 新主轮在旧重试仍在飞时开始：先作废旧重试，再跑自己的三题。
        subject.runApiDetection()
        awaitBatchSettled(subject)
        api.release()
        runBlocking { subject.awaitIdle() }

        val golden = goldenCase().answers
        val slots = subject.uiState.value.modelSlots["test-model"].orEmpty()
        assertEquals("新轮三格必须齐", 3, slots.size)
        // 新轮第 1 题的答案是 calls[4]（golden[1]）；旧重试的答案是 golden[0]，
        // 若旧请求写回了新轮，这里会变成 golden[0]。
        assertEquals("新轮的结果不得被旧重试覆盖", golden[1], slots[0].answer)
        assertEquals(ChallengeState.RECEIVED, slots[0].state)
        // 主轮记过两次历史；旧重试不得再记第三条。
        runBlocking {
            withTimeout(BATCH_SETTLE_TIMEOUT_MS) {
                while (subject.uiState.value.history.size < 2) delay(10)
            }
        }
        assertEquals("历史只记主轮，不记已作废的重试", 2, subject.uiState.value.history.size)
    }

    @Test
    fun `an old retry's cleanup must not delete a same-key retry of the new round`() {
        val cleanupGate = CompletableDeferred<Unit>()
        val api = ReplacingRetryApi(goldenCase().answers, cancellationGate = cleanupGate)
        val subject = parkedRetryState(api)

        subject.retryChallenge(0)
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.retryParked.await() } }
        // 取消使旧重试失效；取消的清理被 cleanupGate 拄住，模拟"旧 Job 还没走完回调"。
        // 放行 API 闸门：旧重试的 await 抛 CancellationException，走进被拄住的清理，
        // 逐题发送的串行互斥锁也随之释放。
        subject.cancelRun()
        api.release()
        // 等旧 Job 真的走完清理（cleanupGate 一放就完成），否则新一轮会被 runInFlight 挡住：
        // 取消后仍攥着 runJob 句柄是刻意设计，正是为了不让新人踩到垂死的旧轮。
        cleanupGate.complete(Unit)

        // 新一轮跑到第一题失败：同键重试只有在这之后才登记得上（没有结果时点击只是换题）。
        subject.runApiDetection()
        awaitBatchSettled(subject)
        subject.retryChallenge(0)
        runBlocking { withTimeout(BATCH_SETTLE_TIMEOUT_MS) { api.retryTwoParked.await() } }

        // 旧 Job 的完成回调此刻已经落地过：若它无条件清掉了同键登记，这一格现在就
        // 不在 retryJobs 里，下面这次点击会发出额外的请求。格子在缓存里已是
        // REQUESTING，可重试列表本来就是空的，所以显式点名，单独钉住去重守卫。
        subject.retryChallenge(0, listOf("test-model"))
        assertEquals("同键重试在飞时不得再发请求", 8, api.calls.get())
        api.releaseSecond()
        runBlocking { subject.awaitIdle() }
        assertEquals(
            "新一轮重试必须能正常补题",
            ChallengeState.RECEIVED,
            subject.uiState.value.modelSlots["test-model"]?.get(0)?.state,
        )
    }

    // ---- R06: history commands run in the order they were issued ---------

    @Test
    fun `a clear issued after a parked append cannot overtake it`() {
        val appendGate = CompletableDeferred<Unit>()
        val history = GatedAppendHistoryStore(MemoryHistoryFiles(), appendGate)
        val subject = readyApiViewModel(FakeCompletionApi(goldenCase().answers), historyStore = history)
        subject.runApiDetection()
        awaitBatchSettled(subject)
        // 追加已进入存储并拄在门上：此刻发起的清空必须排队，而不是抢先进入 IO。
        runBlocking { history.appendStarted.await() }

        subject.clearHistory()
        runBlocking { delay(100) }
        assertEquals("清空必须排在未完成的追加之后", 0, history.clearCalls.get())

        appendGate.complete(Unit)
        runBlocking { subject.awaitIdle() }
        assertEquals("清空必须恰好执行一次", 1, history.clearCalls.get())
        assertTrue("磁盘上不得留下清空前的旧记录", history.load().isEmpty())
        assertTrue("界面上不得留下清空前的旧记录", subject.uiState.value.history.isEmpty())
    }

    @Test
    fun `a new append after a clear is the only record left`() {
        val history = GatedAppendHistoryStore(MemoryHistoryFiles())
        val subject = readyApiViewModel(FakeCompletionApi(goldenCase().answers), historyStore = history)
        subject.runApiDetection()
        awaitBatchSettled(subject)
        runBlocking { subject.awaitIdle() }
        assertEquals(1, history.load().size)

        subject.clearHistory()
        runBlocking { subject.awaitIdle() }
        assertTrue("清空应生效", history.load().isEmpty())

        subject.runApiDetection()
        awaitBatchSettled(subject)
        runBlocking { subject.awaitIdle() }
        assertEquals("清空后的新检测必须保留", 1, history.load().size)
        assertEquals("界面与磁盘看到同一份历史", 1, subject.uiState.value.history.size)
    }

    @Test
    fun `consecutive clears all run and leave nothing behind`() {
        val history = GatedAppendHistoryStore(MemoryHistoryFiles())
        val subject = readyApiViewModel(FakeCompletionApi(goldenCase().answers), historyStore = history)
        subject.runApiDetection()
        awaitBatchSettled(subject)
        runBlocking { subject.awaitIdle() }

        subject.clearHistory()
        subject.clearHistory()
        runBlocking { subject.awaitIdle() }

        assertEquals("两次清空都要执行", 2, history.clearCalls.get())
        assertTrue(history.load().isEmpty())
    }

    @Test
    fun `a clear that cannot write reports the failure`() {
        val subject = apiViewModel(
            FakeCompletionApi(goldenCase().answers),
            historyStore = FingerprintHistoryStore(FailingHistoryFiles()),
        )

        subject.clearHistory()
        runBlocking { subject.awaitIdle() }

        assertTrue(
            "写盘失败要给出明确反馈",
            subject.uiState.value.message?.contains("历史记录清空失败") == true,
        )
        assertTrue(subject.uiState.value.isMessageError)
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

    /**
     * The round's streaming entry point, routed to the same fake answer.
     *
     * The view model always calls the streaming method, so a fake that only knew
     * [completeText] would leave these tests talking to the real network. Delegating
     * keeps this fake's answers, ordering and concurrency accounting unchanged; the
     * live-count behaviour itself is covered by [StreamingCompletionApi].
     */
    override suspend fun completeTextStreaming(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        onProgress: suspend (String) -> Unit,
    onReasoning: suspend (String) -> Unit,
        salvageOnDisconnect: Boolean,
    ): ApiResult<String> = completeText(profile, apiKey, model, prompt, maxTokens, timeoutSeconds)
}

/**
 * A long in-range number list that clears the validity floor (max(80, ⌈count×0.55⌉)).
 *
 * Synthetic on purpose: these cases are about which ask gets repeated and what the panel
 * says, not about which model ranks first, so the answer only has to be scoreable.
 */
private fun numbersAnswer(count: Int = 320): String = (1..count).joinToString(", ")

/**
 * Answers normally, except for chosen ask indexes, which come back with no answer text.
 *
 * Keyed by ask order rather than by question: with the parallel switch on, the three
 * questions are asked concurrently, so "which question got the blank" is not fixed — "the
 * first ask came back blank" is. A blank can also report reasoning, which is the shape the
 * real supplier produced: HTTP 200, the whole token budget spent in the reasoning channel,
 * zero characters of content.
 *
 * The hold is the same one [FakeCompletionApi] carries: without a suspension point the
 * unconfined main dispatcher runs each ask to completion before the next one starts, and a
 * parallel round would be indistinguishable from a sequential one.
 */
private class BlankAskThenAnswerApi(
    private val answers: List<String>,
    private val blankAsks: Set<Int> = setOf(0),
    /** What the blank asks put on the reasoning channel; empty is the silent blank. */
    private val blankReasoning: String = "",
    private val failure: TestError = TestError(
        ErrorKind.INVALID_RESPONSE,
        "上游返回 HTTP 200 但没有可用的文本内容",
    ),
    private val holdMs: Long = 20,
) : RelayApi() {
    val calls = AtomicInteger(0)

    override suspend fun completeTextStreaming(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        onProgress: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
        salvageOnDisconnect: Boolean,
    ): ApiResult<String> {
        // Taken on entry, so a parallel round still hands each ask its own index.
        val index = calls.getAndIncrement()
        withContext(Dispatchers.Default) { delay(holdMs) }
        if (index in blankAsks) {
            if (blankReasoning.isNotEmpty()) onReasoning(blankReasoning)
            return ApiResult.Failure(failure)
        }
        return ApiResult.Success(answers[index % answers.size])
    }
}

/**
 * Parks the automatic re-ask, so the panel can be read while it is in flight.
 *
 * The first ask comes back blank; the ask that follows — the re-ask of the very same
 * question — parks. That is the only moment 「重试中…」 is on screen, and the moment a
 * re-ask that failed to announce itself would read exactly like the first attempt still
 * receiving.
 */
private class ParkedReaskApi(private val answers: List<String>) : RelayApi() {
    val calls = AtomicInteger(0)
    val prompts = Collections.synchronizedList(mutableListOf<String>())

    /** Completed once the re-ask is parked inside the relay. */
    val reaskParked = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun completeTextStreaming(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        onProgress: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
        salvageOnDisconnect: Boolean,
    ): ApiResult<String> {
        val index = calls.getAndIncrement()
        prompts += prompt
        return when (index) {
            0 -> ApiResult.Failure(
                TestError(ErrorKind.INVALID_RESPONSE, "上游返回 HTTP 200 但没有可用的文本内容"),
            )
            1 -> {
                reaskParked.complete(Unit)
                gate.await()
                ApiResult.Success(answers[index % answers.size])
            }
            else -> ApiResult.Success(answers[index % answers.size])
        }
    }
}

/**
 * Streams one answer in pieces, so the panel's live integer count can be observed.
 *
 * Emits [chunks] through the progress callback with a suspension between them, then
 * returns their concatenation. The suspension is load bearing: without it the panel
 * would see every chunk and the final answer in the same frame, and a state that only
 * ever held the end result would look identical to one that counted along the way.
 */
private class StreamingCompletionApi(
    private val chunks: List<String>,
    private val holdMs: Long = 5,
) : RelayApi() {
    /** Every partial text the view model saw, in order. */
    val reported = Collections.synchronizedList(mutableListOf<String>())

    override suspend fun completeTextStreaming(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        onProgress: suspend (String) -> Unit,
    onReasoning: suspend (String) -> Unit,
        salvageOnDisconnect: Boolean,
    ): ApiResult<String> {
        val accumulated = StringBuilder()
        for (chunk in chunks) {
            withContext(Dispatchers.Default) { delay(holdMs) }
            accumulated.append(chunk)
            reported += accumulated.toString()
            onProgress(accumulated.toString())
        }
        return ApiResult.Success(accumulated.toString())
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

    /** Routed to the gated [completeText], so the parked request is the streaming one too. */
    override suspend fun completeTextStreaming(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        onProgress: suspend (String) -> Unit,
    onReasoning: suspend (String) -> Unit,
        salvageOnDisconnect: Boolean,
    ): ApiResult<String> = completeText(profile, apiKey, model, prompt, maxTokens, timeoutSeconds)
}

/**
 * Parks the single-question retry, so the panel can be read while it is in flight.
 *
 * Question 1 fails in the round; questions 2 and 3 succeed; the retry of question 1
 * parks. That is the state where a retry entry still showing, or a second retry being
 * accepted, would mean two requests for the same slot.
 */
private class ParkedRetryApi(
    private val answers: List<String>,
) : RelayApi() {
    /** Completed once the retry of question 1 is parked. */
    val retryParked = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()
    private val calls = AtomicInteger(0)
    val prompts = Collections.synchronizedList(mutableListOf<String>())

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun completeText(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
    ): ApiResult<String> {
        prompts += prompt
        return when (calls.getAndIncrement()) {
            0 -> ApiResult.Failure(TestError(ErrorKind.UPSTREAM, "上游返回 HTTP 502"))
            // The round's own three questions come first; the fourth call is the retry.
            3 -> {
                retryParked.complete(Unit)
                gate.await()
                ApiResult.Success(answers[0])
            }
            else -> ApiResult.Success(answers[1])
        }
    }

    override suspend fun completeTextStreaming(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        onProgress: suspend (String) -> Unit,
    onReasoning: suspend (String) -> Unit,
        salvageOnDisconnect: Boolean,
    ): ApiResult<String> = completeText(profile, apiKey, model, prompt, maxTokens, timeoutSeconds)
}

/**
 * Like [ParkedRetryApi], plus two things the cleanup-isolation case needs: the retry's
 * cancellation cleanup can be held open (the way a connection being released holds it),
 * and the second round's same-key retry parks on its own gate.
 *
 * The call plan is fixed, so the indices are spelled out: round one is calls 0–2 (call 0
 * fails), its retry is call 3, round two is calls 4–6 (call 4 fails), and round two's
 * retry is call 7.
 */
private class ReplacingRetryApi(
    private val answers: List<String>,
    private val cancellationGate: CompletableDeferred<Unit>? = null,
) : RelayApi() {
    /** Completed once the first retry (call 3) is parked. */
    val retryParked = CompletableDeferred<Unit>()

    /** Completed once the second round's retry (call 7) is parked. */
    val retryTwoParked = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()

    /** The second round's retry holds here, so it is still on the wire at the dedup click. */
    private val secondGate = CompletableDeferred<Unit>()
    val calls = AtomicInteger(0)

    fun release() {
        gate.complete(Unit)
    }

    fun releaseSecond() {
        secondGate.complete(Unit)
    }

    override suspend fun completeText(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
    ): ApiResult<String> {
        return when (val position = calls.getAndIncrement()) {
            0, 4 -> ApiResult.Failure(TestError(ErrorKind.UPSTREAM, "上游返回 HTTP 502"))
            3 -> {
                retryParked.complete(Unit)
                try {
                    gate.await()
                } catch (error: CancellationException) {
                    cancellationGate?.let { cleanup -> withContext(NonCancellable) { cleanup.await() } }
                    throw error
                }
                ApiResult.Success(answers[0])
            }
            7 -> {
                retryTwoParked.complete(Unit)
                secondGate.await()
                ApiResult.Success(answers[0])
            }
            else -> ApiResult.Success(answers[position % answers.size])
        }
    }

    override suspend fun completeTextStreaming(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        onProgress: suspend (String) -> Unit,
    onReasoning: suspend (String) -> Unit,
        salvageOnDisconnect: Boolean,
    ): ApiResult<String> = completeText(profile, apiKey, model, prompt, maxTokens, timeoutSeconds)
}

/** In-memory stand-in for the history file, so written rows can be counted. */
private class MemoryHistoryFiles(var content: String? = null) : HistoryFileSystem {
    override fun read(): String? = content

    override fun write(text: String) {
        content = text
    }
}

/** A filesystem whose writes always fail, the way a full disk would. */
private class FailingHistoryFiles : HistoryFileSystem {
    override fun read(): String? = null

    override fun write(text: String) {
        throw IOException("磁盘已满")
    }
}

/**
 * A history store the ordering tests can observe and hold: append signals its arrival
 * and then parks until released, and clear counts its calls. With no gate it is just a
 * counting pass-through.
 */
private class GatedAppendHistoryStore(
    files: HistoryFileSystem,
    private val appendGate: CompletableDeferred<Unit>? = null,
) : FingerprintHistoryStore(files) {
    /** Completed once an append has entered the store, before it parks. */
    val appendStarted = CompletableDeferred<Unit>()
    val clearCalls = AtomicInteger(0)

    override fun append(entries: List<DetectionHistoryEntry>): Boolean {
        appendStarted.complete(Unit)
        // 存储本身是同步 API（真实实现走阻塞文件 IO），所以要拄住它也只能阻塞等待；
        // 测试在专用线程上跑，主线程上的测试协程不会被这里挡住。
        appendGate?.let { gate -> runBlocking { gate.await() } }
        return super.append(entries)
    }

    override fun clear(): Boolean {
        clearCalls.incrementAndGet()
        return super.clear()
    }
}

/**
 * Rejects the first question, then parks the second, all for one model.
 *
 * The state the user described inside a single model: question 1 already failed while
 * question 2 is still receiving. The retry for question 1 must be available right then,
 * and question 2 must not be offered.
 */
private class FirstSlotFailsThenParksApi(
    private val answers: List<String>,
) : RelayApi() {
    /** Completed once the second question's request is parked. */
    val parked = CompletableDeferred<Unit>()

    /** Completed once any request beyond the parked one is answered. */
    val retried = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()
    private val calls = AtomicInteger(0)
    val prompts = Collections.synchronizedList(mutableListOf<String>())

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun completeText(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
    ): ApiResult<String> {
        prompts += prompt
        return when (calls.getAndIncrement()) {
            // The round's first question fails outright…
            0 -> ApiResult.Failure(TestError(ErrorKind.UPSTREAM, "上游返回 HTTP 502"))
            // …the second parks, so the panel can be read mid-round…
            1 -> {
                parked.complete(Unit)
                gate.await()
                ApiResult.Success(answers[1])
            }
            // …and anything after that is the retry (or the last question) and succeeds.
            else -> {
                retried.complete(Unit)
                ApiResult.Success(answers[2])
            }
        }
    }

    override suspend fun completeTextStreaming(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        onProgress: suspend (String) -> Unit,
    onReasoning: suspend (String) -> Unit,
        salvageOnDisconnect: Boolean,
    ): ApiResult<String> = completeText(profile, apiKey, model, prompt, maxTokens, timeoutSeconds)
}

/**
 * Fails one model outright, then parks the other mid-round.
 *
 * This is the shape the user described: three questions in, one model has already
 * failed while another is still receiving. The panel has to offer the retry for the
 * failed one right then — not after the batch finishes — and must not list the model
 * that is still requesting.
 */
private class FirstFailsThenParksApi(
    private val answers: List<String>,
    private val failingModel: String,
) : RelayApi() {
    /** Completed once the healthy model's first request is parked. */
    val parked = CompletableDeferred<Unit>()

    /** Completed when a request for [failingModel] arrives after its own round part. */
    val retried = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()
    val models = Collections.synchronizedList(mutableListOf<String>())

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun completeText(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
    ): ApiResult<String> {
        models += model
        if (model == failingModel) {
            // The round's own three requests fail; a fourth one can only be the retry,
            // so that is the signal a test joins on.
            if (models.count { it == failingModel } > 3) retried.complete(Unit)
            return ApiResult.Failure(TestError(ErrorKind.UPSTREAM, "上游返回 HTTP 502"))
        }
        parked.complete(Unit)
        gate.await()
        return ApiResult.Success(answers[models.size % answers.size])
    }

    override suspend fun completeTextStreaming(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        onProgress: suspend (String) -> Unit,
    onReasoning: suspend (String) -> Unit,
        salvageOnDisconnect: Boolean,
    ): ApiResult<String> = completeText(profile, apiKey, model, prompt, maxTokens, timeoutSeconds)
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

    override suspend fun completeTextStreaming(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
        onProgress: suspend (String) -> Unit,
    onReasoning: suspend (String) -> Unit,
        salvageOnDisconnect: Boolean,
    ): ApiResult<String> = throw boom
}

/** Generous ceiling: only a genuinely stuck batch may hit it. */
private const val BATCH_SETTLE_TIMEOUT_MS = 10_000L

/**
 * The clock every view model in this file runs on.
 *
 * A fixed instant, so a check's timestamp is the same value in the assertion as on the
 * card; 2026-10-03T14:32+08:00, which is what a panel test reads as "今天 14:32".
 */
private const val FIXED_NOW_MILLIS = 1_791_009_120_000L

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
