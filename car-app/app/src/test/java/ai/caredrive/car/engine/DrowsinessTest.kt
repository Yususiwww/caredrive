package ai.caredrive.car.engine

import ai.caredrive.car.engine.RiskEngine.Companion.MIN
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrowsinessTest {

    private fun Drowsiness.at(
        now: Long, sleep: Int? = 450, drivingMin: Int = 10, hour: Int = 11, hr: Int? = 70, rmssd: Double? = null, steering: Boolean = false,
    ) = update(now, sleep, drivingMin, 120, hour, hr, 70, rmssd, 40, steering)

    @Test fun table_scoresEachItem() {
        assertEquals(0, Drowsiness().at(0).score)
        assertEquals(25, Drowsiness().at(0, sleep = 280).score)
        assertEquals(10, Drowsiness().at(0, sleep = 360).score)
        assertEquals(15, Drowsiness().at(0, drivingMin = 96).score) // 80% of 120
        assertEquals(20, Drowsiness().at(0, hour = 3).score)
        assertEquals(10, Drowsiness().at(0, hour = 15).score)
        assertEquals(20, Drowsiness().at(0, steering = true).score)
    }

    @Test fun hrDrop_countsOnlyAfter3Minutes() {
        val d = Drowsiness()
        assertEquals(0, d.at(0, hr = 61).score)
        assertEquals(0, d.at(2 * MIN, hr = 61).score)
        val s = d.at(3 * MIN, hr = 61)
        assertEquals(20, s.score)
        assertTrue(s.live)
        assertEquals(0, d.at(4 * MIN, hr = 70).score) // back up: resets
    }

    @Test fun rmssdRise_countsAfter3Minutes() {
        val d = Drowsiness()
        d.at(0, rmssd = 55.0)
        assertEquals(10, d.at(3 * MIN, rmssd = 55.0).score)
    }

    @Test fun sleepAndTimeOnly_isNotLive_soNeverAlerts() {
        val s = Drowsiness().at(0, sleep = 200, drivingMin = 100, hour = 3)
        assertEquals(60, s.score)
        assertFalse(s.live)
    }

    @Test fun cappedAt100() {
        val d = Drowsiness().apply { demoHrDrop = 12 }
        assertEquals(100, d.at(0, sleep = 200, drivingMin = 100, hour = 3, steering = true).score)
        d.at(0, sleep = 200, drivingMin = 100, hour = 3, rmssd = 80.0, steering = true)
        assertTrue(d.at(3 * MIN, sleep = 200, drivingMin = 100, hour = 3, rmssd = 80.0, steering = true).score <= 100)
    }

    @Test fun riskEngine_40isCaution_60isWarning() {
        val e = RiskEngine().apply { setCondition(DriverCondition(restingHr = 70)) }
        e.update(0, LiveSignals(72), true)
        assertEquals(Reason.DROWSY, e.update(MIN, LiveSignals(72, drowsiness = 45), true).reason)
        assertEquals(Level.L1_CAUTION, e.update(2 * MIN, LiveSignals(72, drowsiness = 45), true).level)
        val w = e.update(3 * MIN, LiveSignals(72, drowsiness = 65), true)
        assertEquals(Level.L2_WARNING, w.level)
        assertEquals(Reason.DROWSY, w.reason)
    }

    // --- Steering --------------------------------------------------------------------------------

    @Test fun steering_idleThenLargeCorrection_flags() {
        val m = SteeringMonitor()
        // Hold still for 12 s (10 Hz), then jerk 15°.
        for (i in 0..120) m.onAngle(i * 100L, 0.2f)
        assertFalse(m.flag(12_000))
        m.onAngle(12_100, 4f)
        m.onAngle(12_300, 15f)
        assertTrue(m.flag(12_400))
        assertFalse(m.flag(12_400 + 6 * MIN)) // forgotten after 5 min
    }

    @Test fun steering_smallCorrectionAfterIdle_doesNotFlag() {
        val m = SteeringMonitor()
        for (i in 0..120) m.onAngle(i * 100L, 0f)
        m.onAngle(12_100, 3f)
        m.onAngle(12_300, 4f)
        m.onAngle(14_000, 5f)
        assertFalse(m.flag(14_000))
    }

    @Test fun steering_countsReversals() {
        val m = SteeringMonitor()
        // Weave ±5° every second: one reversal per second after the first swing.
        for (i in 0..20) m.onAngle(i * 1000L, if (i % 2 == 0) 5f else -5f)
        assertEquals(19, m.reversalsPerMin(20_000))
    }

    @Test fun steering_reversalRateFallsBelowHalfOfUsual_flags() {
        val m = SteeringMonitor()
        var t = 0L
        // 3 minutes of normal weaving (~30 reversals/min), learning the usual rate.
        while (t < 3 * MIN) { m.onAngle(t, if ((t / 2000) % 2 == 0L) 4f else -4f); m.learn(t); t += 200 }
        assertFalse(m.flag(t))
        // Then a minute of slow drifting with almost no reversals (no 10 s freeze).
        val end = t + MIN
        while (t < end) { m.onAngle(t, ((t / 200) % 5).toFloat() * 0.25f + 4f); t += 200 }
        assertTrue(m.flag(t))
    }

    @Test fun steering_demoInjection() {
        val m = SteeringMonitor()
        m.injectIdleCorrection(1000, 12)
        assertTrue(m.flag(2000))
        val n = SteeringMonitor()
        n.injectIdleCorrection(1000, 5)
        assertFalse(n.flag(2000))
    }
}
