package com.relaytester.app.core.fingerprint

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * One finished detection, as the history keeps it.
 *
 * Recorded per model rather than per round: a three-model round produces three
 * records, so a later review can answer "when did I last test this model" directly
 * instead of making the user open rounds until they find it.
 *
 * [candidateName] and [familyName] are null for a model whose round produced no
 * score; [error] then carries the reason, so a failed attempt is still worth keeping
 * (the next question about a model is usually "did this ever work?").
 */
data class DetectionHistoryEntry(
    /** Epoch milliseconds; the panel formats it for display. */
    val finishedAt: Long,
    val supplierName: String,
    val model: String,
    val candidateName: String? = null,
    val familyName: String? = null,
    val probability: Double? = null,
    val usableAnswers: Int = 0,
    val submittedAnswers: Int = 0,
    val error: String? = null,
)

/**
 * Where the history file lives.
 *
 * An interface for the same reason [BankFileSystem] has one: retention and malformed
 * input are the interesting behaviours, and both are testable without a device.
 */
interface HistoryFileSystem {
    /** The stored file, or null when there is none. */
    fun read(): String?

    /** Replaces the file's contents. */
    fun write(text: String)
}

/** The real filesystem: one private file, written through a staged rename. */
class AndroidHistoryFileSystem(context: Context) : HistoryFileSystem {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FINGERPRINT_HISTORY_FILE_NAME)

    override fun read(): String? = if (file.isFile) file.readText(Charsets.UTF_8) else null

    override fun write(text: String) {
        val directory = file.parentFile
        if (directory != null && !directory.isDirectory && !directory.mkdirs()) {
            throw IOException("无法创建历史记录目录")
        }
        // Staged next to the target so the rename stays within one filesystem: a reader
        // never sees a half-written file.
        val staging = File(directory, "${file.name}.staging")
        try {
            staging.writeText(text, Charsets.UTF_8)
            move(staging, file)
        } catch (error: IOException) {
            staging.delete()
            throw error
        }
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * Keeps the most recent detections, newest first, capped at [maxEntries].
 *
 * The cap is the whole feature: a phone that tested a model every day for a year must
 * not accumulate a year of records. Trimming happens on append, so the file is never
 * left oversized between launches, and the newest entry is always kept — a cap of zero
 * would otherwise be able to discard the very record being added.
 *
 * The stored format is a plain JSON array. It is local, non-secret display data, so a
 * corrupt file is not an error condition: it is treated as an empty history, which
 * loses nothing that could be reconstructed anyway.
 */
class FingerprintHistoryStore(
    private val files: HistoryFileSystem,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    /**
     * The stored records, newest first.
     *
     * Reads are not cached: the file is tiny, the panel opens it rarely, and a cache
     * would have to be invalidated against another process writing the same path (a
     * restore, or a second activity instance).
     */
    @Synchronized
    fun load(): List<DetectionHistoryEntry> {
        val raw = try {
            files.read()
        } catch (error: IOException) {
            null
        } ?: return emptyList()
        return parse(raw)
    }

    /**
     * Adds [entries] and returns whether they were stored.
     *
     * [entries] are taken as the newest records and placed at the front in the order
     * given, with everything already stored following them; the result is then trimmed
     * to [maxEntries] from the oldest end. A caller appending a whole round therefore
     * passes its models newest-first, and the entry it passes first is the one a reader
     * sees first.
     *
     * Records are prepended in one write, so a multi-model round lands as a contiguous
     * block rather than as several trims of the same file. A write that fails is
     * reported by a false return and leaves the previous file intact — losing history
     * must never break the detection that produced it.
     */
    @Synchronized
    fun append(entries: List<DetectionHistoryEntry>): Boolean {
        if (entries.isEmpty()) return true
        val merged = (entries + load()).take(maxEntries.coerceAtLeast(1))
        return try {
            files.write(serialize(merged))
            true
        } catch (error: IOException) {
            false
        }
    }

    /** Empties the history; false when the file could not be rewritten. */
    @Synchronized
    fun clear(): Boolean = try {
        files.write(serialize(emptyList()))
        true
    } catch (error: IOException) {
        false
    }

    private fun parse(raw: String): List<DetectionHistoryEntry> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<DetectionHistoryEntry>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val entry = readEntry(item) ?: continue
            out += entry
        }
        return out
    }

    /**
     * One record, or null when it is too incomplete to display.
     *
     * A row without a model name or a timestamp cannot be shown meaningfully, so it is
     * dropped rather than rendered as blanks. Everything else is optional, because a
     * failed detection legitimately has no candidate.
     */
    private fun readEntry(item: JSONObject): DetectionHistoryEntry? {
        val model = item.optString("model").takeIf { it.isNotBlank() } ?: return null
        val finishedAt = item.optLong("finishedAt", -1L).takeIf { it > 0L } ?: return null
        return DetectionHistoryEntry(
            finishedAt = finishedAt,
            supplierName = item.optString("supplierName"),
            model = model,
            candidateName = item.optString("candidateName").takeIf { it.isNotBlank() },
            familyName = item.optString("familyName").takeIf { it.isNotBlank() },
            probability = item.optDouble("probability", Double.NaN).takeIf { !it.isNaN() },
            usableAnswers = item.optInt("usableAnswers", 0),
            submittedAnswers = item.optInt("submittedAnswers", 0),
            error = item.optString("error").takeIf { it.isNotBlank() },
        )
    }

    private fun serialize(entries: List<DetectionHistoryEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("finishedAt", entry.finishedAt)
                    .put("supplierName", entry.supplierName)
                    .put("model", entry.model)
                    .put("candidateName", entry.candidateName)
                    .put("familyName", entry.familyName)
                    .put("probability", entry.probability)
                    .put("usableAnswers", entry.usableAnswers)
                    .put("submittedAnswers", entry.submittedAnswers)
                    .put("error", entry.error),
            )
        }
        return array.toString()
    }

    companion object {
        /** Records kept before the oldest are dropped. */
        const val MAX_ENTRIES = 50
    }
}

/** File name of the history inside the app's private directory. */
const val FINGERPRINT_HISTORY_FILE_NAME = "fingerprint-history.json"
