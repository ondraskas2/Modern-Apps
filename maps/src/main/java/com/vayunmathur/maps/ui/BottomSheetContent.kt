package com.vayunmathur.maps.ui
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.round
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SelectedFeatureViewModel

@Composable
fun BottomSheetContent(viewModel: SelectedFeatureViewModel, selectedFeature: SpecificFeature?, setSelectedFeature: (SpecificFeature?) -> Unit, route: Map<RouteService.TravelMode, RouteService.RouteType?>?, selectedRouteType: RouteService.TravelMode, setSelectedRouteType: (RouteService.TravelMode) -> Unit, inactiveNavigation: SpecificFeature.Route?) {
    when (selectedFeature) {
        is SpecificFeature.Admin0Label -> {
            Column {
                Text(selectedFeature.name, style = MaterialTheme.typography.titleLarge)
                Text(selectedFeature.wikipedia, style = MaterialTheme.typography.bodyMedium)
            }
        }
        is SpecificFeature.Admin1Label -> {
            Column {
                Text(selectedFeature.name, style = MaterialTheme.typography.titleLarge)
                Text(selectedFeature.wikipedia, style = MaterialTheme.typography.bodyMedium)
            }
        }
        is SpecificFeature.Restaurant -> {
            RestaurantBottomSheet(viewModel, inactiveNavigation, selectedFeature) {
                if(inactiveNavigation == null) {
                    setSelectedFeature(SpecificFeature.Route(listOf(null, selectedFeature)))
                } else {
                    setSelectedFeature(SpecificFeature.Route(inactiveNavigation.waypoints + listOf(selectedFeature)))
                }
            }
        }
        is SpecificFeature.GenericPlace -> {
            RestaurantBottomSheet(viewModel, inactiveNavigation, selectedFeature) {
                if (inactiveNavigation == null) {
                    setSelectedFeature(SpecificFeature.Route(listOf(null, selectedFeature)))
                } else {
                    setSelectedFeature(
                        SpecificFeature.Route(
                            inactiveNavigation.waypoints + listOf(
                                selectedFeature
                            )
                        )
                    )
                }
            }
        }
        is SpecificFeature.TransitStop -> {
            TransitStopBottomSheet(inactiveNavigation, selectedFeature) {
                if (inactiveNavigation == null) {
                    setSelectedFeature(SpecificFeature.Route(listOf(null, selectedFeature)))
                } else {
                    setSelectedFeature(
                        SpecificFeature.Route(
                            inactiveNavigation.waypoints + listOf(
                                selectedFeature
                            )
                        )
                    )
                }
            }
        }
        is SpecificFeature.Route -> {
            if(route != null) {
                Column {
                    PrimaryTabRow(route.entries.indexOfFirst { it.key == selectedRouteType }) {
                        route.entries.forEach {
                            Tab(
                                selectedRouteType == it.key,
                                { setSelectedRouteType(it.key) }) {
                                val label = when(it.key) {
                                    RouteService.TravelMode.WALK -> stringResource(R.string.travel_mode_walk)
                                    RouteService.TravelMode.BICYCLE -> stringResource(R.string.travel_mode_bicycle)
                                    RouteService.TravelMode.DRIVE -> stringResource(R.string.travel_mode_drive)
                                    RouteService.TravelMode.TRANSIT -> stringResource(R.string.travel_mode_transit)
                                }
                                Text(label)
                            }
                        }
                    }
                    val route = route[selectedRouteType]
                    if(route != null) {
                        if(route !is RouteService.EmptyRoute) {
                            ListItem({ Text(route.duration.toString()) }, supportingContent = {
                                Text(stringResource(R.string.distance_km, (route.distanceMeters / 1000.0).round(2)))
                            })
                            Spacer(Modifier.height(8.dp))
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            when (route) {
                                is RouteService.Route -> {
                                    itemsIndexed(route.step) { idx, it ->
                                        Card(shape = verticalShape(idx, route.step.size)) {
                                            ListItem({
                                                Text(it.navInstruction.instructions)
                                            }, leadingContent = {
                                                it.navInstruction.maneuver.icon()?.let {
                                                    Icon(painterResource(it), null)
                                                }
                                            })
                                        }
                                    }
                                }

                                is RouteService.EmptyRoute -> {
                                    item {
                                        ListItem({
                                            Text(stringResource(R.string.no_route_found))
                                        })
                                    }
                                }
                            }
                        }
                    } else {
                        ListItem({
                            Text(stringResource(R.string.generating_route))
                        })
                    }
                }
            }
        }
        else -> Unit
    }
}