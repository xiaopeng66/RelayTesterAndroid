package com.relaytester.app

import com.relaytester.app.core.fingerprint.FingerprintChallenge
import com.relaytester.app.feature.fingerprint.ChallengeProgress
import com.relaytester.app.feature.fingerprint.ChallengeState
import com.relaytester.app.feature.fingerprint.compactStateLabel
import com.relaytester.app.feature.fingerprint.stateLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status line sits directly above the reason line on a challenge card, so a
 * rejected card must not echo the reason here — doing so printed the same sentence
 * twice on every failed challenge.
 */
class FingerprintScreenLabelTest {
    private fun entry(
        state: ChallengeState,
        error: String? = null,
        parsed: Int = 0,
        received: Int = 0,
        retrying: Boolean = false,
    ): ChallengeProgress = ChallengeProgress(
        challenge = FingerprintChallenge("c1", 300, "prompt"),
        state = state,
        parsedNumbers = parsed,
        error = error,
        receivedNumbers = received,
        retrying = retrying,
    )

    @Test
    fun `a rejected card does not repeat its reason in the status line`() {
        val failure = "上游返回 HTTP 502"
        val label = stateLabel(entry(ChallengeState.REJECTED, error = failure))

        assertEquals("未采用", label)
        assertFalse("状态行不得重复失败原因", label.contains(failure))
    }

    @Test
    fun `a validity rejection also keeps a short status line`() {
        val reason = "有效数字不足（3/183）"
        val label = stateLabel(entry(ChallengeState.REJECTED, error = reason, parsed = 3))

        assertEquals("未采用", label)
        assertFalse("状态行不得重复有效性提示", label.contains(reason))
    }

    @Test
    fun `an automatic re-ask says so in both wordings`() {
        // 空白回答会带来一次自动重发（见 CHALLENGE_MAX_ATTEMPTS）。第二次等待如果还写
        // 「接收中…」，读起来就是同一题卡住了——而它其实又花掉了一次请求。
        assertEquals("正在重试…", stateLabel(entry(ChallengeState.REQUESTING, retrying = true)))
        assertEquals(
            "正在重试… 已收到 42 个整数",
            stateLabel(entry(ChallengeState.REQUESTING, received = 42, retrying = true)),
        )
        assertEquals("重试中…", compactStateLabel(entry(ChallengeState.REQUESTING, retrying = true)))
        assertEquals(
            "重试中 42",
            compactStateLabel(entry(ChallengeState.REQUESTING, received = 42, retrying = true)),
        )
        // 结题之后就不再有「重试中」：状态自己会说结果。
        assertEquals(
            "已接收 300 个有效数字",
            stateLabel(entry(ChallengeState.RECEIVED, parsed = 300, retrying = true)),
        )
    }

    @Test
    fun `the other states keep their wording`() {
        assertEquals("等待发送", stateLabel(entry(ChallengeState.PENDING)))
        assertEquals("正在接收…", stateLabel(entry(ChallengeState.REQUESTING)))
        assertEquals(
            "已接收 300 个有效数字",
            stateLabel(entry(ChallengeState.RECEIVED, parsed = 300)),
        )
    }

    @Test
    fun `the compact wording keeps the count and drops the long tail`() {
        assertEquals("等待发送", compactStateLabel(entry(ChallengeState.PENDING)))
        assertEquals("接收中…", compactStateLabel(entry(ChallengeState.REQUESTING)))
        assertEquals("接收中 166", compactStateLabel(entry(ChallengeState.REQUESTING, received = 166)))
        assertEquals("已接收 300", compactStateLabel(entry(ChallengeState.RECEIVED, parsed = 300)))
        assertEquals(
            "未采用",
            compactStateLabel(entry(ChallengeState.REJECTED, error = "上游返回 HTTP 502")),
        )
    }

    @Test
    fun `no compact wording grows past the column`() {
        // 一栏约 121dp，labelSmall 下大致只放得下 8 个汉字宽；再长就要靠省略号截断，
        // 而截掉的正是数字。所以这里钉的是「短」本身，不只是措辞。
        val longest = listOf(
            compactStateLabel(entry(ChallengeState.PENDING)),
            compactStateLabel(entry(ChallengeState.REQUESTING, received = 999)),
            compactStateLabel(entry(ChallengeState.REQUESTING, received = 999, retrying = true)),
            compactStateLabel(entry(ChallengeState.RECEIVED, parsed = 999)),
            compactStateLabel(entry(ChallengeState.REJECTED)),
        ).maxBy { it.length }

        assertTrue("最长的格子状态词只有「$longest」（${longest.length} 字）", longest.length <= 9)
    }
}
