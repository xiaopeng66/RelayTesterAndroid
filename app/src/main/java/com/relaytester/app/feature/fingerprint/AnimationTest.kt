package com.relaytester.app.feature.fingerprint

import androidx.compose.runtime.Immutable
import com.relaytester.app.core.model.isTransient

/**
 * The animation panel's own state machine, kept apart from the three-question round.
 *
 * Nothing here feeds the fingerprint score, the answer cache, the retry list or the
 * detection history: an animation request is a separate feature that happens to be
 * launched from a model card, and a failed or ugly animation must never move a
 * detection result.
 */
/**
 * The animation test card's state: whether it is on screen, and every test it holds.
 *
 * A list rather than one session, because the card is now opened from the 模型测试 header
 * and a generation keeps running after the card is closed — the entry button has to be
 * able to say "something is still generating" and reopening must find that test again.
 * Closing the card therefore only clears [panelOpen]; it never drops a session.
 */
@Immutable
data class AnimationUiState(
    /** Whether the card is on screen. Closing it does not cancel or discard anything. */
    val panelOpen: Boolean = false,
    /**
     * The supplier the card's next test will use.
     *
     * Held apart from the detection panel's own selection on purpose: the two screens are
     * unrelated, and sharing one field meant choosing a supplier here silently reset the
     * other panel's ticked models and cleared the results it was showing.
     */
    val supplierId: String? = null,
    /** The model the card's next test will use, from the selected supplier's catalogue. */
    val model: String? = null,
    /**
     * The selected supplier's catalogue, for the card's model picker.
     *
     * Published as state rather than read on demand: a getter would answer correctly but
     * never notify Compose, so the picker would keep showing the previous supplier's list.
     */
    val models: List<String> = emptyList(),
    /**
     * The models ticked for a batch test, from the selected supplier's catalogue.
     *
     * 批量测试的选择状态。只允许同供应商的模型一起测（换供应商即清空），列表顺序就是
     * 目录顺序——批量启动后进入的是其中排序第一的模型的界面，其余的会话都在列表里跑。
     */
    val selectedModels: List<String> = emptyList(),
    /** The session the card is showing in detail; null means the list view. */
    val selectedSessionId: Long? = null,
    /** Every test, newest first. */
    val sessions: List<AnimationSession> = emptyList(),
) {
    /** The session the detail view is showing, if any. */
    val selected: AnimationSession? get() = sessions.firstOrNull { it.sessionId == selectedSessionId }

    /**
     * The first session currently generating, if any.
     *
     * Several can generate at once since 批量测试 (and a second test from another supplier
     * while one runs): the request-id ownership check is per session, so parallel requests
     * never write into each other. The entry button's "in progress" mark only needs to know
     * whether *something* is still running, so first-of is enough for it.
     */
    val generating: AnimationSession? get() = sessions.firstOrNull { it.phase == AnimationPhase.GENERATING }

    val isOpen: Boolean get() = panelOpen

    /**
     * The selected session, spelled the way the single-session card used to.
     *
     * Kept so the existing state-machine tests (and any reader of the old shape) keep
     * asking the same question — "what is the card showing" — without having to know that
     * the card now holds a list. New code should say [selected].
     */
    val session: AnimationSession? get() = selected
}

/**
 * One open panel and the request it owns.
 *
 * [sessionId] identifies the panel; [requestId] identifies the request. Both are checked
 * before any response is written, because cancelling the job is not enough on its own: a
 * late callback from a replaced request would otherwise repaint the new panel with the
 * old prompt's output.
 */
@Immutable
data class AnimationSession(
    val sessionId: Long,
    /**
     * When this test was created, for the list's recency order and its timestamp.
     *
     * Injected rather than read from the clock here so a test can pin the order.
     */
    val createdAt: Long = 0L,
    /**
     * The card this panel was opened from, resolved when it opened.
     *
     * Captured rather than read live: the panel must keep talking about the supplier and
     * model it was opened for even if the surrounding selection changes underneath it.
     */
    val supplierId: String,
    val supplierName: String,
    val model: String,
    /** The editable prompt. Survives close/reopen and rotation; never persisted to disk. */
    val draftPrompt: String,
    val phase: AnimationPhase = AnimationPhase.EDITING,
    /** The prompt actually sent for the request in flight, or the last one that ran. */
    val submittedPrompt: String? = null,
    /** Identifies the in-flight request; null when nothing is running. */
    val requestId: Long? = null,
    /** The complete HTML of the last successful generation, if any. */
    val html: String? = null,
    /**
     * The document the request in flight has written so far.
     *
     * Kept apart from [html] so a half-finished answer can never be mistaken for a result:
     * [html] is only ever set from a completed, parsed document. The panel shows this as
     * the generation happening; the finished animation replaces it, and a failure discards
     * it. Null when nothing is in flight.
     */
    val streamingText: String? = null,
    /**
     * How many answer characters have arrived, across the whole answer.
     *
     * [streamingText] is only the tail, so its length understates a finished document
     * badly — a 12 000-character answer displays as "已收到 4 000 字符". This is the real
     * count, reported by the network layer's accumulated callback.
     */
    val receivedChars: Int = 0,
    /**
     * The model's reasoning while it works, tail-truncated like [streamingText].
     *
     * A reasoning model can spend its whole token budget before writing one character of
     * HTML, and those frames are not the answer — they never enter [html]. Dropping them
     * entirely left the panel on 等待响应… for the whole chain of thought, which looks
     * exactly like a hung request. This is what makes the wait legible.
     */
    val reasoningText: String? = null,
    /**
     * How many reasoning characters have arrived, across the whole chain of thought.
     *
     * The same reason [receivedChars] exists: the visible text is a tail, so its length
     * cannot answer "how much has it thought so far".
     */
    val reasoningChars: Int = 0,
    /**
     * Whether the reader has scrolled back from the newest line and is reading older text.
     *
     * While this is set, the displayed tail must not slide. The window is otherwise trimmed
     * with `takeLast`, and trimming *moves the text under a fixed scroll offset*: holding the
     * offset while characters fall off the front silently replaces what the reader is looking
     * at with whatever has since been appended — measured on the device as a box that stayed
     * at the same pixel position while its content advanced about fifty lines. Keeping the
     * window's start fixed makes appends add to the end only, so the line being read stays
     * put. The panel owns this flag because only it knows where the reader scrolled to.
     */
    val reasoningHeld: Boolean = false,
    /**
     * When the request in flight was sent, for the elapsed-time readout.
     *
     * A generation takes tens of seconds, and the difference between "20 秒" and "4 分钟"
     * is exactly what tells the user whether to keep waiting. It is wall-clock rather than
     * monotonic because it is compared against the ViewModel's injected [clock], which
     * tests pin.
     */
    val startedAt: Long? = null,
    /**
     * When the in-flight request settled, so the elapsed readout can freeze.
     *
     * Set as the request ends; without it the number would keep counting up after the
     * answer arrived or the request failed.
     */
    val settledAt: Long? = null,
    /** The prompt the stored [html] belongs to, so a later draft cannot claim it. */
    val htmlPrompt: String? = null,
    /**
     * Which attempt is in flight, 1-based, for a generation that is retried automatically.
     *
     * A dropped connection mid-thought used to end the generation on the spot, which is what
     * "思考一半就显示网络连接失败，没有自动重试吗" was. Transient failures are now re-sent, and
     * this is what the panel shows so a restart is visible rather than looking like the same
     * attempt going backwards to zero characters.
     */
    val attempt: Int = 1,
    /**
     * Why the previous attempt was abandoned, while a retry runs. Null on a first attempt.
     *
     * Kept apart from [error] on purpose: [error] means "this generation is over and here is
     * why", and the panel paints it red. This one accompanies a generation that is still in
     * flight, so showing it as an error would announce a failure that has not happened.
     */
    val retryNotice: String? = null,
    val error: String? = null,
    /** Which view the result area is showing once there is HTML to show. */
    val view: AnimationView = AnimationView.PREVIEW,
    /**
     * Bumped to ask the preview to reload the same HTML.
     *
     * "重新播放" must not call the model: it only re-runs what is already in [html], and a
     * counter is how that reload is requested without touching the request path.
     */
    val replayToken: Long = 0L,
    /**
     * A PNG of the rendered animation, captured once the result is on screen.
     *
     * The list row shows this instead of a "done" label, so a finished test is recognisable by
     * its own picture rather than by a word that says nothing about what it made. Captured by
     * the detail view, which is the only place a live WebView exists: a row has no way to
     * render the document itself. Null until then, and cleared when a new generation starts —
     * a stale frame under a fresh result would claim the new run produced the old picture.
     *
     * Held as encoded PNG bytes rather than a Bitmap: the sessions outlive the detail view and
     * a decoded bitmap per record would hold a full frame of pixels in memory for as long as
     * the panel is open.
     */
    val snapshot: ByteArray? = null,
) {
    // ByteArray makes the generated equals/hashCode identity-based, which would break the
    // state-diffing the row relies on to notice a new snapshot. Compare by content instead.
    override fun equals(other: Any?): Boolean = this === other ||
        (
            other is AnimationSession &&
                sessionId == other.sessionId &&
                createdAt == other.createdAt &&
                supplierId == other.supplierId &&
                supplierName == other.supplierName &&
                model == other.model &&
                draftPrompt == other.draftPrompt &&
                phase == other.phase &&
                submittedPrompt == other.submittedPrompt &&
                requestId == other.requestId &&
                html == other.html &&
                streamingText == other.streamingText &&
                receivedChars == other.receivedChars &&
                reasoningText == other.reasoningText &&
                reasoningChars == other.reasoningChars &&
                reasoningHeld == other.reasoningHeld &&
                startedAt == other.startedAt &&
                settledAt == other.settledAt &&
                attempt == other.attempt &&
                retryNotice == other.retryNotice &&
                error == other.error &&
                view == other.view &&
                replayToken == other.replayToken &&
                (snapshot?.contentEquals(other.snapshot ?: ByteArray(0)) ?: (other.snapshot == null))
            )

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + (snapshot?.contentHashCode() ?: 0)
        return result
    }
}

/** Where a session is in its own lifecycle. */
enum class AnimationPhase {
    /** The prompt can be edited; nothing has been sent. */
    EDITING,

    /** A request is in flight. Submitting again is refused. */
    GENERATING,

    /** A complete document was received; the preview is showing it. */
    READY,

    /** The request failed, timed out, was cancelled, or the answer was unusable. */
    FAILED,
}

/** Which of the two result views the panel shows. */
enum class AnimationView { PREVIEW, SOURCE }

/** Why an animation request produced no usable document. */
enum class AnimationFailure {
    /** A credential or endpoint could not be resolved for the card's supplier. */
    CONTEXT,

    /** The supplier, model or credentials could not be resolved. */
    CREDENTIALS,

    /** The upstream answered with a failure or an unfinished protocol terminal. */
    UPSTREAM,

    /** The document came back empty, not HTML, or without a complete page. */
    NOT_A_DOCUMENT,

    /** The answer hit the output ceiling, so the document is incomplete. */
    TRUNCATED,

    /** The document was received but the preview could not render it. */
    RENDER,
}

/**
 * The default prompt, in the user's own words.
 *
 * Deliberately short. Earlier revisions enumerated seven requirements — no fences, no prose,
 * start at `<!DOCTYPE html>`, end at `</html>` — and every one of those instructions existed
 * to protect the *parser*, not the animation. The parser no longer needs the protection:
 * [selectAnimationDocument] pulls the outermost complete document out of an answer no matter
 * how the model wraps it (bare, fenced, or sandwiched in explanations, which is what the real
 * supplier actually sends), and [parseAnimationDocument] still refuses anything without a
 * complete document. So the prompt says what is wanted and leaves the shape of the reply to
 * the model.
 *
 * Sent as the user prompt with nothing appended: the animation test exists to observe what
 * the model does on its own, so no scoring format or answer constraint is stapled on.
 */
const val DEFAULT_ANIMATION_PROMPT: String =
    "创建一个HTML，内容是SVG绘制一个鹈鹕骑自行车的2D动画，你不需要任何测试。"

/**
 * Ceiling for one animation answer, in tokens.
 *
 * Raised from 16 384 and deliberately left large: the request no longer asks the upstream to
 * skip its reasoning phase (see [DEFAULT_ANIMATION_PROMPT]'s call site), so the budget has to
 * cover the thinking *and* the document. Measured on the supplier this was diagnosed
 * against, a free-running request spends tens of thousands of characters of reasoning before
 * writing any HTML — `deepseek-v4.1-flash` reached 33 079 characters of reasoning in 300 s
 * without writing a character of the document, and `MiniMax-M2.7-highspeed` 44 145. A budget
 * that only covers the document is spent entirely on thinking and the answer never starts, so
 * the ceiling is set at the largest value the supplier accepted (65 536) rather than at a
 * guess of how long a document should be.
 */
const val ANIMATION_MAX_TOKENS = 65_536

/**
 * How many animation records the panel keeps.
 *
 * A record retains its whole document (up to [ANIMATION_MAX_HTML_CHARS]), the tail of its
 * reasoning and a PNG of the result, and nothing removed one except the user's own delete — so
 * a long session of generating grew the process without limit. The list is a way back to recent
 * work, not an archive; the same reasoning already bounds the detection history at 50 entries.
 * The newest are kept and the oldest fall off, except a record the user has open.
 */
const val ANIMATION_MAX_SESSIONS = 20

/**
 * How long one animation generation may take before it is failed as a timeout.
 *
 * Raised 180 → 300 → 600 s, each time against a measurement rather than a guess. At 180 s a
 * thinking model was cut off while still reasoning; at 300 s it was cut off by the *upstream*
 * instead (the diagnosed supplier closes the connection at 300.3 s of its own accord, which
 * this value cannot extend). 600 s is for the relays that impose no cut of their own: with
 * thinking left on there is no shorter honest bound, because the whole point of this test is
 * to watch what the model does when nothing is holding it back. The client's own cap is this
 * value plus [RelayApi]'s grace.
 */
const val ANIMATION_TIMEOUT_SECONDS = 600

/**
 * The largest document the preview will load, so a runaway answer cannot exhaust memory.
 *
 * Kept equal to [RelayApi]'s answer ceiling on purpose: if the network layer stopped
 * accumulating text before this, a large document would arrive cut short and then be rejected
 * as "missing its ending" — the app blaming the model for its own truncation.
 */
const val ANIMATION_MAX_HTML_CHARS = 2_000_000

/**
 * How much of the in-flight answer the panel keeps for display.
 *
 * The generation view shows the tail of the document being written, not the whole thing: a
 * full answer is up to [ANIMATION_MAX_HTML_CHARS] characters, and re-copying all of it into
 * the UI state on every streamed delta would cost far more than the few lines anyone reads.
 * The tail is what is actually being written at the moment, which is the point of showing it.
 */
internal const val ANIMATION_STREAM_TAIL_CHARS = 4_000

/**
 * How many times a transient failure is retried before the generation is reported as failed.
 *
 * A generation is expensive — up to [ANIMATION_TIMEOUT_SECONDS] and a full token budget — so
 * the budget is deliberately small. Three retries (four attempts) cover the shapes this test
 * actually meets: the dropped link, the 5xx, and — the one that motivated the third retry —
 * the thinking model whose upstream cuts the connection a few minutes into the chain of
 * thought, which burns one attempt each time it happens. Only failures where nothing usable
 * exists are retried: a transient error before any text, or an answer that never became a
 * complete document ([startAnimationAttempt] decides which); a finished document is never
 * re-requested.
 */
internal const val ANIMATION_MAX_RETRIES = 3

/**
 * How long to wait before a retry, growing with each attempt.
 *
 * Not zero: the failures this retries are a dropped link or a rate limit, and re-sending
 * immediately is the one thing that reliably makes both worse. Each attempt waits one base
 * delay more than the last (attempt 2 waits [ANIMATION_RETRY_DELAY_MS], attempt 3 twice
 * that, attempt 4 three times), so a supplier that is briefly busy gets a moment rather
 * than a burst of requests inside a second.
 */
internal const val ANIMATION_RETRY_DELAY_MS = 2_000L

/**
 * The largest prompt the animation test will send.
 *
 * Same discipline as the other user-supplied identifiers in this app (a model name and a
 * source id are each capped at 256 characters with a visible error): a prompt is typed or
 * pasted by hand, so anything past this is an accident — a stray select-all paste, or a whole
 * document dropped into the field — rather than a longer question. Sending it would spend the
 * full token budget on it, keep a copy in every session record, and push the request body
 * toward the relay's own limits, where the failure then reads as the model's fault. The value
 * is deliberately generous: the built-in prompt is a few hundred characters.
 */
const val ANIMATION_MAX_PROMPT_CHARS = 16_384

/**
 * Rough characters per token, used only to explain a document that is missing its ending.
 *
 * The completion path hands back text without a stop reason, so the only available signal
 * is that the answer filled the ceiling. This is a *hint*, never a rejection: a document
 * that reaches `</html>` is accepted however long it is, and the estimate only decides
 * whether an unfinished one is reported as "cut off at the limit" or as "not a document".
 * Judging by length alone rejected complete SVG pages, which are legitimately long.
 */
internal const val TRUNCATION_CHARS_PER_TOKEN = 3

/**
 * The isolated base URL the preview document is loaded under.
 *
 * A fixed, credential-free origin that belongs to no supplier: `loadDataWithBaseURL` needs
 * a base, and passing the supplier's own URL — or `file://` — would hand the untrusted page
 * a real origin to issue requests against or to read local files through.
 */
const val ANIMATION_PREVIEW_BASE_URL = "https://animation.invalid/"

/**
 * Facts about one finish, so the panel can tell "the model stopped" from "we cut it off".
 */
@Immutable
data class AnimationOutcome(
    val truncated: Boolean = false,
)

/**
 * One decoded animation answer: the HTML to preview.
 *
 * Splitting this from the state update keeps the decision testable without a ViewModel:
 * the extraction rules (fence stripping, structure checks) are the part worth pinning
 * down, and they are pure functions over the model's raw text. There is no `truncated`
 * flag here because a successful parse *is* a complete document — the ending tag is what
 * the structure check requires, so carrying the caller's length hint any further would
 * only restate a guess that the document itself has already settled.
 */
@Immutable
data class AnimationDocument(
    val html: String,
)

/**
 * Turns a model answer into the document to preview, or the reason it cannot be used.
 *
 * Only the outermost Markdown fence that covers the whole answer is removed; the original
 * text is what the source view shows. The structure checks are deliberately shallow — they
 * exist to give a friendly error, not to judge quality or to stand in for the sandbox.
 *
 * The feature is not tied to the pelican prompt: any answer that contains one complete HTML
 * document is previewable, whether the page draws with SVG, canvas, CSS or plain markup —
 * the user can point the test at any "generate an HTML page" prompt. What the checks still
 * refuse is a fragment: something that does not start as a document or never reaches
 * `</html>` is a piece of a page, and showing it as if it were the whole one was the thing
 * the plan originally forbade.
 */
fun parseAnimationDocument(
    raw: String,
    truncated: Boolean,
): Result<AnimationDocument> {
    val text = selectAnimationDocument(raw)
    if (text.isEmpty()) {
        return Result.failure(AnimationUnusable(AnimationFailure.NOT_A_DOCUMENT, "模型返回了空内容"))
    }
    if (text.length > ANIMATION_MAX_HTML_CHARS) {
        return Result.failure(
            AnimationUnusable(AnimationFailure.NOT_A_DOCUMENT, "返回内容过大，无法预览"),
        )
    }
    val lower = text.lowercase()
    // The document must *be* the answer, not a fragment quoted inside prose: it starts at
    // <!DOCTYPE html>/<html> and ends at </html>. Slicing a document out of an explanation
    // would present a piece as if it were the whole page, which the plan forbids.
    val startsDocument = lower.startsWith("<!doctype html") || lower.startsWith("<html")
    if (!startsDocument) {
        return Result.failure(
            AnimationUnusable(AnimationFailure.NOT_A_DOCUMENT, "返回内容不是完整的 HTML 文档"),
        )
    }
    // The closing tag decides, not the caller's length hint. [truncated] is only a proxy —
    // the completion path has no stop reason, so it guesses from the answer filling the
    // token ceiling — and a document that reaches `</html>` is finished whatever its length:
    // an SVG page is legitimately long, and a verbose one that happens to cross the
    // threshold used to be thrown away with a reason ("被长度上限截断") that was simply
    // untrue. The hint is still worth keeping for the message when the tag really is
    // missing, because then it names the likely cause instead of only the symptom.
    if (!lower.endsWith("</html>")) {
        return Result.failure(
            if (truncated) {
                AnimationUnusable(AnimationFailure.TRUNCATED, "回答被长度上限截断，页面不完整")
            } else {
                AnimationUnusable(
                    AnimationFailure.NOT_A_DOCUMENT,
                    "HTML 文档没有正常结束，可能是截断的",
                )
            },
        )
    }
    return Result.success(AnimationDocument(text))
}

/** An unusable answer, carrying both the machine-readable reason and the panel's wording. */
class AnimationUnusable(
    val kind: AnimationFailure,
    override val message: String,
) : Exception(message)

/**
 * Removes a fence only when it wraps the entire answer.
 *
 * The prompt asks for no fence, but a model that adds one is otherwise fine, and refusing
 * it would fail a perfectly usable page. A fence in the middle is left alone: that is
 * prose around the document, and the structure check is what rejects it.
 */
internal fun stripOuterFence(raw: String): String {
    val text = raw.trim()
    if (!text.startsWith("```")) return text
    val firstNewline = text.indexOf('\n')
    if (firstNewline < 0) return text
    val opening = text.substring(0, firstNewline).trim()
    // Only a bare fence or a fence tagged as markup counts as the wrapper.
    val tag = opening.removePrefix("```").trim().lowercase()
    if (tag.isNotEmpty() && tag !in setOf("html", "svg", "xml")) return text
    val body = text.substring(firstNewline + 1)
    val closing = body.lastIndexOf("```")
    if (closing < 0) return text
    // Nothing but whitespace may follow the closing fence.
    if (body.substring(closing + 3).isNotBlank()) return text
    // The newline that separated the fence from the document belongs to the fence, not to
    // the page: trimming here keeps the returned document byte-identical to the unfenced
    // answer, which is what a caller comparing the two expects.
    return body.substring(0, closing).trim()
}

/**
 * The document inside an answer, when the answer wraps one in prose.
 *
 * The prompt asks for the document and nothing else, and a model that obeys still passes
 * through [stripOuterFence]'s path untouched. But this was measured on the real supplier:
 * `deepseek-ai/deepseek-v4.1-flash` answered with one line of preamble, ```` ```html ````,
 * the complete page, ```` ``` ````, and then a closing explanation — and the app reported
 * "返回内容不是完整的 HTML 文档" for an answer that *does* contain a valid document. The
 * user saw a hard failure for a page any other client would have rendered.
 *
 * Why extracting is safe here, given the plan's rule against slicing fragments out of
 * explanations: that rule exists so half a page is never shown as if it were the whole one.
 * This function therefore requires a *complete* document — a `<!DOCTYPE html>`/`<html>`
 * opening **and** a matching `</html>` — before returning anything, and it returns only the
 * span between them. Prose that quotes an opening tag with no closing one is still refused,
 * which is the case the rule was written for. The extracted slice is checked by the caller's
 * structure rules like any other answer, and the source view keeps showing the full reply.
 */
internal fun selectAnimationDocument(raw: String): String {
    // The common case first: an answer that is the document, or a fence around it.
    val fenced = stripOuterFence(raw).trim()
    if (fenced.isCompleteDocument()) return fenced

    // Wrapped in prose: take the outermost complete document only when the prose does not
    // itself look like a document (otherwise the whole answer is the document, and the
    // caller's own messages should decide why it is unusable).
    val lower = raw.lowercase()
    val start = minOf(
        lower.indexOf("<!doctype html").takeIf { it >= 0 } ?: Int.MAX_VALUE,
        lower.indexOf("<html").takeIf { it >= 0 } ?: Int.MAX_VALUE,
    )
    if (start == Int.MAX_VALUE) return fenced
    val end = lower.indexOf("</html>", start)
    if (end < 0) return fenced
    val slice = raw.substring(start, end + "</html>".length).trim()
    return if (slice.isCompleteDocument()) slice else fenced
}

/** Whether this text opens and closes as a whole HTML document. */
private fun String.isCompleteDocument(): Boolean {
    val lower = lowercase()
    val starts = lower.startsWith("<!doctype html") || lower.startsWith("<html")
    return starts && lower.endsWith("</html>")
}
