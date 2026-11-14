package ai.caredrive.car.engine

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A place the driver registered on the phone (home, work). No "stopped in traffic" question within [Stationary.PLACE_RADIUS_M]. */
@Serializable
data class Place(val name: String, val lat: Double, val lon: Double)

/**
 * "정체 확인": the car is in gear (not P) but has not moved for 2 minutes and the driver has not touched
 * brake or steering for a minute. Usually a traffic jam (the car offers music), but it is also what a driver who
 * lost consciousness at a red light looks like, so no answer escalates like NO_PULSE.
 *
 * All car signals are fed in with the same (simulated) clock. Unknown signals (null) never block the check,
 * except where noted. Thresholds are design examples.
 */
class Stationary {
    private var parked = true
    private var stillSince: Long? = null
    /** Speed samples (time, km/h) for the last 5 minutes, plus the one before the window (the value then). */
    private val speeds = ArrayDeque<Pair<Long, Int>>()
    private var lastInputAt: Long? = null
    private var seatOccupied: Boolean? = null
    private var beltWasBuckled = false
    private var beltUnbuckledAt: Long? = null
    private var doorOpenedAt: Long? = null
    private var position: Pair<Double, Double>? = null
    var places: List<Place> = emptyList()

    fun onGear(now: Long, parked: Boolean) {
        this.parked = parked
        if (parked) stillSince = null
    }

    fun onSpeed(now: Long, kmh: Int) {
        speeds += now to kmh
        while (speeds.size > 1 && now - speeds[1].first > WINDOW_MS) speeds.removeFirst()
        // Any movement (stop-and-go included) restarts the still time.
        stillSince = if (kmh > 0) null else stillSince ?: now
    }

    /** Brake toggle or steering change above 2°. */
    fun onDriverInput(now: Long) { lastInputAt = now }

    fun onSeatOccupied(occupied: Boolean?) { seatOccupied = occupied }

    /**
     * Seatbelt: only unbuckling counts (the driver getting out). A belt never reported as buckled
     * (unknown, or a car/emulator without the sensor) does not block the check.
     */
    fun onBelt(now: Long, buckled: Boolean?) {
        if (buckled == true) { beltWasBuckled = true; beltUnbuckledAt = null }
        if (buckled == false && beltWasBuckled) beltUnbuckledAt = now
    }

    fun onDoor(now: Long, open: Boolean) { if (open) doorOpenedAt = now }

    fun onPosition(lat: Double, lon: Double) { position = lat to lon }

    /** Highest speed in the last 5 minutes (the value at the window start counts). */
    fun maxSpeed(now: Long): Int? {
        val from = now - WINDOW_MS
        val inWindow = speeds.filter { it.first >= from }.map { it.second }
        val atStart = speeds.lastOrNull { it.first < from }?.second
        return (inWindow + listOfNotNull(atStart)).maxOrNull()
    }

    fun stillSeconds(now: Long): Int = stillSince?.let { ((now - it) / 1000).toInt() } ?: 0

    /** Why the check is not due right now, or null when it is. For the log and the tests. */
    fun blocker(now: Long): String? {
        val still = stillSince
        return when {
            parked -> "gear P"
            still == null || now - still < STILL_MS -> "moving"
            lastInputAt?.let { now - it < NO_INPUT_MS } == true -> "driver input"
            seatOccupied == false -> "seat empty"
            beltUnbuckledAt?.let { it >= still } == true -> "belt unbuckled"
            doorOpenedAt?.let { it >= still } == true -> "door opened"
            inPlace() -> "registered place"
            (maxSpeed(now) ?: 0) < MIN_RECENT_KMH -> "slow area"
            else -> null
        }
    }

    fun due(now: Long) = blocker(now) == null

    private fun inPlace(): Boolean {
        val (lat, lon) = position ?: return false
        return places.any { distanceM(lat, lon, it.lat, it.lon) <= PLACE_RADIUS_M }
    }

    fun reset() {
        stillSince = null; speeds.clear(); lastInputAt = null; doorOpenedAt = null; beltUnbuckledAt = null
    }

    /** Demo only: pretend the car came to a stop from 50 km/h [stillSec] seconds ago with no driver input. */
    fun demoBackdate(now: Long, stillSec: Int) {
        parked = false
        speeds.clear()
        speeds += (now - stillSec * 1000L - 20_000L) to 50
        speeds += (now - stillSec * 1000L) to 0
        stillSince = now - stillSec * 1000L
        lastInputAt = null; doorOpenedAt = null; beltUnbuckledAt = null
    }

    companion object {
        const val STILL_MS = 120_000L
        const val NO_INPUT_MS = 60_000L
        const val WINDOW_MS = 5 * 60_000L
        const val MIN_RECENT_KMH = 30
        const val PLACE_RADIUS_M = 300.0

        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }
    }
}
