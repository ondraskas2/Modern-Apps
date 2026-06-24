package com.vayunmathur.weather.util

import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.ForecastResponse

/**
 * Conditions resolved for whatever the user has selected (an hour, a day, or
 * nothing). The headline fields drive `CurrentWeatherCard`; [blockCurrent]
 * feeds the humidity/wind/pressure/visibility blocks; [uvIndexMax] and the
 * sunrise/sunset ISO strings feed the UV + Sun blocks.
 *
 * Resolution prefers hourly data for a selected hour (Open-Meteo exposes
 * apparent temp, humidity, dew point, wind, pressure, visibility and UV
 * hourly), falls back to the matching day, and finally to `current`.
 */
data class ResolvedConditions(
    val weatherCode: Int,
    val isDay: Boolean,
    val temperature: Double,
    val apparentTemperature: Double?,
    val high: Double?,
    val low: Double?,
    val uvIndexMax: Double?,
    val sunriseIso: String?,
    val sunsetIso: String?,
    val precipitationSum: Double?,
    val blockCurrent: Current,
)

/**
 * Resolve [selected] against [forecast]. Requires a non-null `current`
 * (callers only render the headline/blocks when current observations exist).
 */
fun resolveConditions(
    forecast: ForecastResponse,
    selected: SelectedDateOrTime?,
): ResolvedConditions? {
    val current = forecast.current ?: return null
    val daily = forecast.daily
    val hourly = forecast.hourly

    fun dailyIndexFor(isoDate: String): Int? =
        daily?.time?.indexOf(isoDate)?.takeIf { it >= 0 }

    return when (selected) {
        null -> ResolvedConditions(
            weatherCode = current.weatherCode,
            isDay = current.isDay == 1,
            temperature = current.temperature,
            apparentTemperature = current.apparentTemperature,
            high = daily?.temperatureMax?.firstOrNull(),
            low = daily?.temperatureMin?.firstOrNull(),
            uvIndexMax = daily?.uvIndexMax?.firstOrNull(),
            sunriseIso = daily?.sunrise?.firstOrNull(),
            sunsetIso = daily?.sunset?.firstOrNull(),
            precipitationSum = daily?.precipitationSum?.firstOrNull(),
            blockCurrent = current,
        )

        is SelectedDateOrTime.Time -> {
            val h = hourly?.time?.indexOf(selected.isoTime)?.takeIf { it >= 0 }
            if (hourly == null || h == null) {
                // Selection no longer present (e.g. after a refresh) — behave
                // as if nothing is selected.
                return resolveConditions(forecast, null)
            }
            val d = dailyIndexFor(selected.isoTime.substringBefore('T'))
            val hourCurrent = current.copy(
                time = hourly.time.getOrNull(h) ?: current.time,
                temperature = hourly.temperature.getOrNull(h) ?: current.temperature,
                apparentTemperature = hourly.apparentTemperature.getOrNull(h) ?: current.apparentTemperature,
                relativeHumidity = hourly.relativeHumidity.getOrNull(h) ?: current.relativeHumidity,
                dewPoint = hourly.dewPoint.getOrNull(h) ?: current.dewPoint,
                weatherCode = hourly.weatherCode.getOrNull(h) ?: current.weatherCode,
                windSpeed = hourly.windSpeed.getOrNull(h) ?: current.windSpeed,
                windDirection = hourly.windDirection.getOrNull(h) ?: current.windDirection,
                pressureMsl = hourly.pressureMsl.getOrNull(h) ?: current.pressureMsl,
                visibility = hourly.visibility.getOrNull(h) ?: current.visibility,
                isDay = hourly.isDay.getOrNull(h) ?: current.isDay,
            )
            ResolvedConditions(
                weatherCode = hourCurrent.weatherCode,
                isDay = hourCurrent.isDay == 1,
                temperature = hourCurrent.temperature,
                apparentTemperature = hourly.apparentTemperature.getOrNull(h),
                high = d?.let { daily?.temperatureMax?.getOrNull(it) },
                low = d?.let { daily?.temperatureMin?.getOrNull(it) },
                uvIndexMax = hourly.uvIndex.getOrNull(h)
                    ?: d?.let { daily?.uvIndexMax?.getOrNull(it) },
                sunriseIso = d?.let { daily?.sunrise?.getOrNull(it) },
                sunsetIso = d?.let { daily?.sunset?.getOrNull(it) },
                precipitationSum = hourly.precipitation.getOrNull(h),
                blockCurrent = hourCurrent,
            )
        }

        is SelectedDateOrTime.Day -> {
            val d = dailyIndexFor(selected.isoDate)
            if (daily == null || d == null) {
                return resolveConditions(forecast, null)
            }
            ResolvedConditions(
                weatherCode = daily.weatherCode.getOrNull(d) ?: current.weatherCode,
                isDay = true,
                temperature = daily.temperatureMax.getOrNull(d) ?: current.temperature,
                apparentTemperature = null,
                high = daily.temperatureMax.getOrNull(d),
                low = daily.temperatureMin.getOrNull(d),
                uvIndexMax = daily.uvIndexMax.getOrNull(d),
                sunriseIso = daily.sunrise.getOrNull(d),
                sunsetIso = daily.sunset.getOrNull(d),
                precipitationSum = daily.precipitationSum.getOrNull(d),
                // No daily equivalent for humidity/wind/pressure/visibility —
                // keep showing the live current values for those blocks.
                blockCurrent = current,
            )
        }
    }
}
