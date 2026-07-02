package com.vayunmathur.photos.util

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.net.toUri
import com.vayunmathur.library.util.SerializedStroke
import com.vayunmathur.photos.data.AdjustmentLayer
import com.vayunmathur.photos.data.BasicAdjustment
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.Layer
import com.vayunmathur.photos.data.LayerAdjustment
import com.vayunmathur.photos.data.LayerBlendMode
import com.vayunmathur.photos.data.LayerMask
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDao
import com.vayunmathur.photos.data.Selection
import com.vayunmathur.photos.data.TextElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Document-centric editor view model. The single source of truth is [document]; the
 * compositor renders a high-res [compositedPreview] (up to 2048px, matching the decode
 * size so the on-screen image stays sharp) and a full-res bitmap on export. Undo/redo
 * snapshots whole [EditDocument]s.
 *
 * Drawing (ink) strokes and text overlays remain screen-local state and are baked onto
 * the composite at save time (see [savePhoto]).
 */
class PhotoEditViewModel(
    application: Application,
    private val photoDao: PhotoDao,
) : AndroidViewModel(application) {

    private val compositor = LayerCompositor()
    private val history = UndoRedoManager<EditDocument>()

    /** The photo being edited, resolved from the DB (or a stand-in built from a raw uri). */
    private val _photo = MutableStateFlow<Photo?>(null)
    val photo: StateFlow<Photo?> = _photo.asStateFlow()
    private var photoJob: Job? = null

    /** Loads the [id]'s photo from the DB, falling back to a stand-in built from [initialUri]. */
    fun loadPhoto(id: Long, initialUri: String?) {
        photoJob?.cancel()
        photoJob = viewModelScope.launch {
            photoDao.getByIdFlow(id).collect { fromDb ->
                _photo.value = fromDb ?: initialUri?.let { uri ->
                    Photo(
                        id = 0, name = uri.substringAfterLast("/"), uri = uri,
                        date = System.currentTimeMillis(), width = 0, height = 0,
                        dateModified = System.currentTimeMillis() / 1000, exifSet = false,
                        lat = null, long = null, videoData = null,
                    )
                }
            }
        }
    }

    private val _document = MutableStateFlow(EditDocument())
    val document: StateFlow<EditDocument> = _document.asStateFlow()

    private val _compositedPreview = MutableStateFlow<Bitmap?>(null)
    val compositedPreview: StateFlow<Bitmap?> = _compositedPreview.asStateFlow()

    /** The background (bottom) pixel-layer bitmap, used for filter thumbnails. */
    private val _baseBitmap = MutableStateFlow<Bitmap?>(null)
    val baseBitmap: StateFlow<Bitmap?> = _baseBitmap.asStateFlow()

    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /** When true the preview is rendered without the document crop so crop handles work. */
    private var croppingPreview = false

    private val _writePermissionRequest = MutableStateFlow<IntentSender?>(null)
    val writePermissionRequest: StateFlow<IntentSender?> = _writePermissionRequest.asStateFlow()

    private var pendingSaveBytes: ByteArray? = null
    private var pendingSaveUri: Uri? = null
    private var pendingSaveOnComplete: (() -> Unit)? = null

    private var previewJob: Job? = null
    private var lastDecodedUri: String? = null

    val activeLayer: Layer? get() = _document.value.activeLayer
    val activeAdjustment: LayerAdjustment?
        get() = (_document.value.activeLayer as? AdjustmentLayer)?.adjustment

    // --- decode ---------------------------------------------------------------

    fun decode(uri: Uri) {
        val uriStr = uri.toString()
        if (uriStr == lastDecodedUri && _document.value.layers.isNotEmpty()) return
        val ctx: Context = getApplication()
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val previousBase = _baseBitmap.value
                val source = android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
                val bmp = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val target = 2048
                    if (w > target || h > target) {
                        val scale = target.toFloat() / maxOf(w, h)
                        decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
                    }
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp
                else bmp.copy(Bitmap.Config.ARGB_8888, true)
                lastDecodedUri = uriStr
                _baseBitmap.value = argb
                previousBase?.recycle()
                history.clear()
                _canUndo.value = false
                _canRedo.value = false
                compositor.invalidateCache()
                _document.value = EditDocument.fromBackground(
                    BitmapReference(argb), argb.width, argb.height,
                )
                requestPreviewUpdate(immediate = true)
            } catch (e: Exception) {
                Log.e(TAG, "decode failed for $uri", e)
            }
        }
    }

    // --- document mutation ----------------------------------------------------

    fun updateDocument(pushUndo: Boolean = true, transform: (EditDocument) -> EditDocument) {
        val prev = _document.value
        val next = transform(prev)
        if (next === prev) return
        if (pushUndo) {
            history.push(prev)
            _canUndo.value = history.canUndo
            _canRedo.value = history.canRedo
        }
        compositor.invalidateCache()
        _document.value = next
        requestPreviewUpdate()
    }

    fun undo() {
        val current = _document.value
        val prev = history.undo(current) ?: return
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
        compositor.invalidateCache()
        _document.value = prev
        requestPreviewUpdate()
    }

    fun redo() {
        val current = _document.value
        val next = history.redo(current) ?: return
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
        compositor.invalidateCache()
        _document.value = next
        requestPreviewUpdate()
    }

    // --- layer operations -----------------------------------------------------

    fun setActiveLayer(index: Int) = updateDocument(pushUndo = false) { it.setActiveLayer(index) }
    fun moveLayer(from: Int, to: Int) = updateDocument { it.moveLayer(from, to) }
    fun removeLayer(index: Int) = updateDocument { it.removeLayer(index) }
    fun duplicateLayer(index: Int) = updateDocument { it.duplicateLayer(index) }
    fun mergeDown(index: Int) = updateDocument { compositor.mergeDown(it, index) }
    fun flatten() = updateDocument { compositor.flatten(it) }

    fun setLayerVisibility(index: Int, visible: Boolean) =
        updateDocument { it.updateLayer(index) { l -> l.copyBase(visible = visible) } }

    fun setLayerOpacity(index: Int, opacity: Float) =
        updateDocument { it.updateLayer(index) { l -> l.copyBase(opacity = opacity.coerceIn(0f, 1f)) } }

    fun setLayerBlendMode(index: Int, mode: LayerBlendMode) =
        updateDocument { it.updateLayer(index) { l -> l.copyBase(blendMode = mode) } }

    fun renameLayer(index: Int, name: String) =
        updateDocument { it.updateLayer(index) { l -> l.copyBase(name = name) } }

    fun addAdjustmentLayer(adjustment: LayerAdjustment) =
        updateDocument { it.addLayer(withActiveSelectionMask(AdjustmentLayer(adjustment))) }

    fun addEmptyPixelLayer() {
        val doc = _document.value
        val w = doc.canvasWidth.coerceAtLeast(1)
        val h = doc.canvasHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        updateDocument { it.addLayer(PixelLayer(BitmapReference(bmp), name = "Layer")) }
    }

    // --- layer masks ----------------------------------------------------------

    fun deleteLayerMask(index: Int) =
        updateDocument { it.updateLayer(index) { l -> l.copyBase(mask = null) } }

    fun invertLayerMask(index: Int) =
        updateDocument {
            it.updateLayer(index) { l -> l.copyBase(mask = l.mask?.invert() ?: l.mask) }
        }

    fun setLayerMask(index: Int, mask: LayerMask) =
        updateDocument { it.updateLayer(index) { l -> l.copyBase(mask = mask) } }

    // --- adjustment editing ---------------------------------------------------

    /** Ensures the active layer is an [AdjustmentLayer] whose adjustment satisfies [match]. */
    fun ensureAdjustment(match: (LayerAdjustment) -> Boolean, create: () -> LayerAdjustment) {
        updateDocument(pushUndo = false) { doc ->
            val idx = doc.layers.indexOfLast { it is AdjustmentLayer && match(it.adjustment) }
            if (idx >= 0) doc.setActiveLayer(idx)
            else doc.addLayer(withActiveSelectionMask(AdjustmentLayer(create())))
        }
    }

    /**
     * If a selection is active, returns [layer] masked to it so the edit only
     * affects the selected area (Photoshop-style). Otherwise returns [layer].
     */
    private fun withActiveSelectionMask(layer: Layer): Layer {
        val sel = _selection.value?.takeIf { !it.isEmpty() } ?: return layer
        return layer.copyBase(mask = sel.toLayerMask())
    }

    fun updateActiveAdjustment(adjustment: LayerAdjustment) {
        updateDocument { doc ->
            val i = doc.activeLayerIndex
            if (doc.layers.getOrNull(i) is AdjustmentLayer) {
                doc.updateLayer(i) { (it as AdjustmentLayer).copy(adjustment = adjustment) }
            } else doc
        }
    }

    // --- destructive edits on the active pixel layer --------------------------

    /**
     * Applies [transform] to the active pixel layer's bitmap on a background thread.
     * If a [selection] is present, the result is constrained to the selected area.
     */
    fun applyToActivePixelLayer(transform: (Bitmap) -> Bitmap) {
        val doc = _document.value
        val index = doc.activeLayerIndex
        val layer = doc.layers.getOrNull(index) as? PixelLayer ?: return
        val sel = _selection.value
        viewModelScope.launch(Dispatchers.Default) {
            val src = layer.bitmapRef.bitmap
            val edited = transform(src)
            val finalBmp = if (sel != null && !sel.isEmpty()) {
                blendWithSelection(src, edited, sel)
            } else edited
            withContext(Dispatchers.Main) {
                updateDocument {
                    it.updateLayer(index) { l ->
                        (l as PixelLayer).copy(bitmapRef = BitmapReference(finalBmp))
                    }
                }
            }
        }
    }

    private fun blendWithSelection(original: Bitmap, edited: Bitmap, sel: Selection): Bitmap {
        val w = edited.width
        val h = edited.height
        val out = edited.copy(Bitmap.Config.ARGB_8888, true)
        val orig = IntArray(w * h)
        val origScaled = if (original.width == w && original.height == h) original
        else Bitmap.createScaledBitmap(original, w, h, true)
        origScaled.getPixels(orig, 0, w, 0, 0, w, h)
        val edt = IntArray(w * h)
        out.getPixels(edt, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val sy = (y * sel.height / h).coerceIn(0, sel.height - 1)
            for (x in 0 until w) {
                val sx = (x * sel.width / w).coerceIn(0, sel.width - 1)
                val m = sel.mask[sy * sel.width + sx]
                if (m >= 1f) continue
                val i = y * w + x
                val o = orig[i]; val e = edt[i]
                val a = lerpCh(o ushr 24, e ushr 24, m)
                val r = lerpCh(o ushr 16, e ushr 16, m)
                val g = lerpCh(o ushr 8, e ushr 8, m)
                val b = lerpCh(o, e, m)
                edt[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        out.setPixels(edt, 0, w, 0, 0, w, h)
        return out
    }

    private fun lerpCh(a: Int, b: Int, t: Float): Int {
        val av = a and 0xFF; val bv = b and 0xFF
        return (av + (bv - av) * t).toInt().coerceIn(0, 255)
    }

    // --- selection ------------------------------------------------------------

    fun setSelection(sel: Selection?) { _selection.value = sel }

    fun selectionToActiveMask() {
        val sel = _selection.value ?: return
        val index = _document.value.activeLayerIndex
        setLayerMask(index, sel.toLayerMask())
    }

    // --- transforms -----------------------------------------------------------

    fun rotate(delta: Float) = updateDocument { it.copy(rotation = it.rotation + delta) }

    fun setRotation(angle: Float) = updateDocument { it.copy(rotation = angle) }

    fun setCropRect(rect: androidx.compose.ui.geometry.Rect) =
        updateDocument { it.copy(cropRect = rect) }

    fun setCroppingPreview(cropping: Boolean) {
        croppingPreview = cropping
        requestPreviewUpdate(immediate = true)
    }

    fun setPerspective(corners: com.vayunmathur.photos.data.PerspectiveCorners) =
        updateDocument { it.copy(perspectiveCorners = corners) }

    // --- preview --------------------------------------------------------------

    private fun requestPreviewUpdate(immediate: Boolean = false) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            if (!immediate) delay(150)
            val doc = _document.value
            if (doc.layers.isEmpty()) {
                _compositedPreview.value = null
                return@launch
            }
            val renderDoc = if (croppingPreview) doc.copy(cropRect = EditDocument.FULL_CROP, rotation = 0f) else doc
            val preview = try {
                compositor.compositePreview(renderDoc, PREVIEW_MAX_DIM)
            } catch (e: Exception) {
                Log.e(TAG, "preview composite failed", e)
                return@launch
            }
            _compositedPreview.value = preview
        }
    }

    // --- save -----------------------------------------------------------------

    fun savePhoto(
        photo: Photo,
        asCopy: Boolean,
        strokes: List<SerializedStroke>,
        texts: List<TextElement>,
        viewportWidth: Float,
        viewportHeight: Float,
        onComplete: () -> Unit,
    ) {
        val ctx: Context = getApplication()
        val doc = _document.value
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val composite = compositor.composite(doc, Int.MAX_VALUE)
                bakeOverlays(composite, strokes, texts, viewportWidth, viewportHeight)
                writeBitmap(ctx, photo, composite, asCopy)
            }
            when (result) {
                is WriteResult.Success -> onComplete()
                is WriteResult.NeedsPermission -> {
                    pendingSaveBytes = result.jpegBytes
                    pendingSaveUri = result.uri
                    pendingSaveOnComplete = onComplete
                    _writePermissionRequest.value = result.intentSender
                }
                is WriteResult.Error -> {
                    Log.e(TAG, "Save failed", result.exception)
                    onComplete()
                }
            }
        }
    }

    private fun bakeOverlays(
        target: Bitmap,
        strokes: List<SerializedStroke>,
        texts: List<TextElement>,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        if (strokes.isEmpty() && texts.isEmpty()) return
        val canvas = android.graphics.Canvas(target)
        if (strokes.isNotEmpty() && viewportWidth > 0f && viewportHeight > 0f) {
            canvas.drawSerializedStrokes(strokes, viewportWidth, viewportHeight, target.width, target.height)
        }
        texts.forEach { canvas.drawTextElement(it, target.width, target.height, viewportWidth) }
    }

    fun onWritePermissionGranted() {
        val bytes = pendingSaveBytes ?: return
        val uri = pendingSaveUri ?: return
        val onComplete = pendingSaveOnComplete ?: return
        pendingSaveBytes = null; pendingSaveUri = null; pendingSaveOnComplete = null
        _writePermissionRequest.value = null
        val ctx: Context = getApplication()
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    val resolver = ctx.contentResolver
                    resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                        ?: throw Exception("openOutputStream returned null after permission grant")
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                        put(MediaStore.Images.Media.SIZE, bytes.size.toLong())
                    }
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Overwrite FAILED after permission grant", e)
                }
            }
            onComplete()
        }
    }

    fun onWritePermissionDenied() {
        pendingSaveBytes = null; pendingSaveUri = null; pendingSaveOnComplete = null
        _writePermissionRequest.value = null
    }

    override fun onCleared() {
        super.onCleared()
        previewJob?.cancel()
        _compositedPreview.value?.recycle()
        _baseBitmap.value?.recycle()
        _compositedPreview.value = null
        _baseBitmap.value = null
        _document.value = EditDocument()
    }

    companion object {
        private const val TAG = "PhotoEditViewModel"
        private const val PREVIEW_MAX_DIM = 2048

        private sealed class WriteResult {
            data object Success : WriteResult()
            data class NeedsPermission(val intentSender: IntentSender, val jpegBytes: ByteArray, val uri: Uri) : WriteResult()
            data class Error(val exception: Exception) : WriteResult()
        }

        private fun writeBitmap(
            context: Context,
            photo: Photo,
            bitmap: Bitmap,
            asCopy: Boolean,
        ): WriteResult {
            val resolver = context.contentResolver
            val nowSeconds = System.currentTimeMillis() / 1000
            if (asCopy) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Edited_${photo.name}")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.DATE_MODIFIED, nowSeconds)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                }
                return WriteResult.Success
            }

            val uri = photo.uri.toUri()
            val jpegBytes = ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }.toByteArray()
            try {
                resolver.openOutputStream(uri, "w")?.use { it.write(jpegBytes) }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATE_MODIFIED, nowSeconds)
                    put(MediaStore.Images.Media.SIZE, jpegBytes.size.toLong())
                }
                resolver.update(uri, values, null, null)
                return WriteResult.Success
            } catch (e: Exception) {
                Log.d(TAG, "Direct write failed, falling back to createWriteRequest", e)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pendingIntent = MediaStore.createWriteRequest(resolver, listOf(uri))
                return WriteResult.NeedsPermission(pendingIntent.intentSender, jpegBytes, uri)
            }
            return WriteResult.Error(Exception("createWriteRequest requires API 30+"))
        }
    }
}

@Suppress("FunctionName")
fun PhotoEditViewModelFactory(
    application: Application,
    photoDao: PhotoDao,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { PhotoEditViewModel(application, photoDao) }
    }
