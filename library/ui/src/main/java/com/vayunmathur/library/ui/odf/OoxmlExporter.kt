package com.vayunmathur.library.ui.odf

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Best-effort exporter from the ODF document model to Microsoft OOXML packages
 * (.docx / .xlsx / .pptx). Emits text, basic run formatting, headings, tables, cell
 * values, and slide text boxes. Styling beyond that (images, charts, gradients,
 * conditional formats, exact geometry) is not written — this is a lossy convenience
 * export, hence the UI warns before use.
 */
object OoxmlExporter {

    fun export(document: OdfDocument): ByteArray = when (document) {
        is OdfDocument.TextDocument -> buildDocx(document)
        is OdfDocument.Spreadsheet -> buildXlsx(document)
        is OdfDocument.Presentation -> buildPptx(document.slides)
        is OdfDocument.Drawing -> buildPptx(document.pages)
    }

    /** OOXML extension for a document type. */
    fun extensionFor(document: OdfDocument): String = when (document) {
        is OdfDocument.TextDocument -> "docx"
        is OdfDocument.Spreadsheet -> "xlsx"
        else -> "pptx"
    }

    // --- ZIP helper ---

    private fun zip(files: List<Pair<String, String>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, content) in files) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;"); '\'' -> sb.append("&apos;")
            '\t', '\n' -> sb.append(c)
            else -> if (c.code >= 0x20) sb.append(c)
        }
        return sb.toString()
    }

    private const val XMLDECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private fun emu(px: Float): Long = (px * 9525f).toLong()

    // --- DOCX ---

    private fun buildDocx(doc: OdfDocument.TextDocument): ByteArray {
        val body = StringBuilder()
        for (block in doc.content) when (block) {
            is OdfContentBlock.Paragraph -> body.append(docxParagraph(block.paragraph))
            is OdfContentBlock.Table -> body.append(docxTable(block.table))
            is OdfContentBlock.TableOfContents -> { for (e in block.entries) body.append(docxParagraph(e)) }
            is OdfContentBlock.PageBreak -> body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            is OdfContentBlock.Formula -> body.append(docxParagraph(OdfParagraph(listOf(OdfSpan("[formula]", italic = true)))))
            is OdfContentBlock.Image, is OdfContentBlock.Chart -> {}
            is OdfContentBlock.SectionStart, OdfContentBlock.SectionEnd -> {}
        }
        val document = XMLDECL +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>$body<w:sectPr/></w:body></w:document>"

        val contentTypes = XMLDECL +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"
        val rootRels = XMLDECL +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "</Relationships>"
        val docRels = XMLDECL +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>"
        return zip(listOf(
            "[Content_Types].xml" to contentTypes,
            "_rels/.rels" to rootRels,
            "word/document.xml" to document,
            "word/_rels/document.xml.rels" to docRels
        ))
    }

    private fun docxParagraph(p: OdfParagraph): String {
        val sb = StringBuilder("<w:p>")
        val styleId = when (p.style) {
            ParagraphStyle.HEADING1 -> "Heading1"; ParagraphStyle.HEADING2 -> "Heading2"
            ParagraphStyle.HEADING3 -> "Heading3"; ParagraphStyle.HEADING4 -> "Heading4"; else -> null
        }
        val jc = when (p.alignment) {
            androidx.compose.ui.text.style.TextAlign.Center -> "center"
            androidx.compose.ui.text.style.TextAlign.End, androidx.compose.ui.text.style.TextAlign.Right -> "right"
            androidx.compose.ui.text.style.TextAlign.Justify -> "both"
            else -> null
        }
        if (styleId != null || jc != null) {
            sb.append("<w:pPr>")
            if (styleId != null) sb.append("<w:pStyle w:val=\"$styleId\"/>")
            if (jc != null) sb.append("<w:jc w:val=\"$jc\"/>")
            sb.append("</w:pPr>")
        }
        for (span in p.spans) sb.append(docxRun(span))
        sb.append("</w:p>")
        return sb.toString()
    }

    private fun docxRun(span: OdfSpan): String {
        if (span.text.isEmpty()) return ""
        val rPr = StringBuilder()
        if (span.bold) rPr.append("<w:b/>")
        if (span.italic) rPr.append("<w:i/>")
        if (span.underline) rPr.append("<w:u w:val=\"single\"/>")
        if (span.strikethrough) rPr.append("<w:strike/>")
        span.color?.let { rPr.append("<w:color w:val=\"${"%06X".format(it and 0xFFFFFF)}\"/>") }
        span.fontSize?.let { rPr.append("<w:sz w:val=\"${(it * 2).toInt()}\"/>") }
        val rPrXml = if (rPr.isNotEmpty()) "<w:rPr>$rPr</w:rPr>" else ""
        // Split on newlines/tabs into text + break/tab elements.
        val content = StringBuilder()
        val parts = span.text.split("\n")
        parts.forEachIndexed { i, part ->
            if (i > 0) content.append("<w:br/>")
            val tabParts = part.split("\t")
            tabParts.forEachIndexed { j, tp ->
                if (j > 0) content.append("<w:tab/>")
                if (tp.isNotEmpty()) content.append("<w:t xml:space=\"preserve\">${esc(tp)}</w:t>")
            }
        }
        return "<w:r>$rPrXml$content</w:r>"
    }

    private fun docxTable(table: OdfTable): String {
        val cols = table.rows.maxOfOrNull { r -> r.cells.count { !it.isCovered } } ?: 0
        val sb = StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>")
        for (edge in listOf("top", "left", "bottom", "right", "insideH", "insideV"))
            sb.append("<w:$edge w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
        sb.append("</w:tblBorders></w:tblPr><w:tblGrid>")
        repeat(cols.coerceAtLeast(1)) { sb.append("<w:gridCol w:w=\"2340\"/>") }
        sb.append("</w:tblGrid>")
        for (row in table.rows) {
            sb.append("<w:tr>")
            for (cell in row.cells) {
                if (cell.isCovered) continue
                sb.append("<w:tc><w:tcPr/>")
                if (cell.paragraphs.isEmpty()) sb.append("<w:p/>")
                else for (p in cell.paragraphs) sb.append(docxParagraph(p))
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    // --- XLSX ---

    private fun buildXlsx(doc: OdfDocument.Spreadsheet): ByteArray {
        val sheets = doc.sheets.ifEmpty { listOf(OdfSheet("Sheet1", emptyList())) }
        val files = mutableListOf<Pair<String, String>>()

        val ctypes = StringBuilder(XMLDECL)
        ctypes.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        ctypes.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        ctypes.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        ctypes.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        ctypes.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        for (i in sheets.indices) ctypes.append("<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        ctypes.append("</Types>")

        val rootRels = XMLDECL +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

        val wb = StringBuilder(XMLDECL)
        wb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
        sheets.forEachIndexed { i, s -> wb.append("<sheet name=\"${esc(s.name.take(31).ifBlank { "Sheet${i + 1}" })}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>") }
        wb.append("</sheets></workbook>")

        val wbRels = StringBuilder(XMLDECL)
        wbRels.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        sheets.forEachIndexed { i, _ -> wbRels.append("<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${i + 1}.xml\"/>") }
        wbRels.append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        wbRels.append("</Relationships>")

        val styles = XMLDECL +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border/></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"

        files.add("[Content_Types].xml" to ctypes.toString())
        files.add("_rels/.rels" to rootRels)
        files.add("xl/workbook.xml" to wb.toString())
        files.add("xl/_rels/workbook.xml.rels" to wbRels.toString())
        files.add("xl/styles.xml" to styles)
        sheets.forEachIndexed { i, s -> files.add("xl/worksheets/sheet${i + 1}.xml" to xlsxSheet(s)) }
        return zip(files)
    }

    private fun colLetter(index: Int): String {
        var n = index + 1; val sb = StringBuilder()
        while (n > 0) { val rem = (n - 1) % 26; sb.insert(0, ('A' + rem)); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun xlsxSheet(sheet: OdfSheet): String {
        val sb = StringBuilder(XMLDECL)
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
        sheet.rows.forEachIndexed { ri, row ->
            sb.append("<row r=\"${ri + 1}\">")
            row.cells.forEachIndexed { ci, cell ->
                if (cell.isCovered || cell.text.isEmpty() && cell.numberValue == null) return@forEachIndexed
                val ref = "${colLetter(ci)}${ri + 1}"
                val num = cell.numberValue ?: cell.text.toDoubleOrNull()
                if (num != null && cell.valueType != "string") {
                    sb.append("<c r=\"$ref\"><v>${if (num == num.toLong().toDouble()) num.toLong().toString() else num.toString()}</v></c>")
                } else {
                    sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(cell.text)}</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    // --- PPTX ---

    private fun buildPptx(slides: List<OdfSlide>): ByteArray {
        val deck = slides.ifEmpty { listOf(OdfSlide("Slide 1")) }
        val files = mutableListOf<Pair<String, String>>()

        val ctypes = StringBuilder(XMLDECL)
        ctypes.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        ctypes.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        ctypes.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        ctypes.append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>")
        ctypes.append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>")
        ctypes.append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
        ctypes.append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>")
        for (i in deck.indices) ctypes.append("<Override PartName=\"/ppt/slides/slide${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
        ctypes.append("</Types>")

        val rootRels = XMLDECL +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>" +
            "</Relationships>"

        val pres = StringBuilder(XMLDECL)
        pres.append("<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">")
        pres.append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rIdMaster\"/></p:sldMasterIdLst>")
        pres.append("<p:sldIdLst>")
        deck.indices.forEach { pres.append("<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 1}\"/>") }
        pres.append("</p:sldIdLst>")
        pres.append("<p:sldSz cx=\"9144000\" cy=\"6858000\" type=\"screen4x3\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")

        val presRels = StringBuilder(XMLDECL)
        presRels.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        presRels.append("<Relationship Id=\"rIdMaster\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")
        deck.indices.forEach { presRels.append("<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide${it + 1}.xml\"/>") }
        presRels.append("<Relationship Id=\"rIdTheme\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>")
        presRels.append("</Relationships>")

        files.add("[Content_Types].xml" to ctypes.toString())
        files.add("_rels/.rels" to rootRels)
        files.add("ppt/presentation.xml" to pres.toString())
        files.add("ppt/_rels/presentation.xml.rels" to presRels.toString())
        files.add("ppt/theme/theme1.xml" to PPTX_THEME)
        files.add("ppt/slideMasters/slideMaster1.xml" to PPTX_MASTER)
        files.add("ppt/slideMasters/_rels/slideMaster1.xml.rels" to (XMLDECL +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rIdLayout\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "<Relationship Id=\"rIdTheme\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/>" +
            "</Relationships>"))
        files.add("ppt/slideLayouts/slideLayout1.xml" to PPTX_LAYOUT)
        files.add("ppt/slideLayouts/_rels/slideLayout1.xml.rels" to (XMLDECL +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rIdMaster\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>" +
            "</Relationships>"))
        deck.forEachIndexed { i, s ->
            files.add("ppt/slides/slide${i + 1}.xml" to pptxSlide(s))
            files.add("ppt/slides/_rels/slide${i + 1}.xml.rels" to (XMLDECL +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rIdLayout\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                "</Relationships>"))
        }
        return zip(files)
    }

    private fun pptxSlide(slide: OdfSlide): String {
        val sb = StringBuilder(XMLDECL)
        sb.append("<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">")
        sb.append("<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>")
        var shapeId = 2
        var autoY = 300000L
        for (el in slide.elements) {
            val paras: List<OdfParagraph>; val b: FloatArray
            when (el) {
                is OdfSlideElement.Frame -> { paras = el.frame.paragraphs; b = floatArrayOf(el.frame.x, el.frame.y, el.frame.width, el.frame.height) }
                is OdfSlideElement.Shape -> { paras = el.shape.text; b = floatArrayOf(el.shape.x, el.shape.y, el.shape.width, el.shape.height) }
            }
            if (paras.isEmpty() || paras.all { p -> p.spans.all { it.text.isBlank() } }) continue
            val x = if (b[0] > 0f) emu(b[0]) else 457200L
            val y = if (b[1] > 0f) emu(b[1]) else autoY
            val w = if (b[2] > 0f) emu(b[2]) else 8229600L
            val h = if (b[3] > 0f) emu(b[3]) else 1000000L
            autoY = y + h + 100000L
            sb.append(pptxTextShape(shapeId++, x, y, w, h, paras))
        }
        sb.append("</p:spTree></p:cSld><p:clrMapOvr><a:overrideClrMapping bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/></p:clrMapOvr></p:sld>")
        return sb.toString()
    }

    private fun pptxTextShape(id: Int, x: Long, y: Long, w: Long, h: Long, paras: List<OdfParagraph>): String {
        val sb = StringBuilder("<p:sp>")
        sb.append("<p:nvSpPr><p:cNvPr id=\"$id\" name=\"TextBox $id\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>")
        sb.append("<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>")
        sb.append("<p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/>")
        for (p in paras) {
            sb.append("<a:p>")
            for (span in p.spans) {
                if (span.text.isEmpty()) continue
                val attrs = StringBuilder(" lang=\"en-US\"")
                if (span.bold) attrs.append(" b=\"1\"")
                if (span.italic) attrs.append(" i=\"1\"")
                if (span.underline) attrs.append(" u=\"sng\"")
                span.fontSize?.let { attrs.append(" sz=\"${(it * 100).toInt()}\"") }
                sb.append("<a:r><a:rPr$attrs/><a:t>${esc(span.text)}</a:t></a:r>")
            }
            sb.append("</a:p>")
        }
        sb.append("</p:txBody></p:sp>")
        return sb.toString()
    }

    private val PPTX_THEME = XMLDECL +
        "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Office\">" +
        "<a:themeElements>" +
        "<a:clrScheme name=\"Office\">" +
        "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
        "<a:dk2><a:srgbClr val=\"44546A\"/></a:dk2><a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2>" +
        "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1><a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2>" +
        "<a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>" +
        "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>" +
        "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink>" +
        "</a:clrScheme>" +
        "<a:fontScheme name=\"Office\"><a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
        "<a:fmtScheme name=\"Office\">" +
        "<a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst>" +
        "<a:lnStyleLst><a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"19050\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>" +
        "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
        "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst>" +
        "</a:fmtScheme>" +
        "</a:themeElements></a:theme>"

    private val PPTX_MASTER = XMLDECL +
        "<p:sldMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
        "<p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg>" +
        "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>" +
        "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
        "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rIdLayout\"/></p:sldLayoutIdLst>" +
        "</p:sldMaster>"

    private val PPTX_LAYOUT = XMLDECL +
        "<p:sldLayout xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" type=\"blank\" preserve=\"1\">" +
        "<p:cSld name=\"Blank\"><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>" +
        "<p:clrMapOvr><a:overrideClrMapping bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/></p:clrMapOvr>" +
        "</p:sldLayout>"
}
