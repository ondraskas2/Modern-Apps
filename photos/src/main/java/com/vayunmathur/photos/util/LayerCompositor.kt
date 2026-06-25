package com.vayunmathur.photos.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.vayunmathur.library.util.deserialize
import com.vayunmathur.photos.data.AdjustmentLayer
import com.vayunmathur.photos.data.DrawingLayer
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.Layer
import com.vayunmathur.photos.data.LayerMask
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.TextLayer
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.applyPerspectiveToBitmap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders an [EditDocument] layer stack (bottom→top) to a single [Bitmap].
 *
 * Pixel work happens on raw IntArray buffers using [com.vayunmathur.photos.data.LayerBlendMode]
 * per-pixel math, so every blend mode and mask behaves identically across API levels.
 * During editing call [compositePreview] (small [maxDimension], reuses a cached render
 * of the layers *below* the active one). For export/merge call [composite] at full res.
 *
 * Instances hold a one-entry cache, so keep a single compositor per editor session and
 * call [invalidateCache] when needed (the cache also self-invalidates on content change).
 */
class LayerCompositor {

    private var belowKey: String? = null
    private var belowCache: IntArray? = null

    fun invalidateCache() {
        belowKey = null
        belowCache = null
    }

    /** Full render of all layers (no cache). Use for export, merge and flatten. */
    fun composite(document: EditDocument, maxDimension: Int = Int.MAX_VALUE): Bitmap {
        val (w, h) = targetSize(document, maxDimension)
        val backdrop = IntArray(w * h)
        renderLayersInto(backdrop, document.layers, document, w, h)
        return applyDocumentTransforms(bitmapFromInts(backdrop, w, h), document)
    }

    /** Cached preview render: layers below the active layer are cached and reused. */
    fun compositePreview(document: EditDocument, maxDimension: Int): Bitmap {
        val (w, h) = targetSize(document, maxDimension)
        val layers = document.layers
        val active = document.activeLayerIndex.coerceIn(0, layers.size)

        val below = if (active > 0) layers.subList(0, active) else emptyList()
        val key = cacheKey(below, w, h)
        if (key != belowKey || belowCache == null) {
            val base = IntArray(w * h)
            renderLayersInto(base, below, document, w, h)
            belowCache = base
            belowKey = key
        }

        val backdrop = belowCache!!.copyOf()
        val rest = if (active < layers.size) layers.subList(active, layers.size) else emptyList()
        renderLayersInto(backdrop, rest, document, w, h)
        return applyDocumentTransforms(bitmapFromInts(backdrop, w, h), document)
    }

    /** Merges the layer at [index] into the one directly below it, producing a pixel layer. */
    fun mergeDown(document: EditDocument, index: Int): EditDocument {
        if (index <= 0 || index !in document.layers.indices) return document
        val lower = index - 1
        val w = document.canvasWidth.coerceAtLeast(1)
        val h = document.canvasHeight.coerceAtLeast(1)
        val backdrop = IntArray(w * h)
        renderLayersInto(backdrop, listOf(document.layers[lower], document.layers[index]), document, w, h)
        val merged = PixelLayer(
            bitmapRef = BitmapReference(bitmapFromInts(backdrop, w, h)),
            name = document.layers[lower].name,
        )
        val newLayers = document.layers.toMutableList()
        newLayers[lower] = merged
        newLayers.removeAt(index)
        invalidateCache()
        return document.copy(layers = newLayers, activeLayerIndex = lower)
    }

    /** Collapses the whole stack into a single pixel layer (transforms preserved). */
    fun flatten(document: EditDocument): EditDocument {
        if (document.layers.isEmpty()) return document
        val w = document.canvasWidth.coerceAtLeast(1)
        val h = document.canvasHeight.coerceAtLeast(1)
        val backdrop = IntArray(w * h)
        renderLayersInto(backdrop, document.layers, document, w, h)
        val flat = PixelLayer(
            bitmapRef = BitmapReference(bitmapFromInts(backdrop, w, h)),
            name = "Flattened",
            locked = true,
        )
        invalidateCache()
        return document.copy(layers = listOf(flat), activeLayerIndex = 0)
    }

    // --- internal rendering ---------------------------------------------------

    private fun renderLayersInto(
        backdrop: IntArray,
        layers: List<Layer>,
        document: EditDocument,
        w: Int,
        h: Int,
    ) {
        for (layer in layers) {
            if (!layer.visible || layer.opacity <= 0f) continue
            val src = layerSourcePixels(layer, backdrop, document, w, h) ?: continue
            val mask = layer.mask?.let { scaleMask(it, w, h) }
            val mode = layer.blendMode
            val opacity = layer.opacity
            for (i in backdrop.indices) {
                val extra = opacity * (mask?.get(i) ?: 1f)
                if (extra <= 0f) continue
                backdrop[i] = mode.blendPixel(backdrop[i], src[i], extra)
            }
        }
    }

    /** Returns the layer's own pixels at target size, or null if it contributes nothing. */
    private fun layerSourcePixels(
        layer: Layer,
        backdrop: IntArray,
        document: EditDocument,
        w: Int,
        h: Int,
    ): IntArray? = when (layer) {
        is PixelLayer -> pixelsOf(layer.bitmapRef.bitmap, w, h)
        is AdjustmentLayer -> {
            if (layer.adjustment.isIdentity()) {
                null
            } else {
                val source = bitmapFromInts(backdrop, w, h)
                val adjusted = layer.adjustment.applyToBitmap(source)
                val out = IntArray(w * h)
                adjusted.getPixels(out, 0, w, 0, 0, w, h)
                if (adjusted !== source) adjusted.recycleSafely()
                source.recycleSafely()
                out
            }
        }
        is TextLayer -> renderTextPixels(layer, document, w, h)
        is DrawingLayer -> renderStrokePixels(layer, w, h)
    }

    private fun renderTextPixels(layer: TextLayer, document: EditDocument, w: Int, h: Int): IntArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val t = layer.textElement
        val refWidth = document.canvasWidth.takeIf { it > 0 }?.toFloat() ?: w.toFloat()
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            textAlign = Paint.Align.LEFT
            color = t.color
            textSize = t.fontSize * (w / refWidth)
        }
        val fm = paint.fontMetrics
        canvas.save()
        canvas.translate(t.x * w, t.y * h)
        canvas.rotate(t.rotation)
        canvas.drawText(t.text, 0f, -fm.ascent, paint)
        canvas.restore()
        val out = IntArray(w * h)
        bmp.getPixels(out, 0, w, 0, 0, w, h)
        bmp.recycleSafely()
        return out
    }

    private fun renderStrokePixels(layer: DrawingLayer, w: Int, h: Int): IntArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (layer.strokes.isNotEmpty() && layer.sourceWidth > 0f && layer.sourceHeight > 0f) {
            val canvas = Canvas(bmp)
            val renderer = CanvasStrokeRenderer.create()
            val identity = Matrix()
            canvas.save()
            canvas.scale(w / layer.sourceWidth, h / layer.sourceHeight)
            layer.strokes.forEach { serialized ->
                try {
                    renderer.draw(canvas, serialized.deserialize(), identity)
                } catch (_: Exception) {
                }
            }
            canvas.restore()
        }
        val out = IntArray(w * h)
        bmp.getPixels(out, 0, w, 0, 0, w, h)
        bmp.recycleSafely()
        return out
    }

    private fun applyDocumentTransforms(bitmap: Bitmap, document: EditDocument): Bitmap {
        var result = bitmap

        if (document.rotation != 0f) {
            val matrix = Matrix().apply { postRotate(document.rotation) }
            val rotated = Bitmap.createBitmap(result, 0, 0, result.width, result.height, matrix, true)
            if (rotated !== result) result.recycleSafely()
            result = rotated
        }

        val crop = document.cropRect
        if (crop != EditDocument.FULL_CROP) {
            val left = (crop.left * result.width).roundToInt().coerceIn(0, result.width - 1)
            val top = (crop.top * result.height).roundToInt().coerceIn(0, result.height - 1)
            val width = ((crop.right - crop.left) * result.width).roundToInt()
                .coerceAtMost(result.width - left)
            val height = ((crop.bottom - crop.top) * result.height).roundToInt()
                .coerceAtMost(result.height - top)
            if (width > 0 && height > 0) {
                val cropped = Bitmap.createBitmap(result, left, top, width, height)
                if (cropped !== result) result.recycleSafely()
                result = cropped
            }
        }

        if (!document.perspectiveCorners.isIdentity()) {
            val warped = document.perspectiveCorners.applyPerspectiveToBitmap(result)
            if (warped !== result) result.recycleSafely()
            result = warped
        }

        return result
    }

    // --- helpers --------------------------------------------------------------

    private fun targetSize(document: EditDocument, maxDimension: Int): Pair<Int, Int> {
        val cw = document.canvasWidth
        val ch = document.canvasHeight
        if (cw <= 0 || ch <= 0) return 1 to 1
        val scale = min(maxDimension.toFloat() / cw, maxDimension.toFloat() / ch).coerceAtMost(1f)
        val w = (cw * scale).roundToInt().coerceAtLeast(1)
        val h = (ch * scale).roundToInt().coerceAtLeast(1)
        return w to h
    }

    private fun pixelsOf(bitmap: Bitmap, w: Int, h: Int): IntArray {
        val arr = IntArray(w * h)
        if (bitmap.width == w && bitmap.height == h) {
            bitmap.getPixels(arr, 0, w, 0, 0, w, h)
        } else {
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            scaled.getPixels(arr, 0, w, 0, 0, w, h)
            if (scaled !== bitmap) scaled.recycleSafely()
        }
        return arr
    }

    private fun scaleMask(mask: LayerMask, w: Int, h: Int): FloatArray {
        if (mask.width == w && mask.height == h) return mask.alphaData
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val sy = (y * mask.height / h).coerceIn(0, mask.height - 1)
            for (x in 0 until w) {
                val sx = (x * mask.width / w).coerceIn(0, mask.width - 1)
                out[y * w + x] = mask.alphaData[sy * mask.width + sx]
            }
        }
        return out
    }

    private fun bitmapFromInts(pixels: IntArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun cacheKey(layers: List<Layer>, w: Int, h: Int): String =
        layers.joinToString(separator = ",") { it.hashCode().toString() } + "@${w}x$h"

    private fun Bitmap.recycleSafely() {
        try {
            if (!isRecycled) recycle()
        } catch (_: Exception) {
        }
    }
}
