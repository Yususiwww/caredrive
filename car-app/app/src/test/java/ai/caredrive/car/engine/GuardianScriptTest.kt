package ai.caredrive.car.engine

import ai.caredrive.car.model.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianScriptTest {
    @Test fun call_hasLocationHeadingVehicleHrAnd119Script() {
        val t = GuardianScript.build("call", Reason.NO_PULSE, 37.41272, 127.09468, headingDeg = 40.0,
            address = "경기 성남시 분당구", near = "성남졸음쉼터" to 3.6, lastHr = 52)
        assertTrue(t, t.startsWith("[케어드라이브] 워치에 맥박이 잡히지 않고 운전자 무응답 3분."))
        assertTrue(t, t.contains("위치: 경기 성남시 분당구 (37.41272, 127.09468)"))
        assertTrue(t, t.contains("북동쪽 방향으로 가던 중"))
        assertTrue(t, t.contains("성남졸음쉼터에서 약 3.6km"))
        assertTrue(t, t.contains("차량: ${GuardianScript.VEHICLE_PLACEHOLDER}"))
        assertTrue(t, t.contains("마지막 심박 52 bpm"))
        assertTrue(t, t.contains("119에 이렇게 말해 주세요: '성남졸음쉼터 근처 도로에서 운전자가 의식이 없는 것 같아요. 위치는"))
    }

    @Test fun unknownValues_areLeftOut_notInvented() {
        val t = GuardianScript.build("call", Reason.STATIONARY, null, null)
        assertTrue(t, t.contains("위치: 확인 중"))
        assertTrue(t, !t.contains("bpm") && !t.contains("방향"))
    }

    @Test fun preAlertAndCancel() {
        assertTrue(GuardianScript.build("pre_alert", Reason.NO_PULSE, 1.0, 2.0).contains("미리 알림"))
        assertEquals("[케어드라이브] 운전자가 괜찮다고 답했어요. 연락을 취소해요.", GuardianScript.build("cancel", Reason.NO_PULSE, 1.0, 2.0))
    }

    @Test fun compass() {
        assertEquals("북쪽", GuardianScript.direction(359.0))
        assertEquals("동쪽", GuardianScript.direction(90.0))
        assertEquals("남서쪽", GuardianScript.direction(225.0))
    }
}
