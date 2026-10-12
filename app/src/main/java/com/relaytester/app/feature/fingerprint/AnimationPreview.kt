package com.relaytester.app.feature.fingerprint

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/**
 * Renders generated HTML in a WebView with the sandbox applied by the application.
 *
 * The document is untrusted: the prompt asks the model not to reach the network, but a
 * prompt is an instruction to a model, not a control on a page. Every restriction below is
 * enforced here, in native code, and the page is not given a single capability it could
 * use to escape the preview:
 *
 *  * no `addJavascriptInterface` and no native bridge, so the page cannot call into the app;
 *  * no credentials, supplier URL or app object is injected into the page in any form;
 *  * file and content access are off, so `file://` and `content://` are unreachable;
 *  * no base URL of the page's own origin — a fixed, credential-free `https://…invalid/`
 *    base is used, so a relative URL has nothing real to resolve against;
 *  * every non-inline resource request is refused, so scripts, styles, fonts, frames and
 *    media cannot be fetched from anywhere;
 *  * navigation (top-level and in frames) is blocked outright — there is exactly one
 *    document, and it is the one the app loaded;
 *  * new windows, downloads, file chooser, geolocation and JS dialogs are refused;
 *  * DOM storage is off, so nothing the page writes survives it.
 *
 * The page is still untrusted code running in-process: this is a restricted view, not a
 * security boundary against a determined attacker with a WebView exploit, and it is not
 * described as one. A runaway script can still burn CPU and memory; the app reacts to the
 * renderer dying ([onRenderFailure]) rather than pretending it cannot happen.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AnimationPreview(
    html: String,
    /** Bumped by 重新播放 to reload the same document without another model request. */
    replayToken: Long,
    /** Called when the renderer process dies, so the panel can offer a native recovery. */
    onRenderFailure: (String) -> Unit,
    /** Called once the document has finished loading, so a load error can be surfaced. */
    onLoadError: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Reports the live view once it exists, and null when it is torn down.
     *
     * The preview is the only place the animation is actually rendered, so recording it for a
     * GIF means drawing *this* view — there is no frame API on a WebView. Handing the view out
     * (rather than recording internally) keeps the sandbox's teardown rules in one place: the
     * caller must stop using it when null arrives, which is exactly when `releasePreview` runs.
     */
    onViewAvailable: (View?) -> Unit = {},
) {
    // One WebView per loaded document, keyed on the replay token: 重新播放 must re-run the
    // page from the top, and re-creating the view is the only way to guarantee the old
    // script state is gone rather than resumed.
    key(replayToken) {
        val callbacks = remember { PreviewCallbacks() }
        // The latest handlers, read at call time so a recomposition does not need to tear
        // down and rebuild the WebView just to hand it a new lambda.
        callbacks.onRenderFailure = onRenderFailure
        callbacks.onLoadError = onLoadError
        AndroidView(
            modifier = modifier,
            factory = { context ->
                WebView(context).apply {
                    configureSandbox()
                    webChromeClient = sandboxChromeClient(callbacks)
                    webViewClient = sandboxWebViewClient(callbacks)
                    loadSandboxDocument(html)
                    onViewAvailable(this)
                }
            },
            update = { view ->
                // The document is bound to the composable by `key`; an update only refreshes
                // the handlers, never the content, so a recomposition cannot re-run the page.
                callbacks.onRenderFailure = onRenderFailure
                callbacks.onLoadError = onLoadError
                view.configureSandbox()
            },
            // The view is destroyed, not merely detached. Leaving it to the parent to drop
            // leaked a renderer per replay and per screen change: the WebView holds a
            // renderer process, and an untrusted document that is never torn down keeps
            // whatever it allocated. `onRelease` runs before the view leaves the hierarchy,
            // which is where `releasePreview` (stop the load, detach both clients, destroy)
            // belongs; doing it in `onDispose` would be after that point.
            onRelease = { view ->
                callbacks.disposed = true
                // Reported before the view is destroyed, so a recorder holding it cannot draw
                // a released WebView.
                onViewAvailable(null)
                view.releasePreview()
            },
        )
        DisposableEffect(replayToken) {
            // Belt-and-braces for the paths `onRelease` does not cover (a key change disposes
            // the old subtree): the flag alone is enough to silence the callbacks, and
            // `onRelease` owns the destruction so it never runs twice.
            onDispose { callbacks.disposed = true }
        }
    }
}

/** Mutable handler holder, so the WebView keeps working across recompositions. */
private class PreviewCallbacks {
    var onRenderFailure: (String) -> Unit = {}
    var onLoadError: (String) -> Unit = {}

    /** Set on dispose: a page that finishes loading after this must not report anything. */
    var disposed: Boolean = false
}

/**
 * Applies every restriction. Called on create and again on update, so a setting cannot be
 * left on by anything that touched the view in between.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureSandbox() {
    settings.apply {
        // JavaScript is on because an SVG animation legitimately needs it; every capability
        // that would let that script leave the document is switched off around it.
        javaScriptEnabled = true
        // Not a bridge: no native object is exposed to the page in any form.
        // (No addJavascriptInterface call exists anywhere in this file.)
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        // No network: no resource is loaded from the network, and the resource hook below
        // refuses anything that still tries.
        blockNetworkLoads = true
        blockNetworkImage = true
        // A page cannot move itself, open windows or store anything on the device.
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        domStorageEnabled = false
        databaseEnabled = false
        setGeolocationEnabled(false)
        // No app-configured proxy, no mixed content, no zoom state carried between loads.
        mediaPlaybackRequiresUserGesture = true
        cacheMode = WebSettings.LOAD_NO_CACHE
        // The document is UTF-8 by construction (it is a Kotlin String); saying so avoids a
        // second guess at the charset and keeps non-ASCII SVG text correct.
        defaultTextEncodingName = "utf-8"
        // Lay the page out at a desktop width and then scale the result down to fit the
        // view. Without this the layout viewport is the phone's own width, so a document
        // written for a desktop canvas — which is what the prompt asks for — lays out
        // narrow and tall, and a scene drawn to a wide viewBox is cut off at the sides or
        // pushed out of view entirely. `useWideViewPort` selects the standard desktop
        // viewport (980 px); `loadWithOverviewMode` zooms the whole thing out so the entire
        // document is on screen at once, which is the point of a preview.
        useWideViewPort = true
        loadWithOverviewMode = true
    }
    // Long-press selection of the page is harmless but its context menu is not: it can link
    // out. Nothing here is selectable for a reason — the source view is where reading is done.
    isLongClickable = false
    isHapticFeedbackEnabled = false
}

/**
 * Loads [html] under the isolated base URL, with the app's own CSP injected ahead of it.
 *
 * `loadDataWithBaseURL` is the only loader that gives the page a document without a file
 * path; the base is a fixed `invalid` origin so nothing relative resolves to a real host.
 * The CSP is prepended as a meta tag inside the head: it is the app's policy, not the
 * model's, and it is the outer layer of a defence that does not rely on it alone.
 */
private fun WebView.loadSandboxDocument(html: String) {
    val hardened = injectFitStylesheet(injectCsp(html))
    loadDataWithBaseURL(
        ANIMATION_PREVIEW_BASE_URL,
        hardened,
        "text/html",
        "utf-8",
        null,
    )
}

/**
 * A stylesheet that makes the document fill the preview instead of floating in it.
 *
 * Two failures were reported against the plain sandbox, and both are the same root cause —
 * the page's own box did not match the viewport it was given:
 *
 *  * **A blank pane.** A document whose `body` never establishes a height (a bare `<svg>`
 *    with a `viewBox` and nothing setting a height on it) lays out at its intrinsic size; the
 *    wide-viewport settings then zoom the whole page, and what lands on screen is a corner of
 *    white space. There is nothing wrong with the document — `img`-less SVG with a viewBox
 *    relies on the CSS box for its size, and no box was ever given one.
 *  * **A tiny drawing.** A document that hard-codes `width: 800px; height: 600px` on its
 *    wrapper is laid out at 980 px desktop width and then scaled to fit, so the art shrinks
 *    along with the page. Giving the root a viewport-sized box and letting the body centre it
 *    keeps the scene at a readable size.
 *
 * The root box is anchored with `position: fixed; inset: 0` rather than `height: 100%`, and
 * that part is load-bearing rather than taste. A percentage height needs a *definite* height on
 * its containing block, and in this WebView — a `data:` document under `loadDataWithBaseURL`
 * with the desktop layout viewport on — nothing ever establishes one: `html`, `body` and the
 * SVG each computed to `height: 0px` while the viewport was a perfectly usable 299×220, so the
 * page laid out correctly and painted into a zero-height box. That is the blank pane, measured
 * on the device rather than guessed at. `position: fixed` resolves against the viewport, the
 * one definite height the engine has: it gave the full 220 px where `height: 100%`,
 * `min-height: 100%` and even `100vh` all collapsed to 0. Do not "simplify" this to percentages.
 *
 * The full stylesheet is only for SVG documents, whose art wants the viewport-filling,
 * centred, no-scroll box. The feature now takes any "generate an HTML page" prompt, and a
 * general page manages its own layout — flex-centring it and hiding its overflow would crop
 * or distort what it meant to show. Those get the same root anchor (`position: fixed;
 * inset: 0`, which fixes the definite-height collapse for them too) but keep scrolling, and
 * no sizing is forced on their content.
 *
 * Applied as the *last* `<style>` in the head so it wins over the document's own rules at
 * equal specificity — the app is the one that knows what size the pane actually is, and the
 * document cannot know it. `!important` is avoided on purpose: it would also flatten a
 * deliberate animation on these properties, and `html`/`body` sizing is not something a
 * legitimate SVG animation sets.
 */
internal fun injectFitStylesheet(html: String): String {
    val css = if (html.contains("<svg", ignoreCase = true)) {
        """
        html, body {
          position: fixed;
          inset: 0;
          margin: 0;
          padding: 0;
          width: 100%;
          height: 100%;
          overflow: hidden;
          background: transparent;
        }
        body {
          display: flex;
          align-items: center;
          justify-content: center;
        }
        body > svg {
          width: 100% !important;
          height: 100% !important;
          max-width: 100%;
          max-height: 100%;
          display: block;
        }
        """.trimIndent()
    } else {
        """
        html, body {
          position: fixed;
          inset: 0;
          margin: 0;
          padding: 0;
          background: transparent;
          overflow: auto;
        }
        """.trimIndent()
    }
    val tag = "<style>$css</style>"
    val headIndex = realHeadIndex(html)
    if (headIndex < 0) return html
    val headEnd = html.indexOf('>', headIndex)
    if (headEnd < 0) return html
    // Append before `</head>` when there is one, so this is the last stylesheet of the head
    // and outranks the document's own rules; otherwise after the opening tag.
    val close = html.lowercase().indexOf("</head>", headEnd)
    val at = if (close >= 0) close else headEnd + 1
    return html.substring(0, at) + tag + html.substring(at)
}

/**
 * The app-controlled content policy, prepended to the document.
 *
 * `connect-src 'none'` blocks fetch/XHR/WebSocket, `frame-src`/`object-src`/`form-action`
 * block embedding and posting, `base-uri 'none'` blocks base rewriting, and only inline
 * script and style are allowed so an external file has no way in. This is layered on top of
 * the WebView settings above rather than trusted in place of them.
 */
internal fun injectCsp(html: String): String {
    val policy = buildString {
        append("default-src 'none'; ")
        append("script-src 'unsafe-inline'; ")
        append("style-src 'unsafe-inline'; ")
        append("img-src data:; ")
        append("font-src 'none'; ")
        append("connect-src 'none'; ")
        append("media-src 'none'; ")
        append("frame-src 'none'; ")
        append("child-src 'none'; ")
        append("worker-src 'none'; ")
        append("object-src 'none'; ")
        append("form-action 'none'; ")
        append("base-uri 'none'; ")
        append("frame-ancestors 'none'")
    }
    val meta = """<meta http-equiv="Content-Security-Policy" content="$policy">"""
    val headIndex = realHeadIndex(html)
    if (headIndex < 0) {
        // No head to put it in: wrap the meta and the document so the policy still applies
        // from the document's very first byte.
        return "<!DOCTYPE html><html><head>$meta</head><body>$html</body></html>"
    }
    val headEnd = html.indexOf('>', headIndex)
    if (headEnd < 0) return html
    return html.substring(0, headEnd + 1) + meta + html.substring(headEnd + 1)
}

/**
 * The index of the `<head` that actually opens the document's head element.
 *
 * A plain `indexOf("<head")` matches text inside a comment or a script, and the model writes
 * both: a document with `<!-- <head> -->` above its real head had the policy injected into
 * the comment, where it applies to nothing, and the preview silently ran with no CSP at all.
 * Comments are skipped here, and a `<head` inside one is not a candidate. The scan is
 * deliberately small — it looks for the first `<head` that is not inside a comment or a
 * `<script>`/`<style>` body — because anything more is an HTML parser, and the fallback
 * (wrapping the document) already produces a correct result when this finds nothing.
 */
internal fun realHeadIndex(html: String): Int {
    val lower = html.lowercase()
    var index = 0
    while (index < html.length) {
        val next = lower.indexOf('<', index)
        if (next < 0) return -1
        when {
            lower.startsWith("<!--", next) -> {
                // Skip the whole comment, terminator included.
                val end = lower.indexOf("-->", next + 4)
                if (end < 0) return -1
                index = end + 3
            }
            lower.startsWith("<script", next) || lower.startsWith("<style", next) -> {
                // A `<head` inside a script or style body is text, not markup.
                val tag = if (lower.startsWith("<script", next)) "script" else "style"
                val end = lower.indexOf("</$tag", next)
                if (end < 0) return -1
                index = end
            }
            lower.startsWith("<head", next) -> return next
            else -> index = next + 1
        }
    }
    return -1
}

/**
 * Whether the preview may serve a resource with this URL.
 *
 * Two things may be served, and nothing else:
 *
 *  * `data:` — the document itself. `loadDataWithBaseURL` hands the renderer the page as a
 *    `data:` URL, and the main-frame request carries **that** URL, not the base. Without
 *    this branch the sandbox blocks the app's own document and the pane shows the engine's
 *    failure page (`ERR_HTTP_RESPONSE_CODE_FAILURE`, observed on device) instead of the
 *    animation. Inline images come through the same scheme, which the CSP already allows
 *    (`img-src data:`); a `data:` URL cannot reach the network, and navigation is refused
 *    separately in [sandboxWebViewClient], so admitting it grants no way out of the page.
 *  * the fixed [ANIMATION_PREVIEW_BASE_URL] — the base other engines report for the
 *    document, kept so the document is admitted whichever URL the request arrives under.
 *
 * Everything else — `http(s)`, `file`, `content`, and every relative URL resolved against
 * the base — is a resource the page was never allowed to have.
 */
internal fun previewRequestAllowed(url: String): Boolean =
    url == ANIMATION_PREVIEW_BASE_URL || url.startsWith("data:")

/**
 * The client that decides what may load and where the page may go.
 *
 * Every URL decision other than the document itself returns a refusal. A blank response is
 * used rather than null so the page sees an empty resource it cannot use, instead of a
 * half-taken request.
 */
private fun sandboxWebViewClient(callbacks: PreviewCallbacks): WebViewClient =
    object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url?.toString().orEmpty()
            // Only the loaded document itself and its own inline data may be rendered.
            return if (previewRequestAllowed(url)) null else blockedResource()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError,
        ) {
            // The main frame only. A refused sub-resource (a script or image the page tried
            // to fetch from the network) also lands here on some engines, and reporting that
            // would mark a perfectly working page as failed — the refusal is the intended
            // behaviour, not an error the user needs to hear about.
            if (!request.isForMainFrame || callbacks.disposed) return
            callbacks.onLoadError("页面未能载入：${error.description}")
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            // Any navigation, top-level or frame, is refused: the page has no legitimate
            // destination, and letting it through would hand the untrusted document a way
            // to reach the network or another app.
            // Silenced once disposed, like the two callbacks above: a torn-down preview must
            // not write a failure into a panel that has already moved on.
            if (!callbacks.disposed) {
                callbacks.onLoadError("页面试图跳转到外部地址，已被拦截")
            }
            return true
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            // The renderer died (usually a runaway script). Returning true stops the app
            // from being killed with it; the panel shows a native recovery instead.
            if (!callbacks.disposed) {
                callbacks.onRenderFailure(
                    if (detail.didCrash()) "页面渲染进程崩溃" else "系统回收了页面渲染进程",
                )
            }
            view.destroy()
            return true
        }
    }

/** An empty, uncacheable response for a resource the page may not have. */
private fun blockedResource(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "utf-8",
    ByteArrayInputStream(ByteArray(0)),
).apply {
    setStatusCodeAndReasonPhrase(403, "Blocked")
}

/**
 * The chrome client: every capability a page can ask for is refused here.
 *
 * A page that asks for the file chooser, geolocation, a permission, a JS dialog or a new
 * window gets nothing and is not told why — there is no interaction the preview supports
 * beyond rendering itself.
 */
private fun sandboxChromeClient(callbacks: PreviewCallbacks): WebChromeClient =
    object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: android.webkit.GeolocationPermissions.Callback,
        ) {
            callback.invoke(origin, false, false)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            filePathCallback.onReceiveValue(null)
            return true
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message,
        ): Boolean = false

        override fun onJsAlert(
            view: WebView,
            url: String,
            message: String,
            result: android.webkit.JsResult,
        ): Boolean {
            result.cancel()
            return true
        }

        override fun onJsConfirm(
            view: WebView,
            url: String,
            message: String,
            result: android.webkit.JsResult,
        ): Boolean {
            result.cancel()
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String,
            message: String,
            defaultValue: String,
            result: android.webkit.JsPromptResult,
        ): Boolean {
            result.cancel()
            return true
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            // Nothing to report: the panel owns progress, and a page that is merely slow
            // must not look like a failed request.
        }

        override fun onReceivedTitle(view: WebView, title: String?) = Unit
    }

/**
 * Tears the view down completely.
 *
 * Loads are stopped before the view is destroyed so a late callback cannot touch a dead
 * WebView, and the client is detached so nothing arrives afterwards at all. Called from the
 * preview's `onRelease`.
 *
 * There is deliberately no separate pause/resume pair: the panel stops the animation by
 * taking the preview out of the composition when the app leaves the foreground
 * ([AnimationPanel]'s `foreground` gate), and that path ends in this function. `pauseTimers`
 * on a view that is about to be destroyed would add nothing, and the two functions that
 * used to sit here had no caller at all.
 */
fun WebView.releasePreview() {
    stopLoading()
    loadUrl("about:blank")
    webChromeClient = null
    webViewClient = WebViewClient()
    clearHistory()
    removeAllViews()
    destroy()
}

/** Not used by the preview, but kept so a future caller cannot accidentally fetch icons. */
internal fun noOpBitmap(): Bitmap? = null
