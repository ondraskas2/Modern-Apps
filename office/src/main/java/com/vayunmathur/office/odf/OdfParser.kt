package com.vayunmathur.office.odf

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.zip.ZipInputStream

object OdfParser {

    fun parse(context: Context, uri: Uri, fileName: String): OdfDocument {
        val entries = extractAllEntries(context, uri)
        val contentXml = entries.textEntries["content.xml"]
            ?: throw IllegalArgumentException("Not a valid ODF file: missing content.xml")
        val stylesXml = entries.textEntries["styles.xml"]
        val metaXml = entries.textEntries["meta.xml"]

        val styleMap = mutableMapOf<String, StyleInfo>()
        stylesXml?.let { styleMap.putAll(parseStyles(it)) }
        styleMap.putAll(parseStyles(contentXml))

        val listStyleMap = mutableMapOf<String, ListType>()
        stylesXml?.let { listStyleMap.putAll(parseListStyles(it)) }
        listStyleMap.putAll(parseListStyles(contentXml))

        var metadata = metaXml?.let { parseMetadata(it) } ?: OdfMetadata()

        // File size
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                metadata = metadata.copy(fileSize = fd.statSize)
            }
        } catch (_: Exception) {}

        val images = entries.binaryEntries
        val objectContents = entries.textEntries.filterKeys { it.startsWith("Object") }

        // Parse headers/footers from styles.xml
        val headerFooter = stylesXml?.let { parseHeaderFooter(it, styleMap) }

        val type = detectType(contentXml)
        return when (type) {
            DocType.TEXT -> parseTextDocument(contentXml, styleMap, listStyleMap, fileName, metadata, images, headerFooter, objectContents)
            DocType.SPREADSHEET -> parseSpreadsheet(contentXml, styleMap, fileName, metadata, images)
            DocType.PRESENTATION -> parsePresentation(contentXml, styleMap, fileName, metadata, images)
            DocType.DRAWING -> parseDrawing(contentXml, styleMap, fileName, metadata, images)
        }
    }

    // --- ZIP extraction ---

    private data class ZipEntries(
        val textEntries: Map<String, String>,
        val binaryEntries: Map<String, ByteArray>
    )

    private fun extractAllEntries(context: Context, uri: Uri): ZipEntries {
        val textEntries = mutableMapOf<String, String>()
        val binaryEntries = mutableMapOf<String, ByteArray>()
        val textFiles = setOf("content.xml", "styles.xml", "meta.xml")

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name in textFiles -> textEntries[name] = zip.bufferedReader().readText()
                        name.startsWith("Object") && name.endsWith("content.xml") -> textEntries[name] = zip.bufferedReader().readText()
                        name.startsWith("Pictures/") && !entry.isDirectory -> binaryEntries[name] = zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return ZipEntries(textEntries, binaryEntries)
    }

    // --- Document type detection ---

    private enum class DocType { TEXT, SPREADSHEET, PRESENTATION, DRAWING }

    private fun detectType(contentXml: String): DocType = when {
        contentXml.contains("<office:text") -> DocType.TEXT
        contentXml.contains("<office:spreadsheet") -> DocType.SPREADSHEET
        contentXml.contains("<office:presentation") -> DocType.PRESENTATION
        contentXml.contains("<office:drawing") -> DocType.DRAWING
        else -> DocType.TEXT
    }

    // --- XML helpers ---

    private fun newParser(xml: String): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())
        return parser
    }

    private fun getAttr(parser: XmlPullParser, localName: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == localName) return parser.getAttributeValue(i)
        }
        return null
    }

    private fun skipElement(parser: XmlPullParser) {
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            eventType = parser.next()
        }
    }

    private fun parseDimension(value: String?): Float {
        if (value == null) return 0f
        val numeric = value.replace(Regex("[^0-9.\\-]"), "")
        val base = numeric.toFloatOrNull() ?: 0f
        return when {
            value.endsWith("cm") -> base * 37.8f
            value.endsWith("mm") -> base * 3.78f
            value.endsWith("in") -> base * 96f
            value.endsWith("pt") -> base * 1.33f
            else -> base
        }
    }

    private fun parseColor(hex: String): Long? {
        if (!hex.startsWith("#")) return null
        return try {
            val colorStr = hex.removePrefix("#")
            0xFF000000L or colorStr.toLong(16)
        } catch (_: Exception) { null }
    }

    // --- Style parsing ---

    private data class StyleInfo(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val fontSize: Float? = null,
        val fontFamily: String? = null,
        val parentStyle: String? = null,
        val underline: Boolean = false,
        val strikethrough: Boolean = false,
        val color: Long? = null,
        val backgroundColor: Long? = null,
        val superscript: Boolean = false,
        val subscript: Boolean = false,
        val textAlign: TextAlign? = null,
        val marginLeft: Float = 0f,
        val marginTop: Float = 0f,
        val marginBottom: Float = 0f,
        val textIndent: Float = 0f,
        val paragraphBackgroundColor: Long? = null,
        val breakBefore: String? = null,
        val breakAfter: String? = null,
        val drawFillColor: Long? = null,
        val drawStrokeColor: Long? = null,
        val drawStrokeWidth: Float? = null,
        val cellBackgroundColor: Long? = null,
        val cellBorderColor: Long? = null,
        val writingMode: String? = null,
        val columnWidth: Float? = null
    )

    private fun parseStyles(xml: String): Map<String, StyleInfo> {
        val styles = mutableMapOf<String, StyleInfo>()
        val parser = newParser(xml)
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "style" && parser.namespace?.contains("style") == true) {
                val styleName = getAttr(parser, "name")
                val parentStyle = getAttr(parser, "parent-style-name")
                if (styleName != null) {
                    styles[styleName] = parseStyleProperties(parser, parentStyle)
                }
            }
            eventType = parser.next()
        }
        return styles
    }

    private fun parseStyleProperties(parser: XmlPullParser, parentStyle: String?): StyleInfo {
        var bold = false; var italic = false; var fontSize: Float? = null; var fontFamily: String? = null
        var underline = false; var strikethrough = false
        var color: Long? = null; var bgColor: Long? = null
        var superscript = false; var subscript = false
        var textAlign: TextAlign? = null
        var marginLeft = 0f; var marginTop = 0f; var marginBottom = 0f; var textIndent = 0f
        var paraBgColor: Long? = null
        var breakBefore: String? = null; var breakAfter: String? = null
        var drawFillColor: Long? = null; var drawStrokeColor: Long? = null; var drawStrokeWidth: Float? = null
        var cellBgColor: Long? = null; var cellBorderColor: Long? = null
        var writingMode: String? = null
        var columnWidth: Float? = null

        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "text-properties" -> {
                        if (getAttr(parser, "font-weight") == "bold") bold = true
                        if (getAttr(parser, "font-style") == "italic") italic = true
                        getAttr(parser, "font-size")?.let { s -> fontSize = s.replace("pt", "").replace("px", "").toFloatOrNull() }
                        getAttr(parser, "font-name")?.let { fontFamily = it }
                        if (fontFamily == null) getAttr(parser, "font-family")?.let { fontFamily = it }
                        getAttr(parser, "text-underline-style")?.let { if (it != "none") underline = true }
                        getAttr(parser, "text-line-through-style")?.let { if (it != "none") strikethrough = true }
                        getAttr(parser, "color")?.let { color = parseColor(it) }
                        getAttr(parser, "background-color")?.let { if (it != "transparent") bgColor = parseColor(it) }
                        getAttr(parser, "text-position")?.let { tp ->
                            when {
                                tp.startsWith("super") || (tp.contains("%") && !tp.startsWith("-")) -> superscript = true
                                tp.startsWith("sub") || tp.startsWith("-") -> subscript = true
                            }
                        }
                    }
                    "paragraph-properties" -> {
                        textAlign = when (getAttr(parser, "text-align")) {
                            "start", "left" -> TextAlign.Start
                            "center" -> TextAlign.Center
                            "end", "right" -> TextAlign.End
                            "justify" -> TextAlign.Justify
                            else -> null
                        }
                        getAttr(parser, "margin-left")?.let { marginLeft = parseDimension(it) }
                        getAttr(parser, "margin-top")?.let { marginTop = parseDimension(it) }
                        getAttr(parser, "margin-bottom")?.let { marginBottom = parseDimension(it) }
                        getAttr(parser, "text-indent")?.let { textIndent = parseDimension(it) }
                        getAttr(parser, "background-color")?.let { if (it != "transparent") paraBgColor = parseColor(it) }
                        breakBefore = getAttr(parser, "break-before")
                        breakAfter = getAttr(parser, "break-after")
                        getAttr(parser, "writing-mode")?.let { writingMode = it }
                    }
                    "drawing-page-properties" -> {
                        if (getAttr(parser, "fill") == "solid") {
                            getAttr(parser, "fill-color")?.let { drawFillColor = parseColor(it) }
                        }
                    }
                    "graphic-properties" -> {
                        if (getAttr(parser, "fill") == "solid" || getAttr(parser, "fill") == null) {
                            getAttr(parser, "fill-color")?.let { drawFillColor = parseColor(it) }
                        }
                        getAttr(parser, "stroke-color")?.let { drawStrokeColor = parseColor(it) }
                        getAttr(parser, "stroke-width")?.let { drawStrokeWidth = parseDimension(it) }
                    }
                    "table-cell-properties" -> {
                        getAttr(parser, "background-color")?.let { if (it != "transparent") cellBgColor = parseColor(it) }
                        getAttr(parser, "border")?.let { border ->
                            border.split(" ").lastOrNull { it.startsWith("#") }?.let { cellBorderColor = parseColor(it) }
                        }
                    }
                    "table-column-properties" -> {
                        getAttr(parser, "column-width")?.let { columnWidth = parseDimension(it) }
                    }
                }
            }
            eventType = parser.next()
        }
        return StyleInfo(
            bold, italic, fontSize, fontFamily, parentStyle,
            underline, strikethrough, color, bgColor, superscript, subscript,
            textAlign, marginLeft, marginTop, marginBottom, textIndent, paraBgColor,
            breakBefore, breakAfter, drawFillColor, drawStrokeColor, drawStrokeWidth,
            cellBgColor, cellBorderColor, writingMode, columnWidth
        )
    }

    private fun resolveStyle(name: String?, styles: Map<String, StyleInfo>): StyleInfo {
        if (name == null) return StyleInfo()
        val info = styles[name] ?: return StyleInfo()
        if (info.parentStyle != null) {
            val parent = resolveStyle(info.parentStyle, styles)
            return StyleInfo(
                bold = info.bold || parent.bold,
                italic = info.italic || parent.italic,
                fontSize = info.fontSize ?: parent.fontSize,
                fontFamily = info.fontFamily ?: parent.fontFamily,
                parentStyle = null,
                underline = info.underline || parent.underline,
                strikethrough = info.strikethrough || parent.strikethrough,
                color = info.color ?: parent.color,
                backgroundColor = info.backgroundColor ?: parent.backgroundColor,
                superscript = info.superscript || parent.superscript,
                subscript = info.subscript || parent.subscript,
                textAlign = info.textAlign ?: parent.textAlign,
                marginLeft = if (info.marginLeft != 0f) info.marginLeft else parent.marginLeft,
                marginTop = if (info.marginTop != 0f) info.marginTop else parent.marginTop,
                marginBottom = if (info.marginBottom != 0f) info.marginBottom else parent.marginBottom,
                textIndent = if (info.textIndent != 0f) info.textIndent else parent.textIndent,
                paragraphBackgroundColor = info.paragraphBackgroundColor ?: parent.paragraphBackgroundColor,
                breakBefore = info.breakBefore ?: parent.breakBefore,
                breakAfter = info.breakAfter ?: parent.breakAfter,
                drawFillColor = info.drawFillColor ?: parent.drawFillColor,
                drawStrokeColor = info.drawStrokeColor ?: parent.drawStrokeColor,
                drawStrokeWidth = info.drawStrokeWidth ?: parent.drawStrokeWidth,
                cellBackgroundColor = info.cellBackgroundColor ?: parent.cellBackgroundColor,
                cellBorderColor = info.cellBorderColor ?: parent.cellBorderColor,
                writingMode = info.writingMode ?: parent.writingMode,
                columnWidth = info.columnWidth ?: parent.columnWidth
            )
        }
        return info
    }

    // --- List style parsing ---

    private fun parseListStyles(xml: String): Map<String, ListType> {
        val map = mutableMapOf<String, ListType>()
        val parser = newParser(xml)
        var eventType = parser.eventType
        var currentName: String? = null
        var foundNumbered = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "list-style" -> { currentName = getAttr(parser, "name"); foundNumbered = false }
                    "list-level-style-number" -> foundNumbered = true
                }
                XmlPullParser.END_TAG -> if (parser.name == "list-style" && currentName != null) {
                    map[currentName] = if (foundNumbered) ListType.NUMBERED else ListType.BULLET
                    currentName = null
                }
            }
            eventType = parser.next()
        }
        return map
    }

    // --- Metadata parsing ---

    private fun parseMetadata(xml: String): OdfMetadata {
        val parser = newParser(xml)
        var eventType = parser.eventType
        var title: String? = null; var creator: String? = null; var initialCreator: String? = null
        var creationDate: String? = null; var modifiedDate: String? = null
        var description: String? = null; var subject: String? = null
        val keywords = mutableListOf<String>()
        var pageCount: Int? = null; var wordCount: Int? = null
        var currentTag = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (parser.name == "document-statistic") {
                        pageCount = getAttr(parser, "page-count")?.toIntOrNull()
                        wordCount = getAttr(parser, "word-count")?.toIntOrNull()
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text.trim()
                    if (text.isNotEmpty()) when (currentTag) {
                        "title" -> title = text
                        "creator" -> creator = text
                        "initial-creator" -> initialCreator = text
                        "creation-date" -> creationDate = text
                        "date" -> modifiedDate = text
                        "description" -> description = text
                        "subject" -> subject = text
                        "keyword" -> keywords.add(text)
                    }
                }
                XmlPullParser.END_TAG -> currentTag = ""
            }
            eventType = parser.next()
        }
        return OdfMetadata(title, creator ?: initialCreator, initialCreator, creationDate, modifiedDate, description, subject, keywords, pageCount, wordCount)
    }

    // --- Header/Footer parsing from styles.xml ---

    private data class HeaderFooterResult(
        val headerParagraphs: List<OdfParagraph>,
        val footerParagraphs: List<OdfParagraph>
    )

    private fun parseHeaderFooter(stylesXml: String, styles: Map<String, StyleInfo>): HeaderFooterResult {
        val headerParas = mutableListOf<OdfParagraph>()
        val footerParas = mutableListOf<OdfParagraph>()
        val parser = newParser(stylesXml)
        var eventType = parser.eventType
        var inHeader = false; var inFooter = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "header" -> if (parser.namespace?.contains("style") == true) inHeader = true
                    "footer" -> if (parser.namespace?.contains("style") == true) inFooter = true
                    "p" -> if (inHeader || inFooter) {
                        val spans = parseInlineContent(parser, "p", styles)
                        if (spans.isNotEmpty()) {
                            val para = OdfParagraph(spans)
                            if (inHeader) headerParas.add(para)
                            else footerParas.add(para)
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "header" -> if (parser.namespace?.contains("style") == true) inHeader = false
                    "footer" -> if (parser.namespace?.contains("style") == true) inFooter = false
                }
            }
            eventType = parser.next()
        }
        return HeaderFooterResult(headerParas, footerParas)
    }

    // --- Inline content & span creation ---

    private fun makeSpan(text: String, styleName: String?, styles: Map<String, StyleInfo>, href: String? = null): OdfSpan {
        val resolved = resolveStyle(styleName, styles)
        return OdfSpan(
            text = text,
            bold = resolved.bold,
            italic = resolved.italic,
            fontSize = resolved.fontSize,
            fontFamily = resolved.fontFamily,
            underline = resolved.underline || href != null,
            strikethrough = resolved.strikethrough,
            color = if (href != null && resolved.color == null) LINK_COLOR else resolved.color,
            backgroundColor = resolved.backgroundColor,
            superscript = resolved.superscript,
            subscript = resolved.subscript,
            href = href
        )
    }

    private fun parseInlineContent(
        parser: XmlPullParser, endTag: String,
        styles: Map<String, StyleInfo>,
        images: Map<String, ByteArray> = emptyMap(),
        footnotes: MutableList<OdfFootnote>? = null,
        imagesOut: MutableList<OdfImage>? = null,
        objectContents: Map<String, String> = emptyMap(),
        chartsOut: MutableList<OdfChart>? = null
    ): List<OdfSpan> {
        val spans = mutableListOf<OdfSpan>()
        val depth = parser.depth
        var eventType = parser.next()
        val textBuffer = StringBuilder()
        var currentStyleName: String? = null
        var currentHref: String? = null

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "span" -> {
                        if (textBuffer.isNotEmpty()) {
                            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                            textBuffer.clear()
                        }
                        currentStyleName = getAttr(parser, "style-name")
                    }
                    "a" -> {
                        if (textBuffer.isNotEmpty()) {
                            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                            textBuffer.clear()
                        }
                        currentHref = getAttr(parser, "href")
                    }
                    "tab" -> textBuffer.append("\t")
                    "s" -> textBuffer.append(" ".repeat((getAttr(parser, "c")?.toIntOrNull() ?: 1)))
                    "line-break" -> textBuffer.append("\n")
                    "image" -> {
                        val href = getAttr(parser, "href")
                        if (href != null && images.containsKey(href)) imagesOut?.add(OdfImage(path = href, imageData = images[href]!!))
                    }
                    "object" -> {
                        val href = getAttr(parser, "href")?.removePrefix("./")
                        val xml = href?.let { objectContents["$it/content.xml"] }
                        if (xml != null) parseChart(xml)?.let { chartsOut?.add(it) }
                    }
                    "note" -> {
                        if (textBuffer.isNotEmpty()) {
                            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                            textBuffer.clear()
                        }
                        val fn = parseFootnote(parser, styles)
                        if (fn != null) {
                            footnotes?.add(fn)
                            spans.add(OdfSpan(text = fn.citation, superscript = true, color = LINK_COLOR))
                        }
                    }
                    "annotation" -> {
                        if (textBuffer.isNotEmpty()) {
                            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                            textBuffer.clear()
                        }
                        val annotation = parseAnnotation(parser, styles)
                        if (annotation != null) {
                            spans.add(OdfSpan(text = " \uD83D\uDCDD ", annotation = annotation))
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "span" -> {
                        if (textBuffer.isNotEmpty()) {
                            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                            textBuffer.clear()
                        }
                        currentStyleName = null
                    }
                    "a" -> {
                        if (textBuffer.isNotEmpty()) {
                            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                            textBuffer.clear()
                        }
                        currentHref = null
                    }
                }
                XmlPullParser.TEXT -> textBuffer.append(parser.text)
            }
            eventType = parser.next()
        }
        if (textBuffer.isNotEmpty()) {
            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
        }
        return spans
    }

    // --- Footnotes ---

    private fun parseFootnote(parser: XmlPullParser, styles: Map<String, StyleInfo>): OdfFootnote? {
        var citation = ""
        val body = mutableListOf<OdfParagraph>()
        val depth = parser.depth
        var eventType = parser.next()

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) when (parser.name) {
                "note-citation" -> {
                    val citDepth = parser.depth
                    var citEvent = parser.next()
                    val sb = StringBuilder()
                    while (!(citEvent == XmlPullParser.END_TAG && parser.depth == citDepth)) {
                        if (citEvent == XmlPullParser.TEXT) sb.append(parser.text)
                        citEvent = parser.next()
                    }
                    citation = sb.toString().trim()
                }
                "note-body" -> {
                    val bodyDepth = parser.depth
                    var bodyEvent = parser.next()
                    while (!(bodyEvent == XmlPullParser.END_TAG && parser.depth == bodyDepth)) {
                        if (bodyEvent == XmlPullParser.START_TAG && parser.name == "p") {
                            val spans = parseInlineContent(parser, "p", styles)
                            if (spans.isNotEmpty()) body.add(OdfParagraph(spans))
                        }
                        bodyEvent = parser.next()
                    }
                }
            }
            eventType = parser.next()
        }
        return if (citation.isNotEmpty()) OdfFootnote(citation, body) else null
    }

    // --- Annotations ---

    private fun parseAnnotation(parser: XmlPullParser, styles: Map<String, StyleInfo>): OdfAnnotation? {
        var author: String? = null
        var date: String? = null
        val paragraphs = mutableListOf<OdfParagraph>()
        val depth = parser.depth
        var eventType = parser.next()
        var currentTag = ""

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (parser.name == "p" && parser.namespace?.contains("text") == true) {
                        val spans = parseInlineContent(parser, "p", styles)
                        if (spans.isNotEmpty()) paragraphs.add(OdfParagraph(spans))
                        currentTag = ""
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text.trim()
                    if (text.isNotEmpty()) when (currentTag) {
                        "creator" -> author = text
                        "date" -> date = text
                    }
                }
                XmlPullParser.END_TAG -> currentTag = ""
            }
            eventType = parser.next()
        }
        return OdfAnnotation(author, date, paragraphs)
    }

    // --- Text Document ---

    private fun parseTextDocument(
        xml: String, styles: Map<String, StyleInfo>, listStyles: Map<String, ListType>,
        title: String, metadata: OdfMetadata, images: Map<String, ByteArray>,
        headerFooter: HeaderFooterResult?, objectContents: Map<String, String> = emptyMap()
    ): OdfDocument.TextDocument {
        val content = mutableListOf<OdfContentBlock>()
        val footnotes = mutableListOf<OdfFootnote>()
        val bookmarks = mutableListOf<OdfBookmark>()
        val parser = newParser(xml)
        var inBody = false
        var listDepth = 0
        val listTypeStack = mutableListOf<ListType>()
        val listItemCounter = mutableListOf<Int>()
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when {
                    parser.name == "text" && parser.namespace?.contains("office") == true -> inBody = true
                    inBody && (parser.name == "bookmark" || parser.name == "bookmark-start") -> {
                        val bkName = getAttr(parser, "name")
                        if (bkName != null) bookmarks.add(OdfBookmark(bkName, content.size))
                    }
                    inBody && parser.name == "h" -> {
                        val level = getAttr(parser, "outline-level")?.toIntOrNull() ?: 1
                        val paraStyle = when (level) { 1 -> ParagraphStyle.HEADING1; 2 -> ParagraphStyle.HEADING2; 3 -> ParagraphStyle.HEADING3; else -> ParagraphStyle.HEADING4 }
                        val styleName = getAttr(parser, "style-name")
                        val resolved = resolveStyle(styleName, styles)
                        val inlineImages = mutableListOf<OdfImage>()
                        val inlineCharts = mutableListOf<OdfChart>()
                        val spans = parseInlineContent(parser, "h", styles, images, footnotes, inlineImages, objectContents, inlineCharts)
                        if (resolved.breakBefore == "page") content.add(OdfContentBlock.PageBreak)
                        val direction = parseDirection(resolved.writingMode)
                        content.add(OdfContentBlock.Paragraph(OdfParagraph(
                            spans = spans, style = paraStyle,
                            alignment = resolved.textAlign, marginLeft = resolved.marginLeft,
                            marginTop = resolved.marginTop, marginBottom = resolved.marginBottom,
                            textIndent = resolved.textIndent, backgroundColor = resolved.paragraphBackgroundColor,
                            direction = direction
                        )))
                        for (img in inlineImages) content.add(OdfContentBlock.Image(img))
                        for (ch in inlineCharts) content.add(OdfContentBlock.Chart(ch))
                        if (resolved.breakAfter == "page") content.add(OdfContentBlock.PageBreak)
                    }
                    inBody && parser.name == "p" && parser.namespace?.contains("text") == true -> {
                        val styleName = getAttr(parser, "style-name")
                        val resolved = resolveStyle(styleName, styles)
                        if (resolved.breakBefore == "page") content.add(OdfContentBlock.PageBreak)
                        val style = if (listDepth > 0) ParagraphStyle.LIST_ITEM else ParagraphStyle.BODY
                        val inlineImages = mutableListOf<OdfImage>()
                        val inlineCharts = mutableListOf<OdfChart>()
                        val spans = parseInlineContent(parser, "p", styles, images, footnotes, inlineImages, objectContents, inlineCharts)
                        val direction = parseDirection(resolved.writingMode)
                        if (spans.isNotEmpty() || listDepth > 0) {
                            val itemIdx = if (listItemCounter.isNotEmpty()) listItemCounter.last() else 0
                            content.add(OdfContentBlock.Paragraph(OdfParagraph(
                                spans = spans, style = style,
                                alignment = resolved.textAlign, marginLeft = resolved.marginLeft,
                                marginTop = resolved.marginTop, marginBottom = resolved.marginBottom,
                                textIndent = resolved.textIndent, backgroundColor = resolved.paragraphBackgroundColor,
                                listLevel = listDepth,
                                listType = if (listTypeStack.isNotEmpty()) listTypeStack.last() else ListType.BULLET,
                                listItemIndex = itemIdx,
                                direction = direction
                            )))
                        }
                        for (img in inlineImages) content.add(OdfContentBlock.Image(img))
                        for (ch in inlineCharts) content.add(OdfContentBlock.Chart(ch))
                        if (resolved.breakAfter == "page") content.add(OdfContentBlock.PageBreak)
                    }
                    inBody && parser.name == "list" -> {
                        listDepth++
                        val styleName = getAttr(parser, "style-name")
                        val type = when {
                            styleName != null -> listStyles[styleName] ?: ListType.BULLET
                            listTypeStack.isNotEmpty() -> listTypeStack.last()
                            else -> ListType.BULLET
                        }
                        listTypeStack.add(type)
                        listItemCounter.add(0)
                    }
                    inBody && parser.name == "list-item" -> {
                        if (listItemCounter.isNotEmpty()) {
                            listItemCounter[listItemCounter.size - 1]++
                        }
                    }
                    inBody && parser.name == "table" && parser.namespace?.contains("table") == true -> {
                        content.add(OdfContentBlock.Table(parseTextTable(parser, styles)))
                    }
                    inBody && parser.name == "table-of-content" -> {
                        parseTocContent(parser, styles, content)
                    }
                    inBody && parser.name == "frame" && parser.namespace?.contains("draw") == true -> {
                        val frame = parseSingleFrame(parser, styles, images)
                        frame.image?.let { content.add(OdfContentBlock.Image(it)) }
                        for (para in frame.paragraphs) {
                            content.add(OdfContentBlock.Paragraph(para))
                        }
                    }
                }
                XmlPullParser.END_TAG -> when {
                    parser.name == "text" && parser.namespace?.contains("office") == true -> inBody = false
                    parser.name == "list" && inBody -> {
                        listDepth--
                        if (listTypeStack.isNotEmpty()) listTypeStack.removeAt(listTypeStack.size - 1)
                        if (listItemCounter.isNotEmpty()) listItemCounter.removeAt(listItemCounter.size - 1)
                    }
                }
            }
            eventType = parser.next()
        }
        return OdfDocument.TextDocument(title, content, metadata, images, footnotes,
            headerParagraphs = headerFooter?.headerParagraphs ?: emptyList(),
            footerParagraphs = headerFooter?.footerParagraphs ?: emptyList(),
            bookmarks = bookmarks)
    }

    private fun parseDirection(writingMode: String?): LayoutDirection? = when {
        writingMode == null -> null
        writingMode.startsWith("rl") -> LayoutDirection.Rtl
        writingMode.startsWith("lr") -> LayoutDirection.Ltr
        else -> null
    }

    // --- Tables in text documents ---

    private fun parseTextTable(parser: XmlPullParser, styles: Map<String, StyleInfo>): OdfTable {
        val tableName = getAttr(parser, "name") ?: ""
        val columns = mutableListOf<OdfTableColumn>()
        val rows = mutableListOf<OdfTableRow>()
        val depth = parser.depth
        var eventType = parser.next()

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) when (parser.name) {
                "table-column" -> {
                    val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                    val styleName = getAttr(parser, "style-name")
                    val width = resolveStyle(styleName, styles).columnWidth
                    repeat(repeated.coerceAtMost(100)) { columns.add(OdfTableColumn(width = width)) }
                }
                "table-row" -> rows.add(OdfTableRow(parseTableCells(parser, styles)))
                "table-header-rows" -> { /* continue into children */ }
            }
            eventType = parser.next()
        }
        return OdfTable(tableName, columns, rows)
    }

    private fun parseTableCells(parser: XmlPullParser, styles: Map<String, StyleInfo>): List<OdfTableCell> {
        val cells = mutableListOf<OdfTableCell>()
        val depth = parser.depth
        var eventType = parser.next()

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) when (parser.name) {
                "table-cell" -> {
                    val colSpan = getAttr(parser, "number-columns-spanned")?.toIntOrNull() ?: 1
                    val rowSpan = getAttr(parser, "number-rows-spanned")?.toIntOrNull() ?: 1
                    val styleName = getAttr(parser, "style-name")
                    val resolved = resolveStyle(styleName, styles)
                    cells.add(OdfTableCell(
                        paragraphs = parseTableCellContent(parser, styles),
                        colSpan = colSpan, rowSpan = rowSpan,
                        backgroundColor = resolved.cellBackgroundColor,
                        borderColor = resolved.cellBorderColor
                    ))
                }
                "covered-table-cell" -> {
                    val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                    repeat(repeated.coerceAtMost(100)) { cells.add(OdfTableCell(isCovered = true)) }
                    skipElement(parser)
                }
            }
            eventType = parser.next()
        }
        return cells
    }

    private fun parseTableCellContent(parser: XmlPullParser, styles: Map<String, StyleInfo>): List<OdfParagraph> {
        val paragraphs = mutableListOf<OdfParagraph>()
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "p") {
                val spans = parseInlineContent(parser, "p", styles)
                paragraphs.add(OdfParagraph(spans))
            }
            eventType = parser.next()
        }
        return paragraphs
    }

    // --- TOC ---

    private fun parseTocContent(parser: XmlPullParser, styles: Map<String, StyleInfo>, content: MutableList<OdfContentBlock>) {
        val depth = parser.depth
        var eventType = parser.next()
        var inIndexBody = false

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            when {
                eventType == XmlPullParser.START_TAG && parser.name == "index-body" -> inIndexBody = true
                eventType == XmlPullParser.END_TAG && parser.name == "index-body" -> inIndexBody = false
                eventType == XmlPullParser.START_TAG && parser.name == "p" && inIndexBody -> {
                    val spans = parseInlineContent(parser, "p", styles)
                    if (spans.isNotEmpty()) content.add(OdfContentBlock.Paragraph(OdfParagraph(spans)))
                }
            }
            eventType = parser.next()
        }
    }

    // --- Embedded charts ---

    private fun parseChart(xml: String): OdfChart? {
        val parser = newParser(xml)
        var eventType = parser.eventType
        var chartClass: String? = null
        var inTable = false
        var inCell = false
        var curRow: MutableList<Pair<String, Float?>>? = null
        var cellText = StringBuilder()
        var cellValue: Float? = null
        val rows = mutableListOf<List<Pair<String, Float?>>>()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "chart" -> if (chartClass == null) chartClass = getAttr(parser, "class")
                    "table" -> if (getAttr(parser, "name") == "local-table") inTable = true
                    "table-row" -> if (inTable) curRow = mutableListOf()
                    "table-cell" -> if (inTable && curRow != null) { inCell = true; cellText = StringBuilder(); cellValue = getAttr(parser, "value")?.toFloatOrNull() }
                }
                XmlPullParser.TEXT -> if (inCell) cellText.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "table" -> if (inTable) inTable = false
                    "table-cell" -> if (inCell) { curRow?.add(cellText.toString().trim() to cellValue); inCell = false }
                    "table-row" -> if (curRow != null) { rows.add(curRow!!); curRow = null }
                }
            }
            eventType = parser.next()
        }
        if (rows.size < 2) return null
        val header = rows[0]
        val seriesCount = (header.size - 1).coerceAtLeast(0)
        if (seriesCount == 0) return null
        val seriesNames = (1..seriesCount).map { header.getOrNull(it)?.first?.ifEmpty { "Series $it" } ?: "Series $it" }
        val seriesValues = List(seriesCount) { mutableListOf<Float>() }
        val categories = mutableListOf<String>()
        for (i in 1 until rows.size) {
            val r = rows[i]
            categories.add(r.getOrNull(0)?.first ?: "")
            for (j in 0 until seriesCount) {
                val cell = r.getOrNull(j + 1)
                seriesValues[j].add(cell?.second ?: cell?.first?.toFloatOrNull() ?: 0f)
            }
        }
        val type = when {
            chartClass?.contains("line") == true -> ChartType.LINE
            chartClass?.contains("circle") == true || chartClass?.contains("ring") == true -> ChartType.PIE
            chartClass?.contains("area") == true -> ChartType.AREA
            else -> ChartType.BAR
        }
        val series = seriesNames.mapIndexed { idx, n -> OdfChartSeries(n, seriesValues[idx]) }
        return if (categories.isEmpty()) null else OdfChart(type, categories, series)
    }

    // --- Spreadsheet ---

    private fun parseSpreadsheet(
        xml: String, styles: Map<String, StyleInfo>,
        title: String, metadata: OdfMetadata, images: Map<String, ByteArray>
    ): OdfDocument.Spreadsheet {
        val sheets = mutableListOf<OdfSheet>()
        val parser = newParser(xml)
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "table" && parser.namespace?.contains("table") == true) {
                val name = getAttr(parser, "name") ?: "Sheet ${sheets.size + 1}"
                val result = parseSheetContent(parser, styles)
                sheets.add(OdfSheet(name, result.first, result.second))
            }
            eventType = parser.next()
        }
        return OdfDocument.Spreadsheet(title, sheets, metadata, images)
    }

    private fun parseSheetContent(parser: XmlPullParser, styles: Map<String, StyleInfo>): Pair<List<OdfRow>, List<Float?>> {
        val rows = mutableListOf<OdfRow>()
        val columnWidths = mutableListOf<Float?>()
        val depth = parser.depth
        var eventType = parser.next()

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) when (parser.name) {
                "table-column" -> {
                    val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                    repeat(repeated.coerceAtMost(200)) { columnWidths.add(null) }
                }
                "table-row" -> {
                    val cells = parseSpreadsheetCells(parser, styles)
                    if (cells.any { it.text.isNotEmpty() }) rows.add(OdfRow(cells))
                }
            }
            eventType = parser.next()
        }
        return Pair(rows, columnWidths)
    }

    private fun parseSpreadsheetCells(parser: XmlPullParser, styles: Map<String, StyleInfo>): List<OdfCell> {
        val cells = mutableListOf<OdfCell>()
        val depth = parser.depth
        var eventType = parser.next()

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) when (parser.name) {
                "table-cell" -> {
                    val spanned = getAttr(parser, "number-columns-spanned")?.toIntOrNull() ?: 1
                    val rowSpan = getAttr(parser, "number-rows-spanned")?.toIntOrNull() ?: 1
                    val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                    val styleName = getAttr(parser, "style-name")
                    val resolved = resolveStyle(styleName, styles)
                    val text = parseCellText(parser)
                    repeat(repeated.coerceAtMost(100)) {
                        cells.add(OdfCell(
                            text = text, spannedColumns = spanned, rowSpan = rowSpan,
                            backgroundColor = resolved.cellBackgroundColor, textColor = resolved.color,
                            bold = resolved.bold, italic = resolved.italic, alignment = resolved.textAlign,
                            borderColor = resolved.cellBorderColor
                        ))
                    }
                }
                "covered-table-cell" -> {
                    val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                    repeat(repeated.coerceAtMost(100)) { cells.add(OdfCell(text = "", isCovered = true)) }
                    skipElement(parser)
                }
            }
            eventType = parser.next()
        }
        return cells
    }

    private fun parseCellText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.TEXT) sb.append(parser.text)
            eventType = parser.next()
        }
        return sb.toString().trim()
    }

    // --- Presentation / Drawing ---

    private fun parsePresentation(
        xml: String, styles: Map<String, StyleInfo>,
        title: String, metadata: OdfMetadata, images: Map<String, ByteArray>
    ): OdfDocument.Presentation =
        OdfDocument.Presentation(title, parseSlides(xml, styles, images), metadata, images)

    private fun parseDrawing(
        xml: String, styles: Map<String, StyleInfo>,
        title: String, metadata: OdfMetadata, images: Map<String, ByteArray>
    ): OdfDocument.Drawing =
        OdfDocument.Drawing(title, parseSlides(xml, styles, images), metadata, images)

    private fun parseSlides(xml: String, styles: Map<String, StyleInfo>, images: Map<String, ByteArray>): List<OdfSlide> {
        val slides = mutableListOf<OdfSlide>()
        val parser = newParser(xml)
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "page" && parser.namespace?.contains("draw") == true) {
                val name = getAttr(parser, "name") ?: "Slide ${slides.size + 1}"
                val drawStyleName = getAttr(parser, "style-name")
                val resolved = resolveStyle(drawStyleName, styles)
                val result = parseSlideContent(parser, styles, images)
                slides.add(OdfSlide(
                    name = name, elements = result.elements,
                    backgroundColor = resolved.drawFillColor, notes = result.notes
                ))
            }
            eventType = parser.next()
        }
        return slides
    }

    private data class SlideParseResult(val elements: List<OdfSlideElement>, val notes: List<OdfParagraph>)

    private fun parseSlideContent(parser: XmlPullParser, styles: Map<String, StyleInfo>, images: Map<String, ByteArray>): SlideParseResult {
        val elements = mutableListOf<OdfSlideElement>()
        val notes = mutableListOf<OdfParagraph>()
        val depth = parser.depth
        var eventType = parser.next()

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) when (parser.name) {
                "frame" -> elements.add(OdfSlideElement.Frame(parseSingleFrame(parser, styles, images)))
                "rect" -> elements.add(OdfSlideElement.Shape(parseShape(parser, styles, "rect")))
                "ellipse" -> elements.add(OdfSlideElement.Shape(parseShape(parser, styles, "ellipse")))
                "line" -> elements.add(OdfSlideElement.Shape(parseShape(parser, styles, "line")))
                "custom-shape" -> elements.add(OdfSlideElement.Shape(parseShape(parser, styles, "custom-shape")))
                "notes" -> {
                    val noteDepth = parser.depth
                    var noteEvent = parser.next()
                    while (!(noteEvent == XmlPullParser.END_TAG && parser.depth == noteDepth)) {
                        if (noteEvent == XmlPullParser.START_TAG && parser.name == "frame") {
                            val frame = parseSingleFrame(parser, styles, images)
                            for (para in frame.paragraphs) {
                                if (para.spans.isNotEmpty()) notes.add(para)
                            }
                        }
                        noteEvent = parser.next()
                    }
                }
            }
            eventType = parser.next()
        }
        return SlideParseResult(elements, notes)
    }

    private fun parseSingleFrame(parser: XmlPullParser, styles: Map<String, StyleInfo>, images: Map<String, ByteArray>): OdfFrame {
        val x = parseDimension(getAttr(parser, "x"))
        val y = parseDimension(getAttr(parser, "y"))
        val w = parseDimension(getAttr(parser, "width"))
        val h = parseDimension(getAttr(parser, "height"))
        val styleName = getAttr(parser, "style-name")
        val resolved = resolveStyle(styleName, styles)

        val paragraphs = mutableListOf<OdfParagraph>()
        var image: OdfImage? = null
        val depth = parser.depth
        var eventType = parser.next()

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) when (parser.name) {
                "text-box" -> {
                    val boxDepth = parser.depth
                    var boxEvent = parser.next()
                    while (!(boxEvent == XmlPullParser.END_TAG && parser.depth == boxDepth)) {
                        if (boxEvent == XmlPullParser.START_TAG && parser.name == "p" && parser.namespace?.contains("text") == true) {
                            val spans = parseInlineContent(parser, "p", styles, images)
                            if (spans.isNotEmpty()) paragraphs.add(OdfParagraph(spans))
                        } else if (boxEvent == XmlPullParser.START_TAG && parser.name == "list") {
                            parseListInFrame(parser, styles, images, paragraphs)
                        }
                        boxEvent = parser.next()
                    }
                }
                "image" -> {
                    val href = getAttr(parser, "href")
                    if (href != null && images.containsKey(href)) {
                        image = OdfImage(path = href, imageData = images[href]!!, width = w, height = h)
                    }
                    val imgDepth = parser.depth
                    var imgEvent = parser.next()
                    while (!(imgEvent == XmlPullParser.END_TAG && parser.depth == imgDepth)) {
                        if (imgEvent == XmlPullParser.START_TAG && parser.name == "binary-data") {
                            imgEvent = parser.next()
                            if (imgEvent == XmlPullParser.TEXT) {
                                try {
                                    val bytes = Base64.decode(parser.text.trim(), Base64.DEFAULT)
                                    image = OdfImage(path = "inline", imageData = bytes, width = w, height = h)
                                } catch (_: Exception) { }
                            }
                        }
                        imgEvent = parser.next()
                    }
                }
                "p" -> if (parser.namespace?.contains("text") == true) {
                    val spans = parseInlineContent(parser, "p", styles, images)
                    if (spans.isNotEmpty()) paragraphs.add(OdfParagraph(spans))
                }
            }
            eventType = parser.next()
        }
        return OdfFrame(x, y, w, h, paragraphs, image, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth)
    }

    private fun parseListInFrame(parser: XmlPullParser, styles: Map<String, StyleInfo>, images: Map<String, ByteArray>, paragraphs: MutableList<OdfParagraph>) {
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "p" && parser.namespace?.contains("text") == true) {
                val spans = parseInlineContent(parser, "p", styles, images)
                if (spans.isNotEmpty()) paragraphs.add(OdfParagraph(spans, ParagraphStyle.LIST_ITEM, listLevel = 1))
            } else if (eventType == XmlPullParser.START_TAG && parser.name == "list") {
                parseListInFrame(parser, styles, images, paragraphs)
            }
            eventType = parser.next()
        }
    }

    // --- Shapes ---

    private fun parseShape(parser: XmlPullParser, styles: Map<String, StyleInfo>, shapeName: String): OdfShape {
        val x = parseDimension(getAttr(parser, "x"))
        val y = parseDimension(getAttr(parser, "y"))
        val w = parseDimension(getAttr(parser, "width"))
        val h = parseDimension(getAttr(parser, "height"))
        val x2 = if (shapeName == "line") parseDimension(getAttr(parser, "x2")) else 0f
        val y2 = if (shapeName == "line") parseDimension(getAttr(parser, "y2")) else 0f
        val styleName = getAttr(parser, "style-name")
        val resolved = resolveStyle(styleName, styles)

        val text = mutableListOf<OdfParagraph>()
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "p" && parser.namespace?.contains("text") == true) {
                val spans = parseInlineContent(parser, "p", styles)
                if (spans.isNotEmpty()) text.add(OdfParagraph(spans))
            }
            eventType = parser.next()
        }

        return when (shapeName) {
            "rect" -> OdfShape.Rect(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text)
            "ellipse" -> OdfShape.Ellipse(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text)
            "line" -> OdfShape.Line(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text, x2, y2)
            else -> OdfShape.CustomShape(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text)
        }
    }

    // --- CSV parsing ---

    fun parseCsv(text: String, fileName: String): OdfDocument.Spreadsheet {
        val rows = mutableListOf<OdfRow>()
        val lines = text.lines()
        for (line in lines) {
            if (line.isBlank()) continue
            val cells = parseCsvLine(line).map { OdfCell(text = it) }
            rows.add(OdfRow(cells))
        }
        return OdfDocument.Spreadsheet(fileName, listOf(OdfSheet("Sheet 1", rows)))
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && !inQuotes -> inQuotes = true
                c == '"' && inQuotes -> {
                    if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ }
                    else inQuotes = false
                }
                c == ',' && !inQuotes -> { fields.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        fields.add(sb.toString())
        return fields
    }

    private const val LINK_COLOR = 0xFF0066CCL
}
