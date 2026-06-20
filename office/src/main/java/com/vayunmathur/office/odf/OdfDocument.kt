package com.vayunmathur.office.odf

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection

sealed class OdfDocument {
    abstract val title: String
    abstract val metadata: OdfMetadata

    data class TextDocument(
        override val title: String,
        val content: List<OdfContentBlock>,
        override val metadata: OdfMetadata = OdfMetadata(),
        val images: Map<String, ByteArray> = emptyMap(),
        val footnotes: List<OdfFootnote> = emptyList(),
        val headerParagraphs: List<OdfParagraph> = emptyList(),
        val footerParagraphs: List<OdfParagraph> = emptyList(),
        val bookmarks: List<OdfBookmark> = emptyList()
    ) : OdfDocument()

    data class Spreadsheet(
        override val title: String,
        val sheets: List<OdfSheet>,
        override val metadata: OdfMetadata = OdfMetadata(),
        val images: Map<String, ByteArray> = emptyMap()
    ) : OdfDocument()

    data class Presentation(
        override val title: String,
        val slides: List<OdfSlide>,
        override val metadata: OdfMetadata = OdfMetadata(),
        val images: Map<String, ByteArray> = emptyMap()
    ) : OdfDocument()

    data class Drawing(
        override val title: String,
        val pages: List<OdfSlide>,
        override val metadata: OdfMetadata = OdfMetadata(),
        val images: Map<String, ByteArray> = emptyMap()
    ) : OdfDocument()
}

sealed class OdfContentBlock {
    data class Paragraph(val paragraph: OdfParagraph) : OdfContentBlock()
    data class Table(val table: OdfTable) : OdfContentBlock()
    data class Image(val image: OdfImage) : OdfContentBlock()
    data class Chart(val chart: OdfChart) : OdfContentBlock()
    data class Formula(val mathml: String) : OdfContentBlock()
    data object PageBreak : OdfContentBlock()
}

enum class ChartType { BAR, LINE, PIE, AREA, DONUT, SCATTER, STACKED_BAR }

data class OdfChartSeries(val name: String, val values: List<Float>)

data class OdfChart(
    val type: ChartType,
    val categories: List<String>,
    val series: List<OdfChartSeries>,
    val title: String? = null
)

data class OdfParagraph(
    val spans: List<OdfSpan>,
    val style: ParagraphStyle = ParagraphStyle.BODY,
    val alignment: TextAlign? = null,
    val marginLeft: Float = 0f,
    val marginTop: Float = 0f,
    val marginBottom: Float = 0f,
    val textIndent: Float = 0f,
    val backgroundColor: Long? = null,
    val listLevel: Int = 0,
    val listType: ListType = ListType.BULLET,
    val listItemIndex: Int = 0,
    val direction: LayoutDirection? = null,
    // Line spacing as a multiple of normal (1.0 = single, 1.5 = 150%); null = unspecified. (A6)
    val lineHeightPercent: Float? = null,
    // Paragraph border color (single uniform border) if any. (A7)
    val borderColor: Long? = null,
    // Number format for numbered lists: "1", "a", "A", "i", "I". (F42)
    val listNumberFormat: String = "1",
    // Bullet glyph for bullet lists. (F42)
    val listBulletChar: String = "\u2022",
    // Number prefix/suffix (e.g. "(" and ")" for "(1)"). (F42)
    val listNumberPrefix: String = "",
    val listNumberSuffix: String = ".",
    // Tab stop positions in px (96dpi). (A4)
    val tabStops: List<Float> = emptyList()
)

enum class ParagraphStyle { HEADING1, HEADING2, HEADING3, HEADING4, BODY, LIST_ITEM, TABLE_HEADER }

enum class ListType { BULLET, NUMBERED }

/** Formats a 1-based list item index using an ODF number-format token ("1","a","A","i","I"). (F42) */
fun formatListNumber(index: Int, format: String): String {
    val n = index.coerceAtLeast(1)
    return when (format) {
        "a" -> toAlpha(n).lowercase()
        "A" -> toAlpha(n)
        "i" -> toRoman(n).lowercase()
        "I" -> toRoman(n)
        else -> n.toString()
    }
}

private fun toAlpha(n: Int): String {
    val sb = StringBuilder()
    var v = n
    while (v > 0) {
        val rem = (v - 1) % 26
        sb.insert(0, ('A' + rem))
        v = (v - 1) / 26
    }
    return sb.toString()
}

private fun toRoman(n: Int): String {
    if (n !in 1..3999) return n.toString()
    val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
    val sb = StringBuilder()
    var v = n
    for (i in values.indices) {
        while (v >= values[i]) { sb.append(symbols[i]); v -= values[i] }
    }
    return sb.toString()
}

data class OdfSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val fontSize: Float? = null,
    val fontFamily: String? = null,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val color: Long? = null,
    val backgroundColor: Long? = null,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    val href: String? = null,
    val annotation: OdfAnnotation? = null
)

@Suppress("ArrayInDataClass")
data class OdfImage(
    val path: String,
    val imageData: ByteArray,
    val width: Float = 0f,
    val height: Float = 0f,
    val anchorType: String = "",
    // Rotation in degrees clockwise (E38).
    val rotationDegrees: Float = 0f
)

data class OdfTable(
    val name: String = "",
    val columns: List<OdfTableColumn> = emptyList(),
    val rows: List<OdfTableRow> = emptyList()
)

data class OdfTableColumn(val width: Float? = null)

data class OdfTableRow(val cells: List<OdfTableCell>)

data class OdfTableCell(
    val paragraphs: List<OdfParagraph> = emptyList(),
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    val backgroundColor: Long? = null,
    val borderColor: Long? = null,
    val isCovered: Boolean = false
)

data class OdfSheet(
    val name: String,
    val rows: List<OdfRow>,
    val columnWidths: List<Float?> = emptyList()
)

data class OdfRow(val cells: List<OdfCell>)

data class OdfCell(
    val text: String,
    val spannedColumns: Int = 1,
    val rowSpan: Int = 1,
    val backgroundColor: Long? = null,
    val textColor: Long? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val alignment: TextAlign? = null,
    val borderColor: Long? = null,
    val isCovered: Boolean = false,
    // OpenFormula source (e.g. "of:=SUM([.A1:.A3])"), if this is a formula cell. (H49)
    val formula: String? = null,
    // ODF value type: "float", "string", "date", "percentage", "currency", "boolean". (H50/H54)
    val valueType: String? = null,
    // Cached numeric value from office:value, if numeric. (H54)
    val numberValue: Double? = null,
    // Resolved number/date/currency display format. (H50)
    val numberFormat: OdfNumberFormat? = null,
    // Wrap text in cell. (H53)
    val wrap: Boolean = false
)

/** Resolved spreadsheet number-format descriptor (H50). */
data class OdfNumberFormat(
    val decimals: Int? = null,
    val percent: Boolean = false,
    val currencySymbol: String? = null,
    val grouping: Boolean = false,
    val isDate: Boolean = false
)

data class OdfSlide(
    val name: String,
    val elements: List<OdfSlideElement> = emptyList(),
    val backgroundColor: Long? = null,
    val backgroundImagePath: String? = null,
    val notes: List<OdfParagraph> = emptyList()
)

sealed class OdfSlideElement {
    data class Frame(val frame: OdfFrame) : OdfSlideElement()
    data class Shape(val shape: OdfShape) : OdfSlideElement()
}

data class OdfFrame(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val paragraphs: List<OdfParagraph>,
    val image: OdfImage? = null,
    val fillColor: Long? = null,
    val strokeColor: Long? = null,
    val strokeWidth: Float? = null
)

sealed class OdfShape {
    abstract val x: Float
    abstract val y: Float
    abstract val width: Float
    abstract val height: Float
    abstract val fillColor: Long?
    abstract val strokeColor: Long?
    abstract val strokeWidth: Float?
    abstract val text: List<OdfParagraph>

    data class Rect(
        override val x: Float, override val y: Float,
        override val width: Float, override val height: Float,
        override val fillColor: Long? = null,
        override val strokeColor: Long? = null,
        override val strokeWidth: Float? = null,
        override val text: List<OdfParagraph> = emptyList(),
        val cornerRadius: Float = 0f
    ) : OdfShape()

    data class Ellipse(
        override val x: Float, override val y: Float,
        override val width: Float, override val height: Float,
        override val fillColor: Long? = null,
        override val strokeColor: Long? = null,
        override val strokeWidth: Float? = null,
        override val text: List<OdfParagraph> = emptyList()
    ) : OdfShape()

    data class Line(
        override val x: Float, override val y: Float,
        override val width: Float, override val height: Float,
        override val fillColor: Long? = null,
        override val strokeColor: Long? = null,
        override val strokeWidth: Float? = null,
        override val text: List<OdfParagraph> = emptyList(),
        val x2: Float = 0f, val y2: Float = 0f
    ) : OdfShape()

    data class CustomShape(
        override val x: Float, override val y: Float,
        override val width: Float, override val height: Float,
        override val fillColor: Long? = null,
        override val strokeColor: Long? = null,
        override val strokeWidth: Float? = null,
        override val text: List<OdfParagraph> = emptyList()
    ) : OdfShape()
}

data class OdfFootnote(
    val citation: String,
    val body: List<OdfParagraph>
)

data class OdfAnnotation(
    val author: String? = null,
    val date: String? = null,
    val paragraphs: List<OdfParagraph> = emptyList()
)

data class OdfBookmark(
    val name: String,
    val contentIndex: Int
)
