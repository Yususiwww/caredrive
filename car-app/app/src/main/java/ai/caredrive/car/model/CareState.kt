package ai.caredrive.car.model

import ai.caredrive.car.engine.Readiness

/** Risk level decided from driver condition and live signals. See docs/decisions. */
enum class Level { L0_NORMAL, L1_CAUTION, L2_WARNING, L3_STOPPED }

enum class Reason {
    NONE,
    /** Heart rate above the personal limit for a while (tension, stress). */
    HR_SUSTAINED,
    HR_HIGH,
    HR_LOW,
    IHRN_NEW,
    GLUCOSE_LOW,
    /** Driven as long as today's break interval (drowsiness risk). */
    BREAK_DUE,
    BREAK_OVERDUE,
}

data class CareState(
    val level: Level = Level.L0_NORMAL,
    val reason: Reason = Reason.NONE,
    val heartRate: Int? = null,
    val glucose: Int? = null,
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
)
