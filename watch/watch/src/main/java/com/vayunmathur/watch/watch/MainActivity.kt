package com.vayunmathur.watch.watch

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.watch.watch.data.SensorDatabase
import com.vayunmathur.watch.watch.service.SensorBackgroundService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CollectorScreen()
            }
        }
    }

    @Composable
    private fun CollectorScreen() {
        val context = this
        val dao = remember { SensorDatabase.get(context).sensorDao() }
        val rowCount by dao.countFlow().collectAsState(0)

        var hasPermissions by remember { mutableStateOf(false) }
        var collecting by remember { mutableStateOf(false) }

        val requestPermissions = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            onResult = { result -> hasPermissions = result.values.all { it } },
        )

        LaunchedEffect(Unit) {
            hasPermissions = requiredPermissions().all {
                checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (!hasPermissions) {
                Button(onClick = { requestPermissions.launch(requiredPermissions()) }) {
                    Text(stringResource(R.string.grant_permissions))
                }
                return@Column
            }

            val onOff = if (collecting) stringResource(R.string.on) else stringResource(R.string.off)
            Text(stringResource(R.string.status_collecting, onOff))
            Text(stringResource(R.string.status_rows, rowCount))
            Button(
                onClick = {
                    collecting = if (collecting) {
                        SensorBackgroundService.stop(context)
                        false
                    } else {
                        SensorBackgroundService.start(context)
                        true
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    if (collecting) stringResource(R.string.action_stop)
                    else stringResource(R.string.action_start),
                )
            }
        }
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }
}
