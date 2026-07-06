package com.vayunmathur.watch.shared.data

import kotlinx.serialization.Serializable

/**
 * Serializable summary of one active exercise session captured on the watch via
 * Health Services `ExerciseClient`. It is stored as a JSON blob in the watch's
 * `SensorRecord.session`, shipped to the phone over the existing BLE pipeline,
 * and parsed there into Health Connect records.
 *
 * This is the single source of truth for the DTO, shared by both apps. All metric
 * fields are nullable and capability-gated: only the values the watch actually
 * reported for the chosen exercise type are populated. Only session-unique data
 * lives here — distance, calories, steps and HR keep flowing through the passive/
 * SensorManager pipelines and must NOT be re-derived from this summary (see the
 * phone's HealthConnectManager.insertExerciseSession).
 */
@Serializable
data class ExerciseSessionSummary(
    // Health Services ExerciseType.name (e.g. "RUNNING"). Mapped to the Health
    // Connect exercise-type int on the phone.
    val exerciseType: String,
    val startTime: Long,
    val endTime: Long,
    val activeDurationMs: Long,
    // Session-unique metrics (no daily/passive equivalent).
    val avgSpeedMps: Double? = null,
    val maxSpeedMps: Double? = null,
    val avgCadenceSpm: Double? = null,
    val vo2Max: Double? = null,
    val route: List<RoutePoint>? = null,
    // Display-only lap count aggregate; the real per-lap markers ride in [laps].
    val lapCount: Long? = null,
    val laps: List<LapMarker>? = null,
    // Throttled speed time-series; the phone prefers this over the avg/max
    // approximation when building the SpeedRecord.
    val speedSamples: List<SpeedSample>? = null,
    val segments: List<RepSegment>? = null,
    // Human-readable terminal reason (e.g. "Ended by user", "Auto-paused"). Display-only.
    val endReason: String? = null,
    // Display-only aggregates for debugging/UI. The phone does NOT write these as
    // records (they already flow through the daily/passive pipelines).
    val distanceMeters: Double? = null,
    val totalCalories: Double? = null,
    val avgHr: Double? = null,
)

@Serializable
data class RoutePoint(
    val lat: Double,
    val lng: Double,
    val altitude: Double? = null,
    val time: Long,
)

@Serializable
data class RepSegment(
    // A Health Connect ExerciseSegment type constant (int).
    val type: Int,
    val reps: Long,
    val start: Long,
    val end: Long,
)

/** One swimming lap, mapped to a Health Connect [androidx.health.connect.client.records.ExerciseLap]. */
@Serializable
data class LapMarker(
    val start: Long,
    val end: Long,
)

/** One throttled speed sample, mapped to a Health Connect SpeedRecord.Sample. */
@Serializable
data class SpeedSample(
    val timeMs: Long,
    val mps: Double,
)
