package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Wraps [ZoneDownloadManager] so composables can observe downloaded /
 * downloading zone state without each one re-creating a manager (and the
 * associated polling flows). The actual file/disk work stays in
 * [ZoneDownloadManager].
 */
class MapsZonesViewModel(application: Application) : AndroidViewModel(application) {

    private val zoneManager = ZoneDownloadManager(application)

    val downloadedZones: StateFlow<List<Int>> = zoneManager
        .getDownloadedZonesFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    val downloadingZones: StateFlow<Map<Int, Float>> = zoneManager
        .getDownloadingZonesFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap(),
        )

    init {
        // Ensure newly-finished zones are wired into OfflineRouter as soon as
        // they appear, mirroring the previous LaunchedEffect(downloadedZones)
        // in MapPage. Side-effect runs for the VM's lifetime; the underlying
        // operation is idempotent.
        downloadedZones
            .onEach { zones -> zones.forEach { OfflineRouter.ensureZoneLoaded(it) } }
            .launchIn(viewModelScope)
    }

    fun getZoneStatus(zoneId: Int): ZoneDownloadManager.ZoneStatus =
        zoneManager.getZoneStatus(zoneId)

    fun startDownload(zoneId: Int) {
        zoneManager.startDownload(zoneId)
    }

    fun deleteZone(zoneId: Int) {
        zoneManager.deleteZone(zoneId)
    }
}
