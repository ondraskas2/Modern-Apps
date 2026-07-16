package com.vayunmathur.everysync.remote

import android.util.Log
import com.vayunmathur.everysync.model.MeasurementType
import com.vayunmathur.everysync.model.RemoteMeasurement
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Google Health via the Google Fitness REST API. Aggregates steps / weight /
 * heart-rate into daily buckets and maps them to [RemoteMeasurement]s for Health
 * Connect. Bucket start time makes the clientRecordId idempotent across syncs.
 */
class GoogleFitClient(private val accessToken: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun headers() = mapOf(
        "Authorization" to "Bearer $accessToken",
        "Content-Type" to "application/json",
    )

    suspend fun getMeasurements(sinceMillis: Long): List<RemoteMeasurement> {
        val out = mutableListOf<RemoteMeasurement>()
        val now = System.currentTimeMillis()
        val start = if (sinceMillis > 0) sinceMillis else now - 30L * 24 * 60 * 60 * 1000
        val body = """
            {
              "aggregateBy": [
                {"dataTypeName": "com.google.step_count.delta"},
                {"dataTypeName": "com.google.weight.summary"},
                {"dataTypeName": "com.google.heart_rate.summary"}
              ],
              "bucketByTime": {"durationMillis": 86400000},
              "startTimeMillis": $start,
              "endTimeMillis": $now
            }
        """.trimIndent()
        try {
            val resp = NetworkClient.performRequest(
                "https://fitness.googleapis.com/fitness/v1/users/me/dataset:aggregate",
                "POST", headers(), body,
            )
            val root = json.parseToJsonElement(resp.body) as? JsonObject ?: return out
            (root["bucket"] as? JsonArray)?.forEach { bucketEl ->
                val bucket = bucketEl.jsonObject
                val bucketStart = bucket["startTimeMillis"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@forEach
                (bucket["dataset"] as? JsonArray)?.forEachIndexed { idx, dsEl ->
                    val type = when (idx) {
                        0 -> MeasurementType.STEPS
                        1 -> MeasurementType.WEIGHT
                        else -> MeasurementType.HEART_RATE
                    }
                    (dsEl.jsonObject["point"] as? JsonArray)?.forEach { pointEl ->
                        val value = firstValue(pointEl.jsonObject, type) ?: return@forEach
                        out += RemoteMeasurement(
                            clientRecordId = "googlefit:${type.name}:$bucketStart",
                            type = type,
                            value = value,
                            timeMillis = bucketStart,
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMeasurements failed", e)
        }
        return out
    }

    private fun firstValue(point: JsonObject, type: MeasurementType): Double? {
        val values = point["value"] as? JsonArray ?: return null
        val first = values.firstOrNull()?.jsonObject ?: return null
        return when (type) {
            MeasurementType.STEPS -> first["intVal"]?.jsonPrimitive?.content?.toDoubleOrNull()
            else -> first["fpVal"]?.jsonPrimitive?.content?.toDoubleOrNull()
        }
    }

    companion object {
        private const val TAG = "GoogleFitClient"
    }
}
