package com.vayunmathur.watch.phone.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
import com.vayunmathur.watch.shared.data.ExerciseSessionSummary
import com.vayunmathur.watch.phone.data.WatchRecord
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Wraps Health Connect onboarding, permissions, and inserting the watch's
 * records. Heart rate and steps are written per-sample; the directly-measured
 * daily totals (distance, floors, elevation, calories) are written day-keyed so
 * growing totals upsert instead of duplicating.
 */
class HealthConnectManager(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(FloorsClimbedRecord::class),
        HealthPermission.getWritePermission(ElevationGainedRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(RestingHeartRateRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        // Active exercise session records (session-unique metrics only).
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(SpeedRecord::class),
        HealthPermission.getWritePermission(StepsCadenceRecord::class),
        HealthPermission.getWritePermission(Vo2MaxRecord::class),
        HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    fun isAvailable(): Boolean = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    suspend fun hasAllPermissions(): Boolean =
        client().permissionController.getGrantedPermissions().containsAll(permissions)

    suspend fun insert(records: List<WatchRecord>) {
        if (records.isEmpty()) return
        val zone = ZoneId.systemDefault()

        // Exercise sessions carry a JSON summary and map to a family of session
        // records; handle them separately from the scalar per-sample/daily types.
        val (sessions, scalars) = records.partition { it.type == "ExerciseSession" }
        sessions.forEach { insertExerciseSession(it, zone) }

        val hcRecords = scalars.mapNotNull { record ->
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
                "Distance" -> dailyRecord(record, time, zone) { start, day ->
                    DistanceRecord(
                        startTime = start,
                        startZoneOffset = zone.rules.getOffset(start),
                        endTime = time,
                        endZoneOffset = offset,
                        distance = Length.meters(record.value),
                        metadata = Metadata.manualEntry(clientRecordId = "distance-$day"),
                    )
                }
                "Floors" -> dailyRecord(record, time, zone) { start, day ->
                    FloorsClimbedRecord(
                        startTime = start,
                        startZoneOffset = zone.rules.getOffset(start),
                        endTime = time,
                        endZoneOffset = offset,
                        floors = record.value,
                        metadata = Metadata.manualEntry(clientRecordId = "floors-$day"),
                    )
                }
                "Elevation" -> dailyRecord(record, time, zone) { start, day ->
                    ElevationGainedRecord(
                        startTime = start,
                        startZoneOffset = zone.rules.getOffset(start),
                        endTime = time,
                        endZoneOffset = offset,
                        elevation = Length.meters(record.value),
                        metadata = Metadata.manualEntry(clientRecordId = "elevation-$day"),
                    )
                }
                "Calories" -> dailyRecord(record, time, zone) { start, day ->
                    TotalCaloriesBurnedRecord(
                        startTime = start,
                        startZoneOffset = zone.rules.getOffset(start),
                        endTime = time,
                        endZoneOffset = offset,
                        energy = Energy.kilocalories(record.value),
                        metadata = Metadata.manualEntry(clientRecordId = "calories-$day"),
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

    // Daily totals span start-of-day -> record time; the day-keyed clientRecordId
    // makes a growing total upsert rather than duplicate. Skip non-positive totals.
    private inline fun dailyRecord(
        record: WatchRecord,
        time: Instant,
        zone: ZoneId,
        build: (start: Instant, day: LocalDate) -> Record,
    ): Record? {
        if (record.value <= 0.0) return null
        val day = time.atZone(zone).toLocalDate()
        var start = day.atStartOfDay(zone).toInstant()
        if (!time.isAfter(start)) start = time.minusSeconds(1)
        return build(start, day)
    }

    /**
     * Parses the session JSON and writes the session envelope plus ONLY the
     * session-unique records (Speed, StepsCadence, Vo2Max) and route/segments.
     *
     * Deliberately NOT written here: Distance, Floors, Elevation, Calories, Steps
     * and HeartRate. Those already flow into Health Connect via the passive
     * daily-total pipeline and the watch's SensorManager per-sample pipeline while
     * the workout is happening. Writing session-scoped copies for the same app
     * origin would sum on top of those and double-count. The live values shown on
     * the watch come from ExerciseUpdate and are carried here only as display-only
     * aggregates — never re-written as records.
     */
    private suspend fun insertExerciseSession(record: WatchRecord, zone: ZoneId) {
        val jsonStr = record.session ?: return
        val summary = try {
            json.decodeFromString<ExerciseSessionSummary>(jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "parse ExerciseSessionSummary failed", e)
            return
        }

        val start = Instant.ofEpochMilli(summary.startTime)
        var end = Instant.ofEpochMilli(summary.endTime)
        if (!end.isAfter(start)) end = start.plusSeconds(1)
        val startOffset = zone.rules.getOffset(start)
        val endOffset = zone.rules.getOffset(end)
        val key = summary.startTime

        val toInsert = mutableListOf<Record>()

        val route = summary.route?.takeIf { it.isNotEmpty() }?.let { points ->
            ExerciseRoute(
                points.map { p ->
                    ExerciseRoute.Location(
                        time = Instant.ofEpochMilli(p.time),
                        latitude = p.lat,
                        longitude = p.lng,
                        altitude = p.altitude?.let { Length.meters(it) },
                    )
                },
            )
        }

        val segments = summary.segments.orEmpty().map { seg ->
            ExerciseSegment(
                startTime = Instant.ofEpochMilli(seg.start),
                endTime = Instant.ofEpochMilli(seg.end).let {
                    if (it.isAfter(Instant.ofEpochMilli(seg.start))) it
                    else Instant.ofEpochMilli(seg.start).plusSeconds(1)
                },
                segmentType = seg.type,
                repetitions = seg.reps.toInt(),
            )
        }

        toInsert += ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = startOffset,
            endTime = end,
            endZoneOffset = endOffset,
            exerciseType = healthConnectExerciseType(summary.exerciseType),
            title = null,
            segments = segments,
            laps = emptyList(),
            exerciseRoute = route,
            metadata = Metadata.manualEntry(clientRecordId = "exercise-$key"),
        )

        // Speed: only avg/max were reported (no time series), so emit avg at the
        // start and max at the end of the window.
        summary.avgSpeedMps?.let { avg ->
            val samples = buildList {
                add(SpeedRecord.Sample(time = start, speed = Velocity.metersPerSecond(avg)))
                summary.maxSpeedMps?.let {
                    add(SpeedRecord.Sample(time = end, speed = Velocity.metersPerSecond(it)))
                }
            }
            toInsert += SpeedRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                samples = samples,
                metadata = Metadata.manualEntry(clientRecordId = "exercise-speed-$key"),
            )
        }

        summary.avgCadenceSpm?.let { cadence ->
            toInsert += StepsCadenceRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                samples = listOf(StepsCadenceRecord.Sample(time = start, rate = cadence)),
                metadata = Metadata.manualEntry(clientRecordId = "exercise-cadence-$key"),
            )
        }

        summary.vo2Max?.let { vo2 ->
            toInsert += Vo2MaxRecord(
                time = end,
                zoneOffset = endOffset,
                vo2MillilitersPerMinuteKilogram = vo2,
                measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_OTHER,
                metadata = Metadata.manualEntry(clientRecordId = "exercise-vo2-$key"),
            )
        }

        try {
            client().insertRecords(toInsert)
        } catch (e: Exception) {
            Log.e(TAG, "insert exercise session failed", e)
        }
    }

    // Health Services ExerciseType.name -> Health Connect EXERCISE_TYPE_* int.
    // The two enum systems differ, so the mapping is explicit.
    private fun healthConnectExerciseType(hsName: String): Int = when (hsName) {
        "WALKING" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        "RUNNING" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        "BIKING" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        "HIKING" -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
        "SWIMMING_POOL" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
        "STRENGTH_TRAINING" -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }

    /** Batch-inserts derivation output; clientRecordIds make this idempotent. */
    suspend fun insertDerived(records: List<Record>) {
        if (records.isEmpty()) return
        try {
            client().insertRecords(records)
        } catch (e: Exception) {
            Log.e(TAG, "insertDerived failed", e)
        }
    }

    companion object {
        private const val TAG = "HealthConnectManager"
        private const val STEP_WINDOW_SECONDS = 60L
    }
}
