package com.relaytester.app

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
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class FingerprintViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun bank(): FingerprintBank {
        val candidates = listOf(
            File("app/src/main/assets/lm-fingerprint/lite-bank.bin"),
            File("src/main/assets/lm-fingerprint/lite-bank.bin"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("找不到指纹资产文件：${File(".").absolutePath}")
        return FingerprintBank.fromAssetBytes(file.readBytes())
    }

    private fun viewModel(): FingerprintViewModel = FingerprintViewModel(
        supplierStore = EmptyStore(),
        secretStore = object : SecretStore {
            override fun put(value: String): String = "secret"
            override fun get(secretId: String): String = "api-key"
            override fun delete(secretId: String) = Unit
        },
        relayApiFactory = { throw AssertionError("本测试不应发起网络请求") },
        bankFactory = { bank() },
        skipRestore = true,
    )

    /**
     * Drives the API path offline. The relay is always a fake, so nothing here
     * touches the network; `skipRestore` keeps the initial store read out of the way
     * and the supplier is injected through the store instead.
     */
    private fun apiViewModel(api: RelayApi): FingerprintViewModel = FingerprintViewModel(
        supplierStore = SingleSupplierStore(testSupplier()),
        secretStore = object : SecretStore {
            override fun put(value: String): String = "secret-1"
            override fun get(secretId: String): String = "api-key"
            override fun delete(secretId: String) = Unit
        },
        relayApiFactory = { api },
        bankFactory = { bank() },
        skipRestore = true,
    )

    /** A round ready to dispatch: supplier chosen, model named, challenges seeded. */
    private fun readyApiViewModel(
        api: RelayApi,
        parallel: Boolean = true,
    ): FingerprintViewModel = apiViewModel(api).apply {
        selectSupplier("sup-1")
        updateModel("test-model")
        // Seeds the three challenges; without this the progress list is empty
        // because skipRestore skipped the initial load.
        selectMode(DetectionMode.API)
        updateParallel(parallel)
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

        val before = state.progress[0].challenge.id
        subject.retryChallenge(0)
        assertNotEquals(
            "失败后仍必须能重试该题",
            before,
            subject.uiState.value.progress[0].challenge.id,
        )
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
        assertEquals("夹具必须仍是文档记录的那一条样例", "gpt-4o", golden.top1)
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
    fun `detection refuses to run without a model name`() {
        val api = FakeCompletionApi(goldenCase().answers)
        val subject = apiViewModel(api).apply {
            selectSupplier("sup-1")
            selectMode(DetectionMode.API)
        }

        subject.runApiDetection()

        val state = subject.uiState.value
        assertEquals("请填写要检测的模型名", state.message)
        assertTrue(state.isMessageError)
        assertEquals("没有模型名就不该发出请求", 0, api.calls.get())
        assertFalse(state.isRunning)
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

private fun testSupplier(): SupplierProfile = SupplierProfile(
    id = "sup-1",
    name = "测试供应商",
    baseUrl = "https://relay.test/v1",
    protocol = RelayProtocol.CHAT_COMPLETIONS,
    apiKeySecretId = "secret-1",
    models = listOf("test-model"),
    testSettings = TestSettings(),
)

/**
 * Answers every challenge from [answers] in call order, optionally failing instead.
 *
 * The hold inside the request is load bearing: without a suspension point the
 * unconfined main dispatcher runs each request to completion before the next one
 * starts, so a parallel round and a sequential round become indistinguishable and
 * both concurrency assertions pass for free.
 */
private class FakeCompletionApi(
    private val answers: List<String>,
    private val failure: TestError? = null,
    private val holdMs: Long = 20,
) : RelayApi() {
    val calls = AtomicInteger(0)
    val maxConcurrent = AtomicInteger(0)
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
        val now = inFlight.incrementAndGet()
        maxConcurrent.updateAndGet { current -> maxOf(current, now) }
        try {
            withContext(Dispatchers.Default) { delay(holdMs) }
        } finally {
            inFlight.decrementAndGet()
        }
        failure?.let { return ApiResult.Failure(it) }
        return ApiResult.Success(answers[index % answers.size])
    }
}

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
    return GoldenCase(answers, first.getJSONObject("expected").getString("top1"))
}

private fun assertNotNull(message: String, value: Any?) {
    org.junit.Assert.assertNotNull(message, value)
}
