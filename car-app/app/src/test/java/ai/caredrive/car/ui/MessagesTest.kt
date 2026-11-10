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
            val end = if (m.body.endsWith("?")) "" else "."
            assertEquals("${m.title}. ${m.body}$end", m.speak)
        }
    }
}
