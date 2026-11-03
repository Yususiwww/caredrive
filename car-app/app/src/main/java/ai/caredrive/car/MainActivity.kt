package ai.caredrive.car

import ai.caredrive.car.car.CarServices
import ai.caredrive.car.demo.DemoInputs
import ai.caredrive.car.demo.DemoScenario
import ai.caredrive.car.engine.DriverCondition
import ai.caredrive.car.engine.LiveSignals
import ai.caredrive.car.engine.RiskEngine
import ai.caredrive.car.engine.RiskEngine.Companion.MIN
import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.Level
import ai.caredrive.car.ui.CareActions
import ai.caredrive.car.ui.CareScreen
import ai.caredrive.car.ui.DemoBar
import ai.caredrive.car.ui.Messages
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private var state by mutableStateOf(CareState(connected = true))
    private var demoStatus by mutableStateOf<String?>(null)
    private lateinit var car: CarServices
    private val engine = RiskEngine()
    private val main = Handler(Looper.getMainLooper())

    // Inputs: simulated until the relay (step 4) delivers phone data.
    private var inputs = DemoInputs()
    private var pendingIhrn = false
    private var carParked = true

    // Simulated clock so the demo can run faster than real time.
    private var simNow = 0L
    private var speed = 1
    private var scenarioIndex = -1
    private var scenarioStartMin = 0L

    private val actions = object : CareActions {
        override fun acceptRestStop() {
            car.speak("가까운 휴게소로 안내할게요.")
            car.addNearestRestStop()
            state = state.copy(guidingToRest = true)
        }
        override fun dismiss() {
            car.speak("알겠어요. 계속 지켜볼게요.")
            engine.dismiss(simNow)
            state = state.copy(guidingToRest = false)
            evaluate()
        }
        override fun startEcgGuide() = car.speak("워치에서 삼성 헬스 모니터 앱을 열고 심전도 측정을 눌러 주세요.")
        override fun notifyGuardian() = log("notifyGuardian: phone app sends the message (step 6)")
        override fun call119() = log("call119: phone app places the call (step 6)")
    }

    private val tick = object : Runnable {
        override fun run() {
            simNow += 1000L * speed
            runScenario()
            evaluate()
            main.postDelayed(this, 1000)
        }
    }

    private val demoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra("preset")?.let { applyPreset(it) }
            if (intent.hasExtra("hr")) inputs = inputs.copy(heartRate = intent.getIntExtra("hr", 72))
            if (intent.hasExtra("glucose")) inputs = inputs.copy(glucose = intent.getIntExtra("glucose", 100).takeIf { it > 0 })
            if (intent.hasExtra("drive")) inputs = inputs.copy(drivingOverride = intent.getBooleanExtra("drive", false))
            if (intent.getBooleanExtra("ihrn", false)) pendingIhrn = true
            if (intent.hasExtra("advance")) advance(intent.getIntExtra("advance", 0))
            evaluate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        car = CarServices(
            this,
            onEvent = { msg -> runOnUiThread { state = state.copy(lastEvent = msg) } },
            onGear = { gear -> runOnUiThread { onGearChanged(gear) } },
        )
        car.start()
        if (BuildConfig.DEBUG) {
            registerReceiver(demoReceiver, IntentFilter(DEMO_ACTION), RECEIVER_EXPORTED)
            applyPreset("predrive")
        }
        main.post(tick)
        setContent {
            CareScreen(state, actions) {
                if (BuildConfig.DEBUG) DemoBar(demoStatus) { applyPreset(it); evaluate() }
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        if (BuildConfig.DEBUG) unregisterReceiver(demoReceiver)
        car.stop()
        super.onDestroy()
    }

    private fun evaluate() {
        val driving = inputs.drivingOverride ?: !carParked
        val decision = engine.update(simNow, LiveSignals(inputs.heartRate, inputs.glucose, pendingIhrn), driving)
        pendingIhrn = false
        val prev = state
        val next = prev.copy(
            level = decision.level,
            reason = decision.reason,
            heartRate = inputs.heartRate,
            glucose = inputs.glucose,
            readiness = engine.readiness(),
            drivingMin = decision.drivingMin,
            nextBreakInMin = decision.nextBreakInMin,
            parked = !driving,
            guidingToRest = prev.guidingToRest && decision.level == Level.L2_WARNING,
        )
        state = next
        if (next.level != prev.level || next.reason != prev.reason) respond(next)
    }

    /** What the car does when the level or reason changes. */
    private fun respond(s: CareState) {
        Messages.forState(s).speak?.let(car::speak)
        if (s.level == Level.L1_CAUTION) car.coolCabin()
    }

    private fun onGearChanged(gear: VehicleGear) {
        log("gear: $gear")
        carParked = gear == VehicleGear.GEAR_PARK
        evaluate()
    }

    private fun setCondition(c: DriverCondition) {
        engine.setCondition(c)
        inputs = inputs.copy(condition = c)
    }

    /** Move the simulated clock forward minute by minute so durations are tracked. */
    private fun advance(minutes: Int) {
        repeat(minutes) { simNow += MIN; evaluate() }
    }

    private fun applyPreset(id: String) {
        log("preset: $id")
        scenarioIndex = -1
        speed = 1
        demoStatus = null
        val low = DriverCondition(sleepMinutes = 280, energyScore = 45, restingHr = 68)
        when (id) {
            "predrive" -> {
                setCondition(low)
                engine.reset()
                inputs = inputs.copy(heartRate = 72, glucose = null, drivingOverride = false)
                evaluate()
                // Announce today's condition before departure.
                Messages.forState(state).speak?.let(car::speak)
            }
            "drive" -> inputs = inputs.copy(drivingOverride = true, heartRate = 76, glucose = null)
            "break" -> {
                inputs = inputs.copy(drivingOverride = true, heartRate = 78)
                advance(((state.nextBreakInMin ?: 60).coerceAtLeast(0)) + 1)
            }
            "tense" -> { inputs = inputs.copy(drivingOverride = true, heartRate = 108); advance(6) }
            "glucose" -> inputs = inputs.copy(drivingOverride = true, glucose = 62)
            "ihrn" -> { inputs = inputs.copy(drivingOverride = true); pendingIhrn = true }
            "park" -> inputs = inputs.copy(drivingOverride = false)
            "scenario" -> startScenario()
        }
    }

    private fun startScenario() {
        applyPreset("predrive")
        scenarioIndex = 0
        scenarioStartMin = simNow / MIN
        speed = DemoScenario.SPEED
    }

    private fun runScenario() {
        if (scenarioIndex < 0) return
        val elapsed = (simNow / MIN - scenarioStartMin).toInt()
        while (scenarioIndex < DemoScenario.steps.size && DemoScenario.steps[scenarioIndex].atMin <= elapsed) {
            val step = DemoScenario.steps[scenarioIndex]
            inputs = step.change(inputs)
            if (step.ihrn) pendingIhrn = true
            demoStatus = "▶ ${step.label}"
            scenarioIndex++
        }
        if (scenarioIndex >= DemoScenario.steps.size) speed = 1 // last step reached: back to real time
    }

    private fun log(msg: String) { Log.i(TAG, msg) }

    companion object {
        private const val TAG = "CareDrive"
        const val DEMO_ACTION = "ai.caredrive.car.DEMO"
    }
}
