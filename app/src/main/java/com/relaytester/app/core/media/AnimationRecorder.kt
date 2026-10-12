package com.relaytester.app.core.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Records a rendered animation view to an animated GIF and shares it.
 *
 * The capture is a sequence of `View` bitmaps: the preview is a WebView running the document,
 * and the only way to see its animation is to draw it — there is no frame API to ask it for.
 * That means the view must be attached and visible while recording, which is why the caller
 * records the preview that is already on screen rather than a detached copy.
 *
 * Drawing happens on the main thread and encoding does not. A `View.draw` off the UI thread is
 * not allowed — the WebView's rendering state belongs to the main thread — while quantising and
 * LZW-compressing a few dozen frames is the expensive part and must not block the frame the
 * preview is animating on. So [recordGif] samples pixels on `Dispatchers.Main` and hands each
 * frame to a background encoder as it arrives.
 *
 * Everything here is deliberately bounded. A GIF is not a video: 256 colours and a per-frame
 * delay measured in hundredths of a second. The defaults are a two-second loop at 12 frames per
 * second, which covers the cycle length of the animations this panel produces and keeps the
 * file in the range a chat app will accept.
 */
object AnimationRecorder {

    /** Frames per second of the recording. */
    const val DEFAULT_FPS = 12

    /** How long the recording runs, in milliseconds: roughly one animation cycle. */
    const val DEFAULT_DURATION_MS = 2_000L

    /** GIF delays are centiseconds; below 2 most viewers clamp to 10, so this is a floor. */
    private const val MIN_DELAY_CENTISECONDS = 2

    /**
     * Records [view] for [durationMs] and returns the GIF bytes, or null when nothing could be
     * captured.
     *
     * Sampling is by wall clock rather than by frame callback: the WebView renders on its own
     * schedule, and a `Choreographer`-driven capture would produce however many frames the
     * device happened to draw — a fast phone would make an enormous file and a slow one a
     * stuttering three-frame loop. A fixed interval makes the output depend on neither.
     *
     * Must be called from a context that can reach the main thread (a coroutine on
     * `Dispatchers.Main`); the sampling itself hops to the main dispatcher, and the encoding
     * runs on [Dispatchers.Default].
     */
    suspend fun recordGif(
        view: View,
        durationMs: Long = DEFAULT_DURATION_MS,
        fps: Int = DEFAULT_FPS,
    ): ByteArray? {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null

        val scaled = scaledSize(width, height)
        val frameCount = ((durationMs * fps) / 1_000L).toInt().coerceAtLeast(2)
        val frameDelayMillis = 1_000L / fps

        val frames = ArrayList<IntArray>(frameCount)
        for (index in 0 until frameCount) {
            val startedAt = System.nanoTime()
            // The draw itself is the only part that must be on the main thread.
            val frame = withContext(Dispatchers.Main) {
                captureFrame(view, scaled.first, scaled.second)
            } ?: return null
            frames += frame
            // Hold the target cadence: capture itself takes time, and without this the
            // recorded interval drifts longer than the delay written into the GIF.
            val spentMillis = (System.nanoTime() - startedAt) / 1_000_000L
            val remaining = frameDelayMillis - spentMillis
            if (remaining > 0) delay(remaining)
        }
        if (frames.isEmpty()) return null

        val delayCentiseconds = (100 / fps).coerceAtLeast(MIN_DELAY_CENTISECONDS)
        // Quantising and compressing a couple dozen full frames is the expensive half; it runs
        // away from the UI so the preview keeps animating while the file is built.
        return withContext(Dispatchers.Default) {
            GifEncoder.encode(frames, scaled.first, scaled.second, delayCentiseconds)
        }
    }

    /**
     * One PNG of [view] as it is right now, or null when it has nothing to draw.
     *
     * The list's thumbnail: the same `View.draw` capture the GIF uses, taken once instead of
     * sampled over time. Encoded as PNG rather than kept as a bitmap so a record can hold its
     * picture without pinning a frame of pixels in memory for as long as the panel is open.
     *
     * Must be called from a context that can reach the main thread — `View.draw` is only legal
     * there — which is why the draw hops to `Dispatchers.Main` exactly as [recordGif] does.
     */
    suspend fun captureStill(
        view: View,
        maxWidth: Int = STILL_MAX_WIDTH,
    ): ByteArray? = withContext(Dispatchers.Main) {
        if (view.width <= 0 || view.height <= 0) return@withContext null
        val scale = if (view.width > maxWidth) maxWidth.toFloat() / view.width else 1f
        val width = (view.width * scale).toInt().coerceAtLeast(1)
        val height = (view.height * scale).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.scale(width.toFloat() / view.width, height.toFloat() / view.height)
            view.draw(canvas)
            withContext(Dispatchers.Default) {
                java.io.ByteArrayOutputStream().use { out ->
                    if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Width of a stored thumbnail: enough to recognise a frame, small enough to hold in state. */
    private const val STILL_MAX_WIDTH = 480

    /** One frame, drawn on the main thread. */
    private fun captureFrame(view: View, width: Int, height: Int): IntArray? {
        if (view.width <= 0 || view.height <= 0) return null
        val bitmap = createBitmap(width, height)
        try {
            // Drawing the view into a scaled canvas: the WebView is drawn at its on-screen size
            // and this canvas is the output size, so the scene is resampled rather than
            // re-laid-out.
            val canvas = android.graphics.Canvas(bitmap)
            canvas.scale(width.toFloat() / view.width, height.toFloat() / view.height)
            view.draw(canvas)
            return pixelsOf(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** The recording size: the view's own aspect, capped so the file stays shareable. */
    internal fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= GifEncoder.MAX_DIMENSION) return width to height
        val factor = GifEncoder.MAX_DIMENSION.toFloat() / longest
        return (width * factor).toInt().coerceAtLeast(1) to
            (height * factor).toInt().coerceAtLeast(1)
    }

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    /**
     * Writes [gif] to the cache and offers it to whatever the system shares images with.
     *
     * Same sharing rules as [com.relaytester.app.core.update.PreviewOpener]: a `content://` URI
     * from a provider that exposes only this one directory, and a read grant attached to the
     * intent. `image/gif` is the type, which is what makes chat and photo apps offer themselves.
     */
    fun share(context: Context, gif: ByteArray): Boolean {
        val file = File(File(context.cacheDir, GIF_DIRECTORY), GIF_FILE_NAME)
        file.parentFile?.mkdirs()
        file.writeBytes(gif)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.previews", file)
        // Shares to *any* app that accepts an image, not just a browser: ACTION_SEND with a
        // chooser is the difference between "open this" and "send this to someone".
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/gif"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "分享动画")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (chooser.resolveActivity(context.packageManager) == null) return false
        context.startActivity(chooser)
        return true
    }

    private const val GIF_DIRECTORY = "preview"
    private const val GIF_FILE_NAME = "animation.gif"
}
