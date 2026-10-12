package com.relaytester.app.feature.fingerprint

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle

/**
 * What a highlighted run is, so the palette can decide its colour.
 *
 * The names are HTML-shaped but reused by the other languages: a JSON key reads as an
 * [ATTR]ibute, a JSON `true`/`false`/`null` as a [KEYWORD], a CSS length as a [NUMBER].
 */
internal enum class CodeKind { TAG, ATTR, STRING, COMMENT, DOCTYPE, ENTITY, NUMBER, KEYWORD }

/** One highlighted run of [start, end) over the source text. */
internal data class CodeSpan(val start: Int, val end: Int, val kind: CodeKind)

/**
 * Colours for one highlighting pass.
 *
 * Two palettes rather than one: the same green that reads on white disappears on the dark
 * surface, and the preview card flips with the theme. Chosen mid-saturation so both stay
 * readable; nothing here is theme-derived because code colours want fixed hues, not the
 * app's accent.
 */
internal data class CodePalette(
    val tag: Color,
    val attr: Color,
    val string: Color,
    val comment: Color,
    val doctype: Color,
    val entity: Color,
    val number: Color,
    val keyword: Color,
)

/** The palette for a light or dark surface. */
internal fun codePalette(dark: Boolean): CodePalette = if (dark) {
    CodePalette(
        tag = Color(0xFF8AB4F8),
        attr = Color(0xFF80CBC4),
        string = Color(0xFFA5D6A7),
        comment = Color(0xFF9AA0A6),
        doctype = Color(0xFFCE93D8),
        entity = Color(0xFFF5B26B),
        number = Color(0xFFF48FB1),
        keyword = Color(0xFFB39DDB),
    )
} else {
    CodePalette(
        tag = Color(0xFF1F5FBF),
        attr = Color(0xFF00796B),
        string = Color(0xFF2E7D32),
        comment = Color(0xFF8A8A8A),
        doctype = Color(0xFF8E24AA),
        entity = Color(0xFFB45309),
        number = Color(0xFFAD1457),
        keyword = Color(0xFF4527A0),
    )
}

/** How many spans one pass may emit before the rest of the text stays plain. */
private const val MAX_SPANS = 8_000

/**
 * Highlights an HTML/XML document: tags, attribute names, quoted values, comments, the
 * doctype, character entities, and — where the document embeds them — the content of
 * `<style>` and `<script>` blocks in their own dialects.
 *
 * One forward scan; the cost is linear in the document and the result is what the source
 * view remembers per document, not per recomposition. A tag whose `>` is never found stays
 * plain rather than swallowing the rest of the file, and the pass gives up adding spans at
 * [MAX_SPANS] — a document that dense is unreadable colour anyway, and the text is still all
 * there.
 */
internal fun markupSpans(text: String): List<CodeSpan> {
    if (text.isEmpty()) return emptyList()
    val spans = mutableListOf<CodeSpan>()
    val lower = text.lowercase()
    val n = text.length
    var i = 0
    while (i < n && spans.size < MAX_SPANS) {
        val c = text[i]
        when {
            c == '<' && lower.startsWith("<!--", i) -> {
                val end = text.indexOf("-->", i + 4)
                val stop = if (end >= 0) end + 3 else n
                spans += CodeSpan(i, stop, CodeKind.COMMENT)
                i = stop
            }
            // Doctype, CDATA, processing instructions: markup-shaped but not a tag.
            c == '<' && text.startsWith("<!", i) -> {
                val end = text.indexOf('>', i)
                val stop = if (end >= 0) end + 1 else n
                spans += CodeSpan(i, stop, CodeKind.DOCTYPE)
                i = stop
            }
            c == '<' && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '/' || text[i + 1] == '?') -> {
                // Find the real `>` — inside a quoted attribute value a `>` is text.
                var j = i + 1
                var quote = '\u0000'
                while (j < n) {
                    val ch = text[j]
                    when {
                        quote != '\u0000' -> if (ch == quote) quote = '\u0000'
                        ch == '"' || ch == '\'' -> quote = ch
                        ch == '>' -> break
                    }
                    j++
                }
                val stop = if (j < n) j + 1 else n
                var k = i + 1
                if (k < n && text[k] == '/') k++
                val nameStart = k
                while (k < n && (text[k].isLetterOrDigit() || text[k] == ':' || text[k] == '-')) k++
                if (k > nameStart) {
                    spans += CodeSpan(i, k, CodeKind.TAG)
                    attributeSpans(text, k, stop, spans)
                    // A `<style>`/`<script>` block's content is CSS/JS, not markup: colour
                    // it in its own dialect and jump past the closing tag. A `/` before the
                    // `>` (self-closing) is not a block and has no content to colour.
                    val name = lower.substring(nameStart, k)
                    val selfClosing = text.lastIndexOf('/', stop - 1) >= k
                    if ((name == "style" || name == "script") && !selfClosing) {
                        val close = lower.indexOf("</$name", stop)
                        if (close > stop) {
                            val body = text.substring(stop, close)
                            val dialectSpans = if (name == "style") cssSpans(body) else jsSpans(body)
                            spans += dialectSpans.map { CodeSpan(it.start + stop, it.end + stop, it.kind) }
                            val gt = text.indexOf('>', close)
                            i = if (gt >= 0) gt + 1 else n
                            continue
                        }
                    }
                }
                i = stop
            }
            c == '&' -> {
                // An entity is `&name;` or `&#123;`: alphanumerics (plus one `#`) between
                // the two, and nothing longer than twelve characters counts.
                var j = i + 1
                while (j < n && j - i <= 12 && (text[j].isLetterOrDigit() || text[j] == '#')) j++
                if (j < n && text[j] == ';' && j > i + 1) {
                    spans += CodeSpan(i, j + 1, CodeKind.ENTITY)
                    i = j + 1
                } else {
                    i++
                }
            }
            else -> i++
        }
    }
    return spans
}

/** Attribute names and quoted values inside one tag's `[from, to)` region. */
private fun attributeSpans(text: String, from: Int, to: Int, spans: MutableList<CodeSpan>) {
    if (spans.size >= MAX_SPANS) return
    val region = text.substring(from, to)
    val pattern = Regex("""([\w:-]+)(\s*=\s*)("[^"]*"|'[^']*'|[^\s>]+)""")
    for (match in pattern.findAll(region)) {
        spans += CodeSpan(from + match.range.first, from + match.groups[1]!!.range.last + 1, CodeKind.ATTR)
        spans += CodeSpan(from + match.groups[3]!!.range.first, from + match.groups[3]!!.range.last + 1, CodeKind.STRING)
        if (spans.size >= MAX_SPANS) return
    }
}

/** CSS inside a `<style>` block: comments, strings, numbers/colours, at-rules, properties. */
private fun cssSpans(text: String): List<CodeSpan> {
    val spans = mutableListOf<CodeSpan>()
    for (match in Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
        spans += CodeSpan(match.range.first, match.range.last + 1, CodeKind.COMMENT)
    }
    for (match in Regex(""""[^"\n]*"|'[^'\n]*'""").findAll(text)) {
        spans += CodeSpan(match.range.first, match.range.last + 1, CodeKind.STRING)
    }
    for (match in Regex("""#[0-9a-fA-F]{3,8}\b|\b\d[\d.]*(px|em|rem|%|s|ms|vh|vw|deg|fr)?\b""").findAll(text)) {
        spans += CodeSpan(match.range.first, match.range.last + 1, CodeKind.NUMBER)
    }
    for (match in Regex("""@[\w-]+""").findAll(text)) {
        spans += CodeSpan(match.range.first, match.range.last + 1, CodeKind.KEYWORD)
    }
    for (match in Regex("""([\w-]+)\s*:""").findAll(text)) {
        spans += CodeSpan(match.range.first, match.groups[1]!!.range.last + 1, CodeKind.ATTR)
    }
    return spans
}

/** JavaScript (and close C-family cousins) inside a `<script>` block or a code fence. */
private fun jsSpans(text: String): List<CodeSpan> {
    val spans = mutableListOf<CodeSpan>()
    for (match in Regex("""//.*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
        spans += CodeSpan(match.range.first, match.range.last + 1, CodeKind.COMMENT)
    }
    for (match in Regex(""""(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`""").findAll(text)) {
        spans += CodeSpan(match.range.first, match.range.last + 1, CodeKind.STRING)
    }
    for (match in Regex("""\b\d[\d._]*\b""").findAll(text)) {
        spans += CodeSpan(match.range.first, match.range.last + 1, CodeKind.NUMBER)
    }
    for (match in Regex(
        """\b(?:var|let|const|function|return|if|else|for|while|new|class|import|export|""" +
            """from|true|false|null|undefined|this|typeof|of|in|switch|case|break|continue|""" +
            """default|try|catch|finally|throw|await|async|def|self|lambda|None|True|False|""" +
            """public|private|fun|val)\b""",
    ).findAll(text)) {
        spans += CodeSpan(match.range.first, match.range.last + 1, CodeKind.KEYWORD)
    }
    return spans
}

/** JSON inside a code fence: keys as attributes, values as strings/numbers/keywords. */
private fun jsonSpans(text: String): List<CodeSpan> {
    val spans = mutableListOf<CodeSpan>()
    for (match in Regex(""""(?:\\.|[^"\\])*"(\s*:)?""").findAll(text)) {
        val kind = if (match.groups[1] != null) CodeKind.ATTR else CodeKind.STRING
        spans += CodeSpan(match.range.first, match.range.last + 1, kind)
    }
    for (match in Regex("""\b\d[\d.eE+-]*\b""").findAll(text)) {
        spans += CodeSpan(match.range.first, match.range.last + 1, CodeKind.NUMBER)
    }
    for (match in Regex("""\b(?:true|false|null)\b""").findAll(text)) {
        spans += CodeSpan(match.range.first, match.range.last + 1, CodeKind.KEYWORD)
    }
    return spans
}

/**
 * Highlights a chain of thought: prose stays plain, and only fenced code blocks are
 * coloured in the fence's own language.
 *
 * The reasoning is free text — highlighting all of it would colour sentences that merely
 * contain a `<` — so the fence is the line between "what the model said" and "what it
 * wrote". An unterminated fence (the stream was cut, or the model never closed it) is
 * highlighted to the end: half a coloured block beats a live stream rendered as plain text.
 */
internal fun reasoningSpans(text: String): List<CodeSpan> {
    val spans = mutableListOf<CodeSpan>()
    val opener = Regex("(?m)^```([A-Za-z0-9+#_-]*)[ \\t]*\\r?\\n")
    var i = 0
    while (spans.size < MAX_SPANS) {
        val open = opener.find(text, i) ?: break
        val bodyStart = open.range.last + 1
        val close = text.indexOf("```", bodyStart)
        val bodyEnd = if (close >= 0) close else text.length
        val body = text.substring(bodyStart, bodyEnd)
        spans += codeSpansFor(body, open.groupValues[1].lowercase()).map {
            CodeSpan(it.start + bodyStart, it.end + bodyStart, it.kind)
        }
        i = if (close >= 0) close + 3 else text.length
        if (i >= text.length) break
    }
    return spans
}

/** The highlighter for one fenced block, by the fence's language tag (any case). */
internal fun codeSpansFor(body: String, language: String): List<CodeSpan> = when (language.lowercase()) {
    // 动画测试的围栏大多数没写语言、写的却是 HTML：裸围栏按标记语言处理是安全的——
    // markupSpans 只给标签状的东西上色，围栏里真是散文时也不会误伤。
    "", "html", "xml", "svg", "markup" -> markupSpans(body)
    "css" -> cssSpans(body)
    "json" -> jsonSpans(body)
    "js", "javascript", "ts", "typescript", "java", "kt", "kotlin", "py", "python",
    "c", "cpp", "go", "rust", "sh", "bash",
    -> jsSpans(body)
    else -> emptyList()
}

/**
 * Paints [spans] over [text]. Comments go italic; everything else is colour only, so the
 * layout the reader has already calibrated does not move by a pixel when colour arrives.
 */
internal fun annotatedCode(text: String, spans: List<CodeSpan>, palette: CodePalette): AnnotatedString =
    buildAnnotatedString {
        append(text)
        for (span in spans) {
            if (span.start < 0 || span.end > text.length || span.start >= span.end) continue
            addStyle(
                SpanStyle(
                    color = span.kind.colorOf(palette),
                    fontStyle = if (span.kind == CodeKind.COMMENT) FontStyle.Italic else null,
                ),
                span.start,
                span.end,
            )
        }
    }

private fun CodeKind.colorOf(palette: CodePalette): Color = when (this) {
    CodeKind.TAG -> palette.tag
    CodeKind.ATTR -> palette.attr
    CodeKind.STRING -> palette.string
    CodeKind.COMMENT -> palette.comment
    CodeKind.DOCTYPE -> palette.doctype
    CodeKind.ENTITY -> palette.entity
    CodeKind.NUMBER -> palette.number
    CodeKind.KEYWORD -> palette.keyword
}
