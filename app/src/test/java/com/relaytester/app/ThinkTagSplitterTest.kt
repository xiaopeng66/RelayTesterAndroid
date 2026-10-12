package com.relaytester.app

import com.relaytester.app.core.network.ThinkTagSplitter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `<think>` fence router that keeps a thinking model's chain of thought out of the
 * answer when a deployment folds it into `content`. The fence can be cut by any delta
 * boundary, so every case here feeds the text in awkwardly sized pieces.
 */
class ThinkTagSplitterTest {

    @Test
    fun `text outside a fence is answer`() {
        val splitter = ThinkTagSplitter()
        val (answer, reasoning) = splitter.feed("<!DOCTYPE html><html></html>")
        assertEquals("<!DOCTYPE html><html></html>", answer)
        assertEquals("", reasoning)
        val (finalAnswer, finalReasoning) = splitter.finish()
        assertEquals("", finalAnswer)
        assertEquals("", finalReasoning)
    }

    @Test
    fun `text inside a fence is reasoning`() {
        val splitter = ThinkTagSplitter()
        val (a1, r1) = splitter.feed("<think>先想一下。")
        assertEquals("", a1)
        assertEquals("先想一下。", r1)
        // </think> 落在分片中间：闭标签之前的进思考，之后的同一分片就是答案。
        val (a2, r2) = splitter.feed("再想一下。</think>正文开始")
        assertEquals("正文开始", a2)
        assertEquals("再想一下。", r2)
        val (a3, _) = splitter.feed("<html>")
        assertEquals("<html>", a3)
    }

    @Test
    fun `a fence cut across deltas still routes both sides`() {
        val splitter = ThinkTagSplitter()
        // "<thi" 是 <think> 的前缀，必须扣住不发。
        val (a1, r1) = splitter.feed("答案A<thi")
        assertEquals("答案A", a1)
        assertEquals("", r1)
        // "nk>" 补完围栏；扣住的四个字符一个都不能漏进答案。
        val (a2, r2) = splitter.feed("nk>思考")
        assertEquals("", a2)
        assertEquals("思考", r2)
        val (a3, r3) = splitter.feed("</th")
        assertEquals("", a3)
        assertEquals("", r3)
        val (a4, r4) = splitter.feed("ink>正文B")
        assertEquals("正文B", a4)
        assertEquals("", r4)
    }

    @Test
    fun `an unterminated fence at end of stream is all reasoning`() {
        val splitter = ThinkTagSplitter()
        val (fedAnswer, fedReasoning) = splitter.feed("<html><think>想到一半")
        assertEquals("<html>", fedAnswer)
        assertEquals("想到一半", fedReasoning)
        val (answer, reasoning) = splitter.finish()
        assertEquals("", answer)
        assertEquals("", reasoning)
    }

    @Test
    fun `a held partial tag flushed at end of stream is answer`() {
        // 流在 "<thi" 处结束：它不是围栏，是被扣住的普通文本，必须还给答案。
        val splitter = ThinkTagSplitter()
        val (a1, _) = splitter.feed("x<thi")
        assertEquals("x", a1)
        val (answer, reasoning) = splitter.finish()
        assertEquals("<thi", answer)
        assertEquals("", reasoning)
    }

    @Test
    fun `two fenced blocks in one stream both route`() {
        val splitter = ThinkTagSplitter()
        var reasoning = ""
        var answer = ""
        for (piece in listOf(
            "<think>一</think>A",
            "<think>二</think>B",
        )) {
            val (a, r) = splitter.feed(piece)
            answer += a
            reasoning += r
        }
        assertEquals("AB", answer)
        assertEquals("一二", reasoning)
    }

    @Test
    fun `empty deltas are no-ops`() {
        val splitter = ThinkTagSplitter()
        assertEquals("" to "", splitter.feed(""))
        assertEquals("" to "", splitter.finish())
    }
}
