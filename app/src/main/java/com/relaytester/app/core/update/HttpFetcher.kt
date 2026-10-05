package com.relaytester.app.core.update

import com.relaytester.app.core.fingerprint.hexDigest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Fetches bytes over the network.
 *
 * An interface so an update flow can be tested without a server, and so the device
 * end-to-end run can point at a local endpoint. Shared by both feeds: the detection
 * package (its manifest and the package itself) and the app's own release feed.
 */
interface HttpFetcher {
    /**
     * GETs [url], refusing a body larger than [maxBytes].
     *
     * [onProgress] is called as the body arrives, starting with a zero-byte reading that
     * carries the declared length. It is a default so a small JSON fetch — a few hundred
     * bytes nobody waits on — does not have to pass an empty lambda at every call site.
     */
    suspend fun fetch(
        url: String,
        maxBytes: Int,
        onProgress: (DownloadProgress) -> Unit = {},
    ): ByteArray

    /**
     * Streams [url] into [target] and returns the file it wrote.
     *
     * Separate from [fetch] because an APK is tens of megabytes: holding one in memory to
     * then write it out would double the peak, and the panel would have nothing to show
     * until the last byte arrived. The bytes go to `target.part` first (never to the target
     * itself), [expectation] is checked against the part file, and only then is it renamed —
     * so the name the system installer is handed never refers to bytes that failed a check.
     */
    suspend fun downloadTo(
        url: String,
        target: File,
        maxBytes: Long,
        expectation: DownloadExpectation? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File
}

/**
 * What a download has to turn out to be.
 *
 * Checked on the part file *before* the rename, so a truncated or tampered download cannot
 * even briefly occupy the target name: the install is handed that name, and a process kill in
 * the window between "renamed" and "verified" would otherwise leave the wrong bytes there.
 */
data class DownloadExpectation(
    /** Null when the feed did not state a size. */
    val sizeBytes: Long? = null,
    /** Null when the feed did not state a digest; compared case-insensitively. */
    val sha256: String? = null,
)

/**
 * [HttpFetcher] over OkHttp.
 *
 * Timeouts are deliberately short and separate from the relay client's: these are
 * downloads from fixed hosts, and one that stalls has to fail rather than hold a spinner.
 * The call is enqueued rather than run on the calling thread so cancelling the work
 * actually closes the connection.
 *
 * Redirects are followed *by hand*: OkHttp's own follower would take the request to whatever
 * host a 3xx names, and these downloads come from a feed whose URL field an attacker can
 * edit. Each hop is put through [UpdateHosts] — HTTPS only, release hosts only, at most
 * [UpdateHosts.MAX_UPDATE_REDIRECT_HOPS] of them — which is what a same-origin rule cannot
 * express here, because GitHub's asset hop legitimately changes host.
 */
class OkHttpFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build(),
) : HttpFetcher {
    override suspend fun fetch(
        url: String,
        maxBytes: Int,
        onProgress: (DownloadProgress) -> Unit,
    ): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, application/octet-stream")
            .build()
        return suspendCancellableCoroutine { continuation ->
            // A CancellableContinuation holds exactly one cancellation handler: asking for a
            // second is refused, and the refusal would be thrown on OkHttp's callback thread —
            // taking the process down instead of failing the download. One handler for the
            // whole chain, then, and each hop swaps the call it cancels.
            val inFlight = AtomicReference<Call?>(null)
            continuation.invokeOnCancellation { inFlight.get()?.cancel() }
            follow(continuation, inFlight, request, hop = 0) { response -> readBody(response, maxBytes, onProgress) }
        }
    }

    override suspend fun downloadTo(
        url: String,
        target: File,
        maxBytes: Long,
        expectation: DownloadExpectation?,
        onProgress: (DownloadProgress) -> Unit,
    ): File {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .build()
        return suspendCancellableCoroutine { continuation ->
            val inFlight = AtomicReference<Call?>(null)
            continuation.invokeOnCancellation { inFlight.get()?.cancel() }
            follow(continuation, inFlight, request, hop = 0) { response ->
                writeBody(response, target, maxBytes, expectation, onProgress)
            }
        }
    }

    /**
     * Runs [request], following an allowed redirect, and hands the final response to [finish].
     *
     * The response is closed by whoever consumes it (`finish` runs inside `use`), and a hop
     * that is refused closes the redirect reply before failing, so no connection is leaked on
     * the refusal paths.
     */
    private fun <T> follow(
        continuation: CancellableContinuation<T>,
        inFlight: AtomicReference<Call?>,
        request: Request,
        hop: Int,
        finish: (Response) -> T,
    ) {
        val call = client.newCall(request)
        // The handler registered by the caller cancels whatever this chain currently has on
        // the wire; replacing it here is what makes the second hop cancellable too.
        inFlight.set(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                continuation.complete(
                    Result.failure(UpdateNetworkException("网络请求失败：${error.message ?: "连接中断"}")),
                )
            }

            override fun onResponse(call: Call, response: Response) {
                when (val next = nextHop(request, response, hop)) {
                    is Hop.Final -> continuation.complete(runCatching { response.use(finish) })

                    is Hop.Next -> {
                        response.close()
                        // A cancelled download has already been reported; starting another
                        // request would only be answered into a dead continuation.
                        if (continuation.isActive) {
                            follow(
                                continuation,
                                inFlight,
                                request.newBuilder().url(next.url).build(),
                                hop + 1,
                                finish,
                            )
                        }
                    }

                    is Hop.Refused -> {
                        response.close()
                        continuation.complete(Result.failure(UpdateNetworkException(next.message)))
                    }
                }
            }
        })
    }

    /** What to do with a reply: consume it, chase it, or refuse to chase it. */
    private sealed interface Hop {
        data class Final(val response: Response) : Hop
        data class Next(val url: String) : Hop
        data class Refused(val message: String) : Hop
    }

    private fun nextHop(request: Request, response: Response, hop: Int): Hop {
        if (!response.isRedirect) return Hop.Final(response)
        if (hop >= UpdateHosts.MAX_UPDATE_REDIRECT_HOPS) {
            return Hop.Refused("更新地址跳转次数过多，已停止")
        }
        val location = response.header("Location") ?: return Hop.Refused("更新地址跳转缺少目标")
        // Resolved against the reply's own URL, so a relative Location works and a missing or
        // malformed one is a refusal rather than a guess.
        val target = response.request.url.resolve(location) ?: return Hop.Refused("更新地址跳转的目标无法解析")
        if (target.scheme != "https" || !UpdateHosts.allows(target.host)) {
            // The downgrade case lives here too: an https URL redirected at an http one would
            // put the download on the wire in the clear.
            return Hop.Refused("更新地址被跳转到不允许的站点（${target.host}）")
        }
        return Hop.Next(target.toString())
    }

    private fun readBody(
        response: Response,
        maxBytes: Int,
        onProgress: (DownloadProgress) -> Unit,
    ): ByteArray {
        if (!response.isSuccessful) {
            throw UpdateNetworkException("服务器返回 HTTP ${response.code}")
        }
        val body = response.body ?: throw UpdateNetworkException("服务器没有返回内容")
        val declared = body.contentLength()
        if (declared > maxBytes) {
            throw UpdateNetworkException("文件过大（$declared 字节）")
        }
        val out = ByteArrayOutputStream(if (declared > 0) declared.toInt() else 64 * 1024)
        try {
            copyWithProgress(
                source = body.byteStream(),
                sink = out,
                maxBytes = maxBytes.toLong(),
                declaredBytes = declared,
                onProgress = onProgress,
                // The ceiling is enforced while reading as well as from the declaration:
                // a body that lies about its length would otherwise fill memory.
                tooLarge = { UpdateNetworkException("文件过大（超过 ${maxBytes / 1024} KB）") },
            )
        } catch (error: IOException) {
            // A connection dropped mid-body lands here, and OkHttp words that
            // "unexpected end of stream" — not a sentence to put in front of a user.
            throw UpdateNetworkException("下载中断（${error.message ?: "连接中断"}）", error)
        }
        return out.toByteArray()
    }

    private fun writeBody(
        response: Response,
        target: File,
        maxBytes: Long,
        expectation: DownloadExpectation?,
        onProgress: (DownloadProgress) -> Unit,
    ): File {
        if (!response.isSuccessful) {
            throw UpdateNetworkException("服务器返回 HTTP ${response.code}")
        }
        val body = response.body ?: throw UpdateNetworkException("服务器没有返回内容")
        val declared = body.contentLength()
        if (declared > maxBytes) {
            throw UpdateNetworkException("文件过大（$declared 字节）")
        }
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        // The digest is taken as the bytes land rather than by re-reading the file: the file
        // is tens of megabytes, and the check has to happen while the name is still `.part`.
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            part.outputStream().use { fileOut ->
                DigestOutputStream(fileOut, digest).use { out ->
                    try {
                        copyWithProgress(
                            source = body.byteStream(),
                            sink = out,
                            maxBytes = maxBytes,
                            declaredBytes = declared,
                            onProgress = onProgress,
                            tooLarge = {
                                UpdateNetworkException("安装包过大（超过 ${maxBytes / 1024 / 1024} MB）")
                            },
                        )
                    } catch (error: IOException) {
                        // The same wording the in-memory path gives: a connection dropped
                        // mid-body lands here, and OkHttp words that "unexpected end of
                        // stream" — which is not a sentence to put in front of a user.
                        throw UpdateNetworkException("下载中断（${error.message ?: "连接中断"}）", error)
                    }
                }
            }
        } catch (error: Throwable) {
            part.delete()
            throw error
        }
        verify(part, digest, expectation)
        if (!part.renameTo(target)) {
            part.delete()
            throw UpdateNetworkException("无法保存安装包")
        }
        return target
    }

    /**
     * Checks the finished part file against [expectation], deleting it on a mismatch.
     *
     * A `delete()` that fails is not reported separately: the download is already a failure
     * and the file is inside the app's own cache, where the next download for the same build
     * overwrites it.
     */
    private fun verify(part: File, digest: MessageDigest, expectation: DownloadExpectation?) {
        if (expectation == null) return
        val written = part.length()
        val size = expectation.sizeBytes
        if (size != null && written != size) {
            part.delete()
            throw UpdateNetworkException("下载的安装包大小与更新信息不符（$written ≠ $size）")
        }
        val sha = expectation.sha256
        if (sha != null && !hexDigest(digest.digest()).equals(sha, ignoreCase = true)) {
            part.delete()
            throw UpdateNetworkException("下载的安装包校验失败（SHA-256 不匹配）")
        }
    }

    private fun <T> CancellableContinuation<T>.complete(result: Result<T>) {
        // A cancelled download has already been reported; resuming again would throw.
        if (isActive) resumeWith(result)
    }
}

/**
 * A transport-level failure, before either feed has looked at what came back.
 *
 * Both feeds fail the same way here (a timeout is a timeout), so this is one type and each
 * caller decides how to phrase it: the detection package's card and the app's update page
 * each wrap it in their own vocabulary.
 */
class UpdateNetworkException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
