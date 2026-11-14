package ai.caredrive.car

import ai.caredrive.car.car.AlertNotifier
import ai.caredrive.car.car.CarServices
import ai.caredrive.car.car.VehicleSignal
import ai.caredrive.car.engine.CheckinEngine
import ai.caredrive.car.engine.Drowsiness
import ai.caredrive.car.engine.DrowsyScore
import ai.caredrive.car.engine.Effect
import ai.caredrive.car.engine.SteeringMonitor
import ai.caredrive.car.model.Ask
import ai.caredrive.car.model.PulseState
import ai.caredrive.car.model.Reason
import ai.caredrive.car.ui.Message
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
    /** Demo: `--es theme dark|light` forces a theme; null follows the system night mode (light by day). */
    private var forceDark by mutableStateOf<Boolean?>(null)
    private lateinit var car: CarServices
    private lateinit var relay: RelayClient
    private var lastSentStatus: String? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val engine = RiskEngine()
    private val checkins = CheckinEngine()
    private val drowsiness = Drowsiness()
    private val steering = SteeringMonitor()
    /** Wall-clock minute of the day when the app started; the simulated clock runs on from here (time-of-day drowsiness). */
    private val startClockMin = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
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
        override fun answer(ask: Ask, yes: Boolean) {
            log("answer: $ask yes=$yes")
            run(checkins.answer(simNow, ask, yes, parked = state.parked))
            evaluate()
        }
        override fun cancelContact() {
            run(checkins.cancelContact())
            evaluate()
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
            intent.getStringExtra("theme")?.let { forceDark = when (it) { "dark" -> true; "light" -> false; else -> null } }
            if (intent.getBooleanExtra("call119", false)) actions.call119()
            intent.getStringExtra("pulse")?.let { onPulse(it) }
            intent.getStringExtra("answer")?.let { a -> state.checkin?.let { actions.answer(it.ask, a == "yes") } }
            if (intent.getBooleanExtra("cancelContact", false)) actions.cancelContact()
            if (intent.hasExtra("speed")) speed = intent.getIntExtra("speed", 1).coerceIn(1, 60)
            evaluate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        car = CarServices(
            this,
            onEvent = { msg -> runOnUiThread { log("event: $msg"); state = state.copy(lastEvent = msg) } },
            onGear = { gear -> runOnUiThread { onGearChanged(gear) } },
            onLocation = { loc -> runOnUiThread { state = state.copy(carPos = GeoPos(loc.latitude, loc.longitude)) } },
            onVehicle = ::onVehicle,
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
        val missing = listOf(android.Manifest.permission.POST_NOTIFICATIONS, CAR_SPEED)
            .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        if (BuildConfig.DEBUG) {
            registerReceiver(demoReceiver, IntentFilter(DEMO_ACTION), RECEIVER_EXPORTED)
            applyPreset("predrive")
        }
        main.post(tick)
        setContent {
            CareTheme(dark = forceDark ?: true) {
                CareScreen(state, actions, forceCompact) {
                    if (BuildConfig.DEBUG) DemoBar(demoStatus) { applyPreset(it); evaluate() }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Speed needs the runtime permission: subscribe again once it is granted.
        if (CAR_SPEED in permissions) car.startCarProperties()
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
        val drowsy = if (driving) drowsyScore(if (fresh) hr else null, if (fresh) inputs.rmssd else null) else null
        // Old readings never drive an alert: the engine sees "no data" until readings come back.
        val alertDrowsy = drowsy?.takeIf { it.live }?.score
        val live = if (fresh) LiveSignals(hr, inputs.glucose, pendingIhrn, stress, alertDrowsy) else LiveSignals(newIhrn = pendingIhrn, drowsiness = alertDrowsy)
        var decision = engine.update(simNow, live, driving)
        pendingIhrn = false
        val effects = checkins.update(simNow, decision.level, decision.reason, parked = !driving)
        // No pulse outranks every other level: shown as a warning until the driver answers.
        if (checkins.noPulseActive) decision = decision.copy(level = Level.L2_WARNING, reason = Reason.NO_PULSE)
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
            guidingToRest = prev.guidingToRest && decision.level != Level.L0_NORMAL,
            restPreview = if (decision.level == Level.L2_WARNING || prev.guidingToRest) prev.restPreview ?: nearestRestPreview() else null,
            hrHistory = history,
            nowMs = simNow,
            restingHr = engine.restingHr(),
            hrLimit = engine.hrLimit(),
            checkin = checkins.checkin,
            contacting = checkins.contacting,
            nowPlaying = checkins.nowPlaying,
            pulse = checkins.pulse,
            drowsiness = drowsy?.score,
            ambientRed = checkins.ambientRed,
        )
        state = next
        if (drowsy != null && drowsy.score != prev.drowsiness) log("drowsiness: ${drowsy.score} ${drowsy.parts}")
        if (next.level != prev.level || next.reason != prev.reason) respond(next)
        run(effects)
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
        relay.send(callJson(listOf("119_dial"), state.reason, "call"))
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
            put("ask", s.checkin?.ask?.name)
            put("secondsLeft", s.checkin?.secondsLeft)
            put("helpRequested", s.checkin?.helpRequested)
            put("contacting", s.contacting != null)
            put("nowPlaying", s.nowPlaying)
            put("drowsiness", s.drowsiness)
            put("speedKmh", s.speedKmh)
            put("hazardOn", s.hazardOn)
            put("brakeOn", s.brakeOn)
            put("pulse", s.pulse.name)
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
            "pulse" -> m["state"]?.jsonPrimitive?.content?.let { onPulse(it) }
            "drowsy" -> onDrowsyDemo(m)
            "speed" -> speed = (m["x"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, 60)
            "answer" -> {
                // Phone simulator (demo) can answer for the driver; same path as the car buttons.
                val ask = m["ask"]?.jsonPrimitive?.content?.let { n -> Ask.entries.firstOrNull { it.name == n } }
                if (ask != null) actions.answer(ask, m["yes"]?.jsonPrimitive?.booleanOrNull ?: false)
            }
        }
        evaluate()
    }

    private fun onPulse(value: String) {
        val ps = when (value) {
            "absent" -> PulseState.ABSENT
            "offwrist" -> PulseState.OFF_WRIST
            "weak" -> PulseState.WEAK
            else -> PulseState.PRESENT
        }
        // Parked / no steering never shortens the timer; only logged for the record.
        log("pulse: $ps parked=${state.parked} steeringIdle=${steering.flag(SystemClock.elapsedRealtime())}")
        if (ps == PulseState.ABSENT || ps == PulseState.OFF_WRIST) inputs = inputs.copy(heartRate = null)
        run(checkins.onPulse(simNow, ps))
    }

    /** Demo injection: the emulator cannot steer, so the phone sends the drowsiness signs (protocol `drowsy`). */
    private fun onDrowsyDemo(m: JsonObject) {
        if (m["clear"]?.jsonPrimitive?.booleanOrNull == true) { drowsiness.demoHrDrop = null; steering.reset(); log("drowsy demo cleared"); return }
        m["hrDrop"]?.jsonPrimitive?.intOrNull?.let { drowsiness.demoHrDrop = it }
        m["steeringIdleSec"]?.jsonPrimitive?.intOrNull?.let { steering.injectIdleCorrection(SystemClock.elapsedRealtime(), it) }
        log("drowsy demo: $m")
    }

    private fun drowsyScore(hr: Int?, rmssd: Double?): DrowsyScore {
        val now = SystemClock.elapsedRealtime()
        steering.learn(now)
        val minuteOfDay = (startClockMin + simNow / MIN).toInt()
        return drowsiness.update(
            now = simNow,
            sleepMinutes = inputs.condition.sleepMinutes,
            drivingMin = state.drivingMin,
            breakIntervalMin = engine.readiness().breakIntervalMin,
            hourOfDay = (minuteOfDay / 60) % 24,
            hr = hr,
            restingHr = engine.restingHr(),
            rmssd = rmssd,
            baselineRmssd = inputs.condition.baselineRmssd,
            steeringFlag = steering.flag(now),
        )
    }

    private fun onVehicle(sig: VehicleSignal) {
        when (sig) {
            is VehicleSignal.Speed -> if (sig.kmh != state.speedKmh) {
                if (sig.kmh / 10 != (state.speedKmh ?: -10) / 10) log("speed: ${sig.kmh} km/h")
                state = state.copy(speedKmh = sig.kmh)
            }
            is VehicleSignal.Hazard -> if (sig.on != state.hazardOn) {
                log("hazard: ${sig.on}")
                state = state.copy(hazardOn = sig.on, lastEvent = if (sig.on) "${Messages.HAZARD_ON} ✓" else "비상등 꺼짐")
            }
            is VehicleSignal.Turn -> if (sig.side != state.turnSignal) { log("turn: ${sig.side}"); state = state.copy(turnSignal = sig.side) }
            is VehicleSignal.Brake -> if (sig.pressed != state.brakeOn) { log("brake: ${sig.pressed}"); state = state.copy(brakeOn = sig.pressed) }
            is VehicleSignal.Steering -> {
                if (kotlin.math.abs(sig.deg - (state.steeringDeg ?: 0f)) >= 5f) log("steering: ${sig.deg}")
                steering.onAngle(SystemClock.elapsedRealtime(), sig.deg)
                state = state.copy(steeringDeg = sig.deg)
            }
        }
        sendStatus(state)
    }

    /** Carry out what the check-in engine decided. Every action is reported in lastEvent by CarServices. */
    private fun run(effects: List<Effect>) {
        effects.forEach { e ->
            log("effect: $e")
            when (e) {
                is Effect.Speak -> say(Messages.cue(e.cue))
                is Effect.Chime -> { val m = Messages.cue(e.then); car.beep(e.beeps) { say(m) } }
                is Effect.AmbientRed -> car.ambientRed(e.on)
                is Effect.CabinLight -> car.cabinLight(e.on)
                Effect.AddRestStop -> { car.addNearestRestStop(); state = state.copy(guidingToRest = true) }
                Effect.CoolCabin -> car.coolCabin()
                is Effect.Call -> {
                    relay.send(callJson(e.to, e.reason, e.stage))
                    state = state.copy(lastEvent = when (e.stage) {
                        "pre_alert" -> "보호자에게 미리 알렸어요"
                        "cancel" -> "연락 취소를 폰에 알렸어요"
                        else -> "폰에 연락을 요청했어요: 보호자 전화 · 119 연결 준비"
                    })
                }
                is Effect.Play -> {
                    relay.send(buildJsonObject { put("type", "play"); put("playlist", e.playlist) }.toString())
                    state = state.copy(nowPlaying = checkins.nowPlaying, lastEvent = "폰에 플레이리스트 재생을 요청했어요")
                }
                is Effect.Log -> relay.send(buildJsonObject {
                    put("type", "ask"); put("ask", e.ask.name); put("answer", e.answer)
                }.toString())
            }
        }
        if (effects.isNotEmpty()) state = state.copy(
            checkin = checkins.checkin, contacting = checkins.contacting, nowPlaying = checkins.nowPlaying,
            pulse = checkins.pulse, ambientRed = checkins.ambientRed,
        )
    }

    private fun say(m: Message) { car.speak(m.sayAloud()) }

    private fun callJson(to: List<String>, reason: Reason, stage: String) = buildJsonObject {
        put("type", "call")
        put("to", kotlinx.serialization.json.JsonArray(to.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("reason", reason.name)
        put("stage", stage)
        state.carPos?.let { p -> put("location", buildJsonObject { put("lat", p.lat); put("lon", p.lon) }) }
    }.toString()

    /** What the car does when the level or reason changes. */
    private fun respond(s: CareState) {
        // An open question or a call in progress speaks for itself (check-in effects): no second voice.
        val asking = s.checkin != null || s.contacting != null
        val say = Messages.forState(s).speak
        // A different sound per level, then the same words as the screen.
        if (!asking) { if (s.level != Level.L0_NORMAL) car.chime(s.level) { say?.let(car::speak) } else say?.let(car::speak) }
        if (s.level == Level.L1_CAUTION) car.coolCabin()
        // Drowsiness at 60+: the nearest rest stop goes in as a waypoint without asking.
        if (s.level == Level.L2_WARNING && s.reason == Reason.DROWSY && !s.guidingToRest) {
            car.addNearestRestStop(); state = state.copy(guidingToRest = true)
        }
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
                run(checkins.reset())
                drowsiness.reset(); steering.reset()
                // Demo start: lights back to normal even if an earlier run ended while red.
                car.ambientRed(false); car.cabinLight(false)
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
            // Check-in flows through the real engines (same path as phone messages and car buttons).
            "cardiac" -> { freshCheckins(); inputs = inputs.copy(drivingOverride = true, heartRate = 92); pendingIhrn = true }
            "nopulse" -> { freshCheckins(); onPulse("absent") }
            "stresstalk" -> { freshCheckins(); inputs = inputs.copy(drivingOverride = true, heartRate = 108); advance(6) }
            "drowsy" -> {
                freshCheckins()
                inputs = inputs.copy(drivingOverride = true, heartRate = 72)
                // Sleep 4h40 (+25) and a steering correction after >10 s idle (+20) = 45: the L1 question.
                steering.injectIdleCorrection(SystemClock.elapsedRealtime(), 12)
            }
            "contacting" -> {
                freshCheckins()
                inputs = inputs.copy(drivingOverride = true, heartRate = 92); pendingIhrn = true
                evaluate()
                actions.answer(Ask.CARDIAC_CHECK, true)
                inputs = inputs.copy(drivingOverride = false)
            }
        }
    }

    /** Demo presets start each flow clean: no open question, snooze or call left from an earlier one. */
    private fun freshCheckins() {
        run(checkins.reset())
        drowsiness.reset(); steering.reset()
        engine.reset(); pendingIhrn = false
        inputs = inputs.copy(rmssd = null, glucose = null)
        if (inputs.condition.sleepMinutes == null) setCondition(DriverCondition(sleepMinutes = 280, energyScore = 45, restingHr = 68))
    }

    private fun stopScenario() {
        // Only a running scenario owns the clock speed (a demo `speed` message keeps its own).
        if (scenarioIndex >= 0) speed = 1
        scenarioIndex = -1
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
        private const val CAR_SPEED = "android.car.permission.CAR_SPEED"
    }
}
