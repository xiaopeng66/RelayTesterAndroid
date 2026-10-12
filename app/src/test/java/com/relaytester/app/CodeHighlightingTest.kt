package com.relaytester.app

import com.relaytester.app.feature.fingerprint.CodeKind
import com.relaytester.app.feature.fingerprint.codeSpansFor
import com.relaytester.app.feature.fingerprint.markupSpans
import com.relaytester.app.feature.fingerprint.reasoningSpans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokenizer behind the code views. The spans are pure data (no Compose types), so the
 * offsets and kinds are pinned here exactly as the panel will paint them.
 */
class CodeHighlightingTest {

    @Test
    fun `a tag and its attribute are coloured`() {
        val html = """<svg width="100"></svg>"""
        val spans = markupSpans(html)
        val tags = spans.filter { it.kind == CodeKind.TAG }
        val strings = spans.filter { it.kind == CodeKind.STRING }
        val attrs = spans.filter { it.kind == CodeKind.ATTR }
        // 两个标签名（svg ×2）上色，`<` 与 `/` 一并盖住。
        assertEquals(listOf(0 to 4, 17 to 22), tags.map { it.start to it.end })
        assertEquals(1, attrs.size)
        assertEquals(5 to 10, attrs[0].start to attrs[0].end)
        assertEquals(1, strings.size)
        assertEquals(11 to 16, strings[0].start to strings[0].end)
    }

    @Test
    fun `a doctype and a comment each get one run`() {
        val html = "<!DOCTYPE html><!-- 说明 --><html></html>"
        val spans = markupSpans(html)
        assertEquals(CodeKind.DOCTYPE, spans[0].kind)
        assertEquals(0, spans[0].start)
        assertEquals(15, spans[0].end)
        assertEquals(CodeKind.COMMENT, spans[1].kind)
        assertEquals(15, spans[1].start)
        assertEquals(26, spans[1].end)
    }

    @Test
    fun `a character entity is coloured and plain ampersands are not`() {
        val spans = markupSpans("<html>&amp; a &amp b</html>")
        val entities = spans.filter { it.kind == CodeKind.ENTITY }
        assertEquals(1, entities.size)
        assertEquals(6 to 11, entities[0].start to entities[0].end)
    }

    @Test
    fun `a greater-than inside a quoted value does not end the tag`() {
        val html = """<div title="a > b">text</div>"""
        val spans = markupSpans(html)
        // 如果引号内的 `>` 被当成标签结束，"text" 之后就不会再有 TAG 上色。
        assertTrue(spans.any { it.kind == CodeKind.TAG && html.substring(it.start, it.end) == "<div" })
        assertTrue(spans.any { it.kind == CodeKind.TAG && html.substring(it.start, it.end) == "</div" })
    }

    @Test
    fun `style content is highlighted as css`() {
        val html = "<html><head><style>body{color:#fff}</style></head></html>"
        val spans = markupSpans(html)
        assertTrue("十六进制颜色按数字上色", spans.any { it.kind == CodeKind.NUMBER && it.start == html.indexOf("#fff") })
        assertTrue("属性名（color）按属性上色", spans.any { it.kind == CodeKind.ATTR && it.start == html.indexOf("color") })
    }

    @Test
    fun `script content is highlighted as javascript`() {
        val html = "<html><body><script>const x = 12; // 循环</script></body></html>"
        val spans = markupSpans(html)
        assertTrue("关键字上色", spans.any { it.kind == CodeKind.KEYWORD && it.start == html.indexOf("const") })
        assertTrue("数字上色", spans.any { it.kind == CodeKind.NUMBER && it.start == html.indexOf("12") })
        assertTrue("注释上色", spans.any { it.kind == CodeKind.COMMENT && html.substring(it.start, it.end).contains("循环") })
    }

    @Test
    fun `an unterminated tag stays plain rather than swallowing the file`() {
        val html = "<html><body><div class='x"
        val spans = markupSpans(html)
        assertTrue(spans.any { it.kind == CodeKind.TAG && html.substring(it.start, it.end) == "<html" })
        // 未闭合的标签：属性仍然上色（区域到文末），但不产生越界 span。
        assertTrue(spans.all { it.end <= html.length })
    }

    @Test
    fun `reasoning highlights only fenced code`() {
        val text = "先说明一下：\n```html\n<p class=\"x\">字</p>\n```\n后记没有围栏。"
        val spans = reasoningSpans(text)
        assertTrue(spans.isNotEmpty())
        val fenceStart = text.indexOf("<p ")
        val fenceEnd = text.indexOf("</p>") + 4
        assertTrue("围栏内必须有 span", spans.all { it.start >= fenceStart && it.end <= fenceEnd })
        // 后记与前言（无围栏区域）不得上色。
        assertTrue(spans.none { it.end <= fenceStart })
    }

    @Test
    fun `an unterminated fence is highlighted to the end`() {
        // 流被掐断时围栏可能没有闭合：半块也要上色，而不是整段退化成纯文本。
        val text = "```\n<svg width=\"4\">"
        val spans = reasoningSpans(text)
        assertTrue(spans.any { it.kind == CodeKind.TAG })
    }

    @Test
    fun `json and javascript fences pick their own dialects`() {
        val json = "```json\n{\"a\": 1, \"b\": true}\n```"
        val jsonSpans = reasoningSpans(json)
        assertTrue(jsonSpans.any { it.kind == CodeKind.ATTR })
        assertTrue(jsonSpans.any { it.kind == CodeKind.NUMBER })
        assertTrue(jsonSpans.any { it.kind == CodeKind.KEYWORD })

        val js = "```js\nconst a = 1;\n```"
        assertTrue(reasoningSpans(js).any { it.kind == CodeKind.KEYWORD })
    }

    @Test
    fun `unknown fence languages stay plain`() {
        val text = "```whytex\nsome <text>\n```"
        assertEquals(emptyList<com.relaytester.app.feature.fingerprint.CodeSpan>(), reasoningSpans(text))
    }

    @Test
    fun `codeSpansFor maps the language tag`() {
        assertTrue(codeSpansFor("<p x=\"1\">", "html").isNotEmpty())
        assertTrue(codeSpansFor("<p x=\"1\">", "SVG").isNotEmpty())
        assertEquals(emptyList<com.relaytester.app.feature.fingerprint.CodeSpan>(), codeSpansFor("whatever", ""))
    }
}
