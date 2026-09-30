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
}
