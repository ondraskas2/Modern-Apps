package com.vayunmathur.findfamily.data

import android.location.Location
import kotlinx.serialization.Serializable
import org.maplibre.spatialk.geojson.Position
import kotlin.math.PI

@Serializable
data class Coord(val lat: Double, val lon: Double)

fun Coord.toPosition() = Position(lon, lat)

fun radians(degrees: Double) = degrees * PI / 180

fun havershine(p1: Coord, p2: Coord): Double {
    val results = FloatArray(1)
    Location.distanceBetween(p1.lat, p1.lon, p2.lat, p2.lon, results)
    return results[0].toDouble()
}