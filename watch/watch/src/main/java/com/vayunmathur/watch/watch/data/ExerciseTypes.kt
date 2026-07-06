package com.vayunmathur.watch.watch.data

import androidx.health.services.client.data.ExerciseType

/**
 * Explicit table linking the workout picker to the two distinct enum systems:
 * Health Services [ExerciseType] (what the watch tracks) and Health Connect's
 * `ExerciseSessionRecord.EXERCISE_TYPE_*` int (what the phone writes). The two
 * do not share names or ids, so the mapping is spelled out rather than inferred.
 *
 * The Health Connect int is duplicated as a literal here (the watch module has no
 * connect-client dependency); the values match
 * `androidx.health.connect.client.records.ExerciseSessionRecord`.
 */
enum class WorkoutType(
    val label: String,
    val healthServicesType: ExerciseType,
    val healthConnectType: Int,
    // GPS-backed outdoor activity: request LOCATION + enable GPS for a route.
    val isGpsBased: Boolean = false,
    // Pool swimming: request SWIMMING_LAP_COUNT.
    val isSwimming: Boolean = false,
    // Strength/generic: request REP_COUNT.
    val isRepBased: Boolean = false,
) {
    Walking("Walking", ExerciseType.WALKING, HC_WALKING, isGpsBased = true),
    Running("Running", ExerciseType.RUNNING, HC_RUNNING, isGpsBased = true),
    Biking("Biking", ExerciseType.BIKING, HC_BIKING, isGpsBased = true),
    Hiking("Hiking", ExerciseType.HIKING, HC_HIKING, isGpsBased = true),
    PoolSwimming("Pool Swimming", ExerciseType.SWIMMING_POOL, HC_SWIMMING_POOL, isSwimming = true),
    StrengthTraining("Strength", ExerciseType.STRENGTH_TRAINING, HC_STRENGTH_TRAINING, isRepBased = true),
    Workout("Workout", ExerciseType.WORKOUT, HC_OTHER_WORKOUT, isRepBased = true);

    companion object {
        fun fromHealthServicesName(name: String): WorkoutType? =
            entries.firstOrNull { it.healthServicesType.name == name }
    }
}

// Mirror of ExerciseSessionRecord.EXERCISE_TYPE_* (connect-client 1.2.0-alpha04).
private const val HC_OTHER_WORKOUT = 0
private const val HC_BIKING = 8
private const val HC_HIKING = 37
private const val HC_RUNNING = 56
private const val HC_STRENGTH_TRAINING = 70
private const val HC_SWIMMING_POOL = 74
private const val HC_WALKING = 79
