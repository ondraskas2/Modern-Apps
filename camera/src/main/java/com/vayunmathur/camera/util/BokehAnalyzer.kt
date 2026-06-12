package com.vayunmathur.camera.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter

private const val PERSON_CLASS = 15 // "person" in DeepLabV3's 21-class Pascal VOC labels
private const val TEMPORAL_WEIGHT = 0.35f

class BokehAnalyzer(
    context: Context,
    private val onMaskGenerated: (Bitmap) -> Unit
) : ImageAnalysis.Analyzer {

    private var prevMask: FloatArray? = null
    private val imageSegmenter: ImageSegmenter

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("deeplabv3.tflite")
            .build()

        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .setResultListener { result, _ ->
                val catMask = result.categoryMask()
                if (!catMask.isPresent) return@setResultListener

                val mpImage = catMask.get()
                val w = mpImage.width
                val h = mpImage.height
                val buffer = ByteBufferExtractor.extract(mpImage)
                buffer.rewind()

                val current = FloatArray(w * h)
                for (i in current.indices) {
                    val label = buffer.get().toInt() and 0xFF
                    current[i] = if (label == PERSON_CLASS) 1f else 0f
                }

                // Temporal smoothing: blend with previous frame
                val prev = prevMask
                val smoothed = if (prev != null && prev.size == current.size) {
                    FloatArray(current.size) { i ->
                        current[i] * (1f - TEMPORAL_WEIGHT) + prev[i] * TEMPORAL_WEIGHT
                    }
                } else current
                prevMask = smoothed

                // Gaussian blur the mask for soft edges
                val blurred = blurMask(smoothed, w, h)

                val pixels = IntArray(w * h)
                for (i in pixels.indices) {
                    val alpha = (blurred[i].coerceIn(0f, 1f) * 255).toInt()
                    pixels[i] = Color.argb(alpha, 255, 255, 255)
                }
                val maskBitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                onMaskGenerated(maskBitmap)
            }
            .build()

        imageSegmenter = ImageSegmenter.createFromOptions(context, options)
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()
        val mpImage = BitmapImageBuilder(bitmap).build()
        imageSegmenter.segmentAsync(mpImage, imageProxy.imageInfo.timestamp)
        imageProxy.close()
    }

    fun close() {
        imageSegmenter.close()
    }

    private fun blurMask(src: FloatArray, w: Int, h: Int): FloatArray {
        // Two-pass separable Gaussian blur (radius 3, sigma ~1.5)
        val kernel = floatArrayOf(0.06f, 0.12f, 0.18f, 0.28f, 0.18f, 0.12f, 0.06f)
        val r = 3
        val temp = FloatArray(w * h)
        // Horizontal pass
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) {
                val sx = (x + k).coerceIn(0, w - 1)
                sum += src[y * w + sx] * kernel[k + r]
            }
            temp[y * w + x] = sum
        }
        // Vertical pass
        val dst = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) {
                val sy = (y + k).coerceIn(0, h - 1)
                sum += temp[sy * w + x] * kernel[k + r]
            }
            dst[y * w + x] = sum
        }
        return dst
    }
}
