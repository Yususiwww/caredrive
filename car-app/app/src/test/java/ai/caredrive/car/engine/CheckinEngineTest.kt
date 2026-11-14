package ai.caredrive.car.engine

import ai.caredrive.car.model.Ask
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.PulseState
import ai.caredrive.car.model.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckinEngineTest {
    private val s = 1000L

    private fun List<Effect>.calls() = filterIsInstance<Effect.Call>()
    private fun List<Effect>.cues() = mapNotNull { (it as? Effect.Speak)?.cue ?: (it as? Effect.Chime)?.then }

    /** Tick once a second from [from] to [to] seconds; returns all effects. */
    private fun CheckinEngine.run(from: Int, to: Int, level: Level = Level.L0_NORMAL, reason: Reason = Reason.NONE, parked: Boolean = false) =
        (from..to).flatMap { update(it * s, level, reason, parked) }

    // --- CARDIAC_CHECK ---------------------------------------------------------------------------

    @Test fun cardiacWarning_whileDriving_asksWithA30sTimer() {
        val e = CheckinEngine()
        val out = e.update(0, Level.L2_WARNING, Reason.IHRN_NEW, parked = false)
        assertEquals(Ask.CARDIAC_CHECK, e.checkin?.ask)
        assertEquals(30, e.checkin?.secondsLeft)
        assertEquals(listOf(Cue.CARDIAC_ASK), out.cues())
        e.update(10 * s, Level.L2_WARNING, Reason.IHRN_NEW, parked = false)
        assertEquals(20, e.checkin?.secondsLeft)
    }

    @Test fun cardiac_notAskedWhenParked_orForNonCardiacWarnings() {
        val e = CheckinEngine()
        assertTrue(e.update(0, Level.L2_WARNING, Reason.IHRN_NEW, parked = true).isEmpty())
        assertTrue(e.update(0, Level.L2_WARNING, Reason.GLUCOSE_LOW, parked = false).isEmpty())
        assertNull(e.checkin)
    }

    @Test fun cardiacHelp_redLightRestStop_thenContactsWhenParked() {
        val e = CheckinEngine()
        e.update(0, Level.L2_WARNING, Reason.HR_HIGH, false)
        val help = e.answer(5 * s, Ask.CARDIAC_CHECK, yes = true, parked = false)
        assertTrue(Cue.CARDIAC_HELP in help.cues())
        assertTrue(Effect.AmbientRed(true) in help)
        assertTrue(Effect.AddRestStop in help)
        assertTrue(help.calls().isEmpty())
        assertTrue(e.checkin!!.helpRequested)
        // Still driving and steering now and then: no ladder, however long.
        val driving = (6..120).flatMap { t -> if (t % 30 == 0) e.onDriverInput(t * s); e.update(t * s, Level.L2_WARNING, Reason.HR_HIGH, false) }
        assertTrue(driving.calls().isEmpty())
        assertTrue(e.checkin!!.helpRequested)
        // Gear P: contact guardian + 119 (dialer).
        val stop = e.update(121 * s, Level.L3_STOPPED, Reason.HR_HIGH, parked = true)
        assertEquals(listOf(Effect.Call(listOf("contacts", "119_dial"), Reason.HR_HIGH, "call")), stop.calls())
        assertTrue(Effect.CabinLight(true) in stop)
        assertEquals(Reason.HR_HIGH, e.contacting?.reason)
        assertTrue(e.contacting!!.contacts && e.contacting!!.emergency119)
        assertNull(e.checkin)
    }

    @Test fun cardiacNoAnswer_for30s_actsAsHelp() {
        val e = CheckinEngine()
        e.update(0, Level.L2_WARNING, Reason.IHRN_NEW, false)
        val out = e.run(1, 29, Level.L2_WARNING, Reason.IHRN_NEW)
        assertFalse(e.checkin!!.helpRequested)
        assertTrue(Cue.CARDIAC_HELP !in out.cues())
        val at30 = e.update(30 * s, Level.L2_WARNING, Reason.IHRN_NEW, false)
        assertTrue(e.checkin!!.helpRequested)
        assertTrue(Cue.CARDIAC_HELP in at30.cues())
        assertTrue(Effect.Log(Ask.CARDIAC_CHECK, "none") in at30)
    }

    @Test fun cardiacHelp_thenNoAnswerNoInput60s_whileMoving_runsTheNoPulseLadder() {
        val e = CheckinEngine()
        e.update(0, Level.L2_WARNING, Reason.IHRN_NEW, false)
        e.run(1, 30, Level.L2_WARNING, Reason.IHRN_NEW) // no answer: help assumed at 30 s
        assertTrue(e.checkin!!.helpRequested)
        assertTrue(e.run(31, 89, Level.L2_WARNING, Reason.IHRN_NEW).calls().isEmpty())
        val at90 = e.run(90, 90, Level.L2_WARNING, Reason.IHRN_NEW)
        assertTrue(e.checkin!!.escalated)
        assertTrue(at90.any { it is Effect.Chime && it.beeps == 3 })
        // Same ladder as no pulse: pre-alert (no input → 30 s), then guardian call + 119 dialer at 180 s.
        val ladder = e.run(91, 90 + 180, Level.L2_WARNING, Reason.IHRN_NEW)
        assertEquals(listOf("pre_alert", "call"), ladder.calls().map { it.stage })
        assertEquals(Effect.Call(listOf("contacts", "119_dial"), Reason.IHRN_NEW, "call"), ladder.calls().last())
        assertEquals(Reason.IHRN_NEW, e.contacting?.reason)
    }

    @Test fun cardiacLadder_stoppedByInput_orAnswer() {
        val e = CheckinEngine()
        e.update(0, Level.L2_WARNING, Reason.HR_HIGH, false)
        e.answer(1 * s, Ask.CARDIAC_CHECK, true, parked = false)
        e.run(2, 61, Level.L2_WARNING, Reason.HR_HIGH)
        assertTrue(e.checkin!!.escalated)
        e.onDriverInput(70 * s) // brake
        e.run(70, 70, Level.L2_WARNING, Reason.HR_HIGH)
        assertFalse(e.checkin!!.escalated)
        assertTrue(e.checkin!!.helpRequested)
        // Quiet again for 60 s → ladder again; then "괜찮아요" ends it.
        e.run(71, 130, Level.L2_WARNING, Reason.HR_HIGH)
        assertTrue(e.checkin!!.escalated)
        val ok = e.answer(131 * s, Ask.CARDIAC_CHECK, false, parked = false)
        assertNull(e.checkin)
        assertTrue(Effect.AmbientRed(false) in ok)
        assertTrue(e.run(132, 400, Level.L2_WARNING, Reason.HR_HIGH).calls().isEmpty())
    }

    @Test fun cardiacLadder_gearP_stillContactsAtOnce() {
        val e = CheckinEngine()
        e.update(0, Level.L2_WARNING, Reason.HR_HIGH, false)
        e.answer(1 * s, Ask.CARDIAC_CHECK, true, parked = false)
        e.run(2, 70, Level.L2_WARNING, Reason.HR_HIGH)
        assertTrue(e.checkin!!.escalated)
        val stop = e.update(71 * s, Level.L3_STOPPED, Reason.HR_HIGH, parked = true)
        assertEquals("call", stop.calls().single().stage)
        assertNotNull(e.contacting)
    }

    @Test fun cardiacOk_keepsL2Flow_andSnoozes15Min() {
        val e = CheckinEngine()
        e.update(0, Level.L2_WARNING, Reason.IHRN_NEW, false)
        val ok = e.answer(3 * s, Ask.CARDIAC_CHECK, yes = false, parked = false)
        assertEquals(listOf(Cue.CARDIAC_OK), ok.cues())
        assertNull(e.checkin)
        assertTrue(e.update(14 * 60 * s, Level.L2_WARNING, Reason.IHRN_NEW, false).isEmpty())
        assertNull(e.checkin)
        e.update(15 * 60 * s + 3 * s, Level.L2_WARNING, Reason.IHRN_NEW, false)
        assertEquals(Ask.CARDIAC_CHECK, e.checkin?.ask)
    }

    @Test fun cancelContact_tellsThePhone() {
        val e = CheckinEngine()
        e.update(0, Level.L2_WARNING, Reason.IHRN_NEW, false)
        e.answer(1 * s, Ask.CARDIAC_CHECK, true, parked = true)
        assertNotNull(e.contacting)
        val out = e.cancelContact()
        assertEquals("cancel", out.calls().single().stage)
        assertNull(e.contacting)
    }

    // --- NO_PULSE --------------------------------------------------------------------------------

    @Test fun noPulse_noCarInput_preAlertAt30_callAt180() {
        val e = CheckinEngine()
        val start = e.onPulse(0, PulseState.ABSENT)
        assertTrue(start.any { it is Effect.Chime && it.beeps == 3 })
        assertTrue(Effect.AmbientRed(true) in start)
        assertEquals(180, e.checkin?.totalSeconds)

        val first = e.run(1, 29)
        assertEquals(1, first.cues().count { it == Cue.NO_PULSE_ASK }) // re-asked at 20 s
        assertTrue(first.calls().isEmpty())

        // No steering or brake at all: the guardian hears at 30 s, not 60 s.
        val at30 = e.run(30, 30)
        assertEquals(listOf(Effect.Call(listOf("contacts"), Reason.NO_PULSE, "pre_alert")), at30.calls())
        assertTrue(e.checkin!!.preAlerted)
        assertEquals(150, e.checkin!!.secondsLeft)

        val mid = e.run(31, 179)
        assertTrue(mid.calls().isEmpty())
        assertTrue(Cue.NO_PULSE_ASK !in mid.cues()) // the pre-alert announcement is not talked over

        val at180 = e.run(180, 180, parked = true)
        assertEquals(listOf(Effect.Call(listOf("contacts", "119_dial"), Reason.NO_PULSE, "call")), at180.calls())
        assertTrue(Effect.CabinLight(true) in at180)
        assertEquals(Reason.NO_PULSE, e.contacting?.reason)
        assertNull(e.checkin)
    }

    @Test fun noPulse_driverInput_pausesTimer_speaksOnce_preAlertStays60() {
        val e = CheckinEngine()
        e.onPulse(0, PulseState.ABSENT)
        e.run(1, 10)
        assertEquals(170, e.checkin!!.secondsLeft)
        e.onDriverInput(10 * s) // steering at 10 s
        val paused = e.run(11, 30)
        assertTrue(e.checkin!!.paused)
        assertEquals(listOf(Cue.NO_PULSE_DRIVER_INPUT), paused.cues()) // spoken once, no re-ask while paused
        assertTrue(Effect.Log(Ask.NO_PULSE, "driver_input") in paused)
        assertTrue(paused.calls().isEmpty())
        assertEquals(170, e.checkin!!.secondsLeft) // paused, not cancelled
        assertEquals(Ask.NO_PULSE, e.checkin!!.ask)

        // Brake press keeps it paused; a second input does not speak again.
        e.onDriverInput(25 * s)
        assertTrue(Cue.NO_PULSE_DRIVER_INPUT !in e.run(31, 45).cues())
        // 20 s after the last input the timer runs again, from where it stopped.
        e.run(46, 46)
        assertFalse(e.checkin!!.paused)
        // There was input during the check, so the pre-alert stays at 60 s of counted time.
        val before = e.run(47, 94) // counted time reaches 59 s
        assertTrue(before.calls().isEmpty())
        val at60 = e.run(95, 96)
        assertEquals("pre_alert", at60.calls().single().stage)
    }

    @Test fun noPulse_onlyInputWhileTheCheckIsOpen_counts() {
        // Braking to a stop just before the watch lost the pulse says nothing about the driver now.
        val e = CheckinEngine()
        e.onDriverInput(0)
        e.onPulse(5 * s, PulseState.ABSENT)
        e.update(6 * s, Level.L0_NORMAL, Reason.NONE, false)
        assertFalse(e.checkin!!.paused)
        assertTrue(e.run(7, 35).calls().single().stage == "pre_alert") // still the no-input 30 s pre-alert
        e.onDriverInput(36 * s)
        e.update(37 * s, Level.L0_NORMAL, Reason.NONE, false)
        assertTrue(e.checkin!!.paused)
    }

    @Test fun noPulse_whileDriving_noCabinLight() {
        val e = CheckinEngine()
        e.onPulse(0, PulseState.ABSENT)
        val out = e.run(1, 180, parked = false)
        assertEquals(2, out.calls().size)
        assertTrue(out.none { it is Effect.CabinLight })
    }

    @Test fun noPulse_okCancels_atAnyPoint() {
        val e = CheckinEngine()
        e.onPulse(0, PulseState.ABSENT)
        e.run(1, 90)
        val ok = e.answer(91 * s, Ask.NO_PULSE, yes = false, parked = false)
        assertNull(e.checkin)
        assertTrue(Effect.AmbientRed(false) in ok)
        assertEquals("cancel", ok.calls().single().stage) // contacts were pre-alerted, so tell them
        assertTrue(e.run(92, 400).calls().isEmpty())
        assertNull(e.contacting)
    }

    @Test fun noPulse_okBeforePreAlert_sendsNothing() {
        val e = CheckinEngine()
        e.onPulse(0, PulseState.ABSENT)
        assertTrue(e.answer(10 * s, Ask.NO_PULSE, yes = true, parked = false).calls().isEmpty())
    }

    @Test fun pulseBack_clearsWithMessage() {
        val e = CheckinEngine()
        e.onPulse(0, PulseState.ABSENT)
        e.run(1, 30)
        val back = e.onPulse(31 * s, PulseState.PRESENT)
        assertTrue(Cue.PULSE_BACK in back.cues())
        assertNull(e.checkin)
        assertTrue(e.run(32, 300).calls().isEmpty())
    }

    @Test fun offWristAndWeak_neverEscalate() {
        listOf(PulseState.OFF_WRIST to Cue.OFF_WRIST, PulseState.WEAK to Cue.WEAK_SIGNAL).forEach { (state, cue) ->
            val e = CheckinEngine()
            assertEquals(listOf(cue), e.onPulse(0, state).cues())
            assertNull(e.checkin)
            assertTrue(e.run(1, 400).calls().isEmpty())
            assertNull(e.contacting)
        }
    }

    @Test fun repeatedAbsent_doesNotRestartTheTimer() {
        val e = CheckinEngine()
        e.onPulse(0, PulseState.ABSENT)
        e.run(1, 50)
        assertTrue(e.onPulse(50 * s, PulseState.ABSENT).isEmpty())
        e.run(51, 60)
        assertTrue(e.checkin!!.preAlerted)
    }

    // --- STRESS_TALK -----------------------------------------------------------------------------

    @Test fun stressCaution_whileDriving_asks() {
        val e = CheckinEngine()
        assertEquals(listOf(Cue.STRESS_ASK), e.update(0, Level.L1_CAUTION, Reason.STRESS_HIGH, false).cues())
        assertEquals(Ask.STRESS_TALK, e.checkin?.ask)
        assertTrue(CheckinEngine().update(0, Level.L1_CAUTION, Reason.HR_SUSTAINED, true).isEmpty())
        assertTrue(CheckinEngine().update(0, Level.L1_CAUTION, Reason.BREAK_DUE, false).isEmpty())
    }

    @Test fun stressYes_playsCalmPlaylistAndKeepsCooling() {
        val e = CheckinEngine()
        e.update(0, Level.L1_CAUTION, Reason.HR_SUSTAINED, false)
        val out = e.answer(2 * s, Ask.STRESS_TALK, yes = true, parked = false)
        assertTrue(Effect.Play("calm") in out)
        assertTrue(Effect.CoolCabin in out)
        assertEquals("편안한 플레이리스트", e.nowPlaying)
        // The caution is still on, but the car asked once: no second question for 15 minutes.
        assertTrue(e.run(3, 14 * 60, Level.L1_CAUTION, Reason.HR_SUSTAINED).isEmpty())
    }

    @Test fun stressOk_breathingOnly_snoozes15Min_neverPlays() {
        val e = CheckinEngine()
        e.update(0, Level.L1_CAUTION, Reason.STRESS_HIGH, false)
        val out = e.answer(2 * s, Ask.STRESS_TALK, yes = false, parked = false)
        assertEquals(listOf(Cue.STRESS_OK), out.cues())
        assertTrue(out.none { it is Effect.Play })
        assertNull(e.nowPlaying)
        assertTrue(e.run(3, 14 * 60, Level.L1_CAUTION, Reason.STRESS_HIGH).isEmpty())
        e.update(15 * 60 * s + 2 * s, Level.L1_CAUTION, Reason.STRESS_HIGH, false)
        assertEquals(Ask.STRESS_TALK, e.checkin?.ask)
    }

    @Test fun cardiacWarning_replacesAnOpenStressQuestion() {
        val e = CheckinEngine()
        e.update(0, Level.L1_CAUTION, Reason.HR_SUSTAINED, false)
        e.update(1 * s, Level.L2_WARNING, Reason.IHRN_NEW, false)
        assertEquals(Ask.CARDIAC_CHECK, e.checkin?.ask)
    }

    // --- DROWSY ----------------------------------------------------------------------------------

    @Test fun drowsyCaution_asksAndCools_restAddsWaypoint() {
        val e = CheckinEngine()
        val ask = e.update(0, Level.L1_CAUTION, Reason.DROWSY, false)
        assertEquals(listOf(Cue.DROWSY_ASK), ask.cues())
        assertTrue(Effect.CoolCabin in ask)
        val rest = e.answer(3 * s, Ask.DROWSY, yes = true, parked = false)
        assertTrue(Effect.AddRestStop in rest)
        assertNull(e.checkin)
        assertTrue(e.run(4, 60, Level.L1_CAUTION, Reason.DROWSY).isEmpty())
    }

    @Test fun conversationQuestion_withoutAnswer_closesQuietlyAfter30s() {
        val e = CheckinEngine()
        e.update(0, Level.L1_CAUTION, Reason.DROWSY, false)
        e.run(1, 30, Level.L1_CAUTION, Reason.DROWSY)
        assertNull(e.checkin)
    }
}
