package ai.caredrive.car

import ai.caredrive.car.car.AlertNotifier
import ai.caredrive.car.car.CarServices
import ai.caredrive.car.demo.DemoInputs
import ai.caredrive.car.demo.DemoScenario
import ai.caredrive.car.engine.DriverCondition
import ai.caredrive.car.engine.Hrv
import ai.caredrive.car.engine.Mood
import ai.caredrive.car.engine.LiveSignals
import ai.caredrive.car.engine.RiskEngine
import ai.caredrive.car.engine.RiskEngine.Companion.MIN
import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.DataState
import ai.caredrive.car.model.GeoPos
import ai.caredrive.car.model.HrSample
import ai.caredrive.car.model.RestPreview
import ai.caredrive.car.model.Level
import ai.caredrive.car.relay.RelayClient
import ai.caredrive.car.ui.CareActions
import ai.caredrive.car.ui.CareScreen
import ai.caredrive.car.ui.CareTheme
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
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MainActivity : ComponentActivity() {
    private var state by mutableStateOf(CareState())
    private var demoStatus by mutableStateOf<String?>(null)
    /** Demo: show the narrow (split-window) layout on the full-size emulator. */
    private var forceCompact by mutableStateOf(false)
    private lateinit var car: CarServices
    private lateinit var relay: RelayClient
    private var lastSentStatus: String? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val engine = RiskEngine()
    private val main = Handler(Looper.getMainLooper())

    // Inputs: simulated until the relay (step 4) delivers phone data.
    private var inputs = DemoInputs()
    private var pendingIhrn = false
    private var carParked = true

    // Data freshness: once a phone has sent readings, missing readings are shown, never hidden.
    private var phoneSentLive = false
    private var lastLiveAt = 0L
    /** Demo preset "nodata": pretend the watch stopped sending. */
    private var demoDataLoss = false
    private lateinit var notifier: AlertNotifier
    private var resumed = false

    // Simulated clock so the demo can run faster than real time.
    private var simNow = 0L
    private var speed = 1
    private var scenarioIndex = -1
    private var scenarioStartMin = 0L

    private val actions = object : CareActions {
        override fun acceptRestStop() {
            car.speak(Messages.guiding.sayAloud())
            car.addNearestRestStop()
            state = state.copy(guidingToRest = true)
        }
        override fun dismiss() {
            // Spoken and shown with the same words.
            val reply = "알겠어요. 계속 지켜볼게요."
            car.speak(reply)
            engine.dismiss(simNow)
            state = state.copy(guidingToRest = false, lastEvent = reply)
            evaluate()
        }
        override fun startEcgGuide() {
            val guide = "워치에서 삼성 헬스 모니터 앱을 열고 심전도 측정을 눌러 주세요."
            car.speak(guide)
            state = state.copy(lastEvent = guide)
        }
        override fun notifyGuardian() = log("notifyGuardian: phone app sends the message (step 6)")
        override fun call119() {
            // Never call at once: count down so a mistaken tap can be cancelled (eCall pattern).
            if (state.emergencyCountdown != null) return
            state = state.copy(emergencyCountdown = EMERGENCY_COUNTDOWN_S)
            car.speak(Messages.emergency(EMERGENCY_COUNTDOWN_S).sayAloud())
        }
        override fun call119Now() = placeEmergencyCall()
        override fun cancel119() {
            if (state.emergencyCountdown == null) return
            val reply = "119 전화를 취소했어요."
            car.speak(reply)
            state = state.copy(emergencyCountdown = null, lastEvent = reply)
        }
        override fun setMood(mood: Mood) {
            setCondition(inputs.condition.copy(mood = mood))
            evaluate()
            car.speak(Messages.forState(state).sayAloud())
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            simNow += 1000L * speed
            state.emergencyCountdown?.let { left ->
                if (left <= 1) placeEmergencyCall() else state = state.copy(emergencyCountdown = left - 1)
            }
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
            if (intent.hasExtra("compact")) forceCompact = intent.getBooleanExtra("compact", false)
            if (intent.getBooleanExtra("call119", false)) actions.call119()
            evaluate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        car = CarServices(
            this,
            onEvent = { msg -> runOnUiThread { state = state.copy(lastEvent = msg) } },
            onGear = { gear -> runOnUiThread { onGearChanged(gear) } },
            onLocation = { loc -> runOnUiThread { state = state.copy(carPos = GeoPos(loc.latitude, loc.longitude)) } },
        )
        car.start()
        notifier = AlertNotifier(this) { id ->
            when (id) {
                AlertNotifier.ACCEPT -> actions.acceptRestStop()
                AlertNotifier.DISMISS -> actions.dismiss()
            }
        }
        relay = RelayClient(
            BuildConfig.RELAY_URL,
            onMessage = ::onRelayMessage,
            onConnection = { up -> if (!up) state = state.copy(connected = false); lastSentStatus = null },
        )
        relay.start()
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (BuildConfig.DEBUG) {
            registerReceiver(demoReceiver, IntentFilter(DEMO_ACTION), RECEIVER_EXPORTED)
            applyPreset("predrive")
        }
        main.post(tick)
        setContent {
            CareTheme {
                CareScreen(state, actions, forceCompact) {
                    if (BuildConfig.DEBUG) DemoBar(demoStatus) { applyPreset(it); evaluate() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        notifier.cancel()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
        if (state.level == Level.L2_WARNING || state.level == Level.L3_STOPPED) notifier.show(state)
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        notifier.release()
        relay.stop()
        if (BuildConfig.DEBUG) unregisterReceiver(demoReceiver)
        car.stop()
        super.onDestroy()
    }

    private fun evaluate() {
        val driving = inputs.drivingOverride ?: !carParked
        val data = dataState()
        val fresh = data == DataState.LIVE
        val hr = if (state.connected) inputs.heartRate else inputs.heartRate?.let { it + demoVariation() }
        val stress = inputs.rmssd?.let { Hrv.stress(it, inputs.condition.baselineRmssd) }
        // Old readings never drive an alert: the engine sees "no data" until readings come back.
        val live = if (fresh) LiveSignals(hr, inputs.glucose, pendingIhrn, stress) else LiveSignals(newIhrn = pendingIhrn)
        val decision = engine.update(simNow, live, driving)
        pendingIhrn = false
        val prev = state
        val history = if (fresh) recordHr(prev.hrHistory, hr) else prev.hrHistory
        val next = prev.copy(
            level = decision.level,
            reason = decision.reason,
            heartRate = if (fresh) hr else prev.heartRate,
            glucose = if (fresh) inputs.glucose else prev.glucose,
            stress = if (fresh) stress else null,
            data = data,
            stressLimit = engine.stressLimit(),
            mood = inputs.condition.mood,
            readiness = engine.readiness(),
            drivingMin = decision.drivingMin,
            nextBreakInMin = decision.nextBreakInMin,
            parked = !driving,
            guidingToRest = prev.guidingToRest && decision.level == Level.L2_WARNING,
            restPreview = if (decision.level == Level.L2_WARNING) prev.restPreview ?: nearestRestPreview() else null,
            hrHistory = history,
            nowMs = simNow,
            restingHr = engine.restingHr(),
            hrLimit = engine.hrLimit(),
        )
        state = next
        if (next.level != prev.level || next.reason != prev.reason) respond(next)
        if (next.data != prev.data) log("data: ${next.data}")
        sendStatus(next)
    }

    private fun dataState(): DataState = when {
        demoDataLoss -> DataState.STALE
        !phoneSentLive -> DataState.LIVE // built-in demo values
        !state.connected -> DataState.OFF
        SystemClock.elapsedRealtime() - lastLiveAt > STALE_AFTER_MS -> DataState.STALE
        else -> DataState.LIVE
    }

    private fun placeEmergencyCall() {
        // The phone places the call; the car only asks (protocol: docs/protocol.md).
        relay.send(buildJsonObject { put("type", "call"); put("to", "119") }.toString())
        val msg = "119에 전화하도록 폰에 요청했어요."
        car.speak(msg)
        state = state.copy(emergencyCountdown = null, lastEvent = msg)
    }

    private fun nearestRestPreview(): RestPreview? = car.nearestRestArea()?.let { (r, km) ->
        RestPreview(r.name, r.lat, r.lon, km, isRestStop = r.kind == "rest_area")
    }

    private var drift = 0.0

    /**
     * Demo without a phone: real heart rate is never flat, so add a slow drift and a
     * breathing rhythm (~5 s) around the preset value. Phone data is used as is.
     */
    private fun demoVariation(): Int {
        drift = drift * 0.8 + (Math.random() - 0.5) * 4
        // Breathing period of 5.3 s on the simulated clock (not a whole number, so minute steps still vary).
        val breath = 2.5 * kotlin.math.sin(2 * Math.PI * (simNow / 1000.0) / 5.3)
        return kotlin.math.round(drift + breath).toInt()
    }

    /** Keep one sample per 5 simulated seconds for the last 30 minutes. */
    private fun recordHr(history: List<HrSample>, bpm: Int?): List<HrSample> {
        if (bpm == null) return history
        val last = history.lastOrNull()
        val added = if (last == null || simNow - last.atMs >= 5_000) history + HrSample(simNow, bpm) else history
        val from = simNow - CareState.HR_WINDOW_MS
        return if (added.first().atMs < from) added.dropWhile { it.atMs < from } else added
    }

    /** Mirror the car state to the phone (only when something the phone shows changed). */
    private fun sendStatus(s: CareState) {
        val msg = buildJsonObject {
            put("type", "status")
            put("level", s.level.name)
            put("reason", s.reason.name)
            put("parked", s.parked)
            put("drivingMin", s.drivingMin)
            put("nextBreakInMin", s.nextBreakInMin ?: 0)
            put("grade", s.readiness?.grade?.name)
            put("stress", s.stress)
            put("mood", s.mood?.name)
            put("lastEvent", s.lastEvent)
        }.toString()
        if (msg != lastSentStatus && relay.send(msg)) lastSentStatus = msg
    }

    /** Phone (or phone simulator) → car. Protocol: docs/protocol.md. */
    private fun onRelayMessage(m: JsonObject) {
        val type = m["type"]?.jsonPrimitive?.content ?: return
        if (type == "peers") {
            state = state.copy(connected = m["phone"]?.jsonPrimitive?.booleanOrNull == true)
            lastSentStatus = null // a (re)connected phone needs the current status
            sendStatus(state)
            return
        }
        if (type == "live") { phoneSentLive = true; lastLiveAt = SystemClock.elapsedRealtime() }
        // The scripted scenario owns the inputs until the phone does something else.
        if (type == "live" && scenarioIndex >= 0) return
        if (type != "live") stopScenario()
        when (type) {
            "condition" -> m["condition"]?.let {
                val c = json.decodeFromJsonElement(DriverCondition.serializer(), it)
                setCondition(if (c.mood == null) c.copy(mood = inputs.condition.mood) else c)
                if (state.parked) { evaluate(); Messages.forState(state).speak?.let(car::speak) }
            }
            "live" -> inputs = inputs.copy(
                heartRate = m["hr"]?.jsonPrimitive?.intOrNull,
                glucose = m["glucose"]?.takeIf { it != JsonNull }?.jsonPrimitive?.intOrNull,
                rmssd = m["rmssd"]?.takeIf { it != JsonNull }?.jsonPrimitive?.doubleOrNull,
            )
            "ihrn" -> pendingIhrn = true
            "drive" -> inputs = inputs.copy(drivingOverride = m["driving"]?.jsonPrimitive?.booleanOrNull)
            "advance" -> advance(m["minutes"]?.jsonPrimitive?.intOrNull ?: 0)
            "scenario" -> startScenario()
        }
        evaluate()
    }

    /** What the car does when the level or reason changes. */
    private fun respond(s: CareState) {
        val say = Messages.forState(s).speak
        // A different sound per level, then the same words as the screen.
        if (s.level != Level.L0_NORMAL) car.chime(s.level) { say?.let(car::speak) } else say?.let(car::speak)
        if (s.level == Level.L1_CAUTION) car.coolCabin()
        // Shown over other apps (navigation in front) as a system notification; cancelled when it is over.
        if (!resumed && (s.level == Level.L2_WARNING || s.level == Level.L3_STOPPED)) notifier.show(s) else if (s.level <= Level.L1_CAUTION) notifier.cancel()
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
        stopScenario()
        demoDataLoss = id == "nodata"
        state = state.copy(emergencyCountdown = null, guidingToRest = false)
        val low = DriverCondition(sleepMinutes = 280, energyScore = 45, restingHr = 68)
        when (id) {
            "predrive" -> {
                setCondition(low)
                engine.reset()
                inputs = inputs.copy(heartRate = 72, glucose = null, rmssd = null, drivingOverride = false)
                evaluate()
                // Announce today's condition before departure.
                Messages.forState(state).speak?.let(car::speak)
            }
            "drive" -> inputs = inputs.copy(drivingOverride = true, heartRate = 76, glucose = null, rmssd = null)
            "break" -> {
                inputs = inputs.copy(drivingOverride = true, heartRate = 78)
                advance(((state.nextBreakInMin ?: 60).coerceAtLeast(0)) + 1)
            }
            "tense" -> { inputs = inputs.copy(drivingOverride = true, heartRate = 108); advance(6) }
            // Heart rate below the caution limit, but HRV well under the baseline: stress, not exertion.
            "stress" -> { inputs = inputs.copy(drivingOverride = true, heartRate = 88, rmssd = 16.0); advance(6) }
            "glucose" -> inputs = inputs.copy(drivingOverride = true, glucose = 62)
            "ihrn" -> { inputs = inputs.copy(drivingOverride = true); pendingIhrn = true }
            "park" -> inputs = inputs.copy(drivingOverride = false)
            "nodata" -> inputs = inputs.copy(drivingOverride = true)
            "scenario" -> startScenario()
        }
    }

    private fun stopScenario() {
        scenarioIndex = -1
        speed = 1
        demoStatus = null
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
        /** The watch reports about once a second; 15 s without a reading is shown as missing. */
        private const val STALE_AFTER_MS = 15_000L
        private const val EMERGENCY_COUNTDOWN_S = 10
    }
}
