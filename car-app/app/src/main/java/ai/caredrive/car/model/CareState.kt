package ai.caredrive.car.model

import ai.caredrive.car.engine.Mood
import ai.caredrive.car.engine.Readiness

/** Risk level decided from driver condition and live signals. See docs/decisions. */
enum class Level { L0_NORMAL, L1_CAUTION, L2_WARNING, L3_STOPPED }

enum class Reason {
    NONE,
    /** Heart rate above the personal limit for a while (tension, stress). */
    HR_SUSTAINED,
    /** HRV-based stress above the limit for a while. */
    STRESS_HIGH,
    HR_HIGH,
    HR_LOW,
    IHRN_NEW,
    GLUCOSE_LOW,
    /** Driven as long as today's break interval (drowsiness risk). */
    BREAK_DUE,
    BREAK_OVERDUE,
}

/** Whether the watch data on screen is current. Shown honestly instead of silently using old numbers. */
enum class DataState {
    /** Fresh readings (or the built-in demo values when no phone has connected yet). */
    LIVE,
    /** Phone connected but no reading for a while (watch off the wrist, Bluetooth lost). */
    STALE,
    /** Phone that was sending data is no longer connected. */
    OFF,
}

data class GeoPos(val lat: Double, val lon: Double)

/** Nearest rest area / rest stop suggested to the driver. */
data class RestPreview(val name: String, val lat: Double, val lon: Double, val km: Double, val isRestStop: Boolean)

/** One heart-rate reading at simulated time [atMs]. */
data class HrSample(val atMs: Long, val bpm: Int)

data class CareState(
    val level: Level = Level.L0_NORMAL,
    val reason: Reason = Reason.NONE,
    val heartRate: Int? = null,
    val glucose: Int? = null,
    /** 0–100 HRV stress estimate, null without watch HRV. */
    val stress: Int? = null,
    val stressLimit: Int? = null,
    /** Mood the driver picked before departure. */
    val mood: Mood? = null,
    val readiness: Readiness? = null,
    val drivingMin: Int = 0,
    val nextBreakInMin: Int? = null,
    val connected: Boolean = false,
    /** True when the car is not being driven (gear P, or demo override). */
    val parked: Boolean = true,
    /** Driver accepted the rest-stop suggestion and navigation is set. */
    val guidingToRest: Boolean = false,
    /** Last thing the car did (voice, HVAC, navigation), shown small on screen. */
    val lastEvent: String? = null,
    /** Recent heart-rate readings for the trend chart (last [HR_WINDOW_MS]). */
    val hrHistory: List<HrSample> = emptyList(),
    val nowMs: Long = 0L,
    val restingHr: Int? = null,
    val hrLimit: Int? = null,
    val carPos: GeoPos? = null,
    /** Set while a rest-stop suggestion is on screen (L2 / guiding). */
    val restPreview: RestPreview? = null,
    val data: DataState = DataState.LIVE,
    /** Seconds left before 119 is called; null when no call is pending. The driver can cancel. */
    val emergencyCountdown: Int? = null,
) {
    companion object { const val HR_WINDOW_MS = 30 * 60_000L }
}
