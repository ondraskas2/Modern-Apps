package com.vayunmathur.watch.watch.service

import android.content.Context
import android.util.Log
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationData
import androidx.health.services.client.data.WarmUpConfig
import com.vayunmathur.watch.shared.data.ExerciseSessionSummary
import com.vayunmathur.watch.shared.data.LapMarker
import com.vayunmathur.watch.shared.data.RepSegment
import com.vayunmathur.watch.shared.data.RoutePoint
import com.vayunmathur.watch.shared.data.SpeedSample
import com.vayunmathur.watch.watch.data.MetricType
import com.vayunmathur.watch.watch.data.SensorDao
import com.vayunmathur.watch.watch.data.SensorRecord
import com.vayunmathur.watch.watch.data.WorkoutType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.Executor

/**
 * Wraps Health Services' active [ExerciseClient] to run one workout at a time.
 * Mirrors [HealthServicesCollector]'s future handling (addListener + a direct
 * executor, no kotlinx-coroutines-guava). It capability-gates the requested data
 * types per exercise type, drives the prepare → start → pause/resume → end
 * lifecycle, pushes live values out through callbacks, and on ENDED builds an
 * [ExerciseSessionSummary] and persists it as a single [SensorRecord] for the
 * BLE pipeline to ship.
 */
class ExerciseController(
    context: Context,
    private val dao: SensorDao,
    private val scope: CoroutineScope,
    // Live callbacks for the UI/Tile, invoked on the direct executor thread.
    private val onState: (ExerciseState) -> Unit,
    private val onMetrics: (LiveMetrics) -> Unit,
    private val onMessage: (String) -> Unit,
) {
    data class LiveMetrics(
        val activeDurationMs: Long = 0,
        val heartRateBpm: Double? = null,
        val calories: Double? = null,
        val distanceMeters: Double? = null,
    )

    private val appContext = context.applicationContext
    private val client: ExerciseClient =
        HealthServices.getClient(appContext).exerciseClient

    private val json = Json { ignoreUnknownKeys = true }
    private val directExecutor = Executor { it.run() }

    @Volatile private var activeWorkout: WorkoutType? = null
    @Volatile private var startedAtMs: Long = 0L

    // Accumulated across updates for the final summary.
    private val routePoints = mutableListOf<RoutePoint>()
    @Volatile private var lastRouteAtMs = 0L
    @Volatile private var latestActiveDurationMs = 0L
    @Volatile private var latestHr: Double? = null
    @Volatile private var latestCalories: Double? = null
    @Volatile private var latestDistance: Double? = null
    @Volatile private var latestAvgSpeed: Double? = null
    @Volatile private var latestMaxSpeed: Double? = null
    @Volatile private var latestAvgCadence: Double? = null
    @Volatile private var latestVo2Max: Double? = null
    @Volatile private var latestLapCount: Long? = null
    @Volatile private var latestRepCount: Long? = null

    /**
     * Warms up sensors, then starts the workout once we confirm no other app owns
     * an exercise. If our own app already owns an in-progress exercise, we simply
     * re-attach the update callback (orphan recovery) instead of starting again.
     */
    fun start(workout: WorkoutType) {
        val infoFuture = client.getCurrentExerciseInfoAsync()
        infoFuture.addListener({
            val status = try {
                infoFuture.get().exerciseTrackedStatus
            } catch (e: Exception) {
                Log.e(TAG, "getCurrentExerciseInfo failed", e)
                ExerciseTrackedStatus.UNKNOWN
            }
            when (status) {
                ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS -> {
                    onMessage("Another app is tracking a workout")
                }
                ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS -> {
                    // Recover: rebind to the in-progress session.
                    activeWorkout = workout
                    client.setUpdateCallback(directExecutor, updateCallback)
                }
                else -> beginNewExercise(workout)
            }
        }, directExecutor)
    }

    private fun beginNewExercise(workout: WorkoutType) {
        activeWorkout = workout
        resetAccumulators()
        client.setUpdateCallback(directExecutor, updateCallback)

        val capsFuture = client.getCapabilitiesAsync()
        capsFuture.addListener({
            val supported: Set<DataType<*, *>> = try {
                capsFuture.get().getExerciseTypeCapabilities(workout.healthServicesType)
                    .supportedDataTypes
            } catch (e: Exception) {
                Log.e(TAG, "getCapabilities failed", e)
                emptySet()
            }
            val dataTypes = desiredDataTypes(workout).filter { it in supported }.toSet()
            warmUpThenStart(workout, dataTypes, supported)
        }, directExecutor)
    }

    private fun warmUpThenStart(
        workout: WorkoutType,
        dataTypes: Set<DataType<*, *>>,
        supported: Set<DataType<*, *>>,
    ) {
        // Warm-up only accepts delta (non-aggregate) types.
        val warmUpTypes = dataTypes.filterIsInstance<
            androidx.health.services.client.data.DeltaDataType<*, *>>().toSet()
        if (warmUpTypes.isNotEmpty()) {
            val prepFuture = client.prepareExerciseAsync(
                WarmUpConfig(workout.healthServicesType, warmUpTypes),
            )
            prepFuture.addListener({
                try {
                    prepFuture.get()
                } catch (e: Exception) {
                    Log.w(TAG, "prepareExercise failed; starting anyway", e)
                }
                startExercise(workout, dataTypes, supported)
            }, directExecutor)
        } else {
            startExercise(workout, dataTypes, supported)
        }
    }

    private fun startExercise(
        workout: WorkoutType,
        dataTypes: Set<DataType<*, *>>,
        supported: Set<DataType<*, *>>,
    ) {
        val gps = workout.isGpsBased && DataType.LOCATION in supported
        val config = ExerciseConfig.builder(workout.healthServicesType)
            .setDataTypes(dataTypes)
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(gps)
            .build()
        startedAtMs = System.currentTimeMillis()
        val future = client.startExerciseAsync(config)
        future.addListener({
            try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "startExercise failed", e)
                onMessage("Could not start workout")
            }
        }, directExecutor)
    }

    fun pause() = fireAndForget(client.pauseExerciseAsync(), "pause")
    fun resume() = fireAndForget(client.resumeExerciseAsync(), "resume")
    fun stop() = fireAndForget(client.endExerciseAsync(), "end")

    private fun fireAndForget(
        future: com.google.common.util.concurrent.ListenableFuture<Void>,
        op: String,
    ) {
        future.addListener({
            try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "$op failed", e)
            }
        }, directExecutor)
    }

    private val updateCallback = object : ExerciseUpdateCallback {
        override fun onRegistered() {}

        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(TAG, "update callback registration failed", throwable)
        }

        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val metrics = update.latestMetrics
            captureLive(update, metrics)
            val state = update.exerciseStateInfo.state
            onState(state)
            if (state.isEnded) {
                finishSession(update)
            }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }

    private fun captureLive(update: ExerciseUpdate, metrics: DataPointContainer) {
        update.activeDurationCheckpoint?.let {
            latestActiveDurationMs = it.activeDuration.toMillis()
        }
        metrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.let { latestHr = it.value }
        metrics.getData(DataType.CALORIES_TOTAL)?.let { latestCalories = it.total }
        metrics.getData(DataType.DISTANCE_TOTAL)?.let { latestDistance = it.total }
        metrics.getData(DataType.SPEED_STATS)?.let {
            latestAvgSpeed = it.average
            latestMaxSpeed = it.max
        }
        metrics.getData(DataType.STEPS_PER_MINUTE_STATS)?.let {
            latestAvgCadence = it.average.toDouble()
        }
        metrics.getData(DataType.VO2_MAX_STATS)?.let { latestVo2Max = it.average }
        metrics.getData(DataType.SWIMMING_LAP_COUNT_TOTAL)?.let { latestLapCount = it.total }
        metrics.getData(DataType.REP_COUNT_TOTAL)?.let { latestRepCount = it.total }

        // Throttle route points to at most one every few seconds. Route point
        // timestamps use capture time (near-real-time given the throttle) to
        // avoid resolving the device boot instant for sample-relative durations.
        metrics.getData(DataType.LOCATION).forEach { sample ->
            val now = System.currentTimeMillis()
            if (now - lastRouteAtMs >= ROUTE_MIN_INTERVAL_MS) {
                lastRouteAtMs = now
                val loc: LocationData = sample.value
                routePoints += RoutePoint(
                    lat = loc.latitude,
                    lng = loc.longitude,
                    // Health Services reports Double.MAX_VALUE when altitude is
                    // unavailable (LocationData.ALTITUDE_UNAVAILABLE, whose
                    // companion is internal, so the sentinel is inlined here).
                    altitude = loc.altitude.takeIf { it.isFinite() && it != Double.MAX_VALUE },
                    time = now,
                )
            }
        }

        onMetrics(
            LiveMetrics(
                activeDurationMs = latestActiveDurationMs,
                heartRateBpm = latestHr,
                calories = latestCalories,
                distanceMeters = latestDistance,
            ),
        )
    }

    private fun finishSession(update: ExerciseUpdate) {
        val workout = activeWorkout ?: return
        activeWorkout = null
        val endTime = System.currentTimeMillis()
        val startTime = update.startTime?.toEpochMilli() ?: startedAtMs

        val summary = ExerciseSessionSummary(
            exerciseType = workout.healthServicesType.name,
            startTime = startTime,
            endTime = endTime,
            activeDurationMs = latestActiveDurationMs,
            avgSpeedMps = latestAvgSpeed,
            maxSpeedMps = latestMaxSpeed,
            avgCadenceSpm = latestAvgCadence,
            vo2Max = latestVo2Max,
            route = routePoints.toList().takeIf { it.isNotEmpty() },
            lapCount = latestLapCount,
            segments = buildSegments(workout, startTime, endTime),
            distanceMeters = latestDistance,
            totalCalories = latestCalories,
            avgHr = latestHr,
        )
        persist(summary)
        clearCallback()
    }

    // Rep-based workouts report a running REP_COUNT_TOTAL; we emit it as a single
    // whole-session segment. Fine-grained per-set segmentation is not available
    // from a plain rep counter, so the segment type is UNKNOWN (0).
    private fun buildSegments(workout: WorkoutType, start: Long, end: Long): List<RepSegment>? {
        if (!workout.isRepBased) return null
        val reps = latestRepCount?.takeIf { it > 0 } ?: return null
        return listOf(RepSegment(type = SEGMENT_TYPE_UNKNOWN, reps = reps, start = start, end = end))
    }

    private fun persist(summary: ExerciseSessionSummary) {
        val record = SensorRecord(
            type = MetricType.ExerciseSession,
            timestamp = summary.endTime,
            value = 0.0,
            session = json.encodeToString(summary),
        )
        scope.launch(Dispatchers.IO) {
            try {
                dao.insert(record)
            } catch (e: Exception) {
                Log.e(TAG, "insert session failed", e)
            }
        }
    }

    private fun clearCallback() {
        try {
            client.clearUpdateCallbackAsync(updateCallback)
        } catch (e: Exception) {
            Log.e(TAG, "clearUpdateCallback failed", e)
        }
    }

    private fun resetAccumulators() {
        routePoints.clear()
        lastRouteAtMs = 0L
        latestActiveDurationMs = 0L
        latestHr = null
        latestCalories = null
        latestDistance = null
        latestAvgSpeed = null
        latestMaxSpeed = null
        latestAvgCadence = null
        latestVo2Max = null
        latestLapCount = null
        latestRepCount = null
    }

    private fun desiredDataTypes(workout: WorkoutType): Set<DataType<*, *>> {
        val types = mutableSetOf<DataType<*, *>>(
            DataType.HEART_RATE_BPM,
            DataType.CALORIES_TOTAL,
            DataType.DISTANCE_TOTAL,
            DataType.SPEED,
            DataType.SPEED_STATS,
            DataType.STEPS_PER_MINUTE_STATS,
            DataType.VO2_MAX_STATS,
        )
        if (workout.isGpsBased) types += DataType.LOCATION
        if (workout.isSwimming) types += DataType.SWIMMING_LAP_COUNT_TOTAL
        if (workout.isRepBased) types += DataType.REP_COUNT_TOTAL
        return types
    }

    companion object {
        private const val TAG = "ExerciseController"
        private const val ROUTE_MIN_INTERVAL_MS = 3_000L
        // Health Connect ExerciseSegment.EXERCISE_SEGMENT_TYPE_UNKNOWN.
        private const val SEGMENT_TYPE_UNKNOWN = 0
    }
}
