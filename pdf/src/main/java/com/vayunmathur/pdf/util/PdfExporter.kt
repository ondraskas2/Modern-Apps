package com.vayunmathur.pdf.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import com.vayunmathur.pdf.model.CapturedImage
import com.vayunmathur.pdf.model.Quadrilateral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import kotlin.math.roundToInt

suspend fun savePdfToUri(context: Context, images: List<CapturedImage>, targetUri: Uri): Boolean = withContext(Dispatchers.IO) {
    val pdfDocument = PdfDocument()
    try {
        images.forEachIndexed { index, capturedImage ->
            val uri = capturedImage.uri
            try {
                val crop = capturedImage.cropRect
                val quadrilateral = capturedImage.quadrilateral
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                
                // Determine crop dimensions
                val (cropWidth, cropHeight) = when {
                    quadrilateral != null -> {
                        // For quadrilateral, use bounding box dimensions
                        val bounds = quadrilateral.toBoundingRect()
                        bitmap.width * bounds.width to bitmap.height * bounds.height
                    }
                    crop != null -> bitmap.width * crop.width to bitmap.height * crop.height
                    else -> bitmap.width.toFloat() to bitmap.height.toFloat()
                }

                // Scale the image so its longest side matches the longest side of A4 (842 points).
                val a4LongSide = 842f
                val scale = a4LongSide / maxOf(cropWidth, cropHeight)
                
                val targetWidth = (cropWidth * scale).toInt().coerceAtLeast(1)
                val targetHeight = (cropHeight * scale).toInt().coerceAtLeast(1)

                val pageInfo = PdfDocument.PageInfo.Builder(targetWidth, targetHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)

                when {
                    quadrilateral != null -> {
                        val warped = warpQuadToBitmap(bitmap, quadrilateral, targetWidth, targetHeight)
                        page.canvas.drawBitmap(warped, 0f, 0f, null)
                        warped.recycle()
                    }
                    crop != null -> {
                        val srcRect = android.graphics.Rect(
                            (crop.left * bitmap.width).roundToInt(),
                            (crop.top * bitmap.height).roundToInt(),
                            (crop.right * bitmap.width).roundToInt(),
                            (crop.bottom * bitmap.height).roundToInt()
                        )
                        val dstRect = android.graphics.Rect(0, 0, targetWidth, targetHeight)
                        page.canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                    }
                    else -> {
                        val matrix = Matrix()
                        matrix.postScale(scale, scale)
                        page.canvas.drawBitmap(bitmap, matrix, null)
                    }
                }

                pdfDocument.finishPage(page)
                bitmap.recycle()
            } catch (e: Exception) {
                Log.e("PdfExporter", "Error processing image $uri", e)
            }
        }

        context.contentResolver.openFileDescriptor(targetUri, "w")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { fos ->
                pdfDocument.writeTo(fos)
            }
        }
        true
    } catch (e: Exception) {
        Log.e("PdfExporter", "Failed to save PDF", e)
        false
    } finally {
        pdfDocument.close()
    }
}

/**
 * Perspective-warps [quad] (normalized corners) out of [src] into a new [width]x[height] bitmap.
 * Falls back to a bounding-box crop when the perspective matrix is degenerate. Caller owns the result.
 */
fun warpQuadToBitmap(src: Bitmap, quad: Quadrilateral, width: Int, height: Int): Bitmap {
    val targetWidth = width.coerceAtLeast(1)
    val targetHeight = height.coerceAtLeast(1)
    val srcPoints = quad.toSrcPoints(src.width, src.height)
    val dstPoints = floatArrayOf(
        0f, 0f,
        targetWidth.toFloat(), 0f,
        targetWidth.toFloat(), targetHeight.toFloat(),
        0f, targetHeight.toFloat()
    )
    val matrix = Matrix()
    return if (matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)) {
        Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(src, matrix, null)
        }
    } else {
        val bounds = quad.toBoundingRect()
        val left = (bounds.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val top = (bounds.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val w = targetWidth.coerceAtMost(src.width - left)
        val h = targetHeight.coerceAtMost(src.height - top)
        Bitmap.createBitmap(src, left, top, w, h)
    }
}
