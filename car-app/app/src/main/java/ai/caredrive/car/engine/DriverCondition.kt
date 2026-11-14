package ai.caredrive.car.engine

import kotlinx.serialization.Serializable

/** How the driver says they feel, asked once before departure (Samsung Health has no readable mood data). */
enum class Mood { GOOD, OK, TIRED, STRESSED }

/**
 * Pre-drive condition summary the phone builds from Samsung Health
 * (sleep, sleep apnea, energy score, IHRN history, resting HR, recent exercise,
 * overnight blood oxygen and skin temperature, blood pressure) plus the driver's own mood check.
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
    /** Lowest blood oxygen (%) measured during last night's sleep. */
    val nightSpo2Min: Int? = null,
    /** Last night's skin temperature minus the personal average (°C); the phone computes the average. */
    val skinTempDeltaC: Double? = null,
    /** Most recent systolic blood pressure within the last day (mmHg). */
    val systolicBp: Int? = null,
    /** Personal resting HRV (RMSSD, ms) from the watch app; baseline for the live stress estimate. */
    val baselineRmssd: Int? = null,
    val mood: Mood? = null,
    /** Registered places (home, work): no "정체 확인" within 300 m. */
    val places: List<Place> = emptyList(),
)

enum class Grade { GOOD, FAIR, LOW }

enum class FactorKind {
    SLEEP_SHORT, SLEEP_APNEA, ENERGY_LOW, RECENT_IHRN, RECENT_EXERCISE,
    SPO2_LOW, SKIN_TEMP_UP, BP_HIGH, MOOD_TIRED, MOOD_STRESSED,
}

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
        c.nightSpo2Min?.let { o ->
            when {
                o < 90 -> low += Factor(FactorKind.SPO2_LOW, "수면 중 산소포화도 $o%")
                o < 93 -> fair += Factor(FactorKind.SPO2_LOW, "수면 중 산소포화도 $o%")
            }
        }
        // Higher than usual at night can mean a cold or fatigue coming on.
        c.skinTempDeltaC?.let { d ->
            val label = "피부온도 평소보다 +%.1f℃".format(d)
            when {
                d >= 1.0 -> low += Factor(FactorKind.SKIN_TEMP_UP, label)
                d >= 0.5 -> fair += Factor(FactorKind.SKIN_TEMP_UP, label)
            }
        }
        c.systolicBp?.let { bp ->
            when {
                bp >= 180 -> low += Factor(FactorKind.BP_HIGH, "혈압 $bp")
                bp >= 160 -> fair += Factor(FactorKind.BP_HIGH, "혈압 $bp")
            }
        }
        when (c.mood) {
            Mood.TIRED -> fair += Factor(FactorKind.MOOD_TIRED, "피곤하다고 하셨어요")
            Mood.STRESSED -> fair += Factor(FactorKind.MOOD_STRESSED, "스트레스가 있다고 하셨어요")
            else -> Unit
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
