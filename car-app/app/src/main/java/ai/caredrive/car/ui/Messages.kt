package ai.caredrive.car.ui

import ai.caredrive.car.engine.Grade
import ai.caredrive.car.engine.Mood
import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.Reason

/**
 * What the screen shows and what the car says for one situation. Single source for UI and TTS.
 * Title plus one short sentence (about 20 characters while driving). When [spoken], the voice reads
 * exactly the title and body, the same words as the screen (EU DDAW rule). The note is never spoken.
 */
data class Message(val title: String, val body: String, val note: String? = null, val spoken: Boolean = false) {
    val speak: String? get() = if (spoken) sayAloud() else null

    /** Title and body as one utterance, also used when the driver asks for it (mood pick). */
    fun sayAloud() = listOf(title, body).joinToString(" ") { line ->
        line.replace("\n", " ").let { if (it.endsWith("?") || it.endsWith(".")) it else "$it." }
    }
}

object Messages {
    fun gradeLabel(g: Grade) = when (g) {
        Grade.GOOD -> "좋음"
        Grade.FAIR -> "보통"
        Grade.LOW -> "주의"
    }

    fun levelLabel(level: Level) = when (level) {
        Level.L0_NORMAL -> "정상"
        Level.L1_CAUTION -> "주의"
        Level.L2_WARNING -> "경고"
        Level.L3_STOPPED -> "정차 후 조치"
    }

    fun moodLabel(m: Mood) = when (m) {
        Mood.GOOD -> "좋아요"
        Mood.OK -> "보통"
        Mood.TIRED -> "피곤해요"
        Mood.STRESSED -> "스트레스"
    }

    fun breakLine(state: CareState): String? {
        val next = state.nextBreakInMin ?: return null
        if (state.parked && state.drivingMin == 0) return state.readiness?.let { "오늘은 ${it.breakIntervalMin / 60.0}시간마다 휴식".replace(".0", "") }
        return if (next > 0) "다음 휴식 ${next}분 뒤" else "휴식 시간 지남"
    }

    val guiding = Message("휴게소로 안내 중이에요", "도착하면 잠시 쉬어 가세요", spoken = true)

    fun forState(s: CareState): Message = when (s.level) {
        Level.L0_NORMAL -> if (s.parked && s.drivingMin == 0) preDrive(s) else normal(s)
        Level.L1_CAUTION -> caution(s)
        Level.L2_WARNING -> warning(s)
        Level.L3_STOPPED -> stopped(s)
    }

    private fun preDrive(s: CareState): Message {
        val r = s.readiness ?: return Message("출발 준비가 됐어요", "운전 중 컨디션을 함께 살필게요")
        val factors = r.factors.joinToString(" · ") { it.label }.ifBlank { null }
        return when (r.grade) {
            Grade.GOOD -> Message("오늘 컨디션 좋아요", "2시간마다 쉬어 가요", factors)
            Grade.FAIR -> Message("오늘 컨디션은 보통이에요", "1시간 30분마다 쉬어 가요", factors, spoken = true)
            Grade.LOW -> Message("오늘은 무리하지 마세요", "1시간마다 쉬어 가요", factors, spoken = true)
        }
    }

    private fun normal(s: CareState) = if (s.parked) {
        Message("잠시 멈춰 있어요", "10분 쉬면 휴식 시간이 다시 계산돼요")
    } else {
        Message("편안한 운전 중이에요", breakLine(s) ?: "이상이 있으면 알려 드릴게요")
    }

    private fun caution(s: CareState) = when (s.reason) {
        Reason.BREAK_DUE -> Message(
            "쉬어 갈 시간이에요", "다음 쉼터에서 쉬어 가세요",
            listOfNotNull("운전 ${s.drivingMin}분째", s.readiness?.factors?.firstOrNull()?.label).joinToString(" · "),
            spoken = true,
        )
        Reason.STRESS_HIGH -> Message("스트레스가 높아 보여요", "4초 들이쉬고 6초 내쉬어 보세요", "실내를 시원하게 맞춰요", spoken = true)
        else -> Message("조금 긴장하신 것 같아요", "천천히 숨을 쉬어 보세요", "심박이 평소보다 높아요 · 실내를 시원하게 맞춰요", spoken = true)
    }

    private fun warning(s: CareState): Message {
        val ask = "휴게소로 안내할까요?"
        val title = when (s.reason) {
            Reason.IHRN_NEW -> "심장 리듬 알림이 왔어요"
            Reason.HR_LOW -> "심박이 많이 낮아요"
            Reason.HR_HIGH, Reason.HR_SUSTAINED -> "심박이 계속 높아요"
            Reason.STRESS_HIGH -> "스트레스가 계속 높아요"
            Reason.GLUCOSE_LOW -> "혈당이 낮아요"
            else -> "휴식이 필요해요"
        }
        val note = when (s.reason) {
            Reason.GLUCOSE_LOW -> "휴게소에서 당을 보충하세요"
            Reason.BREAK_DUE, Reason.BREAK_OVERDUE -> "운전 ${s.drivingMin}분째"
            else -> "\"응\" 또는 \"괜찮아\"로 답해도 돼요"
        }
        return Message(title, ask, note, spoken = true)
    }

    private fun stopped(s: CareState) = when (s.reason) {
        Reason.IHRN_NEW -> Message(
            "잠시 쉬어 가세요", "워치로 심전도를 재 보세요",
            "케어드라이브는 진단하지 않아요. 증상이 있으면 의료진과 상의하세요.", spoken = true,
        )
        Reason.GLUCOSE_LOW -> Message(
            "당을 보충하고 쉬어 가세요", "혈당이 오를 때까지 기다려 주세요",
            "어지럽거나 식은땀이 나면 주변에 도움을 요청하세요.", spoken = true,
        )
        else -> Message("잘 멈추셨어요", "스트레칭하고 물을 마셔 보세요", "10분 쉬면 휴식 시간이 다시 계산돼요.", spoken = true)
    }
}
