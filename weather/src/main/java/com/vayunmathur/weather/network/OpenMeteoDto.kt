package com.vayunmathur.weather.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of the Open-Meteo `/v1/forecast` response we actually use.
 * Endpoint: https://api.open-meteo.com/v1/forecast
 *
 * We always request: current weather + hourly arrays + daily arrays in metric
 * units. Unit conversion for the UI happens at display time so swapping
 * Settings doesn't require a re-fetch.
 */
@Serializable
data class ForecastResponse(
    val latitude: Double,
    val longitude: Double,
    val timezone: String? = null,
    @SerialName("timezone_abbreviation") val timezoneAbbreviation: String? = null,
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val current: Current? = null,
    val hourly: Hourly? = null,
    val daily: Daily? = null,
    @SerialName("minutely_15") val minutely15: Minutely15? = null,
)

@Serializable
data class Minutely15(
    val time: List<String> = emptyList(),
    @SerialName("precipitation") val precipitation: List<Double> = emptyList(),
)

@Serializable
data class Current(
    val time: String,
    @SerialName("temperature_2m") val temperature: Double,
    @SerialName("apparent_temperature") val apparentTemperature: Double,
    @SerialName("relative_humidity_2m") val relativeHumidity: Int,
    @SerialName("dew_point_2m") val dewPoint: Double = 0.0,
    @SerialName("weather_code") val weatherCode: Int,
    @SerialName("wind_speed_10m") val windSpeed: Double,
    @SerialName("wind_direction_10m") val windDirection: Int,
    @SerialName("pressure_msl") val pressureMsl: Double = 0.0,
    @SerialName("visibility") val visibility: Double = 0.0,
    @SerialName("is_day") val isDay: Int = 1,
)

@Serializable
data class Hourly(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m") val temperature: List<Double> = emptyList(),
    @SerialName("apparent_temperature") val apparentTemperature: List<Double> = emptyList(),
    @SerialName("relative_humidity_2m") val relativeHumidity: List<Int> = emptyList(),
    @SerialName("dew_point_2m") val dewPoint: List<Double> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int> = emptyList(),
    @SerialName("precipitation_probability") val precipitationProbability: List<Int> = emptyList(),
    @SerialName("precipitation") val precipitation: List<Double> = emptyList(),
    @SerialName("wind_speed_10m") val windSpeed: List<Double> = emptyList(),
    @SerialName("wind_direction_10m") val windDirection: List<Int> = emptyList(),
    @SerialName("pressure_msl") val pressureMsl: List<Double> = emptyList(),
    @SerialName("visibility") val visibility: List<Double> = emptyList(),
    @SerialName("uv_index") val uvIndex: List<Double> = emptyList(),
    @SerialName("is_day") val isDay: List<Int> = emptyList(),
)

@Serializable
data class Daily(
    val time: List<String> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int> = emptyList(),
    @SerialName("temperature_2m_max") val temperatureMax: List<Double> = emptyList(),
    @SerialName("temperature_2m_min") val temperatureMin: List<Double> = emptyList(),
    @SerialName("sunrise") val sunrise: List<String> = emptyList(),
    @SerialName("sunset") val sunset: List<String> = emptyList(),
    @SerialName("uv_index_max") val uvIndexMax: List<Double> = emptyList(),
    @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Int> = emptyList(),
    @SerialName("precipitation_sum") val precipitationSum: List<Double> = emptyList(),
)
