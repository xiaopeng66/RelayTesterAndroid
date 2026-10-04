package com.relaytester.app.core.update

import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * How much of a download has arrived.
 *
 * [totalBytes] is what the server declared, or -1 when it did not say. A download whose
 * length is unknown can still report how much has arrived, which is what makes it worth
 * keeping the two apart instead of storing a fraction: the caller decides whether a bar
 * without a denominator is honest to draw.
 */
data class DownloadProgress(val bytesRead: Long, val totalBytes: Long) {
    /** 0f..1f while [totalBytes] is known, else null. */
    val fraction: Float?
        get() = if (totalBytes > 0) (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/** A byte count as a reader thinks of it: megabytes past a megabyte, kilobytes below. */
internal fun formatByteSize(bytes: Long): String = with(unitFor(bytes)) {
    "${inUnit(bytes, this)} ${suffix}"
}

/**
 * Two byte counts in one unit, e.g. "1.8/2.9 MB".
 *
 * Both numbers take the larger one's unit so the line reads as a single quantity. Printing
 * each with its own unit ("1.8 MB/2.9 MB") is two measurements where the reader wants the
 * ratio, and "390 KB/3.3 MB" cannot be compared at a glance at all.
 */
internal fun formatByteSizePair(arrived: Long, total: Long): String {
    val unit = unitFor(total)
    return "${inUnit(arrived, unit)}/${inUnit(total, unit)} ${unit.suffix}"
}

private enum class SizeUnit(val suffix: String) { KILOBYTES("KB"), MEGABYTES("MB") }

private fun unitFor(reference: Long): SizeUnit =
    if (reference >= 1024L * 1024L) SizeUnit.MEGABYTES else SizeUnit.KILOBYTES

private fun inUnit(bytes: Long, unit: SizeUnit): String = when (unit) {
    SizeUnit.MEGABYTES -> String.format(Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0)
    SizeUnit.KILOBYTES -> String.format(Locale.ROOT, "%.0f", bytes / 1024.0)
}

/**
 * Copies [source] into [sink], reporting progress and refusing to grow past [maxBytes].
 *
 * One loop for both downloads: the detection package (a few megabytes into memory) and the
 * APK (tens of megabytes onto disk). They differ in sink and in the exception they raise
 * for an oversized body, so [tooLarge] is a factory rather than a type this file has to
 * know about — the alternative was the same loop written twice with the ceiling enforced
 * in one of them.
 *
 * The declared length is not trusted in either direction: a body that lies small is still
 * cut off at [maxBytes], and [totalBytes] is only ever reported for display.
 *
 * @return the number of bytes copied.
 */
internal fun copyWithProgress(
    source: InputStream,
    sink: OutputStream,
    maxBytes: Long,
    declaredBytes: Long,
    onProgress: (DownloadProgress) -> Unit,
    tooLarge: () -> Throwable,
): Long {
    // Said once before any bytes move, so a UI that wants to show the denominator can.
    onProgress(DownloadProgress(bytesRead = 0, totalBytes = declaredBytes))
    var copied = 0L
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = source.read(buffer)
        if (read < 0) break
        if (copied + read > maxBytes) throw tooLarge()
        sink.write(buffer, 0, read)
        copied += read
        onProgress(DownloadProgress(bytesRead = copied, totalBytes = declaredBytes))
    }
    return copied
}
