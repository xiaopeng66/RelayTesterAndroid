package com.relaytester.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest contract no JVM test can observe as behaviour: the two FileProvider entries
 * must not share one class name.
 *
 * A JVM test has no PackageManager, so the only thing it can pin is the source fact that
 * prevents the bug. The platform conflates same-class `<provider>` entries of one package
 * and serves the first one's configuration for both authorities — measured on the API 35
 * emulator, where acquiring `${applicationId}.previews` returned the updates provider, so
 * 浏览器打开 and 分享动图 both died with ERR_FILE_NOT_FOUND even though the file existed.
 * The previews entry therefore points at [com.relaytester.app.core.update.PreviewProvider],
 * a dedicated subclass; this test refuses a regression to a shared class name.
 */
class ProviderContractTest {

    private fun manifestText(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, "src/main/AndroidManifest.xml").takeIf { it.isFile }?.let {
                return it.readText(Charsets.UTF_8)
            }
            File(dir, "app/src/main/AndroidManifest.xml").takeIf { it.isFile }?.let {
                return it.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
        }
        error("找不到 AndroidManifest.xml（user.dir=${System.getProperty("user.dir")}）")
    }

    @Test
    fun `the two file providers use distinct classes`() {
        val manifest = manifestText()
        val classes = Regex("""android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .filter { it.endsWith("FileProvider") || it.endsWith("PreviewProvider") }
            .toList()
        assertEquals("应当恰好有两个 provider 条目：$classes", 2, classes.size)
        assertEquals(
            "两个 provider 的类必须不同，同类会被系统解析合并：$classes",
            classes.toSet().size,
            classes.size,
        )
        assertTrue(
            "previews 必须用专属子类而非 androidx 原类：$classes",
            classes.contains(".core.update.PreviewProvider"),
        )
    }
}
