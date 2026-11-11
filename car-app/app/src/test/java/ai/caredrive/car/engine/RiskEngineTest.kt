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
        // After 15 minutes the same reason alerts again.
        assertEquals(Level.L1_CAUTION, e.drive(72, 5).level)
    }

    @Test fun dismiss_doesNotQuietOtherReasons() {
        val e = engine(DriverCondition(restingHr = 70, sleepMinutes = 280))
        e.drive(0, 60)
        e.dismiss(60 * MIN)
        assertEquals(Reason.GLUCOSE_LOW, e.drive(61, 1, LiveSignals(heartRate = 72, glucose = 60)).reason)
    }

    @Test fun longBreak_resetsDrivingTime() {
        val e = engine(DriverCondition(restingHr = 70, sleepMinutes = 280))
        e.drive(0, 50)
        for (m in 51..62) e.update(m * MIN, LiveSignals(72), driving = false)
        val d = e.drive(63, 30)
        assertEquals(30, d.drivingMin)
        assertEquals(Level.L0_NORMAL, d.level)
    }

    @Test fun overnightSignals_lowerTheGrade() {
        assertEquals(Grade.LOW, ConditionRules.assess(DriverCondition(nightSpo2Min = 88)).grade)
        assertEquals(Grade.FAIR, ConditionRules.assess(DriverCondition(nightSpo2Min = 92)).grade)
        assertEquals(Grade.FAIR, ConditionRules.assess(DriverCondition(skinTempDeltaC = 0.6)).grade)
        assertEquals(Grade.LOW, ConditionRules.assess(DriverCondition(skinTempDeltaC = 1.2)).grade)
        assertEquals(Grade.FAIR, ConditionRules.assess(DriverCondition(systolicBp = 165)).grade)
        assertEquals(Grade.GOOD, ConditionRules.assess(DriverCondition(nightSpo2Min = 96, skinTempDeltaC = 0.2, systolicBp = 125)).grade)
    }

    @Test fun tiredOrStressedMood_isFair() {
        assertEquals(Grade.FAIR, ConditionRules.assess(DriverCondition(mood = Mood.TIRED)).grade)
        assertEquals(Grade.FAIR, ConditionRules.assess(DriverCondition(mood = Mood.STRESSED)).grade)
        assertEquals(Grade.GOOD, ConditionRules.assess(DriverCondition(mood = Mood.GOOD)).grade)
    }

    @Test fun rmssd_ignoresArtifacts() {
        val steady = List(20) { if (it % 2 == 0) 800 else 840 }
        assertEquals(40.0, Hrv.rmssd(steady)!!, 0.01)
        // A missed beat (1640 ms) and a spike (250 ms) are dropped instead of inflating RMSSD.
        assertEquals(40.0, Hrv.rmssd(steady + listOf(1640, 250) + steady)!!, 0.01)
        assertEquals(null, Hrv.rmssd(listOf(800, 820, 810)))
    }

    @Test fun stress_isRelativeToBaseline() {
        assertEquals(0, Hrv.stress(45.0, 40))
        assertEquals(100, Hrv.stress(16.0, 40))
        assertEquals(50, Hrv.stress(28.0, 40))
    }

    @Test fun sustainedStress_isCaution_andMoodLowersLimit() {
        val d = engine().drive(0, 6, LiveSignals(heartRate = 85, stress = 75))
        assertEquals(Level.L1_CAUTION, d.level)
        assertEquals(Reason.STRESS_HIGH, d.reason)
        assertEquals(Level.L0_NORMAL, engine().drive(0, 6, LiveSignals(heartRate = 85, stress = 65)).level)
        val stressed = engine(DriverCondition(restingHr = 70, mood = Mood.STRESSED))
        assertEquals(Reason.STRESS_HIGH, stressed.drive(0, 6, LiveSignals(heartRate = 85, stress = 65)).reason)
    }
}
