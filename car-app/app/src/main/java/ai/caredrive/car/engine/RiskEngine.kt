package ai.caredrive.car.engine

import ai.caredrive.car.model.Level
import ai.caredrive.car.model.Reason

/** Signals that change while driving. */
data class LiveSignals(
    val heartRate: Int? = null,
    /** mg/dL, only for users with a continuous glucose monitor linked to Samsung Health. */
    val glucose: Int? = null,
    /** A new irregular heart rhythm notification arrived since the last update. */
    val newIhrn: Boolean = false,
)

data class Decision(
    val level: Level,
    val reason: Reason,
    val drivingMin: Int,
    /** Minutes until the next suggested break; negative when overdue. */
    val nextBreakInMin: Int,
)

/**
 * Turns driver condition + live signals + driving state into a level (L0–L3).
 * Stateful: tracks how long the car has been driven and how long a signal has lasted.
 * Time is passed in so the demo can run faster than real time.
 * Thresholds are design examples; real values need medical review.
 */
class RiskEngine(private var readiness: Readiness = ConditionRules.assess(DriverCondition())) {
    private var restingHr: Int = DEFAULT_RESTING_HR
    private var recentExercise = false

    private var drivingSince: Long? = null
    private var parkedSince: Long? = null
    private var hrHighSince: Long? = null
    private var cautionSince: Long? = null
    private var ihrnPending = false
    private var last = Decision(Level.L0_NORMAL, Reason.NONE, 0, readiness.breakIntervalMin)
    /** Driver said "괜찮아": the same reason stays quiet until this time. */
    private var snoozeUntil = 0L
    private var snoozedReason = Reason.NONE

    fun setCondition(c: DriverCondition) {
        readiness = ConditionRules.assess(c)
        restingHr = c.restingHr ?: DEFAULT_RESTING_HR
        recentExercise = (c.exerciseEndedMinAgo ?: Int.MAX_VALUE) <= 30
    }

    fun readiness() = readiness

    /** Driver said "괜찮아": quiet this reason for a while and go back to normal. */
    fun dismiss(now: Long) {
        snoozedReason = last.reason
        snoozeUntil = now + SNOOZE_MS
        cautionSince = null
        ihrnPending = false
        last = last.copy(level = Level.L0_NORMAL, reason = Reason.NONE)
    }

    /** Start a new trip: forget driving time, signals and snoozes. */
    fun reset() {
        drivingSince = null; parkedSince = null; hrHighSince = null; cautionSince = null
        ihrnPending = false; snoozeUntil = 0L; snoozedReason = Reason.NONE
        last = Decision(Level.L0_NORMAL, Reason.NONE, 0, readiness.breakIntervalMin)
    }

    fun update(now: Long, live: LiveSignals, driving: Boolean): Decision {
        if (live.newIhrn) ihrnPending = true
        trackDriving(now, driving)
        val drivingMin = drivingSince?.let { ((now - it) / MIN).toInt() } ?: 0
        val nextBreak = readiness.breakIntervalMin - drivingMin

        // After a warning, stopping the car switches to the after-stop actions until the break is over.
        val onBreak = parkedSince?.let { now - it < BREAK_RESET_MIN * MIN } ?: false
        if (!driving && onBreak && (last.level == Level.L2_WARNING || last.level == Level.L3_STOPPED)) {
            return remember(Decision(Level.L3_STOPPED, last.reason, drivingMin, nextBreak))
        }
        if (!driving) {
            ihrnPending = false
            return remember(Decision(Level.L0_NORMAL, Reason.NONE, drivingMin, nextBreak))
        }

        val hr = live.heartRate
        val hrLimit = restingHr + (if (readiness.factors.any { it.kind == FactorKind.RECENT_IHRN }) 20 else 30) +
            (if (recentExercise) 10 else 0)
        hrHighSince = if (hr != null && hr >= hrLimit) hrHighSince ?: now else null
        val hrHighMin = hrHighSince?.let { (now - it) / MIN } ?: 0

        val warning: Reason? = when {
            ihrnPending -> Reason.IHRN_NEW
            live.glucose != null && live.glucose < GLUCOSE_LOW -> Reason.GLUCOSE_LOW
            hr != null && hr < HR_VERY_LOW -> Reason.HR_LOW
            hr != null && hr > HR_VERY_HIGH -> Reason.HR_HIGH
            nextBreak <= -OVERDUE_MIN -> Reason.BREAK_OVERDUE
            else -> null
        }
        val caution: Reason? = when {
            hrHighMin >= HR_SUSTAINED_MIN -> Reason.HR_SUSTAINED
            nextBreak <= 0 -> Reason.BREAK_DUE
            else -> null
        }

        val decision = when {
            warning != null && !snoozed(now, warning) -> Decision(Level.L2_WARNING, warning, drivingMin, nextBreak)
            caution != null && !snoozed(now, caution) -> {
                val since = cautionSince ?: now.also { cautionSince = it }
                if ((now - since) / MIN >= CAUTION_ESCALATE_MIN) {
                    Decision(Level.L2_WARNING, caution, drivingMin, nextBreak)
                } else {
                    Decision(Level.L1_CAUTION, caution, drivingMin, nextBreak)
                }
            }
            else -> {
                cautionSince = null
                Decision(Level.L0_NORMAL, Reason.NONE, drivingMin, nextBreak)
            }
        }
        return remember(decision)
    }

    private fun trackDriving(now: Long, driving: Boolean) {
        if (driving) {
            parkedSince = null
            if (drivingSince == null) drivingSince = now
        } else {
            val p = parkedSince ?: now.also { parkedSince = it }
            // A real break (parked long enough) resets the continuous driving time.
            if (now - p >= BREAK_RESET_MIN * MIN) {
                drivingSince = null
                cautionSince = null
            }
        }
    }

    private fun snoozed(now: Long, reason: Reason) = reason == snoozedReason && now < snoozeUntil

    private fun remember(d: Decision): Decision { last = d; return d }

    companion object {
        const val MIN = 60_000L
        const val DEFAULT_RESTING_HR = 70
        const val HR_VERY_LOW = 40
        const val HR_VERY_HIGH = 140
        const val GLUCOSE_LOW = 70
        const val HR_SUSTAINED_MIN = 5
        const val CAUTION_ESCALATE_MIN = 15
        const val OVERDUE_MIN = 30
        const val BREAK_RESET_MIN = 10
        const val SNOOZE_MS = 30 * MIN
    }
}
