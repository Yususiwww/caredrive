package ai.caredrive.car.engine

import kotlinx.serialization.Serializable

/**
 * Pre-drive condition summary the phone builds from Samsung Health
 * (sleep, sleep apnea, energy score, IHRN history, resting HR, recent exercise).
 * Null means "no data" and is never treated as bad.
 */
@Serializable
data class DriverCondition(
    val sleepMinutes: Int? = null,
    val sleepApnea: Boolean = false,
    val energyScore: Int? = null,
    val recentIhrn: Boolean = false,
    val restingHr: Int? = null,
    /** Minutes since the last workout ended, if one ended recently. */
    val exerciseEndedMinAgo: Int? = null,
)

enum class Grade { GOOD, FAIR, LOW }

enum class FactorKind { SLEEP_SHORT, SLEEP_APNEA, ENERGY_LOW, RECENT_IHRN, RECENT_EXERCISE }

data class Factor(val kind: FactorKind, val label: String)

data class Readiness(
    val grade: Grade,
    val factors: List<Factor>,
    /** How often to suggest a break today. */
    val breakIntervalMin: Int,
)

/** Thresholds are design examples; real values need medical review. */
object ConditionRules {
    const val BASE_BREAK_MIN = 120
    const val FAIR_BREAK_MIN = 90
    const val LOW_BREAK_MIN = 60

    fun assess(c: DriverCondition): Readiness {
        val low = mutableListOf<Factor>()
        val fair = mutableListOf<Factor>()

        c.sleepMinutes?.let { m ->
            val label = "수면 ${m / 60}시간 ${m % 60}분"
            when {
                m < 300 -> low += Factor(FactorKind.SLEEP_SHORT, label)
                m < 390 -> fair += Factor(FactorKind.SLEEP_SHORT, label)
            }
        }
        if (c.sleepApnea) low += Factor(FactorKind.SLEEP_APNEA, "수면무호흡 감지 기록")
        if (c.recentIhrn) low += Factor(FactorKind.RECENT_IHRN, "최근 부정맥 알림")
        c.energyScore?.let { e ->
            when {
                e < 40 -> low += Factor(FactorKind.ENERGY_LOW, "에너지 점수 $e")
                e < 60 -> fair += Factor(FactorKind.ENERGY_LOW, "에너지 점수 $e")
            }
        }
        // Informational only: explains a higher heart rate, does not lower the grade.
        val info = c.exerciseEndedMinAgo?.takeIf { it <= 30 }
            ?.let { listOf(Factor(FactorKind.RECENT_EXERCISE, "${it}분 전 운동")) } ?: emptyList()

        val grade = when {
            low.isNotEmpty() -> Grade.LOW
            fair.isNotEmpty() -> Grade.FAIR
            else -> Grade.GOOD
        }
        val interval = when (grade) {
            Grade.LOW -> LOW_BREAK_MIN
            Grade.FAIR -> FAIR_BREAK_MIN
            Grade.GOOD -> BASE_BREAK_MIN
        }
        return Readiness(grade, low + fair + info, interval)
    }
}
