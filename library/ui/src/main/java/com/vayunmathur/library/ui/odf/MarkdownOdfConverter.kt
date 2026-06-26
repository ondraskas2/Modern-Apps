package com.vayunmathur.library.ui.odf

/**
 * Pure, Android-free converter between a Markdown string and an in-memory
 * [OdfDocument.TextDocument]. Used so the apps can store Markdown while editing through the shared
 * ODF editor. The mapping is line-oriented: every Markdown line becomes one [OdfParagraph] (which is
 * how the run editor models lines), so editing/round-tripping preserves the document structure.
 *
 * Well-supported Markdown maps richly (headings, bold/italic/strike, inline & fenced code, links,
 * bullet/numbered lists, task checkboxes, blockquotes, horizontal rules). Anything unsupported —
 * notably math (`$…$`, `$$…$$`) and raw LaTeX — is preserved verbatim as literal text so content is
 * never lost. Round-tripping normalizes markers (`__`→`**`, `*`/`+`→`-`, headings 5–6→4, list
 * renumbering), which is acceptable.
 */
object MarkdownOdfConverter {

    // Marker values chosen so the editor toolbar (restricted to markdown-representable actions)
    // never produces them, making detection on the ODF→MD path unambiguous.
    private const val CODE_SPAN_BG = 0xFFEDEDEDL          // inline `code` background
    private const val CODE_BLOCK_MARKER = 0x00000010L     // paragraph.backgroundColor for fenced code lines
    private const val QUOTE_MARKER = 0x00000020L          // paragraph.backgroundColor for blockquote lines
    private const val HR_COLOR = 0xFF888888L              // paragraph.borderColor for horizontal rules
    private const val LINK_COLOR = 0xFF0066CCL
    private const val MONO = "monospace"

    internal const val UNCHECKED = "\u2610 "  // "☐ "
    internal const val CHECKED = "\u2611 "     // "☑ "

    // region Markdown -> ODF

    fun markdownToOdf(markdown: String, title: String = ""): OdfDocument.TextDocument {
        val lines = markdown.split("\n")
        val blocks = ArrayList<OdfContentBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = fenceInfo(line)
            if (fence != null) {
                // Fenced code block: consume until the closing fence (or EOF).
                i++
                while (i < lines.size && fenceInfo(lines[i]) == null) {
                    blocks.add(codeBlockParagraph(lines[i]))
                    i++
                }
                if (i < lines.size) i++ // skip closing fence
                continue
            }
            blocks.add(lineToParagraph(line))
            i++
        }
        if (blocks.isEmpty()) blocks.add(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = "")))))
        // Renumber ordered lists so they display correctly immediately on load (parsing assigns 1 to
        // every item; the editor only renumbers on edit).
        return renumberLists(OdfDocument.TextDocument(title = title, content = blocks))
    }

    /** Returns the language (possibly "") if [line] opens/closes a ``` fence, else null. */
    private fun fenceInfo(line: String): String? {
        val t = line.trimStart()
        return if (t.startsWith("```")) t.removePrefix("```").trim() else null
    }

    private fun codeBlockParagraph(raw: String): OdfContentBlock =
        OdfContentBlock.Paragraph(
            OdfParagraph(
                spans = listOf(OdfSpan(text = raw, fontFamily = MONO, backgroundColor = CODE_SPAN_BG)),
                backgroundColor = CODE_BLOCK_MARKER
            )
        )

    private fun lineToParagraph(line: String): OdfContentBlock {
        // Horizontal rule
        if (line.trim().let { it == "---" || it == "***" || it == "___" }) {
            return OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = "")), borderColor = HR_COLOR))
        }
        // Heading
        val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
        if (heading != null) {
            val level = heading.groupValues[1].length
            val style = when (level) {
                1 -> ParagraphStyle.HEADING1
                2 -> ParagraphStyle.HEADING2
                3 -> ParagraphStyle.HEADING3
                else -> ParagraphStyle.HEADING4
            }
            return OdfContentBlock.Paragraph(OdfParagraph(parseInline(heading.groupValues[2]), style = style))
        }
        // Blockquote
        if (line.trimStart().startsWith(">")) {
            val content = line.trimStart().removePrefix(">").let { if (it.startsWith(" ")) it.substring(1) else it }
            return OdfContentBlock.Paragraph(
                OdfParagraph(parseInline(content), backgroundColor = QUOTE_MARKER, marginLeft = 24f)
            )
        }
        // List item (bullet / numbered / task)
        val list = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$").find(line)
        if (list != null) {
            val indent = list.groupValues[1].length
            val level = (indent / 2) + 1
            val marker = list.groupValues[2]
            val rest = list.groupValues[3]
            val numbered = marker[0].isDigit()
            // Task checkbox (only for bullet markers): a CHECKBOX list item.
            val task = if (!numbered) Regex("^\\[([ xX])]\\s+(.*)$").find(rest) else null
            if (task != null) {
                val checked = task.groupValues[1].lowercase() == "x"
                return OdfContentBlock.Paragraph(
                    OdfParagraph(
                        spans = parseInline(task.groupValues[2]),
                        style = ParagraphStyle.LIST_ITEM,
                        listLevel = level,
                        listType = ListType.CHECKBOX,
                        listChecked = checked,
                        listItemIndex = 1,
                    )
                )
            }
            return OdfContentBlock.Paragraph(
                OdfParagraph(
                    spans = parseInline(rest),
                    style = ParagraphStyle.LIST_ITEM,
                    listLevel = level,
                    listType = if (numbered) ListType.NUMBERED else ListType.BULLET,
                    listItemIndex = 1
                )
            )
        }
        // Plain / blank line
        return OdfContentBlock.Paragraph(OdfParagraph(parseInline(line)))
    }

    /** Parses inline markdown into styled character spans (coalesced). */
    private fun parseInline(text: String): List<OdfSpan> {
        val out = ArrayList<OdfSpan>()
        emitInline(text, OdfSpan(text = ""), out)
        if (out.isEmpty()) out.add(OdfSpan(text = ""))
        return coalesce(out)
    }

    private fun emitInline(text: String, style: OdfSpan, out: MutableList<OdfSpan>) {
        var i = 0
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) { out.add(style.copy(text = literal.toString())); literal.setLength(0) }
        }
        fun wordBoundaryBefore(): Boolean = i == 0 || !text[i - 1].isLetterOrDigit()
        while (i < text.length) {
            // Math (preserved verbatim, delimiters included): $$...$$ then $...$
            if (text.startsWith("$$", i)) {
                val end = text.indexOf("$$", i + 2)
                if (end >= 0) { flush(); out.add(style.copy(text = text.substring(i, end + 2))); i = end + 2; continue }
            }
            if (text[i] == '$') {
                val end = findMathClose(text, i)
                if (end > i) { flush(); out.add(style.copy(text = text.substring(i, end + 1))); i = end + 1; continue }
            }
            // Inline code (literal content)
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flush(); out.add(style.copy(text = text.substring(i + 1, end), fontFamily = MONO, backgroundColor = CODE_SPAN_BG))
                    i = end + 1; continue
                }
            }
            // Link [label](url)
            if (text[i] == '[') {
                val link = matchLink(text, i)
                if (link != null) {
                    flush()
                    emitInline(link.label, style.copy(href = link.url, underline = true, color = LINK_COLOR), out)
                    i = link.next; continue
                }
            }
            // Bold+italic ***...*** / ___...___
            val tripleStar = text.startsWith("***", i)
            val tripleUnd = text.startsWith("___", i) && wordBoundaryBefore()
            if (tripleStar || tripleUnd) {
                val m = if (tripleStar) "***" else "___"
                val end = text.indexOf(m, i + 3)
                if (end >= 0) { flush(); emitInline(text.substring(i + 3, end), style.copy(bold = true, italic = true), out); i = end + 3; continue }
            }
            // Bold ** / __
            val boldStar = text.startsWith("**", i)
            val boldUnd = text.startsWith("__", i) && wordBoundaryBefore()
            if (boldStar || boldUnd) {
                val m = if (boldStar) "**" else "__"
                val end = text.indexOf(m, i + 2)
                if (end >= 0) { flush(); emitInline(text.substring(i + 2, end), style.copy(bold = true), out); i = end + 2; continue }
            }
            // Strikethrough ~~
            if (text.startsWith("~~", i)) {
                val end = text.indexOf("~~", i + 2)
                if (end >= 0) { flush(); emitInline(text.substring(i + 2, end), style.copy(strikethrough = true), out); i = end + 2; continue }
            }
            // Italic * / _
            val italStar = text[i] == '*'
            val italUnd = text[i] == '_' && wordBoundaryBefore()
            if (italStar || italUnd) {
                val ch = text[i]
                val end = text.indexOf(ch, i + 1)
                if (end > i) { flush(); emitInline(text.substring(i + 1, end), style.copy(italic = true), out); i = end + 1; continue }
            }
            literal.append(text[i]); i++
        }
        flush()
    }

    private fun findMathClose(text: String, start: Int): Int {
        var j = start + 1
        while (j < text.length) {
            if (text[j] == '$') return j
            j++
        }
        return -1
    }

    private data class LinkMatch(val label: String, val url: String, val next: Int)

    private fun matchLink(text: String, start: Int): LinkMatch? {
        // [label](url) with balanced-ish brackets in label.
        var depth = 0
        var j = start
        var labelEnd = -1
        while (j < text.length) {
            when (text[j]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) { labelEnd = j; break } }
            }
            j++
        }
        if (labelEnd < 0 || labelEnd + 1 >= text.length || text[labelEnd + 1] != '(') return null
        val urlEnd = text.indexOf(')', labelEnd + 2)
        if (urlEnd < 0) return null
        val label = text.substring(start + 1, labelEnd)
        val url = text.substring(labelEnd + 2, urlEnd)
        return LinkMatch(label, url, urlEnd + 1)
    }

    /** Merges adjacent spans with identical styling. */
    private fun coalesce(spans: List<OdfSpan>): List<OdfSpan> {
        if (spans.isEmpty()) return spans
        val out = ArrayList<OdfSpan>()
        var cur = spans[0]
        val sb = StringBuilder(cur.text)
        for (k in 1 until spans.size) {
            val s = spans[k]
            if (s.copy(text = "") == cur.copy(text = "")) {
                sb.append(s.text)
            } else {
                out.add(cur.copy(text = sb.toString())); cur = s; sb.setLength(0); sb.append(s.text)
            }
        }
        out.add(cur.copy(text = sb.toString()))
        return out
    }

    // endregion

    // region ODF -> Markdown

    fun odfToMarkdown(doc: OdfDocument.TextDocument): String {
        val lines = ArrayList<String>()
        var inCodeBlock = false
        val numberedCounters = HashMap<Int, Int>()
        for (block in doc.content) {
            val para = (block as? OdfContentBlock.Paragraph)?.paragraph
            if (para == null) continue
            val isCode = para.backgroundColor == CODE_BLOCK_MARKER
            if (isCode && !inCodeBlock) { lines.add("```"); inCodeBlock = true }
            if (!isCode && inCodeBlock) { lines.add("```"); inCodeBlock = false }
            // Re-number contiguous numbered list items per level; any other paragraph resets.
            val isNumbered = !isCode && para.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.NUMBERED
            var number = 1
            if (isNumbered) {
                val level = para.listLevel.coerceAtLeast(1)
                number = (numberedCounters[level] ?: 0) + 1
                numberedCounters[level] = number
                numberedCounters.keys.filter { it > level }.toList().forEach { numberedCounters.remove(it) }
            } else if (!isCode) {
                numberedCounters.clear()
            }
            lines.add(paragraphToLine(para, isCode, number))
        }
        if (inCodeBlock) lines.add("```")
        return lines.joinToString("\n")
    }

    private fun paragraphToLine(para: OdfParagraph, isCode: Boolean, listNumber: Int): String {
        if (isCode) return para.spans.joinToString("") { it.text }
        // Horizontal rule
        if (para.borderColor != null && para.spans.all { it.text.isEmpty() }) return "---"

        val quote = para.backgroundColor == QUOTE_MARKER
        when (para.style) {
            ParagraphStyle.HEADING1 -> return "# " + emitSpans(para.spans)
            ParagraphStyle.HEADING2 -> return "## " + emitSpans(para.spans)
            ParagraphStyle.HEADING3 -> return "### " + emitSpans(para.spans)
            ParagraphStyle.HEADING4 -> return "#### " + emitSpans(para.spans)
            ParagraphStyle.LIST_ITEM -> {
                val indent = "  ".repeat((para.listLevel - 1).coerceAtLeast(0))
                return when (para.listType) {
                    ListType.NUMBERED -> indent + "$listNumber. " + emitSpans(para.spans)
                    ListType.CHECKBOX -> indent + (if (para.listChecked) "- [x] " else "- [ ] ") + emitSpans(para.spans)
                    ListType.BULLET -> indent + "- " + emitSpans(para.spans)
                }
            }
            else -> {}
        }
        val body = emitSpans(para.spans)
        return if (quote) "> $body" else body
    }

    /** Drops the first [n] characters (the checkbox glyph + space) from the span list. */
    private fun stripPrefix(spans: List<OdfSpan>, n: Int): List<OdfSpan> {
        var remaining = n
        val out = ArrayList<OdfSpan>()
        for (s in spans) {
            if (remaining <= 0) { out.add(s); continue }
            if (s.text.length <= remaining) { remaining -= s.text.length }
            else { out.add(s.copy(text = s.text.substring(remaining))); remaining = 0 }
        }
        return out
    }

    private fun emitSpans(spans: List<OdfSpan>): String =
        spans.joinToString("") { emitSpan(it) }

    private fun emitSpan(span: OdfSpan): String {
        if (span.text.isEmpty()) return ""
        // Inline code is exclusive/literal.
        if (span.fontFamily == MONO) {
            val code = "`${span.text}`"
            return if (span.href != null) "[$code](${span.href})" else code
        }
        var t = span.text
        if (span.strikethrough) t = "~~$t~~"
        if (span.italic) t = "*$t*"
        if (span.bold) t = "**$t**"
        if (span.href != null) t = "[$t](${span.href})"
        return t
    }

    // endregion
}
