package ai.caredrive.car.ui

import ai.caredrive.car.engine.CheckinEngine
import ai.caredrive.car.engine.Cue
import ai.caredrive.car.engine.Grade
import ai.caredrive.car.engine.Mood
import ai.caredrive.car.model.Ask
import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.Checkin
import ai.caredrive.car.model.Contacting
import ai.caredrive.car.model.DataState
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
    fun sayAloud() = listOf(title, body).filter { it.isNotBlank() }.joinToString(" ") { line ->
        line.replace("\n", " ").let { if (it.endsWith("?") || it.endsWith(".")) it else "$it." }
    }.let(::spoken)

    companion object {
        /** Screen text → what the TTS should say. The car voice reads "119" as "백십구"; Koreans say "일일구". */
        fun spoken(text: String) = text.replace("119", "일일구")
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
        return if (next > 0) "다음 휴식 ${next}분 뒤" else "휴식 시간이 지났어요"
    }

    val guiding = Message("휴게소로 안내 중이에요", "도착하면 잠시 쉬어 가세요", spoken = true)

    /** Contacting and open questions come first: the screen and the voice say the same thing. */
    fun forState(s: CareState): Message = s.contacting?.let { contacting(it) } ?: s.checkin?.let { forCheckin(it) } ?: forLevel(s)

    private fun forLevel(s: CareState): Message = when (s.level) {
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
        // A suggestion, not a score (Mercedes Energizing Coach): the car cools the cabin and guides breathing.
        Reason.DROWSY -> Message("졸음이 올 수 있어요", "실내를 시원하게 할게요", "창문을 조금 열어도 좋아요", spoken = true)
        Reason.STRESS_HIGH -> Message("잠깐 편안하게 해 드릴게요", "4초 들이쉬고 6초 내쉬어 보세요", "실내를 시원하게 맞춰요", spoken = true)
        else -> Message("조금 긴장하신 것 같아요", "천천히 숨을 쉬어 보세요", "심박이 평소보다 높아요 · 실내를 시원하게 맞춰요", spoken = true)
    }

    private fun warning(s: CareState): Message {
        if (s.reason == Reason.NO_PULSE || s.reason == Reason.STATIONARY) return cue(Cue.NO_PULSE_ASK)
        // Drowsiness at 60+: the nearest rest stop is added as a waypoint right away.
        if (s.reason == Reason.DROWSY) return Message("졸음쉼터에서 쉬어 가요", "가까운 쉼터를 경유지로 넣었어요", "차는 직접 안전한 곳에 세워 주세요", spoken = true)
        val ask = "휴게소로 안내할까요?"
        val title = when (s.reason) {
            Reason.IHRN_NEW -> "심장 리듬 알림이 왔어요"
            Reason.HR_LOW -> "심박이 많이 낮아요"
            Reason.HR_HIGH, Reason.HR_SUSTAINED -> "심박이 계속 높아요"
            Reason.STRESS_HIGH -> "잠깐 쉬어 가면 좋겠어요"
            Reason.GLUCOSE_LOW -> "혈당이 낮아요"
            else -> "휴식이 필요해요"
        }
        val note = when (s.reason) {
            Reason.GLUCOSE_LOW -> "휴게소에서 단 것을 드세요"
            Reason.BREAK_DUE, Reason.BREAK_OVERDUE -> "운전 ${s.drivingMin}분째"
            // CareDrive never controls the car: say so where stopping matters.
            else -> "차는 직접 안전한 곳에 세워 주세요"
        }
        return Message(title, ask, note, spoken = true)
    }

    /** After stopping: what to do now, as plain instructions. */
    private fun stopped(s: CareState) = when (s.reason) {
        Reason.IHRN_NEW -> Message(
            "운전을 잠시 쉬어 주세요", "워치로 심전도를 재 주세요",
            "케어드라이브는 진단하지 않아요. 가슴이 아프거나 숨이 차면 119를 누르세요.", spoken = true,
        )
        Reason.GLUCOSE_LOW -> Message(
            "당을 먼저 보충해 주세요", "15분 쉬고 혈당을 다시 보세요",
            "어지럽거나 식은땀이 나면 119를 누르세요.", spoken = true,
        )
        else -> Message("10분 쉬고 출발해 주세요", "스트레칭하고 물을 마셔 주세요", "10분 쉬면 휴식 시간이 다시 계산돼요.", spoken = true)
    }

    /** 119 call pending: the driver can still cancel (eCall pattern). */
    fun emergency(secondsLeft: Int) = Message("${secondsLeft}초 뒤 119 연결 화면을 열어요", "원하지 않으면 취소를 누르세요", spoken = true)

    fun dataLine(state: CareState): String? = when (state.data) {
        DataState.LIVE -> null
        DataState.STALE -> "워치 값이 안 들어와요"
        DataState.OFF -> "워치 연결이 끊겼어요"
    }

    // --- Check-in questions (spec: docs/decisions/2026-11-15-응급-대화-졸음-설계.md) ---

    /** Buttons for a question, primary first. `CareActions.answer(ask, yes = index == 0)`. */
    fun buttons(ask: Ask): List<String> = when (ask) {
        Ask.CARDIAC_CHECK -> listOf("도움이 필요해요", "괜찮아요")
        Ask.NO_PULSE -> listOf("괜찮아요")
        Ask.STRESS_TALK -> listOf("좀 그래요", "괜찮아요")
        Ask.DROWSY -> listOf("쉬어 갈게요", "괜찮아요")
        Ask.STATIONARY -> listOf("틀어 줘", "괜찮아")
    }

    /** What the screen shows (and the voice says) while [c] is open. */
    fun forCheckin(c: Checkin): Message = when (c.ask) {
        Ask.CARDIAC_CHECK -> cue(when {
            c.helpRequested -> Cue.CARDIAC_HELP
            // Still moving with no answer or input: the no-pulse countdown, with the two cardiac buttons to stop it.
            c.escalated && c.preAlerted -> Cue.NO_PULSE_PRE_ALERT
            c.escalated -> Cue.NO_PULSE_ASK
            else -> Cue.CARDIAC_ASK
        })
        Ask.NO_PULSE -> cue(when { c.paused -> Cue.NO_PULSE_DRIVER_INPUT; c.preAlerted -> Cue.NO_PULSE_PRE_ALERT; else -> Cue.NO_PULSE_ASK })
        Ask.STRESS_TALK -> cue(Cue.STRESS_ASK)
        Ask.DROWSY -> cue(Cue.DROWSY_ASK)
        // No answer: the same countdown as no pulse.
        Ask.STATIONARY -> if (!c.escalated) cue(Cue.STATIONARY_ASK) else cue(when {
            c.paused -> Cue.NO_PULSE_DRIVER_INPUT; c.preAlerted -> Cue.NO_PULSE_PRE_ALERT; else -> Cue.NO_PULSE_ASK
        })
    }

    fun contacting(c: Contacting) = cue(Cue.CONTACTING)

    /** Contacting checklist rows. 119 is never dialled by an app: the phone opens its dialer with 119 filled in. */
    fun contactLines(c: Contacting): List<String> = listOfNotNull(
        "보호자 전화".takeIf { c.contacts },
        "119 연결 준비됨(누르면 연결)".takeIf { c.emergency119 },
    )

    const val HAZARD_ON = "비상등 켜짐"
    fun nowPlayingLine(name: String) = "♪ $name 재생 중"

    fun cue(c: Cue): Message = when (c) {
        Cue.CARDIAC_ASK -> Message("심장 박동이 평소와 달라요", "괜찮으세요?", spoken = true)
        Cue.CARDIAC_HELP -> Message("비상등을 켜고 세워 주세요", "멈추면 보호자에게 전화할게요", "갓길이나 휴게소에 세워 주세요", spoken = true)
        Cue.CARDIAC_OK -> Message("알겠어요", "휴게소로 안내할까요?", spoken = true)
        Cue.CONTACTING, Cue.NO_PULSE_CALL -> Message("보호자에게 전화하고 있어요", "119 연결도 준비했어요", "119는 폰에서 누르면 연결돼요 · 원하지 않으면 취소를 누르세요", spoken = true)
        Cue.NO_PULSE_ASK -> Message("괜찮으세요?", "대답해 주세요", "3분 동안 대답이 없으면 보호자에게 전화하고 119 연결을 준비해요", spoken = true)
        Cue.NO_PULSE_DRIVER_INPUT -> Message("운전 입력이 있어요", "워치 신호 문제일 수 있어요. 괜찮으면 '괜찮아요'를 눌러 주세요", "핸들이나 브레이크 입력이 있어 연락 타이머를 멈췄어요", spoken = true)
        Cue.NO_PULSE_PRE_ALERT -> Message("곧 보호자에게 전화해요", "119 연결도 준비할게요. 괜찮으면 '괜찮아요'를 눌러 주세요", "보호자에게 위치와 함께 미리 알렸어요", spoken = true)
        Cue.NO_PULSE_CANCELLED, Cue.DROWSY_OK -> Message("알겠어요", "계속 지켜볼게요", spoken = true)
        Cue.PULSE_BACK -> Message("맥박이 다시 잡혀요", "계속 지켜볼게요", spoken = true)
        Cue.OFF_WRIST -> Message("워치를 다시 차 주세요", "", spoken = true)
        Cue.WEAK_SIGNAL -> Message("워치 신호가 약해요", "", "워치를 손목에 조금 더 꼭 맞게 차 주세요", spoken = true)
        Cue.STRESS_ASK -> Message("많이 긴장하신 것 같아요", "힘든 일이 있었나요?", spoken = true)
        Cue.STRESS_PLAY -> Message("좋아하시는 노래 틀어 드릴게요", "천천히 숨 쉬어 보세요", nowPlayingLine(CheckinEngine.PLAYLIST_NAME), spoken = true)
        Cue.STRESS_OK -> Message("알겠어요", "4초 들이쉬고 6초 내쉬어 보세요", spoken = true)
        Cue.DROWSY_ASK -> Message("졸음이 올 수 있어요", "잠깐 쉬어 갈까요?", "실내를 시원하게 맞췄어요 · 창문을 조금 열어도 좋아요", spoken = true)
        Cue.DROWSY_REST -> Message("가까운 쉼터로 안내할게요", "도착하면 잠시 쉬어 가세요", spoken = true)
        Cue.STATIONARY_ASK -> Message("정체 구간인가 봐요", "노래 틀어 드릴까요?", spoken = true)
        Cue.STATIONARY_PLAY -> Message("노래 틀어 드릴게요", "", nowPlayingLine(CheckinEngine.PLAYLIST_NAME), spoken = true)
        Cue.CONTACT_CANCELLED -> Message("연락을 취소했어요", "", spoken = true)
    }
}
