package com.vayunmathur.office.odf

import androidx.compose.ui.text.style.TextAlign

object OdfSerializer {

    fun serialize(document: OdfDocument): String {
        return when (document) {
            is OdfDocument.TextDocument -> serializeTextDocument(document)
            is OdfDocument.Spreadsheet -> serializeSpreadsheet(document)
            is OdfDocument.Presentation -> serializePresentation(document)
            is OdfDocument.Drawing -> serializePresentation(
                OdfDocument.Presentation(document.title, document.pages, document.metadata, document.images)
            )
        }
    }

    private fun serializeTextDocument(doc: OdfDocument.TextDocument): String {
        val styles = mutableMapOf<String, SpanStyleDef>()
        val paraStyles = mutableMapOf<String, ParaStyleDef>()
        val body = StringBuilder()

        var listOpen = false
        for (block in doc.content) {
            when (block) {
                is OdfContentBlock.Paragraph -> {
                    val para = block.paragraph
                    if (para.style == ParagraphStyle.LIST_ITEM) {
                        if (!listOpen) { body.append("<text:list>"); listOpen = true }
                        body.append("<text:list-item>")
                        serializeParagraph(body, para, styles, paraStyles, "text:p")
                        body.append("</text:list-item>")
                    } else {
                        if (listOpen) { body.append("</text:list>"); listOpen = false }
                        val tag = when (para.style) {
                            ParagraphStyle.HEADING1, ParagraphStyle.HEADING2,
                            ParagraphStyle.HEADING3, ParagraphStyle.HEADING4 -> "text:h"
                            else -> "text:p"
                        }
                        serializeParagraph(body, para, styles, paraStyles, tag)
                    }
                }
                is OdfContentBlock.Table -> {
                    if (listOpen) { body.append("</text:list>"); listOpen = false }
                    serializeTable(body, block.table, styles, paraStyles)
                }
                is OdfContentBlock.Image -> {
                    if (listOpen) { body.append("</text:list>"); listOpen = false }
                    serializeImageRef(body, block.image)
                }
                is OdfContentBlock.Chart -> {
                    if (listOpen) { body.append("</text:list>"); listOpen = false }
                    // Charts are embedded OLE objects; not re-serialized (preserved only when not re-saved).
                }
                is OdfContentBlock.PageBreak -> {
                    if (listOpen) { body.append("</text:list>"); listOpen = false }
                }
            }
        }
        if (listOpen) body.append("</text:list>")

        return buildDocument("office:text", styles, paraStyles, body.toString())
    }

    private fun serializeSpreadsheet(doc: OdfDocument.Spreadsheet): String {
        val body = StringBuilder()
        for (sheet in doc.sheets) {
            body.append("""<table:table table:name="${esc(sheet.name)}">""")
            val maxCols = sheet.rows.maxOfOrNull { it.cells.size } ?: 0
            if (maxCols > 0) {
                body.append("""<table:table-column table:number-columns-repeated="$maxCols"/>""")
            }
            for (row in sheet.rows) {
                body.append("<table:table-row>")
                for (cell in row.cells) {
                    if (cell.isCovered) {
                        body.append("<table:covered-table-cell/>")
                    } else {
                        body.append("<table:table-cell")
                        if (cell.spannedColumns > 1) body.append(""" table:number-columns-spanned="${cell.spannedColumns}"""")
                        if (cell.rowSpan > 1) body.append(""" table:number-rows-spanned="${cell.rowSpan}"""")
                        if (cell.text.isNotEmpty()) body.append(""" office:value-type="string"""")
                        body.append(">")
                        if (cell.text.isNotEmpty()) {
                            body.append("<text:p>${esc(cell.text)}</text:p>")
                        }
                        body.append("</table:table-cell>")
                    }
                }
                body.append("</table:table-row>")
            }
            body.append("</table:table>")
        }
        return buildDocument("office:spreadsheet", mutableMapOf(), mutableMapOf(), body.toString())
    }

    private fun serializePresentation(doc: OdfDocument.Presentation): String {
        val styles = mutableMapOf<String, SpanStyleDef>()
        val paraStyles = mutableMapOf<String, ParaStyleDef>()
        val body = StringBuilder()
        for (slide in doc.slides) {
            body.append("""<draw:page draw:name="${esc(slide.name)}">""")
            for (element in slide.elements) {
                when (element) {
                    is OdfSlideElement.Frame -> serializeFrame(body, element.frame, styles, paraStyles)
                    is OdfSlideElement.Shape -> serializeShape(body, element.shape, styles, paraStyles)
                }
            }
            body.append("</draw:page>")
        }
        return buildDocument("office:presentation", styles, paraStyles, body.toString())
    }

    private fun serializeParagraph(
        sb: StringBuilder, para: OdfParagraph,
        styles: MutableMap<String, SpanStyleDef>,
        paraStyles: MutableMap<String, ParaStyleDef>,
        tag: String
    ) {
        sb.append("<$tag")
        val pStyleName = getOrCreateParaStyle(para, paraStyles)
        if (pStyleName != null) sb.append(""" text:style-name="$pStyleName"""")
        if (tag == "text:h") {
            val level = when (para.style) {
                ParagraphStyle.HEADING1 -> 1; ParagraphStyle.HEADING2 -> 2
                ParagraphStyle.HEADING3 -> 3; else -> 4
            }
            sb.append(""" text:outline-level="$level"""")
        }
        sb.append(">")
        for (span in para.spans) {
            if (span.annotation != null) continue
            val needsStyle = span.bold || span.italic || span.underline || span.strikethrough ||
                span.color != null || span.fontSize != null || span.superscript || span.subscript
            if (span.href != null) {
                sb.append("""<text:a xlink:href="${esc(span.href)}" xlink:type="simple">""")
                if (needsStyle) {
                    val styleName = getOrCreateSpanStyle(span, styles)
                    sb.append("""<text:span text:style-name="$styleName">${esc(span.text)}</text:span>""")
                } else {
                    sb.append(esc(span.text))
                }
                sb.append("</text:a>")
            } else if (needsStyle) {
                val styleName = getOrCreateSpanStyle(span, styles)
                sb.append("""<text:span text:style-name="$styleName">${esc(span.text)}</text:span>""")
            } else {
                sb.append(esc(span.text))
            }
        }
        sb.append("</$tag>")
    }

    private fun serializeTable(
        sb: StringBuilder, table: OdfTable,
        styles: MutableMap<String, SpanStyleDef>,
        paraStyles: MutableMap<String, ParaStyleDef>
    ) {
        sb.append("""<table:table table:name="${esc(table.name)}">""")
        for (col in table.columns) sb.append("<table:table-column/>")
        for (row in table.rows) {
            sb.append("<table:table-row>")
            for (cell in row.cells) {
                if (cell.isCovered) {
                    sb.append("<table:covered-table-cell/>")
                } else {
                    sb.append("<table:table-cell")
                    if (cell.colSpan > 1) sb.append(""" table:number-columns-spanned="${cell.colSpan}"""")
                    if (cell.rowSpan > 1) sb.append(""" table:number-rows-spanned="${cell.rowSpan}"""")
                    sb.append(">")
                    for (para in cell.paragraphs) serializeParagraph(sb, para, styles, paraStyles, "text:p")
                    sb.append("</table:table-cell>")
                }
            }
            sb.append("</table:table-row>")
        }
        sb.append("</table:table>")
    }

    private fun serializeImageRef(sb: StringBuilder, image: OdfImage) {
        if (image.path == "inline") return
        sb.append("""<draw:frame""")
        if (image.width > 0) sb.append(""" svg:width="${image.width / 37.8f}cm"""")
        if (image.height > 0) sb.append(""" svg:height="${image.height / 37.8f}cm"""")
        sb.append("""><draw:image xlink:href="${esc(image.path)}" xlink:type="simple" xlink:actuate="onLoad"/>""")
        sb.append("</draw:frame>")
    }

    private fun serializeFrame(
        sb: StringBuilder, frame: OdfFrame,
        styles: MutableMap<String, SpanStyleDef>,
        paraStyles: MutableMap<String, ParaStyleDef>
    ) {
        sb.append("<draw:frame")
        sb.append(""" svg:x="${frame.x / 37.8f}cm" svg:y="${frame.y / 37.8f}cm"""")
        sb.append(""" svg:width="${frame.width / 37.8f}cm" svg:height="${frame.height / 37.8f}cm"""")
        sb.append(">")
        if (frame.image != null && frame.image.path != "inline") {
            sb.append("""<draw:image xlink:href="${esc(frame.image.path)}" xlink:type="simple"/>""")
        }
        if (frame.paragraphs.isNotEmpty()) {
            sb.append("<draw:text-box>")
            for (para in frame.paragraphs) serializeParagraph(sb, para, styles, paraStyles, "text:p")
            sb.append("</draw:text-box>")
        }
        sb.append("</draw:frame>")
    }

    private fun serializeShape(
        sb: StringBuilder, shape: OdfShape,
        styles: MutableMap<String, SpanStyleDef>,
        paraStyles: MutableMap<String, ParaStyleDef>
    ) {
        val tag = when (shape) {
            is OdfShape.Rect -> "draw:rect"
            is OdfShape.Ellipse -> "draw:ellipse"
            is OdfShape.Line -> "draw:line"
            is OdfShape.CustomShape -> "draw:custom-shape"
        }
        sb.append("<$tag")
        sb.append(""" svg:x="${shape.x / 37.8f}cm" svg:y="${shape.y / 37.8f}cm"""")
        sb.append(""" svg:width="${shape.width / 37.8f}cm" svg:height="${shape.height / 37.8f}cm"""")
        sb.append(">")
        for (para in shape.text) serializeParagraph(sb, para, styles, paraStyles, "text:p")
        sb.append("</$tag>")
    }

    // --- Style management ---

    private data class SpanStyleDef(
        val bold: Boolean = false, val italic: Boolean = false,
        val underline: Boolean = false, val strikethrough: Boolean = false,
        val color: Long? = null, val fontSize: Float? = null,
        val superscript: Boolean = false, val subscript: Boolean = false
    )

    private data class ParaStyleDef(val alignment: TextAlign? = null)

    private fun getOrCreateSpanStyle(span: OdfSpan, styles: MutableMap<String, SpanStyleDef>): String {
        val def = SpanStyleDef(span.bold, span.italic, span.underline, span.strikethrough,
            span.color, span.fontSize, span.superscript, span.subscript)
        for ((name, existing) in styles) if (existing == def) return name
        val name = "T${styles.size + 1}"
        styles[name] = def
        return name
    }

    private fun getOrCreateParaStyle(para: OdfParagraph, styles: MutableMap<String, ParaStyleDef>): String? {
        if (para.alignment == null) return null
        val def = ParaStyleDef(para.alignment)
        for ((name, existing) in styles) if (existing == def) return name
        val name = "P${styles.size + 1}"
        styles[name] = def
        return name
    }

    // --- XML construction ---

    private fun buildDocument(
        bodyType: String,
        spanStyles: Map<String, SpanStyleDef>,
        paraStyles: Map<String, ParaStyleDef>,
        bodyContent: String
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("""<office:document-content""")
        sb.append(""" xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"""")
        sb.append(""" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"""")
        sb.append(""" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"""")
        sb.append(""" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"""")
        sb.append(""" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"""")
        sb.append(""" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"""")
        sb.append(""" xmlns:xlink="http://www.w3.org/1999/xlink"""")
        sb.append(""" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"""")
        sb.append(""" office:version="1.3">""")

        sb.append("<office:automatic-styles>")
        for ((name, def) in spanStyles) {
            sb.append("""<style:style style:name="$name" style:family="text"><style:text-properties""")
            if (def.bold) sb.append(""" fo:font-weight="bold"""")
            if (def.italic) sb.append(""" fo:font-style="italic"""")
            if (def.underline) sb.append(""" style:text-underline-style="solid" style:text-underline-width="auto"""")
            if (def.strikethrough) sb.append(""" style:text-line-through-style="solid"""")
            if (def.color != null) sb.append(""" fo:color="${formatColor(def.color)}"""")
            if (def.fontSize != null) sb.append(""" fo:font-size="${def.fontSize}pt"""")
            if (def.superscript) sb.append(""" style:text-position="super 58%"""")
            if (def.subscript) sb.append(""" style:text-position="sub 58%"""")
            sb.append("/></style:style>")
        }
        for ((name, def) in paraStyles) {
            sb.append("""<style:style style:name="$name" style:family="paragraph"><style:paragraph-properties""")
            when (def.alignment) {
                TextAlign.Start, TextAlign.Left -> sb.append(""" fo:text-align="start"""")
                TextAlign.Center -> sb.append(""" fo:text-align="center"""")
                TextAlign.End, TextAlign.Right -> sb.append(""" fo:text-align="end"""")
                TextAlign.Justify -> sb.append(""" fo:text-align="justify"""")
                else -> {}
            }
            sb.append("/></style:style>")
        }
        sb.append("</office:automatic-styles>")

        sb.append("<office:body><$bodyType>")
        sb.append(bodyContent)
        sb.append("</$bodyType></office:body>")
        sb.append("</office:document-content>")
        return sb.toString()
    }

    private fun formatColor(color: Long): String {
        val rgb = (color and 0xFFFFFFL).toInt()
        return String.format("#%06X", rgb)
    }

    private fun esc(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
