package ai.caredrive.car.car

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class RestArea(val name: String, val lat: Double, val lon: Double, val kind: String, val osm: String)

@Serializable
private data class RestAreaFile(val source: String, val items: List<RestArea>)

/**
 * Korean highway rest areas (휴게소) and rest stops (졸음쉼터) from OpenStreetMap.
 * NaviHelper 3.0.5 has no POI search, so we pick the nearest one ourselves.
 */
class RestAreas(context: Context) {
    val items: List<RestArea> = context.assets.open("rest_areas.json").use {
        Json { ignoreUnknownKeys = true }.decodeFromString<RestAreaFile>(it.readBytes().decodeToString()).items
    }

    /** Nearest rest area, preferring ones ahead of the car (within ±70° of heading). */
    fun nearest(lat: Double, lon: Double, heading: Double?): Pair<RestArea, Double>? {
        val withDist = items.map { it to distanceKm(lat, lon, it.lat, it.lon) }
        val ahead = if (heading == null) withDist else withDist.filter { (r, _) ->
            angleDiff(bearing(lat, lon, r.lat, r.lon), heading) <= 70.0
        }
        return (ahead.ifEmpty { withDist }).minByOrNull { it.second }
    }

    companion object {
        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }

        fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lon2 - lon1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }

        private fun angleDiff(a: Double, b: Double): Double {
            val d = abs(a - b) % 360
            return if (d > 180) 360 - d else d
        }
    }
}
