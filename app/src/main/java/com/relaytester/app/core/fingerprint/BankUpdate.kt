package com.relaytester.app.core.fingerprint

import androidx.compose.runtime.Immutable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Anything that stops a check or an install, carrying a message meant for the panel. */
class BankUpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * What the release endpoint advertises about the newest reference bank.
 *
 * The digest identifies a bank: it is compared with the digest of the copy in use, so
 * a re-published bank reads as "already current" instead of being downloaded again.
 */
@Immutable
data class BankManifest(
    val formatVersion: Int,
    /** Reference build stamp, e.g. `2026-09-30T05:12:31+00:00`. */
    val builtAt: String,
    /** Reference digest upstream reports for the same data. */
    val referenceSha256: String,
    val modelCount: Int,
    val sizeBytes: Long,
    val sha256: String,
    val url: String,
    val minAppVersionCode: Long,
)

/** Reads the published manifest; every failure is a [BankUpdateException]. */
object BankManifestParser {
    /** Bumped when the packed package changes shape; an app that predates it must not install it. */
    const val SUPPORTED_FORMAT = 2

    fun parse(text: String): BankManifest {
        val root = try {
            JSONObject(text)
        } catch (error: Exception) {
            throw BankUpdateException("更新清单无法解析")
        }
        val formatVersion = root.optInt("formatVersion", -1)
        if (formatVersion != SUPPORTED_FORMAT) {
            throw BankUpdateException("更新清单的格式版本 $formatVersion 不受支持")
        }
        val builtAt = root.string("builtAt", "构建时间")
        val sizeBytes = root.optLong("sizeBytes", -1)
        if (sizeBytes <= 0 || sizeBytes > FingerprintBankStore.MAX_INSTALLED_BYTES) {
            throw BankUpdateException("更新清单的库大小不合理（$sizeBytes 字节）")
        }
        val modelCount = root.optInt("modelCount", -1)
        if (modelCount <= 0) {
            throw BankUpdateException("更新清单的模型数量不合理（$modelCount）")
        }
        val url = root.string("url", "下载地址")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw BankUpdateException("更新清单的下载地址必须是 HTTP(S)")
        }
        return BankManifest(
            formatVersion = formatVersion,
            builtAt = builtAt,
            referenceSha256 = root.string("referenceSha256", "参考数据摘要"),
            modelCount = modelCount,
            sizeBytes = sizeBytes,
            sha256 = root.digest("sha256"),
            url = url,
            minAppVersionCode = root.optLong("minAppVersionCode", 0L),
        )
    }

    private fun JSONObject.string(key: String, what: String): String =
        optString(key).takeIf { it.isNotBlank() } ?: throw BankUpdateException("更新清单缺少$what")

    private fun JSONObject.digest(key: String): String {
        val value = optString(key).trim().lowercase()
        if (value.length != 64 || value.any { it !in "0123456789abcdef" }) {
            throw BankUpdateException("更新清单缺少有效的 SHA-256")
        }
        return value
    }
}

/** Outcome of a manual check. */
sealed interface BankUpdateCheck {
    /** A different bank is published. */
    data class Available(val manifest: BankManifest) : BankUpdateCheck

    /** The published bank is the one already in use. */
    data object UpToDate : BankUpdateCheck

    /** The published bank needs a newer app, so installing it here would be wrong. */
    data class NeedsNewerApp(val manifest: BankManifest) : BankUpdateCheck
}

/** Where the published bank lives. */
object BankUpdateDefaults {
    /** The `bank` pre-release of our own repository; a plain asset URL, no API call, no token. */
    const val MANIFEST_URL =
        "https://github.com/xiaopeng66/RelayTesterAndroid/releases/download/bank/latest.json"
}

/**
 * Fetches bytes over the network.
 *
 * An interface so the update flow can be tested without a server, and so the device
 * end-to-end run can point at a local endpoint.
 */
interface BankFetcher {
    /** GETs [url], refusing a body larger than [maxBytes]. */
    suspend fun fetch(url: String, maxBytes: Int): ByteArray
}

/**
 * Checks what is published and downloads it once the user asks for it.
 *
 * Nothing here installs anything: [download] returns verified bytes and the store
 * decides what to do with them.
 */
class BankUpdateClient(
    private val fetcher: BankFetcher,
    private val manifestUrl: String = BankUpdateDefaults.MANIFEST_URL,
    private val maxManifestBytes: Int = MAX_MANIFEST_BYTES,
) {
    /**
     * Compares what is published with the package in use.
     *
     * [localSha256] is null when nothing is installed — the panel is unprovisioned, so
     * whatever is published is by definition an install rather than an update.
     */
    suspend fun check(localSha256: String?, appVersionCode: Long): BankUpdateCheck {
        val bytes = fetcher.fetch(manifestUrl, maxManifestBytes)
        val manifest = BankManifestParser.parse(String(bytes, Charsets.UTF_8))
        return when {
            // Identity comes first: a package we already have is nothing to do, whatever
            // the manifest asks of the app.
            localSha256 != null && manifest.sha256 == localSha256 -> BankUpdateCheck.UpToDate
            manifest.minAppVersionCode > appVersionCode -> BankUpdateCheck.NeedsNewerApp(manifest)
            else -> BankUpdateCheck.Available(manifest)
        }
    }

    /**
     * Fetches the package [manifest] advertises.
     *
     * The size and the digest are checked here, so bytes that do not match what the
     * manifest promised never reach the store.
     */
    suspend fun download(
        manifest: BankManifest,
        maxBytes: Int = FingerprintBankStore.MAX_INSTALLED_BYTES.toInt(),
    ): ByteArray {
        val bytes = fetcher.fetch(manifest.url, maxBytes)
        if (bytes.size.toLong() != manifest.sizeBytes) {
            throw BankUpdateException("下载的检测包大小与清单不符（${bytes.size} ≠ ${manifest.sizeBytes}）")
        }
        if (!sha256Hex(bytes).equals(manifest.sha256, ignoreCase = true)) {
            throw BankUpdateException("下载的检测包校验失败（SHA-256 不匹配）")
        }
        return bytes
    }

    companion object {
        const val MAX_MANIFEST_BYTES = 256 * 1024
    }
}

/**
 * [BankFetcher] over OkHttp.
 *
 * Timeouts are deliberately short and separate from the relay client's: a bank is a
 * few hundred kilobytes from one fixed host, and a stalled download has to fail rather
 * than hold the panel's spinner. The call is enqueued rather than run on the calling
 * thread so cancelling the work actually closes the connection.
 */
class OkHttpBankFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build(),
) : BankFetcher {
    override suspend fun fetch(url: String, maxBytes: Int): ByteArray {
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
                        Result.failure(BankUpdateException("网络请求失败：${error.message ?: "连接中断"}")),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.complete(runCatching { response.use { readBody(it, maxBytes) } })
                }
            })
        }
    }

    private fun readBody(response: Response, maxBytes: Int): ByteArray {
        if (!response.isSuccessful) {
            throw BankUpdateException("服务器返回 HTTP ${response.code}")
        }
        val body = response.body ?: throw BankUpdateException("服务器没有返回内容")
        val declared = body.contentLength()
        if (declared > maxBytes) {
            throw BankUpdateException("文件过大（$declared 字节）")
        }
        val out = ByteArrayOutputStream(if (declared > 0) declared.toInt() else 64 * 1024)
        try {
            val source = body.byteStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                // The declared length can lie, so the ceiling is enforced while reading too.
                if (out.size().toLong() + read > maxBytes) {
                    throw BankUpdateException("文件过大（超过 ${maxBytes / 1024} KB）")
                }
                out.write(buffer, 0, read)
            }
        } catch (error: BankUpdateException) {
            throw error
        } catch (error: IOException) {
            // A connection dropped mid-body lands here, and OkHttp words that
            // "unexpected end of stream" — not a sentence to put in front of a user.
            throw BankUpdateException("下载中断（${error.message ?: "连接中断"}）", error)
        }
        return out.toByteArray()
    }

    private fun <T> CancellableContinuation<T>.complete(result: Result<T>) {
        // A cancelled download has already been reported; resuming again would throw.
        if (isActive) resumeWith(result)
    }
}
