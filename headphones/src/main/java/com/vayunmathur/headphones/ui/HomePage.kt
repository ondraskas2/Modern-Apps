package com.vayunmathur.headphones.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.headphones.R
import com.vayunmathur.headphones.bluetooth.ConnectionState
import com.vayunmathur.headphones.protocol.BatteryComponent
import com.vayunmathur.headphones.protocol.BatteryInfo
import com.vayunmathur.headphones.protocol.DeviceCapabilities
import com.vayunmathur.headphones.protocol.NcAsmMode
import com.vayunmathur.headphones.protocol.NcAsmState
import com.vayunmathur.headphones.service.HeadphonesController
import com.vayunmathur.headphones.service.HeadphonesService

@Composable
fun HomePage() {
    val state by HeadphonesService.state.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectionCard(state.deviceName ?: state.deviceAddress ?: "", state.connection)
        if (state.connection == ConnectionState.Connected) {
            BatteryCard(state.battery)
            if (state.capabilities.supportsNcAsm) {
                NcAsmCard(state.ncAsm, state.capabilities)
            }
        }
    }
}

@Composable
private fun ConnectionCard(name: String, connection: ConnectionState) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    when (connection) {
                        ConnectionState.Connected -> "Connected"
                        ConnectionState.Connecting -> "Connecting…"
                        ConnectionState.Disconnected -> "Disconnected"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (connection == ConnectionState.Connecting) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun BatteryCard(battery: BatteryInfo) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Battery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val dual = battery.left.isKnown || battery.right.isKnown
            if (dual) {
                BatteryRow("Left", battery.left)
                BatteryRow("Right", battery.right)
                if (battery.case.isKnown) BatteryRow("Case", battery.case)
            } else if (battery.single.isKnown) {
                BatteryRow("Battery", battery.single)
            } else {
                Text("—", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun BatteryRow(label: String, component: BatteryComponent) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        if (component.charging) {
            Icon(painterResource(R.drawable.bolt_24px), contentDescription = "Charging", Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
        }
        Text(if (component.isKnown) "${component.level}%" else "—", fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NcAsmCard(ncAsm: NcAsmState, capabilities: DeviceCapabilities) {
    val debouncer = rememberDebouncer()
    var ambient by remember(ncAsm.ambientLevel) { mutableStateOf(ncAsm.ambientLevel.toFloat()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Noise Control", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            val modes = listOf(
                NcAsmMode.NOISE_CANCELLING to "Noise Cancel",
                NcAsmMode.AMBIENT_SOUND to "Ambient",
                NcAsmMode.OFF to "Off",
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = ncAsm.mode == mode,
                        onClick = { HeadphonesController.setMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    ) { Text(label) }
                }
            }

            if (ncAsm.mode == NcAsmMode.AMBIENT_SOUND && capabilities.supportsAmbientLevel) {
                Text("Ambient level: ${ambient.toInt()}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = ambient,
                    onValueChange = {
                        ambient = it
                        debouncer.run { HeadphonesController.setAmbientLevel(it.toInt()) }
                    },
                    valueRange = 0f..NcAsmState.MAX_AMBIENT_LEVEL.toFloat(),
                    steps = NcAsmState.MAX_AMBIENT_LEVEL - 1,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Focus on Voice", Modifier.weight(1f))
                    Switch(
                        checked = ncAsm.voicePassthrough,
                        onCheckedChange = { HeadphonesController.setVoicePassthrough(it) },
                    )
                }
            }

            if (ncAsm.mode == NcAsmMode.NOISE_CANCELLING && capabilities.supportsWindNoiseReduction) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Wind Noise Reduction", Modifier.weight(1f))
                    Switch(
                        checked = ncAsm.windNoiseReduction,
                        onCheckedChange = { HeadphonesController.setWindNoiseReduction(it) },
                    )
                }
            }
        }
    }
}
