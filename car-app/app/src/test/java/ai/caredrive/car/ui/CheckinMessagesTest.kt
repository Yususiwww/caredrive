package ai.caredrive.car.ui

import ai.caredrive.car.engine.Cue
import ai.caredrive.car.model.Ask
import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.Checkin
import ai.caredrive.car.model.Contacting
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckinMessagesTest {

    @Test fun everyCue_isSpoken_withTheScreenWords() {
        Cue.entries.forEach { c ->
            val m = Messages.cue(c)
            assertTrue("$c not spoken", m.spoken)
            assertTrue("$c title too long: ${m.title}", m.title.length <= 20)
            // The sensor-doubt line is spoken in a stop/emergency moment and must include how to cancel.
            assertTrue("$c body too long: ${m.body}", m.body.length <= (if (c == Cue.NO_PULSE_DRIVER_INPUT || c == Cue.NO_PULSE_PRE_ALERT) 40 else 20))
            assertTrue("$c voice differs", m.speak!!.startsWith(Message.spoken(m.title)))
            if (m.body.isNotBlank()) assertTrue(m.speak!!.contains(Message.spoken(m.body)))
            assertFalse("$c speaks blank: ${m.speak}", m.speak!!.contains(". ."))
        }
    }

    @Test fun specLines() {
        assertEquals("심장 박동이 평소와 달라요. 괜찮으세요?", Messages.cue(Cue.CARDIAC_ASK).speak)
        assertEquals("괜찮으세요? 대답해 주세요.", Messages.cue(Cue.NO_PULSE_ASK).speak)
        assertEquals("많이 긴장하신 것 같아요. 힘든 일이 있었나요?", Messages.cue(Cue.STRESS_ASK).speak)
        assertEquals("맥박이 다시 잡혀요. 계속 지켜볼게요.", Messages.cue(Cue.PULSE_BACK).speak)
        assertEquals("워치를 다시 차 주세요.", Messages.cue(Cue.OFF_WRIST).speak)
        assertEquals("워치 신호가 약해요.", Messages.cue(Cue.WEAK_SIGNAL).speak)
        assertEquals("운전 입력이 있어요. 워치 신호 문제일 수 있어요. 괜찮으면 '괜찮아요'를 눌러 주세요.", Messages.cue(Cue.NO_PULSE_DRIVER_INPUT).speak)
    }

    @Test fun buttons_primaryFirst_okLast() {
        Ask.entries.filter { it != Ask.STATIONARY }.forEach { assertEquals("괜찮아요", Messages.buttons(it).last()) }
        assertEquals(listOf("틀어 줘", "괜찮아"), Messages.buttons(Ask.STATIONARY))
        assertEquals("도움이 필요해요", Messages.buttons(Ask.CARDIAC_CHECK).first())
    }

    @Test fun screenFollowsTheOpenQuestion() {
        val base = CareState(level = Level.L2_WARNING, reason = Reason.IHRN_NEW, parked = false)
        assertEquals(Messages.cue(Cue.CARDIAC_ASK), Messages.forState(base.copy(checkin = Checkin(Ask.CARDIAC_CHECK, 0, 30, 30))))
        assertEquals(Messages.cue(Cue.CARDIAC_HELP), Messages.forState(base.copy(checkin = Checkin(Ask.CARDIAC_CHECK, 0, helpRequested = true))))
        assertEquals(Messages.cue(Cue.NO_PULSE_PRE_ALERT), Messages.forState(base.copy(checkin = Checkin(Ask.NO_PULSE, 0, 100, 180, preAlerted = true))))
        assertEquals(Messages.cue(Cue.CONTACTING), Messages.forState(base.copy(contacting = Contacting(true, true, Reason.NO_PULSE))))
    }

    @Test fun contacting_neverClaimsA119TextOrCall() {
        val lines = Messages.contactLines(Contacting(true, true, Reason.NO_PULSE))
        assertEquals(listOf("보호자 전화", "119 연결 준비됨(누르면 연결)"), lines)
        // Guardian: automatic call. 119: only "연결 준비" (a dialer the person taps). Never "119에 연락/전화".
        val all = Cue.entries.map { Messages.cue(it) } + Messages.emergency(10)
        all.forEach { m ->
            val text = listOfNotNull(m.title, m.body, m.note).joinToString(" ")
            assertFalse("claims 119 is called: $text", Regex("119(에|와| 에| 와)|보호자와 119|119 ?전화|일일구에").containsMatchIn(text + " " + m.speak))
            assertFalse("claims a text was sent to 119: $text", text.contains("119 문자"))
        }
        assertEquals("10초 뒤 119 연결 화면을 열어요", Messages.emergency(10).title)
    }

    @Test fun voiceSays119AsIlIlGu_screenKeeps119() {
        val m = Messages.cue(Cue.CONTACTING)
        assertTrue(m.body.contains("119"))
        assertEquals("보호자에게 전화하고 있어요. 일일구 연결도 준비했어요.", m.speak)
        assertFalse(Messages.emergency(5).sayAloud().contains("119"))
        assertEquals("5초 뒤 일일구 연결 화면을 열어요. 원하지 않으면 취소를 누르세요.", Messages.emergency(5).sayAloud())
    }

    @Test fun stationary_questionAndEscalation() {
        assertEquals("정체 구간인가 봐요. 노래 틀어 드릴까요?", Messages.forCheckin(Checkin(Ask.STATIONARY, 0, 30, 30)).speak)
        assertEquals(Messages.cue(Cue.NO_PULSE_ASK), Messages.forCheckin(Checkin(Ask.STATIONARY, 0, 180, 180, escalated = true)))
        assertEquals(Messages.cue(Cue.NO_PULSE_PRE_ALERT), Messages.forCheckin(Checkin(Ask.STATIONARY, 0, 150, 180, preAlerted = true, escalated = true)))
    }
}
