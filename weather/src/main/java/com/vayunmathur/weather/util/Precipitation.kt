package com.vayunmathur.weather.util

import com.vayunmathur.weather.network.Minutely15

/**
 * Short-range precipitation nowcast built from Open-Meteo's 15-minute
 * [Minutely15] series. Returns a human string like "Raining now",
 * "Rain in ~30 min", or "No rain in the next 2 hours" — or null when there
 * is no usable data.
 */
fun precipitationNowcast(minutely15: Minutely15?, utcOffsetSeconds: Int): String? {
    val series = minutely15 ?: return null
    if (series.time.isEmpty() || series.precipitation.isEmpty()) return null

    val now = System.currentTimeMillis() / 1000
    val horizon = now + 2 * 3600

    data class Slot(val epoch: Long, val precip: Double)
    val slots = series.time.indices.mapNotNull { i ->
        val epoch = parseLocalIsoToEpochSec(series.time[i], utcOffsetSeconds) ?: return@mapNotNull null
        Slot(epoch, series.precipitation.getOrNull(i) ?: 0.0)
    }
    if (slots.isEmpty()) return null

    // Slot covering "now" (each slot spans 15 min).
    val currentSlot = slots.lastOrNull { it.epoch <= now }
    if (currentSlot != null && currentSlot.epoch >= now - 15 * 60 && currentSlot.precip > 0.0) {
        return "Raining now"
    }

    val next = slots.firstOrNull { it.epoch > now && it.epoch <= horizon && it.precip > 0.0 }
        ?: return "No rain in the next 2 hours"

    val mins = ((next.epoch - now) / 60.0).toInt()
    val rounded = ((mins + 2) / 5) * 5 // nearest 5 min
    return if (rounded <= 0) "Rain starting now" else "Rain in ~$rounded min"
}
