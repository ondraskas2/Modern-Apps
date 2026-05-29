package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.maps.data.AmenityDatabase
import com.vayunmathur.maps.data.AmenityEntity
import com.vayunmathur.maps.data.OpeningHours
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.geojson.Position

/**
 * Owns the text query and FTS-backed result list for the maps search page.
 *
 * The viewport bounding box is supplied per-query because it is determined by
 * the camera in the calling composable; we don't observe it as state.
 */
class MapsSearchViewModel(application: Application) : AndroidViewModel(application) {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<AmenityEntity>>(emptyList())
    val results: StateFlow<List<AmenityEntity>> = _results.asStateFlow()

    /**
     * Updates the search text and (asynchronously) the result list filtered by
     * the supplied bounding box. Queries shorter than two characters clear the
     * results without hitting the DB.
     */
    fun setQuery(
        query: String,
        db: AmenityDatabase,
        west: Double,
        east: Double,
        south: Double,
        north: Double,
    ) {
        _query.value = query
        viewModelScope.launch {
            _results.value = if (query.length >= 2) {
                withContext(Dispatchers.IO) {
                    db.amenityDao().getInBBox(
                        query = "*$query*",
                        latMin = south,
                        latMax = north,
                        lonMin = west,
                        lonMax = east,
                    )
                }
            } else {
                emptyList()
            }
        }
    }

    /** Resets the search state. */
    fun reset() {
        _query.value = ""
        _results.value = emptyList()
    }

    /**
     * Loads tags for [amenity], builds a [SpecificFeature.Restaurant], and
     * invokes [onFeature] back on the main thread. Used by composables to bind
     * the result into the selected-feature flow.
     */
    fun resolveAmenity(
        amenity: AmenityEntity,
        db: AmenityDatabase,
        onFeature: (SpecificFeature.Restaurant) -> Unit,
    ) {
        viewModelScope.launch {
            val tags = withContext(Dispatchers.IO) {
                db.tagDao().getTags(amenity.id).associate { it.key to it.value }
            }
            val feature = SpecificFeature.Restaurant(
                name = tags["name"] ?: "",
                phone = tags["phone"],
                website = tags["website"],
                menu = tags["website:menu"],
                openingHours = tags["opening_hours"]?.let { OpeningHours.from(it) },
                position = Position(amenity.lon, amenity.lat),
            )
            onFeature(feature)
        }
    }
}
