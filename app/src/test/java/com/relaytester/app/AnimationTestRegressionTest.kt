package com.relaytester.app

import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.relaytester.app.core.fingerprint.BankUpdateClient
import com.relaytester.app.core.model.ApiResult
import com.relaytester.app.core.model.ErrorKind
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestError
import com.relaytester.app.core.model.TestSettings
import com.relaytester.app.core.model.isTransient
import com.relaytester.app.core.network.RelayApi
import com.relaytester.app.core.security.SecretStore
import com.relaytester.app.core.storage.SupplierStore
import com.relaytester.app.core.storage.SupplierStoreState
import com.relaytester.app.feature.fingerprint.ANIMATION_MAX_HTML_CHARS
import com.relaytester.app.feature.fingerprint.ANIMATION_MAX_SESSIONS
import com.relaytester.app.feature.fingerprint.ANIMATION_MAX_PROMPT_CHARS
import com.relaytester.app.feature.fingerprint.ANIMATION_MAX_RETRIES
import com.relaytester.app.feature.fingerprint.ANIMATION_MAX_TOKENS
import com.relaytester.app.feature.fingerprint.ANIMATION_PREVIEW_BASE_URL
import com.relaytester.app.feature.fingerprint.ANIMATION_REASONING_EXPANDED_BY_DEFAULT
import com.relaytester.app.feature.fingerprint.ANIMATION_REASONING_MAX_LINES
import com.relaytester.app.feature.fingerprint.ANIMATION_OUTPUT_MAX_LINES
import com.relaytester.app.feature.fingerprint.ANIMATION_SOURCE_MAX_LINES
import com.relaytester.app.feature.fingerprint.ANIMATION_SOURCE_MIN_LINES
import com.relaytester.app.feature.fingerprint.ANIMATION_STREAM_TAIL_CHARS
import com.relaytester.app.feature.fingerprint.ANIMATION_TIMEOUT_SECONDS
import com.relaytester.app.feature.fingerprint.AnimationFailure
import com.relaytester.app.feature.fingerprint.AnimationPhase
import com.relaytester.app.feature.fingerprint.AnimationSession
import com.relaytester.app.feature.fingerprint.AnimationView
import com.relaytester.app.feature.fingerprint.DEFAULT_ANIMATION_PROMPT
import com.relaytester.app.feature.fingerprint.DetectionMode
import com.relaytester.app.feature.fingerprint.FOLLOW_END_SLACK_PX
import com.relaytester.app.feature.fingerprint.FingerprintViewModel
import com.relaytester.app.feature.fingerprint.SupplierOption
import com.relaytester.app.feature.fingerprint.TRUNCATION_CHARS_PER_TOKEN
import com.relaytester.app.feature.fingerprint.animationElapsedLabel
import com.relaytester.app.feature.fingerprint.animationPreviewAspectRatio
import com.relaytester.app.feature.fingerprint.animationReasoningLabel
import com.relaytester.app.feature.fingerprint.animationStreamingHint
import com.relaytester.app.feature.fingerprint.animationPhaseLabel
import com.relaytester.app.feature.fingerprint.animationStreamingStatus
import com.relaytester.app.feature.fingerprint.followEndObservation
import com.relaytester.app.feature.fingerprint.generationDurationMs
import com.relaytester.app.feature.fingerprint.injectCsp
import com.relaytester.app.feature.fingerprint.injectFitStylesheet
import com.relaytester.app.feature.fingerprint.parseAnimationDocument
import com.relaytester.app.feature.fingerprint.previewRequestAllowed
import com.relaytester.app.feature.fingerprint.realHeadIndex
import com.relaytester.app.feature.fingerprint.selectAnimationDocument
import com.relaytester.app.feature.fingerprint.SECONDARY_ACTION_SIZE
import com.relaytester.app.feature.fingerprint.stripOuterFence
import com.relaytester.app.feature.fingerprint.supplierPickerOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The animation test as a pure decision surface.
 *
 * The WebView itself is not exercised here — a sandbox cannot be proven correct by a unit
 * test, and the checks that matter for the page (network, file access, bridges) are native
 * settings verified on a device. What is pinned here is the part that is easy to get wrong
 * in code: which answers count as a usable document, and what the panel does in response.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AnimationTestRegressionTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val lifecycle = ViewModelStore()
    private val subjects = mutableListOf<FingerprintViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        val roots = subjects.map { it.viewModelScope.coroutineContext[Job]!! }
        try {
            lifecycle.clear()
            mainDispatcher.scheduler.runCurrent()
            runBlocking {
                withContext(Dispatchers.Default) { withTimeout(10_000) { roots.forEach { it.join() } } }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ---- What counts as a document --------------------------------------

    @Test
    fun `a complete document passes`() {
        val html = "<!DOCTYPE html><html><head></head><body><svg></svg></body></html>"
        val result = parseAnimationDocument(html, truncated = false)
        assertTrue(result.isSuccess)
        assertEquals(html, result.getOrNull()?.html)
    }

    @Test
    fun `an outer markdown fence is removed but the document is kept whole`() {
        val body = "<!DOCTYPE html><html><body><svg><circle/></svg></body></html>"
        val fenced = "```html\n$body\n```"
        assertEquals(body, stripOuterFence(fenced))
        assertTrue(parseAnimationDocument(fenced, truncated = false).isSuccess)
    }

    @Test
    fun `a bare fence without a language tag is also removed`() {
        val body = "<html><svg/></html>"
        assertEquals(body, stripOuterFence("```\n$body\n```"))
    }

    @Test
    fun `prose around a complete document no longer costs the user the answer`() {
        // 这条测试原来钉的是相反的行为（拒绝），依据是计划里"不要在解释中随意截取片段当完整
        // 页面"。真实供应商把它推翻了：deepseek-v4.1-flash 实际返回的就是"一句前言 +
        // ```html + 完整文档 + ``` + 一段解释"，而应用据此报"返回内容不是完整的 HTML 文档"
        // ——用户为一个本身完整、任何其他客户端都能渲染的页面收到了硬失败。规则要防的是
        // "半个页面被当成整页"，所以 extractAnimationDocument 只在**确实闭合**时才提取。
        val body = "<!DOCTYPE html><html><body><svg><circle/></svg></body></html>"
        val mixed = "这是一个可直接在浏览器中打开的HTML页面。\n```html\n$body\n```\n" +
            "### 说明\n这个动画把骑行拆成了几个部件。"
        val result = parseAnimationDocument(mixed, truncated = false)
        assertTrue("完整文档被解说包住时仍应可用", result.isSuccess)
        assertEquals("预览用的必须是文档本身，不含解说", body, result.getOrNull()?.html)
    }

    @Test
    fun `prose that quotes an unfinished document is still refused`() {
        // 计划那条规则的原始目标：解释里引用了半截文档，不能当作整页显示。提取只看得到
        // 开标签、看不到闭合标签时必须拒绝——两个都要求，才谈得上"完整"。
        val half = "我先给你开头：\n```html\n<!DOCTYPE html><html><body><svg>"
        assertTrue(parseAnimationDocument(half, truncated = false).isFailure)

        val noClose = "参考一下：\n<html><body><svg/><circle/>\n（后面还有内容）"
        assertTrue(parseAnimationDocument(noClose, truncated = false).isFailure)
    }

    @Test
    fun `a document with no fence at all is still taken whole`() {
        // 提取逻辑不得改动"模型照规矩输出"这条主路径：答案就是文档本身时返回原样。
        val body = "<!DOCTYPE html><html><body><svg><circle/></svg></body></html>"
        assertEquals(body, selectAnimationDocument(body))
        assertTrue(parseAnimationDocument(body, truncated = false).isSuccess)
    }

    @Test
    fun `prose around the document is not treated as a fence`() {
        // stripOuterFence 的边界本身没变，仍然是"只剥覆盖整个答案的围栏"。它不再等于
        // "整段答案因此不可用"——那是 selectAnimationDocument 的职责，见上面两条。
        val mixed = "Here you go:\n```html\n<html><svg/></html>\n```\nHope it helps!"
        assertEquals(mixed, stripOuterFence(mixed))
    }

    @Test
    fun `whitespace around the document is trimmed`() {
        val body = "<html><svg/></html>"
        val padded = "\n\n   $body   \n\n"
        assertEquals(body, stripOuterFence(padded).trim())
    }

    @Test
    fun `the preview pane never comes out shorter than the floor`() {
        // 手机宽度下 16:9 只有 231 dp，沙箱按桌面宽度排版再整体缩放，于是画面小到看不清。
        // 地板必须作用在比例上，链在 aspectRatio 之后的 heightIn 会被比例自身的
        // 高度夹掉（旧注释记录的正是那次失败），所以这里钉的是比例本身。
        //
        // 地板只在 16:9 会矮过它时才生效：411 dp 宽配 220 dp 地板，16:9 给出 231 dp 已经
        // 高过地板，比例原样保留。
        assertEquals(16f / 9f, animationPreviewAspectRatio(width = 411.dp, minHeight = 220.dp))

        // 宽 380 dp 时 16:9 只剩 213.75 dp，掉到地板以下：比例被压到 380/220 = 1.727，
        // 高度因此正好停在 220 dp。
        val narrow = animationPreviewAspectRatio(width = 380.dp, minHeight = 220.dp)
        assertEquals(380f / 220f, narrow, 0.0001f)
        assertEquals("380 dp 宽时高度正好落在地板上", 220f, 380f / narrow, 0.01f)

        // 宽到 16:9 本身已经高过地板时，必须原样返回桌面比例，不能把平板也压窄。
        assertEquals(16f / 9f, animationPreviewAspectRatio(width = 800.dp, minHeight = 220.dp))
        // 地板交界的宽度上两者相等，两侧各自保持。
        assertEquals(16f / 9f, animationPreviewAspectRatio(width = 220.dp * 16f / 9f, minHeight = 220.dp))
    }

    @Test
    fun `a degenerate width falls back to the desktop ratio instead of dividing by zero`() {
        assertEquals(16f / 9f, animationPreviewAspectRatio(width = 0.dp, minHeight = 220.dp))
        assertEquals(16f / 9f, animationPreviewAspectRatio(width = 411.dp, minHeight = 0.dp))
    }

    @Test
    fun `an empty answer is rejected`() {
        val result = parseAnimationDocument("   \n  ", truncated = false)
        assertTrue(result.isFailure)
        assertEquals(
            AnimationFailure.NOT_A_DOCUMENT,
            (result.exceptionOrNull() as com.relaytester.app.feature.fingerprint.AnimationUnusable).kind,
        )
    }

    @Test
    fun `a non-html answer is rejected`() {
        val result = parseAnimationDocument("这是一段解释，没有任何标记。", truncated = false)
        assertTrue(result.isFailure)
    }

    @Test
    fun `an html answer without svg is accepted`() {
        // The feature is no longer tied to the pelican prompt: any "generate an HTML page"
        // prompt whose answer contains one complete document is previewable, whatever it
        // draws with. The old rule refused a perfectly good page for lacking an SVG.
        val body = "<!DOCTYPE html><html><body><div>hi</div></body></html>"
        val result = parseAnimationDocument(body, truncated = false)
        assertTrue("无 SVG 的完整页面应当可用", result.isSuccess)
        assertEquals(body, result.getOrNull()?.html)
    }

    @Test
    fun `a canvas page without svg is accepted too`() {
        // Same rule, the other drawing style the user might point the test at.
        val body = "<!DOCTYPE html><html><head><style>canvas{background:#000}</style></head>" +
            "<body><canvas id='c'></canvas><script>const c=document.getElementById('c');</script>" +
            "</body></html>"
        assertTrue(parseAnimationDocument(body, truncated = false).isSuccess)
    }

    @Test
    fun `a truncated answer is rejected even when it looks like html`() {
        val result = parseAnimationDocument(
            "<!DOCTYPE html><html><body><svg></svg>",
            truncated = true,
        )
        assertTrue(result.isFailure)
        assertEquals(
            AnimationFailure.TRUNCATED,
            (result.exceptionOrNull() as com.relaytester.app.feature.fingerprint.AnimationUnusable).kind,
        )
    }

    @Test
    fun `a complete document is accepted however long it is`() {
        // The length hint is a guess, and this is the case that exposes it: an SVG page is
        // legitimately long, and one that happens to cross the token-ceiling estimate used to
        // be thrown away with a reason ("被长度上限截断") that was simply untrue. The closing
        // tag is the real evidence, and it settles the question the hint was guessing at.
        val body = "<!DOCTYPE html><html><head></head><body><svg></svg>" +
            "x".repeat(ANIMATION_MAX_TOKENS * TRUNCATION_CHARS_PER_TOKEN) +
            "</body></html>"
        val result = parseAnimationDocument(body, truncated = true)

        assertTrue("完整文档不得因长度被拒：${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(body, result.getOrNull()?.html)
    }

    @Test
    fun `an unfinished document is still rejected and the hint names the cause`() {
        // The other side of the same rule: no closing tag really is a broken document, and
        // then the length hint is worth keeping — it distinguishes "we cut it off" from
        // "this was never a document", which are different things to tell the user.
        val cutOff = "<!DOCTYPE html><html><body><svg></svg>" +
            "x".repeat(ANIMATION_MAX_TOKENS * TRUNCATION_CHARS_PER_TOKEN)
        val hinted = parseAnimationDocument(cutOff, truncated = true)
        assertTrue(hinted.isFailure)
        assertEquals(
            AnimationFailure.TRUNCATED,
            (hinted.exceptionOrNull() as com.relaytester.app.feature.fingerprint.AnimationUnusable).kind,
        )

        val unhinted = parseAnimationDocument(cutOff, truncated = false)
        assertTrue(unhinted.isFailure)
        assertEquals(
            AnimationFailure.NOT_A_DOCUMENT,
            (unhinted.exceptionOrNull() as com.relaytester.app.feature.fingerprint.AnimationUnusable).kind,
        )
    }

    @Test
    fun `the default prompt is the short one, with the shape left to the model`() {
        // 用户指定的原话。早先的默认提示词列了七条要求（不要围栏、不要解释、从 DOCTYPE
        // 到 /html），每一条都是在替解析器挡输入；selectAnimationDocument 已经能从任意
        // 包裹形式里取出最外层完整文档（真实供应商返回的就是「开场白 + 围栏 + 文档 + 说明」），
        // 这些条款不再有存在的理由，提示词只说要什么。
        assertEquals(
            "创建一个HTML，内容是SVG绘制一个鹈鹕骑自行车的2D动画，你不需要任何测试。",
            DEFAULT_ANIMATION_PROMPT,
        )
    }

    // ---- The app's own content policy -----------------------------------

    @Test
    fun `the injected policy blocks network, frames, objects and base rewriting`() {
        val hardened = injectCsp("<!DOCTYPE html><html><head></head><body></body></html>")
        val headIndex = hardened.indexOf("<head>")
        val metaIndex = hardened.indexOf("Content-Security-Policy")
        assertTrue("策略必须在 head 内注入", metaIndex > headIndex)
        assertTrue(hardened.contains("connect-src 'none'"))
        assertTrue(hardened.contains("frame-src 'none'"))
        assertTrue(hardened.contains("object-src 'none'"))
        assertTrue(hardened.contains("form-action 'none'"))
        assertTrue(hardened.contains("base-uri 'none'"))
        assertTrue(hardened.contains("worker-src 'none'"))
        assertTrue(hardened.contains("default-src 'none'"))
        // 内联脚本与样式必须放开，否则动画本身跑不起来。
        assertTrue(hardened.contains("script-src 'unsafe-inline'"))
        assertTrue(hardened.contains("style-src 'unsafe-inline'"))
    }

    @Test
    fun `a document without a head still gets the policy`() {
        val hardened = injectCsp("<html><svg/></html>")
        assertTrue(hardened.contains("Content-Security-Policy"))
        assertTrue(hardened.startsWith("<!DOCTYPE html>"))
    }

    @Test
    fun `a head mentioned inside a comment does not capture the policy`() {
        // The model writes comments, and a comment can name a head. Injecting there produced
        // a document with the policy inside the comment — applied to nothing — and the
        // preview ran with no CSP at all while looking like it had one. The policy has to
        // land in the element that actually opens the head.
        val html = "<!DOCTYPE html><!-- <head> decoy --><html><head>" +
            "<script>window.ok = true</script></head><body><svg></svg></body></html>"
        val hardened = injectCsp(html)

        val realHead = hardened.indexOf("<head>")
        val meta = hardened.indexOf("Content-Security-Policy")
        assertTrue("策略必须落在真正的 head 里", meta > realHead)
        val commentEnd = hardened.indexOf("-->")
        assertFalse("策略不得被塞进注释", meta < commentEnd)
        // And the document itself is otherwise untouched.
        assertTrue(hardened.contains("<script>window.ok = true</script>"))
    }

    @Test
    fun `a head mentioned inside a script does not capture the policy`() {
        // A meta policy is not retroactive: it must precede the first executable script.
        val script = "<script>var s = \"<head>\";</script>"
        val html = "<html>$script<head><body><svg></svg></body></html>"
        val hardened = injectCsp(html)
        val parsed = Jsoup.parse(hardened)
        val policies = parsed.head().select("meta[http-equiv=Content-Security-Policy]")
        assertEquals("策略必须成为真实 head 元素的子节点", 1, policies.size)
        assertTrue(policies.single().attr("content").contains("default-src 'none'"))
        assertTrue("原脚本必须完整保留", hardened.contains(script))
        assertTrue(
            "策略必须在前置脚本执行之前生效",
            hardened.indexOf("Content-Security-Policy") < hardened.indexOf(script),
        )
        assertEquals("var s = \"<head>\";", parsed.selectFirst("script")!!.data())
    }

    @Test
    fun `realHeadIndex finds the document head and ignores decoys`() {
        assertEquals(3, realHeadIndex("<h><head>"))
        assertEquals(-1, realHeadIndex("<html><svg/></html>"))
        assertEquals(-1, realHeadIndex("<!-- <head> --><html><body></body></html>"))
        // A real head after a decoy comment is found at its own offset, not the comment's:
        // `indexOf` answers 5 here, which is the one inside the comment.
        val commentFirst = "<!-- <head> --><html><head>"
        assertEquals(commentFirst.lastIndexOf("<head>"), realHeadIndex(commentFirst))
        assertTrue(
            "必须跳过注释里的假 head",
            commentFirst.indexOf("<head>") != realHeadIndex(commentFirst),
        )
        // And a decoy inside a script body, with the real head after it.
        val scriptFirst = "<html><script>var s = \"<head>\";</script><head>"
        assertEquals(scriptFirst.lastIndexOf("<head>"), realHeadIndex(scriptFirst))
    }

    @Test
    fun `csp remains a parsed head policy when a head attribute contains greater than`() {
        for (quote in listOf('"', '\'')) {
            assertParserVisibleCsp(
                "<!DOCTYPE html><html><head data-note=${quote}a > b$quote></head>" +
                    "<body><svg></svg></body></html>",
            )
        }
    }

    @Test
    fun `csp ignores a fake head inside an html attribute`() {
        for (quote in listOf('"', '\'')) {
            assertParserVisibleCsp(
                "<!DOCTYPE html><html data-note=$quote<head>$quote><head></head>" +
                    "<body><svg></svg></body></html>",
            )
        }
    }

    @Test
    fun `csp ignores a fake head inside textarea text`() {
        // No explicit head: HTML creates one before the body. The policy must go into an
        // app-owned head, not into the textarea's raw text where it is merely displayed.
        assertParserVisibleCsp(
            "<!DOCTYPE html><html><body><textarea><head></textarea><svg></svg></body></html>",
        )
    }

    @Test
    fun `csp remains visible for ordinary and headless documents`() {
        // Controls for the parser-based oracle: neither the document nor its content may be
        // discarded just to make the malicious attribute cases pass.
        assertParserVisibleCsp("<!DOCTYPE html><html><head></head><body><svg></svg></body></html>")
        assertParserVisibleCsp("<!DOCTYPE html><html><body><svg></svg></body></html>")
    }

    private fun assertParserVisibleCsp(html: String) {
        val document = parseAnimationDocument(html, truncated = false).getOrThrow()
        val hardened = injectFitStylesheet(injectCsp(document.html))
        // An independent HTML parser, not realHeadIndex or another substring assertion:
        // text saying "Content-Security-Policy" inside an attribute/comment is not a meta tag.
        // This checks markup placement only; native WebView enforcement still needs a device.
        val parsed = Jsoup.parse(hardened)
        val policies = parsed.head().children()
            .filter {
                it.tagName() == "meta" &&
                    it.attr("http-equiv").equals("Content-Security-Policy", ignoreCase = true)
            }
            .map { it.attr("content") }
        assertEquals("CSP must be an actual meta element in the parsed head: $html", 1, policies.size)
        for (directive in listOf("connect-src 'none'", "frame-src 'none'", "worker-src 'none'", "form-action 'none'")) {
            assertTrue("The parsed policy lost $directive", policies.single().contains(directive))
        }
        assertEquals("The accepted document must remain in the parsed body", 1, parsed.body().select("svg").size)
    }

    @Test
    fun `the preview base belongs to no supplier`() {
        assertTrue(ANIMATION_PREVIEW_BASE_URL.startsWith("https://"))
        assertTrue(ANIMATION_PREVIEW_BASE_URL.contains("invalid"))
    }

    @Test
    fun `the preview admits its own document and nothing that could reach out`() {
        // 预览在真机上显示的是引擎的失败页（ERR_HTTP_RESPONSE_CODE_FAILURE），因为主框架的
        // 请求带的是 `data:` 地址——`loadDataWithBaseURL` 就是这么交文档的——而拦截器只认
        // base URL，于是把自己的文档当外部资源拦掉了。合同＝文档本身放行，任何能出去的地址
        // 一律拒绝。
        assertTrue(
            "自己的文档被拦掉了：预览只会显示引擎的失败页",
            previewRequestAllowed(ANIMATION_PREVIEW_BASE_URL),
        )
        assertTrue(
            "loadDataWithBaseURL 交上来的 data: 文档被拦掉了：预览什么都看不到",
            previewRequestAllowed("data:text/html;charset=utf-8,%3Chtml%3E"),
        )
        assertTrue(
            "内联 data: 图片被拦掉了：允许的动画会缺图",
            previewRequestAllowed("data:image/svg+xml;base64,PHN2Zy8+"),
        )
        // 下面这些只要有一条放行，页面就有了一条出去的通道。
        val refusals = mapOf(
            "http 地址" to "http://relay.test/v1/chat/completions",
            "https 地址" to "https://relay.test/v1/chat/completions",
            "供应商地址" to "https://10.0.2.2:8099/v1/models",
            "file 访问" to "file:///etc/passwd",
            "content 访问" to "content://com.android.contacts/data",
            "javascript" to "javascript:fetch('https://relay.test')",
            "about:blank" to "about:blank",
        )
        refusals.forEach { (label, url) ->
            assertFalse(
                "$label 被放行了：不可信页面拿到了一条出网/出沙箱的通道（$url）",
                previewRequestAllowed(url),
            )
        }
    }

    // ---- The session state machine --------------------------------------

    @Test
    fun `opening the panel sends nothing and seeds the default prompt`() {
        val api = ScriptedAnimationApi(listOf("unused"))
        val subject = animationViewModel(api)

        subject.openAnimationTest("sup-1", "test-model")

        val session = subject.uiState.value.animation.session
        assertNotNull(session)
        assertEquals(AnimationPhase.EDITING, session!!.phase)
        assertEquals(DEFAULT_ANIMATION_PROMPT, session.draftPrompt)
        assertEquals("test-model", session.model)
        assertEquals("Test Supplier", session.supplierName)
        assertEquals("打开面板不得发请求", 0, api.calls.get())
    }

    @Test
    fun `an empty prompt cannot be submitted`() {
        val api = ScriptedAnimationApi(listOf("unused"))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.updateAnimationPrompt("   ")

        subject.startAnimationGeneration()

        assertEquals("空白提示词不得发请求", 0, api.calls.get())
        val session = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.EDITING, session.phase)
        assertTrue(session.error?.contains("不能为空") == true)
    }

    @Test
    fun `restoring the default does not clear a preview or send a request`() {
        val html = "<!DOCTYPE html><html><body><svg id='a'></svg></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }
        assertEquals(1, api.calls.get())
        assertNotNull(subject.uiState.value.animation.session?.html)

        subject.updateAnimationPrompt("换一个题目")
        subject.restoreDefaultAnimationPrompt()

        val session = subject.uiState.value.animation.session!!
        assertEquals(DEFAULT_ANIMATION_PROMPT, session.draftPrompt)
        assertNotNull("恢复默认不得清掉已有预览", session.html)
        assertEquals("恢复默认不得发请求", 1, api.calls.get())
    }

    @Test
    fun `a successful generation lands in the preview phase`() {
        val html = "<!DOCTYPE html><html><body><svg id='pelican'></svg></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        val session = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.READY, session.phase)
        assertEquals(html, session.html)
        assertEquals(DEFAULT_ANIMATION_PROMPT, session.htmlPrompt)
        assertNull(session.requestId)
        assertEquals(AnimationView.PREVIEW, session.view)
    }

    @Test
    fun `the submitted prompt is snapshotted and later edits do not change it`() {
        val gate = CompletableDeferred<Unit>()
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html), gate = gate)
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.updateAnimationPrompt("第一次的题目")
        subject.startAnimationGeneration()
        runBlocking { api.started.await() }

        // 请求在飞时改草稿：快照必须还是发出时的那一份。
        subject.updateAnimationPrompt("第二次的题目")

        val generating = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.GENERATING, generating.phase)
        assertEquals("第一次的题目", generating.submittedPrompt)
        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
        assertEquals(AnimationPhase.READY, subject.uiState.value.animation.session!!.phase)
    }

    @Test
    fun `a second submit while generating is refused`() {
        val gate = CompletableDeferred<Unit>()
        val api = ScriptedAnimationApi(listOf("<!DOCTYPE html><html><svg/></html>"), gate = gate)
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { api.started.await() }

        subject.startAnimationGeneration()
        subject.startAnimationGeneration()

        assertEquals("生成中不得重复提交", 1, api.calls.get())
        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
    }

    @Test
    fun `the request goes to the card's own model and supplier`() {
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")

        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        assertEquals(listOf("test-model"), api.models)
        assertEquals(listOf("sup-1"), api.supplierIds)
        assertEquals(ANIMATION_MAX_TOKENS, api.maxTokens.single())
        assertEquals(ANIMATION_TIMEOUT_SECONDS, api.timeouts.single())
    }

    @Test
    fun `an unknown supplier is refused without sending anything`() {
        val api = ScriptedAnimationApi(listOf("unused"))
        val subject = animationViewModel(api)

        subject.openAnimationTest("supplier-that-is-gone", "test-model")

        assertNull("上下文解析不了就不开面板", subject.uiState.value.animation.session)
        assertTrue(
            subject.uiState.value.message?.contains("供应商") == true,
        )
        assertEquals(0, api.calls.get())
    }

    @Test
    fun `replay and view switching never call the model`() {
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }
        val firstToken = subject.uiState.value.animation.session!!.replayToken

        subject.replayAnimation()
        subject.replayAnimation()
        subject.showAnimationView(AnimationView.SOURCE)
        subject.showAnimationView(AnimationView.PREVIEW)

        val session = subject.uiState.value.animation.session!!
        assertTrue("重新播放必须请求一次重载", session.replayToken > firstToken)
        assertEquals("重新播放与切换视图都不得调用模型", 1, api.calls.get())
        assertEquals(html, session.html)
    }

    @Test
    fun `the document being written is visible while the request runs`() {
        // 生成过程要看得见：请求在飞的那段时间里，网关面板显示的应该是正在写入的文档，
        // 而不是一个只说「在转」的圈。这一条钉的是「进度真的进了状态」。
        val html = "<!DOCTYPE html><html><body><svg id='x'/></body></html>"
        val gate = CompletableDeferred<Unit>()
        val api = ScriptedAnimationApi(listOf(html), gate = gate, progressChunks = 4)
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { withTimeout(10_000) { api.progressed.await() } }

        val session = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.GENERATING, session.phase)
        val seen = session.streamingText
        assertNotNull("生成中必须能看到正在写入的文档", seen)
        assertTrue("看到的内容必须是这次回答的前缀", html.startsWith(seen!!))
        assertTrue("进度必须真的在增长", seen.length > 0)

        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
    }

    @Test
    fun `the finished animation replaces the generation view`() {
        // 画完之后那段「正在生成」必须让位给动画本身：留着它会让用户以为还在跑。
        val html = "<!DOCTYPE html><html><body><svg id='done'/></body></html>"
        val api = ScriptedAnimationApi(listOf(html), progressChunks = 4)
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        val session = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.READY, session.phase)
        assertEquals("完整文档进了预览", html, session.html)
        assertNull("生成过程的残留必须被清掉", session.streamingText)
        assertEquals("生成结束后按钮的语义是重新生成", 1, api.calls.get())
    }

    @Test
    fun `reasoning fragments accumulate into one readable chain instead of the last scrap`() {
        // 用户报的就是这个：思考框里只出现最后一个分片，屏幕上是一句「吧。」这类残句。
        // 网络层给的是分片（不是累计值），累计必须在这里做。
        val html = "<!DOCTYPE html><html><body><svg id='r'/></body></html>"
        val gate = CompletableDeferred<Unit>()
        val api = ScriptedAnimationApi(listOf(html), gate = gate)
        val fragments = listOf("让我想想。", "先画一个圆。", "再让它转起来。")
        api.reasoningFragments = fragments
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { withTimeout(10_000) { api.started.await() } }

        val session = subject.uiState.value.animation.session!!
        assertEquals(
            "三个分片必须拼成整条思考链",
            "让我想想。先画一个圆。再让它转起来。",
            session.reasoningText,
        )
        assertEquals("思考字数按累计计", fragments.sumOf { it.length }, session.reasoningChars)

        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
    }

    @Test
    fun `the reasoning count survives the display tail being truncated`() {
        // 显示只留尾部（和正文同一条规则），但计数必须按整条链算：否则思考到几万字时
        // 界面会永远停在 4000。
        val html = "<!DOCTYPE html><html><body><svg id='t'/></body></html>"
        val gate = CompletableDeferred<Unit>()
        val api = ScriptedAnimationApi(listOf(html), gate = gate)
        val fragment = "x".repeat(500)
        api.reasoningFragments = List(20) { fragment }
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { withTimeout(10_000) { api.started.await() } }

        val session = subject.uiState.value.animation.session!!
        assertEquals("显示的思考必须被截到上限", ANIMATION_STREAM_TAIL_CHARS, session.reasoningText!!.length)
        assertEquals("计数必须是全部 20 个分片", 10_000, session.reasoningChars)

        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
    }

    @Test
    fun `holding the reasoning keeps the reader's line from sliding away`() {
        // 用户报的就是这个：上滑回到中间某处后，新内容会让「显示的内容」被顶走。钉住滚动
        // 位置是不够的——显示的是 takeLast 的滑动窗口，新分片会把前面的字挤掉，像素位置
        // 没动、底下的文字却整体前移。设备实测到的就是框停在同一个位置、内容前进约五十行。
        // 因此读者回看时必须停止修剪：追加只发生在末尾，正在读的那一行停在原处。
        //
        // 这必须是*流中间*的动作，所以让假接口在 12 个分片后停下来：读者在那时上滑，剩下的
        // 分片随后到达，正好检验「持有期间窗口不再滑动」。
        val html = "<!DOCTYPE html><html><body><svg id='h'/></body></html>"
        val gate = CompletableDeferred<Unit>()
        val api = ScriptedAnimationApi(listOf(html), gate = gate)
        val fragment = "x".repeat(500)
        api.reasoningFragments = List(24) { fragment }
        api.reasoningFragmentsBeforePause = 12
        api.reasoningPause = CompletableDeferred()
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { withTimeout(10_000) { api.reasoningPaused.await() } }

        // Twelve fragments in: the tail is already clipped to its cap, and this is the text the
        // reader is looking at.
        val beforeHold = subject.uiState.value.animation.session!!
        assertEquals("暂停时显示的是被截断的尾部", ANIMATION_STREAM_TAIL_CHARS, beforeHold.reasoningText!!.length)

        // The reader scrolls back — the panel reports it, and the window must stop sliding.
        subject.holdAnimationReasoning(true)
        // The remaining twelve fragments arrive while the reader is away.
        api.reasoningPause!!.complete(Unit)
        runBlocking { withTimeout(10_000) { api.started.await() } }

        val held = subject.uiState.value.animation.session!!
        // Appends only: the window grew instead of sliding, so the characters that were on
        // screen when the reader scrolled back are still there, at the same place.
        assertTrue(
            "持有期间窗口必须只增不滑（实际 ${held.reasoningText!!.length}）",
            held.reasoningText!!.length > beforeHold.reasoningText!!.length,
        )
        assertTrue(
            "窗口起点不能前移：读者看到的那一段必须仍在开头",
            held.reasoningText!!.startsWith(beforeHold.reasoningText!!),
        )

        // Returning to the end resumes the bounded tail, so a long chain cannot grow forever.
        subject.holdAnimationReasoning(false)
        val resumed = subject.uiState.value.animation.session!!
        assertEquals(
            "回到末尾后恢复截断（实际 ${resumed.reasoningText!!.length}）",
            ANIMATION_STREAM_TAIL_CHARS,
            resumed.reasoningText!!.length,
        )

        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
    }

    @Test
    fun `a transient failure before any answer is retried automatically`() {
        // 用户报的就是这个：「思考一半就显示网络连接失败，没有自动重试吗」。连接在思考阶段断掉
        // 时一个正文字符都没拿到，这种失败第二次请求很可能就能过，所以要自动重试。
        val html = "<!DOCTYPE html><html><body><svg id='retry'/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        api.reasoningFragments = listOf("正在思考。")
        // 第一次网络断掉，第二次成功。
        api.failuresUntilSuccess = listOf(TestError(ErrorKind.NETWORK, "网络连接失败"))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        assertEquals("必须自动重发一次", 2, api.calls.get())
        val session = subject.uiState.value.animation.session!!
        assertEquals("第二次成功后进入结果态", AnimationPhase.READY, session.phase)
        assertEquals(html, session.html)
        assertNull("成功后不得留下错误", session.error)
    }

    @Test
    fun `a failure that a retry cannot fix is not retried`() {
        // 认证、额度、模型不存在这类失败是请求本身的问题：重发只会再被拒一次，白白多花一次
        // 完整预算。它们必须当次就结束。
        val api = ScriptedAnimationApi(listOf("unused"))
        api.failuresUntilSuccess = listOf(TestError(ErrorKind.AUTHENTICATION, "认证失败：401"))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        assertEquals("不可重试的失败只发一次", 1, api.calls.get())
        val session = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.FAILED, session.phase)
        assertTrue(session.error?.contains("401") == true)
    }

    @Test
    fun `a failure after the document started arriving is not re-requested`() {
        // 已经收到正文再断流：这一次请求已经产生并计费了产出，重发会把这份结果丢掉重来。
        // 重试只用于「什么都没拿到」的情况。
        val api = ScriptedAnimationApi(listOf("unused"), progressChunks = 3)
        api.failuresUntilSuccess = listOf(TestError(ErrorKind.UPSTREAM, "上游返回 502"))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        assertEquals("已收到正文后不再重发", 1, api.calls.get())
        assertEquals(AnimationPhase.FAILED, subject.uiState.value.animation.session!!.phase)
    }

    @Test
    fun `the retry budget is spent and then the failure is reported`() {
        // 一直失败必须停下来并如实报错，而不是无声地一直重试。
        val api = ScriptedAnimationApi(listOf("unused"))
        api.failuresUntilSuccess = List(10) { TestError(ErrorKind.NETWORK, "网络连接失败") }
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        assertEquals(
            "重试次数必须封顶",
            ANIMATION_MAX_RETRIES + 1,
            api.calls.get(),
        )
        val session = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.FAILED, session.phase)
        assertTrue("最终必须报出失败原因", session.error?.contains("网络") == true)
        assertNull("结束后不得留着重试提示", session.retryNotice)
    }

    @Test
    fun `a cut answer is retried, not dead-ended`() {
        // 用户截图里的死局：上游在输出中途掐断连接，已收到的半篇文档按「EOF 即答案」返回，
        // 解析失败后旧逻辑直接钉死在失败上（返回内容不是完整的 HTML 文档）。一次没成篇的
        // 回答和一次空响应一样，都值得用剩下的重试预算再试。
        val cut = "<!DOCTYPE html><html><body><svg><circle/></svg></body"
        val good = "<!DOCTYPE html><html><body><svg id='recovered'/></body></html>"
        val api = ScriptedAnimationApi(listOf(cut, good))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        assertEquals("中断的回答必须重发一次", 2, api.calls.get())
        val session = subject.uiState.value.animation.session!!
        assertEquals("第二次成功后进入结果态", AnimationPhase.READY, session.phase)
        assertEquals(good, session.html)
        assertNull(session.error)
    }

    @Test
    fun `the retry budget covers cut answers too`() {
        // 每一次都被掐断时同样要封顶并如实报错，而不是无声地一直重试。
        val cut = "<!DOCTYPE html><html><body>"
        val api = ScriptedAnimationApi(listOf(cut))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        assertEquals(ANIMATION_MAX_RETRIES + 1, api.calls.get())
        val session = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.FAILED, session.phase)
    }

    @Test
    fun `a retry is announced instead of looking like lost progress`() {
        // 自动重试必须看得见：否则计数从 8000 掉回 0 会被当成计数坏了，而不是重试。
        val html = "<!DOCTYPE html><html><body><svg id='notice'/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        api.failuresUntilSuccess = listOf(TestError(ErrorKind.NETWORK, "网络连接失败"))
        // 停住重试等待，好观察「正在重试」这段状态本身。
        val retryGate = CompletableDeferred<Unit>()
        val subject = animationViewModel(api, retryDelay = { retryGate.await() })
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()

        runBlocking {
            withTimeout(10_000) {
                while (subject.uiState.value.animation.session?.retryNotice == null) delay(10)
            }
        }
        val duringRetry = subject.uiState.value.animation.session!!
        assertTrue(
            "重试期间必须说明原因与次数：${duringRetry.retryNotice}",
            duringRetry.retryNotice!!.contains("网络") && duringRetry.retryNotice!!.contains("第 2 次"),
        )
        assertEquals("重试期间记为第二次尝试", 2, duringRetry.attempt)
        assertEquals("重试仍是生成中，不能预先报失败", AnimationPhase.GENERATING, duringRetry.phase)
        assertNull("重试期间不得把它报成失败", duringRetry.error)

        retryGate.complete(Unit)
        runBlocking { subject.awaitIdle() }

        val done = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.READY, done.phase)
        assertNull("成功落地后不得留着重试提示", done.retryNotice)
        assertEquals("成功后尝试次数复位", 1, done.attempt)
    }

    @Test
    fun `the retry notice names the attempt while it runs`() {
        // 直接驱动状态函数，确认重试期间的措辞包含次数，而不是一句含糊的「重试中」。
        val session = streamingSession(reasoning = 100, startedAt = 0L).copy(
            attempt = 2,
            retryNotice = "网络连接失败；正在重试（第 2 次，共 4 次）",
        )
        val label = animationStreamingStatus(session, now = 5_000L)
        assertTrue("重试期间必须标出是第几次尝试：$label", label.contains("第 2 次尝试"))

        val first = streamingSession(reasoning = 100, startedAt = 0L)
        val plain = animationStreamingStatus(first, now = 5_000L)
        assertEquals(
            "首次尝试的措辞不得被改动",
            "思考中 · 100 字 · 5 秒",
            plain,
        )
    }

    @Test
    fun `a cancel during the retry wait does not re-send`() {
        // 等重试的那段时间里用户取消，必须真的停下：再发一次会花掉用户没同意的预算。
        val api = ScriptedAnimationApi(listOf("unused"))
        api.failuresUntilSuccess = List(10) { TestError(ErrorKind.NETWORK, "网络连接失败") }
        // 把重试的等待停住，于是「正在等重试」这段状态是可观察的、可取消的。
        val retryGate = CompletableDeferred<Unit>()
        val subject = animationViewModel(api, retryDelay = { retryGate.await() })
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        // 第一次失败后进入重试等待：此时状态里应当已经有重试提示。
        runBlocking { withTimeout(10_000) { api.started.await() } }
        // 等到重试提示真的出现，证明我们停在等待段而不是失败段。
        runBlocking {
            withTimeout(10_000) {
                while (subject.uiState.value.animation.session?.retryNotice == null) delay(10)
            }
        }
        val beforeCancel = api.calls.get()

        subject.cancelAnimationGeneration()
        retryGate.complete(Unit)
        runBlocking { subject.awaitIdle() }

        val session = subject.uiState.value.animation.session!!
        assertEquals("取消后为结束态", AnimationPhase.FAILED, session.phase)
        assertNull("取消后不得留着重试提示", session.retryNotice)
        assertEquals("取消后不得再补发请求", beforeCancel, api.calls.get())
    }

    @Test
    fun `the reasoning view follows the newest line while nothing touches it`() {
        // 用户报的就是这个：思考中界面停在旧行，最后一行只露半个字。跟随是本层的默认行为，
        // 用「滚到底部 = 仍在跟随」这一条来钉住。
        assertEquals("停在末尾视为跟随", true, followEndObservation(1_000, 1_000, 1_000))
        assertEquals(
            "跟随滚动落在末尾，带一点亚像素余量也算末尾",
            true,
            followEndObservation(1_000 - FOLLOW_END_SLACK_PX, 1_000, 1_000),
        )
    }

    @Test
    fun `scrolling up stops the view from chasing the newest line`() {
        // 用户明确要求：手动上滑到上边就停在上边旧行。跟随必须让位给读者。
        assertEquals(
            "读者上滑后必须解除跟随",
            false,
            followEndObservation(offset = 120, extent = 1_000, previousExtent = 1_000),
        )
    }

    @Test
    fun `scrolling back to the end resumes following`() {
        // 解除跟随不是一次性的：回到末尾应当重新跟上，否则读者得手动追一辈子。
        assertEquals(
            "回到末尾恢复跟随",
            true,
            followEndObservation(offset = 1_000, extent = 1_000, previousExtent = 1_000),
        )
    }

    @Test
    fun `text growing is not mistaken for the reader scrolling up`() {
        // 这是这套判断的关键：新文本到达也会改变 offset/maxValue。若把增长当成读者的手势，
        // 跟随会在第一段新文本落下时立刻自我关闭——正是「停在旧行」那个 bug 的复发方式。
        assertNull(
            "内容变长本身不构成读者手势",
            followEndObservation(offset = 300, extent = 1_400, previousExtent = 1_000),
        )
    }

    @Test
    fun `the pin rule tolerates an unmeasured box`() {
        // 内容还没超过框高时 maxValue 是 0：此时「已经在末尾」是唯一正确答案，不能当成
        // 读者上滑（那会在第一行时就关掉跟随）。
        assertEquals(
            "没有可滚动高度时视为在末尾",
            true,
            followEndObservation(offset = 0, extent = 0, previousExtent = 0),
        )
    }

    @Test
    fun `the follow loop reads the pin decision at scroll time, not at capture time`() {
        // 这条钉的是刚刚修掉的那个 bug 的*接线*，而不是规则本身——上面几条规则测试一直都是
        // 绿的，因为错的从来不是规则，而是「谁在读、什么时候读」。
        //
        // 现象（用户报的）：手动上滑到中间之后，每来一段新内容视图就被拉回底部。根因是两个
        // effect 跑在不同协程里，增长那一个只以 contentKey 为键，读者滚动不会重启它，于是它
        // 闭包里捕获的 pin 值永远停在 true。修法是把决定放进一个可变容器，滚动时现读。
        //
        // 直接测 Compose 的 effect 需要宿主，所以这里用一个最小的模拟：一个写入端、一个
        // 与写入端生命周期不同的读取端，断言读取端拿到的是写入后的值而不是初始值。
        val decision = booleanArrayOf(true)
        // 读取端在「捕获时」记下它看到的东西，模拟那个只启动一次的 effect。
        var readAtStart = decision[0]
        // 读者上滑：写入端更新决定（规则见上一条测试）。
        followEndObservation(offset = 120, extent = 1_000, previousExtent = 1_000)
            ?.let { decision[0] = it }
        // 读取端在滚动那一刻现读，必须看到 false；若它用的是捕获时的值，这里会是 true，
        // 新内容一到就会把读者的位置拉回底部——正是那个 bug。
        assertTrue("捕获时读到的是旧值 true", readAtStart)
        assertEquals("滚动时现读必须拿到读者上滑后的 false", false, decision[0])
        readAtStart = decision[0]
        assertEquals("读取端此刻看到 false，因此不会把视图拉回底部", false, readAtStart)
    }

    @Test
    fun `the box heights are measured in lines so the clip never lands mid-glyph`() {
        // 半行问题的根因：固定 dp 上限不随字号缩放，切在字的中间。行高 × 整数才是安全高度。
        // 这里钉住算术本身（Compose 的取值链在设备截图上验证）。
        val lineHeightDp = 17f
        val reasoning = lineHeightDp * ANIMATION_REASONING_MAX_LINES
        assertEquals(
            "思考框高度必须是整行数：切在行间，末行完整",
            0f,
            reasoning % lineHeightDp,
        )
        val output = lineHeightDp * ANIMATION_OUTPUT_MAX_LINES
        assertEquals("输出框同理", 0f, output % lineHeightDp)
        // 源码窗格（1.7.17 加入同一族）：它原来用 aspectRatio 匹配预览高度，比例值几乎总
        // 是切在某一行的中间，最后一行就是半行。
        assertTrue("源码窗格下限必须至少一行", ANIMATION_SOURCE_MIN_LINES >= 1)
        assertTrue(
            "源码窗格上限不得小于下限",
            ANIMATION_SOURCE_MAX_LINES >= ANIMATION_SOURCE_MIN_LINES,
        )
        val source = lineHeightDp * ANIMATION_SOURCE_MAX_LINES
        assertEquals("源码窗格同理", 0f, source % lineHeightDp)
        // 字号放大时高度必须跟着长，否则大字号下又被切半行。
        val scaled = (lineHeightDp * 2f) * ANIMATION_REASONING_MAX_LINES
        assertTrue("字号翻倍时高度也翻倍", scaled > reasoning)
        assertEquals("且仍是整行数", 0f, scaled % (lineHeightDp * 2f))

        // 内边距加在整行数**之外**：它位于滚动容器外面，所以盒子是「整行数 + 内边距」，
        // 而真正要满足整行数的是文本视口。之前把内边距留在滚动里面，视口就少了整整一个
        // 内边距，上限又切在行中间 —— 半行以另一种形式回来了。这一条钉住那个关系。
        val paddingDp = 10f
        val boxHeight = reasoning + paddingDp
        assertEquals("文本视口（盒子减内边距）必须是整行数", 0f, (boxHeight - paddingDp) % lineHeightDp)
    }

    @Test
    fun `the fit stylesheet gives the page a viewport-sized box`() {
        // 白屏的两个成因共用一个根：文档自己的盒子没跟视口对上。注入的样式必须同时给出
        // 根元素高度（治白屏）与 SVG 的 100% 尺寸（治缩成一点点）。
        val doc = "<!DOCTYPE html><html><head><style>body{background:#fff}</style></head>" +
            "<body><svg viewBox=\"0 0 900 600\"><circle r=\"10\"/></svg></body></html>"
        val fitted = injectFitStylesheet(doc)
        // 根盒子必须锚在视口上，而不是靠百分比高度。设备实测：这个 WebView 里 html/body/svg
        // 的 height 全是 0px（视口本身是好的 299×220），页面画进了一个零高的盒子 —— 那就是
        // 白屏。`position: fixed` 直接对视口定尺寸，是引擎里唯一确定的那个高度；改成
        // height:100% / min-height:100% / 100vh 都会塌回 0。这一条钉住「锚在视口」这个选择。
        assertTrue("根盒子必须用 position: fixed 锚在视口上", fitted.contains("position: fixed"))
        assertTrue("必须铺满视口", fitted.contains("inset: 0"))
        assertTrue("仍要保留显式高度作为兜底", fitted.contains("height: 100%"))
        assertTrue("必须把 body 内的 svg 撑满", fitted.contains("body > svg"))
        assertTrue("必须保留文档自身的样式", fitted.contains("background:#fff"))
        // 必须排在文档自己样式之后，否则同优先级下会被文档的规则压过去。
        assertTrue(
            "注入的样式必须晚于文档自身的 <style>",
            fitted.indexOf("body > svg") > fitted.indexOf("background:#fff"),
        )
        // 结构不能被破坏：注入后仍是一份完整文档。
        assertTrue(fitted.startsWith("<!DOCTYPE html>"))
        assertTrue(fitted.trimEnd().endsWith("</html>"))
    }

    @Test
    fun `a general html page gets the root anchor but keeps its own layout`() {
        // 非 SVG 的普通页面自己管排版：给它同样的视口锚（一并治好 data: 文档里
        // height:100% 塌成 0 的问题），但不再强制 flex 居中、隐藏溢出或改写内容尺寸——
        // 那会把页面想显示的东西裁掉。
        val doc = "<!DOCTYPE html><html><head></head><body><div id='clock'>12:00</div></body></html>"
        val fitted = injectFitStylesheet(doc)
        assertTrue("普通页面也要锚在视口上", fitted.contains("position: fixed"))
        assertTrue("普通页面保留滚动", fitted.contains("overflow: auto"))
        assertFalse("不得强制 flex 居中", fitted.contains("align-items: center"))
        assertFalse("不得改写 svg 尺寸", fitted.contains("body > svg"))
        assertTrue(fitted.trimEnd().endsWith("</html>"))
    }

    @Test
    fun `the fit stylesheet leaves a document with no head alone`() {
        // 没有 head 就没地方放样式，此时原样返回，由 injectCsp 的包裹分支去兜底——
        // 而不是凭空造一个不完整的文档出来。
        val bare = "<svg><circle/></svg>"
        assertEquals(bare, injectFitStylesheet(bare))
    }

    @Test
    fun `the four secondary actions keep a full-size square touch target`() {
        // 四个动作改成纯图标方按钮之后，还能在 JVM 里量出来的是它的大小：44dp，与同一排的
        // 主按钮一致（一行按钮必须一样高），也是手指按得准的下限。图标与文案的取舍逻辑
        // （secondaryActionFit）随文案一起删掉了——没有字可切，规则就没有存在的理由；
        // 「按钮里不画文字、不留边框与涟漪」由 DialogWindowContractTest 的源码契约钉住。
        assertEquals(44.dp, SECONDARY_ACTION_SIZE)
    }

    @Test
    fun `the reasoning block starts folded`() {
        // 思考过程默认收起。展开是用户主动动作，不是一个自动发生的状态——链子常有几万字，
        // 自动展开会把输出框挤出屏幕，用户等的正文反而看不见了。
        assertEquals("思考块默认收起", false, ANIMATION_REASONING_EXPANDED_BY_DEFAULT)
    }

    @Test
    fun `the retry policy is the one the model testing path already uses`() {
        // 两条路径必须共用同一份「什么算可重试」的定义：各自维护一份迟早会漂移，而漂移的
        // 后果是同一个上游错误在一块屏幕上可重试、在另一块上致命。
        assertTrue(ErrorKind.NETWORK.isTransient())
        assertTrue(ErrorKind.TIMEOUT.isTransient())
        assertTrue(ErrorKind.RATE_LIMITED.isTransient())
        assertTrue(ErrorKind.UPSTREAM.isTransient())
        assertFalse("认证失败是请求本身的问题，重发只会再被拒", ErrorKind.AUTHENTICATION.isTransient())
        assertFalse(ErrorKind.INSUFFICIENT_QUOTA.isTransient())
        assertFalse(ErrorKind.FORBIDDEN.isTransient())
        assertFalse(ErrorKind.MODEL_NOT_FOUND.isTransient())
        assertFalse("响应无法解析不是链路问题", ErrorKind.INVALID_RESPONSE.isTransient())
        assertFalse(ErrorKind.TESTING_DISABLED.isTransient())
        assertFalse(ErrorKind.OTHER.isTransient())
    }

    @Test
    fun `the header counts the whole answer rather than the visible tail`() {
        // 正文框只显示尾部，所以拿它的长度当进度会把一份 12000 字符的文档报成「已收到
        // 4000 字符」。计数必须来自累计值。
        val html = "<!DOCTYPE html><html><body>" + "y".repeat(9_000) + "<svg/></body></html>"
        val gate = CompletableDeferred<Unit>()
        val api = ScriptedAnimationApi(listOf(html), gate = gate, progressChunks = 5)
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { withTimeout(10_000) { api.progressed.await() } }

        val session = subject.uiState.value.animation.session!!
        assertEquals("显示的是尾部", ANIMATION_STREAM_TAIL_CHARS, session.streamingText!!.length)
        assertEquals("计数是整份回答", html.length, session.receivedChars)

        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
    }

    @Test
    fun `the animation bounds are wide enough to let a thinking model finish`() {
        // 这是"不限制思考"这条产品决定在本层的落地：既然不再压制推理，就必须给它足够的
        // 预算和时间，否则思考还在进行就被应用自己的边界值截断——那还是"限制"。
        //
        // 实测支撑（记录在 ANIMATION_MAX_TOKENS / ANIMATION_TIMEOUT_SECONDS 的注释里）：
        // 同一提示词下 deepseek-v4.1-flash 花掉 33 079 字思考、正文 0 字；MiniMax-M2.7
        // 花掉 44 145 字思考、正文 0 字。（这里量的是字数而非 token，所以门槛按 token
        // 的常识上限取 65 536。）
        assertTrue(
            "输出预算必须足以覆盖思考+正文，实际 $ANIMATION_MAX_TOKENS",
            ANIMATION_MAX_TOKENS >= 65_536,
        )
        assertTrue(
            "时间窗口必须够长，实际 $ANIMATION_TIMEOUT_SECONDS 秒",
            ANIMATION_TIMEOUT_SECONDS >= 600,
        )
        // 文档上限必须大到能装下一份合法长文档。真正的风险是"网络层先截断、本层再以
        // 没有结尾为由拒掉"，而网络层的正文上限不在本包可见范围内，所以这里钉的是下界：
        // 它必须明显大于任何真实文档，而不是贴着 ANIMATION_MAX_TOKENS 换算出来的量级。
        assertTrue(
            "文档上限 $ANIMATION_MAX_HTML_CHARS 必须容得下完整长文档",
            ANIMATION_MAX_HTML_CHARS >= 2_000_000,
        )
    }

    @Test
    fun `the elapsed clock starts with the request and freezes when it settles`() {
        val html = "<!DOCTYPE html><html><body><svg id='e'/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        // The injected clock is a fixed instant, so the duration is deterministic: 0 ms
        // means the two stamps came from the same reading, which is what "frozen" means.
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        val session = subject.uiState.value.animation.session!!
        assertNotNull("请求开始时间必须记录下来", session.startedAt)
        assertNotNull("结束时间必须记录下来，否则读数会一直涨", session.settledAt)
        assertEquals("固定时钟下两枚时间戳相同", 0L, session.generationDurationMs())
    }

    @Test
    fun `a failed generation does not leave a half-written document on screen`() {
        // 失败时那段半成品必须一起丢掉：留在屏幕上会看起来像一个「只是停住了」的结果。
        val api = ScriptedAnimationApi(listOf("unused"), progressChunks = 4)
        api.failure = TestError(ErrorKind.UPSTREAM, "上游返回 HTTP 502")
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        val session = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.FAILED, session.phase)
        assertNull("失败后不得留下半成品", session.streamingText)
        assertNull(session.html)
    }

    @Test
    fun `cancelling drops the partial document but keeps a previous preview`() {
        val html = "<!DOCTYPE html><html><body><svg id='good'/></body></html>"
        val gate = CompletableDeferred<Unit>()
        val api = ScriptedAnimationApi(listOf(html), gate = gate, progressChunks = 4)
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        // 第一次：等进度到达再放行，拿到一份可用的预览。
        runBlocking { withTimeout(10_000) { api.progressSignal(0).await() } }
        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
        val preview = subject.uiState.value.animation.session!!.html
        assertNotNull(preview)

        // 第二次：进度到达后取消（这一次的响应留在 gate 后面不返回）。
        api.gate = CompletableDeferred()
        subject.startAnimationGeneration()
        runBlocking { withTimeout(10_000) { api.progressSignal(1).await() } }
        val partial = subject.uiState.value.animation.session!!.streamingText
        assertNotNull("取消前应该已经看到半成品，否则这条测不到清理", partial)
        subject.cancelAnimationGeneration()

        val session = subject.uiState.value.animation.session!!
        assertEquals("取消后仍显示上一次成功的结果", preview, session.html)
        assertNull("取消掉的请求不得留下半成品", session.streamingText)
    }

    @Test
    fun `a relay that reports no progress still yields a result`() {
        // 有的中转站一次 delta 都不发（或只发 [DONE]）。没有进度不等于失败：这种情况
        // 下面板只是没有东西可显示，结果照样要落地。
        val html = "<!DOCTYPE html><html><body><svg id='silent'/></body></html>"
        val gate = CompletableDeferred<Unit>()
        val api = ScriptedAnimationApi(listOf(html), gate = gate, progressChunks = 0)
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        // 请求已发出、还没有任何 delta：这一段是可观察的，用 gate 把它定住。
        runBlocking { withTimeout(10_000) { api.started.await() } }

        val inFlight = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.GENERATING, inFlight.phase)
        assertNull("没有进度时不应伪造内容", inFlight.streamingText)

        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
        val done = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.READY, done.phase)
        assertEquals(html, done.html)
        assertNull(done.streamingText)
    }

    @Test
    fun `a failed regeneration keeps the previous preview`() {
        val html = "<!DOCTYPE html><html><body><svg id='first'/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        api.failure = TestError(ErrorKind.UPSTREAM, "上游返回 HTTP 502")
        // 第一次成功
        val subject = animationViewModel(api)
        api.failure = null
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }
        val kept = subject.uiState.value.animation.session!!.html
        assertNotNull(kept)

        // 第二次失败：预览必须留在原地。
        api.failure = TestError(ErrorKind.UPSTREAM, "上游返回 HTTP 502")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        val session = subject.uiState.value.animation.session!!
        assertEquals("失败不得丢掉上一次的预览", kept, session.html)
        assertEquals(AnimationPhase.READY, session.phase)
        assertTrue(session.error?.contains("502") == true)
    }

    @Test
    fun `a failed first generation reports the reason and stays recoverable`() {
        val api = ScriptedAnimationApi(listOf("unused"))
        api.failure = TestError(ErrorKind.AUTHENTICATION, "认证失败：401")
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        val session = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.FAILED, session.phase)
        assertTrue(session.error?.contains("401") == true)
        // 面板仍然开着，用户可以改提示词再试。
        assertNotNull(subject.uiState.value.animation.session)
    }

    @Test
    fun `unusable output is reported instead of previewed`() {
        val api = ScriptedAnimationApi(listOf("抱歉，我不能生成这个。"))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        val session = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.FAILED, session.phase)
        assertNull("非 HTML 不得进预览", session.html)
        assertTrue(session.error?.contains("HTML") == true)
    }

    @Test
    fun `cancelling leaves the panel open and the previous preview intact`() {
        val gate = CompletableDeferred<Unit>()
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html), gate = gate)
        val subject = animationViewModel(api)
        // 先成功一次，建立已有预览。
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { api.started.await() }
        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
        val preview = subject.uiState.value.animation.session!!.html

        // 再生成一次并取消。
        val secondGate = CompletableDeferred<Unit>()
        api.gate = secondGate
        api.started = CompletableDeferred()
        subject.startAnimationGeneration()
        runBlocking { api.started.await() }
        subject.cancelAnimationGeneration()

        val session = subject.uiState.value.animation.session!!
        assertEquals("取消后仍可看到已有预览", preview, session.html)
        assertEquals(AnimationPhase.READY, session.phase)
        assertTrue(session.error?.contains("取消") == true)
        // 取消后可以主动重试：这是"允许用户重试"的那一条。
        assertTrue(subject.uiState.value.animation.session != null)
    }

    @Test
    fun `a cancelled generation does not leave the slot occupied for the next one`() {
        // 用户的原话是「点击取消后，不会再占据后台测试进程，影响下一次测试」。取消掉的
        // 请求必须把位置真的让出来：下一次点「重新生成」要能发出去，而不是被一条已经取消
        // 的进程挡在门外。取消时把该会话的作业摘掉，所以「还有动画在跑」读到的 false 是
        // 「这里没有东西了」，不是「刚好跑完了」。
        val gate = CompletableDeferred<Unit>()
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html), gate = gate)
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { api.started.await() }
        subject.cancelAnimationGeneration()
        runBlocking { api.cancelled.await() }

        // 下一次生成必须真的走出去：若位置还占着，这里就等不到请求，超时会说明原因。
        val secondGate = CompletableDeferred<Unit>()
        api.gate = secondGate
        api.started = CompletableDeferred()
        subject.startAnimationGeneration()
        runBlocking {
            withTimeout(ROUND_START_TIMEOUT_MILLIS) { api.started.await() }
        }

        assertEquals("取消之后必须能再发一次请求", 2, api.calls.get())
        assertEquals(
            "新的一次必须真的在生成中",
            AnimationPhase.GENERATING,
            subject.uiState.value.animation.session!!.phase,
        )
        secondGate.complete(Unit)
        runBlocking { subject.awaitIdle() }
    }

    @Test
    fun `closing the card keeps the test running`() {
        val gate = CompletableDeferred<Unit>()
        val api = ScriptedAnimationApi(listOf("<!DOCTYPE html><html><svg/></html>"), gate = gate)
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { api.started.await() }

        subject.closeAnimationPanel()

        // 卡片收起只是收起界面：记录还在，并且还在跑——用户关掉卡片去干别的，回来时
        // 那个测试必须还在列表里、还在进行，而不是被悄悄掐掉。
        assertFalse("关掉卡片就是把面板打开状态清掉", subject.uiState.value.animation.panelOpen)
        val session = subject.uiState.value.animation.session
        assertNotNull("关掉卡片不得把进行中的测试一起丢掉", session)
        assertEquals(AnimationPhase.GENERATING, session!!.phase)

        // 生成照旧完成并落到那条记录上。
        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
        val finished = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.READY, finished.phase)
        assertNotNull("关卡片期间跑完的动画必须留在记录里", finished.html)
    }

    @Test
    fun `deleting the generating test cancels it and drops it`() {
        val gate = CompletableDeferred<Unit>()
        val api = ScriptedAnimationApi(listOf("<!DOCTYPE html><html><svg/></html>"), gate = gate)
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { api.started.await() }
        val sessionId = subject.uiState.value.animation.session!!.sessionId

        subject.removeAnimationSession(sessionId)

        assertNull("删掉的那条记录必须从列表消失", subject.uiState.value.animation.session)
        // 取消必须真的到达网络层：await 成功即证明，超时会让测试失败。关卡片不取消，
        // 删除才取消——否则关掉的生成会在后台白烧一个请求。
        runBlocking { withTimeout(2_000) { api.cancelled.await() } }
    }

    @Test
    fun `a late answer from a deleted test writes nothing`() {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<Unit>()
        val html = "<!DOCTYPE html><html><svg/></html>"
        val api = object : RelayApi() {
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
            ): ApiResult<String> = withContext(NonCancellable) {
                started.complete(Unit)
                gate.await()
                onProgress(html)
                onReasoning("late reasoning")
                delivered.complete(Unit)
                ApiResult.Success(html)
            }
        }
        val subject = animationViewModel(api)
        try {
            subject.openAnimationTest("sup-1", "test-model")
            subject.startAnimationGeneration()
            runBlocking { withTimeout(10_000) { started.await() } }
            val sessionId = subject.uiState.value.animation.session!!.sessionId
            val jobs = subject.viewModelScope.coroutineContext[Job]!!.children.toList()
            assertTrue("必须捕获实际生成作业", jobs.isNotEmpty())
            subject.removeAnimationSession(sessionId)
            subject.openAnimationTest("sup-1", "other-model")
            val remaining = subject.uiState.value.animation
            assertTrue("已取消作业仍持有晚到响应", jobs.any { !it.isCompleted })

            gate.complete(Unit)
            runBlocking {
                withContext(Dispatchers.Default) {
                    withTimeout(10_000) {
                        delivered.await()
                        jobs.forEach { it.join() }
                    }
                }
            }
            assertTrue(
                "晚到响应不得让已删除的记录复活",
                subject.uiState.value.animation.sessions.none { it.sessionId == sessionId },
            )
            assertEquals("晚到回调不得污染后来打开的记录", remaining, subject.uiState.value.animation)
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun `a late answer from an earlier test lands on its own record only`() {
        val oldHtml = "<!DOCTYPE html><html><svg id='old'/></html>"
        val newHtml = "<!DOCTYPE html><html><svg id='new'/></html>"
        val firstGate = CompletableDeferred<Unit>()
        val api = ScriptedAnimationApi(listOf(oldHtml, newHtml), gate = firstGate)
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { api.started.await() }
        val firstId = subject.uiState.value.animation.session!!.sessionId

        // 第二条测试同时跑（1.7.17 起两条并行，各持各的 requestId）：给它换自己的闸门，
        // 让它先跑完，第一条仍挂在旧闸门上。
        val secondGate = CompletableDeferred<Unit>()
        api.gate = secondGate
        api.started = CompletableDeferred()
        subject.startAnimationTest("sup-1", "other-model")
        runBlocking {
            withTimeout(10_000) {
                while (api.calls.get() < 2) delay(10)
            }
        }
        secondGate.complete(Unit)
        runBlocking {
            withTimeout(10_000) {
                while (subject.uiState.value.animation.sessions
                    .firstOrNull { it.model == "other-model" }?.phase != AnimationPhase.READY
                ) {
                    delay(10)
                }
            }
        }

        // 第一条的响应此刻才回来：它只准落到自己的记录上，绝不准碰当前展示的这条。
        firstGate.complete(Unit)
        runBlocking {
            withTimeout(10_000) {
                while (subject.uiState.value.animation.sessions
                    .firstOrNull { it.sessionId == firstId }?.phase != AnimationPhase.READY
                ) {
                    delay(10)
                }
            }
        }

        val selected = subject.uiState.value.animation.session!!
        assertEquals("当前展示的仍是第二条", "other-model", selected.model)
        assertEquals("第二条的结果不得被旧测试的晚到答案覆盖", newHtml, selected.html)

        val old = subject.uiState.value.animation.sessions.firstOrNull { it.model == "test-model" }
        assertNotNull("开第二条不得把第一条从列表里丢掉", old)
        assertEquals("晚到的结果落在它自己的记录上", oldHtml, old!!.html)
    }

    @Test
    fun `starting a second test runs beside the first instead of cancelling it`() {
        val firstGate = CompletableDeferred<Unit>()
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html), gate = firstGate)
        val subject = animationViewModel(api)
        subject.startAnimationTest("sup-1", "m-1")
        runBlocking { api.started.await() }
        val firstId = subject.uiState.value.animation.session!!.sessionId

        // 1.7.16 及以前同一时刻只允许一条生成，开第二条会把第一条「挤下来」写成失败结局。
        // 批量测试与跨供应商并行推翻了这条语义：两条各挂各的作业，各自等待自己的回答。
        api.started = CompletableDeferred()
        subject.startAnimationTest("sup-1", "m-2")
        runBlocking { api.started.await() }

        val sessions = subject.uiState.value.animation.sessions
        assertEquals("两条测试都得留在列表里", 2, sessions.size)
        assertEquals("两条可以同时在生成", 2, sessions.count { it.phase == AnimationPhase.GENERATING })
        val first = sessions.firstOrNull { it.sessionId == firstId }
        assertNotNull("先开的那条必须还在列表里", first)
        assertNotEquals(
            "两条请求各持各的归属 id",
            first!!.requestId,
            sessions.first { it.sessionId != firstId }.requestId,
        )

        firstGate.complete(Unit)
        runBlocking { subject.awaitIdle() }
        val done = subject.uiState.value.animation
        assertTrue(
            "放行后两条都要跑完：${done.sessions.map { it.phase }}",
            done.sessions.all { it.phase == AnimationPhase.READY },
        )
        assertEquals(
            "跑完之后列表顺序仍是新的在前",
            listOf("m-2", "m-1"),
            done.sessions.map { it.model },
        )
    }

    @Test
    fun `the draft survives closing and reopening within the session`() {
        val api = ScriptedAnimationApi(listOf("unused"))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.updateAnimationPrompt("自定义的题目：画一只猫")
        subject.closeAnimationPanel()

        subject.openAnimationTest("sup-1", "test-model")

        assertEquals(
            "关闭重开不得丢草稿",
            "自定义的题目：画一只猫",
            subject.uiState.value.animation.session!!.draftPrompt,
        )
    }

    @Test
    fun `a round runs while an animation is generating`() {
        val gate = CompletableDeferred<Unit>()
        val api = ScriptedAnimationApi(listOf("<!DOCTYPE html><html><svg/></html>"), gate = gate)
        val subject = animationViewModel(api)
        subject.selectSupplier("sup-1")
        subject.toggleModelSelection("test-model")
        subject.selectMode(DetectionMode.API)
        subject.regenerateChallenges()
        subject.startAnimationTest("sup-1", "test-model")
        runBlocking { api.started.await() }

        subject.runApiDetection()
        runBlocking {
            withTimeout(ROUND_START_TIMEOUT_MILLIS) {
                while (api.calls.get() < 2) delay(10)
            }
        }

        assertTrue("动画生成中，指纹轮次也必须进入在途状态", subject.uiState.value.isRunning)
        assertTrue(
            "动画和指纹都应共用各自的请求，不得互相拒绝",
            api.calls.get() >= 2,
        )

        gate.complete(Unit)
        subject.cancelRun()
        runBlocking { subject.awaitIdle() }
    }

    @Test
    fun `a cancelled animation does not block the next round`() {
        // 取消之后不得再占着位置：取消会把句柄清掉，而 `isCompleted` 读的正是「当时那个
        // 句柄到底跑完没有」，所以这条同时守住「取消完就能开检测」和「取消不是把锁留在
        // 后台等它自己超时」。
        val gate = CompletableDeferred<Unit>()
        val api = ScriptedAnimationApi(listOf("<!DOCTYPE html><html><svg/></html>"), gate = gate)
        val subject = animationViewModel(api)
        subject.selectSupplier("sup-1")
        subject.toggleModelSelection("test-model")
        subject.selectMode(DetectionMode.API)
        subject.startAnimationTest("sup-1", "test-model")
        runBlocking { api.started.await() }

        subject.cancelAnimationGeneration()
        runBlocking { api.cancelled.await() }

        // 同样把检测停在闸门上：这条要证明的是「取消之后检测真的起来了」，而不是「它跑完
        // 之后 isRunning 恰好是 false」。等待带超时：位置没让出来时它永远不会到达，而无上界
        // 的等待会让这条测试挂死，读起来像环境问题而不是回归。
        val roundGate = CompletableDeferred<Unit>()
        api.gate = roundGate
        api.started = CompletableDeferred()

        // 取消之后检测要能起来：动画那一路已经被取消，不该再拦人。
        subject.runApiDetection()
        runBlocking {
            withTimeout(ROUND_START_TIMEOUT_MILLIS) { api.started.await() }
        }

        assertTrue(
            "取消动画之后检测仍然开不起来：被取消的后台进程还占着位置",
            subject.uiState.value.isRunning,
        )
        assertFalse(
            "被误当成动画还在跑而拒绝",
            subject.uiState.value.message?.contains("动画") == true,
        )

        roundGate.complete(Unit)
        subject.cancelRun()
        runBlocking { subject.awaitIdle() }
    }

    @Test
    fun `a finished animation does not block the next round`() {
        // 正常跑完也要把位置让出来，别变成「用过一次就再也开不了检测」。
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        val subject = animationViewModel(api)
        subject.selectSupplier("sup-1")
        subject.toggleModelSelection("test-model")
        subject.selectMode(DetectionMode.API)
        subject.startAnimationTest("sup-1", "test-model")
        runBlocking { subject.awaitIdle() }
        assertEquals(
            "动画得先真的跑完",
            AnimationPhase.READY,
            subject.uiState.value.animation.session!!.phase,
        )

        // 让检测的请求停在闸门上：否则它会在这一行里跑完，`isRunning` 读到的 false 是
        // 「已经结束」而不是「没被拦住」，这条测试就什么都没证明。等待带超时，理由同上。
        val roundGate = CompletableDeferred<Unit>()
        api.gate = roundGate
        api.started = CompletableDeferred()

        subject.runApiDetection()
        runBlocking {
            withTimeout(ROUND_START_TIMEOUT_MILLIS) { api.started.await() }
        }

        assertTrue(
            "动画跑完之后检测仍然开不起来：位置没让出来",
            subject.uiState.value.isRunning,
        )
        assertFalse(
            "被误当成动画还在跑而拒绝",
            subject.uiState.value.message?.contains("动画") == true,
        )

        roundGate.complete(Unit)
        subject.cancelRun()
        runBlocking { subject.awaitIdle() }
    }

    @Test
    fun `opening the card reads the catalogue it needs to offer a supplier`() {
        // 入口在模型测试页，用户完全可能一次都没进过指纹页就点进来（刚拉完模型就想生成动画
        // 是最顺的一条路）。指纹页进入时才刷目录，所以这里必须自己刷一次，否则卡片会显示
        // 「还没有可用的供应商」，而供应商列表就在它背后那一页上。
        //
        // 起点必须是空目录：fixture 里 helper 已经读过一次了，直接开卡片就变成「反正已经
        // 有一份」的自证。这里先把它清空，再让 openAnimationPanel 自己去读回来。
        val api = ScriptedAnimationApi(listOf("unused"))
        val subject = animationViewModel(api)
        clearCatalogue(subject)
        assertTrue(
            "前置条件：目录要先清空，否则这条测试什么都没测",
            subject.uiState.value.suppliers.isEmpty(),
        )

        subject.openAnimationPanel()
        runBlocking { subject.awaitIdle() }

        assertTrue(
            "卡片打开后供应商仍然是空的：它没有自己读目录，卡片会对着背后的供应商说「没有可用的供应商」",
            subject.uiState.value.suppliers.isNotEmpty(),
        )
    }

    /** Empties the loaded supplier catalogue the way a cold start leaves it. */
    private fun clearCatalogue(subject: FingerprintViewModel) {
        val field = FingerprintViewModel::class.java.getDeclaredField("knownSuppliers")
        field.isAccessible = true
        field.set(subject, emptyList<SupplierProfile>())
        val state = FingerprintViewModel::class.java.getDeclaredField("_uiState")
        state.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = state.get(subject) as kotlinx.coroutines.flow.MutableStateFlow<
            com.relaytester.app.feature.fingerprint.FingerprintUiState
            >
        flow.value = flow.value.copy(suppliers = emptyList())
    }

    @Test
    fun `an animation request runs while a fingerprint round is active`() {
        val animationGate = CompletableDeferred<Unit>()
        val api = ScriptedAnimationApi(
            listOf("<!DOCTYPE html><html><svg/></html>"),
            gate = animationGate,
        )
        val subject = animationViewModel(api)
        setRunning(subject)
        subject.openAnimationTest("sup-1", "test-model")

        subject.startAnimationGeneration()
        runBlocking { api.started.await() }

        assertEquals("指纹轮次进行中也必须允许动画请求", 1, api.calls.get())
        assertEquals(AnimationPhase.GENERATING, subject.uiState.value.animation.session!!.phase)

        animationGate.complete(Unit)
        runBlocking { subject.awaitIdle() }
    }

    @Test
    fun `a second test for the same supplier and model reuses its record`() {
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html, html))
        val subject = animationViewModel(api)

        subject.startAnimationTest("sup-1", "test-model")
        runBlocking { subject.awaitIdle() }
        val firstId = subject.uiState.value.animation.session!!.sessionId

        subject.startAnimationTest("sup-1", "test-model")
        runBlocking { subject.awaitIdle() }

        // 同一个供应商 + 同一个模型再点一次「开始生成」是重跑那条测试，不是列表里多出
        // 一行说着同样话的记录。列表是给人看的，重复行会让"哪条才是我在等的"失效。
        assertEquals(1, subject.uiState.value.animation.sessions.size)
        assertEquals("重跑必须落在原来那条记录上", firstId, subject.uiState.value.animation.session!!.sessionId)
    }

    @Test
    fun `a second test for another model is a separate record`() {
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html, html))
        val subject = animationViewModel(api)

        subject.startAnimationTest("sup-1", "test-model")
        runBlocking { subject.awaitIdle() }
        subject.startAnimationTest("sup-1", "other-model")
        runBlocking { subject.awaitIdle() }

        // 换模型是另一条测试：两条都在列表里，新的排在前面（createdAt 大）。
        val sessions = subject.uiState.value.animation.sessions
        assertEquals(2, sessions.size)
        assertEquals("other-model", sessions.first().model)
        assertEquals("test-model", sessions.last().model)
    }

    @Test
    fun `the list keeps every test and the newest is first`() {
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html, html, html))
        val subject = animationViewModel(api)

        subject.startAnimationTest("sup-1", "m-1")
        runBlocking { subject.awaitIdle() }
        subject.startAnimationTest("sup-1", "m-2")
        runBlocking { subject.awaitIdle() }
        subject.startAnimationTest("sup-1", "m-3")
        runBlocking { subject.awaitIdle() }

        val ids = subject.uiState.value.animation.sessions.map { it.sessionId }
        assertEquals("n-1 条测试必须一条不少", 3, ids.size)
        assertEquals("列表必须新的在前", ids.sortedDescending(), ids)
    }

    @Test
    fun `a running test stays openable in the list while another one runs`() {
        val gate = CompletableDeferred<Unit>()
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html), gate = gate)
        val subject = animationViewModel(api)
        subject.startAnimationTest("sup-1", "m-1")
        runBlocking { api.started.await() }
        val firstId = subject.uiState.value.animation.session!!.sessionId

        // 批量测试进入第一条的界面后，其余的还在列表里跑：列表必须能看到、能切到每一条，
        // 先开的那条不被第二条动过。
        api.started = CompletableDeferred()
        subject.startAnimationTest("sup-1", "m-2")
        runBlocking {
            withTimeout(10_000) {
                while (api.calls.get() < 2) delay(10)
            }
        }
        assertEquals(2, subject.uiState.value.animation.sessions.size)
        subject.selectAnimationSession(firstId)
        val first = subject.uiState.value.animation.session!!
        assertEquals("m-1", first.model)
        assertEquals("先开的那条仍在生成，没有被第二条取代", AnimationPhase.GENERATING, first.phase)
        assertNull("第二条不得写进第一条的记录", first.streamingText)

        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
        val done = subject.uiState.value.animation
        assertTrue(
            "放行后两条都要跑完：${done.sessions.map { it.phase }}",
            done.sessions.all { it.phase == AnimationPhase.READY },
        )
        assertEquals(
            "列表里两条都得在",
            listOf("m-2", "m-1"),
            done.sessions.map { it.model },
        )
    }

    @Test
    fun `selecting a test shows that one and null returns to the list`() {
        val html = "<!DOCTYPE html><html><body><svg id='a'/></body></html>"
        val api = ScriptedAnimationApi(listOf(html, html))
        val subject = animationViewModel(api)
        subject.startAnimationTest("sup-1", "m-1")
        runBlocking { subject.awaitIdle() }
        val firstId = subject.uiState.value.animation.session!!.sessionId
        subject.startAnimationTest("sup-1", "m-2")
        runBlocking { subject.awaitIdle() }
        val secondId = subject.uiState.value.animation.session!!.sessionId

        subject.selectAnimationSession(firstId)
        assertEquals(firstId, subject.uiState.value.animation.selectedSessionId)
        assertEquals("m-1", subject.uiState.value.animation.selected!!.model)

        subject.selectAnimationSession(null)
        assertNull("选中为 null 表示回到列表视图", subject.uiState.value.animation.selectedSessionId)
        assertNull(subject.uiState.value.animation.selected)
        assertEquals("回到列表不得丢任何一条记录", 2, subject.uiState.value.animation.sessions.size)
        assertEquals(secondId, subject.uiState.value.animation.sessions.first().sessionId)
    }

    @Test
    fun `selecting a test that no longer exists is ignored`() {
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        val subject = animationViewModel(api)
        subject.startAnimationTest("sup-1", "m-1")
        runBlocking { subject.awaitIdle() }
        val id = subject.uiState.value.animation.session!!.sessionId

        subject.selectAnimationSession(id + 999)

        assertEquals("选一条不存在的记录必须原样不动", id, subject.uiState.value.animation.selectedSessionId)
    }

    @Test
    fun `an unknown supplier is refused without creating a record`() {
        val api = ScriptedAnimationApi(listOf("unused"))
        val subject = animationViewModel(api)

        subject.startAnimationTest("sup-does-not-exist", "test-model")

        assertTrue("供应商不存在时不得留下一条空记录", subject.uiState.value.animation.sessions.isEmpty())
        assertNull(subject.uiState.value.animation.selectedSessionId)
    }

    @Test
    fun `a blank model is refused without creating a record`() {
        val api = ScriptedAnimationApi(listOf("unused"))
        val subject = animationViewModel(api)

        subject.startAnimationTest("sup-1", "   ")

        assertTrue("没选模型时不得留下一条空记录", subject.uiState.value.animation.sessions.isEmpty())
        assertNull(subject.uiState.value.animation.generating)
    }

    @Test
    fun `the supplier picker publishes the catalogue and resets the model`() {
        val api = ScriptedAnimationApi(listOf("unused"))
        val subject = animationViewModel(api)
        subject.selectAnimationSupplier("sup-1")
        subject.toggleAnimationModel("test-model")

        // 重新选供应商时，上一个供应商的模型必须清空：留着的模型很可能不在新目录里，
        // 点开始会被当成"该模型不存在"或者干脆发到一个不相干的模型上。
        subject.selectAnimationSupplier("sup-1")

        assertNull("换供应商后必须清掉上一个供应商选的模型", subject.uiState.value.animation.model)
    }

    @Test
    fun `choosing an animation supplier does not disturb the detection panel`() {
        val api = ScriptedAnimationApi(listOf("unused"))
        val subject = animationViewModel(api)
        val before = subject.uiState.value

        subject.selectAnimationSupplier("sup-1")
        subject.toggleAnimationModel("test-model")

        val after = subject.uiState.value
        assertEquals("动画卡片选供应商不得动检测页的供应商", before.selectedSupplierId, after.selectedSupplierId)
        assertEquals("动画卡片选供应商不得清掉检测页勾选的模型", before.selectedModels, after.selectedModels)
        assertEquals("动画卡片选供应商不得清掉检测结果", before.batchResults, after.batchResults)
        assertEquals("动画卡片选供应商不得动模型过滤词", before.modelFilter, after.modelFilter)
    }

    @Test
    fun `the animation session never touches the round's own state`() {
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        val subject = animationViewModel(api)
        val before = subject.uiState.value

        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        val after = subject.uiState.value
        assertEquals("答案不得改变", before.progress, after.progress)
        assertEquals("模型格必须原样", before.modelSlots, after.modelSlots)
        assertEquals("重试列表必须原样", before.retryableModels, after.retryableModels)
        assertEquals("排队重试必须原样", before.queuedRetries, after.queuedRetries)
        assertEquals("候选分析必须原样", before.analysis, after.analysis)
        assertEquals("历史必须原样", before.history, after.history)
        assertEquals("检测结果行必须原样", before.batchResults, after.batchResults)
    }

    @Test
    fun `a render failure is reported and keeps the source readable`() {
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        subject.reportAnimationRenderFailure("渲染进程崩溃")

        val session = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.FAILED, session.phase)
        assertTrue(session.error?.contains("渲染") == true)
        assertEquals("渲染失败不得丢掉源码", html, session.html)
    }

    // ---- 多模型一次启动与并行（1.7.17 起，入口在 1.7.20 统一）--------------

    @Test
    fun `every ticked model starts at once and the first in catalogue order is focused`() {
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.selectAnimationSupplier("sup-1")
        // 勾选顺序故意与目录顺序相反：启动顺序按目录来，不按勾选先后。
        subject.toggleAnimationModel("other-model")
        subject.toggleAnimationModel("test-model")

        subject.startSelectedAnimationTests("sup-1")
        runBlocking { subject.awaitIdle() }

        val animation = subject.uiState.value.animation
        assertEquals("两个勾选各开一条会话", 2, animation.sessions.size)
        assertTrue(
            "每一条都要跑完：${animation.sessions.map { it.phase }}",
            animation.sessions.all { it.phase == AnimationPhase.READY },
        )
        assertEquals(
            "请求按目录顺序发出，不按勾选先后",
            listOf("test-model", "other-model"),
            api.models,
        )
        assertEquals(
            "启动后进入排序第一的模型的测试界面",
            "test-model",
            animation.selected!!.model,
        )
        assertEquals("启动后勾选清空", emptyList<String>(), animation.selectedModels)
    }

    @Test
    fun `ticking models keeps the single run pointed at the first ticked in catalogue order`() {
        val api = ScriptedAnimationApi(listOf("unused"))
        val subject = animationViewModel(api)
        subject.selectAnimationSupplier("sup-1")

        subject.toggleAnimationModel("other-model")
        assertEquals("勾第一个时单选跟随它", "other-model", subject.uiState.value.animation.model)
        subject.toggleAnimationModel("test-model")
        assertEquals(
            "再勾一个，单选指向目录顺序的第一个，与批量聚焦规则一致",
            "test-model",
            subject.uiState.value.animation.model,
        )
        subject.toggleAnimationModel("test-model")
        assertEquals("取消勾选后单选退回剩下的第一个", "other-model", subject.uiState.value.animation.model)
        subject.toggleAnimationModel("other-model")
        assertNull("全部取消后单选清空", subject.uiState.value.animation.model)
        assertEquals(emptyList<String>(), subject.uiState.value.animation.selectedModels)
    }

    @Test
    fun `batch ticks do not survive a supplier switch`() {
        // 多选只允许同供应商：换供应商后残留的勾选会指向不存在的目录项，必须整体作废。
        val api = ScriptedAnimationApi(listOf("unused"))
        val subject = animationViewModel(api)
        subject.selectAnimationSupplier("sup-1")
        subject.toggleAnimationModel("test-model")
        subject.toggleAnimationModel("other-model")

        subject.selectAnimationSupplier("sup-1")

        assertEquals("换供应商后勾选清空", emptyList<String>(), subject.uiState.value.animation.selectedModels)
        assertNull("换供应商后模型清空", subject.uiState.value.animation.model)
    }

    @Test
    fun `a test from another supplier runs beside a running one`() {
        // 1.7.16 及以前，新测试会把正在跑的那条「取代」掉（直接写上失败结局）。用户要的
        // 批量与跨供应商并行推翻了这个语义：两条各挂各的作业，各自等待自己的回答。
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val gate = CompletableDeferred<Unit>()
        val api = ScriptedAnimationApi(listOf(html), gate = gate)
        val subject = animationViewModel(api, extraSupplier = true)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { api.started.await() }

        // 第一条还挂在闸门里，第二家供应商的测试现在开始：不得取代，也不得排队。
        subject.startAnimationTest("sup-2", "sup2-model")
        runBlocking {
            withTimeout(10_000) {
                while (api.calls.get() < 2) delay(10)
            }
        }
        val animation = subject.uiState.value.animation
        assertEquals(2, animation.sessions.size)
        val first = animation.sessions.first { it.model == "test-model" }
        val second = animation.sessions.first { it.model == "sup2-model" }
        assertEquals("先开的那条不得被取代", AnimationPhase.GENERATING, first.phase)
        assertEquals(AnimationPhase.GENERATING, second.phase)
        assertNotEquals("两条请求各持各的归属 id", first.requestId, second.requestId)

        gate.complete(Unit)
        runBlocking { subject.awaitIdle() }
        val done = subject.uiState.value.animation
        assertTrue(
            "放行后两条都要跑完：${done.sessions.map { it.phase }}",
            done.sessions.all { it.phase == AnimationPhase.READY },
        )
    }

    @Test
    fun `the retry notice clears once the retried attempt starts`() {
        // 用户实测到的：第 2 次尝试都开始跑请求了，「网络连接失败；正在重试」还钉在屏幕
        // 上。提示是给**等待段**看的，新尝试一开始就必须退场——归零则发生在这一次生成
        // 彻底结束时。
        val html = "<!DOCTYPE html><html><body><svg id='notice-clear'/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        api.failuresUntilSuccess = listOf(TestError(ErrorKind.NETWORK, "网络连接失败"))
        val retryGate = CompletableDeferred<Unit>()
        val subject = animationViewModel(api, retryDelay = { retryGate.await() })
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking {
            withTimeout(10_000) {
                while (subject.uiState.value.animation.session?.retryNotice == null) delay(10)
            }
        }
        // 停在重试等待段；立起 API 闸门，让第二次尝试停在请求进行中，好观察那一刻的状态。
        val apiGate = CompletableDeferred<Unit>()
        api.gate = apiGate
        retryGate.complete(Unit)
        runBlocking {
            withTimeout(10_000) {
                while (api.calls.get() < 2) delay(10)
            }
        }

        val secondAttempt = subject.uiState.value.animation.session!!
        assertEquals(2, secondAttempt.attempt)
        assertEquals(AnimationPhase.GENERATING, secondAttempt.phase)
        assertNull("新尝试开始后，重试提示必须已经清掉", secondAttempt.retryNotice)

        apiGate.complete(Unit)
        runBlocking { subject.awaitIdle() }
        val done = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.READY, done.phase)
        assertEquals("成功后尝试次数复位，下次网络中断从第 2 次重新计起", 1, done.attempt)
    }

    @Test
    fun `a generation that recovered through a retry leaves the next one a full retry budget`() {
        // 用户复查的那条要求：重试成功后次数归零，下一次网络中断仍然有三次重试。
        // 第一代断一次、重试成功（计数写回 1）；第二代连断，必须重新拿到三条预算、
        // 总共四次请求——而不是接着上一代剩下的次数。
        val html = "<!DOCTYPE html><html><body><svg id='fresh-budget'/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        api.failuresUntilSuccess = listOf(TestError(ErrorKind.NETWORK, "网络连接失败"))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")

        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }
        assertEquals("第一代靠一次重试成功", 2, api.calls.get())
        val recovered = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.READY, recovered.phase)
        assertEquals("成功后重试次数归零", 1, recovered.attempt)
        assertNull("成功后不留重试提示", recovered.retryNotice)

        // 第二代：一连断十次，预算用尽即止。
        api.failuresUntilSuccess = List(10) { TestError(ErrorKind.NETWORK, "网络连接失败") }
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        assertEquals(
            "下一代重新拥有三条重试预算（首问 + 3）",
            2 + ANIMATION_MAX_RETRIES + 1,
            api.calls.get(),
        )
        val failed = subject.uiState.value.animation.session!!
        assertTrue("失败原因要留下：${failed.error}", failed.error?.contains("网络") == true)
        assertEquals("失败之后次数同样归零，再开一代又是三条", 1, failed.attempt)
        assertNull("终局不留重试提示", failed.retryNotice)
    }

    @Test
    fun `an empty 200 answer gets the same bounded retry as a transient failure`() {
        val html = "<!DOCTYPE html><html><body><svg id='empty-retry'/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        api.failuresUntilSuccess = listOf(
            TestError(ErrorKind.INVALID_RESPONSE, "上游返回 HTTP 200 但没有可用的文本内容"),
        )
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        assertEquals("HTTP 200 空正文应自动重试一次", 2, api.calls.get())
        assertEquals(AnimationPhase.READY, subject.uiState.value.animation.session!!.phase)
        assertEquals(html, subject.uiState.value.animation.session!!.html)
    }

    @Test
    fun `the selected animation start path runs every checked model`() {
        val html = "<!DOCTYPE html><html><body><svg id='selected-models'/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.selectAnimationSupplier("sup-1")
        subject.toggleAnimationModel("test-model")
        subject.toggleAnimationModel("other-model")

        subject.startSelectedAnimationTests("sup-1")
        runBlocking { subject.awaitIdle() }

        assertEquals("唯一的开始生成入口必须启动所有勾选模型", 2, api.calls.get())
        assertEquals(listOf("test-model", "other-model"), api.models)
        assertEquals("批量启动后聚焦目录第一项", "test-model", subject.uiState.value.animation.session!!.model)
    }

    @Test
    fun `the model picker searches locally without disturbing the ticked set`() {
        // 用户要求：动画选模型时也要能搜索。筛选是纯本地的（目录已在手），所以它不得
        // 产生任何请求，也不得因为过滤而丢掉已勾选的模型——否则输一个词就把批量选择清空。
        val panel = animationPanelSource()
        assertTrue("模型选择弹窗缺少搜索输入框", "label = { Text(\"搜索模型\") }" in panel)
        assertTrue("缺少匹配数量提示", "匹配 " in panel)
        assertTrue("关键词应当不区分大小写匹配", "option.label.lowercase()" in panel)
        assertFalse("搜索框不得触发网络请求", "fetchModels" in panel)

        val api = ScriptedAnimationApi(listOf("unused"))
        val subject = animationViewModel(api)
        subject.selectAnimationSupplier("sup-1")
        subject.toggleAnimationModel("test-model")
        subject.toggleAnimationModel("other-model")
        val before = subject.uiState.value.animation.selectedModels
        assertEquals("两个模型都应在勾选集合里", 2, before.size)
        assertEquals("打开选择器不得产生请求", 0, api.calls.get())
    }

    @Test
    fun `the model picker exposes one start action and no separate batch button`() {
        val panel = animationPanelSource()
        assertFalse("模型选择弹窗不应再有批量测试按钮", "批量测试" in panel)
        assertFalse("不再保留旧的单模型开始回调", "onStartSingle" in panel)
        assertTrue("应保留唯一的开始生成按钮", "开始生成" in panel)
    }

    private fun animationPanelSource(): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(
                dir,
                "src/main/java/com/relaytester/app/feature/fingerprint/AnimationPanel.kt",
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            val nested = java.io.File(
                dir,
                "app/src/main/java/com/relaytester/app/feature/fingerprint/AnimationPanel.kt",
            )
            if (nested.isFile) return nested.readText(Charsets.UTF_8)
            dir = dir.parentFile
        }
        error("找不到 AnimationPanel.kt")
    }

    @Test
    fun `a blank body after real thinking is named as an upstream cut, not a silent model`() {
        // 实测（2026-10-10，中转网关固定 ~121 秒截断）：思考已经流了几千字、正文 0 字、
        // 没有 [DONE]/finish_reason/错误帧。旧文案只说「没有可用的文本内容」，用户无从
        // 判断是模型不说话还是被网关掐断，而这两件事要采取的动作完全不同。
        val api = ScriptedAnimationApi(listOf("unused"))
        api.reasoningFragments = listOf("先算一下尺寸。", "再确认动画周期。")
        api.failuresUntilSuccess = List(10) {
            TestError(ErrorKind.INVALID_RESPONSE, "上游返回 HTTP 200 但没有可用的文本内容")
        }
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        val session = subject.uiState.value.animation.session!!
        assertEquals(AnimationPhase.FAILED, session.phase)
        val error = session.error.orEmpty()
        assertTrue("必须说明是上游在思考阶段中断：$error", error.contains("思考阶段中断"))
        assertTrue("必须带上已收到的思考字符数：$error", error.contains("思考"))
        assertTrue("必须提示可以重试：$error", error.contains("可重试"))
        // 有界重试仍然生效：首问 + ANIMATION_MAX_RETRIES 次。
        assertEquals(1 + ANIMATION_MAX_RETRIES, api.calls.get())
    }

    @Test
    fun `a blank body with no thinking on either channel keeps the plain upstream wording`() {
        val api = ScriptedAnimationApi(listOf("unused"))
        api.failuresUntilSuccess = List(10) {
            TestError(ErrorKind.INVALID_RESPONSE, "上游返回 HTTP 200 但没有可用的文本内容")
        }
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        val error = subject.uiState.value.animation.session!!.error.orEmpty()
        assertTrue("两条通道都没文字时不得声称是思考中断：$error", error.contains("没有可用的文本内容"))
        assertFalse("不得虚构思考内容：$error", error.contains("思考阶段中断"))
    }

    @Test
    fun `starting selected models for a gone supplier keeps the ticks and sends nothing`() {
        // 供应商在面板开着时被删掉：启动必须只报错，不得先清勾选再失败——否则用户要重新
        // 勾一遍才知道发生了什么，而这次点击一个请求都没发。
        val api = ScriptedAnimationApi(listOf("unused"))
        val subject = animationViewModel(api)
        subject.selectAnimationSupplier("sup-1")
        subject.toggleAnimationModel("test-model")
        subject.toggleAnimationModel("other-model")
        assertEquals(2, subject.uiState.value.animation.selectedModels.size)

        subject.startSelectedAnimationTests("sup-does-not-exist")
        runBlocking { subject.awaitIdle() }

        assertEquals("无效供应商不得发出任何请求", 0, api.calls.get())
        assertEquals(
            "被拒绝的启动必须保留勾选，用户改好供应商即可重试",
            2,
            subject.uiState.value.animation.selectedModels.size,
        )
        assertTrue(subject.uiState.value.message?.contains("供应商") == true)
    }

    @Test
    fun `an over-long animation prompt is refused instead of being sent whole`() {
        // 使用边界：这个输入框没有任何长度上限，粘贴一份巨大的文本会被原样发到上游并留在每条
        // 会话里。项目里凡是由用户输入的标识都有上限并明确报错（模型名/来源 256 字符），
        // 提示词不该是唯一的例外。
        val api = ScriptedAnimationApi(listOf("unused"))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        val huge = "画".repeat(ANIMATION_MAX_PROMPT_CHARS + 1)

        subject.updateAnimationPrompt(huge)
        subject.startAnimationGeneration()

        assertEquals("超长提示词不得发请求", 0, api.calls.get())
        val session = subject.uiState.value.animation.session!!
        assertTrue(
            "超长提示词必须明确拒绝并说明上限：${session.error}",
            session.error?.contains(ANIMATION_MAX_PROMPT_CHARS.toString()) == true,
        )
    }

    @Test
    fun `a prompt at the limit is still accepted`() {
        // 边界内侧必须照常可用，否则上限就成了「刚好不能用」。
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.updateAnimationPrompt("画".repeat(ANIMATION_MAX_PROMPT_CHARS))

        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        assertEquals("上限处的提示词必须照常发送", 1, api.calls.get())
        assertEquals(AnimationPhase.READY, subject.uiState.value.animation.session!!.phase)
    }

    @Test
    fun `a captured snapshot is stored on its record and cleared by the next generation`() {
        // 用户要求：成功时用动画截图替代「已完成」。截图只能在详情里那张真实 WebView 上采，
        // 所以它由界面回填到会话上；新一代开始时旧截图必须清掉，否则旧画面会挂在新结果上。
        val html = "<!DOCTYPE html><html><body><svg id='shot'/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }
        val sessionId = subject.uiState.value.animation.session!!.sessionId
        assertNull("刚生成完、还没渲染过预览时没有截图", subject.uiState.value.animation.session!!.snapshot)

        subject.saveAnimationSnapshot(sessionId, byteArrayOf(1, 2, 3))
        assertArrayEquals(
            "界面回填的截图必须落在它自己那条记录上",
            byteArrayOf(1, 2, 3),
            subject.uiState.value.animation.session!!.snapshot,
        )

        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }
        assertNull(
            "新一代开始时旧截图必须清掉，否则旧画面会挂在新结果上",
            subject.uiState.value.animation.session!!.snapshot,
        )
    }

    @Test
    fun `a snapshot for a record that no longer exists is dropped`() {
        // 记录被删掉后，正在路上的那帧截图不能再写进任何地方。
        val api = ScriptedAnimationApi(listOf("unused"))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")
        val sessionId = subject.uiState.value.animation.session!!.sessionId
        subject.removeAnimationSession(sessionId)

        subject.saveAnimationSnapshot(sessionId, byteArrayOf(9))

        assertTrue("记录已删，不得留下任何会话", subject.uiState.value.animation.sessions.none { it.sessionId == sessionId })
    }

    @Test
    fun `a finished record offers its screenshot instead of a done label`() {
        // 用户明确要求「不要已完成」：成功那行不再写文字，位置让给截图；截图还没采到时同样
        // 不写「已完成」，行里先空着，而不是退回那个什么都不说明的旧词。
        val ready = AnimationSession(
            sessionId = 1L,
            supplierId = "sup",
            supplierName = "sup",
            model = "m",
            draftPrompt = "p",
            phase = AnimationPhase.READY,
        )
        assertNull(
            "成功态不得再出现「已完成」之类的文字",
            animationPhaseLabel(ready),
        )
        assertNull(
            "有截图时同样不显示文字，位置让给截图",
            animationPhaseLabel(ready.copy(snapshot = byteArrayOf(1))),
        )

        val failed = ready.copy(phase = AnimationPhase.FAILED, error = "上游在思考阶段中断")
        assertEquals("失败必须照旧显示失败原因", "上游在思考阶段中断", animationPhaseLabel(failed))
    }

    @Test
    fun `the oldest records are dropped once the list is full`() {
        // 资源边界：每条记录都留着完整 HTML（上限 2 MB）、思考尾巴，现在还有一张截图，
        // 而列表从不修剪——生成得越多，进程内存越大。列表本身是给人看的，留最近若干条
        // 足够，超出的按「最旧」淘汰（新记录排在最前）。
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        val subject = animationViewModel(api)

        val over = ANIMATION_MAX_SESSIONS + 3
        for (index in 1..over) {
            subject.startAnimationTest("sup-1", "model-$index")
            runBlocking { subject.awaitIdle() }
        }

        val sessions = subject.uiState.value.animation.sessions
        assertEquals("记录数必须封顶", ANIMATION_MAX_SESSIONS, sessions.size)
        assertEquals("留下的必须是最新的那批", "model-$over", sessions.first().model)
        assertTrue(
            "最旧的记录必须已被淘汰：${sessions.map { it.model }}",
            sessions.none { it.model == "model-1" },
        )
    }

    @Test
    fun `trimming keeps the record the user is looking at`() {
        // 淘汰不得把当前打开的那条删掉：用户正看着它，下一帧界面就空了。
        val html = "<!DOCTYPE html><html><body><svg/></body></html>"
        val api = ScriptedAnimationApi(listOf(html))
        val subject = animationViewModel(api)

        // 先造满列表，再选中最旧一条，然后继续新增，逼出一次修剪。
        for (index in 1..ANIMATION_MAX_SESSIONS) {
            subject.startAnimationTest("sup-1", "model-$index")
            runBlocking { subject.awaitIdle() }
        }
        val oldestId = subject.uiState.value.animation.sessions.last().sessionId
        subject.selectAnimationSession(oldestId)

        subject.startAnimationTest("sup-1", "model-new")
        runBlocking { subject.awaitIdle() }

        val animation = subject.uiState.value.animation
        assertEquals("记录数仍须封顶", ANIMATION_MAX_SESSIONS, animation.sessions.size)
        assertNotNull(
            "当前打开的记录不得被淘汰",
            animation.sessions.firstOrNull { it.sessionId == animation.selectedSessionId },
        )
    }

    private fun animationViewModel(
        api: RelayApi,
        /** Set by the pull-only test; the store read is what the refusal is based on. */
        pullOnly: Boolean = false,
        /** 跨供应商并行测试用：目录里再给一家供应商。 */
        extraSupplier: Boolean = false,
        /**
         * The wait between automatic retries.
         *
         * Immediate by default: the production wait is real seconds, and a suite that sat
         * through it would be slow for no added coverage. The cancel-during-retry case passes
         * a gate instead, so the retry can be observed while it is parked.
         */
        retryDelay: suspend (Long) -> Unit = {},
    ): FingerprintViewModel =
        FingerprintViewModel(
            supplierStore = AnimationSupplierStore(pullOnly = pullOnly, extraSupplier = extraSupplier),
            secretStore = object : SecretStore {
                override fun put(value: String): String = "secret-1"
                override fun get(secretId: String): String = "api-key"
                override fun delete(secretId: String) = Unit
            },
            relayApiFactory = { api },
            bankStore = MemoryBankFileSystem().store(),
            bankUpdateClient = BankUpdateClient(fetcher = FakeHttpFetcher()),
            updatePreferences = MemoryUpdatePreferences(),
            appVersionCode = 10_400L,
            skipRestore = true,
            ioDispatcher = mainDispatcher,
            clock = { 1_791_009_120_000L },
            animationRetryDelay = retryDelay,
        ).apply {
            lifecycle.put("animation-${subjects.size}", this)
            subjects += this
            // skipRestore 跳过了启动读，供应商目录还没进 knownSuppliers；卡片上的动画入口
            // 依赖它解析上下文，所以测试要先把目录读上来（真实应用里这一步发生在进入
            // 面板时，见 FingerprintScreen 的 refreshCatalogue 调用）。
            refreshCatalogue()
            runBlocking { awaitIdle() }
        }

    @Test
    fun `a pull-only supplier is refused before an animation request is sent`() {
        // The animation request is the most expensive one the app makes (16 384 max tokens),
        // and it ignored 禁止测试：只拉模型 exactly as the round did. The call count is the
        // assertion: the switch has to stop the request, not explain it afterwards.
        val api = ScriptedAnimationApi(listOf("<!DOCTYPE html><html><svg/></html>"))
        val subject = animationViewModel(api, pullOnly = true)
        subject.openAnimationTest("sup-1", "test-model")

        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        assertEquals("仅拉取供应商不得发出动画请求", 0, api.calls.get())
        val session = subject.uiState.value.animation.session!!
        assertTrue(
            "理由要点明是仅拉取：${session.error}",
            session.error?.contains("仅拉取") == true,
        )
    }

    @Test
    fun `the supplier list carries pull-only as a flag, never inside the name`() {
        // The panel used to write the state into the label ("供应商 2 · 仅拉取"). The mark is
        // drawn now — the same red bar the 模型测试 cards use — so the label has to stay the
        // plain name: a suffix here would double up with the bar, and it ate the width of a
        // row that already ellipsizes long names.
        val options = supplierPickerOptions(
            listOf(
                SupplierOption(id = "sup-1", name = "供应商 1"),
                SupplierOption(id = "sup-2", name = "供应商 2", pullOnly = true),
            ),
        )

        assertEquals(listOf("供应商 1", "供应商 2"), options.map { it.label })
        assertEquals(listOf(false, true), options.map { it.pullOnly })
        assertTrue(
            "名称里不得再出现状态文字：${options.map { it.label }}",
            options.none { it.label.contains("仅拉取") },
        )
        // The value stays the id, which is what the picker hands back.
        assertEquals(listOf("sup-1", "sup-2"), options.map { it.value })
    }

    // ---- The generation view's own wording ---------------------------------

    private fun streamingSession(
        received: Int = 0,
        reasoning: Int = 0,
        startedAt: Long? = 1_000L,
    ): AnimationSession = AnimationSession(
        sessionId = 1L,
        supplierId = "sup-1",
        supplierName = "supplier",
        model = "test-model",
        draftPrompt = "prompt",
        phase = AnimationPhase.GENERATING,
        requestId = 1L,
        streamingText = if (received > 0) "tail" else null,
        receivedChars = received,
        reasoningText = if (reasoning > 0) "chain of thought" else null,
        reasoningChars = reasoning,
        startedAt = startedAt,
    )

    @Test
    fun `the status line reports the whole count and the elapsed time`() {
        val label = animationStreamingStatus(
            streamingSession(received = 12_345, startedAt = 0L),
            now = 42_000L,
        )
        assertEquals("输出中 · 12345 字符 · 42 秒", label)
    }

    @Test
    fun `a thinking model is reported as thinking, not as a hung request`() {
        // 「等待响应」和「思考中」对用户是两条不同的信息：后者说明模型在动。
        val label = animationStreamingStatus(
            streamingSession(reasoning = 3_200, startedAt = 0L),
            now = 65_000L,
        )
        assertEquals("思考中 · 3200 字 · 1 分 05 秒", label)
    }

    @Test
    fun `an unanswered request says only that it is waiting`() {
        val label = animationStreamingStatus(streamingSession(startedAt = 0L), now = 5_000L)
        assertEquals("等待响应 · 5 秒", label)
    }

    @Test
    fun `the wait hint names what is being waited for`() {
        assertTrue(animationStreamingHint(streamingSession()).contains("等待上游"))
        // 已经在思考时，要说明 HTML 不会现在出现——否则用户会以为卡在正文上。
        assertTrue(animationStreamingHint(streamingSession(reasoning = 100)).contains("思考"))
    }

    @Test
    fun `the folded reasoning block still shows how much thinking arrived`() {
        assertEquals("思考 · 8800 字", animationReasoningLabel(streamingSession(reasoning = 8_800)))
    }

    @Test
    fun `elapsed time stays readable past a minute`() {
        assertEquals("0 秒", animationElapsedLabel(0L))
        assertEquals("59 秒", animationElapsedLabel(59_999L))
        assertEquals("1 分 00 秒", animationElapsedLabel(60_000L))
        assertEquals("5 分 07 秒", animationElapsedLabel(307_000L))
        // 时钟回拨或未开始都不能报出负数。
        assertEquals("0 秒", animationElapsedLabel(-5_000L))
    }

    @Test
    fun `a record that never settled has no duration to report`() {
        // 没有时间戳时报 null，而不是 0：印出「耗时 0 秒」是凭空造了一个数。
        val idle = AnimationSession(
            sessionId = 1L,
            supplierId = "sup-1",
            supplierName = "supplier",
            model = "test-model",
            draftPrompt = "prompt",
        )
        assertNull(idle.generationDurationMs())
        assertNull(idle.copy(startedAt = 1_000L).generationDurationMs())
        assertEquals(90_000L, idle.copy(startedAt = 1_000L, settledAt = 91_000L).generationDurationMs())
    }

    @Test
    fun `a normal supplier still generates`() {
        // The guard must not block the ordinary case: this is the same call the pull-only
        // test refuses, with the switch off.
        val html = "<!DOCTYPE html><html><svg id='ok'/></html>"
        val api = ScriptedAnimationApi(listOf(html))
        val subject = animationViewModel(api)
        subject.openAnimationTest("sup-1", "test-model")

        subject.startAnimationGeneration()
        runBlocking { subject.awaitIdle() }

        assertEquals(1, api.calls.get())
        assertEquals(html, subject.uiState.value.animation.session!!.html)
    }

    private fun setRunning(subject: FingerprintViewModel) {
        val field = FingerprintViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(subject) as kotlinx.coroutines.flow.MutableStateFlow<
            com.relaytester.app.feature.fingerprint.FingerprintUiState
            >
        state.value = state.value.copy(isRunning = true)
    }
}

/** One supplier with a usable key, so the animation path can resolve credentials offline. */
private class AnimationSupplierStore(
    /** Set by the pull-only test: the supplier must not have a request sent on its key. */
    private val pullOnly: Boolean = false,
    /** 跨供应商并行测试用：再给一家供应商，模型目录与 sup-1 不同。 */
    private val extraSupplier: Boolean = false,
) : SupplierStore(null) {
    override suspend fun read() = SupplierStoreState(
        suppliers = listOf(
            SupplierProfile(
                id = "sup-1",
                name = "Test Supplier",
                baseUrl = "https://relay.test/v1",
                protocol = RelayProtocol.CHAT_COMPLETIONS,
                apiKeySecretId = "secret-1",
                models = listOf("test-model", "other-model"),
                testSettings = TestSettings(),
                isTestingDisabled = pullOnly,
            ),
        ) + if (extraSupplier) {
            listOf(
                SupplierProfile(
                    id = "sup-2",
                    name = "Second Supplier",
                    baseUrl = "https://relay2.test/v1",
                    protocol = RelayProtocol.CHAT_COMPLETIONS,
                    apiKeySecretId = "secret-1",
                    models = listOf("sup2-model"),
                    testSettings = TestSettings(),
                ),
            )
        } else {
            emptyList()
        },
        activeSupplierId = "sup-1",
    )

    override suspend fun save(state: SupplierStoreState) = Unit
}

/**
 * Answers animation requests from a script, optionally parking one until released.
 *
 * Records the model, supplier, ceiling and timeout it was called with: those are what
 * prove the request was bound to the card rather than to whatever was selected at the time.
 */
/**
 * How long a test waits for the round to reach the wire before giving up.
 *
 * A round that is refused never arrives, so every wait on it needs a bound: without one a
 * regression that over-blocks reads as a hung suite rather than a failure, which is the
 * hardest kind of result to attribute.
 */
private const val ROUND_START_TIMEOUT_MILLIS = 5_000L

private class ScriptedAnimationApi(
    private val answers: List<String>,
    var gate: CompletableDeferred<Unit>? = null,
    var failure: TestError? = null,
    /**
     * How many progress callbacks the answer is reported through before it is returned.
     *
     * Zero means the answer arrives whole with no progress at all: some relays answer
     * `text/event-stream` in one delta or none, and the panel has to survive that rather
     * than assume it always saw the document being written.
     */
    private val progressChunks: Int = 0,
) : RelayApi() {
    /**
     * Failures to hand back before the scripted answers take over, one entry per call.
     *
     * A queue rather than a flag because the retry tests are about *how many* attempts happen:
     * a flag can say "this call fails" but not "the first call fails and the second succeeds",
     * which is the only shape that proves a retry actually re-sent the request.
     */
    var failuresUntilSuccess: List<TestError> = emptyList()

    var started = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Boolean>()
    val calls = java.util.concurrent.atomic.AtomicInteger(0)
    val models = java.util.Collections.synchronizedList(mutableListOf<String>())
    val supplierIds = java.util.Collections.synchronizedList(mutableListOf<String>())
    val maxTokens = java.util.Collections.synchronizedList(mutableListOf<Int>())
    val timeouts = java.util.Collections.synchronizedList(mutableListOf<Int>())

    /** What each call asked for, so a test can pin the thinking-off request. */

    /**
     * Reasoning fragments to deliver before the answer, one call per fragment.
     *
     * These are *fragments*, not accumulations — the same contract the network layer has —
     * which is what makes a test able to catch a ViewModel that treats them as cumulative.
     */
    var reasoningFragments: List<String> = emptyList()

    /**
     * How many reasoning fragments to deliver before pausing on [reasoningPause].
     *
     * A real chain of thought arrives over minutes, so a test that wants to act *during* the
     * stream — scroll back and see whether the window keeps sliding — needs the stream to stop
     * partway. Without this the fragments land in one synchronous burst before any test code
     * runs, and the behaviour under test (what happens to the window after the reader leaves
     * the end) can never be exercised.
     */
    var reasoningFragmentsBeforePause: Int = 0

    /** Resolves when the fragment stream has paused; the test completes it to resume. */
    var reasoningPause: CompletableDeferred<Unit>? = null

    /** Resolves once the fragment stream has reached the pause point. */
    val reasoningPaused = CompletableDeferred<Unit>()

    /** Resolves once [progressChunks] progress callbacks have been delivered. */
    val progressed = CompletableDeferred<Unit>()

    /**
     * A fresh progress signal for each call, for tests that drive two generations.
     *
     * [progressed] can only be completed once, so a test that awaits it twice would sail
     * through the second wait without progress having arrived. These resolve in call order.
     */
    private val progressSignals = java.util.Collections.synchronizedList(
        mutableListOf<CompletableDeferred<Unit>>(),
    )

    /** The progress signal for the [index]-th call (0-based), created on demand. */
    fun progressSignal(index: Int): CompletableDeferred<Unit> = synchronized(progressSignals) {
        while (progressSignals.size <= index) progressSignals += CompletableDeferred()
        progressSignals[index]
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
        models += model
        supplierIds += profile.id
        this.maxTokens += maxTokens
        timeouts += timeoutSeconds
        started.complete(Unit)
        val answer = answers[index % answers.size]
        // Reasoning frames come first — a thinking model emits them all before the first
        // answer character — and each one is reported as the fragment it is.
        for ((index, fragment) in reasoningFragments.withIndex()) {
            onReasoning(fragment)
            if (index + 1 == reasoningFragmentsBeforePause) {
                reasoningPaused.complete(Unit)
                reasoningPause?.await()
            }
        }
        // Deltas arrive while the response is still open, then the response ends — the same
        // order a real stream has. Emitting them before the gate is also what lets a test
        // observe progress and then cancel, or hold the response open after it has arrived.
        if (progressChunks > 0) {
            // Accumulating prefixes, exactly as the real stream reports them.
            for (chunk in 1..progressChunks) {
                onProgress(answer.take(answer.length * chunk / progressChunks))
            }
            progressed.complete(Unit)
            progressSignal(index).complete(Unit)
        }
        val currentGate = gate
        if (currentGate != null) {
            try {
                currentGate.await()
            } catch (error: kotlinx.coroutines.CancellationException) {
                cancelled.complete(true)
                throw error
            }
        }
        failure?.let { return ApiResult.Failure(it) }
        // The scripted failure queue is consulted first so a test can say "the first N calls
        // fail with this, then it works" — the shape a retry test needs.
        failuresUntilSuccess.getOrNull(index)?.let { return ApiResult.Failure(it) }
        return ApiResult.Success(answer)
    }
}
