package com.vayunmathur.watch.phone.data

import kotlinx.serialization.Serializable

/** Matches the JSON emitted by the watch's GattServerManager. */
@Serializable
data class WatchRecord(
    val id: Long,
    val type: String,
    val timestamp: Long,
    val value: Double,
    val delta: Double,
    val stationary: Boolean = false,
    // Present only for type == "ExerciseSession": the serialized session summary
    // (com.vayunmathur.watch.shared.data.ExerciseSessionSummary JSON).
    val session: String? = null,
)

@Serializable
data class WatchBatch(val records: List<WatchRecord>)
