package com.vayunmathur.education.util

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Thin wrapper over Android [TextToSpeech] for the audio-first K-2 shell:
 * prompts and labels are narrated (tap-to-hear). Init is async; calls before
 * the engine is ready are no-ops.
 */
class Narrator(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            ready = true
        }
    }

    fun speak(text: String) {
        if (ready && text.isNotBlank()) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
        }
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}

val LocalNarrator = staticCompositionLocalOf<Narrator?> { null }

/** Creates a [Narrator] scoped to the composition, shutting it down on dispose. */
@Composable
fun rememberNarrator(): Narrator {
    val context = LocalContext.current
    val narrator = remember { Narrator(context) }
    DisposableEffect(Unit) {
        onDispose { narrator.shutdown() }
    }
    return narrator
}
