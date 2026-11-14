package ai.caredrive.car.engine

import ai.caredrive.car.model.Ask
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryTest {
    private val s = 1000L

    /** Gear D, drove at 60 km/h, stopped at t = 10 s, no input since. */
    private fun stopped(): Stationary = Stationary().apply {
        onGear(0, parked = false)
        onSpeed(0, 60)
        onSpeed(10 * s, 0)
    }

    // --- Trigger ---------------------------------------------------------------------------------

    @Test fun due_after120sStill_inGear_noInput() {
        val m = stopped()
        assertFalse(m.due(129 * s))
        assertEquals("moving", m.blocker(129 * s))
        assertTrue(m.due(130 * s))
    }

    @Test fun recentInput_blocksFor60s() {
        val m = stopped()
        m.onDriverInput(100 * s) // brake toggle or steering > 2°
        assertEquals("driver input", m.blocker(130 * s))
        assertFalse(m.due(159 * s))
        assertTrue(m.due(160 * s))
    }

    @Test fun stopAndGo_restartsTheStillTime() {
        val m = stopped()
        m.onSpeed(100 * s, 5)
        m.onSpeed(105 * s, 0)
        assertFalse(m.due(200 * s))
        assertTrue(m.due(225 * s))
    }

    // --- Exclusions ------------------------------------------------------------------------------

    @Test fun gearP_neverDue() {
        val m = stopped()
        m.onGear(20 * s, parked = true)
        assertEquals("gear P", m.blocker(300 * s))
    }

    @Test fun seatEmpty_neverDue() {
        val m = stopped()
        m.onSeatOccupied(false)
        assertEquals("seat empty", m.blocker(200 * s))
        m.onSeatOccupied(null) // unknown does not block
        assertTrue(m.due(200 * s))
    }

    @Test fun doorOpenedDuringTheStop_neverDue() {
        val m = stopped()
        m.onDoor(50 * s, open = true)
        assertEquals("door opened", m.blocker(200 * s))
        // A door opened before this stop does not matter.
        val n = Stationary().apply { onGear(0, false); onDoor(0, true); onSpeed(0, 60); onSpeed(10 * s, 0) }
        assertTrue(n.due(200 * s))
    }

    @Test fun beltUnbuckled_blocks_onlyIfItWasBuckled() {
        val m = stopped()
        m.onBelt(0, true)
        m.onBelt(60 * s, false)
        assertEquals("belt unbuckled", m.blocker(200 * s))
        // Never reported buckled (no sensor / emulator): not a reason to skip.
        val n = stopped().apply { onBelt(60 * s, false) }
        assertTrue(n.due(200 * s))
    }

    @Test fun registeredPlace_within300m_neverDue() {
        val m = stopped()
        m.places = listOf(Place("집", 37.4000, 127.1000))
        m.onPosition(37.4010, 127.1010) // about 140 m away
        assertEquals("registered place", m.blocker(200 * s))
        m.onPosition(37.4100, 127.1000) // about 1.1 km away
        assertTrue(m.due(200 * s))
    }

    @Test fun slowArea_maxUnder30_neverDue() {
        val m = Stationary().apply { onGear(0, false); onSpeed(0, 20); onSpeed(10 * s, 0) }
        assertEquals("slow area", m.blocker(200 * s))
        // 60 km/h more than 5 minutes ago does not count.
        val n = Stationary().apply { onGear(0, false); onSpeed(0, 60); onSpeed(10 * s, 25); onSpeed(400 * s, 0) }
        assertEquals("slow area", n.blocker(600 * s))
    }

    // --- Question and answers (CheckinEngine) ----------------------------------------------------

    private fun CheckinEngine.tick(sec: Int, due: Boolean = true, parked: Boolean = false, moving: Boolean = false) =
        update(sec * s, Level.L0_NORMAL, Reason.NONE, parked, stationaryDue = due, carMoving = moving)

    @Test fun due_asksWith30sTimer() {
        val e = CheckinEngine()
        val out = e.tick(0)
        assertEquals(Ask.STATIONARY, e.checkin?.ask)
        assertEquals(30, e.checkin?.secondsLeft)
        assertTrue(out.any { it is Effect.Chime && it.then == Cue.STATIONARY_ASK })
        assertTrue(CheckinEngine().tick(0, parked = true).isEmpty())
    }

    @Test fun playYes_playsAndSnoozes2h() {
        val e = CheckinEngine()
        e.tick(0)
        val out = e.answer(5 * s, Ask.STATIONARY, yes = true, parked = false)
        assertTrue(Effect.Play("calm") in out)
        assertEquals("편안한 플레이리스트", e.nowPlaying)
        assertNull(e.checkin)
        assertTrue((6..7199).all { e.tick(it).isEmpty() })
        e.tick(7206)
        assertEquals(Ask.STATIONARY, e.checkin?.ask)
    }

    @Test fun okNo_doesNothingAndSnoozes2h() {
        val e = CheckinEngine()
        e.tick(0)
        val out = e.answer(5 * s, Ask.STATIONARY, yes = false, parked = false)
        assertTrue(out.none { it is Effect.Play || it is Effect.Call || it is Effect.Speak })
        assertNull(e.checkin)
        assertNull(e.tick(3600).let { e.checkin })
    }

    @Test fun noAnswer_noInput_escalatesLikeNoPulse_thenCancelOnAnswer() {
        val e = CheckinEngine()
        e.tick(0)
        assertTrue((1..29).all { e.tick(it).isEmpty() })
        val at30 = e.tick(30)
        assertTrue(e.stationaryEscalated)
        assertTrue(at30.any { it is Effect.Chime && it.beeps == 3 && it.then == Cue.NO_PULSE_ASK }) // loud re-ask
        assertTrue(Effect.AmbientRed(true) in at30)
        // No input at all: guardian pre-alert 30 s later, call at 180 s.
        val pre = (31..60).flatMap { e.tick(it) }
        assertEquals(listOf(Effect.Call(listOf("contacts"), Reason.STATIONARY, "pre_alert")), pre.filterIsInstance<Effect.Call>())
        val cancel = e.answer(70 * s, Ask.STATIONARY, yes = false, parked = false)
        assertEquals("cancel", cancel.filterIsInstance<Effect.Call>().single().stage)
        assertTrue(Effect.AmbientRed(false) in cancel)
        assertNull(e.checkin)
    }

    @Test fun noAnswer_callAt180() {
        val e = CheckinEngine()
        e.tick(0)
        val calls = (1..30 + 180).flatMap { e.tick(it, parked = false) }.filterIsInstance<Effect.Call>()
        assertEquals(listOf("pre_alert", "call"), calls.map { it.stage })
        assertEquals(listOf("contacts", "119_dial"), calls.last().to)
        assertEquals(Reason.STATIONARY, e.contacting?.reason)
    }

    @Test fun noAnswer_butDriverInput_closesQuietly() {
        val e = CheckinEngine()
        e.tick(0)
        e.onDriverInput(12 * s)
        val out = e.tick(30)
        assertNull(e.checkin)
        assertTrue(out.none { it is Effect.Call || it is Effect.AmbientRed })
    }

    @Test fun drivingOff_closesTheQuestion() {
        val e = CheckinEngine()
        e.tick(0)
        e.tick(10, moving = true)
        assertNull(e.checkin)
        assertTrue(e.tick(20).isEmpty()) // snoozed
    }
}
