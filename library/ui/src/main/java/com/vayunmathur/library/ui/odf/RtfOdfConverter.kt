package com.vayunmathur.library.ui.odf

/**
 * Converter between RTF (Rich Text Format) and an [OdfDocument.TextDocument]. Export emits a
 * font/color table plus grouped run formatting (bold/italic/underline/strike/color/size),
 * paragraph alignment, headings, and simple lists. Import is a tolerant control-word reader
 * handling the common subset (character formatting, colours, `\'hh`/`\uN` text, alignment, `\par`).
 */
object RtfOdfConverter {

    // region ODF -> RTF

    fun odfToRtf(doc: OdfDocument.TextDocument): String {
        // Collect colour palette (index 0 = auto).
        val colors = LinkedHashMap<Long, Int>()
        fun colorId(c: Long?): Int? { c ?: return null; return colors.getOrPut(c) { colors.size + 1 } }
        // Pre-scan so the colour table is complete before the body references it.
        for (block in doc.content) if (block is OdfContentBlock.Paragraph) for (s in block.paragraph.spans) { colorId(s.color); colorId(s.backgroundColor) }

        val body = StringBuilder()
        for (block in doc.content) when (block) {
            is OdfContentBlock.Paragraph -> body.append(rtfParagraph(block.paragraph, ::colorId))
            is OdfContentBlock.Table -> for (row in block.table.rows) for (cell in row.cells) for (p in cell.paragraphs) body.append(rtfParagraph(p, ::colorId))
            is OdfContentBlock.TableOfContents -> for (e in block.entries) body.append(rtfParagraph(e, ::colorId))
            is OdfContentBlock.Formula -> body.append(rtfParagraph(OdfParagraph(listOf(OdfSpan(block.mathml))), ::colorId))
            else -> {}
        }

        val sb = StringBuilder()
        sb.append("{\\rtf1\\ansi\\ansicpg1252\\deff0")
        sb.append("{\\fonttbl{\\f0\\fswiss Calibri;}{\\f1\\froman Times New Roman;}}")
        sb.append("{\\colortbl;")
        for (c in colors.keys) sb.append("\\red${(c shr 16) and 0xFF}\\green${(c shr 8) and 0xFF}\\blue${c and 0xFF};")
        sb.append("}")
        sb.append("\\viewkind4\\uc1\n")
        sb.append(body)
        sb.append("}")
        return sb.toString()
    }

    private fun rtfParagraph(p: OdfParagraph, colorId: (Long?) -> Int?): String {
        val sb = StringBuilder("\\pard")
        when (p.alignment) {
            androidx.compose.ui.text.style.TextAlign.Center -> sb.append("\\qc")
            androidx.compose.ui.text.style.TextAlign.End, androidx.compose.ui.text.style.TextAlign.Right -> sb.append("\\qr")
            androidx.compose.ui.text.style.TextAlign.Justify -> sb.append("\\qj")
            else -> {}
        }
        if (p.style == ParagraphStyle.LIST_ITEM) sb.append("\\li${720 * p.listLevel.coerceAtLeast(1)}\\fi-360")
        val headingSize = when (p.style) {
            ParagraphStyle.HEADING1 -> 36; ParagraphStyle.HEADING2 -> 32
            ParagraphStyle.HEADING3 -> 28; ParagraphStyle.HEADING4 -> 24; else -> 0
        }
        sb.append(" ")
        // List marker.
        if (p.style == ParagraphStyle.LIST_ITEM) {
            val marker = when (p.listType) {
                ListType.NUMBERED -> "${p.listItemIndex.coerceAtLeast(1)}. "
                ListType.CHECKBOX -> if (p.listChecked) "\\u9745 " else "\\u9744 "
                ListType.BULLET -> "\\bullet "
            }
            sb.append(marker)
        }
        val headingBold = headingSize > 0
        for (span in p.spans) sb.append(rtfRun(span, colorId, headingSize, headingBold))
        sb.append("\\par\n")
        return sb.toString()
    }

    private fun rtfRun(span: OdfSpan, colorId: (Long?) -> Int?, headingSize: Int, headingBold: Boolean): String {
        if (span.text.isEmpty()) return ""
        val sb = StringBuilder("{")
        if (span.bold || headingBold) sb.append("\\b")
        if (span.italic) sb.append("\\i")
        if (span.underline) sb.append("\\ul")
        if (span.strikethrough) sb.append("\\strike")
        if (span.superscript) sb.append("\\super")
        if (span.subscript) sb.append("\\sub")
        colorId(span.color)?.let { sb.append("\\cf$it") }
        colorId(span.backgroundColor)?.let { sb.append("\\highlight$it") }
        val size = when { span.fontSize != null -> (span.fontSize * 2).toInt(); headingSize > 0 -> headingSize; else -> 0 }
        if (size > 0) sb.append("\\fs$size")
        sb.append(" ")
        sb.append(escapeRtf(span.text))
        sb.append("}")
        return sb.toString()
    }

    private fun escapeRtf(text: String): String {
        val sb = StringBuilder()
        for (c in text) when {
            c == '\\' -> sb.append("\\\\")
            c == '{' -> sb.append("\\{")
            c == '}' -> sb.append("\\}")
            c == '\n' -> sb.append("\\line ")
            c == '\t' -> sb.append("\\tab ")
            c.code in 0x20..0x7E -> sb.append(c)
            else -> sb.append("\\u${c.code}?")
        }
        return sb.toString()
    }

    // endregion

    // region RTF -> ODF

    fun rtfToOdf(rtf: String, title: String = ""): OdfDocument.TextDocument {
        val blocks = ArrayList<OdfContentBlock>()
        val colorTable = ArrayList<Long?>() // entry 0 filled by the leading ';' in \colortbl
        var spans = ArrayList<OdfSpan>()
        val buf = StringBuilder()

        data class Fmt(var bold: Boolean = false, var italic: Boolean = false, var underline: Boolean = false,
                       var strike: Boolean = false, var color: Long? = null, var size: Float? = null,
                       var sup: Boolean = false, var sub: Boolean = false)
        val stack = ArrayDeque<Fmt>().apply { addLast(Fmt()) }
        fun fmt() = stack.last()
        var align: androidx.compose.ui.text.style.TextAlign? = null
        var inColorTbl = false
        var colorTblDepth = -1
        var pendingColorR = 0; var pendingColorG = 0; var pendingColorB = 0; var colorHasVal = false
        var skipGroupDepth = -1
        var depth = 0

        fun flushBuf() {
            if (buf.isEmpty()) return
            val f = fmt()
            spans.add(OdfSpan(buf.toString(), bold = f.bold, italic = f.italic, underline = f.underline,
                strikethrough = f.strike, color = f.color, fontSize = f.size, superscript = f.sup, subscript = f.sub))
            buf.setLength(0)
        }
        fun flushPara() {
            flushBuf()
            if (spans.isEmpty()) spans.add(OdfSpan(""))
            blocks.add(OdfContentBlock.Paragraph(OdfParagraph(spans, alignment = align)))
            spans = ArrayList()
        }

        var i = 0
        while (i < rtf.length) {
            val c = rtf[i]
            when (c) {
                '{' -> { depth++; stack.addLast(fmt().copy()); i++ }
                '}' -> {
                    flushBuf()
                    if (stack.size > 1) stack.removeLast()
                    if (skipGroupDepth == depth) skipGroupDepth = -1
                    depth--
                    if (inColorTbl && depth < colorTblDepth) inColorTbl = false
                    i++
                }
                '\\' -> {
                    // Control word or symbol.
                    if (i + 1 < rtf.length && !rtf[i + 1].isLetter()) {
                        val sym = rtf[i + 1]
                        when (sym) {
                            '\'' -> { // \'hh hex byte
                                if (i + 3 < rtf.length) { val hex = rtf.substring(i + 2, i + 4); hex.toIntOrNull(16)?.let { if (skipGroupDepth < 0) buf.append(it.toChar()) }; i += 4 } else i += 2
                            }
                            '\\', '{', '}' -> { if (skipGroupDepth < 0) buf.append(sym); i += 2 }
                            '*' -> { skipGroupDepth = depth; i += 2 }
                            '~' -> { if (skipGroupDepth < 0) buf.append('\u00A0'); i += 2 }
                            '\n', '\r' -> { i += 2 }
                            else -> i += 2
                        }
                    } else {
                        var j = i + 1
                        while (j < rtf.length && rtf[j].isLetter()) j++
                        val word = rtf.substring(i + 1, j)
                        var numStr = ""
                        var k = j
                        if (k < rtf.length && (rtf[k] == '-' || rtf[k].isDigit())) { val s = k; if (rtf[k] == '-') k++; while (k < rtf.length && rtf[k].isDigit()) k++; numStr = rtf.substring(s, k) }
                        if (k < rtf.length && rtf[k] == ' ') k++ // delimiter space consumed
                        val num = numStr.toIntOrNull()
                        i = k
                        if (skipGroupDepth in 0..depth && word != "colortbl") { continue }
                        when (word) {
                            "colortbl" -> { inColorTbl = true; colorTblDepth = depth; colorHasVal = false; pendingColorR = 0; pendingColorG = 0; pendingColorB = 0 }
                            "fonttbl", "stylesheet", "info", "generator", "pict", "object", "themedata", "colorschememapping" -> skipGroupDepth = depth
                            "red" -> if (inColorTbl) { pendingColorR = num ?: 0; colorHasVal = true }
                            "green" -> if (inColorTbl) { pendingColorG = num ?: 0; colorHasVal = true }
                            "blue" -> if (inColorTbl) { pendingColorB = num ?: 0; colorHasVal = true }
                            "par", "sect" -> if (!inColorTbl) flushPara()
                            "pard" -> { align = null }
                            "qc" -> align = androidx.compose.ui.text.style.TextAlign.Center
                            "qr" -> align = androidx.compose.ui.text.style.TextAlign.End
                            "qj" -> align = androidx.compose.ui.text.style.TextAlign.Justify
                            "ql" -> align = androidx.compose.ui.text.style.TextAlign.Start
                            "b" -> { flushBuf(); fmt().bold = num != 0 }
                            "i" -> { flushBuf(); fmt().italic = num != 0 }
                            "ul" -> { flushBuf(); fmt().underline = num != 0 }
                            "ulnone" -> { flushBuf(); fmt().underline = false }
                            "strike" -> { flushBuf(); fmt().strike = num != 0 }
                            "super" -> { flushBuf(); fmt().sup = num != 0 }
                            "sub" -> { flushBuf(); fmt().sub = num != 0 }
                            "nosupersub" -> { flushBuf(); fmt().sup = false; fmt().sub = false }
                            "cf" -> { flushBuf(); fmt().color = num?.let { colorTable.getOrNull(it) } }
                            "fs" -> { flushBuf(); fmt().size = num?.let { it / 2f } }
                            "line" -> if (skipGroupDepth < 0) buf.append('\n')
                            "tab" -> if (skipGroupDepth < 0) buf.append('\t')
                            "u" -> { if (skipGroupDepth < 0 && num != null) buf.append(String(Character.toChars(num and 0xFFFF))); }
                            else -> {}
                        }
                    }
                }
                ';' -> {
                    if (inColorTbl) {
                        colorTable.add(if (colorHasVal) 0xFF000000L or (pendingColorR.toLong() shl 16) or (pendingColorG.toLong() shl 8) or pendingColorB.toLong() else null)
                        colorHasVal = false; pendingColorR = 0; pendingColorG = 0; pendingColorB = 0
                    }
                    i++
                }
                '\n', '\r' -> i++
                else -> { if (skipGroupDepth < 0 && !inColorTbl) buf.append(c); i++ }
            }
        }
        flushPara()
        if (blocks.isEmpty()) blocks.add(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("")))))
        return OdfDocument.TextDocument(title = title, content = blocks)
    }

    // endregion
}
