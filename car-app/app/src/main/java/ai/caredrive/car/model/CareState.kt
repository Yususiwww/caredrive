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
    /** Watch on the wrist but no pulse detected. Highest urgency. */
    NO_PULSE,
    /** Drowsiness score (sleep debt, time driven, time of day, HR drop, steering) above the limit. */
    DROWSY,
}

/** A question the car is asking aloud and on screen, waiting for the driver's answer. */
enum class Ask {
    /** "심장 박동이 평소와 달라요. 괜찮으세요?" → [도움이 필요해요] [괜찮아요] */
    CARDIAC_CHECK,
    /** "괜찮으세요? 대답해 주세요." → [괜찮아요]; 60 s pre-alert, 180 s call */
    NO_PULSE,
    /** "많이 긴장하신 것 같아요. 힘든 일이 있었나요?" → [좀 그래요] [괜찮아요] */
    STRESS_TALK,
    /** "졸음이 올 수 있어요. 창문을 조금 열까요?" → [쉬어 갈게요] [괜찮아요] */
    DROWSY,
}

/** Open question on screen. [secondsLeft] counts down to the next escalation (null = no timer). */
data class Checkin(
    val ask: Ask,
    val startedMs: Long,
    val secondsLeft: Int? = null,
    /** Total seconds of the timer, for the ring gauge. */
    val totalSeconds: Int? = null,
    /** NO_PULSE after 60 s: contacts already got a pre-alert text. */
    val preAlerted: Boolean = false,
)

enum class PulseState { PRESENT, ABSENT, OFF_WRIST }

/** Who the phone has been asked to reach, shown as a checklist on screen. */
data class Contacting(val contacts: Boolean, val emergency119: Boolean, val reason: Reason)

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
    /** Question the car is waiting on, or null. */
    val checkin: Checkin? = null,
    val pulse: PulseState = PulseState.PRESENT,
    /** 0–100 drowsiness score (design example), null when there is nothing to base it on. */
    val drowsiness: Int? = null,
    /** Hazard lights on, read from the car's turn-signal state (the app cannot switch them itself). */
    val hazardOn: Boolean = false,
    /** Playlist the phone is playing at the car's request, e.g. "편안한 플레이리스트". */
    val nowPlaying: String? = null,
    /** Set once the phone has been asked to contact people. */
    val contacting: Contacting? = null,
    /** Vehicle speed in km/h when the car reports it (null on cars/emulators without it). */
    val speedKmh: Int? = null,
) {
    companion object { const val HR_WINDOW_MS = 30 * 60_000L }
}
