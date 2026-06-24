package com.vayunmathur.weather.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.data.WeatherCache
import com.vayunmathur.weather.data.WeatherDao
import com.vayunmathur.weather.data.WeatherRefreshWorker
import com.vayunmathur.weather.network.AirQualityResponse
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.WeatherApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Per-location forecast state held in [WeatherViewModel.forecasts]. */
data class ForecastUiState(
    val forecast: ForecastResponse? = null,
    val airQuality: AirQualityResponse? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    val fetchedAtEpochMs: Long = 0,
)

/**
 * A user-chosen hour or day to inspect. Identified by the Open-Meteo ISO
 * string (not an array index) so a refresh that shifts the arrays can't
 * corrupt the selection.
 */
sealed interface SelectedDateOrTime {
    /** [isoTime] matches a value in `Hourly.time`. */
    data class Time(val isoTime: String) : SelectedDateOrTime
    /** [isoDate] matches a value in `Daily.time`. */
    data class Day(val isoDate: String) : SelectedDateOrTime
}

/**
 * Holds saved locations, per-location forecast state, and the user's unit
 * prefs. Mirrors the manual-Factory pattern used everywhere else in this
 * repo (see [com.vayunmathur.passwords.util.PasswordsViewModel]).
 */
class WeatherViewModel(
    application: Application,
    private val dao: WeatherDao,
) : AndroidViewModel(application) {

    private val json = Json { ignoreUnknownKeys = true }

    val savedLocations: StateFlow<List<SavedLocation>> = dao.observeLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Per-location forecast state, keyed by [SavedLocation.id]. */
    private val _forecasts = MutableStateFlow<Map<Long, ForecastUiState>>(emptyMap())
    val forecasts: StateFlow<Map<Long, ForecastUiState>> = _forecasts.asStateFlow()

    /**
     * The hour/day the user is inspecting, or null for "now / today". Reset
     * whenever the active location changes (see [clearSelection]).
     */
    private val _selected = MutableStateFlow<SelectedDateOrTime?>(null)
    val selectedDateOrTime: StateFlow<SelectedDateOrTime?> = _selected.asStateFlow()

    fun selectTime(isoTime: String) { _selected.value = SelectedDateOrTime.Time(isoTime) }

    fun selectDay(isoDate: String) { _selected.value = SelectedDateOrTime.Day(isoDate) }

    fun clearSelection() { _selected.value = null }

    /** Select the hour, or clear it if it's already the selected one. */
    fun toggleTime(isoTime: String) {
        val current = _selected.value
        _selected.value = if (current is SelectedDateOrTime.Time && current.isoTime == isoTime) {
            null
        } else {
            SelectedDateOrTime.Time(isoTime)
        }
    }

    /** Select the day, or clear it if it's already the selected one. */
    fun toggleDay(isoDate: String) {
        val current = _selected.value
        _selected.value = if (current is SelectedDateOrTime.Day && current.isoDate == isoDate) {
            null
        } else {
            SelectedDateOrTime.Day(isoDate)
        }
    }

    init {
        WeatherRefreshWorker.scheduleHourlyRefresh(application)
    }

    /**
     * Ensure there's a fresh-enough forecast for [location].
     *
     * The 15-minute staleness decision lives here, so callers can poll
     * freely: hydrate from the on-disk cache first so the UI gets something
     * immediately, then kick off a background refresh only when the data is
     * missing/stale or [force] is set. Repeated calls while a refresh is in
     * flight are no-ops.
     */
    fun ensureForecast(location: SavedLocation, force: Boolean = false) {
        val existing = _forecasts.value[location.id]
        if (existing != null && existing.refreshing) return

        val isStale = existing?.forecast == null ||
            (System.currentTimeMillis() - existing.fetchedAtEpochMs) >= STALE_THRESHOLD_MS
        if (!force && !isStale) return

        viewModelScope.launch {
            // 1. Hydrate from cache if we don't have anything in memory yet.
            if (existing?.forecast == null) {
                val cache = dao.getCache(roundCoord(location.latitude), roundCoord(location.longitude))
                if (cache != null) {
                    runCatching { json.decodeFromString<ForecastResponse>(cache.forecastJson) }
                        .onSuccess { decoded ->
                            _forecasts.update { current ->
                                current + (location.id to ForecastUiState(
                                    forecast = decoded,
                                    refreshing = true,
                                    fetchedAtEpochMs = cache.fetchedAtEpochMs,
                                ))
                            }
                        }
                } else {
                    _forecasts.update { it + (location.id to ForecastUiState(refreshing = true)) }
                }
            } else {
                _forecasts.update { current ->
                    current + (location.id to existing.copy(refreshing = true))
                }
            }

            // 2. Network refresh — forecast + air quality fetched in parallel.
            data class FetchResult(val forecast: kotlin.Result<ForecastResponse>, val air: AirQualityResponse?)
            val fetched: FetchResult = coroutineScope {
                val forecastDeferred = async {
                    runCatching { WeatherApi.forecast(location.latitude, location.longitude) }
                }
                val airQualityDeferred = async {
                    runCatching { WeatherApi.airQuality(location.latitude, location.longitude) }.getOrNull()
                }
                FetchResult(forecastDeferred.await(), airQualityDeferred.await())
            }
            val forecastResult = fetched.forecast
            val airQuality = fetched.air

            forecastResult
                .onSuccess { fresh ->
                    val now = System.currentTimeMillis()
                    dao.upsertCache(
                        WeatherCache(
                            latRounded = roundCoord(location.latitude),
                            lonRounded = roundCoord(location.longitude),
                            forecastJson = json.encodeToString(fresh),
                            fetchedAtEpochMs = now,
                        )
                    )
                    _forecasts.update { current ->
                        current + (location.id to ForecastUiState(
                            forecast = fresh,
                            airQuality = airQuality,
                            refreshing = false,
                            error = null,
                            fetchedAtEpochMs = now,
                        ))
                    }
                }
                .onFailure { e ->
                    _forecasts.update { current ->
                        val prev = current[location.id]
                        current + (location.id to (prev?.copy(
                            refreshing = false,
                            error = e.message ?: "Failed to load forecast",
                        ) ?: ForecastUiState(refreshing = false, error = e.message)))
                    }
                }
        }
    }

    fun deleteLocation(location: SavedLocation) {
        viewModelScope.launch {
            dao.deleteLocation(location)
            _forecasts.update { it - location.id }
        }
    }

    /**
     * Insert a manually-picked location at the end of the list. The current
     * row count drives [SavedLocation.displayOrder] so the new pin lands last.
     */
    fun addLocation(name: String, country: String, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            val existing = dao.getLocations()
            dao.insertLocation(
                SavedLocation(
                    name = name,
                    country = country,
                    latitude = latitude,
                    longitude = longitude,
                    displayOrder = existing.size,
                    isCurrent = false,
                )
            )
        }
    }

    /** Replace (or insert) the single "current device" row with a fresh fix. */
    fun setCurrentLocation(name: String, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            dao.replaceCurrentDeviceLocation(
                SavedLocation(
                    name = name.ifBlank { "Current location" },
                    country = "",
                    latitude = latitude,
                    longitude = longitude,
                    displayOrder = -1, // current row sorts first
                    isCurrent = true,
                )
            )
        }
    }

    companion object {
        /** Forecasts older than this are refreshed on the next poll. */
        const val STALE_THRESHOLD_MS = 15 * 60 * 1000L
    }

}

class WeatherViewModelFactory(
    private val application: Application,
    private val dao: WeatherDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return WeatherViewModel(application, dao) as T
    }
}
