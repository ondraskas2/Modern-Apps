package com.vayunmathur.pdf.util

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.pdf.EditablePdfDocument
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.SandboxedPdfLoader
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.model.CapturedImage
import com.vayunmathur.pdf.model.Quadrilateral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OutlineEntry(val label: String, val pageNum: Int)

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private val pdfLoader = SandboxedPdfLoader(application)

    // --- Captured images / cropping ----------------------------------------

    private val _capturedImages = MutableStateFlow<List<CapturedImage>>(emptyList())
    val capturedImages: StateFlow<List<CapturedImage>> = _capturedImages.asStateFlow()

    fun addCapturedImage(uri: Uri): Int {
        val updated = _capturedImages.value + CapturedImage(uri)
        _capturedImages.value = updated
        return updated.lastIndex
    }

    fun removeCapturedImage(index: Int) {
        val current = _capturedImages.value
        if (index !in current.indices) return
        _capturedImages.value = current.toMutableList().also { it.removeAt(index) }
    }

    fun moveCapturedImage(from: Int, to: Int) {
        val current = _capturedImages.value
        if (from !in current.indices || to !in current.indices) return
        _capturedImages.value = current.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    fun updateQuadrilateral(index: Int, quadrilateral: Quadrilateral) {
        val current = _capturedImages.value
        if (index !in current.indices) return
        _capturedImages.value = current.toMutableList().also {
            it[index] = it[index].copy(quadrilateral = quadrilateral, cropRect = quadrilateral.toBoundingRect())
        }
    }

    // --- PDF export from captured images -----------------------------------

    data class PdfWriteResult(val targetUri: Uri, val success: Boolean)

    private val _pdfWriteResults = MutableSharedFlow<PdfWriteResult>(extraBufferCapacity = 1)
    val pdfWriteResults: SharedFlow<PdfWriteResult> = _pdfWriteResults.asSharedFlow()

    fun exportCapturedPdf(targetUri: Uri) {
        val snapshot = _capturedImages.value
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val ok = savePdfToUri(ctx, snapshot, targetUri)
            _pdfWriteResults.emit(PdfWriteResult(targetUri, ok))
        }
    }

    fun saveDocumentChanges(document: EditablePdfDocument, targetUri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openFileDescriptor(targetUri, "w")?.use { pfd ->
                        document.createWriteHandle().writeTo(pfd)
                    }
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving PDF to $targetUri", e)
                    false
                }
            }
            _pdfWriteResults.emit(PdfWriteResult(targetUri, ok))
        }
    }

    fun clearCapturedImages() {
        _capturedImages.value = emptyList()
    }

    // --- Document loading ---------------------------------------------------

    private val _pdfDocument = MutableStateFlow<EditablePdfDocument?>(null)
    val pdfDocument: StateFlow<EditablePdfDocument?> = _pdfDocument.asStateFlow()

    private val _passwordRequired = MutableStateFlow(false)
    val passwordRequired: StateFlow<Boolean> = _passwordRequired.asStateFlow()

    private val _passwordError = MutableStateFlow<String?>(null)
    val passwordError: StateFlow<String?> = _passwordError.asStateFlow()

    fun loadDocument(uri: Uri, password: String?) {
        val ctx = getApplication<Application>()
        _passwordError.value = null
        viewModelScope.launch {
            try {
                val doc = pdfLoader.openDocument(uri, password) as EditablePdfDocument
                _pdfDocument.value = doc
                _passwordRequired.value = false
                _passwordError.value = null
            } catch (_: PdfPasswordException) {
                if (password != null) {
                    _passwordError.value = ctx.getString(R.string.incorrect_password)
                }
                _passwordRequired.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open PDF $uri", e)
            }
        }
    }

    fun clearDocument() {
        _pdfDocument.value = null
        _passwordRequired.value = false
        _passwordError.value = null
        _linkDestinations.value = emptyMap()
        _outlineEntries.value = emptyList()
    }

    // --- Internal link resolution -------------------------------------------

    private val _linkDestinations = MutableStateFlow<Map<String, Int>>(emptyMap())
    val linkDestinations: StateFlow<Map<String, Int>> = _linkDestinations.asStateFlow()

    fun buildLinkIndex(document: EditablePdfDocument) {
        viewModelScope.launch(Dispatchers.Default) {
            val map = mutableMapOf<String, Int>()
            for (page in 0 until document.pageCount) {
                val links = try {
                    document.getPageLinks(page)
                } catch (e: Exception) {
                    continue
                }
                val gotos = links.gotoLinks.filter { it.bounds.isNotEmpty() }
                val externals = links.externalLinks.filter {
                    it.bounds.isNotEmpty() && it.uri.scheme == "file"
                }
                if (gotos.isEmpty() || externals.isEmpty()) continue

                for (ext in externals) {
                    val extY = ext.bounds.first().centerY()
                    val match = gotos.minByOrNull {
                        kotlin.math.abs(it.bounds.first().centerY() - extY)
                    } ?: continue
                    if (kotlin.math.abs(match.bounds.first().centerY() - extY) < 20f) {
                        map[ext.uri.toString()] = match.destination.pageNumber
                    }
                }
            }
            Log.d(TAG, "Link index: ${map.size} entries")
            for ((uri, dest) in map) {
                Log.d(TAG, "  $uri -> page $dest")
            }
            _linkDestinations.value = map
        }
    }

    // --- Outline ------------------------------------------------------------

    private val _outlineEntries = MutableStateFlow<List<OutlineEntry>>(emptyList())
    val outlineEntries: StateFlow<List<OutlineEntry>> = _outlineEntries.asStateFlow()

    fun buildOutline(document: PdfDocument) {
        viewModelScope.launch(Dispatchers.Default) {
            val entries = mutableListOf<OutlineEntry>()
            for (page in 0 until document.pageCount) {
                try {
                    val content = document.getPageContent(page)
                    val firstText = content?.textContents?.firstOrNull()?.text
                    val firstLine = firstText?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
                    val label = if (!firstLine.isNullOrEmpty()) {
                        firstLine.take(80)
                    } else {
                        "Page ${page + 1}"
                    }
                    entries.add(OutlineEntry(label, page))
                } catch (e: Exception) {
                    entries.add(OutlineEntry("Page ${page + 1}", page))
                }
            }
            _outlineEntries.value = entries
        }
    }

    companion object {
        private const val TAG = "PdfViewModel"
    }
}
