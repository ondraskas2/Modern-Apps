package com.vayunmathur.office.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.vayunmathur.office.R
import com.vayunmathur.office.odf.*
import com.vayunmathur.library.ui.odf.*
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.library.ui.EditorBaseButtons
import com.vayunmathur.library.ui.EditorFormat
import com.vayunmathur.library.ui.EditorFormatter
import com.vayunmathur.library.ui.LinkContext

/** What the shared bottom bar is currently formatting. (Phase 2) */
sealed interface FormatTarget {
    data object None : FormatTarget
    data class TextRun(val runStart: Int, val runEnd: Int, val selStart: Int, val selEnd: Int) : FormatTarget
    data class Cell(val sheet: Int, val row: Int, val col: Int) : FormatTarget
    data class Element(val slide: Int, val element: Int) : FormatTarget
}

/** Which insert actions are available for the current document type. (Phase 2) */
data class DocCaps(
    val insertImage: Boolean = false,
    val insertShape: Boolean = false,
    val insertChart: Boolean = false,
    val insertTable: Boolean = false
)

enum class ShapeKind { RECT, ELLIPSE, LINE }

/** Callbacks the shared bottom bar may invoke. */
data class BottomBarActions(
    val onTextColor: () -> Unit = {},
    val onCellTextColor: () -> Unit = {},
    val onCellBgColor: () -> Unit = {},
    val onSlideTextColor: () -> Unit = {},
    val onSlideFill: () -> Unit = {},
    val onSlideStroke: () -> Unit = {},
    val onFontSize: () -> Unit = {},
    val onInsertImage: () -> Unit = {},
    val onInsertShape: (ShapeKind) -> Unit = {},
    val onInsertChart: () -> Unit = {},
    val onInsertTable: () -> Unit = {},
    val onDeleteElement: () -> Unit = {},
    val onCellBorder: () -> Unit = {},
    val onCellComment: () -> Unit = {},
    val onCellResize: () -> Unit = {},
    val onSlideNotes: () -> Unit = {},
    val onSlideBackground: () -> Unit = {},
    val onSlideTransition: () -> Unit = {}
)

@Composable
internal fun FmtIcon(res: Int, active: Boolean, enabled: Boolean, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(painterResource(res), contentDescription = desc, tint = if (active && enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current)
    }
}

/**
 * Single shared format + insert bar hosted in the Scaffold bottom bar for all document types.
 * Renders a type-appropriate formatting branch plus a unified Insert menu. (Phase 2)
 */
@Composable
fun OfficeBottomBar(
    document: OdfDocument,
    target: FormatTarget,
    caps: DocCaps,
    viewModel: OfficeViewModel,
    actions: BottomBarActions,
    activeTableBlock: Int = -1,
    activeTableRow: Int = -1,
    activeTableCol: Int = -1
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (document) {
                is OdfDocument.TextDocument -> TextFormatControls(document, target as? FormatTarget.TextRun, viewModel, activeTableBlock, activeTableRow, activeTableCol, actions)
                is OdfDocument.Spreadsheet -> CellFormatControls(target as? FormatTarget.Cell, viewModel, actions)
                is OdfDocument.Presentation -> ElementFormatControls(target as? FormatTarget.Element, viewModel, actions)
                else -> {}
            }
            InsertControl(caps, actions)
        }
    }
}

@Composable
private fun InsertControl(caps: DocCaps, actions: BottomBarActions) {
    if (!(caps.insertImage || caps.insertShape || caps.insertChart || caps.insertTable)) return
    var menu by remember { mutableStateOf(false) }
    var shapeMenu by remember { mutableStateOf(false) }
    Box {
        FmtIcon(com.vayunmathur.library.R.drawable.add_24px, false, true, "Insert") { menu = true }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (caps.insertImage) DropdownMenuItem(text = { Text("Image…") }, onClick = { menu = false; actions.onInsertImage() })
            if (caps.insertShape) DropdownMenuItem(text = { Text("Shape") }, trailingIcon = { Icon(painterResource(R.drawable.arrow_drop_down_24px), null) }, onClick = { shapeMenu = true })
            if (caps.insertChart) DropdownMenuItem(text = { Text("Chart…") }, onClick = { menu = false; actions.onInsertChart() })
            if (caps.insertTable) DropdownMenuItem(text = { Text("Table…") }, onClick = { menu = false; actions.onInsertTable() })
        }
        DropdownMenu(expanded = shapeMenu, onDismissRequest = { shapeMenu = false }) {
            DropdownMenuItem(text = { Text("Rectangle") }, onClick = { shapeMenu = false; menu = false; actions.onInsertShape(ShapeKind.RECT) })
            DropdownMenuItem(text = { Text("Ellipse") }, onClick = { shapeMenu = false; menu = false; actions.onInsertShape(ShapeKind.ELLIPSE) })
            DropdownMenuItem(text = { Text("Line") }, onClick = { shapeMenu = false; menu = false; actions.onInsertShape(ShapeKind.LINE) })
        }
    }
}

// --- Text document branch (ported from QuickFormatBar) ---

@Composable
private fun TextFormatControls(
    doc: OdfDocument.TextDocument,
    target: FormatTarget.TextRun?,
    viewModel: OfficeViewModel,
    activeTableBlock: Int,
    activeTableRow: Int,
    activeTableCol: Int,
    actions: BottomBarActions
) {
    val enabled = target != null
    val runStart = target?.runStart ?: -1
    val runEnd = target?.runEnd ?: -1
    val selStart = target?.selStart ?: 0
    val selEnd = target?.selEnd ?: 0
    val focusedPara = if (enabled) viewModel.runParagraphIndexAt(runStart, runEnd, selStart) else -1
    val para = (doc.content.getOrNull(focusedPara) as? OdfContentBlock.Paragraph)?.paragraph
    var styleMenu by remember { mutableStateOf(false) }
    var alignMenu by remember { mutableStateOf(false) }
    var tableMenu by remember { mutableStateOf(false) }

    val isBullet = para?.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.BULLET
    val isNumbered = para?.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.NUMBERED
    val isCheckbox = para?.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.CHECKBOX

    val textFormatter = TextRunFormatter(viewModel, runStart, runEnd, selStart, selEnd, enabled)

    val styleLabel = when (para?.style) {
        ParagraphStyle.HEADING1 -> "H1"; ParagraphStyle.HEADING2 -> "H2"; ParagraphStyle.HEADING3 -> "H3"; ParagraphStyle.HEADING4 -> "H4"; else -> "Normal"
    }
    val alignIcon = when (para?.alignment) {
        TextAlign.Center -> R.drawable.format_align_center_24px
        TextAlign.End, TextAlign.Right -> R.drawable.format_align_right_24px
        TextAlign.Justify -> R.drawable.format_align_justify_24px
        else -> R.drawable.format_align_left_24px
    }
    fun setStyle(s: ParagraphStyle) { viewModel.mutateRunParagraphs(runStart, runEnd, selStart, selEnd) { it.copy(style = s) } }
    fun setAlign(a: TextAlign) { viewModel.mutateRunParagraphs(runStart, runEnd, selStart, selEnd) { it.copy(alignment = a) } }

    Box {
        TextButton(onClick = { styleMenu = true }, enabled = enabled) {
            Text(styleLabel)
            Icon(painterResource(R.drawable.arrow_drop_down_24px), contentDescription = "Paragraph style")
        }
        DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
            DropdownMenuItem(text = { Text("Normal") }, onClick = { styleMenu = false; setStyle(ParagraphStyle.BODY) })
            DropdownMenuItem(text = { Text("Heading 1") }, onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING1) })
            DropdownMenuItem(text = { Text("Heading 2") }, onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING2) })
            DropdownMenuItem(text = { Text("Heading 3") }, onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING3) })
            DropdownMenuItem(text = { Text("Heading 4") }, onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING4) })
        }
    }
    EditorBaseButtons(textFormatter)
    FmtIcon(R.drawable.format_color_text_24px, false, enabled, "Text color") { actions.onTextColor() }
    Box {
        FmtIcon(alignIcon, false, enabled, "Alignment") { alignMenu = true }
        DropdownMenu(expanded = alignMenu, onDismissRequest = { alignMenu = false }) {
            DropdownMenuItem(text = { Text("Left") }, leadingIcon = { Icon(painterResource(R.drawable.format_align_left_24px), null) }, onClick = { alignMenu = false; setAlign(TextAlign.Start) })
            DropdownMenuItem(text = { Text("Center") }, leadingIcon = { Icon(painterResource(R.drawable.format_align_center_24px), null) }, onClick = { alignMenu = false; setAlign(TextAlign.Center) })
            DropdownMenuItem(text = { Text("Right") }, leadingIcon = { Icon(painterResource(R.drawable.format_align_right_24px), null) }, onClick = { alignMenu = false; setAlign(TextAlign.End) })
            DropdownMenuItem(text = { Text("Justify") }, leadingIcon = { Icon(painterResource(R.drawable.format_align_justify_24px), null) }, onClick = { alignMenu = false; setAlign(TextAlign.Justify) })
        }
    }
    FmtIcon(R.drawable.format_list_bulleted_24px, isBullet, enabled, "Bulleted list") { if (focusedPara >= 0) viewModel.toggleListItem(focusedPara) }
    FmtIcon(R.drawable.format_list_numbered_24px, isNumbered, enabled, "Numbered list") { if (focusedPara >= 0) viewModel.toggleNumberedList(focusedPara) }
    FmtIcon(R.drawable.check_box_24px, isCheckbox, enabled, "Checklist") { if (focusedPara >= 0) viewModel.toggleCheckbox(focusedPara) }
    FmtIcon(R.drawable.format_indent_increase_24px, false, enabled, "Increase indent") {
        if (focusedPara >= 0) {
            if (para?.style == ParagraphStyle.LIST_ITEM) viewModel.changeListLevel(focusedPara, 1)
            else viewModel.indentParagraph(focusedPara)
        }
    }
    FmtIcon(R.drawable.format_indent_decrease_24px, false, enabled, "Decrease indent") {
        if (focusedPara >= 0) {
            if (para?.style == ParagraphStyle.LIST_ITEM) viewModel.changeListLevel(focusedPara, -1)
            else viewModel.outdentParagraph(focusedPara)
        }
    }
    Box {
        val tableEnabled = activeTableBlock >= 0
        TextButton(onClick = { tableMenu = true }) {
            Text("Table")
            Icon(painterResource(R.drawable.arrow_drop_down_24px), contentDescription = "Table options")
        }
        DropdownMenu(expanded = tableMenu, onDismissRequest = { tableMenu = false }) {
            DropdownMenuItem(text = { Text("Insert table") }, onClick = { tableMenu = false; actions.onInsertTable() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Insert row below") }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.textTableAddRow(activeTableBlock, activeTableRow) })
            DropdownMenuItem(text = { Text("Insert column right") }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.textTableAddColumn(activeTableBlock, activeTableCol) })
            DropdownMenuItem(text = { Text("Delete row") }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.textTableDeleteRow(activeTableBlock, activeTableRow) })
            DropdownMenuItem(text = { Text("Delete column") }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.textTableDeleteColumn(activeTableBlock, activeTableCol) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Bold cell") }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.setTextTableCellSpanFormat(activeTableBlock, activeTableRow, activeTableCol) { it.copy(bold = !it.bold) } })
            DropdownMenuItem(text = { Text("Italic cell") }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.setTextTableCellSpanFormat(activeTableBlock, activeTableRow, activeTableCol) { it.copy(italic = !it.italic) } })
            DropdownMenuItem(text = { Text("Merge with right") }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.mergeTextTableCells(activeTableBlock, activeTableRow, activeTableCol, activeTableRow, activeTableCol + 1) })
            DropdownMenuItem(text = { Text("Merge with below") }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.mergeTextTableCells(activeTableBlock, activeTableRow, activeTableCol, activeTableRow + 1, activeTableCol) })
            DropdownMenuItem(text = { Text("Unmerge cell") }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.unmergeTextTableCells(activeTableBlock, activeTableRow, activeTableCol) })
        }
    }
    Box {
        var moreMenu by remember { mutableStateOf(false) }
        FmtIcon(R.drawable.more_vert_24px, false, enabled, "More") { moreMenu = true }
        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
            DropdownMenuItem(text = { Text("Font size…") }, onClick = { moreMenu = false; actions.onFontSize() })
            DropdownMenuItem(text = { Text("Clear formatting") }, onClick = { moreMenu = false; viewModel.clearRunFormatting(runStart, runEnd, selStart, selEnd) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Demote list item") }, enabled = focusedPara >= 0, onClick = { moreMenu = false; if (focusedPara >= 0) viewModel.changeListLevel(focusedPara, 1) })
            DropdownMenuItem(text = { Text("Promote list item") }, enabled = focusedPara >= 0, onClick = { moreMenu = false; if (focusedPara >= 0) viewModel.changeListLevel(focusedPara, -1) })
            DropdownMenuItem(text = { Text("Restart numbering") }, enabled = focusedPara >= 0, onClick = { moreMenu = false; if (focusedPara >= 0) viewModel.restartNumbering(focusedPara) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Duplicate paragraph") }, enabled = focusedPara >= 0, onClick = { moreMenu = false; viewModel.duplicateParagraph(focusedPara) })
            DropdownMenuItem(text = { Text("Move paragraph up") }, enabled = focusedPara > 0, onClick = { moreMenu = false; viewModel.moveParagraphUp(focusedPara) })
            DropdownMenuItem(text = { Text("Move paragraph down") }, enabled = focusedPara >= 0, onClick = { moreMenu = false; viewModel.moveParagraphDown(focusedPara) })
            DropdownMenuItem(text = { Text("Delete paragraph", color = MaterialTheme.colorScheme.error) }, enabled = focusedPara >= 0, onClick = { moreMenu = false; viewModel.deleteParagraph(focusedPara) })
        }
    }
}

// --- Spreadsheet cell branch ---

@Composable
private fun CellFormatControls(target: FormatTarget.Cell?, viewModel: OfficeViewModel, actions: BottomBarActions) {
    val enabled = target != null
    val s = target?.sheet ?: -1
    val r = target?.row ?: -1
    val c = target?.col ?: -1
    var alignMenu by remember { mutableStateOf(false) }
    EditorBaseButtons(CellFormatter(viewModel, s, r, c, enabled))
    FmtIcon(R.drawable.format_color_text_24px, false, enabled, "Text color") { actions.onCellTextColor() }
    TextButton(onClick = { actions.onCellBgColor() }, enabled = enabled) { Text("Fill") }
    Box {
        FmtIcon(R.drawable.format_align_left_24px, false, enabled, "Alignment") { alignMenu = true }
        DropdownMenu(expanded = alignMenu, onDismissRequest = { alignMenu = false }) {
            DropdownMenuItem(text = { Text("Left") }, leadingIcon = { Icon(painterResource(R.drawable.format_align_left_24px), null) }, onClick = { alignMenu = false; if (enabled) viewModel.setCellAlignment(s, r, c, TextAlign.Start) })
            DropdownMenuItem(text = { Text("Center") }, leadingIcon = { Icon(painterResource(R.drawable.format_align_center_24px), null) }, onClick = { alignMenu = false; if (enabled) viewModel.setCellAlignment(s, r, c, TextAlign.Center) })
            DropdownMenuItem(text = { Text("Right") }, leadingIcon = { Icon(painterResource(R.drawable.format_align_right_24px), null) }, onClick = { alignMenu = false; if (enabled) viewModel.setCellAlignment(s, r, c, TextAlign.End) })
        }
    }
    TextButton(onClick = { if (enabled) viewModel.unmergeCells(s, r, c) }, enabled = enabled) { Text("Unmerge") }
    Box {
        var moreMenu by remember { mutableStateOf(false) }
        var numMenu by remember { mutableStateOf(false) }
        FmtIcon(R.drawable.more_vert_24px, false, enabled, "More") { moreMenu = true }
        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
            DropdownMenuItem(text = { Text("Fill down") }, enabled = enabled, onClick = { moreMenu = false; if (enabled) viewModel.fillDownToEnd(s, r, c) })
            DropdownMenuItem(text = { Text("Border color…") }, enabled = enabled, onClick = { moreMenu = false; actions.onCellBorder() })
            DropdownMenuItem(text = { Text("Comment…") }, enabled = enabled, onClick = { moreMenu = false; actions.onCellComment() })
            DropdownMenuItem(text = { Text("Row/column size…") }, enabled = enabled, onClick = { moreMenu = false; actions.onCellResize() })
            DropdownMenuItem(text = { Text("Number format") }, trailingIcon = { Icon(painterResource(R.drawable.arrow_drop_down_24px), null) }, enabled = enabled, onClick = { numMenu = true })
        }
        DropdownMenu(expanded = numMenu, onDismissRequest = { numMenu = false }) {
            DropdownMenuItem(text = { Text("General") }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, null) })
            DropdownMenuItem(text = { Text("Number (2 dp)") }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(decimals = 2, grouping = true)) })
            DropdownMenuItem(text = { Text("Integer") }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(decimals = 0)) })
            DropdownMenuItem(text = { Text("Percent") }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(decimals = 0, percent = true)) })
            DropdownMenuItem(text = { Text("Currency ($)") }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(decimals = 2, currencySymbol = "$", grouping = true)) })
            DropdownMenuItem(text = { Text("Date") }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(isDate = true)) })
            DropdownMenuItem(text = { Text("Time") }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(isTime = true)) })
            DropdownMenuItem(text = { Text("Scientific") }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(decimals = 2, isScientific = true)) })
            DropdownMenuItem(text = { Text("Fraction") }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(isFraction = true, fractionDenominatorDigits = 2)) })
        }
    }
}

// --- EditorFormatter adapters: map the shared base buttons onto office actions ---

/** Inline character formatting for a text-document run selection. */
private class TextRunFormatter(
    private val viewModel: OfficeViewModel,
    private val runStart: Int,
    private val runEnd: Int,
    private val selStart: Int,
    private val selEnd: Int,
    override val enabled: Boolean,
) : EditorFormatter {
    override val supported = setOf(
        EditorFormat.BOLD, EditorFormat.ITALIC, EditorFormat.UNDERLINE, EditorFormat.STRIKETHROUGH,
        EditorFormat.LINK,
    )

    override fun isActive(format: EditorFormat): Boolean = enabled && when (format) {
        EditorFormat.BOLD -> viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.bold }
        EditorFormat.ITALIC -> viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.italic }
        EditorFormat.UNDERLINE -> viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.underline }
        EditorFormat.STRIKETHROUGH -> viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.strikethrough }
        else -> false
    }

    override fun toggle(format: EditorFormat) {
        if (!enabled) return
        when (format) {
            EditorFormat.BOLD -> { val t = !isActive(EditorFormat.BOLD); viewModel.applyRunSpanStyle(runStart, runEnd, selStart, selEnd) { it.copy(bold = t) } }
            EditorFormat.ITALIC -> { val t = !isActive(EditorFormat.ITALIC); viewModel.applyRunSpanStyle(runStart, runEnd, selStart, selEnd) { it.copy(italic = t) } }
            EditorFormat.UNDERLINE -> { val t = !isActive(EditorFormat.UNDERLINE); viewModel.applyRunSpanStyle(runStart, runEnd, selStart, selEnd) { it.copy(underline = t) } }
            EditorFormat.STRIKETHROUGH -> { val t = !isActive(EditorFormat.STRIKETHROUGH); viewModel.applyRunSpanStyle(runStart, runEnd, selStart, selEnd) { it.copy(strikethrough = t) } }
            else -> {}
        }
    }

    override fun linkContext(): LinkContext? {
        if (!enabled) return null
        val link = viewModel.linkAt(runStart, runEnd, selStart)
        if (link != null) return LinkContext(editing = true, text = link.text, url = link.url)
        if (selStart != selEnd) return LinkContext(editing = false, text = viewModel.runSelectedText(runStart, runEnd, selStart, selEnd), url = "")
        return null
    }

    override fun applyLink(context: LinkContext, text: String, url: String) {
        if (!enabled || url.isBlank()) return
        val link = viewModel.linkAt(runStart, runEnd, selStart)
        val gs: Int; val ge: Int
        if (link != null) { gs = link.gStart; ge = link.gEnd }
        else if (selStart != selEnd) { gs = selStart; ge = selEnd }
        else return
        viewModel.setLink(runStart, runEnd, gs, ge, text.ifBlank { url }, url)
    }

    override fun removeLink(context: LinkContext) {
        val link = viewModel.linkAt(runStart, runEnd, selStart) ?: return
        viewModel.removeLinkInRun(runStart, runEnd, link.gStart, link.gEnd)
    }
}

/** Inline character formatting for a spreadsheet cell. */
private class CellFormatter(
    private val viewModel: OfficeViewModel,
    private val sheet: Int,
    private val row: Int,
    private val col: Int,
    override val enabled: Boolean,
) : EditorFormatter {
    override val supported = setOf(EditorFormat.BOLD, EditorFormat.ITALIC)
    override fun toggle(format: EditorFormat) {
        if (!enabled) return
        when (format) {
            EditorFormat.BOLD -> viewModel.setCellBold(sheet, row, col)
            EditorFormat.ITALIC -> viewModel.setCellItalic(sheet, row, col)
            else -> {}
        }
    }
}

/** Inline character formatting for a presentation slide element. */
private class SlideFormatter(
    private val viewModel: OfficeViewModel,
    private val slide: Int,
    private val element: Int,
    override val enabled: Boolean,
) : EditorFormatter {
    override val supported = setOf(EditorFormat.BOLD, EditorFormat.ITALIC, EditorFormat.UNDERLINE)
    override fun toggle(format: EditorFormat) {
        if (!enabled) return
        when (format) {
            EditorFormat.BOLD -> viewModel.toggleSlideElementBold(slide, element)
            EditorFormat.ITALIC -> viewModel.toggleSlideElementItalic(slide, element)
            EditorFormat.UNDERLINE -> viewModel.toggleSlideElementUnderline(slide, element)
            else -> {}
        }
    }
}

@Composable
private fun ElementFormatControls(target: FormatTarget.Element?, viewModel: OfficeViewModel, actions: BottomBarActions) {
    val enabled = target != null
    val s = target?.slide ?: -1
    val e = target?.element ?: -1
    var alignMenu by remember { mutableStateOf(false) }
    EditorBaseButtons(SlideFormatter(viewModel, s, e, enabled))
    FmtIcon(R.drawable.format_color_text_24px, false, enabled, "Text color") { actions.onSlideTextColor() }
    TextButton(onClick = { actions.onSlideFill() }, enabled = enabled) { Text("Fill") }
    TextButton(onClick = { actions.onSlideStroke() }, enabled = enabled) { Text("Border") }
    Box {
        FmtIcon(R.drawable.format_align_left_24px, false, enabled, "Alignment") { alignMenu = true }
        DropdownMenu(expanded = alignMenu, onDismissRequest = { alignMenu = false }) {
            DropdownMenuItem(text = { Text("Left") }, leadingIcon = { Icon(painterResource(R.drawable.format_align_left_24px), null) }, onClick = { alignMenu = false; if (enabled) viewModel.setSlideElementAlignment(s, e, TextAlign.Start) })
            DropdownMenuItem(text = { Text("Center") }, leadingIcon = { Icon(painterResource(R.drawable.format_align_center_24px), null) }, onClick = { alignMenu = false; if (enabled) viewModel.setSlideElementAlignment(s, e, TextAlign.Center) })
            DropdownMenuItem(text = { Text("Right") }, leadingIcon = { Icon(painterResource(R.drawable.format_align_right_24px), null) }, onClick = { alignMenu = false; if (enabled) viewModel.setSlideElementAlignment(s, e, TextAlign.End) })
        }
    }
    FmtIcon(com.vayunmathur.library.R.drawable.delete_24px, false, enabled, "Delete element") { if (enabled) actions.onDeleteElement() }
    Box {
        var moreMenu by remember { mutableStateOf(false) }
        FmtIcon(R.drawable.more_vert_24px, false, enabled, "More") { moreMenu = true }
        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
            DropdownMenuItem(text = { Text("Duplicate") }, enabled = enabled, onClick = { moreMenu = false; if (enabled) viewModel.duplicateSlideElement(s, e) })
            DropdownMenuItem(text = { Text("Bring to front") }, enabled = enabled, onClick = { moreMenu = false; if (enabled) viewModel.reorderSlideElement(s, e, true) })
            DropdownMenuItem(text = { Text("Send to back") }, enabled = enabled, onClick = { moreMenu = false; if (enabled) viewModel.reorderSlideElement(s, e, false) })
            DropdownMenuItem(text = { Text("Rotate 90°") }, enabled = enabled, onClick = { moreMenu = false; if (enabled) viewModel.setSlideElementRotation(s, e, 90f) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Speaker notes…") }, onClick = { moreMenu = false; actions.onSlideNotes() })
            DropdownMenuItem(text = { Text("Slide background…") }, onClick = { moreMenu = false; actions.onSlideBackground() })
            DropdownMenuItem(text = { Text("Slide transition…") }, onClick = { moreMenu = false; actions.onSlideTransition() })
        }
    }
}
