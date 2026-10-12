package com.relaytester.app.core.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a generated document to a browser so it can be viewed outside the app's sandbox.
 *
 * The preview is deliberately locked down (no network, no storage, no navigation — see
 * `AnimationPreview`), which is right for *inspecting* an untrusted document but wrong for the
 * case the user actually asked about: seeing the animation in a real browser, where links and
 * full-screen behave normally. This is the one path that leaves the sandbox, so it is
 * explicit, user-initiated, and open about the trade:
 *
 *  * the file is written to the cache directory, which the provider below exposes by name;
 *  * it is shared as a `content://` URI, never a `file://` one — a path across processes has
 *    been refused since Android 7 and the browser is another process;
 *  * the read grant is attached to the intent (`FLAG_GRANT_READ_URI_PERMISSION`) and is
 *    per-intent, so nothing else gains access to the file.
 *
 * What this does **not** do is make the document safe. A browser will run its JavaScript with
 * the browser's own privileges, not the app's restricted ones. The document came from a model
 * the user chose, through a request the user sent, and is opened by a button the user presses;
 * that is the same trust decision as opening any downloaded file, and the alternative — silently
 * sandboxing everything — is what the user is asking to opt out of here.
 */
object PreviewOpener {

    /** Where a shared preview is written; cleared of its previous file on each open. */
    private const val PREVIEW_DIRECTORY = "preview"

    private const val PREVIEW_FILE_NAME = "animation.html"

    /**
     * The file a preview is written to.
     *
     * A single fixed name rather than one per generation: the previous document is worthless
     * once the next is generated, and a name that changes every time would leave the cache
     * filling with pages nobody can reach again.
     */
    fun targetFile(context: Context): File =
        File(File(context.cacheDir, PREVIEW_DIRECTORY), PREVIEW_FILE_NAME)

    /**
     * Writes [html] and opens it in whatever the system has registered for `text/html`.
     *
     * Returns false when no browser would take the intent — an emulator image with no browser
     * is the realistic case, and the caller turns that into a message rather than a crash from
     * `startActivity` finding nothing.
     */
    fun open(context: Context, html: String): Boolean {
        val file = targetFile(context)
        file.parentFile?.mkdirs()
        file.writeText(html)

        val uri = FileProvider.getUriForFile(context, authorityOf(context), file)
        // `QueryPermissionsNeeded` is suppressed rather than silenced by reshaping the call:
        // the manifest *does* declare `<queries><intent><action VIEW/><data mimeType
        // text/html/></intent></queries>`, but lint's check only recognises the intent when the
        // type and data are set as separate attributes — and setting them separately is what
        // `IntentReset` then flags, because assigning one resets the other. `setDataAndType` is
        // the call that actually sets both; the declaration above is what makes
        // `resolveActivity` able to see a browser at all on API 30+. Verified against the
        // merged manifest, which carries the `<queries>` block.
        @Suppress("QueryPermissionsNeeded")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/html")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Resolved before starting: `startActivity` on an unmatched intent throws, and "no
        // browser installed" is an ordinary state on a stripped emulator image, not an error
        // worth a crash.
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        return true
    }

    /** The provider authority, derived from the package so debug and release do not collide. */
    private fun authorityOf(context: Context): String = "${context.packageName}.previews"
}
