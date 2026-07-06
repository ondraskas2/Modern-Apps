package com.vayunmathur.watch.watch.service

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import com.vayunmathur.watch.watch.data.MetricType
import com.vayunmathur.watch.watch.data.SensorDao
import com.vayunmathur.watch.watch.data.SensorRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * Collects directly-measured, OEM-calibrated daily totals (distance, floors,
 * elevation gain, calories) from Wear OS Health Services via passive monitoring
 * and persists each new running daily total as a [SensorRecord] for the BLE
 * pipeline to stream to the phone.
 *
 * Health Services is a Wear OS platform component and does not pull in Google
 * Play Services / Fitness, so it is safe to keep on the watch alongside the
 * Health Connect phone app.
 */
class HealthServicesCollector(
    context: Context,
    private val dao: SensorDao,
    private val scope: CoroutineScope,
) {
    private val client: PassiveMonitoringClient =
        HealthServices.getClient(context.applicationContext).passiveMonitoringClient

    private var registered = false

    // Run future listeners inline; work done is trivial (read result, register).
    private val directExecutor = Executor { it.run() }

    fun start() {
        val future = client.getCapabilitiesAsync()
        future.addListener({
            val supported = try {
                future.get().supportedDataTypesPassiveMonitoring
            } catch (e: Exception) {
                Log.e(TAG, "getCapabilities failed", e)
                return@addListener
            }
            val types = DESIRED_TYPES.filter { it in supported }.toSet()
            if (types.isEmpty()) {
                Log.w(TAG, "No supported daily data types for passive monitoring")
                return@addListener
            }
            register(types)
        }, directExecutor)
    }

    private fun register(types: Set<DataType<*, *>>) {
        val config = PassiveListenerConfig.builder()
            .setDataTypes(types)
            .build()
        try {
            client.setPassiveListenerCallback(config, callback)
            registered = true
        } catch (e: Exception) {
            Log.e(TAG, "setPassiveListenerCallback failed", e)
        }
    }

    private val callback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            val now = System.currentTimeMillis()
            dataPoints.getData(DataType.DISTANCE_DAILY).forEach {
                persist(MetricType.Distance, it.value, now)
            }
            dataPoints.getData(DataType.FLOORS_DAILY).forEach {
                persist(MetricType.Floors, it.value, now)
            }
            dataPoints.getData(DataType.ELEVATION_GAIN_DAILY).forEach {
                persist(MetricType.Elevation, it.value, now)
            }
            dataPoints.getData(DataType.CALORIES_DAILY).forEach {
                persist(MetricType.Calories, it.value, now)
            }
        }
    }

    fun stop() {
        if (!registered) return
        try {
            client.clearPassiveListenerCallbackAsync()
        } catch (e: Exception) {
            Log.e(TAG, "clearPassiveListenerCallback failed", e)
        }
        registered = false
    }

    private fun persist(type: MetricType, value: Double, timestamp: Long) {
        val record = SensorRecord(type = type, timestamp = timestamp, value = value)
        scope.launch(Dispatchers.IO) {
            try {
                dao.insert(record)
            } catch (e: Exception) {
                Log.e(TAG, "insert failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "HealthServicesCollector"

        // Direct daily-total metrics. CALORIES_DAILY is total energy (BMR +
        // active); we forward the honest direct value rather than re-deriving.
        private val DESIRED_TYPES: Set<DataType<*, *>> = setOf(
            DataType.DISTANCE_DAILY,
            DataType.FLOORS_DAILY,
            DataType.ELEVATION_GAIN_DAILY,
            DataType.CALORIES_DAILY,
        )
    }
}
