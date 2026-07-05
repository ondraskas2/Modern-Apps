package com.vayunmathur.watch.phone.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.vayunmathur.watch.phone.data.WatchRecord
import java.time.Instant
import java.time.ZoneId

/**
 * Wraps Health Connect onboarding, permissions, and inserting the watch's
 * heart-rate and step records.
 */
class HealthConnectManager(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
    )

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    fun isAvailable(): Boolean = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    suspend fun hasAllPermissions(): Boolean =
        client().permissionController.getGrantedPermissions().containsAll(permissions)

    suspend fun insert(records: List<WatchRecord>) {
        if (records.isEmpty()) return
        val zone = ZoneId.systemDefault()
        val hcRecords = records.mapNotNull { record ->
            val time = Instant.ofEpochMilli(record.timestamp)
            val offset = zone.rules.getOffset(time)
            when (record.type) {
                "HeartRate" -> HeartRateRecord(
                    startTime = time,
                    startZoneOffset = offset,
                    endTime = time,
                    endZoneOffset = offset,
                    samples = listOf(
                        HeartRateRecord.Sample(
                            time = time,
                            beatsPerMinute = record.value.toLong(),
                        ),
                    ),
                    metadata = Metadata.manualEntry(clientRecordId = "hr-${record.id}"),
                )
                "Steps" -> {
                    val count = record.delta.toLong()
                    if (count <= 0L) return@mapNotNull null
                    val start = time.minusSeconds(STEP_WINDOW_SECONDS)
                    StepsRecord(
                        startTime = start,
                        startZoneOffset = zone.rules.getOffset(start),
                        endTime = time,
                        endZoneOffset = offset,
                        count = count,
                        metadata = Metadata.manualEntry(clientRecordId = "steps-${record.id}"),
                    )
                }
                else -> null
            }
        }
        if (hcRecords.isEmpty()) return
        try {
            client().insertRecords(hcRecords)
        } catch (e: Exception) {
            Log.e(TAG, "insertRecords failed", e)
        }
    }

    companion object {
        private const val TAG = "HealthConnectManager"
        private const val STEP_WINDOW_SECONDS = 60L
    }
}
