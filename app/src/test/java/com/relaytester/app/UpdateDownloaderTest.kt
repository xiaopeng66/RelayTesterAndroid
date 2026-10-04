package com.relaytester.app

import com.relaytester.app.core.update.DownloadProgress
import com.relaytester.app.core.update.copyWithProgress
import com.relaytester.app.core.update.formatByteSize
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The byte loop both downloads share.
 *
 * It is what a progress bar reads from, and it is the only place the size ceiling is
 * enforced while reading, so both facts are pinned here rather than through one of the
 * two callers.
 */
class UpdateDownloaderTest {
    private class TooLarge : IOException("too large")

    private fun copy(
        bytes: ByteArray,
        maxBytes: Long = 8L * 1024 * 1024,
        declared: Long = bytes.size.toLong(),
        seen: MutableList<DownloadProgress> = mutableListOf(),
    ): Pair<ByteArray, List<DownloadProgress>> {
        val out = ByteArrayOutputStream()
        copyWithProgress(
            source = ByteArrayInputStream(bytes),
            sink = out,
            maxBytes = maxBytes,
            declaredBytes = declared,
            onProgress = { seen += it },
            tooLarge = { TooLarge() },
        )
        return out.toByteArray() to seen
    }

    @Test
    fun `the bytes arrive unchanged`() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }

        val (copied, _) = copy(payload)

        assertArrayEquals(payload, copied)
    }

    @Test
    fun `progress starts at zero with the total and ends at the total`() {
        val payload = ByteArray(200_000)

        val (_, seen) = copy(payload)

        assertEquals(DownloadProgress(bytesRead = 0, totalBytes = 200_000), seen.first())
        assertEquals(DownloadProgress(bytesRead = 200_000, totalBytes = 200_000), seen.last())
        // Monotonic: a bar that hops backwards is read as a restart.
        assertTrue(seen.zipWithNext().all { (before, after) -> after.bytesRead >= before.bytesRead })
    }

    @Test
    fun `an undeclared length is reported as unknown rather than zero`() {
        // Zero would draw an empty bar that never fills; -1 is what lets the row say
        // "正在下载 1.3 MB" instead of pretending to have a denominator.
        val (_, seen) = copy(ByteArray(1000), declared = -1L)

        assertNull(seen.first().totalBytes.takeIf { it > 0 })
        assertEquals(-1L, seen.first().totalBytes)
        assertNull(seen.first().fraction)
    }

    @Test
    fun `a body that lies about its length is still cut off`() {
        // The declared size is a claim from the server; the ceiling has to hold while
        // reading too, or a body claiming to be small fills memory.
        val attempt = runCatching { copy(ByteArray(4096), maxBytes = 1024, declared = 1024) }

        assertTrue(attempt.exceptionOrNull() is TooLarge)
    }

    @Test
    fun `the caller's exception type is what an oversized body raises`() {
        // The bank's own wording and the APK's differ; the loop must not pick one.
        val attempt = runCatching {
            copyWithProgress(
                source = ByteArrayInputStream(ByteArray(4096)),
                sink = ByteArrayOutputStream(),
                maxBytes = 1024,
                declaredBytes = 1024,
                onProgress = {},
                tooLarge = { IllegalStateException("APK 太大") },
            )
        }

        assertEquals("APK 太大", attempt.exceptionOrNull()?.message)
    }

    @Test
    fun `a size reads in the same units a download shows`() {
        assertEquals("390 KB", formatByteSize(399_470))
        assertEquals("3.3 MB", formatByteSize(3_476_998))
        assertEquals("0 KB", formatByteSize(1))
    }
}
