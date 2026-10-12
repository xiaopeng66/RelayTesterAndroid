package com.relaytester.app

import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.network.RelayCompletionText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for the per-protocol answer extraction that fingerprint detection depends
 * on. `RelayApi.test` only checks that a response has an envelope; detection needs the
 * actual text, and each of the three protocols nests it differently.
 */
class RelayCompletionTextTest {

    @Test
    fun `chat completions reads the message content`() {
        val body = JSONObject(
            """
            {"choices":[{"message":{"role":"assistant","content":"1, 2, 3"}}]}
            """.trimIndent(),
        )
        assertEquals("1, 2, 3", RelayCompletionText.extract(body, RelayProtocol.CHAT_COMPLETIONS))
    }

    @Test
    fun `chat completions handles a content block array`() {
        // Some relays normalise to the multimodal shape even for plain text.
        val body = JSONObject(
            """
            {"choices":[{"message":{"content":[{"type":"text","text":"10, "},{"type":"text","text":"20"}]}}]}
            """.trimIndent(),
        )
        assertEquals("10, 20", RelayCompletionText.extract(body, RelayProtocol.CHAT_COMPLETIONS))
    }

    @Test
    fun `responses concatenates output text blocks and skips reasoning`() {
        val body = JSONObject(
            """
            {"status":"completed","output":[
              {"type":"reasoning","content":[{"type":"reasoning_text","text":"thinking..."}]},
              {"type":"message","content":[{"type":"output_text","text":"1, 2"},{"type":"output_text","text":", 3"}]}
            ]}
            """.trimIndent(),
        )
        val text = RelayCompletionText.extract(body, RelayProtocol.RESPONSES)
        assertEquals("1, 2, 3", text)
    }

    @Test
    fun `anthropic concatenates text blocks and skips thinking`() {
        val body = JSONObject(
            """
            {"content":[
              {"type":"thinking","thinking":"secret"},
              {"type":"text","text":"100, "},
              {"type":"text","text":"200"}
            ]}
            """.trimIndent(),
        )
        assertEquals("100, 200", RelayCompletionText.extract(body, RelayProtocol.ANTHROPIC))
    }

    @Test
    fun `missing envelopes yield empty text so the caller can report it`() {
        assertEquals("", RelayCompletionText.extract(JSONObject("{}"), RelayProtocol.CHAT_COMPLETIONS))
        assertEquals("", RelayCompletionText.extract(JSONObject("{}"), RelayProtocol.RESPONSES))
        assertEquals("", RelayCompletionText.extract(JSONObject("{}"), RelayProtocol.ANTHROPIC))
        // A reasoning-only Responses payload must not be mistaken for an answer.
        val reasoningOnly = JSONObject(
            """{"output":[{"type":"reasoning","content":[{"type":"reasoning_text","text":"hmm"}]}]}""",
        )
        assertEquals("", RelayCompletionText.extract(reasoningOnly, RelayProtocol.RESPONSES))
    }

    @Test
    fun `a numbers-only answer survives extraction intact`() {
        // Detection re-parses this text, so separators must not be rewritten.
        val numbers = (1..300).joinToString(", ")
        val body = JSONObject().put(
            "choices",
            org.json.JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", numbers)),
            ),
        )
        assertEquals(numbers, RelayCompletionText.extract(body, RelayProtocol.CHAT_COMPLETIONS))
    }

    @Test
    fun `an explicit null reasoning field yields nothing, not the text null`() {
        // 很多中转对非思考模型的每一帧都带 "reasoning_content": null。optString 对显式
        // JSON null 返回四个字符 "null"，于是思考框被一行行 null 刷屏 —— 用户看到的就是
        // 「思考输出 null」。空缺、显式 null 和非字符串都必须得到空串。
        val explicitNull = JSONObject(
            """{"choices":[{"delta":{"content":"","reasoning_content":null}}]}""",
        )
        assertEquals("", RelayCompletionText.extractStreamReasoning(explicitNull.toString(), RelayProtocol.CHAT_COMPLETIONS))

        val nullReasoning = JSONObject(
            """{"choices":[{"delta":{"content":"","reasoning":null}}]}""",
        )
        assertEquals("", RelayCompletionText.extractStreamReasoning(nullReasoning.toString(), RelayProtocol.CHAT_COMPLETIONS))

        val missing = JSONObject("""{"choices":[{"delta":{"content":""}}]}""")
        assertEquals("", RelayCompletionText.extractStreamReasoning(missing.toString(), RelayProtocol.CHAT_COMPLETIONS))
    }

    @Test
    fun `reasoning is read from both field spellings`() {
        val deepseek = JSONObject(
            """{"choices":[{"delta":{"reasoning_content":"第一段思考。"}}]}""",
        )
        assertEquals("第一段思考。", RelayCompletionText.extractStreamReasoning(deepseek.toString(), RelayProtocol.CHAT_COMPLETIONS))

        val renamed = JSONObject(
            """{"choices":[{"delta":{"reasoning":"第二段思考。"}}]}""",
        )
        assertEquals("第二段思考。", RelayCompletionText.extractStreamReasoning(renamed.toString(), RelayProtocol.CHAT_COMPLETIONS))
    }

    @Test
    fun `think blocks are stripped from a whole answer`() {
        // 没有 reasoning 字段的部署把思考包在 <think> 标签里放进 content。整段提取时
        // 这些块必须消失（缓冲路径没有思考通道可去），否则思考文本会被当答案计分。
        assertEquals(
            "正文",
            RelayCompletionText.stripThinkBlocks("<think>想了一会。</think>正文"),
        )
        assertEquals(
            "前后",
            RelayCompletionText.stripThinkBlocks("前<think>秘密</think>后"),
        )
        // 流被掐断时 </think> 可能永远不来：未闭合的尾巴整段去掉。
        assertEquals("开头", RelayCompletionText.stripThinkBlocks("开头<think>没写完"))
        // 没有 think 块的回答原样返回。
        assertEquals("<html></html>", RelayCompletionText.stripThinkBlocks("<html></html>"))
    }

    @Test
    fun `a chat content carrying think blocks extracts the text outside them`() {
        val body = JSONObject(
            """{"choices":[{"message":{"content":"<think>想了很久。</think><html><body>x</body></html>"}}]}""",
        )
        assertEquals(
            "<html><body>x</body></html>",
            RelayCompletionText.extract(body, RelayProtocol.CHAT_COMPLETIONS),
        )
    }
}
