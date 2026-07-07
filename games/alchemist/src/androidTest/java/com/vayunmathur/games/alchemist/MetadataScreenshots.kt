package com.vayunmathur.games.alchemist

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.games.alchemist.data.AlchemyItem
import com.vayunmathur.games.alchemist.util.AlchemistViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshot generator driven by `:games:alchemist:metadata`. Seeds a few base elements
 * onto the crafting play area (via the ViewModel) so the board isn't empty.
 */
@RunWith(AndroidJUnit4::class)
class MetadataScreenshots {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val outDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "metadata_screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun snap(index: Int) {
        composeRule.waitForIdle()
        val image = composeRule.onRoot().captureToImage()
        File(outDir, "$index.png").outputStream().use { out ->
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    @Test
    fun generateStoreScreenshots() {
        val vm = composeRule.runOnUiThread<AlchemistViewModel> {
            ViewModelProvider(composeRule.activity)[AlchemistViewModel::class.java]
        }

        // Base elements are seeded asynchronously on first launch — wait for them.
        var items: List<AlchemyItem> = emptyList()
        for (i in 0 until 25) {
            items = vm.availableItems.value
            if (items.isNotEmpty()) break
            Thread.sleep(200)
        }

        // Scatter a few elements across the play area so the board looks alive.
        val spots = listOf(
            Offset(180f, 380f), Offset(640f, 360f),
            Offset(320f, 760f), Offset(680f, 900f)
        )
        composeRule.runOnUiThread {
            items.take(spots.size).forEachIndexed { i, item -> vm.placeElement(item.id, spots[i]) }
        }
        snap(1) // crafting play area (lead)
    }
}
