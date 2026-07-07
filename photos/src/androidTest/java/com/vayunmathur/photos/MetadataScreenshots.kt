package com.vayunmathur.photos

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshot generator driven by `:photos:metadata`. Seeds a set of colorful images into
 * MediaStore before launch, then captures the gallery grid.
 */
@RunWith(AndroidJUnit4::class)
class MetadataScreenshots {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val outDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "metadata_screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun snap(index: Int) {
        val image = composeRule.onRoot().captureToImage()
        File(outDir, "$index.png").outputStream().use { out ->
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun gradientBitmap(top: Int, bottom: Int): Bitmap {
        val size = 900
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        // A soft circle accent so thumbnails look photographic rather than flat.
        canvas.drawCircle(size * 0.7f, size * 0.3f, size * 0.18f, Paint().apply { color = Color.argb(70, 255, 255, 255) })
        return bmp
    }

    private fun seedImages() {
        val palettes = listOf(
            Color.rgb(255, 138, 101) to Color.rgb(255, 209, 128),
            Color.rgb(129, 212, 250) to Color.rgb(179, 157, 219),
            Color.rgb(165, 214, 167) to Color.rgb(255, 245, 157),
            Color.rgb(244, 143, 177) to Color.rgb(206, 147, 216),
            Color.rgb(128, 222, 234) to Color.rgb(128, 203, 196),
            Color.rgb(255, 171, 145) to Color.rgb(188, 170, 164),
            Color.rgb(159, 168, 218) to Color.rgb(144, 202, 249),
            Color.rgb(255, 204, 128) to Color.rgb(255, 138, 101),
            Color.rgb(178, 235, 242) to Color.rgb(174, 213, 129),
            Color.rgb(240, 98, 146) to Color.rgb(149, 117, 205),
            Color.rgb(255, 224, 130) to Color.rgb(129, 199, 132),
            Color.rgb(179, 229, 252) to Color.rgb(244, 143, 177),
        )
        val resolver = ctx.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val now = System.currentTimeMillis()
        palettes.forEachIndexed { i, (top, bottom) ->
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "sample_${i + 1}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Images.Media.DATE_TAKEN, now - i * 3_600_000L)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return@forEachIndexed
            resolver.openOutputStream(uri)!!.use { out ->
                gradientBitmap(top, bottom).compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    @Test
    fun generateStoreScreenshots() {
        seedImages()
        ActivityScenario.launch(MainActivity::class.java).use {
            // Let the gallery index MediaStore and load thumbnails.
            Thread.sleep(6000)
            snap(1)
        }
    }
}
