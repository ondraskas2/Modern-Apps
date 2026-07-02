package com.vayunmathur.office.odf

import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.odf.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.zip.ZipInputStream

/**
 * Best-effort importer for Microsoft OOXML files (.docx / .xlsx / .pptx).
 *
 * Extracts text, basic run formatting (bold/italic/underline/strike/color/size), paragraph
 * headings and alignment (docx); shared-string/inline/numeric cell values across sheets (xlsx);
 * and per-shape slide text (pptx). Advanced features (images, styles, charts, drawing geometry,
 * formulas) are intentionally dropped — this is a lossy convenience import, not a full converter.
 */
object OoxmlImporter {

    /** Imports OOXML [bytes]; returns null if the package isn't a recognized docx/xlsx/pptx. */
    fun import(bytes: ByteArray, fileName: String): OdfDocument? {
        val entries = readZip(bytes)
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext == "docx" || entries.containsKey("word/document.xml") -> importDocx(entries, fileName)
            ext == "xlsx" || entries.containsKey("xl/workbook.xml") -> importXlsx(entries, fileName)
            ext == "pptx" || entries.keys.any { it.startsWith("ppt/slides/slide") } -> importPptx(entries, fileName)
            else -> null
        }
    }

    /** True if [bytes] is a ZIP whose entries look like an OOXML package. */
    fun looksLikeOoxml(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return false
        val names = readZip(bytes).keys
        return names.any { it.startsWith("word/") || it.startsWith("xl/") || it.startsWith("ppt/") }
    }

    // --- ZIP + XML helpers ---

    private fun readZip(bytes: ByteArray): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val n = e.name
                    if (!e.isDirectory && (n.endsWith(".xml") || n.endsWith(".rels"))) {
                        map[n] = zip.readBytes().toString(Charsets.UTF_8)
                    }
                    e = zip.nextEntry
                }
            }
        } catch (_: Exception) {}
        return map
    }

    private fun newParser(xml: String): XmlPullParser {
        val f = XmlPullParserFactory.newInstance(); f.isNamespaceAware = true
        val p = f.newPullParser(); p.setInput(xml.reader()); return p
    }

    private fun attr(parser: XmlPullParser, localName: String): String? {
        for (i in 0 until parser.attributeCount) if (parser.getAttributeName(i) == localName) return parser.getAttributeValue(i)
        return null
    }

    /** Reads concatenated text content up to the end tag [endTag] at the current element's depth. */
    private fun readElementText(parser: XmlPullParser, endTag: String): String {
        val sb = StringBuilder()
        val depth = parser.depth
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.TEXT) sb.append(parser.text)
            if (e == XmlPullParser.END_DOCUMENT) break
            e = parser.next()
        }
        return sb.toString()
    }

    private fun boolAttr(v: String?): Boolean = v == null || v == "1" || v == "true" || v == "on"

    private fun hexColor(v: String?): Long? {
        if (v == null || v.equals("auto", true)) return null
        val hex = v.removePrefix("#").takeIf { it.length == 6 } ?: return null
        return try { 0xFF000000L or hex.toLong(16) } catch (_: Exception) { null }
    }

    private fun colIndex(cellRef: String): Int {
        var n = 0
        for (c in cellRef) { if (c.isLetter()) n = n * 26 + (c.uppercaseChar() - 'A' + 1) else break }
        return (n - 1).coerceAtLeast(0)
    }

    // --- DOCX ---

    private fun importDocx(entries: Map<String, String>, fileName: String): OdfDocument.TextDocument {
        val xml = entries["word/document.xml"] ?: return OdfDocument.TextDocument(fileName, emptyList())
        val parser = newParser(xml)
        val content = mutableListOf<OdfContentBlock>()
        var event = parser.eventType
        var inBody = false
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) when (parser.name) {
                "body" -> inBody = true
                "p" -> if (inBody) content.add(OdfContentBlock.Paragraph(parseDocxParagraph(parser)))
                "tbl" -> if (inBody) content.add(OdfContentBlock.Table(parseDocxTable(parser)))
            } else if (event == XmlPullParser.END_TAG && parser.name == "body") inBody = false
            event = parser.next()
        }
        return OdfDocument.TextDocument(fileName, content)
    }

    private fun parseDocxParagraph(parser: XmlPullParser): OdfParagraph {
        val depth = parser.depth
        val spans = mutableListOf<OdfSpan>()
        var styleId: String? = null
        var align: TextAlign? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "p")) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "pStyle" -> styleId = attr(parser, "val")
                "jc" -> align = when (attr(parser, "val")) {
                    "center" -> TextAlign.Center; "right", "end" -> TextAlign.End
                    "both", "distribute" -> TextAlign.Justify; "left", "start" -> TextAlign.Start; else -> null
                }
                "r" -> parseDocxRun(parser)?.let { spans.add(it) }
            }
            if (e == XmlPullParser.END_DOCUMENT) break
            e = parser.next()
        }
        val style = when {
            styleId == null -> ParagraphStyle.BODY
            styleId.equals("Title", true) || styleId.equals("Heading1", true) -> ParagraphStyle.HEADING1
            styleId.equals("Subtitle", true) || styleId.equals("Heading2", true) -> ParagraphStyle.HEADING2
            styleId.equals("Heading3", true) -> ParagraphStyle.HEADING3
            styleId.startsWith("Heading", true) -> ParagraphStyle.HEADING4
            else -> ParagraphStyle.BODY
        }
        return OdfParagraph(spans = if (spans.isEmpty()) listOf(OdfSpan("")) else spans, style = style, alignment = align)
    }

    private fun parseDocxRun(parser: XmlPullParser): OdfSpan? {
        val depth = parser.depth
        var bold = false; var italic = false; var underline = false; var strike = false
        var color: Long? = null; var size: Float? = null
        val sb = StringBuilder()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "r")) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "b" -> bold = boolAttr(attr(parser, "val"))
                "i" -> italic = boolAttr(attr(parser, "val"))
                "u" -> underline = attr(parser, "val")?.let { it != "none" } ?: true
                "strike" -> strike = boolAttr(attr(parser, "val"))
                "color" -> color = hexColor(attr(parser, "val"))
                "sz" -> size = attr(parser, "val")?.toFloatOrNull()?.div(2f)  // half-points -> pt
                "t" -> sb.append(readElementText(parser, "t"))
                "tab" -> sb.append("\t")
                "br", "cr" -> sb.append("\n")
            }
            if (e == XmlPullParser.END_DOCUMENT) break
            e = parser.next()
        }
        if (sb.isEmpty()) return null
        return OdfSpan(text = sb.toString(), bold = bold, italic = italic, fontSize = size,
            underline = underline, strikethrough = strike, color = color)
    }

    private fun parseDocxTable(parser: XmlPullParser): OdfTable {
        val depth = parser.depth
        val rows = mutableListOf<OdfTableRow>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "tbl")) {
            if (e == XmlPullParser.START_TAG && parser.name == "tr") rows.add(parseDocxRow(parser))
            if (e == XmlPullParser.END_DOCUMENT) break
            e = parser.next()
        }
        return OdfTable(rows = rows)
    }

    private fun parseDocxRow(parser: XmlPullParser): OdfTableRow {
        val depth = parser.depth
        val cells = mutableListOf<OdfTableCell>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "tr")) {
            if (e == XmlPullParser.START_TAG && parser.name == "tc") cells.add(parseDocxCell(parser))
            if (e == XmlPullParser.END_DOCUMENT) break
            e = parser.next()
        }
        return OdfTableRow(cells)
    }

    private fun parseDocxCell(parser: XmlPullParser): OdfTableCell {
        val depth = parser.depth
        val paras = mutableListOf<OdfParagraph>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "tc")) {
            if (e == XmlPullParser.START_TAG && parser.name == "p") paras.add(parseDocxParagraph(parser))
            if (e == XmlPullParser.END_DOCUMENT) break
            e = parser.next()
        }
        return OdfTableCell(paragraphs = if (paras.isEmpty()) listOf(OdfParagraph(listOf(OdfSpan("")))) else paras)
    }

    // --- XLSX ---

    private fun importXlsx(entries: Map<String, String>, fileName: String): OdfDocument.Spreadsheet {
        val shared = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val rels = entries["xl/_rels/workbook.xml.rels"]?.let { parseRels(it) } ?: emptyMap()
        val ordered = entries["xl/workbook.xml"]?.let { parseWorkbookSheets(it) } ?: emptyList()
        val sheets = mutableListOf<OdfSheet>()
        if (ordered.isNotEmpty()) {
            for ((name, rId) in ordered) {
                val target = rels[rId]?.removePrefix("/")?.let { if (it.startsWith("xl/")) it else "xl/$it" }
                val sheetXml = target?.let { entries[it] } ?: continue
                sheets.add(parseWorksheet(sheetXml, name, shared))
            }
        }
        if (sheets.isEmpty()) {
            // Fallback: sheetN.xml in filename order.
            entries.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                .sortedBy { it.substringAfterLast("sheet").substringBefore(".xml").toIntOrNull() ?: 0 }
                .forEachIndexed { i, path -> sheets.add(parseWorksheet(entries[path]!!, "Sheet ${i + 1}", shared)) }
        }
        if (sheets.isEmpty()) sheets.add(OdfSheet("Sheet 1", emptyList()))
        return OdfDocument.Spreadsheet(fileName, sheets)
    }

    private fun parseSharedStrings(xml: String): List<String> {
        val list = mutableListOf<String>()
        val parser = newParser(xml)
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "si") {
                val depth = parser.depth
                val sb = StringBuilder()
                var ev = parser.next()
                while (!(ev == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "si")) {
                    if (ev == XmlPullParser.START_TAG && parser.name == "t") sb.append(readElementText(parser, "t"))
                    if (ev == XmlPullParser.END_DOCUMENT) break
                    ev = parser.next()
                }
                list.add(sb.toString())
            }
            e = parser.next()
        }
        return list
    }

    private fun parseWorkbookSheets(xml: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val parser = newParser(xml)
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "sheet") {
                val name = attr(parser, "name") ?: "Sheet ${out.size + 1}"
                val rId = attr(parser, "id") ?: ""
                out.add(name to rId)
            }
            e = parser.next()
        }
        return out
    }

    private fun parseRels(xml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val parser = newParser(xml)
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = attr(parser, "Id"); val target = attr(parser, "Target")
                if (id != null && target != null) map[id] = target
            }
            e = parser.next()
        }
        return map
    }

    private fun parseWorksheet(xml: String, name: String, shared: List<String>): OdfSheet {
        val parser = newParser(xml)
        val rows = mutableListOf<OdfRow>()
        var event = parser.eventType
        var curCells: MutableList<Pair<Int, OdfCell>>? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> curCells = mutableListOf()
                    "c" -> if (curCells != null) {
                        val ref = attr(parser, "r") ?: ""
                        val ci = if (ref.isNotEmpty()) colIndex(ref) else curCells.size
                        curCells.add(ci to parseXlsxCell(parser, shared))
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "row" && curCells != null) {
                    val maxCol = curCells.maxOfOrNull { it.first } ?: -1
                    val arr = MutableList(maxCol + 1) { OdfCell(text = "") }
                    for ((ci, cell) in curCells) if (ci in arr.indices) arr[ci] = cell
                    rows.add(OdfRow(arr))
                    curCells = null
                }
            }
            event = parser.next()
        }
        return OdfSheet(name, rows)
    }

    private fun parseXlsxCell(parser: XmlPullParser, shared: List<String>): OdfCell {
        val type = attr(parser, "t")
        val depth = parser.depth
        var value: String? = null
        var inlineText: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "c")) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "v" -> value = readElementText(parser, "v")
                "t" -> inlineText = (inlineText ?: "") + readElementText(parser, "t") // inlineStr
            }
            if (e == XmlPullParser.END_DOCUMENT) break
            e = parser.next()
        }
        return when (type) {
            "s" -> OdfCell(text = shared.getOrNull(value?.toIntOrNull() ?: -1) ?: "")
            "inlineStr" -> OdfCell(text = inlineText ?: "")
            "str" -> OdfCell(text = value ?: "")
            "b" -> OdfCell(text = if (value == "1") "TRUE" else "FALSE")
            else -> {
                val num = value?.toDoubleOrNull()
                if (num != null) OdfCell(text = value!!, numberValue = num, valueType = "float")
                else OdfCell(text = value ?: "")
            }
        }
    }

    // --- PPTX ---

    private fun importPptx(entries: Map<String, String>, fileName: String): OdfDocument.Presentation {
        val slidePaths = entries.keys.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.substringAfterLast("slide").substringBefore(".xml").toIntOrNull() ?: 0 }
        val slides = slidePaths.mapIndexed { i, path -> parseSlide(entries[path]!!, "Slide ${i + 1}") }
        return OdfDocument.Presentation(fileName, if (slides.isEmpty()) listOf(OdfSlide("Slide 1")) else slides)
    }

    private fun parseSlide(xml: String, name: String): OdfSlide {
        val parser = newParser(xml)
        val elements = mutableListOf<OdfSlideElement>()
        var event = parser.eventType
        var autoY = 36f
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "sp") {
                val shape = parseSlideShape(parser, autoY)
                if (shape != null) { elements.add(OdfSlideElement.Frame(shape)); autoY = shape.y + shape.height + 12f }
            }
            event = parser.next()
        }
        return OdfSlide(name = name, elements = elements)
    }

    private fun parseSlideShape(parser: XmlPullParser, autoY: Float): OdfFrame? {
        val depth = parser.depth
        var x = 36f; var y = autoY; var w = 640f; var h = 80f; var hasXfrm = false
        val paras = mutableListOf<OdfParagraph>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "sp")) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "off" -> { attr(parser, "x")?.toLongOrNull()?.let { x = it / 9525f; hasXfrm = true }; attr(parser, "y")?.toLongOrNull()?.let { y = it / 9525f } }
                "ext" -> { attr(parser, "cx")?.toLongOrNull()?.let { w = it / 9525f }; attr(parser, "cy")?.toLongOrNull()?.let { h = it / 9525f } }
                "p" -> parseDrawingParagraph(parser)?.let { paras.add(it) }
            }
            if (e == XmlPullParser.END_DOCUMENT) break
            e = parser.next()
        }
        if (paras.isEmpty()) return null
        if (!hasXfrm) { x = 36f; w = 640f }
        return OdfFrame(x = x, y = y, width = w, height = h.coerceAtLeast(40f), paragraphs = paras)
    }

    private fun parseDrawingParagraph(parser: XmlPullParser): OdfParagraph? {
        val depth = parser.depth
        val spans = mutableListOf<OdfSpan>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "p")) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "r" -> parseDrawingRun(parser)?.let { spans.add(it) }
                "br" -> spans.add(OdfSpan("\n"))
            }
            if (e == XmlPullParser.END_DOCUMENT) break
            e = parser.next()
        }
        if (spans.isEmpty()) return null
        return OdfParagraph(spans)
    }

    private fun parseDrawingRun(parser: XmlPullParser): OdfSpan? {
        val depth = parser.depth
        var bold = false; var italic = false
        val sb = StringBuilder()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "r")) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "rPr" -> { if (attr(parser, "b") == "1") bold = true; if (attr(parser, "i") == "1") italic = true }
                "t" -> sb.append(readElementText(parser, "t"))
            }
            if (e == XmlPullParser.END_DOCUMENT) break
            e = parser.next()
        }
        if (sb.isEmpty()) return null
        return OdfSpan(text = sb.toString(), bold = bold, italic = italic)
    }
}
