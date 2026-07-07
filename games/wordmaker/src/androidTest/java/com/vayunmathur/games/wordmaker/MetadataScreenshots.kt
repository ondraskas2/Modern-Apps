package com.vayunmathur.games.wordmaker

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshot generator driven by `:games:wordmaker:metadata`. Captures the main game
 * screen (crossword grid + circular letter wheel).
 *
 * The app shows a loading spinner until achievements load, and the game screen has small
 * ongoing animations, so we wait for the letter wheel to appear (real-time), then pause
 * the Compose clock to freeze animations for a clean capture.
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
        // Wait (real-time) until the game screen is up — the letter wheel's Shuffle button.
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithContentDescription("Shuffle").fetchSemanticsNodes().isNotEmpty()
        }
        // Freeze the letter-placement animations so the capture settles.
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(1500)
        snap(1)
    }
}
