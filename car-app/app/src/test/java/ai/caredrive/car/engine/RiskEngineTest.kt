package ai.caredrive.car.engine

import ai.caredrive.car.engine.RiskEngine.Companion.MIN
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

class RiskEngineTest {

    private fun engine(c: DriverCondition = DriverCondition(restingHr = 70)) = RiskEngine().apply { setCondition(c) }

    /** Drive for [minutes] with constant signals, one update per minute. Returns the last decision. */
    private fun RiskEngine.drive(fromMin: Int, minutes: Int, live: LiveSignals = LiveSignals(heartRate = 72)): Decision {
        var d: Decision? = null
        for (m in fromMin..fromMin + minutes) d = update(m * MIN, live, driving = true)
        return d!!
    }

    @Test fun goodCondition_breaksEveryTwoHours() {
        val r = ConditionRules.assess(DriverCondition(sleepMinutes = 450, energyScore = 80))
        assertEquals(Grade.GOOD, r.grade)
        assertEquals(120, r.breakIntervalMin)
    }

    @Test fun shortSleepOrApnea_isLowAndBreaksHourly() {
        assertEquals(60, ConditionRules.assess(DriverCondition(sleepMinutes = 280)).breakIntervalMin)
        assertEquals(60, ConditionRules.assess(DriverCondition(sleepApnea = true)).breakIntervalMin)
        assertEquals(Grade.FAIR, ConditionRules.assess(DriverCondition(energyScore = 55)).grade)
    }

    @Test fun missingData_isNeverBad() {
        assertEquals(Grade.GOOD, ConditionRules.assess(DriverCondition()).grade)
    }

    @Test fun recentExercise_isInfoOnly() {
        val r = ConditionRules.assess(DriverCondition(exerciseEndedMinAgo = 10))
        assertEquals(Grade.GOOD, r.grade)
        assertEquals(FactorKind.RECENT_EXERCISE, r.factors.single().kind)
    }

    @Test fun normalDriving_staysNormal() {
        assertEquals(Level.L0_NORMAL, engine().drive(0, 60).level)
    }

    @Test fun breakDue_thenOverdue() {
        val e = engine(DriverCondition(restingHr = 70, sleepMinutes = 280)) // 60-min interval
        assertEquals(Level.L0_NORMAL, e.drive(0, 59).level)
        val due = e.drive(60, 0)
        assertEquals(Level.L1_CAUTION, due.level)
        assertEquals(Reason.BREAK_DUE, due.reason)
        // Caution that lasts 15 min escalates; overdue by 30 min is a warning on its own.
        assertEquals(Level.L2_WARNING, e.drive(61, 14).level)
    }

    @Test fun sustainedHighHeartRate_isCautionAfterFiveMinutes() {
        val e = engine()
        e.drive(0, 10)
        assertEquals(Level.L0_NORMAL, e.drive(11, 3, LiveSignals(heartRate = 105)).level)
        val d = e.drive(15, 3, LiveSignals(heartRate = 105))
        assertEquals(Level.L1_CAUTION, d.level)
        assertEquals(Reason.HR_SUSTAINED, d.reason)
    }

    @Test fun recentExercise_raisesHeartRateLimit() {
        val e = engine(DriverCondition(restingHr = 70, exerciseEndedMinAgo = 5))
        assertEquals(Level.L0_NORMAL, e.drive(0, 10, LiveSignals(heartRate = 105)).level)
    }

    @Test fun newIhrn_isWarning_thenStoppedWhenParked() {
        val e = engine()
        e.drive(0, 5)
        assertEquals(Reason.IHRN_NEW, e.update(6 * MIN, LiveSignals(72, newIhrn = true), true).reason)
        assertEquals(Level.L2_WARNING, e.update(7 * MIN, LiveSignals(72), true).level)
        assertEquals(Level.L3_STOPPED, e.update(8 * MIN, LiveSignals(72), false).level)
        // After a 10-minute break the after-stop screen ends.
        assertEquals(Level.L0_NORMAL, e.update(19 * MIN, LiveSignals(72), false).level)
    }

    @Test fun lowGlucose_isWarning() {
        val d = engine().drive(0, 1, LiveSignals(heartRate = 80, glucose = 62))
        assertEquals(Level.L2_WARNING, d.level)
        assertEquals(Reason.GLUCOSE_LOW, d.reason)
    }

    @Test fun extremeHeartRate_isWarning() {
        assertEquals(Reason.HR_LOW, engine().drive(0, 1, LiveSignals(heartRate = 35)).reason)
        assertEquals(Reason.HR_HIGH, engine().drive(0, 1, LiveSignals(heartRate = 150)).reason)
    }

    @Test fun dismiss_quietsSameReason() {
        val e = engine(DriverCondition(restingHr = 70, sleepMinutes = 280))
        e.drive(0, 60)
        e.dismiss(60 * MIN)
        assertEquals(Level.L0_NORMAL, e.drive(61, 10).level)
    }

    @Test fun longBreak_resetsDrivingTime() {
        val e = engine(DriverCondition(restingHr = 70, sleepMinutes = 280))
        e.drive(0, 50)
        for (m in 51..62) e.update(m * MIN, LiveSignals(72), driving = false)
        val d = e.drive(63, 30)
        assertEquals(30, d.drivingMin)
        assertEquals(Level.L0_NORMAL, d.level)
    }
}
