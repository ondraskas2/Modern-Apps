package com.vayunmathur.library.intents.clock

import kotlinx.serialization.Serializable

/** Cross-app payload for the Clock app's `SetAlarmIntent`. Time is 24-hour. */
@Serializable
data class SetAlarmData(val hour: Int, val minute: Int, val label: String = "")

/** Cross-app payload for the Clock app's `SetTimerIntent`. */
@Serializable
data class SetTimerData(val seconds: Int, val label: String = "")
