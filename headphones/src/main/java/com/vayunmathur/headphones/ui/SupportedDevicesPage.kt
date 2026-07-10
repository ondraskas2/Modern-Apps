package com.vayunmathur.headphones.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.headphones.R
import com.vayunmathur.headphones.bluetooth.BondedDeviceFinder
import com.vayunmathur.headphones.service.HeadphonesService
import kotlinx.coroutines.launch

private data class SupportedModel(
    val name: String,
    val type: String,
    /** Uppercase substring used to match the bonded device by name. */
    val nameMatch: String,
)

private val SUPPORTED_MODELS = listOf(
    SupportedModel(name = "Sony WF-1000XM5", type = "Earbuds", nameMatch = "WF-1000XM5"),
)

/** Landing page shown until a device is connected: the list of supported headphones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportedDevicesPage() {
    val context = LocalContext.current
    val finder = remember { BondedDeviceFinder(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Connect a device",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(SUPPORTED_MODELS) { model ->
                DeviceCard(model) {
                    val match = finder.findCandidates()
                        .firstOrNull { it.name.uppercase().contains(model.nameMatch) }
                    if (match != null) {
                        HeadphonesService.connectTo(context, match.address)
                    } else {
                        scope.launch {
                            snackbar.showSnackbar("Pair your ${model.name} in Bluetooth settings first")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(model: SupportedModel, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(painterResource(R.drawable.headphones_24px), contentDescription = null, Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(model.type, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
