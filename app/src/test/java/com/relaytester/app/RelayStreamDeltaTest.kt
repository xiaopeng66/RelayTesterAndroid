package com.relaytester.app

import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.network.RelayCompletionText
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The streamed-text extraction, one protocol at a time.
 *
 * A streamed event is not a whole response, so the buffered extractor cannot read it:
 * Chat puts text under `choices[0].delta`, Responses under `response.output_text.delta`,
 * and Anthropic under `content_block_delta` with a `text_delta` payload. Each protocol
 * also emits events that carry no answer text at all, and counting those would inflate
 * the panel's live integer count — the number the user watches while a challenge runs.
 */
class RelayStreamDeltaTest {
    private fun chatDelta(content: String): String = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", content))))
        .toString()

    private fun responsesDelta(text: String): String = JSONObject()
        .put("type", "response.output_text.delta")
        .put("delta", text)
        .toString()

    private fun anthropicDelta(text: String): String = JSONObject()
        .put("type", "content_block_delta")
        .put("delta", JSONObject().put("type", "text_delta").put("text", text))
        .toString()

    // ---- Chat Completions --------------------------------------------------

    @Test
    fun `chat reads the delta content`() {
        assertEquals(
            "12 34 ",
            RelayCompletionText.extractStreamDelta(chatDelta("12 34 "), RelayProtocol.CHAT_COMPLETIONS),
        )
    }

    @Test
    fun `a chat role preamble carries no text`() {
        // The first event of a Chat stream announces the role with a null content; a
        // parser that assumed `content` exists would throw and lose the whole answer.
        val preamble = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("role", "assistant"))))
            .toString()

        assertEquals("", RelayCompletionText.extractStreamDelta(preamble, RelayProtocol.CHAT_COMPLETIONS))
    }

    @Test
    fun `a chat final event with no content is empty`() {
        val final = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")))
            .toString()

        assertEquals("", RelayCompletionText.extractStreamDelta(final, RelayProtocol.CHAT_COMPLETIONS))
    }

    @Test
    fun `chat reads a multimodal content-block delta`() {
        // Some relays stream the same block array their buffered replies use; the text
        // still lives inside the blocks, so skipping the array would report nothing.
        val delta = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "delta",
                        JSONObject().put(
                            "content",
                            JSONArray().put(JSONObject().put("type", "text").put("text", "7 8")),
                        ),
                    ),
                ),
            )
            .toString()

        assertEquals("7 8", RelayCompletionText.extractStreamDelta(delta, RelayProtocol.CHAT_COMPLETIONS))
    }

    // ---- Responses ---------------------------------------------------------

    @Test
    fun `responses reads the text delta event`() {
        assertEquals(
            "5 6 ",
            RelayCompletionText.extractStreamDelta(responsesDelta("5 6 "), RelayProtocol.RESPONSES),
        )
    }

    @Test
    fun `responses ignores the completed-item echo`() {
        // `response.output_item.done` repeats the text that was already streamed. Counting
        // it as well would double every number in the panel's live count.
        val done = JSONObject()
            .put("type", "response.output_item.done")
            .put("delta", "1 2 3 4 5")
            .toString()

        assertEquals("", RelayCompletionText.extractStreamDelta(done, RelayProtocol.RESPONSES))
    }

    @Test
    fun `responses ignores lifecycle events`() {
        listOf("response.created", "response.in_progress", "response.completed").forEach { type ->
            val event = JSONObject().put("type", type).put("delta", "999").toString()
            assertEquals("$type 不应产生文本", "", RelayCompletionText.extractStreamDelta(event, RelayProtocol.RESPONSES))
        }
    }

    // ---- Anthropic ---------------------------------------------------------

    @Test
    fun `anthropic reads the text delta`() {
        assertEquals(
            "9 10 ",
            RelayCompletionText.extractStreamDelta(anthropicDelta("9 10 "), RelayProtocol.ANTHROPIC),
        )
    }

    @Test
    fun `anthropic ignores a thinking delta`() {
        // Extended-thinking models stream their reasoning through the same event name.
        // Counting it would report integers the user never sees and that are not part of
        // the answer being scored.
        val thinking = JSONObject()
            .put("type", "content_block_delta")
            .put("delta", JSONObject().put("type", "thinking_delta").put("thinking", "12 34 56 78"))
            .toString()

        assertEquals("", RelayCompletionText.extractStreamDelta(thinking, RelayProtocol.ANTHROPIC))
    }

    @Test
    fun `anthropic ignores its block markers`() {
        listOf("message_start", "content_block_start", "message_delta", "message_stop").forEach { type ->
            val event = JSONObject().put("type", type).toString()
            assertEquals("$type 不应产生文本", "", RelayCompletionText.extractStreamDelta(event, RelayProtocol.ANTHROPIC))
        }
    }

    @Test
    fun `anthropic ignores an input-json delta`() {
        // Tool-use blocks stream their arguments through content_block_delta too, but as
        // `input_json_delta`; it is not answer text.
        val tool = JSONObject()
            .put("type", "content_block_delta")
            .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", "{\"a\":1}"))
            .toString()

        assertEquals("", RelayCompletionText.extractStreamDelta(tool, RelayProtocol.ANTHROPIC))
    }

    @Test
    fun `anthropic only accepts a text delta, not any delta carrying text`() {
        // The guard is a whitelist, and this is what makes it one: a future or
        // vendor-specific delta type that happens to carry a `text` field must still be
        // refused, because only `text_delta` is the model's answer. A blacklist ("skip
        // thinking, take the rest") would accept this event and mix unrelated text into
        // the scored answer.
        val unknown = JSONObject()
            .put("type", "content_block_delta")
            .put("delta", JSONObject().put("type", "future_annotation_delta").put("text", "1 2 3 4"))
            .toString()

        assertEquals(
            "白名单以外的 delta 类型一律不采信",
            "",
            RelayCompletionText.extractStreamDelta(unknown, RelayProtocol.ANTHROPIC),
        )
    }

    // ---- the buffered fallback --------------------------------------------

    @Test
    fun `a whole non-streamed body still yields its text`() {
        // The fallback path: a relay that ignored `stream: true` answers with one JSON
        // document, and the streaming call must return exactly what the buffered call
        // would have. This is what keeps live counting an enhancement rather than a
        // requirement on the upstream.
        val body = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("role", "assistant").put("content", "1 2 3"),
                    ),
                ),
            )
            .toString()

        assertEquals("1 2 3", RelayCompletionText.extractFromBody(body, RelayProtocol.CHAT_COMPLETIONS))
    }

    @Test
    fun `a body that is not JSON yields no text rather than throwing`() {
        assertEquals("", RelayCompletionText.extractFromBody("<html>gateway</html>", RelayProtocol.CHAT_COMPLETIONS))
        assertEquals("", RelayCompletionText.extractFromBody("", RelayProtocol.RESPONSES))
    }
}
