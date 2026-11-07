package ai.caredrive.car.engine

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Stress estimate from heart-rate variability. Samsung Health does not share its stress score,
 * so the watch app sends beat-to-beat intervals (IBI, Health Sensor SDK) and we compute RMSSD.
 * Lower RMSSD than the driver's own baseline means more sympathetic (stress) activity.
 * A wellness estimate, not a diagnosis; thresholds are design examples.
 */
object Hrv {
    const val DEFAULT_BASELINE_RMSSD = 40
    private const val MIN_INTERVALS = 10

    /** RMSSD (ms) of beat-to-beat intervals, dropping implausible beats and jumps over 20%. */
    fun rmssd(ibiMs: List<Int>): Double? {
        val beats = ibiMs.filter { it in 300..2000 }
        val diffs = beats.zipWithNext()
            .filter { (a, b) -> abs(b - a) <= a * 0.2 }
            .map { (a, b) -> (b - a).toDouble() }
        if (diffs.size < MIN_INTERVALS) return null
        return sqrt(diffs.sumOf { it * it } / diffs.size)
    }

    /** 0 (at or above baseline) to 100 (RMSSD at 40% of baseline or lower). */
    fun stress(rmssd: Double, baseline: Int?): Int {
        val ratio = rmssd / (baseline ?: DEFAULT_BASELINE_RMSSD)
        return ((1 - ratio) / 0.6 * 100).roundToInt().coerceIn(0, 100)
    }
}
