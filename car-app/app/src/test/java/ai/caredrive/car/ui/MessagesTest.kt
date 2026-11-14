package ai.caredrive.car.ui

import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {

    private val drivingAlerts = Reason.entries.filter { it != Reason.NONE }.flatMap { r ->
        listOf(Level.L1_CAUTION, Level.L2_WARNING).map { l -> CareState(level = l, reason = r, parked = false, drivingMin = 65) }
    }

    @Test fun drivingAlerts_areShort() {
        drivingAlerts.forEach { s ->
            val m = Messages.forState(s)
            assertTrue("title too long: ${m.title}", m.title.length <= 14)
            assertTrue("body too long: ${m.body}", m.body.length <= 20)
        }
    }

    @Test fun voice_usesTheScreenWords() {
        drivingAlerts.forEach { s ->
            val m = Messages.forState(s)
            val dot = { t: String -> if (t.endsWith("?")) t else "$t." }
            assertEquals("${dot(m.title)} ${dot(m.body)}", m.speak)
        }
    }

    @Test fun afterStop_givesInstructions() {
        listOf(Reason.IHRN_NEW, Reason.GLUCOSE_LOW, Reason.BREAK_DUE).forEach { r ->
            val m = Messages.forState(CareState(level = Level.L3_STOPPED, reason = r, parked = true, drivingMin = 65))
            assertTrue("not an instruction: ${m.title}", m.title.endsWith("주세요"))
        }
    }

    @Test fun stressCaution_isASuggestionWithoutScore() {
        val m = Messages.forState(CareState(level = Level.L1_CAUTION, reason = Reason.STRESS_HIGH, stress = 90, parked = false))
        assertTrue(m.title.none { it.isDigit() } && !m.title.contains("스트레스"))
    }
}
