package ai.caredrive.car.car

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.api.model.area.SeatArea as LightArea
import ai.caredrive.car.model.Level
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import java.util.Locale

/**
 * Thin wrapper over the Pleos Connect SDKs used by CareDrive:
 * TTS (voice), Vehicle (HVAC, gear) and NaviHelper (rest-stop waypoint).
 * Every result is reported through [onEvent] so the screen can show what the car did.
 */
class CarServices(
    context: Context,
    private val onEvent: (String) -> Unit,
    private val onGear: (VehicleGear) -> Unit,
    private val onLocation: (CurrentLocationInfo) -> Unit = {},
    private val onVehicle: (VehicleSignal) -> Unit = {},
) {
    private val app = context.applicationContext
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val vehicle = Vehicle(app)
    private val navi = NaviHelper(app)
    private val tts = TextToSpeech(app, Mode.ON_DEVICE)
    private val restAreas = RestAreas(app)

    private val tones = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
    private var ttsReady = false
    private var location: CurrentLocationInfo? = null
    private var hasDestination = false
    private var destinationName: String? = null
    private var pendingRestStop = false


    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { gear?.let(onGear) }
        override fun onFailed(e: Exception) = report("기어 상태를 읽지 못했어요", e)
    }

    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) { main.post { onVehicle(VehicleSignal.Turn(value?.takeIf { it != TurnSignalValue.OFF }?.name)) } }
        override fun onFailed(e: Exception) = report("방향지시등 상태를 읽지 못했어요", e)
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) { main.post { onVehicle(VehicleSignal.Brake(pressed)) } }
        override fun onFailed(e: Exception) = report("브레이크 상태를 읽지 못했어요", e)
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(deg: Float?) { deg?.let { main.post { onVehicle(VehicleSignal.Steering(it)) } } }
        override fun onFailed(e: Exception) = report("핸들 각도를 읽지 못했어요", e)
    }

    // Driver seat and door: used by the "정체 확인" (did the driver get out?).
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)
    private val seatListener = object : SeatOccupancyListener {
        // On the Pleos emulator the event carries the previous value, so read the current one after it settles.
        override fun onSeatOccupancyUpdated(occupied: Boolean?) { main.postDelayed({ readSeat() }, 400) }
        override fun onFailed(e: Exception) = report("운전석 착석 상태를 읽지 못했어요", e)
    }
    private fun readSeat() = guard("착석") {
        vehicle.getSeat().getCurrentSeatOccupancy(driverSeat, { v -> main.post { onVehicle(VehicleSignal.Seat(v)) } }, { report("착석 조회 실패", it) })
    }
    private val beltListener = object : SeatBeltBuckledStateListener {
        override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) { main.post { onVehicle(VehicleSignal.Belt(buckled)) } }
        override fun onFailed(e: Exception) = report("안전벨트 상태를 읽지 못했어요", e)
    }
    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(position: Int?) { position?.let { main.post { onVehicle(VehicleSignal.Door(it > 0)) } } }
        override fun onFailed(e: Exception) = report("운전석 문 상태를 읽지 못했어요", e)
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            location = info
            onLocation(info)
            if (pendingRestStop) {
                pendingRestStop = false
                routeToRestStop(info)
            }
        }
        override fun onDestinationInfo(info: DestinationInfo) {
            hasDestination = info.name.isNotBlank()
            destinationName = info.name.takeIf { it.isNotBlank() }
        }
        override fun onRouteStarted(info: RouteStartInfo) { hasDestination = true }
        override fun onDestinationChanged() { navi.getDestinationInfo() }
        override fun onRouteEnded() { hasDestination = false }
        override fun onRouteCancelled() { hasDestination = false }
        override fun onError(errorCode: NaviErrorCode) { onEvent("내비 오류: $errorCode") }
    }

    fun start() {
        vehicle.initialize()
        vehicle.getGear().registerGearSelection(gearListener)
        vehicle.getGear().getCurrentGearSelectionState({ gear -> gear?.let(onGear) }, { report("기어 조회 실패", it) })
        // Each signal on its own: one failing (permission, unsupported car) never stops the others.
        guard("방향지시등") { vehicle.getTurnSignal().registerTurnSignalState(turnListener) }
        guard("브레이크") { vehicle.getBrake().registerBrakePedalStatus(brakeListener) }
        guard("핸들") { vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }
        guard("착석") {
            vehicle.getSeat().registerSeatOccupancy(driverSeat, seatListener)
            readSeat()
        }
        guard("안전벨트") {
            vehicle.getSeat().registerSeatBeltBuckledState(driverSeat, beltListener)
            vehicle.getSeat().getCurrentSeatBeltBuckledState(driverSeat, { onVehicle(VehicleSignal.Belt(it)) }, { report("안전벨트 조회 실패", it) })
        }
        guard("운전석 문") { vehicle.getDoor().registerDoorPosition(driverDoor, doorListener) }
        startCarProperties()

        navi.initialize(
            onConnected = {
                navi.addListener(naviListener)
                navi.getDestinationInfo()
                navi.getCurrentLocationInfo()
            },
            onFailed = { report("내비 연결 실패", it) },
        )

        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                tts.setLocale(Locale.KOREA)
                ttsReady = true
                pendingSpeech?.let { main.post { pendingSpeech = null; lastSpoken = null; speak(it) } }
            }
            override fun onFailure(errorType: ErrorType) { onEvent("음성 초기화 실패: $errorType") }
        })
    }

    fun stop() {
        vehicle.getGear().unregisterGearSelection(gearListener)
        guard("방향지시등") { vehicle.getTurnSignal().unregisterTurnSignalState(turnListener) }
        guard("브레이크") { vehicle.getBrake().unregisterBrakePedalStatus(brakeListener) }
        guard("핸들") { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
        guard("착석") { vehicle.getSeat().unregisterSeatOccupancy(driverSeat, seatListener) }
        guard("안전벨트") { vehicle.getSeat().unregisterSeatBeltBuckledState(driverSeat, beltListener) }
        guard("운전석 문") { vehicle.getDoor().unregisterDoorPosition(driverDoor, doorListener) }
        guard("차량 속성") { carProps?.disconnect() }
        vehicle.release()
        navi.removeListener(naviListener)
        navi.release()
        tts.release()
        tones?.release()
    }

    /**
     * Level sound before the spoken message, so the level is clear without looking:
     * one beep for caution, two for warning, three after stopping. Then [then] (usually speech).
     */
    fun chime(level: Level, then: () -> Unit) = beep(when (level) {
        Level.L0_NORMAL -> 0
        Level.L1_CAUTION -> 1
        Level.L2_WARNING -> 2
        Level.L3_STOPPED -> 3
    }, then)

    /** [beeps] short beeps, then [then]. */
    fun beep(beeps: Int, then: () -> Unit) {
        if (tones == null || beeps == 0) { then(); return }
        repeat(beeps) { i -> main.postDelayed({ tones.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS) }, i * BEEP_GAP_MS) }
        main.postDelayed(then, beeps * BEEP_GAP_MS + 150)
    }

    private var pendingSpeech: String? = null
    private var lastSpoken: Pair<String, Long>? = null

    /** Speak [text]; the same sentence within 3 s is ignored, and speech before TTS is ready is queued. */
    fun speak(text: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        lastSpoken?.let { (t, at) -> if (t == text && now - at < 3_000) return }
        lastSpoken = text to now
        // Log the screen words (video captions show "119"); the voice gets the spoken form ("일일구").
        Log.i(TAG, "speak: ${text.replace("일일구", "119")}")
        if (!ttsReady) { pendingSpeech = text; return }
        tts.stop()
        tts.speak(ai.caredrive.car.ui.Message.spoken(text), true)
    }


    /** Make the cabin a little cooler: driver temperature −2℃ and fan +2, within the car's limits. */
    private var lastCooledAt = 0L

    fun coolCabin() {
        // One alert can ask twice (level change + question): cool once per minute, not to the fan's maximum.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCooledAt < 60_000L) return
        lastCooledAt = now
        val hvac = vehicle.getHVAC()
        // HVAC areas are car-specific (power and fan cover the whole first row, temperature is per seat),
        // so always use the area the car reports that includes the driver seat.
        hvac.checkHvacPowerCapabilities({ powerAreas ->
            val area = driverArea(powerAreas)
            if (area == null) { adjustTemperatureAndFan(); return@checkHvacPowerCapabilities }
            hvac.setHvacPower(area, true, { _, on ->
                Log.i(TAG, "hvac power set: $on")
                // The power change reaches the other HVAC properties a moment later.
                main.postDelayed({ adjustTemperatureAndFan() }, 800)
            }, { report("공조 켜기 실패", it) })
        }, { report("공조 기능 확인 실패", it) })
    }

    private fun adjustTemperatureAndFan() {
        val hvac = vehicle.getHVAC()
        hvac.checkHvacTemperatureCapabilities({ bounds ->
            val area = driverArea(bounds.keys) ?: return@checkHvacTemperatureCapabilities onEvent("온도 조절 미지원")
            val min = bounds[area]?.minValue ?: 16f
            hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, { current ->
                // Cool by 2℃ but never below a comfortable 20℃ (repeated cautions must not freeze the cabin).
                val target = ((current ?: 22f) - 2f).coerceAtLeast(maxOf(min, COMFORT_FLOOR_C))
                if (current != null && current <= target) { onEvent("운전석 온도 ${current}℃ 유지"); return@getHvacTemperature }
                hvac.setHvacTemperature(area, target, TemperatureUnit.CELSIUS,
                    { _, v -> onEvent("운전석 온도 ${v}℃로 낮춤") },
                    { report("온도 설정 실패", it) })
            }, { report("온도 조회 실패", it) })
        }, { report("온도 기능 확인 실패", it) })

        hvac.checkHvacFanSpeedCapabilities({ bounds ->
            val area = driverArea(bounds.keys) ?: return@checkHvacFanSpeedCapabilities onEvent("풍량 조절 미지원")
            val max = bounds[area]?.maxValue ?: 8
            hvac.getHvacFanSpeed(area, { current ->
                val target = ((current ?: 2) + 2).coerceAtMost(max)
                hvac.setHvacFanSpeed(area, target,
                    { _, v -> onEvent("풍량 ${v}단으로 올림") },
                    { report("풍량 설정 실패", it) })
            }, { report("풍량 조회 실패", it) })
        }, { report("풍량 기능 확인 실패", it) })
    }

    private fun driverArea(areas: Collection<SeatArea>): SeatArea? =
        areas.firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: areas.firstOrNull()

    /** Nearest rest area to show before the driver decides. Null until the car location is known. */
    fun nearestRestArea(): Pair<RestArea, Double>? {
        val loc = location ?: run { navi.getCurrentLocationInfo(); return null }
        return restAreas.nearest(loc.latitude, loc.longitude, loc.heading)
    }

    /** Route to the nearest rest area: as a waypoint if a destination is set, otherwise as the destination. */
    fun addNearestRestStop() {
        val loc = location
        if (loc == null) {
            pendingRestStop = true
            navi.getCurrentLocationInfo()
            onEvent("현재 위치 확인 중…")
            return
        }
        routeToRestStop(loc)
        navi.getCurrentLocationInfo()
    }

    private fun routeToRestStop(loc: CurrentLocationInfo) {
        val (rest, km) = restAreas.nearest(loc.latitude, loc.longitude, loc.heading) ?: run {
            onEvent("근처 휴게소를 찾지 못했어요"); return
        }
        if (hasDestination && destinationName == rest.name) {
            onEvent("이미 ${rest.name}(으)로 안내 중이에요")
            return
        }
        if (hasDestination) {
            navi.addWaypoint(
                RequestWaypointInfo(
                    latitude = rest.lat,
                    longitude = rest.lon,
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = "",
                    poiName = rest.name,
                    poiSubId = "0",
                    address = "",
                )
            )
            onEvent("경유지 추가: ${rest.name} (%.1fkm)".format(km))
        } else {
            navi.requestRoute(
                RouteInfo(
                    latitude = rest.lat,
                    longitude = rest.lon,
                    displayLongitude = null,
                    displayLatitude = null,
                    poiName = rest.name,
                    poiId = "",
                    poiSubId = "0",
                    address = "",
                )
            )
            hasDestination = true
            destinationName = rest.name
            onEvent("목적지 설정: ${rest.name} (%.1fkm)".format(km))
        }
    }

    // --- Ambient and cabin light (Pleos Light) ------------------------------------------------------

    /** Colors before CareDrive turned the ambient light red, restored by [ambientRed] (false). */
    private val savedColors = mutableMapOf<LightArea, Int>()

    /** Ambient light red for an emergency, or back to the driver's own color. */
    fun ambientRed(on: Boolean) = guard("앰비언트 조명") {
        val light = vehicle.getLight()
        light.checkAmbientLightBrightnessColorCapabilities({ _, areas ->
            if (areas.isEmpty()) { onEvent("앰비언트 조명 미지원"); return@checkAmbientLightBrightnessColorCapabilities }
            areas.forEach { area ->
                if (on) {
                    light.getAmbientLightBrightnessColor(area, { c ->
                        if (c != null && c != RED && area !in savedColors) savedColors[area] = c
                        light.setAmbientLightBrightnessColor(area, RED, { _, _ -> onEvent("실내 조명을 붉게 바꿨어요") }, { report("조명 색 변경 실패", it) })
                    }, { report("조명 색 조회 실패", it) })
                } else {
                    // Saved color lost (app restarted while red): fall back to the default ambient color.
                    val back = savedColors.remove(area) ?: DEFAULT_AMBIENT
                    light.setAmbientLightBrightnessColor(area, back, { _, _ -> onEvent("실내 조명을 원래대로 돌렸어요") }, { report("조명 복원 실패", it) })
                }
            }
        }, { report("앰비언트 조명 확인 실패", it) })
    }

    /** Cabin light on after stopping in an emergency (helps rescuers), off when it is over. */
    fun cabinLight(on: Boolean) = guard("실내등") {
        vehicle.getLight().setCabinLightSwitch(if (on) LightSwitch.ON else LightSwitch.OFF,
            { v -> onEvent(if (v == LightSwitch.ON) "실내등을 켰어요" else "실내등을 껐어요") },
            { report("실내등 설정 실패", it) })
    }

    // --- AAOS car properties: speed and hazard lights (Pleos SDK has neither) -----------------------

    private var carProps: android.car.Car? = null

    /** Vehicle speed and hazard lights from AAOS CarPropertyManager. Needs CAR_SPEED (runtime) and CAR_EXTERIOR_LIGHTS. */
    fun startCarProperties() = guard("차량 속성") {
        carProps?.disconnect()
        val car = android.car.Car.createCar(app) ?: run { onEvent("차량 속성 서비스 없음"); return@guard }
        carProps = car
        val pm = car.getCarManager(android.car.Car.PROPERTY_SERVICE) as android.car.hardware.property.CarPropertyManager
        val cb = object : android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: android.car.hardware.CarPropertyValue<*>) {
                val value = v.value
                main.post {
                    when (v.propertyId) {
                        PERF_VEHICLE_SPEED -> (value as? Float)?.let { onVehicle(VehicleSignal.Speed(Math.round(it * 3.6f))) }
                        HAZARD_LIGHTS_STATE -> (value as? Int)?.let { onVehicle(VehicleSignal.Hazard(it == 1)) }
                    }
                }
            }
            override fun onErrorEvent(propId: Int, zone: Int) { Log.w(TAG, "car property error $propId") }
        }
        guard("속도") { pm.registerCallback(cb, PERF_VEHICLE_SPEED, android.car.hardware.property.CarPropertyManager.SENSOR_RATE_NORMAL) }
        guard("비상등") { pm.registerCallback(cb, HAZARD_LIGHTS_STATE, android.car.hardware.property.CarPropertyManager.SENSOR_RATE_ONCHANGE) }
    }

    /** Run [block]; any failure (missing permission, unsupported car, missing library) is shown, never a crash. */
    private inline fun guard(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "$what 실패", t); onEvent("$what 사용 불가: ${t.javaClass.simpleName}") }
    }

    private fun report(msg: String, e: Throwable) {
        Log.w(TAG, msg, e)
        onEvent("$msg: ${e.javaClass.simpleName}")
    }

    companion object {
        private const val TAG = "CareDrive"
        private const val COMFORT_FLOOR_C = 20f
        private const val BEEP_MS = 160
        private const val BEEP_GAP_MS = 280L
        private const val RED = 0xFF0000
        /** Pleos Connect default ambient color (cyan). */
        private const val DEFAULT_AMBIENT = 0x00E5FF
        /** android.car VehiclePropertyIds (stable AAOS values). */
        const val PERF_VEHICLE_SPEED = 0x11600207
        const val HAZARD_LIGHTS_STATE = 0x11400E03
    }
}

/** Vehicle signals CareDrive reads and shows. */
sealed interface VehicleSignal {
    data class Turn(val side: String?) : VehicleSignal
    data class Brake(val pressed: Boolean?) : VehicleSignal
    data class Steering(val deg: Float) : VehicleSignal
    data class Speed(val kmh: Int) : VehicleSignal
    data class Hazard(val on: Boolean) : VehicleSignal
    data class Seat(val occupied: Boolean?) : VehicleSignal
    data class Belt(val buckled: Boolean?) : VehicleSignal
    data class Door(val open: Boolean) : VehicleSignal
}
