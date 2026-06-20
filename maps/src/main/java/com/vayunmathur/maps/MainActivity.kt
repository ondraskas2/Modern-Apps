package com.vayunmathur.maps

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.downloadservice.InitialDownloadChecker
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.maps.data.AmenityDatabase
import com.vayunmathur.maps.data.buildAmenityDatabase
import com.vayunmathur.maps.ui.DownloadedMapsPage
import com.vayunmathur.maps.ui.MapPage
import com.vayunmathur.maps.ui.SearchPage
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.MapsZonesViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.maplibre.android.log.Logger
import java.io.File

/**
 * Copies the bundled `world_z0-6.pmtiles` asset into [Context.filesDir] on
 * first launch and returns the `pmtiles://` URL the map style references.
 *
 * MUST be called off the main thread — the pmtiles file is ~tens of MB and
 * copying it synchronously in `onCreate` previously caused an ANR on first
 * launch / after a clean install.
 */
fun ensurePmtilesReady(context: Context): String {
    val fileName = "world_z0-6.pmtiles"
    val outFile = File(context.filesDir, fileName)

    if (!outFile.exists()) {
        context.assets.open(fileName).use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
    return "pmtiles://file://${outFile.absolutePath}"
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ds = DataStoreUtils.getInstance(this)
        Logger.setVerbosity(Logger.INFO)
//
//        runBlocking {
//            ds.setBoolean("dbSetupComplete", false)
//            ds.setBoolean("done_metadata.bin", false)
//            ds.setBoolean("done_road_names.bin", false)
//            File(getExternalFilesDir(null), "metadata.bin").delete()
//            File(getExternalFilesDir(null), "road_names.bin").delete()
//        }

        setContent {
            DynamicTheme {
                // Copy the bundled pmtiles asset off the main thread on first
                // launch (~tens of MB). Until it's ready we show whatever the
                // download-checker decides; afterwards the map style URL is
                // available via Application-level state (existing callers
                // re-invoke ensurePmtilesReady but it's a fast no-op once the
                // file is on disk).
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) { ensurePmtilesReady(this@MainActivity) }
                }
                InitialDownloadChecker(ds, listOf(
                    Triple("https://data.vayunmathur.com/amenities.db", "amenities.db", getString(R.string.downloading_amenity_database)),
                    Triple("https://data.vayunmathur.com/metadata.bin", "metadata.bin", getString(R.string.downloading_navigation_metadata)),
                    Triple("https://data.vayunmathur.com/road_names.bin", "road_names.bin", getString(R.string.downloading_road_data)),
                    Triple("https://data.vayunmathur.com/intermediate.bin", "intermediate.bin", getString(R.string.downloading_road_data))
                )) {
                    val db = remember { buildAmenityDatabase(this@MainActivity) }
                    val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // POST_NOTIFICATIONS is runtime-grantable on API 33+
                        // and required for the navigation foreground-service
                        // notification to actually display (otherwise the
                        // service runs invisibly and the user can't End Trip
                        // from outside the app).
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.POST_NOTIFICATIONS,
                        )
                    } else {
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    PermissionsChecker(perms, getString(R.string.grant_location_permission)) {
                        Navigation(db)
                    }
                }
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object MapPage: Route
    @Serializable
    data object DownloadedMapsPage: Route

    @Serializable
    data class SearchPage(val idx: Int?, val east: Double, val west: Double, val north: Double, val south: Double): Route
}

@Composable
fun Navigation(
    db: AmenityDatabase,
    viewModel: SelectedFeatureViewModel = viewModel(),
    searchViewModel: MapsSearchViewModel = viewModel(),
    zonesViewModel: MapsZonesViewModel = viewModel(),
) {
    val backStack = rememberNavBackStack<Route>(Route.MapPage)
    MainNavigation(backStack) {
        entry<Route.MapPage> {
            MapPage(backStack, viewModel, zonesViewModel, db)
        }
        entry<Route.DownloadedMapsPage> {
            DownloadedMapsPage(backStack, zonesViewModel)
        }
        entry<Route.SearchPage> {
            SearchPage(backStack, viewModel, searchViewModel, db, it.idx, it.east, it.west, it.north, it.south)
        }
    }
}