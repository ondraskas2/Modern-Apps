package com.vayunmathur.office.util

import android.app.Application
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.office.odf.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class OfficeViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<ViewState>(ViewState.Empty)
    val state: StateFlow<ViewState> = _state

    private val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode

    private val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo

    private val _nightMode = MutableStateFlow(false)
    val nightMode: StateFlow<Boolean> = _nightMode

    var documentUri: Uri? = null
        private set

    // --- Auto-save ---
    private var autoSaveJob: Job? = null
    private var _autoSaveEnabled = MutableStateFlow(false)
    val autoSaveEnabled: StateFlow<Boolean> = _autoSaveEnabled
    private var autoSaveIntervalMs: Long = 60_000L // default 1 minute

    fun setAutoSave(enabled: Boolean, intervalSeconds: Int = 60) {
        autoSaveIntervalMs = intervalSeconds * 1000L
        _autoSaveEnabled.value = enabled
        autoSaveJob?.cancel()
        if (enabled) {
            autoSaveJob = viewModelScope.launch {
                while (true) {
                    delay(autoSaveIntervalMs)
                    if (_hasUnsavedChanges.value && documentUri != null) save()
                }
            }
        }
    }

    // --- Settings ---
    fun loadSettings(context: Context) {
        val prefs = context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
        val autoSave = prefs.getBoolean("auto_save", false)
        val interval = prefs.getInt("auto_save_interval", 60)
        setAutoSave(autoSave, interval)
    }

    fun saveSettings(context: Context, autoSave: Boolean, autoSaveInterval: Int, defaultFontSize: Float) {
        val prefs = context.getSharedPreferences("office_settings", Context.MODE_PRIVATE).edit()
        prefs.putBoolean("auto_save", autoSave)
        prefs.putInt("auto_save_interval", autoSaveInterval)
        prefs.putFloat("default_font_size", defaultFontSize)
        prefs.apply()
        setAutoSave(autoSave, autoSaveInterval)
    }

    fun getDefaultFontSize(context: Context): Float {
        return context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
            .getFloat("default_font_size", 16f)
    }

    fun getAutoSaveInterval(context: Context): Int {
        return context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
            .getInt("auto_save_interval", 60)
    }

    fun getAutoSaveEnabled(context: Context): Boolean {
        return context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
            .getBoolean("auto_save", false)
    }

    // --- Undo/Redo ---
    private val undoStack = ArrayDeque<OdfDocument>(maxOf(1, MAX_UNDO))
    private val redoStack = ArrayDeque<OdfDocument>(maxOf(1, MAX_UNDO))

    private fun pushUndo(doc: OdfDocument) {
        undoStack.addLast(doc)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = false
    }

    fun undo() {
        val current = (_state.value as? ViewState.Loaded)?.document ?: return
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        _state.value = ViewState.Loaded(previous)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        _hasUnsavedChanges.value = true
    }

    fun redo() {
        val current = (_state.value as? ViewState.Loaded)?.document ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        _state.value = ViewState.Loaded(next)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        _hasUnsavedChanges.value = true
    }

    private fun updateDocument(newDoc: OdfDocument) {
        val current = (_state.value as? ViewState.Loaded)?.document ?: return
        pushUndo(current)
        _state.value = ViewState.Loaded(newDoc)
        _hasUnsavedChanges.value = true
    }

    // --- Recent files ---
    fun addToRecent(context: Context, uri: Uri, name: String) {
        val prefs = context.getSharedPreferences("office_recent", Context.MODE_PRIVATE)
        val existing = getRecentFiles(context).toMutableList()
        existing.removeAll { it.first == uri.toString() }
        existing.add(0, Pair(uri.toString(), name))
        if (existing.size > MAX_RECENT) existing.subList(MAX_RECENT, existing.size).clear()
        val editor = prefs.edit()
        editor.putInt("count", existing.size)
        existing.forEachIndexed { i, (u, n) ->
            editor.putString("uri_$i", u)
            editor.putString("name_$i", n)
        }
        editor.apply()
    }

    fun getRecentFiles(context: Context): List<Pair<String, String>> {
        val prefs = context.getSharedPreferences("office_recent", Context.MODE_PRIVATE)
        val count = prefs.getInt("count", 0)
        return (0 until count).mapNotNull { i ->
            val uri = prefs.getString("uri_$i", null) ?: return@mapNotNull null
            val name = prefs.getString("name_$i", null) ?: return@mapNotNull null
            Pair(uri, name)
        }
    }

    fun clearRecentFiles(context: Context) {
        context.getSharedPreferences("office_recent", Context.MODE_PRIVATE).edit().clear().apply()
    }

    // --- Night mode ---
    fun toggleNightMode() { _nightMode.value = !_nightMode.value }

    // --- Load / Clear ---

    fun loadDocument(uri: Uri, fileName: String) {
        _state.value = ViewState.Loading
        _isEditMode.value = true
        _hasUnsavedChanges.value = false
        undoStack.clear(); redoStack.clear()
        _canUndo.value = false; _canRedo.value = false
        documentUri = uri
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val doc = OdfParser.parse(getApplication(), uri, fileName)
                _state.value = ViewState.Loaded(doc)
                addToRecent(getApplication(), uri, fileName)
            } catch (e: Exception) {
                _state.value = ViewState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun loadCsv(uri: Uri, fileName: String) {
        _state.value = ViewState.Loading
        _isEditMode.value = true
        _hasUnsavedChanges.value = false
        undoStack.clear(); redoStack.clear()
        _canUndo.value = false; _canRedo.value = false
        documentUri = uri
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                val doc = OdfParser.parseCsv(text, fileName)
                _state.value = ViewState.Loaded(doc)
                addToRecent(getApplication(), uri, fileName)
            } catch (e: Exception) {
                _state.value = ViewState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun clear() {
        _state.value = ViewState.Empty
        _isEditMode.value = false
        _hasUnsavedChanges.value = false
        undoStack.clear(); redoStack.clear()
        _canUndo.value = false; _canRedo.value = false
        documentUri = null
        autoSaveJob?.cancel()
    }

    fun toggleEditMode() { _isEditMode.value = !_isEditMode.value }

    // --- Create new documents ---

    fun createNewTextDocument() {
        undoStack.clear(); redoStack.clear()
        _canUndo.value = false; _canRedo.value = false
        val doc = OdfDocument.TextDocument(
            title = "Untitled Document",
            content = listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = "")))))
        )
        _state.value = ViewState.Loaded(doc)
        _isEditMode.value = true
        _hasUnsavedChanges.value = true
        documentUri = null
    }

    fun createNewSpreadsheet() {
        undoStack.clear(); redoStack.clear()
        _canUndo.value = false; _canRedo.value = false
        val rows = (0 until 10).map { OdfRow(List(5) { OdfCell(text = "") }) }
        val doc = OdfDocument.Spreadsheet(
            title = "Untitled Spreadsheet",
            sheets = listOf(OdfSheet("Sheet 1", rows))
        )
        _state.value = ViewState.Loaded(doc)
        _isEditMode.value = true
        _hasUnsavedChanges.value = true
        documentUri = null
    }

    fun createNewPresentation() {
        undoStack.clear(); redoStack.clear()
        _canUndo.value = false; _canRedo.value = false
        val doc = OdfDocument.Presentation(
            title = "Untitled Presentation",
            slides = listOf(OdfSlide(
                name = "Slide 1",
                elements = listOf(
                    OdfSlideElement.Frame(OdfFrame(
                        x = 50f, y = 50f, width = 600f, height = 100f,
                        paragraphs = listOf(OdfParagraph(
                            listOf(OdfSpan(text = "Title", bold = true, fontSize = 36f)),
                            style = ParagraphStyle.HEADING1
                        ))
                    ))
                )
            ))
        )
        _state.value = ViewState.Loaded(doc)
        _isEditMode.value = true
        _hasUnsavedChanges.value = true
        documentUri = null
    }

    // --- Text document editing ---

    fun updateParagraphText(blockIndex: Int, newText: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
        val para = block.paragraph
        val oldText = para.spans.joinToString("") { it.text }
        if (newText == oldText) return
        // Preserve span formatting on the unchanged prefix/suffix; inserted text inherits a neighbor's style.
        var prefix = 0
        while (prefix < oldText.length && prefix < newText.length && oldText[prefix] == newText[prefix]) prefix++
        var oldEnd = oldText.length
        var newEnd = newText.length
        while (oldEnd > prefix && newEnd > prefix && oldText[oldEnd - 1] == newText[newEnd - 1]) { oldEnd--; newEnd-- }
        val chars = spansToChars(para.spans)
        val template = (chars.getOrNull(prefix - 1) ?: chars.getOrNull(oldEnd) ?: chars.getOrNull(prefix) ?: OdfSpan(text = "")).copy(text = "")
        val result = ArrayList<OdfSpan>(newText.length)
        for (i in 0 until prefix) result.add(chars[i])
        for (i in prefix until newEnd) result.add(template.copy(text = newText[i].toString()))
        for (i in oldEnd until chars.size) result.add(chars[i])
        content[blockIndex] = OdfContentBlock.Paragraph(para.copy(spans = charsToSpans(result)))
        updateDocument(doc.copy(content = content))
    }

    /** Applies a span transform to the character range [start, end). If the range is empty, applies to the whole paragraph. */
    fun applySpanStyleToRange(blockIndex: Int, start: Int, end: Int, transform: (OdfSpan) -> OdfSpan) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
        val chars = spansToChars(block.paragraph.spans)
        if (chars.isEmpty()) return
        val s = start.coerceIn(0, chars.size)
        val e = end.coerceIn(s, chars.size)
        val range = if (s == e) chars.indices else (s until e)
        for (i in range) chars[i] = transform(chars[i])
        content[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(spans = charsToSpans(chars)))
        updateDocument(doc.copy(content = content))
    }

    /** True if every character in [start, end) (or the whole paragraph when empty) satisfies [predicate]. */
    fun rangeHasFormat(blockIndex: Int, start: Int, end: Int, predicate: (OdfSpan) -> Boolean): Boolean {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return false
        val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return false
        val chars = spansToChars(block.paragraph.spans)
        if (chars.isEmpty()) return false
        val s = start.coerceIn(0, chars.size)
        val e = end.coerceIn(s, chars.size)
        val range = if (s == e) chars.indices else (s until e)
        return range.all { predicate(chars[it]) }
    }

    private fun spansToChars(spans: List<OdfSpan>): MutableList<OdfSpan> {
        val out = ArrayList<OdfSpan>()
        for (span in spans) for (ch in span.text) out.add(span.copy(text = ch.toString()))
        return out
    }

    private fun charsToSpans(chars: List<OdfSpan>): List<OdfSpan> {
        if (chars.isEmpty()) return listOf(OdfSpan(text = ""))
        val out = ArrayList<OdfSpan>()
        var current = chars[0]
        val sb = StringBuilder(current.text)
        for (i in 1 until chars.size) {
            val c = chars[i]
            if (c.copy(text = "") == current.copy(text = "")) {
                sb.append(c.text)
            } else {
                out.add(current.copy(text = sb.toString()))
                current = c
                sb.setLength(0)
                sb.append(c.text)
            }
        }
        out.add(current.copy(text = sb.toString()))
        return out
    }

    // --- Continuous (multi-paragraph run) editing ---

    private fun runParas(start: Int, endInclusive: Int): List<OdfParagraph>? {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return null
        if (start < 0 || endInclusive >= doc.content.size || start > endInclusive) return null
        val list = ArrayList<OdfParagraph>()
        for (i in start..endInclusive) {
            val b = doc.content[i] as? OdfContentBlock.Paragraph ?: return null
            list.add(b.paragraph)
        }
        return list
    }

    private fun paraLens(paras: List<OdfParagraph>) = paras.map { p -> p.spans.sumOf { it.text.length } }

    private fun runLocate(lens: List<Int>, pos: Int): Pair<Int, Int> {
        var rem = pos
        for (i in lens.indices) {
            if (rem <= lens[i]) return i to rem
            rem -= lens[i] + 1 // consume paragraph chars + separator
            if (rem < 0) return i to lens[i]
        }
        return lens.lastIndex.coerceAtLeast(0) to (lens.lastOrNull() ?: 0)
    }

    /** Edits a run of consecutive paragraphs [start, endInclusive] as one continuous text (paragraphs joined by '\n'). */
    fun updateParagraphRun(start: Int, endInclusive: Int, newText: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val paras = runParas(start, endInclusive) ?: return
        val lens = paraLens(paras)
        val oldText = paras.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
        if (newText == oldText) return
        var p = 0
        while (p < oldText.length && p < newText.length && oldText[p] == newText[p]) p++
        var oldEnd = oldText.length; var newEnd = newText.length
        while (oldEnd > p && newEnd > p && oldText[oldEnd - 1] == newText[newEnd - 1]) { oldEnd--; newEnd-- }
        val (sp, so) = runLocate(lens, p)
        val (ep, eo) = runLocate(lens, oldEnd)
        val middle = newText.substring(p, newEnd)
        val startChars = spansToChars(paras[sp].spans)
        val endChars = spansToChars(paras[ep].spans)
        val head = startChars.subList(0, so.coerceIn(0, startChars.size)).toMutableList()
        val tail = endChars.subList(eo.coerceIn(0, endChars.size), endChars.size).toMutableList()
        val template = (head.lastOrNull() ?: startChars.firstOrNull() ?: paras[sp].spans.firstOrNull() ?: OdfSpan(text = "")).copy(text = "")
        val segments = middle.split("\n")
        val rebuilt = ArrayList<OdfParagraph>()
        if (segments.size == 1) {
            val chars = ArrayList<OdfSpan>(head)
            for (ch in segments[0]) chars.add(template.copy(text = ch.toString()))
            chars.addAll(tail)
            rebuilt.add(paras[sp].copy(spans = charsToSpans(chars)))
        } else {
            val first = ArrayList<OdfSpan>(head)
            for (ch in segments.first()) first.add(template.copy(text = ch.toString()))
            rebuilt.add(paras[sp].copy(spans = charsToSpans(first)))
            for (k in 1 until segments.size - 1) {
                val mid = ArrayList<OdfSpan>()
                for (ch in segments[k]) mid.add(template.copy(text = ch.toString()))
                rebuilt.add(paras[sp].copy(spans = charsToSpans(mid)))
            }
            val last = ArrayList<OdfSpan>()
            for (ch in segments.last()) last.add(template.copy(text = ch.toString()))
            last.addAll(tail)
            rebuilt.add(paras[ep].copy(spans = charsToSpans(last)))
        }
        val finalParas = ArrayList<OdfParagraph>()
        finalParas.addAll(paras.subList(0, sp))
        finalParas.addAll(rebuilt)
        finalParas.addAll(paras.subList(ep + 1, paras.size))
        val newContent = doc.content.toMutableList()
        for (i in endInclusive downTo start) newContent.removeAt(i)
        newContent.addAll(start, finalParas.map { OdfContentBlock.Paragraph(it) })
        updateDocument(doc.copy(content = newContent))
    }

    /** Applies a span transform across a (possibly multi-paragraph) selection within a run. Empty selection = caret's whole paragraph. */
    fun applyRunSpanStyle(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, transform: (OdfSpan) -> OdfSpan) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val paras = runParas(start, endInclusive) ?: return
        val lens = paraLens(paras)
        val s = minOf(gStart, gEnd); val e = maxOf(gStart, gEnd)
        val newParas = paras.toMutableList()
        if (s == e) {
            val (pi, _) = runLocate(lens, s)
            val chars = spansToChars(paras[pi].spans)
            for (k in chars.indices) chars[k] = transform(chars[k])
            newParas[pi] = paras[pi].copy(spans = charsToSpans(chars))
        } else {
            var base = 0
            for (i in paras.indices) {
                val from = (maxOf(s, base) - base)
                val to = (minOf(e, base + lens[i]) - base)
                if (to > from) {
                    val chars = spansToChars(paras[i].spans)
                    for (k in from until to.coerceAtMost(chars.size)) chars[k] = transform(chars[k])
                    newParas[i] = paras[i].copy(spans = charsToSpans(chars))
                }
                base += lens[i] + 1
            }
        }
        val newContent = doc.content.toMutableList()
        for (i in start..endInclusive) newContent[i] = OdfContentBlock.Paragraph(newParas[i - start])
        updateDocument(doc.copy(content = newContent))
    }

    /** True if every character in the run selection (or caret's whole paragraph) satisfies [predicate]. */
    fun runRangeHasFormat(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, predicate: (OdfSpan) -> Boolean): Boolean {
        val paras = runParas(start, endInclusive) ?: return false
        val lens = paraLens(paras)
        val s = minOf(gStart, gEnd); val e = maxOf(gStart, gEnd)
        val sel = ArrayList<OdfSpan>()
        if (s == e) {
            val (pi, _) = runLocate(lens, s)
            sel.addAll(spansToChars(paras[pi].spans))
        } else {
            var base = 0
            for (i in paras.indices) {
                val from = (maxOf(s, base) - base)
                val to = (minOf(e, base + lens[i]) - base)
                if (to > from) {
                    val chars = spansToChars(paras[i].spans)
                    sel.addAll(chars.subList(from.coerceIn(0, chars.size), to.coerceIn(0, chars.size)))
                }
                base += lens[i] + 1
            }
        }
        return sel.isNotEmpty() && sel.all(predicate)
    }

    /** Paragraph index (within the document) at the given run-global caret position. */
    fun runParagraphIndexAt(start: Int, endInclusive: Int, gPos: Int): Int {
        val paras = runParas(start, endInclusive) ?: return start
        val (pi, _) = runLocate(paraLens(paras), gPos)
        return start + pi
    }

    /** Applies a paragraph-level mutation to every paragraph touched by the run selection. */
    fun mutateRunParagraphs(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, transform: (OdfParagraph) -> OdfParagraph) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val paras = runParas(start, endInclusive) ?: return
        val lens = paraLens(paras)
        val lo = runLocate(lens, minOf(gStart, gEnd)).first
        val hi = runLocate(lens, maxOf(gStart, gEnd)).first
        val newContent = doc.content.toMutableList()
        for (i in lo..hi) newContent[start + i] = OdfContentBlock.Paragraph(transform(paras[i]))
        updateDocument(doc.copy(content = newContent))
    }

    /** Inserts literal text at the caret position within a paragraph run (B17/B18). */
    fun insertTextInRun(start: Int, endInclusive: Int, gPos: Int, insert: String) {
        val paras = runParas(start, endInclusive) ?: return
        val full = paras.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
        val pos = gPos.coerceIn(0, full.length)
        val newText = full.substring(0, pos) + insert + full.substring(pos)
        updateParagraphRun(start, endInclusive, newText)
    }

    /** Clears all character formatting across a run selection (B24). */
    fun clearRunFormatting(start: Int, endInclusive: Int, gStart: Int, gEnd: Int) {
        applyRunSpanStyle(start, endInclusive, gStart, gEnd) {
            it.copy(bold = false, italic = false, underline = false, strikethrough = false,
                color = null, backgroundColor = null, fontSize = null, superscript = false, subscript = false)
        }
    }

    /** Promote/demote a list item's nesting level (B13). */
    fun changeListLevel(blockIndex: Int, delta: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
        val para = block.paragraph
        if (para.style != ParagraphStyle.LIST_ITEM) return
        content[blockIndex] = OdfContentBlock.Paragraph(para.copy(listLevel = (para.listLevel + delta).coerceIn(1, 9)))
        updateDocument(doc.copy(content = content))
    }

    /** Restart numbering at 1 for a numbered list item (B13). */
    fun restartNumbering(blockIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
        content[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(listItemIndex = 1))
        // Renumber subsequent contiguous numbered items at the same level.
        var idx = 1
        var i = blockIndex + 1
        val level = block.paragraph.listLevel
        while (i < content.size) {
            val b = content[i] as? OdfContentBlock.Paragraph ?: break
            if (b.paragraph.style != ParagraphStyle.LIST_ITEM || b.paragraph.listType != ListType.NUMBERED || b.paragraph.listLevel != level) break
            idx++
            content[i] = OdfContentBlock.Paragraph(b.paragraph.copy(listItemIndex = idx))
            i++
        }
        updateDocument(doc.copy(content = content))
    }

    /** Inserts an image (already-read bytes) after the given block (B14). */
    fun insertImage(blockIndex: Int, fileName: String, bytes: ByteArray) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val safeName = fileName.substringAfterLast('/').ifBlank { "image" }
        val path = "Pictures/$safeName"
        val content = doc.content.toMutableList()
        val at = (blockIndex + 1).coerceIn(0, content.size)
        content.add(at, OdfContentBlock.Image(OdfImage(path = path, imageData = bytes)))
        updateDocument(doc.copy(content = content, images = doc.images + (path to bytes)))
    }

    /** Inserts a horizontal rule (rendered as a bordered empty paragraph) (B19). */
    fun insertHorizontalLine(blockIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val at = (blockIndex + 1).coerceIn(0, content.size)
        content.add(at, OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = "")), borderColor = 0xFF888888L)))
        updateDocument(doc.copy(content = content))
    }

    /** Generates a Table of Contents from headings and inserts it at the top (B21). */
    fun insertTableOfContents() {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val headingStyles = setOf(ParagraphStyle.HEADING1, ParagraphStyle.HEADING2, ParagraphStyle.HEADING3, ParagraphStyle.HEADING4)
        val toc = mutableListOf<OdfContentBlock>()
        toc.add(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = "Table of Contents", bold = true)), style = ParagraphStyle.HEADING2)))
        for (block in doc.content) {
            val para = (block as? OdfContentBlock.Paragraph)?.paragraph ?: continue
            if (para.style !in headingStyles) continue
            val text = para.spans.joinToString("") { it.text }.trim()
            if (text.isEmpty()) continue
            val level = when (para.style) { ParagraphStyle.HEADING1 -> 1; ParagraphStyle.HEADING2 -> 2; ParagraphStyle.HEADING3 -> 3; else -> 4 }
            toc.add(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = text)), marginLeft = (level - 1) * 18f)))
        }
        if (toc.size <= 1) return
        toc.add(OdfContentBlock.PageBreak)
        val content = toc + doc.content
        updateDocument(doc.copy(content = content))
    }

    /** Inserts a footnote with a citation marker at the caret (B15). */
    fun insertFootnote(start: Int, endInclusive: Int, gPos: Int, body: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val citation = (doc.footnotes.size + 1).toString()
        val footnotes = doc.footnotes + OdfFootnote(citation, listOf(OdfParagraph(listOf(OdfSpan(text = body)))))
        // Insert the citation marker text at the caret, then attach the footnote.
        val paras = runParas(start, endInclusive)
        if (paras != null) {
            val full = paras.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
            val pos = gPos.coerceIn(0, full.length)
            val newText = full.substring(0, pos) + "[$citation]" + full.substring(pos)
            updateParagraphRun(start, endInclusive, newText)
        }
        val current = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        updateDocument(current.copy(footnotes = footnotes))
    }

    /** Appends a comment/annotation marker to a paragraph (B16). */
    fun insertComment(blockIndex: Int, author: String, text: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
        val annotation = OdfAnnotation(
            author = author.ifBlank { null },
            date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date()),
            paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = text))))
        )
        val newSpans = block.paragraph.spans + OdfSpan(text = " \uD83D\uDCDD ", annotation = annotation)
        content[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(spans = newSpans))
        updateDocument(doc.copy(content = content))
    }

    /** Sets header/footer text (in-session edit; B20). */
    fun setHeaderText(text: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val paras = if (text.isBlank()) emptyList() else text.split("\n").map { OdfParagraph(listOf(OdfSpan(text = it))) }
        updateDocument(doc.copy(headerParagraphs = paras))
    }

    fun setFooterText(text: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val paras = if (text.isBlank()) emptyList() else text.split("\n").map { OdfParagraph(listOf(OdfSpan(text = it))) }
        updateDocument(doc.copy(footerParagraphs = paras))
    }

    // --- Text-document table editing ---

    private fun withTextTable(blockIndex: Int, transform: (OdfTable) -> OdfTable) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Table ?: return
        val newContent = doc.content.toMutableList()
        newContent[blockIndex] = OdfContentBlock.Table(transform(block.table))
        updateDocument(doc.copy(content = newContent))
    }

    fun updateTextTableCell(blockIndex: Int, row: Int, col: Int, newText: String) = withTextTable(blockIndex) { table ->
        val rows = table.rows.toMutableList()
        val r = rows.getOrNull(row) ?: return@withTextTable table
        val cells = r.cells.toMutableList()
        if (col !in cells.indices) return@withTextTable table
        val paras = newText.split("\n").map { OdfParagraph(listOf(OdfSpan(text = it))) }
        cells[col] = cells[col].copy(paragraphs = paras)
        rows[row] = OdfTableRow(cells)
        table.copy(rows = rows)
    }

    fun textTableAddRow(blockIndex: Int, afterRow: Int) = withTextTable(blockIndex) { table ->
        val colCount = table.rows.firstOrNull()?.cells?.size ?: 1
        val newRow = OdfTableRow(List(colCount) { OdfTableCell(paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = ""))))) })
        val rows = table.rows.toMutableList()
        val at = (afterRow + 1).coerceIn(0, rows.size)
        rows.add(at, newRow)
        table.copy(rows = rows)
    }

    fun textTableAddColumn(blockIndex: Int, afterCol: Int) = withTextTable(blockIndex) { table ->
        table.copy(rows = table.rows.map { row ->
            val cells = row.cells.toMutableList()
            val at = (afterCol + 1).coerceIn(0, cells.size)
            cells.add(at, OdfTableCell(paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = ""))))))
            OdfTableRow(cells)
        })
    }

    fun textTableDeleteRow(blockIndex: Int, row: Int) = withTextTable(blockIndex) { table ->
        if (table.rows.size <= 1 || row !in table.rows.indices) table
        else table.copy(rows = table.rows.toMutableList().apply { removeAt(row) })
    }

    fun textTableDeleteColumn(blockIndex: Int, col: Int) = withTextTable(blockIndex) { table ->
        table.copy(rows = table.rows.map { row ->
            if (row.cells.size <= 1 || col !in row.cells.indices) row
            else OdfTableRow(row.cells.toMutableList().apply { removeAt(col) })
        })
    }

    /** Per-cell character formatting for text-document tables (C26). */
    fun setTextTableCellSpanFormat(blockIndex: Int, row: Int, col: Int, transform: (OdfSpan) -> OdfSpan) = withTextTable(blockIndex) { table ->
        val rows = table.rows.toMutableList()
        val r = rows.getOrNull(row) ?: return@withTextTable table
        val cells = r.cells.toMutableList()
        val cell = cells.getOrNull(col) ?: return@withTextTable table
        val newParas = cell.paragraphs.map { p -> p.copy(spans = p.spans.map(transform)) }
        cells[col] = cell.copy(paragraphs = newParas)
        rows[row] = OdfTableRow(cells)
        table.copy(rows = rows)
    }

    fun setTextTableCellAlignment(blockIndex: Int, row: Int, col: Int, alignment: androidx.compose.ui.text.style.TextAlign?) = withTextTable(blockIndex) { table ->
        val rows = table.rows.toMutableList()
        val r = rows.getOrNull(row) ?: return@withTextTable table
        val cells = r.cells.toMutableList()
        val cell = cells.getOrNull(col) ?: return@withTextTable table
        cells[col] = cell.copy(paragraphs = cell.paragraphs.map { it.copy(alignment = alignment) })
        rows[row] = OdfTableRow(cells)
        table.copy(rows = rows)
    }

    fun setTextTableCellBackground(blockIndex: Int, row: Int, col: Int, color: Long?) = withTextTable(blockIndex) { table ->
        val rows = table.rows.toMutableList()
        val r = rows.getOrNull(row) ?: return@withTextTable table
        val cells = r.cells.toMutableList()
        val cell = cells.getOrNull(col) ?: return@withTextTable table
        cells[col] = cell.copy(backgroundColor = color)
        rows[row] = OdfTableRow(cells)
        table.copy(rows = rows)
    }

    /** Merge a rectangular block of text-table cells (C27). */
    fun mergeTextTableCells(blockIndex: Int, startRow: Int, startCol: Int, endRow: Int, endCol: Int) = withTextTable(blockIndex) { table ->
        val rows = table.rows.toMutableList()
        val colSpan = endCol - startCol + 1
        val rowSpan = endRow - startRow + 1
        if (colSpan < 1 || rowSpan < 1) return@withTextTable table
        for (r in startRow..endRow) {
            val rr = rows.getOrNull(r) ?: continue
            val cells = rr.cells.toMutableList()
            for (c in startCol..endCol) {
                if (c !in cells.indices) continue
                cells[c] = if (r == startRow && c == startCol) cells[c].copy(colSpan = colSpan, rowSpan = rowSpan)
                else cells[c].copy(isCovered = true)
            }
            rows[r] = OdfTableRow(cells)
        }
        table.copy(rows = rows)
    }

    fun unmergeTextTableCells(blockIndex: Int, row: Int, col: Int) = withTextTable(blockIndex) { table ->
        val rows = table.rows.toMutableList()
        val cell = rows.getOrNull(row)?.cells?.getOrNull(col) ?: return@withTextTable table
        if (cell.colSpan <= 1 && cell.rowSpan <= 1) return@withTextTable table
        for (r in row until minOf(row + cell.rowSpan, rows.size)) {
            val cells = rows[r].cells.toMutableList()
            for (c in col until minOf(col + cell.colSpan, cells.size)) {
                cells[c] = if (r == row && c == col) cells[c].copy(colSpan = 1, rowSpan = 1) else cells[c].copy(isCovered = false)
            }
            rows[r] = OdfTableRow(cells)
        }
        table.copy(rows = rows)
    }

    fun insertChart(blockIndex: Int, chart: OdfChart) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val at = (blockIndex + 1).coerceIn(0, content.size)
        content.add(at, OdfContentBlock.Chart(chart))
        updateDocument(doc.copy(content = content))
    }

    fun updateChart(blockIndex: Int, chart: OdfChart) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        if (doc.content.getOrNull(blockIndex) !is OdfContentBlock.Chart) return
        val content = doc.content.toMutableList()
        content[blockIndex] = OdfContentBlock.Chart(chart)
        updateDocument(doc.copy(content = content))
    }

    fun addParagraphAfter(blockIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        content.add(blockIndex + 1, OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = "")))))
        updateDocument(doc.copy(content = content))
    }

    fun deleteParagraph(blockIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        if (doc.content.size <= 1) return
        val content = doc.content.toMutableList()
        content.removeAt(blockIndex)
        updateDocument(doc.copy(content = content))
    }

    fun toggleBold(blockIndex: Int) = toggleSpanFormat(blockIndex) { it.copy(bold = !it.bold) }
    fun toggleItalic(blockIndex: Int) = toggleSpanFormat(blockIndex) { it.copy(italic = !it.italic) }
    fun toggleUnderline(blockIndex: Int) = toggleSpanFormat(blockIndex) { it.copy(underline = !it.underline) }
    fun toggleStrikethrough(blockIndex: Int) = toggleSpanFormat(blockIndex) { it.copy(strikethrough = !it.strikethrough) }

    fun setSpanColor(blockIndex: Int, color: Long?) = toggleSpanFormat(blockIndex) { it.copy(color = color) }
    fun setSpanFontSize(blockIndex: Int, size: Float) = toggleSpanFormat(blockIndex) { it.copy(fontSize = size) }

    fun setParagraphStyle(blockIndex: Int, style: ParagraphStyle) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
        content[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(style = style))
        updateDocument(doc.copy(content = content))
    }

    fun setParagraphAlignment(blockIndex: Int, alignment: androidx.compose.ui.text.style.TextAlign?) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
        content[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(alignment = alignment))
        updateDocument(doc.copy(content = content))
    }

    fun replaceInDocument(search: String, replacement: String, replaceAll: Boolean, matchCase: Boolean = false, wholeWord: Boolean = false): Int {
        if (search.isEmpty()) return 0
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return 0
        val opts = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val boundary = if (wholeWord) "\\b" else ""
        val pattern = try { Regex(boundary + Regex.escape(search) + boundary, opts) } catch (_: Exception) { return 0 }
        val content = doc.content.toMutableList()
        var count = 0
        for (i in content.indices) {
            val block = content[i] as? OdfContentBlock.Paragraph ?: continue
            val para = block.paragraph
            var changed = false
            val newSpans = para.spans.map { span ->
                if (!replaceAll && count > 0) return@map span
                val matches = pattern.findAll(span.text).toList()
                if (matches.isEmpty()) return@map span
                changed = true
                if (replaceAll) {
                    count += matches.size
                    span.copy(text = pattern.replace(span.text) { replacement })
                } else {
                    val m = matches.first()
                    count += 1
                    span.copy(text = span.text.substring(0, m.range.first) + replacement + span.text.substring(m.range.last + 1))
                }
            }
            if (changed) content[i] = OdfContentBlock.Paragraph(para.copy(spans = newSpans))
        }
        if (count > 0) updateDocument(doc.copy(content = content))
        return count
    }

    /** Returns the content block indices that contain a match for the query (B23 find-next navigation). */
    fun findMatchBlocks(search: String, matchCase: Boolean = false, wholeWord: Boolean = false): List<Int> {
        if (search.isEmpty()) return emptyList()
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return emptyList()
        val opts = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val boundary = if (wholeWord) "\\b" else ""
        val pattern = try { Regex(boundary + Regex.escape(search) + boundary, opts) } catch (_: Exception) { return emptyList() }
        val result = mutableListOf<Int>()
        doc.content.forEachIndexed { i, block ->
            if (block is OdfContentBlock.Paragraph && block.paragraph.spans.any { pattern.containsMatchIn(it.text) }) result.add(i)
        }
        return result
    }

    fun duplicateParagraph(blockIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
        content.add(blockIndex + 1, OdfContentBlock.Paragraph(block.paragraph.copy()))
        updateDocument(doc.copy(content = content))
    }

    fun moveParagraphUp(blockIndex: Int) {
        if (blockIndex <= 0) return
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val item = content.removeAt(blockIndex)
        content.add(blockIndex - 1, item)
        updateDocument(doc.copy(content = content))
    }

    fun moveParagraphDown(blockIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        if (blockIndex >= doc.content.size - 1) return
        val content = doc.content.toMutableList()
        val item = content.removeAt(blockIndex)
        content.add(blockIndex + 1, item)
        updateDocument(doc.copy(content = content))
    }

    fun toggleListItem(blockIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
        val para = block.paragraph
        val newPara = if (para.style == ParagraphStyle.LIST_ITEM) {
            para.copy(style = ParagraphStyle.BODY, listLevel = 0)
        } else {
            para.copy(style = ParagraphStyle.LIST_ITEM, listLevel = 1, listType = ListType.BULLET, listItemIndex = 1)
        }
        content[blockIndex] = OdfContentBlock.Paragraph(newPara)
        updateDocument(doc.copy(content = content))
    }

    fun toggleNumberedList(blockIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
        val para = block.paragraph
        val newPara = if (para.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.NUMBERED) {
            para.copy(style = ParagraphStyle.BODY, listLevel = 0)
        } else {
            para.copy(style = ParagraphStyle.LIST_ITEM, listLevel = 1, listType = ListType.NUMBERED, listItemIndex = 1)
        }
        content[blockIndex] = OdfContentBlock.Paragraph(newPara)
        updateDocument(doc.copy(content = content))
    }

    fun indentParagraph(blockIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
        content[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(marginLeft = block.paragraph.marginLeft + 24f))
        updateDocument(doc.copy(content = content))
    }

    fun outdentParagraph(blockIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
        content[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(marginLeft = maxOf(0f, block.paragraph.marginLeft - 24f)))
        updateDocument(doc.copy(content = content))
    }

    fun insertTable(blockIndex: Int, rows: Int, cols: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val tableRows = (0 until rows).map { OdfTableRow((0 until cols).map { OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan(text = ""))))) }) }
        content.add(blockIndex + 1, OdfContentBlock.Table(OdfTable("Table", emptyList(), tableRows)))
        updateDocument(doc.copy(content = content))
    }

    fun insertPageBreak(blockIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        content.add(blockIndex + 1, OdfContentBlock.PageBreak)
        updateDocument(doc.copy(content = content))
    }

    fun insertHyperlink(blockIndex: Int, text: String, url: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val linkSpan = OdfSpan(text = text, href = url, underline = true, color = 0xFF0066CCL)
        content.add(blockIndex + 1, OdfContentBlock.Paragraph(OdfParagraph(listOf(linkSpan))))
        updateDocument(doc.copy(content = content))
    }

    fun addBookmark(name: String, contentIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val bookmarks = doc.bookmarks.toMutableList()
        bookmarks.add(OdfBookmark(name, contentIndex))
        updateDocument(doc.copy(bookmarks = bookmarks))
    }

    fun removeBookmark(name: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val bookmarks = doc.bookmarks.filter { it.name != name }
        updateDocument(doc.copy(bookmarks = bookmarks))
    }

    /** Edit document metadata (G47). */
    fun updateMetadata(transform: (OdfMetadata) -> OdfMetadata) {
        val doc = (_state.value as? ViewState.Loaded)?.document ?: return
        val newDoc = when (doc) {
            is OdfDocument.TextDocument -> doc.copy(metadata = transform(doc.metadata))
            is OdfDocument.Spreadsheet -> doc.copy(metadata = transform(doc.metadata))
            is OdfDocument.Presentation -> doc.copy(metadata = transform(doc.metadata))
            is OdfDocument.Drawing -> doc.copy(metadata = transform(doc.metadata))
        }
        updateDocument(newDoc)
    }

    private fun toggleSpanFormat(blockIndex: Int, transform: (OdfSpan) -> OdfSpan) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
        val updatedSpans = block.paragraph.spans.map(transform)
        content[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(spans = updatedSpans))
        updateDocument(doc.copy(content = content))
    }

    // --- Spreadsheet editing ---

    fun updateCellText(sheetIndex: Int, rowIndex: Int, cellIndex: Int, newText: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets[sheetIndex]
        val rows = sheet.rows.toMutableList()
        val row = rows[rowIndex]
        val cells = row.cells.toMutableList()
        cells[cellIndex] = if (newText.startsWith("=")) {
            // Typed formula (H49): store as OpenFormula, drop cached numeric value.
            cells[cellIndex].copy(text = newText, formula = newText, valueType = "float", numberValue = null)
        } else {
            cells[cellIndex].copy(text = newText, formula = null,
                valueType = if (newText.toDoubleOrNull() != null) "float" else "string",
                numberValue = newText.toDoubleOrNull())
        }
        rows[rowIndex] = OdfRow(cells)
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun addRow(sheetIndex: Int, afterRowIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets[sheetIndex]
        val rows = sheet.rows.toMutableList()
        val colCount = rows.getOrNull(afterRowIndex)?.cells?.size ?: 1
        rows.add(afterRowIndex + 1, OdfRow(List(colCount) { OdfCell(text = "") }))
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun addColumn(sheetIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets[sheetIndex]
        val rows = sheet.rows.map { row -> OdfRow(row.cells + OdfCell(text = "")) }
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun deleteRow(sheetIndex: Int, rowIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets[sheetIndex]
        if (sheet.rows.size <= 1) return
        val rows = sheet.rows.toMutableList()
        rows.removeAt(rowIndex)
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun deleteColumn(sheetIndex: Int, colIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets[sheetIndex]
        val rows = sheet.rows.map { row ->
            val cells = row.cells.toMutableList()
            if (colIndex < cells.size && cells.size > 1) cells.removeAt(colIndex)
            OdfRow(cells)
        }
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun renameSheet(sheetIndex: Int, newName: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        sheets[sheetIndex] = sheets[sheetIndex].copy(name = newName)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun addSheet() {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val rows = (0 until 10).map { OdfRow(List(5) { OdfCell(text = "") }) }
        sheets.add(OdfSheet("Sheet ${sheets.size + 1}", rows))
        updateDocument(doc.copy(sheets = sheets))
    }

    fun deleteSheet(sheetIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        if (doc.sheets.size <= 1) return
        val sheets = doc.sheets.toMutableList()
        sheets.removeAt(sheetIndex)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun setCellBold(sheetIndex: Int, rowIndex: Int, cellIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(bold = !it.bold) }
    }

    fun setCellItalic(sheetIndex: Int, rowIndex: Int, cellIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(italic = !it.italic) }
    }

    fun setCellColor(sheetIndex: Int, rowIndex: Int, cellIndex: Int, color: Long?) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(textColor = color) }
    }

    fun setCellBgColor(sheetIndex: Int, rowIndex: Int, cellIndex: Int, color: Long?) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(backgroundColor = color) }
    }

    fun setCellAlignment(sheetIndex: Int, rowIndex: Int, cellIndex: Int, alignment: androidx.compose.ui.text.style.TextAlign?) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(alignment = alignment) }
    }

    fun mergeCells(sheetIndex: Int, startRow: Int, startCol: Int, endRow: Int, endCol: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets[sheetIndex]
        val rows = sheet.rows.toMutableList()
        val colSpan = endCol - startCol + 1
        val rowSpan = endRow - startRow + 1
        for (r in startRow..endRow) {
            if (r >= rows.size) continue
            val cells = rows[r].cells.toMutableList()
            for (c in startCol..endCol) {
                if (c >= cells.size) continue
                cells[c] = if (r == startRow && c == startCol) {
                    cells[c].copy(spannedColumns = colSpan, rowSpan = rowSpan)
                } else {
                    cells[c].copy(isCovered = true)
                }
            }
            rows[r] = OdfRow(cells)
        }
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun unmergeCells(sheetIndex: Int, rowIndex: Int, cellIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets[sheetIndex]
        val rows = sheet.rows.toMutableList()
        val cell = rows.getOrNull(rowIndex)?.cells?.getOrNull(cellIndex) ?: return
        if (cell.spannedColumns <= 1 && cell.rowSpan <= 1) return
        for (r in rowIndex until minOf(rowIndex + cell.rowSpan, rows.size)) {
            val cells = rows[r].cells.toMutableList()
            for (c in cellIndex until minOf(cellIndex + cell.spannedColumns, cells.size)) {
                cells[c] = if (r == rowIndex && c == cellIndex) cells[c].copy(spannedColumns = 1, rowSpan = 1) else cells[c].copy(isCovered = false)
            }
            rows[r] = OdfRow(cells)
        }
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun sortRows(sheetIndex: Int, colIndex: Int, ascending: Boolean) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets[sheetIndex]
        val sorted = sheet.rows.sortedWith(compareBy<OdfRow> {
            val text = it.cells.getOrNull(colIndex)?.text ?: ""
            text.toDoubleOrNull() ?: Double.MAX_VALUE
        }.thenBy { it.cells.getOrNull(colIndex)?.text ?: "" })
        sheets[sheetIndex] = sheet.copy(rows = if (ascending) sorted else sorted.reversed())
        updateDocument(doc.copy(sheets = sheets))
    }

    private fun modifyCell(doc: OdfDocument.Spreadsheet, sheetIndex: Int, rowIndex: Int, cellIndex: Int, transform: (OdfCell) -> OdfCell) {
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets[sheetIndex]
        val rows = sheet.rows.toMutableList()
        val cells = rows[rowIndex].cells.toMutableList()
        cells[cellIndex] = transform(cells[cellIndex])
        rows[rowIndex] = OdfRow(cells)
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    // --- Presentation editing ---

    fun addSlide(afterIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        slides.add(afterIndex + 1, OdfSlide(
            name = "Slide ${slides.size + 1}",
            elements = listOf(OdfSlideElement.Frame(OdfFrame(
                x = 50f, y = 50f, width = 600f, height = 100f,
                paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = "New Slide", bold = true, fontSize = 28f))))
            )))
        ))
        updateDocument(doc.copy(slides = slides))
    }

    fun deleteSlide(index: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        if (doc.slides.size <= 1) return
        val slides = doc.slides.toMutableList()
        slides.removeAt(index)
        updateDocument(doc.copy(slides = slides))
    }

    fun duplicateSlide(index: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        slides.add(index + 1, slides[index].copy(name = "${slides[index].name} (copy)"))
        updateDocument(doc.copy(slides = slides))
    }

    fun moveSlideUp(index: Int) {
        if (index <= 0) return
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val item = slides.removeAt(index)
        slides.add(index - 1, item)
        updateDocument(doc.copy(slides = slides))
    }

    fun moveSlideDown(index: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        if (index >= doc.slides.size - 1) return
        val slides = doc.slides.toMutableList()
        val item = slides.removeAt(index)
        slides.add(index + 1, item)
        updateDocument(doc.copy(slides = slides))
    }

    /** Edits the text of a slide element (frame or shape), preserving the first span's formatting (I62). */
    fun updateSlideElementText(slideIndex: Int, elementIndex: Int, newText: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val elements = slide.elements.toMutableList()
        val el = elements.getOrNull(elementIndex) ?: return
        fun rebuild(old: List<OdfParagraph>): List<OdfParagraph> {
            val template = old.firstOrNull()?.spans?.firstOrNull()?.copy(text = "") ?: OdfSpan(text = "")
            val style = old.firstOrNull()?.style ?: ParagraphStyle.BODY
            return newText.split("\n").map { line -> OdfParagraph(listOf(template.copy(text = line)), style = style) }
        }
        elements[elementIndex] = when (el) {
            is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(paragraphs = rebuild(el.frame.paragraphs)))
            is OdfSlideElement.Shape -> {
                val s = el.shape
                val t = rebuild(s.text)
                OdfSlideElement.Shape(when (s) {
                    is OdfShape.Rect -> s.copy(text = t)
                    is OdfShape.Ellipse -> s.copy(text = t)
                    is OdfShape.Line -> s.copy(text = t)
                    is OdfShape.CustomShape -> s.copy(text = t)
                })
            }
        }
        slides[slideIndex] = slide.copy(elements = elements)
        updateDocument(doc.copy(slides = slides))
    }

    /** Adds an empty text box to a slide (I62). */
    fun addTextBoxToSlide(slideIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val frame = OdfFrame(
            x = 60f, y = 200f + slide.elements.size * 20f, width = 600f, height = 80f,
            paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = "New text", fontSize = 20f))))
        )
        slides[slideIndex] = slide.copy(elements = slide.elements + OdfSlideElement.Frame(frame))
        updateDocument(doc.copy(slides = slides))
    }

    private fun elementParas(el: OdfSlideElement): List<OdfParagraph> = when (el) {
        is OdfSlideElement.Frame -> el.frame.paragraphs
        is OdfSlideElement.Shape -> el.shape.text
    }

    private fun mutateSlideElementSpans(slideIndex: Int, elementIndex: Int, transform: (OdfSpan) -> OdfSpan) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val elements = slide.elements.toMutableList()
        val el = elements.getOrNull(elementIndex) ?: return
        fun map(ps: List<OdfParagraph>) = ps.map { p -> p.copy(spans = p.spans.map(transform)) }
        elements[elementIndex] = when (el) {
            is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(paragraphs = map(el.frame.paragraphs)))
            is OdfSlideElement.Shape -> {
                val s = el.shape; val t = map(s.text)
                OdfSlideElement.Shape(when (s) {
                    is OdfShape.Rect -> s.copy(text = t); is OdfShape.Ellipse -> s.copy(text = t)
                    is OdfShape.Line -> s.copy(text = t); is OdfShape.CustomShape -> s.copy(text = t)
                })
            }
        }
        slides[slideIndex] = slide.copy(elements = elements)
        updateDocument(doc.copy(slides = slides))
    }

    private fun firstSpan(slideIndex: Int, elementIndex: Int): OdfSpan? {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return null
        val el = doc.slides.getOrNull(slideIndex)?.elements?.getOrNull(elementIndex) ?: return null
        return elementParas(el).firstOrNull()?.spans?.firstOrNull()
    }

    fun toggleSlideElementBold(s: Int, e: Int) { val cur = firstSpan(s, e)?.bold == true; mutateSlideElementSpans(s, e) { it.copy(bold = !cur) } }
    fun toggleSlideElementItalic(s: Int, e: Int) { val cur = firstSpan(s, e)?.italic == true; mutateSlideElementSpans(s, e) { it.copy(italic = !cur) } }
    fun toggleSlideElementUnderline(s: Int, e: Int) { val cur = firstSpan(s, e)?.underline == true; mutateSlideElementSpans(s, e) { it.copy(underline = !cur) } }
    fun setSlideElementColor(s: Int, e: Int, color: Long?) { mutateSlideElementSpans(s, e) { it.copy(color = color) } }

    /** Sets paragraph alignment for all paragraphs in a slide element. */
    fun setSlideElementAlignment(slideIndex: Int, elementIndex: Int, alignment: androidx.compose.ui.text.style.TextAlign?) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val elements = slide.elements.toMutableList()
        val el = elements.getOrNull(elementIndex) ?: return
        fun map(ps: List<OdfParagraph>) = ps.map { it.copy(alignment = alignment) }
        elements[elementIndex] = when (el) {
            is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(paragraphs = map(el.frame.paragraphs)))
            is OdfSlideElement.Shape -> {
                val sh = el.shape; val t = map(sh.text)
                OdfSlideElement.Shape(when (sh) {
                    is OdfShape.Rect -> sh.copy(text = t); is OdfShape.Ellipse -> sh.copy(text = t)
                    is OdfShape.Line -> sh.copy(text = t); is OdfShape.CustomShape -> sh.copy(text = t)
                })
            }
        }
        slides[slideIndex] = slide.copy(elements = elements)
        updateDocument(doc.copy(slides = slides))
    }

    // --- CSV export ---

    fun exportCsv(): String {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return ""
        val sb = StringBuilder()
        val sheet = doc.sheets.firstOrNull() ?: return ""
        for (row in sheet.rows) {
            sb.appendLine(row.cells.joinToString(",") { cell ->
                val text = cell.text
                if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
                    "\"${text.replace("\"", "\"\"")}\""
                } else text
            })
        }
        return sb.toString()
    }

    // --- Text export ---

    /** Flat ODF (.fodt/.fods/.fodp) export (K75). */
    fun exportFlat(): String {
        val doc = (_state.value as? ViewState.Loaded)?.document ?: return ""
        return OdfSerializer.serializeFlat(doc)
    }

    fun exportAsPlainText(): String {
        val doc = (_state.value as? ViewState.Loaded)?.document ?: return ""
        val sb = StringBuilder()
        when (doc) {
            is OdfDocument.TextDocument -> {
                for (block in doc.content) {
                    when (block) {
                        is OdfContentBlock.Paragraph -> sb.appendLine(block.paragraph.spans.joinToString("") { it.text })
                        is OdfContentBlock.Table -> {
                            for (row in block.table.rows)
                                sb.appendLine(row.cells.filterNot { it.isCovered }.joinToString("\t") { cell -> cell.paragraphs.joinToString(" ") { p -> p.spans.joinToString("") { it.text } } })
                        }
                        is OdfContentBlock.PageBreak -> sb.appendLine("---")
                        is OdfContentBlock.Image -> sb.appendLine("[Image]")
                        is OdfContentBlock.Chart -> sb.appendLine("[Chart]")
                        is OdfContentBlock.Formula -> sb.appendLine("[Formula] " + OdfMath.parse(block.mathml)?.let { OdfMath.toText(it) }.orEmpty())
                    }
                }
            }
            is OdfDocument.Spreadsheet -> {
                for (sheet in doc.sheets) {
                    sb.appendLine("=== ${sheet.name} ===")
                    for (row in sheet.rows) sb.appendLine(row.cells.filterNot { it.isCovered }.joinToString("\t") { it.text })
                    sb.appendLine()
                }
            }
            is OdfDocument.Presentation -> {
                for (slide in doc.slides) {
                    sb.appendLine("=== ${slide.name} ===")
                    for (el in slide.elements) when (el) {
                        is OdfSlideElement.Frame -> for (p in el.frame.paragraphs) sb.appendLine(p.spans.joinToString("") { it.text })
                        is OdfSlideElement.Shape -> for (p in el.shape.text) sb.appendLine(p.spans.joinToString("") { it.text })
                    }
                    sb.appendLine()
                }
            }
            is OdfDocument.Drawing -> {
                for (page in doc.pages) {
                    sb.appendLine("=== ${page.name} ===")
                    for (el in page.elements) when (el) {
                        is OdfSlideElement.Frame -> for (p in el.frame.paragraphs) sb.appendLine(p.spans.joinToString("") { it.text })
                        is OdfSlideElement.Shape -> for (p in el.shape.text) sb.appendLine(p.spans.joinToString("") { it.text })
                    }
                    sb.appendLine()
                }
            }
        }
        return sb.toString()
    }

    // --- Save ---

    fun save(targetUri: Uri? = null) {
        val doc = (_state.value as? ViewState.Loaded)?.document ?: return
        val source = documentUri ?: return
        val target = targetUri ?: source
        _isSaving.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                OdfWriter.save(getApplication(), source, doc, target)
                _hasUnsavedChanges.value = false
                if (targetUri != null) documentUri = targetUri
                launch(Dispatchers.Main) { Toast.makeText(getApplication(), "Saved", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { Toast.makeText(getApplication(), "Save failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            } finally {
                _isSaving.value = false
            }
        }
    }

    sealed class ViewState {
        data object Empty : ViewState()
        data object Loading : ViewState()
        data class Loaded(val document: OdfDocument) : ViewState()
        data class Error(val message: String) : ViewState()
    }

    companion object {
        private const val MAX_UNDO = 30
        private const val MAX_RECENT = 20
    }
}
