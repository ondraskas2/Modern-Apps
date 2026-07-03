package com.vayunmathur.office.util

import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Projects a paragraph-only [OdfDocument.TextDocument] to/from a flat list of **character cells** so
 * it can be merged by [DocumentCrdt] at *character* granularity (conflict-free, Yjs-style) rather
 * than at line/element granularity.
 *
 * Each cell is `"<styleJson>\u0002<char>"`. A paragraph's characters each carry their span's inline
 * style; each paragraph is terminated by a newline cell whose style carries the paragraph style.
 * Rebuilding groups consecutive equal-style chars into spans and splits paragraphs on newline cells,
 * so the result is always a valid document (we never merge serialized XML).
 *
 * Only paragraph content is representable here; use [isEligible] to check before using this codec
 * (documents with tables/images/etc. fall back to the line-level XML CRDT).
 */
object TextDocCodec {
    private const val SEP = '\u0002'
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class CellStyle(
        val b: Boolean = false,
        val i: Boolean = false,
        val u: Boolean = false,
        val st: Boolean = false,
        val sz: Float? = null,
        val col: Long? = null,
        val href: String? = null,
        val pstyle: String? = null, // paragraph style name (only on newline cells)
    )

    /** True if the document is representable as pure paragraphs (safe for char-level merge). */
    fun isEligible(doc: OdfDocument): Boolean =
        doc is OdfDocument.TextDocument && doc.content.all { it is OdfContentBlock.Paragraph }

    private fun spanStyle(s: OdfSpan) = CellStyle(
        b = s.bold, i = s.italic, u = s.underline, st = s.strikethrough,
        sz = s.fontSize, col = s.color, href = s.href,
    )

    private fun paraStyle(p: OdfParagraph) = CellStyle(pstyle = p.style.name)

    fun toCells(doc: OdfDocument.TextDocument): List<String> {
        val cells = ArrayList<String>()
        for (block in doc.content) {
            val para = (block as? OdfContentBlock.Paragraph)?.paragraph ?: continue
            for (span in para.spans) {
                val key = json.encodeToString(spanStyle(span))
                for (ch in span.text) cells.add("$key$SEP$ch")
            }
            cells.add("${json.encodeToString(paraStyle(para))}$SEP\n")
        }
        return cells
    }

    /** Rebuilds a document from merged cells, copying non-content fields from [base]. */
    fun fromCells(cells: List<String>, base: OdfDocument.TextDocument): OdfDocument.TextDocument {
        val blocks = ArrayList<OdfContentBlock>()
        var spans = ArrayList<OdfSpan>()
        var curKey: String? = null
        var curText = StringBuilder()

        fun flushSpan() {
            if (curKey != null && curText.isNotEmpty()) {
                val cs = runCatching { json.decodeFromString<CellStyle>(curKey!!) }.getOrNull() ?: CellStyle()
                spans.add(
                    OdfSpan(
                        text = curText.toString(), bold = cs.b, italic = cs.i, underline = cs.u,
                        strikethrough = cs.st, fontSize = cs.sz, color = cs.col, href = cs.href,
                    )
                )
            }
            curText = StringBuilder()
        }

        fun flushPara(newlineKey: String?) {
            flushSpan()
            val cs = newlineKey?.let { runCatching { json.decodeFromString<CellStyle>(it) }.getOrNull() }
            val style = cs?.pstyle?.let { runCatching { ParagraphStyle.valueOf(it) }.getOrNull() } ?: ParagraphStyle.BODY
            blocks.add(OdfContentBlock.Paragraph(OdfParagraph(spans = if (spans.isEmpty()) listOf(OdfSpan("")) else spans, style = style)))
            spans = ArrayList()
            curKey = null
        }

        for (cell in cells) {
            val sep = cell.indexOf(SEP)
            if (sep < 0) continue
            val key = cell.substring(0, sep)
            val value = cell.substring(sep + 1)
            if (value == "\n") {
                flushPara(key)
            } else {
                if (key != curKey) { flushSpan(); curKey = key }
                curText.append(value)
            }
        }
        // Trailing text with no final newline cell => final paragraph.
        if (curText.isNotEmpty() || spans.isNotEmpty()) flushPara(null)
        if (blocks.isEmpty()) blocks.add(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("")))))
        return base.copy(content = blocks)
    }
}
