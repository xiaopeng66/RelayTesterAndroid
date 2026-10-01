package com.relaytester.app

import com.relaytester.app.core.fingerprint.FingerprintChallenge
import com.relaytester.app.feature.fingerprint.ChallengeProgress
import com.relaytester.app.feature.fingerprint.ChallengeState
import com.relaytester.app.feature.fingerprint.stateLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    ): ChallengeProgress = ChallengeProgress(
        challenge = FingerprintChallenge("c1", 300, "prompt"),
        state = state,
        parsedNumbers = parsed,
        error = error,
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
    fun `the other states keep their wording`() {
        assertEquals("等待发送", stateLabel(entry(ChallengeState.PENDING)))
        assertEquals("正在接收…", stateLabel(entry(ChallengeState.REQUESTING)))
        assertEquals(
            "已接收 300 个有效数字",
            stateLabel(entry(ChallengeState.RECEIVED, parsed = 300)),
        )
    }
}
