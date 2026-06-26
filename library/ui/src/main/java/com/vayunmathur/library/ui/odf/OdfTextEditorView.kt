package com.vayunmathur.library.ui.odf

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Shared, framework-light text-run editor extracted from the Office app so the markdown editor and
 * Office can use a single implementation. Renders a contiguous run of paragraphs as one
 * [BasicTextField] with a visual transformation that injects list/heading prefixes and applies
 * inline span styling, and reports edits back through callbacks. Pure Compose + ODF model only.
 */

internal fun headingSizeSp(style: ParagraphStyle): Float? = when (style) {
    ParagraphStyle.HEADING1 -> 30f
    ParagraphStyle.HEADING2 -> 26f
    ParagraphStyle.HEADING3 -> 22f
    ParagraphStyle.HEADING4 -> 20f
    else -> null
}

/** Visible list prefix (indent + bullet/number) for a paragraph. (A1/F42) */
fun listPrefixFor(para: OdfParagraph): String {
    // Headings: show automatic outline numbering if present, else no generic prefix. (bugfix + Round 3)
    if (para.style == ParagraphStyle.HEADING1 || para.style == ParagraphStyle.HEADING2 ||
        para.style == ParagraphStyle.HEADING3 || para.style == ParagraphStyle.HEADING4) {
        return para.outlineNumber?.let { "$it " } ?: ""
    }
    if (para.listLevel <= 0 && para.style != ParagraphStyle.LIST_ITEM) return ""
    val level = maxOf(para.listLevel, 1)
    val indent = "    ".repeat(level - 1)
    return if (para.listType == ListType.NUMBERED) {
        indent + para.listNumberPrefix + formatListNumber(para.listItemIndex, para.listNumberFormat) + para.listNumberSuffix + "  "
    } else {
        indent + sanitizeBulletChar(para.listBulletChar) + "  "
    }
}

/**
 * Maps a list bullet glyph to something the app font can actually render. ODF list styles often
 * specify bullets from a symbol font's Private Use Area (StarSymbol/OpenSymbol/Wingdings), which
 * show as a "notdef" tofu box; fall back to a standard bullet for those. (bugfix)
 */
private fun sanitizeBulletChar(ch: String): String {
    if (ch.isBlank()) return "\u2022"
    val c = ch[0]
    return when {
        c.code in 0xE000..0xF8FF -> "\u2022"   // Private Use Area (symbol fonts)
        c == '\uFFFD' -> "\u2022"              // replacement char
        c.isISOControl() -> "\u2022"
        else -> ch
    }
}

/** OffsetMapping for list prefixes injected at the start of each paragraph in a run. (A1) */
private class PrefixOffsetMapping(
    private val origStarts: IntArray,
    private val transStarts: IntArray,
    private val prefixLens: IntArray,
    private val lens: IntArray,
    private val origLen: Int,
    private val transLen: Int
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        val o = offset.coerceIn(0, origLen)
        var p = 0
        for (i in origStarts.indices) { if (origStarts[i] <= o) p = i else break }
        val inPara = (o - origStarts[p]).coerceIn(0, lens[p])
        return (transStarts[p] + prefixLens[p] + inPara).coerceIn(0, transLen)
    }
    override fun transformedToOriginal(offset: Int): Int {
        val t = offset.coerceIn(0, transLen)
        var p = 0
        for (i in transStarts.indices) { if (transStarts[i] <= t) p = i else break }
        val afterPrefix = t - transStarts[p] - prefixLens[p]
        val inPara = afterPrefix.coerceIn(0, lens[p])
        return (origStarts[p] + inPara).coerceIn(0, origLen)
    }
}

@Composable
fun ContinuousParagraphEditor(
    doc: OdfDocument.TextDocument,
    start: Int,
    endInclusive: Int,
    fontSizeMultiplier: Float,
    onSelectionChange: (Int, Int, Int, Int) -> Unit,
    onTextChange: (Int, Int, String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val paras = (start..endInclusive).mapNotNull { (doc.content[it] as? OdfContentBlock.Paragraph)?.paragraph }
    val plainText = paras.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
    var tfv by remember { mutableStateOf(TextFieldValue(plainText)) }
    if (tfv.text != plainText) {
        val caret = tfv.selection.end.coerceIn(0, plainText.length)
        tfv = TextFieldValue(plainText, TextRange(caret))
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val prefixColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lens = remember(paras) { paras.map { p -> p.spans.sumOf { it.text.length } } }
    val prefixes = remember(paras) { paras.map { listPrefixFor(it) } }
    val transformation = remember(paras, fontSizeMultiplier, onSurface, prefixColor) {
        VisualTransformation { buildDocTransformed(it.text, paras, lens, prefixes, onSurface, prefixColor, fontSizeMultiplier) }
    }
    // Let Compose own the caret. The built-in cursor is positioned with the SAME internal
    // (visually-transformed) TextLayoutResult and OffsetMapping that lay out the aligned glyphs,
    // so getCursorRect reflects each paragraph's TextAlign (set via ParagraphStyle below) and the
    // caret stays aligned with the displayed text for left/center/right/justified paragraphs.
    // A manual caret has to reconstruct that layout/mapping in the outer draw scope and drifts out
    // of alignment, so it is intentionally not used here. (caret-align fix)
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
        modifier = modifier
    )
}

private fun buildDocTransformed(
    text: String, paras: List<OdfParagraph>, lens: List<Int>, prefixes: List<String>,
    baseColor: Color, prefixColor: Color, mult: Float
): TransformedText {
    val n = paras.size
    val origStarts = IntArray(n)
    val transStarts = IntArray(n)
    val prefixLens = IntArray(n) { prefixes[it].length }

    // Paragraph-break model (fixes the extra blank line between paragraphs + the start-of-paragraph
    // backspace-merge). Compose renders each ParagraphStyle range as its own paragraph, so a '\n'
    // that lands on a ParagraphStyle boundary becomes an extra empty "gap" paragraph -> a blank
    // line between every paragraph, and that blank line is caret-addressable (its offset maps back
    // to the END of the previous paragraph, so backspace there deletes a char instead of merging).
    //
    // Instead we keep a '\n' separator ONLY between consecutive paragraphs that share the same
    // paragraph-level layout (alignment, line spacing) and use no first-line indent: those sit
    // inside ONE shared ParagraphStyle, so the '\n' is an ordinary single line break (no blank
    // line, fully addressable). At a real layout change we drop the '\n' and let the paragraph
    // break come for free from the two adjacent ParagraphStyle ranges. Empty paragraphs keep a
    // separator with their previous (or, for a leading empty paragraph, next) neighbour so they
    // still occupy a caret-addressable line. The original text always joins paragraphs with '\n'
    // (see plainText), so origStarts still advances by lens[i] + 1 for every paragraph.
    fun sameLayout(a: OdfParagraph, b: OdfParagraph): Boolean =
        a.alignment == b.alignment && a.lineHeightPercent == b.lineHeightPercent &&
            a.textIndent == 0f && b.textIndent == 0f
    val sep = IntArray(n) // sep[i] = 1 if a '\n' is emitted between paragraph i and i+1
    for (i in 0 until n - 1) {
        sep[i] = if (sameLayout(paras[i], paras[i + 1]) || lens[i + 1] == 0) 1 else 0
    }
    if (n > 0 && lens[0] == 0 && n > 1) sep[0] = 1 // keep a leading empty paragraph on its own line

    var oAcc = 0; var tAcc = 0
    for (i in 0 until n) {
        origStarts[i] = oAcc
        transStarts[i] = tAcc
        oAcc += lens[i] + 1
        tAcc += prefixLens[i] + lens[i] + sep[i]
    }
    val origLen = text.length
    val transLen = if (n == 0) 0 else transStarts[n - 1] + prefixLens[n - 1] + lens[n - 1]

    val annotated = buildAnnotatedString {
        var groupStart = 0   // annotated offset where the current ParagraphStyle group began
        var groupFirst = 0   // index of the first paragraph in the current group
        for (i in paras.indices) {
            if (i == 0 || sep[i - 1] == 0) { groupStart = length; groupFirst = i }
            val para = paras[i]
            // prefix (styled muted, never bold/italic)
            if (prefixes[i].isNotEmpty()) {
                withStyle(SpanStyle(color = prefixColor)) { append(prefixes[i]) }
            }
            // paragraph text from original
            val pStartOrig = origStarts[i]
            val pEndOrig = (pStartOrig + lens[i]).coerceAtMost(text.length)
            val paraText = if (pEndOrig > pStartOrig) text.substring(pStartOrig, pEndOrig) else ""
            val textStart = length
            append(paraText)
            val textEnd = length
            headingSizeSp(para.style)?.let { addStyle(SpanStyle(fontSize = (it * mult).sp, fontWeight = FontWeight.Bold), textStart, textEnd) }
            var off = textStart
            for (span in para.spans) {
                val segEnd = (off + span.text.length).coerceAtMost(textEnd)
                if (segEnd > off) {
                    val decorations = mutableListOf<TextDecoration>()
                    if (span.underline) decorations.add(TextDecoration.Underline)
                    if (span.strikethrough) decorations.add(TextDecoration.LineThrough)
                    if (span.changeKind == "insertion") decorations.add(TextDecoration.Underline)
                    if (span.changeKind == "deletion") decorations.add(TextDecoration.LineThrough)
                    val changeColor = when (span.changeKind) { "insertion" -> Color(0xFF1B7F3B); "deletion" -> Color(0xFFC62828); else -> null }
                    addStyle(
                        SpanStyle(
                            fontWeight = if (span.bold) FontWeight.Bold else null,
                            fontStyle = if (span.italic) FontStyle.Italic else null,
                            fontSize = span.fontSize?.let { (it * mult).sp } ?: TextUnit.Unspecified,
                            textDecoration = if (decorations.isNotEmpty()) TextDecoration.combine(decorations) else null,
                            color = changeColor ?: span.color?.let { Color(it.toInt()) } ?: Color.Unspecified,
                            background = span.backgroundColor?.let { Color(it.toInt()) } ?: Color.Unspecified,
                            letterSpacing = span.letterSpacing?.sp ?: TextUnit.Unspecified,
                            baselineShift = when { span.superscript -> BaselineShift.Superscript; span.subscript -> BaselineShift.Subscript; else -> null }
                        ), off, segEnd
                    )
                }
                off = segEnd
            }
            // At the end of a group, emit ONE ParagraphStyle spanning every paragraph in the group
            // (prefixes, text and interior '\n' separators). The group's paragraph-level layout is
            // taken from its first non-empty paragraph so that an absorbed empty paragraph never
            // overrides the alignment/indent of real content. (A5/A6 + paragraph-spacing fix)
            if (i == n - 1 || sep[i] == 0) {
                val styleSrc = (groupFirst..i).firstOrNull { lens[it] > 0 }?.let { paras[it] } ?: paras[groupFirst]
                val firstLineIndent = if (styleSrc.textIndent != 0f) styleSrc.textIndent.sp else 0.sp
                // Justify is stretched at draw time, but BasicTextField computes caret / selection /
                // handle positions from the UNSTRETCHED glyph advances, so they drift left of the
                // displayed justified glyphs. Render the editable field with Start alignment so the
                // caret and selection match the glyphs exactly; the OdfParagraph.alignment model is
                // untouched, so read-only rendering (ParagraphView) and export stay justified.
                // (justified-selection alignment fix)
                val editorAlign = if (styleSrc.alignment == TextAlign.Justify) TextAlign.Start else (styleSrc.alignment ?: TextAlign.Unspecified)
                addStyle(
                    androidx.compose.ui.text.ParagraphStyle(
                        textAlign = editorAlign,
                        lineHeight = styleSrc.lineHeightPercent?.let { (22f * mult * it).sp } ?: TextUnit.Unspecified,
                        textIndent = if (styleSrc.textIndent != 0f) androidx.compose.ui.text.style.TextIndent(firstLine = firstLineIndent, restLine = 0.sp) else androidx.compose.ui.text.style.TextIndent.None
                    ),
                    groupStart, length
                )
            }
            if (sep[i] == 1) append("\n")
        }
    }
    return TransformedText(annotated, PrefixOffsetMapping(origStarts, transStarts, prefixLens, lens.toIntArray(), origLen, transLen))
}
