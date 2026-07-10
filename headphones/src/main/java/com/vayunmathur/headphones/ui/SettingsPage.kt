package com.vayunmathur.headphones.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.headphones.protocol.Feature
import com.vayunmathur.headphones.service.HeadphonesController
import com.vayunmathur.headphones.service.HeadphonesService

private val FEATURE_LABELS = listOf(
    Feature.DSEE to "DSEE (Upscaling)",
    Feature.SPEAK_TO_CHAT to "Speak-to-Chat",
    Feature.MULTIPOINT to "Multipoint",
    Feature.ADAPTIVE_SOUND_CONTROL to "Adaptive Sound Control",
)

@Composable
fun SettingsPage() {
    val context = LocalContext.current
    val state by HeadphonesService.state.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.isConnected) {
            if (state.pairedDevices.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paired devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        state.pairedDevices.forEach { device ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(device.name)
                                    Text(
                                        if (device.connected) "Connected" else "Registered",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                TextButton(onClick = { HeadphonesController.removePairedDevice(device.address) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Features", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val supported = FEATURE_LABELS.filter { state.capabilities.supports(it.first) }
                    if (supported.isEmpty()) {
                        Text("No optional features reported by this device.")
                    }
                    supported.forEach { (feature, label) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f))
                            Switch(
                                checked = state.features[feature] == true,
                                onCheckedChange = { HeadphonesController.setFeature(feature, it) },
                            )
                        }
                    }
                }
            }
        } else {
            Text("Not connected.", style = MaterialTheme.typography.bodyMedium)
        }

        OutlinedButton(
            onClick = { HeadphonesService.forget(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Forget device") }
    }
}
