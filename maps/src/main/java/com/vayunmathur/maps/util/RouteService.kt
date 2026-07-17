package com.vayunmathur.maps.util

import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.Serializable
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object RouteService {
    private const val ROUTES_URL = "https://api.vayunmathur.com/maps/route"

    suspend fun computeRoute(
        features: SpecificFeature.Route,
        userPosition: Position,
        travelMode: TravelMode
    ): Route? {
        val originPos = features.waypoints.first()?.position ?: userPosition
        val destPos = features.waypoints.last()?.position ?: userPosition
        val intermediates = features.waypoints.subList(1, features.waypoints.size - 1).map { it?.position ?: userPosition }

        // Construct simplified request for our Deno server
        val request = ServerRouteRequest(
            origin = ServerLatLng(originPos.latitude, originPos.longitude),
            destination = ServerLatLng(destPos.latitude, destPos.longitude),
            intermediates = intermediates.map { ServerLatLng(it.latitude, it.longitude) },
            travelMode = travelMode
        )

        return try {
            val serverRoute: ServerRouteResponse = NetworkClient.callJson(
                url = ROUTES_URL,
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = request
            )

            val steps = serverRoute.step.map { step ->
                Step(
                    distanceMeters = step.distanceMeters,
                    staticDuration = Duration.parse(step.staticDuration),
                    polyline = step.polyline.map { Position(it.longitude, it.latitude) },
                    navInstruction = step.navInstruction,
                    travelMode = step.travelMode,
                    transitDetails = step.transitDetails
                )
            }
            // Rebuild the full polyline from concatenated step polylines
            // (de-duplicating the shared join vertex between steps). The
            // server's `polyline` field is decoded independently and isn't
            // guaranteed to be vertex-for-vertex aligned to the step
            // polylines (different simplification / rounding). PolylineIndex
            // (the navigation snap engine) relies on the route polyline being
            // exactly the step concatenation so its per-step vertex ranges
            // line up; mismatches there cause wrong ETAs / step-transitions.
            val rebuiltPolyline = mutableListOf<Position>()
            for (step in steps) {
                if (step.polyline.isEmpty()) continue
                if (rebuiltPolyline.isEmpty()) {
                    rebuiltPolyline.addAll(step.polyline)
                } else {
                    val first = step.polyline.first()
                    if (rebuiltPolyline.last() == first) {
                        rebuiltPolyline.addAll(step.polyline.drop(1))
                    } else {
                        rebuiltPolyline.addAll(step.polyline)
                    }
                }
            }
            return Route(
                duration = Duration.parse(serverRoute.duration),
                distanceMeters = serverRoute.distanceMeters,
                polyline = rebuiltPolyline,
                step = steps,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    enum class TravelMode {
        DRIVE, TRANSIT, WALK, BICYCLE
    }

    // --- DTOs for communication with Vayunmathur.com Server ---

    @Serializable
    data class ServerRouteRequest(
        val origin: ServerLatLng,
        val destination: ServerLatLng,
        val intermediates: List<ServerLatLng>,
        val travelMode: TravelMode
    )

    @Serializable
    data class ServerLatLng(val latitude: Double, val longitude: Double)

    @Serializable
    data class ServerRouteResponse(
        val duration: String,
        val distanceMeters: Double,
        val polyline: List<ServerLatLng>,
        val step: List<ServerStep>
    )

    @Serializable
    data class ServerStep(
        val distanceMeters: Double,
        val staticDuration: String,
        val polyline: List<ServerLatLng>,
        val navInstruction: API.NavInstruction,
        val travelMode: TravelMode,
        val transitDetails: API.TransitDetails? = null
    )

    // --- Existing Models reused for internal structure ---

    object API {
        @Serializable
        data class TransitDetails(val headsign: String, val stopCount: Int, val transitLine: TransitLine, val stopDetails: StopDetails, val feedName: String? = null)

        @Serializable
        data class StopDetails(val arrivalTime: String, val departureTime: String, val arrivalStop: Stop, val departureStop: Stop)

        @Serializable
        data class Stop(val name: String)

        @Serializable
        data class TransitLine(val name: String, val nameShort: String? = null, val color: String)

        @Serializable
        data class NavInstruction(val maneuver: Maneuver = Maneuver.MANEUVER_UNSPECIFIED, val instructions: String = "")

        @Serializable
        enum class Maneuver {
            MANEUVER_UNSPECIFIED, TURN_SLIGHT_LEFT, TURN_SHARP_LEFT, UTURN_LEFT, TURN_LEFT,
            TURN_SLIGHT_RIGHT, TURN_SHARP_RIGHT, UTURN_RIGHT, TURN_RIGHT, STRAIGHT,
            RAMP_LEFT, RAMP_RIGHT, MERGE, FORK_LEFT, FORK_RIGHT, FERRY, FERRY_TRAIN,
            ROUNDABOUT_LEFT, ROUNDABOUT_RIGHT, DEPART, NAME_CHANGE, WAIT, RIDE
        }
    }

    data class Route(
        override val duration: Duration,
        override val distanceMeters: Double,
        val polyline: List<Position>,
        val step: List<Step>,
    ): RouteType

    data class Step(
        val distanceMeters: Double,
        val staticDuration: Duration,
        val polyline: List<Position>,
        val navInstruction: API.NavInstruction,
        val travelMode: TravelMode,
        val transitDetails: API.TransitDetails? = null,
        val speedRatio: Double = 1.0
    )

    interface RouteType {
        val duration: Duration
        val distanceMeters: Double
    }

    class EmptyRoute: RouteType {
        override val duration: Duration = 0.seconds
        override val distanceMeters: Double = 0.0
    }
}
