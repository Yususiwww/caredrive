package ai.caredrive.car.engine

import kotlin.math.abs

/** One drowsiness estimate. [live] is true when a live signal (watch or steering) contributed. */
data class DrowsyScore(val score: Int, val live: Boolean, val parts: List<String>)

/**
 * Drowsiness score 0–100 from the spec table (design example, needs research backing):
 * sleep debt, continuous driving, time of day, HR drop vs resting (3 min), RMSSD rise (3 min), steering.
 * Live signals are required for an alert: with sleep/time only, the break interval is all we change.
 */
class Drowsiness {
    private var hrLowSince: Long? = null
    private var rmssdHighSince: Long? = null
    /** Demo injection: HR drop treated as already sustained. */
    var demoHrDrop: Int? = null

    fun update(
        now: Long,
        sleepMinutes: Int?,
        drivingMin: Int,
        breakIntervalMin: Int,
        hourOfDay: Int,
        hr: Int?,
        restingHr: Int,
        rmssd: Double?,
        baselineRmssd: Int?,
        steeringFlag: Boolean,
    ): DrowsyScore {
        val parts = mutableListOf<String>()
        var score = 0
        var live = false
        sleepMinutes?.let {
            when {
                it < 300 -> { score += 25; parts += "수면 부족" }
                it < 390 -> { score += 10; parts += "수면 조금 부족" }
            }
        }
        if (breakIntervalMin > 0 && drivingMin * 100 >= breakIntervalMin * 80) { score += 15; parts += "연속 운전" }
        when (hourOfDay) {
            in 2..5 -> { score += 20; parts += "새벽 시간" }
            in 14..15 -> { score += 10; parts += "오후 시간" }
        }
        hrLowSince = if (hr != null && hr <= restingHr - HR_DROP) hrLowSince ?: now else null
        val hrDropSustained = hrLowSince?.let { now - it >= SUSTAIN_MS } ?: false
        if (hrDropSustained || (demoHrDrop ?: 0) >= HR_DROP) { score += 20; live = true; parts += "심박 저하" }
        val base = baselineRmssd ?: Hrv.DEFAULT_BASELINE_RMSSD
        rmssdHighSince = if (rmssd != null && rmssd >= base * 1.3) rmssdHighSince ?: now else null
        if (rmssdHighSince?.let { now - it >= SUSTAIN_MS } == true) { score += 10; live = true; parts += "심박변이도 상승" }
        if (steeringFlag) { score += 20; live = true; parts += "핸들 조작" }
        return DrowsyScore(score.coerceAtMost(100), live, parts)
    }

    fun reset() { hrLowSince = null; rmssdHighSince = null; demoHrDrop = null }

    companion object {
        const val HR_DROP = 8
        const val SUSTAIN_MS = 3 * 60_000L
        const val CAUTION = 40
        const val WARNING = 60
    }
}

/**
 * Steering-wheel analysis from Pleos `SteeringWheel.registerSteeringWheelAngle`.
 * Two drowsiness signs: no steering for over 10 s followed by a large correction, and a steering
 * reversal rate far below the driver's own usual rate. Time is passed in.
 */
class SteeringMonitor {
    private var lastAngle: Float? = null
    private var lastMoveAt: Long? = null
    private var idleAngle: Float? = null
    private var correctionWindowEnd = 0L
    private val corrections = ArrayDeque<Long>()

    // Reversal counting: direction of the last significant move and where it turned.
    private var dir = 0
    private var extreme: Float? = null
    private val reversals = ArrayDeque<Long>()
    private var baselinePerMin: Double? = null
    private var lastBaselineAt = 0L
    private var firstAt: Long? = null

    fun onAngle(now: Long, deg: Float) {
        if (firstAt == null) firstAt = now
        val prev = lastAngle
        lastAngle = deg
        if (prev == null) { lastMoveAt = now; extreme = deg; return }
        val moved = abs(deg - prev) >= IDLE_DEG
        if (moved) {
            val idleFor = now - (lastMoveAt ?: now)
            if (idleFor >= IDLE_MS) { idleAngle = prev; correctionWindowEnd = now + CORRECTION_WINDOW_MS }
            lastMoveAt = now
        }
        idleAngle?.let { from ->
            if (now <= correctionWindowEnd && abs(deg - from) >= CORRECTION_DEG) { corrections += now; idleAngle = null }
            else if (now > correctionWindowEnd) idleAngle = null
        }
        countReversal(now, deg)
        trim(now)
    }

    private fun countReversal(now: Long, deg: Float) {
        val e = extreme ?: deg.also { extreme = it }
        val d = deg - e
        when {
            dir == 0 -> if (abs(d) >= GAP_DEG) { dir = if (d > 0) 1 else -1; extreme = deg }
            d * dir > 0 -> extreme = deg // still turning the same way: follow the peak
            abs(d) >= GAP_DEG -> { reversals += now; dir = -dir; extreme = deg }
        }
    }

    /** Demo: the emulator cannot steer, so the phone injects "no steering for [idleSec] s then a correction". */
    fun injectIdleCorrection(now: Long, idleSec: Int) { if (idleSec * 1000L >= IDLE_MS) corrections += now }

    /** Reversals in the last minute. */
    fun reversalsPerMin(now: Long): Int { trim(now); return reversals.size }

    /** True when an idle-then-correction happened in the last 5 min, or the reversal rate fell below half the usual. */
    fun flag(now: Long): Boolean {
        trim(now)
        if (corrections.isNotEmpty()) return true
        val base = baselinePerMin ?: return false
        val rate = reversals.size.toDouble()
        val warmedUp = (firstAt?.let { now - it } ?: 0L) >= 2 * 60_000L
        return warmedUp && base >= MIN_BASELINE && rate < base * 0.5
    }

    /** Learn the usual reversal rate (slow average, once a minute). Call every tick while driving. */
    fun learn(now: Long) {
        if (now - lastBaselineAt < 60_000L || lastAngle == null) return
        lastBaselineAt = now
        val rate = reversalsPerMin(now).toDouble()
        baselinePerMin = baselinePerMin?.let { it * 0.9 + rate * 0.1 } ?: rate
    }

    fun reset() { corrections.clear(); reversals.clear(); baselinePerMin = null; lastAngle = null; firstAt = null; dir = 0; extreme = null }

    private fun trim(now: Long) {
        while (corrections.isNotEmpty() && now - corrections.first() > FLAG_KEEP_MS) corrections.removeFirst()
        while (reversals.isNotEmpty() && now - reversals.first() > 60_000L) reversals.removeFirst()
    }

    companion object {
        const val IDLE_DEG = 1f
        const val IDLE_MS = 10_000L
        const val CORRECTION_DEG = 10f
        const val CORRECTION_WINDOW_MS = 1_500L
        const val GAP_DEG = 2f
        const val MIN_BASELINE = 5.0
        const val FLAG_KEEP_MS = 5 * 60_000L
    }
}
