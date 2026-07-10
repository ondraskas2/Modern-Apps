package com.vayunmathur.headphones.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.headphones.protocol.EqBands
import com.vayunmathur.headphones.service.HeadphonesController
import com.vayunmathur.headphones.service.HeadphonesService

/**
 * Example EQ curves. These mirror the WF-1000XM5's built-in presets, but the app applies them
 * through the custom-EQ write (not the built-in preset id), so every change — example or manual
 * slider — is just an edit to the one custom curve.
 */
private data class ExampleEq(val name: String, val bands: EqBands)

private fun eq(clearBass: Int, vararg bands: Int) = EqBands(clearBass = clearBass, bands = bands.toList())

private val EXAMPLE_EQS = listOf(
    ExampleEq("Flat", eq(10, 10, 10, 10, 10, 10)),
    ExampleEq("Bright", eq(9, 10, 15, 17, 17, 19)),
    ExampleEq("Excited", eq(18, 9, 11, 10, 13, 15)),
    ExampleEq("Mellow", eq(7, 9, 8, 7, 6, 4)),
    ExampleEq("Relaxed", eq(1, 7, 9, 7, 5, 2)),
    ExampleEq("Vocal", eq(10, 16, 14, 12, 13, 9)),
    ExampleEq("Treble Boost", eq(10, 10, 10, 12, 16, 20)),
    ExampleEq("Bass Boost", eq(17, 10, 10, 10, 10, 10)),
    ExampleEq("Speech", eq(0, 14, 13, 11, 12, 0)),
)

private val BAND_LABELS = listOf("Clear Bass", "400", "1k", "2.5k", "6.3k", "16k")

@Composable
fun EqualizerPage() {
    val state by HeadphonesService.state.collectAsState()

    if (!state.isConnected) {
        NotConnected()
        return
    }
    if (!state.capabilities.supportsEqualizer) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Equalizer is not supported on this device.")
        }
        return
    }

    val debouncer = rememberDebouncer()
    // Local slider state, seeded from the device and re-seeded when the device reports new bands.
    var bands by remember(state.eqBands) { mutableStateOf(state.eqBands) }

    fun apply(newBands: EqBands) {
        bands = newBands
        debouncer.run { HeadphonesController.setEqBands(newBands) }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Presets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EXAMPLE_EQS.forEach { example ->
                FilterChip(
                    selected = bands == example.bands,
                    onClick = { apply(example.bands) },
                    label = { Text(example.name) },
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Custom bands", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val values = listOf(bands.clearBass) + bands.bands
                values.forEachIndexed { index, value ->
                    BandSlider(BAND_LABELS[index], value) { newValue ->
                        apply(
                            if (index == 0) {
                                bands.copy(clearBass = newValue)
                            } else {
                                bands.copy(bands = bands.bands.toMutableList().also { it[index - 1] = newValue })
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BandSlider(label: String, value: Int, onValueChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..(EqBands.STEPS - 1).toFloat(),
            steps = EqBands.STEPS - 2,
            modifier = Modifier.weight(1f),
        )
        Text("${value - EqBands.CENTER}", Modifier.width(32.dp))
    }
}
