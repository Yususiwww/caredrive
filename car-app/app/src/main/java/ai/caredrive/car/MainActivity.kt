package ai.caredrive.car

import ai.caredrive.car.car.CarServices
import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.Reason
import ai.caredrive.car.ui.CareActions
import ai.caredrive.car.ui.CareScreen
import ai.caredrive.car.ui.DemoBar
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private var state by mutableStateOf(CareState(heartRate = 72, connected = true))
    private lateinit var car: CarServices

    private val actions = object : CareActions {
        override fun acceptRestStop() {
            car.speak("가까운 휴게소로 안내할게요.")
            car.addNearestRestStop()
            state = state.copy(guidingToRest = true)
        }
        override fun dismiss() {
            car.speak("알겠어요. 계속 지켜볼게요.")
            state = state.copy(level = Level.L0_NORMAL, reason = Reason.NONE, guidingToRest = false)
        }
        override fun startEcgGuide() = car.speak("워치에서 삼성 헬스 모니터 앱을 열고 심전도 측정을 눌러 주세요.")
        override fun notifyGuardian() = log("notifyGuardian: phone app sends the message (step 6)")
        override fun call119() = log("call119: phone app places the call (step 6)")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        car = CarServices(
            this,
            onEvent = { msg -> runOnUiThread { state = state.copy(lastEvent = msg) } },
            onGear = { gear -> runOnUiThread { onGearChanged(gear) } },
        )
        car.start()
        setContent {
            CareScreen(state, actions) {
                if (BuildConfig.DEBUG) DemoBar { applyState(it.copy(parked = state.parked)) }
            }
        }
    }

    override fun onDestroy() {
        car.stop()
        super.onDestroy()
    }

    /** Apply a new state and run the car response for a level change. */
    private fun applyState(next: CareState) {
        val prev = state
        state = next.copy(lastEvent = prev.lastEvent)
        if (next.level == prev.level && next.reason == prev.reason) return
        when (next.level) {
            Level.L0_NORMAL -> Unit
            Level.L1_CAUTION -> {
                car.speak("조금 긴장하신 것 같아요. 실내를 시원하게 맞출게요.")
                car.coolCabin()
            }
            Level.L2_WARNING -> car.speak(warningLine(next.reason) + " 가까운 휴게소로 안내할까요?")
            Level.L3_STOPPED -> car.speak("잠시 쉬어 가세요. 워치로 심전도를 측정해 보는 걸 권해요.")
        }
    }

    private fun onGearChanged(gear: VehicleGear) {
        val parked = gear == VehicleGear.GEAR_PARK
        log("gear: $gear")
        state = state.copy(parked = parked)
        // L2 followed by stopping (P) moves to the after-stop actions.
        if (parked && state.level == Level.L2_WARNING) applyState(state.copy(level = Level.L3_STOPPED))
    }

    private fun warningLine(reason: Reason) = when (reason) {
        Reason.IHRN_NEW -> "심장 리듬 알림이 왔어요."
        Reason.HR_LOW -> "심박이 평소보다 많이 낮아요."
        else -> "심박이 계속 높아요."
    }

    private fun log(msg: String) { Log.i(TAG, msg) }

    companion object { private const val TAG = "CareDrive" }
}
