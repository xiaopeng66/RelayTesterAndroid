package com.relaytester.app.core.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** A redirect that would hand the request (and its credentials) to another origin. */
internal class RedirectRefusedException(message: String) : IOException(message)

/** The 3xx codes that carry a `Location` this app is willing to consider following. */
internal val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

/** How many same-origin hops are tolerated before a redirect loop is declared. */
internal const val MAX_REDIRECT_HOPS = 3

/**
 * Whether [this] URL points at the same origin — scheme, host and port — as [base].
 *
 * A redirect that stays on the same origin cannot change who receives the API key, so
 * it is safe to follow. Anything else can: the destination is chosen by whoever answered
 * the request, not by the user who configured the supplier.
 */
internal fun HttpUrl.sameOriginAs(base: HttpUrl): Boolean =
    scheme == base.scheme && host == base.host && port == base.port

/**
 * The URL a `Location: [location]` on [base] may be followed to, or null when it may not.
 *
 * Null covers both an unparseable `Location` and one that leaves [base]'s origin; both
 * mean the same thing to the caller, which is "do not re-send the credentials there".
 */
internal fun sameOriginRedirect(base: HttpUrl, location: String): HttpUrl? =
    base.resolve(location)?.takeIf { it.sameOriginAs(base) }

/**
 * The per-request client shape both API clients use: redirects off, timeouts scaled.
 *
 * Redirects are turned off so [executeSameOrigin] can enforce the origin policy itself;
 * OkHttp's default would silently re-send `Authorization`/`x-api-key` to any host a 3xx
 * names, and would downgrade https to http if asked. Connect/read/write are all set because
 * scaling only one of them leaves OkHttp's 10-second default in force for the others.
 *
 * [callSeconds] bounds the whole call and is therefore a **different kind** of limit from
 * the other three: it counts the upstream's thinking time too, not just dead air. For a
 * streaming completion that is the wrong watchdog — measured on a real thinking model, the
 * upstream sat silent for 30–58 s before its response headers and then needed 850 s in total
 * to produce the document, all of it healthy (bytes kept arriving). A whole-call bound of
 * 605 s killed that request mid-thought, which is exactly the "思考到一半就失败" report.
 * So callers pass a whole-call bound only where the total is genuinely known-bounded, and
 * rely on `readSeconds` (a per-read window that any arriving byte resets) to notice a link
 * that has actually gone silent. Pass [NO_CALL_TIMEOUT] to leave the call unbounded.
 */
internal fun OkHttpClient.forSameOriginRequests(
    connectSeconds: Long,
    readSeconds: Long,
    writeSeconds: Long,
    callSeconds: Long = NO_CALL_TIMEOUT,
): OkHttpClient = newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(connectSeconds, TimeUnit.SECONDS)
    .readTimeout(readSeconds, TimeUnit.SECONDS)
    .writeTimeout(writeSeconds, TimeUnit.SECONDS)
    .apply {
        // Zero is OkHttp's own encoding for "no whole-call timeout"; the other three
        // timeouts still apply, so this is not "no limits" — it is "no bound on the total".
        callTimeout(if (callSeconds == NO_CALL_TIMEOUT) 0L else callSeconds, TimeUnit.SECONDS)
    }
    .build()

/**
 * [forSameOriginRequests]'s encoding for "do not bound the whole call".
 *
 * OkHttp reads a zero call timeout as unbounded, and its builder takes a primitive `long`,
 * so a marker is clearer at the call site than a bare `0L` that looks like a bug.
 */
internal const val NO_CALL_TIMEOUT = 0L

/**
 * Follows same-origin redirects only, refusing everything else.
 *
 * The caller must still close the returned [Response]. A refusal throws
 * [RedirectRefusedException] with a message safe to show; the response it was deciding
 * about is closed first.
 */
internal suspend fun OkHttpClient.executeSameOrigin(request: Request): Response {
    var current = request
    var hops = 0
    while (true) {
        val response = newCall(current).awaitResponse()
        val location = if (response.code in REDIRECT_CODES) response.header("Location") else null
        if (location == null) return response
        val target = sameOriginRedirect(current.url, location)
        if (target == null) {
            response.close()
            val where = current.url.resolve(location)
                ?.let { "${it.scheme}://${it.host}" }
                ?: "无效地址"
            throw RedirectRefusedException("上游要求跳转到 $where，出于密钥安全已拒绝")
        }
        response.close()
        if (++hops > MAX_REDIRECT_HOPS) {
            throw RedirectRefusedException("上游重定向次数过多，已停止")
        }
        current = current.newBuilder().url(target).build()
    }
}

/**
 * Awaits a call without blocking a thread, cancelling the call if the coroutine is.
 *
 * Shared by both API clients so the cancellation contract — a cancelled coroutine
 * aborts the in-flight OkHttp call rather than leaving it running — is defined once.
 */
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                response.close()
            }
        }
    })
}
