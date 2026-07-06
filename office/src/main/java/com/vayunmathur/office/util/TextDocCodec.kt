package com.vayunmathur.office.util

import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.odf.ListType
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
 * style; each paragraph is terminated by a newline cell whose style carries the paragraph's layout.
 * Rebuilding groups consecutive equal-style chars into spans and splits paragraphs on newline cells,
 * so the result is always a valid document (we never merge serialized XML).
 *
 * Only plain paragraph content is representable here (see [isEligible]); documents with tables,
 * images, footnotes, headers/footers, comments, bookmarks, fields, page setup, etc. fall back to the
 * lossless line-level XML CRDT so nothing is dropped.
 */
object TextDocCodec {
    private const val SEP = '\u0002'
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class CellStyle(
        // Inline (character) style.
        val b: Boolean = false,
        val i: Boolean = false,
        val u: Boolean = false,
        val st: Boolean = false,
        val sz: Float? = null,
        val col: Long? = null,
        val href: String? = null,
        val ff: String? = null,   // font family
        val bg: Long? = null,     // highlight / background color
        val sup: Boolean = false, // superscript
        val sub: Boolean = false, // subscript
        // Paragraph layout (only on newline cells).
        val pstyle: String? = null,
        val al: String? = null,   // alignment
        val ml: Float = 0f,       // left margin (indent)
        val ti: Float = 0f,       // first-line indent
        val ll: Int = 0,          // list level
        val lt: String? = null,   // list type
        val chk: Boolean = false, // checkbox checked
        val lh: Float? = null,    // line-height percent
        val pbg: Long? = null,    // paragraph background color
    )

    /**
     * True only if the document is representable as plain paragraphs with no content the codec can't
     * carry — so switching to char-level never loses data. Anything richer uses the line-level codec.
     */
    fun isEligible(doc: OdfDocument): Boolean {
        if (doc !is OdfDocument.TextDocument) return false
        if (doc.footnotes.isNotEmpty() || doc.headerParagraphs.isNotEmpty() || doc.footerParagraphs.isNotEmpty() ||
            doc.bookmarks.isNotEmpty() || doc.changes.isNotEmpty() || doc.pageSetup != null
        ) return false
        for (block in doc.content) {
            val para = (block as? OdfContentBlock.Paragraph)?.paragraph ?: return false
            for (s in para.spans) if (s.annotation != null || s.field != null || s.refName != null) return false
        }
        return true
    }

    private fun spanStyle(s: OdfSpan) = CellStyle(
        b = s.bold, i = s.italic, u = s.underline, st = s.strikethrough,
        sz = s.fontSize, col = s.color, href = s.href, ff = s.fontFamily, bg = s.backgroundColor,
        sup = s.superscript, sub = s.subscript,
    )

    private fun paraStyle(p: OdfParagraph) = CellStyle(
        pstyle = p.style.name, al = p.alignment?.let { alignName(it) }, ml = p.marginLeft, ti = p.textIndent,
        ll = p.listLevel, lt = p.listType.name, chk = p.listChecked, lh = p.lineHeightPercent, pbg = p.backgroundColor,
    )

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
                        fontFamily = cs.ff, backgroundColor = cs.bg, superscript = cs.sup, subscript = cs.sub,
                    )
                )
            }
            curText = StringBuilder()
        }

        fun flushPara(newlineKey: String?) {
            flushSpan()
            val cs = newlineKey?.let { runCatching { json.decodeFromString<CellStyle>(it) }.getOrNull() }
            val style = cs?.pstyle?.let { runCatching { ParagraphStyle.valueOf(it) }.getOrNull() } ?: ParagraphStyle.BODY
            val listType = cs?.lt?.let { runCatching { ListType.valueOf(it) }.getOrNull() } ?: ListType.BULLET
            blocks.add(OdfContentBlock.Paragraph(OdfParagraph(
                spans = if (spans.isEmpty()) listOf(OdfSpan("")) else spans,
                style = style,
                alignment = parseAlign(cs?.al),
                marginLeft = cs?.ml ?: 0f,
                textIndent = cs?.ti ?: 0f,
                listLevel = cs?.ll ?: 0,
                listType = listType,
                listChecked = cs?.chk ?: false,
                lineHeightPercent = cs?.lh,
                backgroundColor = cs?.pbg,
            )))
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

    private fun alignName(a: TextAlign): String = when (a) {
        TextAlign.Start -> "start"; TextAlign.End -> "end"; TextAlign.Center -> "center"
        TextAlign.Justify -> "justify"; TextAlign.Left -> "left"; TextAlign.Right -> "right"; else -> "start"
    }

    private fun parseAlign(s: String?): TextAlign? = when (s) {
        "start" -> TextAlign.Start; "end" -> TextAlign.End; "center" -> TextAlign.Center
        "justify" -> TextAlign.Justify; "left" -> TextAlign.Left; "right" -> TextAlign.Right; else -> null
    }
}
