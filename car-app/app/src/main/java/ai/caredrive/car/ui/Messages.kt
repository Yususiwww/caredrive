package ai.caredrive.car.ui

import ai.caredrive.car.engine.Grade
import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.Reason

/** What the screen shows and what the car says for one situation. Single source for UI and TTS. */
data class Message(val title: String, val body: String, val note: String? = null, val speak: String? = null)

object Messages {
    fun gradeLabel(g: Grade) = when (g) {
        Grade.GOOD -> "좋음"
        Grade.FAIR -> "보통"
        Grade.LOW -> "주의"
    }

    fun breakLine(state: CareState): String? {
        val next = state.nextBreakInMin ?: return null
        if (state.parked && state.drivingMin == 0) return state.readiness?.let { "오늘은 ${it.breakIntervalMin / 60.0}시간마다 휴식".replace(".0", "") }
        return if (next > 0) "다음 휴식까지 ${next}분" else "운전 ${state.drivingMin}분째 · 휴식 시간 지남"
    }

    fun forState(s: CareState): Message = when (s.level) {
        Level.L0_NORMAL -> if (s.parked && s.drivingMin == 0) preDrive(s) else normal(s)
        Level.L1_CAUTION -> caution(s)
        Level.L2_WARNING -> warning(s)
        Level.L3_STOPPED -> stopped(s)
    }

    private fun preDrive(s: CareState): Message {
        val r = s.readiness ?: return Message("출발 준비가 됐어요", "운전 중 컨디션을 함께 살필게요")
        val factors = r.factors.joinToString(" · ") { it.label }
        return when (r.grade) {
            Grade.GOOD -> Message("오늘 컨디션 좋아요", "2시간마다 쉬어 가기를 권해 드릴게요", factors.ifBlank { null })
            Grade.FAIR -> Message(
                "오늘 컨디션은 보통이에요", "1시간 30분마다 쉬어 가기를 권해 드릴게요", factors,
                speak = "오늘 컨디션은 보통이에요. 1시간 30분마다 쉬어 가기를 권해 드릴게요.",
            )
            Grade.LOW -> Message(
                "오늘은 조금 무리하지 마세요", "1시간마다 쉬어 가기를 권해 드릴게요", factors,
                speak = "오늘은 컨디션이 좋지 않아요. 1시간마다 쉬어 가기를 권해 드릴게요.",
            )
        }
    }

    private fun normal(s: CareState) = Message(
        "편안한 운전 중이에요",
        breakLine(s) ?: "이상이 있으면 음성으로 알려 드릴게요",
    )

    private fun caution(s: CareState) = when (s.reason) {
        Reason.BREAK_DUE -> Message(
            "쉬어 갈 시간이에요",
            "운전한 지 ${s.drivingMin}분이 지났어요.\n실내를 시원하게 맞췄어요.",
            s.readiness?.factors?.firstOrNull()?.let { "오늘은 ${it.label}이라 평소보다 자주 쉬어 가요" },
            speak = "운전한 지 ${s.drivingMin}분이 지났어요. 다음 쉼터에서 쉬어 가세요. 실내를 시원하게 맞출게요.",
        )
        else -> Message(
            "조금 긴장하신 것 같아요",
            "실내를 시원하게 맞췄어요.\n천천히 숨을 쉬어 보세요.",
            "평소보다 심박이 높은 상태가 이어지고 있어요",
            speak = "조금 긴장하신 것 같아요. 실내를 시원하게 맞출게요. 천천히 숨을 쉬어 보세요.",
        )
    }

    private fun warning(s: CareState): Message {
        val ask = "가까운 휴게소로 안내할까요?"
        val (title, body) = when (s.reason) {
            Reason.IHRN_NEW -> "심장 리듬 알림이 왔어요" to ask
            Reason.HR_LOW -> "심박이 평소보다 많이 낮아요" to ask
            Reason.HR_HIGH, Reason.HR_SUSTAINED -> "심박이 계속 높아요" to ask
            Reason.GLUCOSE_LOW -> "혈당이 낮아요" to "가까운 휴게소에서 당을 보충하세요.\n안내할까요?"
            else -> "휴식이 필요해요" to "운전한 지 ${s.drivingMin}분째예요.\n$ask"
        }
        return Message(title, body, "\"응\" 또는 \"괜찮아\"라고 말해도 돼요", speak = "$title. ${body.replace("\n", " ")}")
    }

    private fun stopped(s: CareState) = when (s.reason) {
        Reason.IHRN_NEW -> Message(
            "잠시 쉬어 가세요", "워치로 심전도를 측정해 보는 걸 권해요.",
            "케어드라이브는 진단하지 않아요. 증상이 있으면 의료진과 상의하세요.",
            speak = "잠시 쉬어 가세요. 워치로 심전도를 측정해 보는 걸 권해요.",
        )
        Reason.GLUCOSE_LOW -> Message(
            "당을 보충하고 쉬어 가세요", "혈당이 오를 때까지 운전을 멈춰 주세요.",
            "어지럽거나 식은땀이 나면 주변에 도움을 요청하세요.",
            speak = "당을 보충하고 쉬어 가세요. 혈당이 오를 때까지 운전을 멈춰 주세요.",
        )
        else -> Message(
            "잠시 쉬어 가세요", "스트레칭하고 물을 마셔 보세요.\n10분 쉬면 휴식 시간이 다시 계산돼요.",
            speak = "잘 멈추셨어요. 스트레칭하고 잠시 쉬어 가세요.",
        )
    }
}
