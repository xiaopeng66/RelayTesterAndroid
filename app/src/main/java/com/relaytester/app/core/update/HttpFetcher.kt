package com.relaytester.app.core.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
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
     * until the last byte arrived. The bytes go to `target.part` first and are renamed
     * once the whole body has arrived, so the target name never refers to a half file.
     */
    suspend fun downloadTo(
        url: String,
        target: File,
        maxBytes: Long,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File
}

/**
 * [HttpFetcher] over OkHttp.
 *
 * Timeouts are deliberately short and separate from the relay client's: these are
 * downloads from fixed hosts, and one that stalls has to fail rather than hold a spinner.
 * The call is enqueued rather than run on the calling thread so cancelling the work
 * actually closes the connection.
 *
 * Plain OkHttp, on purpose: **not** [com.relaytester.app.core.network.HttpRedirects], whose
 * same-origin rule refuses the cross-host hop GitHub makes when serving a release asset.
 * That rule exists to keep an API key from being replayed to another host; nothing here
 * carries a credential.
 */
class OkHttpFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
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
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    continuation.complete(
                        Result.failure(UpdateNetworkException("网络请求失败：${error.message ?: "连接中断"}")),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.complete(runCatching { response.use { readBody(it, maxBytes, onProgress) } })
                }
            })
        }
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

    override suspend fun downloadTo(
        url: String,
        target: File,
        maxBytes: Long,
        onProgress: (DownloadProgress) -> Unit,
    ): File {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    continuation.complete(
                        Result.failure(UpdateNetworkException("网络请求失败：${error.message ?: "连接中断"}")),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.complete(
                        runCatching { response.use { writeBody(it, target, maxBytes, onProgress) } },
                    )
                }
            })
        }
    }

    private fun writeBody(
        response: Response,
        target: File,
        maxBytes: Long,
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
        // A part file rather than the target itself: the caller may be about to hand the
        // target name to the system installer, and it must never name a partial download.
        val part = File(target.parentFile, "${target.name}.part")
        try {
            part.outputStream().use { out ->
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
                    // stream" — which is not a sentence to put in front of a user. Opening
                    // the part file is outside this catch, so a disk that refuses is not
                    // reported as a broken connection.
                    throw UpdateNetworkException("下载中断（${error.message ?: "连接中断"}）", error)
                }
            }
        } catch (error: Throwable) {
            part.delete()
            throw error
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw UpdateNetworkException("无法保存安装包")
        }
        return target
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
