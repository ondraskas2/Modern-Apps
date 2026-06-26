package com.vayunmathur.weather.util

import com.vayunmathur.weather.network.ForecastResponse
import kotlinx.datetime.toLocalDateTime

/** A metric that has an hourly series we can plot. */
enum class WeatherMetric(val title: String) {
    Temperature("Temperature"),
    FeelsLike("Feels like"),
    Humidity("Humidity"),
    DewPoint("Dew point"),
    Precipitation("Precipitation"),
    WindSpeed("Wind speed"),
    WindGusts("Wind gusts"),
    Pressure("Pressure"),
    Visibility("Visibility"),
    CloudCover("Cloud cover"),
    UvIndex("UV index"),
}

/** A single (time, raw value) sample. Values are in API units (°C, km/h, hPa, m, %, mm). */
data class MetricPoint(val epochSec: Long, val value: Double)

/**
 * Extract the hourly series for [metric] over exactly one local calendar day
 * (midnight to midnight): the selected day/hour's date, or today when nothing
 * is selected. Values are raw API units; callers format them for display.
 */
fun metricSeries(
    forecast: ForecastResponse,
    metric: WeatherMetric,
    selected: SelectedDateOrTime?,
): List<MetricPoint> {
    val hourly = forecast.hourly ?: return emptyList()

    val raw: List<Double> = when (metric) {
        WeatherMetric.Temperature -> hourly.temperature
        WeatherMetric.FeelsLike -> hourly.apparentTemperature
        WeatherMetric.Humidity -> hourly.relativeHumidity.map { it.toDouble() }
        WeatherMetric.DewPoint -> hourly.dewPoint
        WeatherMetric.Precipitation -> hourly.precipitation
        WeatherMetric.WindSpeed -> hourly.windSpeed
        WeatherMetric.WindGusts -> hourly.windGusts
        WeatherMetric.Pressure -> hourly.pressureMsl
        WeatherMetric.Visibility -> hourly.visibility
        WeatherMetric.CloudCover -> hourly.cloudCover.map { it.toDouble() }
        WeatherMetric.UvIndex -> hourly.uvIndex
    }

    val targetDate = when (selected) {
        is SelectedDateOrTime.Day -> selected.isoDate
        is SelectedDateOrTime.Time -> selected.isoTime.substringBefore('T')
        // No selection: plot today (the location's local calendar day) so the
        // graph always runs midnight-to-midnight, never a rolling 24h window.
        null -> forecast.daily?.time?.firstOrNull() ?: localDate(forecast.utcOffsetSeconds)
    }

    val out = ArrayList<MetricPoint>()
    for (i in hourly.time.indices) {
        val value = raw.getOrNull(i) ?: continue
        val iso = hourly.time[i]
        if (iso.substringBefore('T') != targetDate) continue
        val epoch = parseLocalIsoToEpochSec(iso, forecast.utcOffsetSeconds) ?: continue
        out.add(MetricPoint(epoch, value))
    }
    return out
}

/** Today's date in the location's local time, as an ISO `yyyy-MM-dd` string. */
private fun localDate(utcOffsetSeconds: Int): String {
    val now = System.currentTimeMillis() / 1000
    return kotlin.time.Instant.fromEpochSeconds(now + utcOffsetSeconds)
        .toLocalDateTime(kotlinx.datetime.TimeZone.UTC).date.toString()
}
