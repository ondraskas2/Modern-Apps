package com.vayunmathur.photos.util

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.SerializedStroke
import com.vayunmathur.library.util.deserialize
import com.vayunmathur.photos.data.BlurParams
import com.vayunmathur.photos.data.CurvesAdjustment
import com.vayunmathur.photos.data.HealingStrokes
import com.vayunmathur.photos.data.HslAdjustments
import com.vayunmathur.photos.data.ImageAdjustments
import com.vayunmathur.photos.data.applyPixelEffects
import com.vayunmathur.photos.data.hasPixelEffects
import com.vayunmathur.photos.data.PerspectiveCorners
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoFilter
import com.vayunmathur.photos.data.SelectiveEdits
import com.vayunmathur.photos.data.TextElement
import com.vayunmathur.photos.data.applyToBitmap
import com.vayunmathur.photos.data.applyLutToBitmap
import com.vayunmathur.photos.data.applyHslToBitmap
import com.vayunmathur.photos.data.applyBlurToBitmap
import com.vayunmathur.photos.data.applySelectiveEdits
import com.vayunmathur.photos.data.applyHealingToBitmap
import com.vayunmathur.photos.data.applyPerspectiveToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

class PhotoEditViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap.asStateFlow()

    private val _transformedBitmap = MutableStateFlow<Bitmap?>(null)
    val transformedBitmap: StateFlow<Bitmap?> = _transformedBitmap.asStateFlow()

    val adjustments = MutableStateFlow(ImageAdjustments())
    val selectedFilter = MutableStateFlow<PhotoFilter?>(null)

    val curvesAdjustment = MutableStateFlow(CurvesAdjustment())
    val hslAdjustments = MutableStateFlow(HslAdjustments())
    val blurParams = MutableStateFlow(BlurParams())
    val selectiveEdits = MutableStateFlow(SelectiveEdits())
    val healingStrokes = MutableStateFlow(HealingStrokes())
    val perspectiveCorners = MutableStateFlow(PerspectiveCorners())

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap.asStateFlow()
    private var previewJob: Job? = null

    private val _writePermissionRequest = MutableStateFlow<IntentSender?>(null)
    val writePermissionRequest: StateFlow<IntentSender?> = _writePermissionRequest.asStateFlow()

    private var pendingSaveBytes: ByteArray? = null
    private var pendingSaveUri: Uri? = null
    private var pendingSaveOnComplete: (() -> Unit)? = null

    fun updateAdjustment(update: (ImageAdjustments) -> ImageAdjustments) {
        val prev = adjustments.value
        adjustments.value = update(adjustments.value)
        val curr = adjustments.value
        if (prev.sharpness != curr.sharpness || prev.vignette != curr.vignette || prev.grain != curr.grain) {
            requestPreviewUpdate()
        }
    }

    fun applyFilter(filter: PhotoFilter?) {
        val prev = adjustments.value
        selectedFilter.value = filter
        if (filter != null) {
            adjustments.value = filter.adjustments
        }
        val curr = adjustments.value
        if (prev.sharpness != curr.sharpness || prev.vignette != curr.vignette || prev.grain != curr.grain) {
            requestPreviewUpdate()
        }
    }

    fun resetAdjustments() {
        val prev = adjustments.value
        adjustments.value = ImageAdjustments()
        selectedFilter.value = null
        if (prev.hasPixelEffects()) {
            requestPreviewUpdate()
        }
    }

    fun updateCurves(curves: CurvesAdjustment) { curvesAdjustment.value = curves }
    fun resetCurves() { curvesAdjustment.value = CurvesAdjustment() }

    fun updateHsl(hsl: HslAdjustments) {
        hslAdjustments.value = hsl
        requestPreviewUpdate()
    }
    fun resetHsl() {
        hslAdjustments.value = HslAdjustments()
        _previewBitmap.value = null
    }

    fun updateBlur(params: BlurParams) {
        blurParams.value = params
        requestPreviewUpdate()
    }
    fun resetBlur() {
        blurParams.value = BlurParams()
        _previewBitmap.value = null
    }

    fun updateSelective(edits: SelectiveEdits) {
        selectiveEdits.value = edits
        requestPreviewUpdate()
    }
    fun resetSelective() {
        selectiveEdits.value = SelectiveEdits()
        _previewBitmap.value = null
    }

    fun updateHealing(strokes: HealingStrokes) { healingStrokes.value = strokes }
    fun resetHealing() { healingStrokes.value = HealingStrokes() }

    fun updatePerspective(corners: PerspectiveCorners) { perspectiveCorners.value = corners }
    fun resetPerspective() { perspectiveCorners.value = PerspectiveCorners() }

    private fun requestPreviewUpdate() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            delay(150)
            val source = _transformedBitmap.value ?: return@launch
            val adj = adjustments.value
            val hsl = hslAdjustments.value
            val blur = blurParams.value
            val selective = selectiveEdits.value
            val hasPixelEffects = adj.hasPixelEffects() ||
                !hsl.isIdentity() || !blur.isIdentity() || !selective.isIdentity()
            if (!hasPixelEffects) {
                _previewBitmap.value = null
                return@launch
            }
            val maxDim = 512
            val scale = min(maxDim.toFloat() / source.width, maxDim.toFloat() / source.height).coerceAtMost(1f)
            var preview = Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
            if (adj.hasPixelEffects()) {
                preview = adj.applyPixelEffects(preview)
            }
            if (!hsl.isIdentity()) {
                preview = hsl.applyHslToBitmap(preview)
            }
            if (!blur.isIdentity()) {
                preview = blur.applyBlurToBitmap(preview)
            }
            if (!selective.isIdentity()) {
                preview = selective.applySelectiveEdits(preview)
            }
            _previewBitmap.value = preview
        }
    }

    private val decodedCache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            if (size > 32) {
                val current = _originalBitmap.value
                if (eldest.value !== current) {
                    try { eldest.value.recycle() } catch (_: Exception) {}
                }
                return true
            }
            return false
        }
    }

    private var lastDecodedUri: String? = null

    fun decode(uri: Uri) {
        val uriStr = uri.toString()
        if (uriStr == lastDecodedUri && _originalBitmap.value != null) return
        val cached = synchronized(decodedCache) { decodedCache[uriStr] }
        if (cached != null && !cached.isRecycled) {
            lastDecodedUri = uriStr
            _originalBitmap.value = cached
            return
        }
        val ctx: Context = getApplication()
        viewModelScope.launch(Dispatchers.Default) {
            try {
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
                synchronized(decodedCache) { decodedCache[uriStr] = bmp }
                lastDecodedUri = uriStr
                _originalBitmap.value = bmp
            } catch (e: Exception) {
                Log.e(TAG, "decode failed for $uri", e)
            }
        }
    }

    fun applyTransform(rotation: Float, cropRect: Rect, isCropping: Boolean) {
        val original = _originalBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val matrix = Matrix().apply { postRotate(rotation) }
            var result = Bitmap.createBitmap(
                original, 0, 0, original.width, original.height, matrix, true,
            )
            if (!isCropping) {
                val left = (cropRect.left * result.width).roundToInt().coerceIn(0, result.width - 1)
                val top = (cropRect.top * result.height).roundToInt().coerceIn(0, result.height - 1)
                val width = ((cropRect.right - cropRect.left) * result.width).roundToInt()
                    .coerceAtMost(result.width - left)
                val height = ((cropRect.bottom - cropRect.top) * result.height).roundToInt()
                    .coerceAtMost(result.height - top)
                if (width > 0 && height > 0) {
                    val cropped = Bitmap.createBitmap(result, left, top, width, height)
                    if (cropped !== result) {
                        try { if (result !== original) result.recycle() } catch (_: Exception) {}
                        result = cropped
                    }
                }
            }
            val previous = _transformedBitmap.value
            _transformedBitmap.value = result
            if (previous != null && previous !== original && previous !== result) {
                try { if (!previous.isRecycled) previous.recycle() } catch (_: Exception) {}
            }
        }
    }

    fun savePhoto(
        photo: Photo,
        rotation: Float,
        cropRect: Rect,
        strokes: List<SerializedStroke>,
        texts: List<TextElement>,
        viewportWidth: Float,
        viewportHeight: Float,
        asCopy: Boolean,
        imageAdjustments: ImageAdjustments = ImageAdjustments(),
        curves: CurvesAdjustment = CurvesAdjustment(),
        hsl: HslAdjustments = HslAdjustments(),
        blur: BlurParams = BlurParams(),
        selective: SelectiveEdits = SelectiveEdits(),
        healing: HealingStrokes = HealingStrokes(),
        perspective: PerspectiveCorners = PerspectiveCorners(),
        onComplete: () -> Unit,
    ) {
        Log.d(TAG, "savePhoto called with asCopy=$asCopy")
        val ctx: Context = getApplication()
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                prepareAndWrite(ctx, photo, rotation, cropRect, strokes, texts, viewportWidth, viewportHeight, asCopy, imageAdjustments, curves, hsl, blur, selective, healing, perspective)
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

    fun onWritePermissionGranted() {
        val bytes = pendingSaveBytes ?: return
        val uri = pendingSaveUri ?: return
        val onComplete = pendingSaveOnComplete ?: return
        pendingSaveBytes = null
        pendingSaveUri = null
        pendingSaveOnComplete = null
        _writePermissionRequest.value = null

        val ctx: Context = getApplication()
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    val resolver = ctx.contentResolver
                    resolver.openOutputStream(uri, "w")?.use { out ->
                        out.write(bytes)
                    } ?: throw Exception("openOutputStream returned null after permission grant")
                    val updateValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                        put(MediaStore.Images.Media.SIZE, bytes.size.toLong())
                    }
                    resolver.update(uri, updateValues, null, null)
                    Log.d(TAG, "Overwrite SUCCESS after permission grant")
                } catch (e: Exception) {
                    Log.e(TAG, "Overwrite FAILED after permission grant", e)
                }
            }
            onComplete()
        }
    }

    fun onWritePermissionDenied() {
        pendingSaveBytes = null
        pendingSaveUri = null
        pendingSaveOnComplete = null
        _writePermissionRequest.value = null
    }

    override fun onCleared() {
        super.onCleared()
        val transformed = _transformedBitmap.value
        val original = _originalBitmap.value
        _transformedBitmap.value = null
        _originalBitmap.value = null
        if (transformed != null && transformed !== original) {
            try { if (!transformed.isRecycled) transformed.recycle() } catch (_: Exception) {}
        }
        synchronized(decodedCache) {
            decodedCache.values.forEach { bmp ->
                try { if (!bmp.isRecycled) bmp.recycle() } catch (_: Exception) {}
            }
            decodedCache.clear()
        }
    }

    companion object {
        private const val TAG = "PhotoEditViewModel"

        private sealed class WriteResult {
            object Success : WriteResult()
            data class NeedsPermission(val intentSender: IntentSender, val jpegBytes: ByteArray, val uri: Uri) : WriteResult()
            data class Error(val exception: Exception) : WriteResult()
        }

        private fun prepareAndWrite(
            context: Context,
            photo: Photo,
            rotation: Float,
            cropRect: Rect,
            strokes: List<SerializedStroke>,
            texts: List<TextElement>,
            viewportWidth: Float,
            viewportHeight: Float,
            asCopy: Boolean,
            imageAdjustments: ImageAdjustments = ImageAdjustments(),
            curves: CurvesAdjustment = CurvesAdjustment(),
            hsl: HslAdjustments = HslAdjustments(),
            blur: BlurParams = BlurParams(),
            selective: SelectiveEdits = SelectiveEdits(),
            healing: HealingStrokes = HealingStrokes(),
            perspective: PerspectiveCorners = PerspectiveCorners(),
        ): WriteResult {
            Log.d(TAG, "prepareAndWrite: asCopy=$asCopy, uri=${photo.uri}")
            val photoUri = Uri.parse(photo.uri)
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, photoUri)
            var originalBitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }

            // 1. Rotate
            val matrix = Matrix().apply { postRotate(rotation) }
            var transformedBitmap = Bitmap.createBitmap(
                originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true,
            )

            // 2. Crop
            val left = (cropRect.left * transformedBitmap.width).roundToInt()
                .coerceIn(0, transformedBitmap.width - 1)
            val top = (cropRect.top * transformedBitmap.height).roundToInt()
                .coerceIn(0, transformedBitmap.height - 1)
            val width = ((cropRect.right - cropRect.left) * transformedBitmap.width).roundToInt()
                .coerceAtMost(transformedBitmap.width - left)
            val height = ((cropRect.bottom - cropRect.top) * transformedBitmap.height).roundToInt()
                .coerceAtMost(transformedBitmap.height - top)
            if (width > 0 && height > 0) {
                transformedBitmap = Bitmap.createBitmap(transformedBitmap, left, top, width, height)
            }

            // 3. Perspective correction
            var resultBitmap = transformedBitmap.copy(Bitmap.Config.ARGB_8888, true)
            if (!perspective.isIdentity()) {
                resultBitmap = perspective.applyPerspectiveToBitmap(resultBitmap)
            }

            // 4. Image adjustments via ColorMatrix
            if (imageAdjustments != ImageAdjustments()) {
                resultBitmap = imageAdjustments.applyToBitmap(resultBitmap)
            }

            // 5. Curves LUT
            if (!curves.isIdentity()) {
                resultBitmap = curves.applyLutToBitmap(resultBitmap)
            }

            // 6. HSL tuning
            if (!hsl.isIdentity()) {
                resultBitmap = hsl.applyHslToBitmap(resultBitmap)
            }

            // 7. Selective edits
            if (!selective.isIdentity()) {
                resultBitmap = selective.applySelectiveEdits(resultBitmap)
            }

            // 8. Blur
            if (!blur.isIdentity()) {
                resultBitmap = blur.applyBlurToBitmap(resultBitmap)
            }

            // 9. Healing strokes
            if (!healing.isIdentity()) {
                resultBitmap = healing.applyHealingToBitmap(resultBitmap)
            }

            // 10. Render ink strokes
            val canvas = android.graphics.Canvas(resultBitmap)
            if (strokes.isNotEmpty()) {
                val renderer = androidx.ink.rendering.android.canvas.CanvasStrokeRenderer.create()
                val identityMatrix = Matrix()
                val scaleX = resultBitmap.width.toFloat() / viewportWidth
                val scaleY = resultBitmap.height.toFloat() / viewportHeight
                canvas.save()
                canvas.scale(scaleX, scaleY)
                strokes.forEach { serialized ->
                    try {
                        val stroke = serialized.deserialize()
                        renderer.draw(canvas, stroke, identityMatrix)
                    } catch (_: Exception) {}
                }
                canvas.restore()
            }

            // 11. Render text overlays
            if (texts.isNotEmpty()) {
                val textPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    style = android.graphics.Paint.Style.FILL
                    textAlign = android.graphics.Paint.Align.LEFT
                }
                texts.forEach { textElement ->
                    textPaint.color = textElement.color
                    textPaint.textSize = textElement.fontSize * (resultBitmap.width / viewportWidth)
                    val fontMetrics = textPaint.fontMetrics
                    canvas.save()
                    canvas.translate(textElement.x * resultBitmap.width, textElement.y * resultBitmap.height)
                    canvas.rotate(textElement.rotation)
                    canvas.drawText(textElement.text, 0f, -fontMetrics.ascent, textPaint)
                    canvas.restore()
                }
            }

            // 12. Write to disk
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
                        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                }
                return WriteResult.Success
            }

            val uri = Uri.parse(photo.uri)
            val jpegBytes = ByteArrayOutputStream().also {
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }.toByteArray()

            // Try direct write first (works with MANAGE_MEDIA permission)
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    out.write(jpegBytes)
                }
                val updateValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DATE_MODIFIED, nowSeconds)
                    put(MediaStore.Images.Media.SIZE, jpegBytes.size.toLong())
                }
                resolver.update(uri, updateValues, null, null)
                Log.d(TAG, "Direct overwrite SUCCESS for $uri")
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

class PhotoEditViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PhotoEditViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return PhotoEditViewModel(application) as T
    }
}
