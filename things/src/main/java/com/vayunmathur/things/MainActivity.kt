package com.vayunmathur.things

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.things.ui.ThingsApp
import com.vayunmathur.things.util.BleManager

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: BleManager
    val messages = mutableStateListOf<String>()
    val connectionState = mutableStateOf("Disconnected")
    val scanning = mutableStateOf(false)
    val discoveredDevices = mutableStateListOf<BleManager.BleDevice>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            bleManager.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bleManager = BleManager(this)
        setContent {
            DynamicTheme {
                ThingsApp(
                    messages = messages,
                    connectionState = connectionState.value,
                    scanning = scanning.value,
                    discoveredDevices = discoveredDevices,
                    onScanClick = ::requestPermissionsAndScan,
                    onDeviceClick = { bleManager.connect(it.address) },
                    onDisconnectClick = { bleManager.disconnect() }
                )
            }
        }
    }

    private fun requestPermissionsAndScan() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.close()
    }
}
