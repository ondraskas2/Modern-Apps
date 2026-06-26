package com.vayunmathur.photos.util

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.vayunmathur.library.util.SerializedStroke
import com.vayunmathur.library.util.deserialize
import com.vayunmathur.photos.data.TextElement

private const val OVERLAY_TAG = "OverlayRendering"

/**
 * Draws a single [text] element onto this canvas. [referenceWidth] is the width the element's
 * normalized position/font size were authored against (canvas width for a full render, the
 * on-screen viewport width when baking the editor preview).
 */
fun Canvas.drawTextElement(
    text: TextElement,
    canvasWidth: Int,
    canvasHeight: Int,
    referenceWidth: Float,
) {
    val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        color = text.color
        textSize = text.fontSize * (canvasWidth / referenceWidth)
    }
    val fm = paint.fontMetrics
    save()
    translate(text.x * canvasWidth, text.y * canvasHeight)
    rotate(text.rotation)
    drawText(text.text, 0f, -fm.ascent, paint)
    restore()
}

/** Draws serialized ink [strokes] (authored at [sourceWidth]x[sourceHeight]) scaled to this canvas. */
fun Canvas.drawSerializedStrokes(
    strokes: List<SerializedStroke>,
    sourceWidth: Float,
    sourceHeight: Float,
    canvasWidth: Int,
    canvasHeight: Int,
    renderer: CanvasStrokeRenderer = CanvasStrokeRenderer.create(),
) {
    if (strokes.isEmpty() || sourceWidth <= 0f || sourceHeight <= 0f) return
    val identity = Matrix()
    save()
    scale(canvasWidth / sourceWidth, canvasHeight / sourceHeight)
    strokes.forEach { serialized ->
        try {
            renderer.draw(this, serialized.deserialize(), identity)
        } catch (e: Exception) {
            Log.w(OVERLAY_TAG, "Failed to render stroke", e)
        }
    }
    restore()
}
