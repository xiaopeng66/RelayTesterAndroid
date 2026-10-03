package com.relaytester.app

import com.relaytester.app.core.fingerprint.DetectionHistoryEntry
import com.relaytester.app.core.fingerprint.FINGERPRINT_HISTORY_FILE_NAME
import com.relaytester.app.core.fingerprint.FingerprintHistoryStore
import com.relaytester.app.core.fingerprint.HistoryFileSystem
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history's two promises: newest first, and never more than the cap.
 *
 * The cap is the feature — an install that tested a model daily for a year must not
 * carry a year of records — so it is asserted on the file as well as on the returned
 * list, because a reader that trims in memory while the file grows would still pass a
 * list-only check while filling the device.
 */
class FingerprintHistoryStoreTest {
    /** In-memory stand-in for the private file. */
    private class MemoryHistoryFiles(
        var content: String? = null,
    ) : HistoryFileSystem {
        var writes = 0
        var failWrites = false

        override fun read(): String? = content

        override fun write(text: String) {
            if (failWrites) throw IOException("磁盘已满")
            writes++
            content = text
        }
    }

    private fun entry(
        model: String,
        at: Long,
        candidate: String? = "GPT-4o",
        error: String? = null,
    ) = DetectionHistoryEntry(
        finishedAt = at,
        supplierName = "测试供应商",
        model = model,
        candidateName = candidate,
        familyName = "GPT",
        probability = 0.87,
        usableAnswers = 3,
        submittedAnswers = 3,
        error = error,
    )

    @Test
    fun `an empty file reads as no history`() {
        assertEquals(emptyList<DetectionHistoryEntry>(), FingerprintHistoryStore(MemoryHistoryFiles()).load())
    }

    @Test
    fun `an appended record round-trips every field`() {
        val files = MemoryHistoryFiles()
        val store = FingerprintHistoryStore(files)
        val record = entry("gpt-4o-preview", 1_700_000_000_000L)

        assertTrue(store.append(listOf(record)))
        val loaded = store.load()

        assertEquals(1, loaded.size)
        assertEquals(record, loaded.single())
    }

    @Test
    fun `the newest record is first`() {
        val store = FingerprintHistoryStore(MemoryHistoryFiles())

        store.append(listOf(entry("older", 1_000L)))
        store.append(listOf(entry("newer", 2_000L)))

        assertEquals(listOf("newer", "older"), store.load().map { it.model })
    }

    @Test
    fun `a batch keeps its own order inside one write`() {
        val store = FingerprintHistoryStore(MemoryHistoryFiles())

        // A round appends its models together, newest-in-round first, exactly as the
        // rows are reported; a store that re-sorted by timestamp would scramble models
        // that finished in the same millisecond.
        store.append(listOf(entry("first", 2_000L), entry("second", 2_000L), entry("third", 2_000L)))

        assertEquals(listOf("first", "second", "third"), store.load().map { it.model })
    }

    @Test
    fun `the cap is enforced on the file, not just in memory`() {
        val files = MemoryHistoryFiles()
        val store = FingerprintHistoryStore(files, maxEntries = 50)

        for (index in 1..120) {
            store.append(listOf(entry("model-$index", index.toLong())))
        }

        val loaded = store.load()
        assertEquals(50, loaded.size)
        // The newest survive and the oldest are gone: dropping the newest would make the
        // feature worse than useless — the test just run would be the one that vanished.
        assertEquals("model-120", loaded.first().model)
        assertEquals("model-71", loaded.last().model)
        assertEquals(50, JSONArray(files.content).length())
    }

    @Test
    fun `a multi-model round cannot push the file past the cap`() {
        val files = MemoryHistoryFiles()
        val store = FingerprintHistoryStore(files, maxEntries = 5)

        store.append(listOf(entry("a", 1L), entry("b", 2L), entry("c", 3L)))
        // The second round is newer, so it takes the front; only the two most recent of
        // the first round's records survive.
        store.append(listOf(entry("d", 4L), entry("e", 5L), entry("f", 6L)))

        assertEquals(5, store.load().size)
        assertEquals(listOf("d", "e", "f", "a", "b"), store.load().map { it.model })
    }

    @Test
    fun `a corrupt file is an empty history rather than an error`() {
        val files = MemoryHistoryFiles(content = "{ this is not json ]")

        assertEquals(emptyList<DetectionHistoryEntry>(), FingerprintHistoryStore(files).load())
    }

    @Test
    fun `a row missing its model or timestamp is dropped`() {
        // A partial write must not render as a blank line: a record without a model name
        // or a time cannot be shown meaningfully, so it is skipped and the rest survive.
        val stored = JSONArray()
            .put(JSONObject().put("finishedAt", 5_000L).put("model", "keep-me"))
            .put(JSONObject().put("finishedAt", 5_000L))
            .put(JSONObject().put("model", "no-timestamp"))
            .toString()

        val loaded = FingerprintHistoryStore(MemoryHistoryFiles(stored)).load()

        assertEquals(listOf("keep-me"), loaded.map { it.model })
    }

    @Test
    fun `a failed detection keeps its reason and no candidate`() {
        val files = MemoryHistoryFiles()
        val store = FingerprintHistoryStore(files)

        store.append(listOf(entry("flaky", 1L, candidate = null, error = "上游返回 HTTP 502")))
        val loaded = store.load().single()

        assertNull(loaded.candidateName)
        assertEquals("上游返回 HTTP 502", loaded.error)
    }

    @Test
    fun `a failed write leaves the previous file intact and reports it`() {
        val files = MemoryHistoryFiles()
        val store = FingerprintHistoryStore(files)
        store.append(listOf(entry("kept", 1L)))
        val before = files.content

        files.failWrites = true
        val written = store.append(listOf(entry("lost", 2L)))

        assertFalse("写失败必须如实返回 false", written)
        assertEquals(before, files.content)
        assertEquals(listOf("kept"), store.load().map { it.model })
    }

    @Test
    fun `clearing empties the stored history`() {
        val files = MemoryHistoryFiles()
        val store = FingerprintHistoryStore(files)
        store.append(listOf(entry("gone", 1L)))

        assertTrue(store.clear())

        assertEquals(emptyList<DetectionHistoryEntry>(), store.load())
        assertNotNull(files.content)
        assertEquals(0, JSONArray(files.content).length())
    }

    @Test
    fun `the file name is the one the real filesystem uses`() {
        // Guards the wiring in the factory: the Android implementation and this test
        // must not drift onto different names, or a device would look empty forever.
        assertEquals("fingerprint-history.json", FINGERPRINT_HISTORY_FILE_NAME)
    }
}
