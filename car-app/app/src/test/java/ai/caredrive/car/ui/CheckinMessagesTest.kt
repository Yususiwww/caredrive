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
            assertTrue("$c body too long: ${m.body}", m.body.length <= 20)
            assertTrue("$c voice differs", m.speak!!.startsWith(m.title))
            if (m.body.isNotBlank()) assertTrue(m.speak!!.contains(m.body))
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
    }

    @Test fun buttons_primaryFirst_okLast() {
        Ask.entries.forEach { assertEquals("괜찮아요", Messages.buttons(it).last()) }
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
        assertEquals(listOf("보호자 전화", "119 연결 준비"), lines)
        val m = Messages.cue(Cue.CONTACTING)
        assertFalse(m.speak!!.contains("문자"))
        assertFalse(m.speak!!.contains("119에 전화"))
    }
}
