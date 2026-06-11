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

class BokehAnalyzer(
    context: Context,
    private val onMaskGenerated: (Bitmap) -> Unit
) : ImageAnalysis.Analyzer {

    private val imageSegmenter: ImageSegmenter

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("selfie_segmenter.tflite")
            .build()

        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setOutputCategoryMask(false)
            .setOutputConfidenceMasks(true)
            .setResultListener { result, _ ->
                val masks = result.confidenceMasks()
                if (masks.isPresent && masks.get().isNotEmpty()) {
                    val mpImage = masks.get()[0]
                    val w = mpImage.width
                    val h = mpImage.height
                    val floatBuffer = ByteBufferExtractor.extract(mpImage).asFloatBuffer()
                    floatBuffer.rewind()
                    val pixels = IntArray(w * h)
                    for (i in pixels.indices) {
                        val alpha = (floatBuffer.get().coerceIn(0f, 1f) * 255).toInt()
                        pixels[i] = Color.argb(alpha, 255, 255, 255)
                    }
                    val maskBitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                    onMaskGenerated(maskBitmap)
                }
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
}
