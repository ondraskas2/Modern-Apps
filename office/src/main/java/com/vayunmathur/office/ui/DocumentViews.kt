package com.vayunmathur.office.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.office.odf.*

// --- Headings for outline ---

data class HeadingItem(val text: String, val level: Int, val contentIndex: Int)

fun extractHeadings(doc: OdfDocument.TextDocument): List<HeadingItem> {
    val headings = mutableListOf<HeadingItem>()
    doc.content.forEachIndexed { index, block ->
        if (block is OdfContentBlock.Paragraph) {
            val style = block.paragraph.style
            if (style == ParagraphStyle.HEADING1 || style == ParagraphStyle.HEADING2 ||
                style == ParagraphStyle.HEADING3 || style == ParagraphStyle.HEADING4
            ) {
                val text = block.paragraph.spans.joinToString("") { it.text }
                val level = when (style) {
                    ParagraphStyle.HEADING1 -> 1; ParagraphStyle.HEADING2 -> 2
                    ParagraphStyle.HEADING3 -> 3; else -> 4
                }
                headings.add(HeadingItem(text, level, index))
            }
        }
    }
    return headings
}

fun countWords(doc: OdfDocument.TextDocument): Int {
    var count = 0
    for (block in doc.content) if (block is OdfContentBlock.Paragraph) for (span in block.paragraph.spans) count += span.text.split(Regex("\\s+")).count { it.isNotEmpty() }
    return count
}

fun countChars(doc: OdfDocument.TextDocument): Int {
    var count = 0
    for (block in doc.content) if (block is OdfContentBlock.Paragraph) for (span in block.paragraph.spans) count += span.text.length
    return count
}

fun readingTimeMinutes(doc: OdfDocument.TextDocument): Int = maxOf(1, (countWords(doc) + 199) / 200)

// --- Color Picker ---

@Composable
fun ColorPickerDialog(title: String, onColorSelected: (Long?) -> Unit, onDismiss: () -> Unit) {
    val colors = listOf(
        null, 0xFF000000L, 0xFFFFFFFF, 0xFFFF0000L, 0xFF00FF00L, 0xFF0000FFL,
        0xFFFFFF00L, 0xFFFF00FFL, 0xFF00FFFFL, 0xFF800000L, 0xFF008000L, 0xFF000080L,
        0xFF808000L, 0xFF800080L, 0xFF008080L, 0xFF808080L, 0xFFC0C0C0L,
        0xFFFF6600L, 0xFF6633CCL, 0xFF336699L, 0xFF993366L, 0xFF333300L,
        0xFF003300L, 0xFF003366L, 0xFF660066L, 0xFF333333L
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                for (row in colors.chunked(6)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in row) {
                            val bgColor = c?.let { Color(it.toInt()) } ?: Color.Transparent
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(bgColor, CircleShape)
                                    .border(1.dp, Color.Gray, CircleShape)
                                    .clickable { onColorSelected(c); onDismiss() }
                            ) {
                                if (c == null) Text("∅", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Font Size Picker ---

@Composable
fun FontSizePickerDialog(onSizeSelected: (Float) -> Unit, onDismiss: () -> Unit) {
    val sizes = listOf(8f, 9f, 10f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f, 36f, 48f, 72f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Font Size") },
        text = {
            Column {
                for (row in sizes.chunked(5)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (size in row) {
                            Surface(
                                modifier = Modifier.clickable { onSizeSelected(size); onDismiss() },
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text("${size.toInt()}", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Insert Table Dialog ---

@Composable
fun InsertTableDialog(onInsert: (rows: Int, cols: Int) -> Unit, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf("3") }
    var cols by remember { mutableStateOf("3") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert Table") },
        text = {
            Column {
                TextField(value = rows, onValueChange = { rows = it }, label = { Text("Rows") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                TextField(value = cols, onValueChange = { cols = it }, label = { Text("Columns") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val r = rows.toIntOrNull()?.coerceIn(1, 50) ?: 3
                val c = cols.toIntOrNull()?.coerceIn(1, 26) ?: 3
                onInsert(r, c)
                onDismiss()
            }) { Text("Insert") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Insert Hyperlink Dialog ---

@Composable
fun InsertHyperlinkDialog(onInsert: (text: String, url: String) -> Unit, onDismiss: () -> Unit) {
    var linkText by remember { mutableStateOf("") }
    var linkUrl by remember { mutableStateOf("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert Hyperlink") },
        text = {
            Column {
                TextField(value = linkText, onValueChange = { linkText = it }, label = { Text("Display Text") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                TextField(value = linkUrl, onValueChange = { linkUrl = it }, label = { Text("URL") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (linkText.isNotBlank() && linkUrl.isNotBlank()) { onInsert(linkText, linkUrl); onDismiss() }
            }) { Text("Insert") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Go-to-Slide Dialog ---

@Composable
fun GoToSlideDialog(total: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to Slide") },
        text = {
            TextField(value = input, onValueChange = { input = it },
                label = { Text("Slide number (1-$total)") }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = {
                val n = input.toIntOrNull()
                if (n != null && n in 1..total) { onGo(n - 1); onDismiss() }
            }) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Add Bookmark Dialog ---

@Composable
fun AddBookmarkDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Bookmark") },
        text = { TextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { onAdd(name); onDismiss() } }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Settings Dialog ---

@Composable
fun SettingsDialog(
    autoSave: Boolean,
    autoSaveInterval: Int,
    defaultFontSize: Float,
    onSave: (autoSave: Boolean, interval: Int, fontSize: Float) -> Unit,
    onDismiss: () -> Unit
) {
    var autoSaveEnabled by remember { mutableStateOf(autoSave) }
    var interval by remember { mutableStateOf(autoSaveInterval.toString()) }
    var fontSize by remember { mutableFloatStateOf(defaultFontSize) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-save", modifier = Modifier.weight(1f))
                    TextButton(onClick = { autoSaveEnabled = !autoSaveEnabled }) {
                        Text(if (autoSaveEnabled) "ON" else "OFF",
                            color = if (autoSaveEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (autoSaveEnabled) {
                    TextField(value = interval, onValueChange = { interval = it },
                        label = { Text("Interval (seconds)") }, singleLine = true)
                }
                Spacer(Modifier.height(16.dp))
                Text("Default Font Size: ${fontSize.toInt()}pt")
                androidx.compose.material3.Slider(value = fontSize, onValueChange = { fontSize = it }, valueRange = 8f..48f)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(autoSaveEnabled, interval.toIntOrNull() ?: 60, fontSize)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Sort Dialog ---

@Composable
fun SortDialog(maxCols: Int, onSort: (colIndex: Int, ascending: Boolean) -> Unit, onDismiss: () -> Unit) {
    var col by remember { mutableStateOf("0") }
    var ascending by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort Rows") },
        text = {
            Column {
                TextField(value = col, onValueChange = { col = it },
                    label = { Text("Column index (0-${maxCols - 1})") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Order:", modifier = Modifier.weight(1f))
                    TextButton(onClick = { ascending = !ascending }) {
                        Text(if (ascending) "A→Z ↑" else "Z→A ↓")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = col.toIntOrNull()?.coerceIn(0, maxCols - 1) ?: 0
                onSort(c, ascending); onDismiss()
            }) { Text("Sort") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Text Document (continuous editor) ---

private sealed class DocSegment {
    data class Paragraphs(val start: Int, val endInclusive: Int) : DocSegment()
    data class Block(val index: Int) : DocSegment()
}

private fun buildSegments(content: List<OdfContentBlock>): List<DocSegment> {
    val segments = mutableListOf<DocSegment>()
    var runStart = -1
    content.forEachIndexed { i, block ->
        if (block is OdfContentBlock.Paragraph) {
            if (runStart < 0) runStart = i
        } else {
            if (runStart >= 0) { segments.add(DocSegment.Paragraphs(runStart, i - 1)); runStart = -1 }
            segments.add(DocSegment.Block(i))
        }
    }
    if (runStart >= 0) segments.add(DocSegment.Paragraphs(runStart, content.size - 1))
    return segments
}

@Composable
fun TextDocumentView(
    doc: OdfDocument.TextDocument,
    searchQuery: String = "",
    fontSizeMultiplier: Float = 1f,
    listState: LazyListState = rememberLazyListState(),
    onRunSelectionChange: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    onRunTextChange: (Int, Int, String) -> Unit = { _, _, _ -> },
    onCellTextChange: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> },
    onCellFocus: (Int, Int, Int) -> Unit = { _, _, _ -> }
) {
    val segments = remember(doc.content) { buildSegments(doc.content) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        if (doc.headerParagraphs.isNotEmpty()) {
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) { for (para in doc.headerParagraphs) ParagraphView(para, "", fontSizeMultiplier) }
                }
            }
        }

        items(segments.size) { si ->
            when (val seg = segments[si]) {
                is DocSegment.Paragraphs -> ContinuousParagraphEditor(doc, seg.start, seg.endInclusive, fontSizeMultiplier, onRunSelectionChange, onRunTextChange)
                is DocSegment.Block -> when (val block = doc.content[seg.index]) {
                    is OdfContentBlock.Table -> TableView(block.table, seg.index, searchQuery, fontSizeMultiplier, onCellTextChange, onCellFocus)
                    is OdfContentBlock.Image -> {
                        var fullScreen by remember { mutableStateOf(false) }
                        if (fullScreen) FullScreenImage(block.image) { fullScreen = false }
                        else Box(modifier = Modifier.clickable { fullScreen = true }) { OdfImageView(block.image) }
                    }
                    is OdfContentBlock.PageBreak -> PageBreakView()
                    is OdfContentBlock.Chart -> OdfChartView(block.chart)
                    is OdfContentBlock.Paragraph -> {}
                }
            }
        }

        if (doc.footnotes.isNotEmpty()) {
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
            items(doc.footnotes.size) { index ->
                val fn = doc.footnotes[index]
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("${fn.citation} ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f)) { for (para in fn.body) ParagraphView(para, searchQuery, fontSizeMultiplier) }
                }
            }
        }

        if (doc.footerParagraphs.isNotEmpty()) {
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) { for (para in doc.footerParagraphs) ParagraphView(para, "", fontSizeMultiplier) }
                }
            }
        }
    }
}

private fun headingSizeSp(style: ParagraphStyle): Float? = when (style) {
    ParagraphStyle.HEADING1 -> 30f
    ParagraphStyle.HEADING2 -> 26f
    ParagraphStyle.HEADING3 -> 22f
    ParagraphStyle.HEADING4 -> 20f
    else -> null
}

private fun listPrefix(para: OdfParagraph): String = when {
    para.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.NUMBERED -> "${para.listItemIndex}.  "
    para.style == ParagraphStyle.LIST_ITEM -> "•  "
    else -> ""
}

@Composable
private fun ContinuousParagraphEditor(
    doc: OdfDocument.TextDocument,
    start: Int,
    endInclusive: Int,
    fontSizeMultiplier: Float,
    onSelectionChange: (Int, Int, Int, Int) -> Unit,
    onTextChange: (Int, Int, String) -> Unit
) {
    val paras = (start..endInclusive).mapNotNull { (doc.content[it] as? OdfContentBlock.Paragraph)?.paragraph }
    val plainText = paras.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
    var tfv by remember { mutableStateOf(TextFieldValue(plainText)) }
    if (tfv.text != plainText) {
        val caret = tfv.selection.end.coerceIn(0, plainText.length)
        tfv = TextFieldValue(plainText, TextRange(caret))
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val lens = remember(paras) { paras.map { p -> p.spans.sumOf { it.text.length } } }
    val transformation = remember(paras, fontSizeMultiplier, onSurface) {
        VisualTransformation { TransformedText(buildDocAnnotated(it.text, paras, lens, onSurface, fontSizeMultiplier), OffsetMapping.Identity) }
    }
    BasicTextField(
        value = tfv,
        onValueChange = { nv ->
            val textChanged = nv.text != tfv.text
            tfv = nv
            onSelectionChange(start, endInclusive, nv.selection.min, nv.selection.max)
            if (textChanged) onTextChange(start, endInclusive, nv.text)
        },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = onSurface, lineHeight = (22f * fontSizeMultiplier).sp),
        visualTransformation = transformation,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun buildDocAnnotated(text: String, paras: List<OdfParagraph>, lens: List<Int>, baseColor: Color, mult: Float): AnnotatedString = buildAnnotatedString {
    append(text)
    var base = 0
    for (i in paras.indices) {
        val pStart = base
        val pEnd = (base + lens[i]).coerceAtMost(text.length)
        val para = paras[i]
        val paraEnd = if (i < paras.size - 1) (pEnd + 1).coerceAtMost(text.length) else text.length
        addStyle(
            androidx.compose.ui.text.ParagraphStyle(
                textAlign = para.alignment ?: TextAlign.Unspecified,
                textIndent = if (para.style == ParagraphStyle.LIST_ITEM || para.marginLeft > 0) androidx.compose.ui.text.style.TextIndent(firstLine = 0.sp, restLine = 0.sp) else androidx.compose.ui.text.style.TextIndent.None
            ),
            pStart, paraEnd
        )
        headingSizeSp(para.style)?.let { addStyle(SpanStyle(fontSize = (it * mult).sp, fontWeight = FontWeight.Bold), pStart, pEnd) }
        var off = pStart
        for (span in para.spans) {
            val segEnd = (off + span.text.length).coerceAtMost(pEnd)
            if (segEnd > off) {
                val decorations = mutableListOf<TextDecoration>()
                if (span.underline) decorations.add(TextDecoration.Underline)
                if (span.strikethrough) decorations.add(TextDecoration.LineThrough)
                addStyle(
                    SpanStyle(
                        fontWeight = if (span.bold) FontWeight.Bold else null,
                        fontStyle = if (span.italic) FontStyle.Italic else null,
                        fontSize = span.fontSize?.let { (it * mult).sp } ?: TextUnit.Unspecified,
                        textDecoration = if (decorations.isNotEmpty()) TextDecoration.combine(decorations) else null,
                        color = span.color?.let { Color(it.toInt()) } ?: Color.Unspecified,
                        background = span.backgroundColor?.let { Color(it.toInt()) } ?: Color.Unspecified
                    ), off, segEnd
                )
            }
            off = segEnd
        }
        base = pEnd + 1
    }
}

// --- Paragraph rendering (read-only: used for headers, footers, footnotes, table cells, slides) ---

@Composable
private fun paragraphBaseStyle(style: ParagraphStyle): TextStyle = when (style) {
    ParagraphStyle.HEADING1 -> MaterialTheme.typography.headlineLarge
    ParagraphStyle.HEADING2 -> MaterialTheme.typography.headlineMedium
    ParagraphStyle.HEADING3 -> MaterialTheme.typography.headlineSmall
    ParagraphStyle.HEADING4 -> MaterialTheme.typography.titleLarge
    ParagraphStyle.BODY -> MaterialTheme.typography.bodyLarge
    ParagraphStyle.LIST_ITEM -> MaterialTheme.typography.bodyLarge
    ParagraphStyle.TABLE_HEADER -> MaterialTheme.typography.titleSmall
}

// --- Paragraph rendering ---

@Composable
fun ParagraphView(paragraph: OdfParagraph, searchQuery: String = "", fontSizeMultiplier: Float = 1f, nightTextColor: Color = Color.Unspecified) {
    val baseStyle = paragraphBaseStyle(paragraph.style)
    val prefix = when {
        paragraph.style == ParagraphStyle.LIST_ITEM && paragraph.listType == ListType.NUMBERED ->
            "  " + "  ".repeat(maxOf(0, paragraph.listLevel - 1)) + "${paragraph.listItemIndex}.  "
        paragraph.style == ParagraphStyle.LIST_ITEM ->
            "  " + "  ".repeat(maxOf(0, paragraph.listLevel - 1)) + "\u2022  "
        else -> ""
    }
    val hasLinks = paragraph.spans.any { it.href != null }
    val hasAnnotations = paragraph.spans.any { it.annotation != null }
    val context = LocalContext.current
    val highlightColor = Color(0xFFFFEB3B)

    val annotatedString = buildAnnotatedString {
        if (prefix.isNotEmpty()) append(prefix)
        for (span in paragraph.spans) {
            if (span.annotation != null) {
                val start = length
                withStyle(SpanStyle(background = Color(0xFFFFF9C4))) { append(span.text) }
                addStringAnnotation("ANNOTATION", "${span.annotation.author ?: ""}\n${span.annotation.paragraphs.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }}", start, length)
                continue
            }
            val decorations = mutableListOf<TextDecoration>()
            if (span.underline) decorations.add(TextDecoration.Underline)
            if (span.strikethrough) decorations.add(TextDecoration.LineThrough)
            val rawFontSize = span.fontSize?.sp ?: baseStyle.fontSize
            val baseFontSize = if (rawFontSize != TextUnit.Unspecified) rawFontSize * fontSizeMultiplier else rawFontSize
            val effectiveFontSize = if ((span.superscript || span.subscript) && baseFontSize != TextUnit.Unspecified) baseFontSize * 0.7f else baseFontSize
            val spanTextColor = when {
                span.color != null -> Color(span.color.toInt())
                nightTextColor != Color.Unspecified -> nightTextColor
                else -> Color.Unspecified
            }
            val spanStyle = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontSize = effectiveFontSize,
                textDecoration = if (decorations.isNotEmpty()) TextDecoration.combine(decorations) else null,
                color = spanTextColor,
                background = span.backgroundColor?.let { Color(it.toInt()) } ?: Color.Unspecified,
                baselineShift = when { span.superscript -> BaselineShift.Superscript; span.subscript -> BaselineShift.Subscript; else -> null }
            )
            if (searchQuery.isNotEmpty() && span.text.contains(searchQuery, ignoreCase = true)) {
                var remaining = span.text
                while (remaining.isNotEmpty()) {
                    val idx = remaining.indexOf(searchQuery, ignoreCase = true)
                    if (idx < 0) { linkOrPlain(span, spanStyle, remaining); break }
                    if (idx > 0) linkOrPlain(span, spanStyle, remaining.substring(0, idx))
                    withStyle(spanStyle.copy(background = highlightColor)) { append(remaining.substring(idx, idx + searchQuery.length)) }
                    remaining = remaining.substring(idx + searchQuery.length)
                }
            } else linkOrPlain(span, spanStyle, span.text)
        }
    }

    val indentDp = if (paragraph.marginLeft > 0 || paragraph.listLevel > 1) (paragraph.marginLeft + maxOf(0, paragraph.listLevel - 1) * 16f).dp else 0.dp
    val verticalPadding = when (paragraph.style) {
        ParagraphStyle.HEADING1 -> 12.dp; ParagraphStyle.HEADING2 -> 10.dp; ParagraphStyle.HEADING3 -> 8.dp; ParagraphStyle.HEADING4 -> 6.dp
        ParagraphStyle.BODY -> 2.dp; ParagraphStyle.LIST_ITEM -> 1.dp; ParagraphStyle.TABLE_HEADER -> 4.dp
    }
    val topPad = if (paragraph.marginTop > 0) paragraph.marginTop.dp else verticalPadding
    val bottomPad = if (paragraph.marginBottom > 0) paragraph.marginBottom.dp else verticalPadding
    val modifier = Modifier.fillMaxWidth()
        .then(if (indentDp > 0.dp) Modifier.padding(start = indentDp) else Modifier)
        .then(paragraph.backgroundColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
        .padding(top = topPad, bottom = bottomPad)
    val scaledStyle = if (fontSizeMultiplier != 1f && baseStyle.fontSize != TextUnit.Unspecified) {
        baseStyle.copy(fontSize = baseStyle.fontSize * fontSizeMultiplier, textAlign = paragraph.alignment ?: TextAlign.Unspecified, color = if (nightTextColor != Color.Unspecified) nightTextColor else baseStyle.color)
    } else baseStyle.copy(textAlign = paragraph.alignment ?: TextAlign.Unspecified, color = if (nightTextColor != Color.Unspecified) nightTextColor else baseStyle.color)

    if (hasLinks || hasAnnotations) {
        var expandedAnnotation by remember { mutableStateOf<String?>(null) }
        Column {
            @Suppress("DEPRECATION")
            ClickableText(text = annotatedString, style = scaledStyle, modifier = modifier, onClick = { offset ->
                annotatedString.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { a ->
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.item))) } catch (_: Exception) {}; return@ClickableText
                }
                annotatedString.getStringAnnotations("ANNOTATION", offset, offset).firstOrNull()?.let { a ->
                    expandedAnnotation = if (expandedAnnotation == a.item) null else a.item
                }
            })
            AnimatedVisibility(visible = expandedAnnotation != null) { expandedAnnotation?.let { AnnotationPopup(it) } }
        }
    } else Text(text = annotatedString, style = scaledStyle, modifier = modifier)
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.linkOrPlain(span: OdfSpan, style: SpanStyle, text: String) {
    if (span.href != null) {
        val s = length; withStyle(style) { append(text) }; addStringAnnotation("URL", span.href, s, length)
    } else withStyle(style) { append(text) }
}

@Composable
private fun AnnotationPopup(content: String) {
    val parts = content.split("\n", limit = 2)
    val author = parts[0].ifEmpty { null }
    val body = if (parts.size > 1) parts[1] else ""
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (author != null) Text(author, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun FullScreenImage(image: OdfImage, onDismiss: () -> Unit) {
    val bitmap = remember(image.path, image.imageData.size) { BitmapFactory.decodeByteArray(image.imageData, 0, image.imageData.size) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().padding(16.dp), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun TableView(
    table: OdfTable,
    blockIndex: Int,
    searchQuery: String = "",
    fontSizeMultiplier: Float = 1f,
    onCellTextChange: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> },
    onCellFocus: (Int) -> Unit = {}
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    fun colWidthDp(start: Int, span: Int): androidx.compose.ui.unit.Dp {
        var w = 0f
        for (k in start until start + span) {
            val px = table.columns.getOrNull(k)?.width
            w += if (px != null && px > 0f) px * (160f / 96f) else 110f
        }
        return w.dp
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).horizontalScroll(rememberScrollState())) {
        for ((r, row) in table.rows.withIndex()) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                for ((c, cell) in row.cells.withIndex()) {
                    if (cell.isCovered) continue
                    Box(
                        Modifier.width(colWidthDp(c, cell.colSpan))
                            .fillMaxHeight()
                            .border(0.7.dp, MaterialTheme.colorScheme.outline)
                            .then(cell.backgroundColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
                            .padding(8.dp)
                    ) {
                        EditableCell(cell, onSurface, fontSizeMultiplier, onFocus = { onCellFocus(blockIndex, r, c) }) { txt -> onCellTextChange(blockIndex, r, c, txt) }
                    }
                }
            }
        }
        if (table.rows.isEmpty()) Text("(empty table)", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun EditableCell(cell: OdfTableCell, onSurface: Color, mult: Float, onFocus: () -> Unit, onChange: (String) -> Unit) {
    val plain = cell.paragraphs.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
    var tfv by remember { mutableStateOf(TextFieldValue(plain)) }
    if (tfv.text != plain) tfv = TextFieldValue(plain, TextRange(tfv.selection.end.coerceIn(0, plain.length)))
    BasicTextField(
        value = tfv,
        onValueChange = { nv -> val changed = nv.text != tfv.text; tfv = nv; if (changed) onChange(nv.text) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = onSurface, fontSize = (14f * mult).sp),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() }
    )
}

@Composable
fun OdfImageView(image: OdfImage, modifier: Modifier = Modifier) {
    val bitmap = remember(image.path, image.imageData.size) {
        if (image.imageData.isNotEmpty()) try { BitmapFactory.decodeByteArray(image.imageData, 0, image.imageData.size) } catch (_: Exception) { null } else null
    }
    if (bitmap != null) {
        val aspect = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 1.5f
        Image(
            bitmap = bitmap.asImageBitmap(), contentDescription = null,
            modifier = modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.3f, 4f)).padding(vertical = 4.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(modifier.fillMaxWidth().height(120.dp).padding(vertical = 4.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("[Image]", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val chartPalette = listOf(
    Color(0xFF1F6FC0), Color(0xFFE8551E), Color(0xFFF2B600),
    Color(0xFF3FA34D), Color(0xFF8E44AD), Color(0xFF16A2B8), Color(0xFFD81B60)
)

private fun formatAxis(v: Float): String = if (v == v.toLong().toFloat()) v.toLong().toString() else "%.1f".format(v)

@Composable
private fun OdfChartView(chart: OdfChart) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        chart.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = onSurface, modifier = Modifier.padding(bottom = 4.dp)) }
        // Legend
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            chart.series.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(chartPalette[i % chartPalette.size]))
                    Spacer(Modifier.width(4.dp))
                    Text(s.name, style = MaterialTheme.typography.labelMedium, color = onSurface)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val labelArgb = onSurface.toArgb()
        Canvas(Modifier.fillMaxWidth().height(240.dp)) {
            val maxV = (chart.series.flatMap { it.values }.maxOrNull() ?: 1f).coerceAtLeast(1f)
            val leftPad = 72f; val bottomPad = 56f; val topPad = 12f; val rightPad = 12f
            val plotW = size.width - leftPad - rightPad
            val plotH = size.height - bottomPad - topPad
            val axisPaint = android.graphics.Paint().apply { color = labelArgb; textSize = 26f; isAntiAlias = true }
            val centerPaint = android.graphics.Paint().apply { color = labelArgb; textSize = 26f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER }
            val steps = 5
            for (s in 0..steps) {
                val v = maxV * s / steps
                val y = topPad + plotH - plotH * s / steps
                drawLine(gridColor, Offset(leftPad, y), Offset(leftPad + plotW, y), 1f)
                drawContext.canvas.nativeCanvas.drawText(formatAxis(v), 6f, y + 9f, axisPaint)
            }
            drawLine(onSurface, Offset(leftPad, topPad), Offset(leftPad, topPad + plotH), 2f)
            drawLine(onSurface, Offset(leftPad, topPad + plotH), Offset(leftPad + plotW, topPad + plotH), 2f)
            val catCount = chart.categories.size.coerceAtLeast(1)
            when (chart.type) {
                ChartType.LINE -> {
                    val stepX = if (catCount > 1) plotW / (catCount - 1) else plotW
                    chart.series.forEachIndexed { si, ser ->
                        val col = chartPalette[si % chartPalette.size]
                        var prev: Offset? = null
                        for (ci in 0 until catCount) {
                            val v = ser.values.getOrNull(ci) ?: 0f
                            val p = Offset(leftPad + stepX * ci, topPad + plotH - plotH * (v / maxV))
                            prev?.let { drawLine(col, it, p, 4f) }
                            drawCircle(col, 5f, p)
                            prev = p
                        }
                    }
                    for (ci in 0 until catCount) {
                        val x = leftPad + (if (catCount > 1) plotW / (catCount - 1) else plotW) * ci
                        drawContext.canvas.nativeCanvas.drawText(chart.categories[ci], x, topPad + plotH + 34f, centerPaint)
                    }
                }
                ChartType.PIE -> {
                    val values = chart.series.firstOrNull()?.values ?: emptyList()
                    val total = values.sum().coerceAtLeast(0.0001f)
                    val d = minOf(plotW, plotH)
                    val topLeft = Offset(leftPad + (plotW - d) / 2, topPad + (plotH - d) / 2)
                    var startAngle = -90f
                    values.forEachIndexed { i, v ->
                        val sweep = 360f * (v / total)
                        drawArc(chartPalette[i % chartPalette.size], startAngle, sweep, true, topLeft, Size(d, d))
                        startAngle += sweep
                    }
                }
                else -> { // BAR / AREA -> grouped bars
                    val serCount = chart.series.size.coerceAtLeast(1)
                    val groupW = plotW / catCount
                    val pad = groupW * 0.15f
                    val barW = (groupW - 2 * pad) / serCount
                    for (ci in 0 until catCount) {
                        val gx = leftPad + groupW * ci + pad
                        for (si in 0 until serCount) {
                            val v = chart.series[si].values.getOrNull(ci) ?: 0f
                            val h = plotH * (v / maxV)
                            drawRect(chartPalette[si % chartPalette.size], Offset(gx + barW * si, topPad + plotH - h), Size(barW * 0.92f, h))
                        }
                        drawContext.canvas.nativeCanvas.drawText(chart.categories.getOrElse(ci) { "" }, leftPad + groupW * ci + groupW / 2, topPad + plotH + 34f, centerPaint)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageBreakView() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

// --- Spreadsheet ---

@Composable
fun SpreadsheetView(
    doc: OdfDocument.Spreadsheet, searchQuery: String = "", fontSizeMultiplier: Float = 1f,
    isEditMode: Boolean = false,
    onCellTextChange: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> },
    onAddRow: (Int, Int) -> Unit = { _, _ -> }, onAddColumn: (Int) -> Unit = {},
    onDeleteRow: (Int, Int) -> Unit = { _, _ -> }, onDeleteColumn: (Int, Int) -> Unit = { _, _ -> },
    onRenameSheet: (Int, String) -> Unit = { _, _ -> }, onAddSheet: () -> Unit = {}, onDeleteSheet: (Int) -> Unit = {},
    onCellBold: (Int, Int, Int) -> Unit = { _, _, _ -> }, onCellItalic: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onCellColor: (Int, Int, Int, Long?) -> Unit = { _, _, _, _ -> }, onCellBgColor: (Int, Int, Int, Long?) -> Unit = { _, _, _, _ -> },
    onCellAlignment: (Int, Int, Int, TextAlign?) -> Unit = { _, _, _, _ -> },
    onMergeCells: (Int, Int, Int, Int, Int) -> Unit = { _, _, _, _, _ -> },
    onUnmergeCells: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onSort: (Int, Int, Boolean) -> Unit = { _, _, _ -> }
) {
    if (doc.sheets.isEmpty()) { Text("Empty spreadsheet", modifier = Modifier.padding(16.dp)); return }

    var selectedSheet by remember { mutableIntStateOf(0) }
    var editingCell by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var editText by remember { mutableStateOf("") }
    var showRenameSheet by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showColorPicker by remember { mutableStateOf(false) }
    var showBgColorPicker by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (doc.sheets.size > 1 || isEditMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryScrollableTabRow(selectedTabIndex = selectedSheet, modifier = Modifier.weight(1f)) {
                    doc.sheets.forEachIndexed { index, sheet ->
                        Tab(selected = selectedSheet == index, onClick = { selectedSheet = index; editingCell = null },
                            text = { if (isEditMode) Text(sheet.name, Modifier.clickable { renameText = sheet.name; showRenameSheet = true }) else Text(sheet.name) })
                    }
                }
                if (isEditMode) TextButton(onClick = { onAddSheet() }) { Text("+") }
            }
        } else {
            Text(doc.sheets[0].name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 8.dp))
        }

        if (isEditMode && editingCell != null) {
            val (_, ri, ci) = editingCell!!
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${columnLabel(ci)}${ri + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                TextField(value = editText, onValueChange = { editText = it }, singleLine = true, modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant))
                TextButton(onClick = { val (si, r, c) = editingCell!!; onCellTextChange(si, r, c, editText); editingCell = null }) { Text("Done") }
            }
            // Cell formatting toolbar
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val (si, r, c) = editingCell!!
                TextButton(onClick = { onCellBold(si, r, c) }) { Text("B", fontWeight = FontWeight.Bold) }
                TextButton(onClick = { onCellItalic(si, r, c) }) { Text("I", fontStyle = FontStyle.Italic) }
                TextButton(onClick = { showColorPicker = true }) { Text("A", color = Color.Red) }
                TextButton(onClick = { showBgColorPicker = true }) { Text("⬛") }
                TextButton(onClick = { onCellAlignment(si, r, c, TextAlign.Start) }) { Text("←") }
                TextButton(onClick = { onCellAlignment(si, r, c, TextAlign.Center) }) { Text("↔") }
                TextButton(onClick = { onCellAlignment(si, r, c, TextAlign.End) }) { Text("→") }
                TextButton(onClick = { onUnmergeCells(si, r, c) }) { Text("⊟") }
            }
        }

        if (isEditMode) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { val ri = editingCell?.second ?: (doc.sheets[selectedSheet].rows.size - 1); onAddRow(selectedSheet, ri) }) { Text("+ Row") }
                TextButton(onClick = { onAddColumn(selectedSheet) }) { Text("+ Col") }
                if (editingCell != null) {
                    TextButton(onClick = { onDeleteRow(selectedSheet, editingCell!!.second); editingCell = null }) { Text("- Row") }
                    TextButton(onClick = { onDeleteColumn(selectedSheet, editingCell!!.third); editingCell = null }) { Text("- Col") }
                }
                TextButton(onClick = { showSortDialog = true }) { Text("Sort") }
                Spacer(Modifier.weight(1f))
                if (doc.sheets.size > 1) TextButton(onClick = { onDeleteSheet(selectedSheet); if (selectedSheet >= doc.sheets.size - 1) selectedSheet = maxOf(0, doc.sheets.size - 2) }) { Text("- Sheet", color = MaterialTheme.colorScheme.error) }
            }
        }

        val sheet = doc.sheets[selectedSheet]
        val maxCols = sheet.rows.maxOfOrNull { it.cells.count { c -> !c.isCovered } } ?: 0

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                    Column {
                        Row {
                            Box(Modifier.defaultMinSize(minWidth = 40.dp).background(MaterialTheme.colorScheme.surfaceVariant).border(0.5.dp, MaterialTheme.colorScheme.outline).padding(4.dp), contentAlignment = Alignment.Center) { Text("") }
                            for (col in 0 until maxCols) Box(Modifier.defaultMinSize(minWidth = 80.dp).background(MaterialTheme.colorScheme.surfaceVariant).border(0.5.dp, MaterialTheme.colorScheme.outline).padding(4.dp), contentAlignment = Alignment.Center) {
                                Text(columnLabel(col), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                        for ((rowIdx, row) in sheet.rows.withIndex()) {
                            Row {
                                Box(Modifier.defaultMinSize(minWidth = 40.dp).background(MaterialTheme.colorScheme.surfaceVariant).border(0.5.dp, MaterialTheme.colorScheme.outline).padding(4.dp), contentAlignment = Alignment.Center) {
                                    Text("${rowIdx + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                for ((cellIdx, cell) in row.cells.withIndex()) {
                                    if (cell.isCovered) continue
                                    val isEditing = editingCell?.let { it.first == selectedSheet && it.second == rowIdx && it.third == cellIdx } == true
                                    val isMatch = searchQuery.isNotEmpty() && cell.text.contains(searchQuery, ignoreCase = true)
                                    Box(
                                        Modifier.defaultMinSize(minWidth = (80 * cell.spannedColumns).dp)
                                            .border(if (isEditing) 2.dp else 0.5.dp, if (isEditing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                                            .then(if (isMatch) Modifier.background(Color(0xFFFFEB3B).copy(alpha = 0.3f)) else cell.backgroundColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
                                            .then(if (isEditMode) Modifier.clickable { editingCell = Triple(selectedSheet, rowIdx, cellIdx); editText = cell.text } else Modifier)
                                            .padding(8.dp, 4.dp)
                                    ) {
                                        Text(cell.text,
                                            style = MaterialTheme.typography.bodyMedium.let { if (fontSizeMultiplier != 1f && it.fontSize != TextUnit.Unspecified) it.copy(fontSize = it.fontSize * fontSizeMultiplier) else it },
                                            fontWeight = if (cell.bold) FontWeight.Bold else null,
                                            fontStyle = if (cell.italic) FontStyle.Italic else null,
                                            color = cell.textColor?.let { Color(it.toInt()) } ?: Color.Unspecified,
                                            textAlign = cell.alignment, maxLines = 3)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRenameSheet) {
        AlertDialog(onDismissRequest = { showRenameSheet = false }, title = { Text("Rename Sheet") },
            text = { TextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRenameSheet(selectedSheet, renameText); showRenameSheet = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showRenameSheet = false }) { Text("Cancel") } })
    }
    if (showColorPicker && editingCell != null) {
        val (si, r, c) = editingCell!!
        ColorPickerDialog("Text Color", onColorSelected = { onCellColor(si, r, c, it) }, onDismiss = { showColorPicker = false })
    }
    if (showBgColorPicker && editingCell != null) {
        val (si, r, c) = editingCell!!
        ColorPickerDialog("Background Color", onColorSelected = { onCellBgColor(si, r, c, it) }, onDismiss = { showBgColorPicker = false })
    }
    if (showSortDialog) {
        val maxC = doc.sheets[selectedSheet].rows.maxOfOrNull { it.cells.size } ?: 1
        SortDialog(maxC, onSort = { col, asc -> onSort(selectedSheet, col, asc) }, onDismiss = { showSortDialog = false })
    }
}

private fun columnLabel(index: Int): String {
    val sb = StringBuilder(); var n = index
    do { sb.insert(0, ('A' + n % 26)); n = n / 26 - 1 } while (n >= 0)
    return sb.toString()
}

// --- Presentation ---

@Composable
fun PresentationView(
    doc: OdfDocument.Presentation,
    isEditMode: Boolean = false,
    onAddSlide: (Int) -> Unit = {},
    onDeleteSlide: (Int) -> Unit = {},
    onDuplicateSlide: (Int) -> Unit = {},
    onMoveSlideUp: (Int) -> Unit = {},
    onMoveSlideDown: (Int) -> Unit = {}
) {
    if (doc.slides.isEmpty()) { Text("Empty presentation", modifier = Modifier.padding(16.dp)); return }

    var currentSlide by remember { mutableIntStateOf(0) }
    var showGoToSlide by remember { mutableStateOf(false) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Main slide with swipe gesture
        Box(
            modifier = Modifier.weight(1f).pointerInput(doc.slides.size) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragAccumulator < -100 && currentSlide < doc.slides.size - 1) currentSlide++
                        else if (dragAccumulator > 100 && currentSlide > 0) currentSlide--
                        dragAccumulator = 0f
                    },
                    onHorizontalDrag = { _, dragAmount -> dragAccumulator += dragAmount }
                )
            }
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                item { SlideCard(doc.slides[currentSlide]) }
            }
        }

        // Slide editing controls
        if (isEditMode) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onAddSlide(currentSlide) }) { Text("+ Slide") }
                TextButton(onClick = { onDuplicateSlide(currentSlide) }) { Text("Dup") }
                TextButton(onClick = { onMoveSlideUp(currentSlide); if (currentSlide > 0) currentSlide-- }) { Text("↑") }
                TextButton(onClick = { onMoveSlideDown(currentSlide); if (currentSlide < doc.slides.size - 1) currentSlide++ }) { Text("↓") }
                Spacer(Modifier.weight(1f))
                if (doc.slides.size > 1) TextButton(onClick = { onDeleteSlide(currentSlide); currentSlide = minOf(currentSlide, doc.slides.size - 2).coerceAtLeast(0) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Slide thumbnail strip
        if (doc.slides.size > 1) {
            HorizontalDivider()
            LazyRow(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(doc.slides.size) { index ->
                    SlideThumbnail(doc.slides[index], index, index == currentSlide) { currentSlide = index }
                }
            }
        }

        // Navigation bar
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { if (currentSlide > 0) currentSlide-- }, enabled = currentSlide > 0) { Text("◀ Prev") }
                TextButton(onClick = { showGoToSlide = true }) {
                    Text("Slide ${currentSlide + 1} of ${doc.slides.size}", style = MaterialTheme.typography.titleSmall)
                }
                TextButton(onClick = { if (currentSlide < doc.slides.size - 1) currentSlide++ }, enabled = currentSlide < doc.slides.size - 1) { Text("Next ▶") }
            }
        }
    }

    if (showGoToSlide) GoToSlideDialog(doc.slides.size, onGo = { currentSlide = it }, onDismiss = { showGoToSlide = false })
}

@Composable
private fun SlideThumbnail(slide: OdfSlide, index: Int, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(120.dp).aspectRatio(16f / 9f).clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Box(Modifier.fillMaxSize().then(slide.backgroundColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier).padding(4.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val firstText = slide.elements.firstNotNullOfOrNull { el ->
                    when (el) { is OdfSlideElement.Frame -> el.frame.paragraphs.firstOrNull()?.spans?.joinToString("") { it.text }?.take(30); is OdfSlideElement.Shape -> el.shape.text.firstOrNull()?.spans?.joinToString("") { it.text }?.take(30) }
                }
                Text("${index + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                if (firstText != null) Text(firstText, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun DrawingView(doc: OdfDocument.Drawing) {
    if (doc.pages.isEmpty()) { Text("Empty drawing", modifier = Modifier.padding(16.dp)); return }
    var currentPage by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f).padding(8.dp)) { item { SlideCard(doc.pages[currentPage]) } }
        if (doc.pages.size > 1) Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { if (currentPage > 0) currentPage-- }, enabled = currentPage > 0) { Text("◀ Prev") }
                Text("Page ${currentPage + 1} of ${doc.pages.size}", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { if (currentPage < doc.pages.size - 1) currentPage++ }, enabled = currentPage < doc.pages.size - 1) { Text("Next ▶") }
            }
        }
    }
}

@Composable
private fun SlideCard(slide: OdfSlide) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(slide.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Card(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(horizontal = 8.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(Modifier.fillMaxWidth().then(slide.backgroundColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier).padding(16.dp)) {
                for (element in slide.elements) when (element) {
                    is OdfSlideElement.Frame -> FrameView(element.frame)
                    is OdfSlideElement.Shape -> ShapeView(element.shape)
                }
            }
        }
        if (slide.notes.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(start = 8.dp)) { Text(if (expanded) "Hide Notes" else "Speaker Notes") }
            if (expanded) Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { for (note in slide.notes) ParagraphView(note) }
        }
    }
}

@Composable
private fun FrameView(frame: OdfFrame) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        frame.image?.let { OdfImageView(it) }
        for (para in frame.paragraphs) ParagraphView(para)
    }
}

@Composable
private fun ShapeView(shape: OdfShape) {
    val fillColor = shape.fillColor?.let { Color(it.toInt()) } ?: Color.Transparent
    val strokeColor = shape.strokeColor?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.outline
    val strokeW = shape.strokeWidth ?: 1f
    val shapeW = if (shape.width > 0) shape.width.dp else 100.dp
    val shapeH = if (shape.height > 0) shape.height.dp else 60.dp
    Box(modifier = Modifier.size(shapeW, shapeH).padding(2.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            when (shape) {
                is OdfShape.Rect -> { drawRect(fillColor); drawRect(strokeColor, style = Stroke(strokeW)) }
                is OdfShape.Ellipse -> { drawOval(fillColor); drawOval(strokeColor, style = Stroke(strokeW)) }
                is OdfShape.Line -> drawLine(strokeColor, Offset.Zero, Offset(size.width, size.height), strokeW)
                is OdfShape.CustomShape -> { drawRect(fillColor); drawRect(strokeColor, style = Stroke(strokeW)) }
            }
        }
        if (shape.text.isNotEmpty()) Column(Modifier.padding(4.dp).align(Alignment.Center)) { for (para in shape.text) ParagraphView(para) }
    }
}
