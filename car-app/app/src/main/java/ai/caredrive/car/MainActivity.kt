package ai.caredrive.car

import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.Level
import ai.caredrive.car.ui.CareActions
import ai.caredrive.car.ui.CareScreen
import ai.caredrive.car.ui.DemoBar
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private var state by mutableStateOf(CareState(heartRate = 72, connected = true))

    // Step 2 replaces these logs with TTS, HVAC and NaviHelper calls.
    private val actions = object : CareActions {
        override fun acceptRestStop() { log("acceptRestStop"); state = state.copy(level = Level.L0_NORMAL) }
        override fun dismiss() { log("dismiss"); state = state.copy(level = Level.L0_NORMAL) }
        override fun startEcgGuide() = log("startEcgGuide")
        override fun notifyGuardian() = log("notifyGuardian")
        override fun call119() = log("call119")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CareScreen(state, actions) {
                if (BuildConfig.DEBUG) DemoBar { state = it }
            }
        }
    }

    private fun log(msg: String) { Log.i(TAG, msg) }

    companion object { private const val TAG = "CareDrive" }
}
