package com.vayunmathur.weather.data

import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.util.roundCoord
import kotlinx.serialization.json.Json

/** Shared lenient JSON used for (de)serializing cached forecast payloads. */
val weatherJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Encode [forecast] and upsert it into the cache for [latitude]/[longitude],
 * rounding the coordinates to the cache's 4-decimal key. Centralizes the
 * cache-write + JSON-encode that the view model, refresh worker, and widget
 * would otherwise each duplicate.
 */
suspend fun WeatherDao.writeForecastCache(
    latitude: Double,
    longitude: Double,
    forecast: ForecastResponse,
    fetchedAtEpochMs: Long = System.currentTimeMillis(),
) {
    upsertCache(
        WeatherCache(
            latRounded = roundCoord(latitude),
            lonRounded = roundCoord(longitude),
            forecastJson = weatherJson.encodeToString(forecast),
            fetchedAtEpochMs = fetchedAtEpochMs,
        )
    )
}
