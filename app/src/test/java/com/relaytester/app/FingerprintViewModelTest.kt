package com.relaytester.app

import com.relaytester.app.core.fingerprint.FingerprintBank
import com.relaytester.app.core.fingerprint.minimumNumbersFor
import com.relaytester.app.core.security.SecretStore
import com.relaytester.app.core.storage.SupplierStore
import com.relaytester.app.core.storage.SupplierStoreState
import com.relaytester.app.feature.fingerprint.ChallengeProgress
import com.relaytester.app.feature.fingerprint.ChallengeState
import com.relaytester.app.feature.fingerprint.DetectionMode
import com.relaytester.app.feature.fingerprint.FingerprintViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
}

private class EmptyStore : SupplierStore(null) {
    override suspend fun read() = SupplierStoreState(emptyList(), null)
    override suspend fun save(state: SupplierStoreState) = Unit
}

private fun assertNotNull(message: String, value: Any?) {
    org.junit.Assert.assertNotNull(message, value)
}
