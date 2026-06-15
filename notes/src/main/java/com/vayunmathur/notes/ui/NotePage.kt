package com.vayunmathur.notes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.IntegrationInstructions
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.R as LibraryR
import com.vayunmathur.notes.R
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.util.NotesViewModel

private fun stripLinePrefix(line: String): String {
    Regex("^#{1,6} ").find(line)?.let { return line.substring(it.value.length) }
    if (line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("- [X] ")) return line.substring(6)
    if (line.startsWith("- ")) return line.substring(2)
    Regex("^\\d+\\. ").find(line)?.let { return line.substring(it.value.length) }
    if (line.startsWith("> ")) return line.substring(2)
    return line
}

private fun getLinePrefix(line: String): String {
    Regex("^#{1,6} ").find(line)?.let { return it.value }
    if (line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("- [X] ")) return line.substring(0, 6)
    if (line.startsWith("- ")) return "- "
    Regex("^\\d+\\. ").find(line)?.let { return it.value }
    if (line.startsWith("> ")) return "> "
    return ""
}

private fun getSelectedLines(text: String, selection: TextRange): Pair<Int, Int> {
    val blockStart = text.lastIndexOf('\n', (selection.start - 1).coerceAtLeast(0)) + 1
    val effectiveEnd = if (!selection.collapsed && selection.end > selection.start && selection.end > 0 && text.getOrNull(selection.end - 1) == '\n') selection.end - 1 else selection.end
    val blockEnd = text.indexOf('\n', effectiveEnd).let { if (it == -1) text.length else it }
    return blockStart to blockEnd
}

private fun hasInlineMarker(content: String, marker: String): Boolean {
    if (content.length < marker.length * 2) return false
    if (!content.startsWith(marker) || !content.endsWith(marker)) return false
    if (marker == "*" && content.startsWith("**") && !content.startsWith("***")) return false
    if (marker == "*" && content.endsWith("**") && !content.endsWith("***")) return false
    return true
}

private fun toggleInlineFormat(value: TextFieldValue, marker: String): TextFieldValue {
    val text = value.text
    val selection = value.selection
    if (selection.collapsed) {
        val newText = text.substring(0, selection.start) + marker + marker + text.substring(selection.start)
        return value.copy(text = newText, selection = TextRange(selection.start + marker.length))
    }
    val (blockStart, blockEnd) = getSelectedLines(text, selection)
    val blockText = text.substring(blockStart, blockEnd)
    val lines = blockText.split("\n")

    val allHaveMarker = lines.all { line ->
        line.isBlank() || hasInlineMarker(line.substring(getLinePrefix(line).length), marker)
    }

    val newLines = lines.map { line ->
        if (line.isBlank()) line
        else {
            val prefix = getLinePrefix(line)
            val content = line.substring(prefix.length)
            if (allHaveMarker) {
                prefix + content.substring(marker.length, content.length - marker.length)
            } else {
                if (hasInlineMarker(content, marker)) line
                else prefix + marker + content + marker
            }
        }
    }

    val newBlockText = newLines.joinToString("\n")
    val newText = text.substring(0, blockStart) + newBlockText + text.substring(blockEnd)
    return value.copy(text = newText, selection = TextRange(blockStart, blockStart + newBlockText.length))
}

private fun toggleLinePrefix(value: TextFieldValue, prefix: String): TextFieldValue {
    val text = value.text
    val selection = value.selection
    val (blockStart, blockEnd) = getSelectedLines(text, selection)
    val blockText = text.substring(blockStart, blockEnd)
    val lines = blockText.split("\n")

    val isNumberedList = prefix == "1. "
    val isCheckbox = prefix == "- [ ] "

    val allHavePrefix = lines.all { line ->
        when {
            isNumberedList -> Regex("^\\d+\\. ").containsMatchIn(line)
            isCheckbox -> line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("- [X] ")
            prefix == "- " -> line.startsWith("- ") && !line.startsWith("- [ ] ") && !line.startsWith("- [x] ") && !line.startsWith("- [X] ")
            else -> line.startsWith(prefix)
        }
    }

    val newLines = if (allHavePrefix) {
        lines.map { stripLinePrefix(it) }
    } else {
        lines.mapIndexed { index, line ->
            val stripped = stripLinePrefix(line)
            if (isNumberedList) "${index + 1}. $stripped" else prefix + stripped
        }
    }

    val newBlockText = newLines.joinToString("\n")
    val newText = text.substring(0, blockStart) + newBlockText + text.substring(blockEnd)
    val newSelection = if (selection.collapsed) {
        val textBeforeCursorInBlock = text.substring(blockStart, selection.start)
        val lineIndex = textBeforeCursorInBlock.count { it == '\n' }
        val diff = newLines[lineIndex].length - lines[lineIndex].length
        TextRange((selection.start + diff).coerceAtLeast(blockStart))
    } else {
        TextRange(blockStart, blockStart + newBlockText.length)
    }
    return value.copy(text = newText, selection = newSelection)
}

private fun insertHeading(value: TextFieldValue, level: Int): TextFieldValue {
    val text = value.text
    val selection = value.selection
    val (blockStart, blockEnd) = getSelectedLines(text, selection)
    val blockText = text.substring(blockStart, blockEnd)
    val lines = blockText.split("\n")
    val targetPrefix = "#".repeat(level) + " "

    val allHaveHeading = lines.all { line ->
        Regex("^#{1,6} ").find(line)?.value == targetPrefix
    }

    val newLines = if (allHaveHeading) {
        lines.map { stripLinePrefix(it) }
    } else {
        lines.map { line -> targetPrefix + stripLinePrefix(line) }
    }

    val newBlockText = newLines.joinToString("\n")
    val newText = text.substring(0, blockStart) + newBlockText + text.substring(blockEnd)
    val newSelection = if (selection.collapsed) {
        val textBeforeCursorInBlock = text.substring(blockStart, selection.start)
        val lineIndex = textBeforeCursorInBlock.count { it == '\n' }
        val diff = newLines[lineIndex].length - lines[lineIndex].length
        TextRange((selection.start + diff).coerceAtLeast(blockStart))
    } else {
        TextRange(blockStart, blockStart + newBlockText.length)
    }
    return value.copy(text = newText, selection = newSelection)
}

private fun isInlineFormatActive(text: String, selection: TextRange, marker: String): Boolean {
    val (blockStart, blockEnd) = getSelectedLines(text, selection)
    val blockText = text.substring(blockStart, blockEnd)
    val lines = blockText.split("\n")
    val nonEmptyLines = lines.filter { it.isNotBlank() }
    if (nonEmptyLines.isEmpty()) return false
    return nonEmptyLines.all { line ->
        hasInlineMarker(line.substring(getLinePrefix(line).length), marker)
    }
}

private fun isLinePrefixActive(text: String, selection: TextRange, prefix: String): Boolean {
    val (blockStart, blockEnd) = getSelectedLines(text, selection)
    val blockText = text.substring(blockStart, blockEnd)
    val lines = blockText.split("\n")
    return lines.all { line ->
        when {
            prefix == "1. " -> Regex("^\\d+\\. ").containsMatchIn(line)
            prefix == "- [ ] " -> line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("- [X] ")
            prefix == "- " -> line.startsWith("- ") && !line.startsWith("- [ ] ") && !line.startsWith("- [x] ") && !line.startsWith("- [X] ")
            else -> line.startsWith(prefix)
        }
    }
}

private fun getActiveHeadingLevel(text: String, cursorPos: Int): Int? {
    val lineStart = text.lastIndexOf('\n', (cursorPos - 1).coerceAtLeast(0)) + 1
    val lineEnd = text.indexOf('\n', cursorPos).let { if (it == -1) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    val match = Regex("^(#{1,6}) ").find(line) ?: return null
    return match.groupValues[1].length
}

private fun insertCodeBlock(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val selection = value.selection
    if (selection.collapsed) {
        val insert = "```\n\n```"
        val newText = text.substring(0, selection.start) + insert + text.substring(selection.start)
        return value.copy(text = newText, selection = TextRange(selection.start + 4))
    }
    val selectedText = text.substring(selection.start, selection.end)
    val newText = text.substring(0, selection.start) + "```\n" + selectedText + "\n```" + text.substring(selection.end)
    return value.copy(text = newText, selection = TextRange(selection.start + 4, selection.start + 4 + selectedText.length))
}

private fun insertHorizontalRule(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val cursor = value.selection.start
    val insert = "\n---\n"
    val newText = text.substring(0, cursor) + insert + text.substring(cursor)
    return value.copy(text = newText, selection = TextRange(cursor + insert.length))
}

private fun insertLink(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val selection = value.selection
    if (selection.collapsed) {
        val insert = "[link](url)"
        val newText = text.substring(0, selection.start) + insert + text.substring(selection.start)
        return value.copy(text = newText, selection = TextRange(selection.start + 1, selection.start + 5))
    }
    val selectedText = text.substring(selection.start, selection.end)
    val newText = text.substring(0, selection.start) + "[" + selectedText + "](url)" + text.substring(selection.end)
    return value.copy(text = newText, selection = TextRange(selection.end + 3, selection.end + 6))
}

private val checkboxPattern = Regex("^(\\s*- )\\[([ xX])] ")

private fun tryToggleCheckbox(offset: Int, value: TextFieldValue): TextFieldValue? {
    val text = value.text
    val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
    val lineEnd = text.indexOf('\n', offset).let { if (it == -1) text.length else it }
    val line = text.substring(lineStart, lineEnd)

    val match = checkboxPattern.find(line) ?: return null
    val bracketOffset = lineStart + match.groups[1]!!.value.length
    if (offset > bracketOffset + 3) return null

    val isChecked = match.groups[2]!!.value.lowercase() == "x"
    val newChar = if (isChecked) " " else "x"
    val newText = text.substring(0, bracketOffset + 1) + newChar + text.substring(bracketOffset + 2)
    return value.copy(text = newText, selection = TextRange(offset))
}

private fun findCheckboxPositions(text: String): List<Pair<Int, Boolean>> {
    val results = mutableListOf<Pair<Int, Boolean>>()
    Regex("(?m)^(\\s*- )\\[([ xX])] ").findAll(text).forEach { match ->
        val bracketOffset = match.groups[1]!!.range.last + 1
        val isChecked = match.groups[2]!!.value.lowercase() == "x"
        results.add(bracketOffset to isChecked)
    }
    return results
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotePage(
    backStack: NavBackStack<Route>,
    notesViewModel: NotesViewModel,
    noteID: Long,
) {
    var note by notesViewModel.editableNote(noteID) { Note(0, "", "") }

    if (noteID != 0L && note.id == 0L) return

    var showSearchBar by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var searchIndex by remember { mutableIntStateOf(0) }
    val focusRequestor = remember { FocusRequester() }

    val searchResultsCount by remember(note.content, searchText) {
        derivedStateOf {
            notesViewModel.searchResultsCount(note.content, searchText)
        }
    }

    BackHandler(enabled = showSearchBar) {
        if (searchText.isNotEmpty()) {
            searchText = ""
            searchIndex = 0
        } else {
            showSearchBar = false
        }
    }

    LaunchedEffect(showSearchBar) {
        if (showSearchBar) {
            focusRequestor.requestFocus()
        }
    }

    val context = LocalContext.current

    LaunchedEffect(notesViewModel) {
        notesViewModel.shareUris.collect { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Note"))
        }
    }

    var contentValue by remember(noteID) {
        mutableStateOf(TextFieldValue(notesViewModel.parseDisplay(note.content)))
    }

    var showHeadingMenu by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(title = {
            if (showSearchBar) {
                TextField(
                    value = searchText,
                    onValueChange = {
                        searchText = it
                        searchIndex = 0
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequestor),
                    placeholder = { Text(stringResource(R.string.search)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    )
                )
            }
        }, navigationIcon = {
            Row {
                IconNavigation {
                    if (showSearchBar) {
                        showSearchBar = false
                    } else {
                        backStack.pop()
                    }
                }
            }
        }, actions = {
            if (!showSearchBar) {
                IconButton({ showSearchBar = true }) { IconSearch() }
                IconButton({
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("note", note.content))
                }) {
                    IconCopy()
                }
                IconButton({
                    notesViewModel.requestShare(note.title, note.content)
                }) {
                    IconShare()
                }
                IconButton(onClick = {
                    notesViewModel.delete(note)
                    backStack.pop()
                }) {
                    IconDelete()
                }
            } else {
                if (searchResultsCount > 0) {
                    Text(
                        "${searchIndex + 1} / $searchResultsCount",
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            }
        })
    }, floatingActionButton = {
        if (showSearchBar) {
            Column(Modifier.imePadding()) {
                SmallFloatingActionButton({ if (searchIndex > 0) searchIndex-- }) {
                    Icon(painterResource(LibraryR.drawable.chevron_right_24px), null, modifier = Modifier.rotate(-90f))
                }
                SmallFloatingActionButton({
                    if (searchIndex < searchResultsCount - 1) searchIndex++
                }) {
                    Icon(painterResource(LibraryR.drawable.chevron_right_24px), null, modifier = Modifier.rotate(90f))
                }
            }
        }
    }, bottomBar = {
        if (!showSearchBar) {
            Surface(
                modifier = Modifier.imePadding(),
                tonalElevation = 3.dp,
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val isBoldActive = isInlineFormatActive(contentValue.text, contentValue.selection, "**")
                    val isItalicActive = isInlineFormatActive(contentValue.text, contentValue.selection, "*")
                    val isStrikethroughActive = isInlineFormatActive(contentValue.text, contentValue.selection, "~~")
                    val isCodeActive = isInlineFormatActive(contentValue.text, contentValue.selection, "`")
                    val isQuoteActive = isLinePrefixActive(contentValue.text, contentValue.selection, "> ")
                    val isBulletActive = isLinePrefixActive(contentValue.text, contentValue.selection, "- ")
                    val isNumberedActive = isLinePrefixActive(contentValue.text, contentValue.selection, "1. ")
                    val isCheckboxActive = isLinePrefixActive(contentValue.text, contentValue.selection, "- [ ] ")
                    val activeHeadingLevel = getActiveHeadingLevel(contentValue.text, contentValue.selection.start)
                    val activeBg = Modifier.background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(8.dp)
                    )

                    IconButton(
                        onClick = {
                            contentValue = toggleInlineFormat(contentValue, "**")
                            note = note.copy(content = contentValue.text)
                            contentValue = contentValue.copy(annotatedString = notesViewModel.parseDisplay(contentValue.text))
                        },
                        modifier = if (isBoldActive) activeBg else Modifier
                    ) { Icon(Icons.Default.FormatBold, "Bold") }

                    IconButton(
                        onClick = {
                            contentValue = toggleInlineFormat(contentValue, "*")
                            note = note.copy(content = contentValue.text)
                            contentValue = contentValue.copy(annotatedString = notesViewModel.parseDisplay(contentValue.text))
                        },
                        modifier = if (isItalicActive) activeBg else Modifier
                    ) { Icon(Icons.Default.FormatItalic, "Italic") }

                    IconButton(
                        onClick = {
                            contentValue = toggleInlineFormat(contentValue, "~~")
                            note = note.copy(content = contentValue.text)
                            contentValue = contentValue.copy(annotatedString = notesViewModel.parseDisplay(contentValue.text))
                        },
                        modifier = if (isStrikethroughActive) activeBg else Modifier
                    ) { Icon(Icons.Default.FormatStrikethrough, "Strikethrough") }

                    IconButton(
                        onClick = {
                            contentValue = toggleInlineFormat(contentValue, "`")
                            note = note.copy(content = contentValue.text)
                            contentValue = contentValue.copy(annotatedString = notesViewModel.parseDisplay(contentValue.text))
                        },
                        modifier = if (isCodeActive) activeBg else Modifier
                    ) { Icon(Icons.Default.Code, "Inline Code") }

                    Box {
                        IconButton(
                            onClick = { showHeadingMenu = true },
                            modifier = if (activeHeadingLevel != null) activeBg else Modifier
                        ) {
                            Icon(Icons.Default.Title, "Heading")
                        }
                        DropdownMenu(
                            expanded = showHeadingMenu,
                            onDismissRequest = { showHeadingMenu = false }
                        ) {
                            (1..6).forEach { level ->
                                val isActive = activeHeadingLevel == level
                                DropdownMenuItem(
                                    text = { Text("H$level") },
                                    onClick = {
                                        contentValue = insertHeading(contentValue, level)
                                        note = note.copy(content = contentValue.text)
                                        contentValue = contentValue.copy(annotatedString = notesViewModel.parseDisplay(contentValue.text))
                                        showHeadingMenu = false
                                    },
                                    modifier = if (isActive) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier,
                                    trailingIcon = if (isActive) {{ Icon(Icons.Default.Check, "Active") }} else null
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = {
                            contentValue = toggleLinePrefix(contentValue, "> ")
                            note = note.copy(content = contentValue.text)
                            contentValue = contentValue.copy(annotatedString = notesViewModel.parseDisplay(contentValue.text))
                        },
                        modifier = if (isQuoteActive) activeBg else Modifier
                    ) { Icon(Icons.Default.FormatQuote, "Quote") }

                    IconButton(
                        onClick = {
                            contentValue = toggleLinePrefix(contentValue, "- ")
                            note = note.copy(content = contentValue.text)
                            contentValue = contentValue.copy(annotatedString = notesViewModel.parseDisplay(contentValue.text))
                        },
                        modifier = if (isBulletActive) activeBg else Modifier
                    ) { Icon(Icons.Default.FormatListBulleted, "Bullet List") }

                    IconButton(
                        onClick = {
                            contentValue = toggleLinePrefix(contentValue, "1. ")
                            note = note.copy(content = contentValue.text)
                            contentValue = contentValue.copy(annotatedString = notesViewModel.parseDisplay(contentValue.text))
                        },
                        modifier = if (isNumberedActive) activeBg else Modifier
                    ) { Icon(Icons.Default.FormatListNumbered, "Numbered List") }

                    IconButton(
                        onClick = {
                            contentValue = toggleLinePrefix(contentValue, "- [ ] ")
                            note = note.copy(content = contentValue.text)
                            contentValue = contentValue.copy(annotatedString = notesViewModel.parseDisplay(contentValue.text))
                        },
                        modifier = if (isCheckboxActive) activeBg else Modifier
                    ) { Icon(Icons.Default.CheckBox, "Checkbox") }

                    IconButton({
                        contentValue = insertCodeBlock(contentValue)
                        note = note.copy(content = contentValue.text)
                        contentValue = contentValue.copy(annotatedString = notesViewModel.parseDisplay(contentValue.text))
                    }) { Icon(Icons.Default.IntegrationInstructions, "Code Block") }

                    IconButton({
                        contentValue = insertHorizontalRule(contentValue)
                        note = note.copy(content = contentValue.text)
                        contentValue = contentValue.copy(annotatedString = notesViewModel.parseDisplay(contentValue.text))
                    }) { Icon(Icons.Default.HorizontalRule, "Horizontal Rule") }

                    IconButton({
                        contentValue = insertLink(contentValue)
                        note = note.copy(content = contentValue.text)
                        contentValue = contentValue.copy(annotatedString = notesViewModel.parseDisplay(contentValue.text))
                    }) { Icon(Icons.Default.Link, "Link") }
                }
            }
        }
    }) { paddingValues ->
        LazyColumn(contentPadding = paddingValues + PaddingValues(horizontal = 16.dp) + PaddingValues(bottom = 16.dp)) {
            item {
                BasicTextField(
                    note.title,
                    { note = note.copy(title = it) },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(color = LocalContentColor.current),
                    cursorBrush = SolidColor(LocalContentColor.current),
                    decorationBox = { innerTextField ->
                        Box {
                            if (note.title.isEmpty()) Text(
                                text = stringResource(R.string.title),
                                style = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            innerTextField()
                        }
                    }
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
            }
            item {
                val displayValue by remember(note.content, searchText, searchIndex, showSearchBar, contentValue.selection) {
                    derivedStateOf {
                        if (showSearchBar) {
                            TextFieldValue(
                                notesViewModel.parseDisplay(
                                    note.content,
                                    searchQuery = searchText,
                                    searchIndex = searchIndex,
                                ),
                                selection = contentValue.selection,
                            )
                        } else {
                            contentValue
                        }
                    }
                }
                var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                val density = LocalDensity.current
                Box {
                    BasicTextField(
                        displayValue,
                        { newValue ->
                            if (newValue.text == contentValue.text && newValue.selection.collapsed) {
                                val toggled = tryToggleCheckbox(newValue.selection.start, contentValue)
                                if (toggled != null) {
                                    note = note.copy(content = toggled.text)
                                    contentValue = toggled.copy(annotatedString = notesViewModel.parseDisplay(toggled.text))
                                    return@BasicTextField
                                }
                            }
                            note = note.copy(content = newValue.text)
                            contentValue = newValue.copy(annotatedString = notesViewModel.parseDisplay(newValue.text))
                        },
                        Modifier.fillMaxSize(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current),
                        cursorBrush = SolidColor(LocalContentColor.current),
                        onTextLayout = { textLayoutResult = it },
                        decorationBox = { innerTextField ->
                            Box {
                                if (note.content.isEmpty()) Text(
                                    text = stringResource(R.string.content),
                                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                                innerTextField()
                            }
                        }
                    )
                    textLayoutResult?.let { layout ->
                        val checkboxes = remember(note.content) { findCheckboxPositions(note.content) }
                        checkboxes.forEach { (bracketOffset, isChecked) ->
                            val rect = runCatching { layout.getBoundingBox(bracketOffset) }.getOrNull() ?: return@forEach
                            val lineHeight = rect.bottom - rect.top
                            with(density) {
                                Icon(
                                    imageVector = if (isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = if (isChecked) "Checked" else "Unchecked",
                                    tint = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .offset(x = rect.left.toDp() - 12.dp, y = rect.top.toDp())
                                        .size(lineHeight.toDp())
                                        .clickable {
                                            val toggled = tryToggleCheckbox(bracketOffset, contentValue)
                                            if (toggled != null) {
                                                note = note.copy(content = toggled.text)
                                                contentValue = toggled.copy(annotatedString = notesViewModel.parseDisplay(toggled.text))
                                            }
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
