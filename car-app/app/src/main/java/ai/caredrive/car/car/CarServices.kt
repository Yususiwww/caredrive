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
    fun chime(level: Level, then: () -> Unit) {
        val beeps = when (level) {
            Level.L0_NORMAL -> 0
            Level.L1_CAUTION -> 1
            Level.L2_WARNING -> 2
            Level.L3_STOPPED -> 3
        }
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
        Log.i(TAG, "speak: $text")
        if (!ttsReady) { pendingSpeech = text; return }
        tts.stop()
        tts.speak(text, true)
    }


    /** Make the cabin a little cooler: driver temperature −2℃ and fan +2, within the car's limits. */
    fun coolCabin() {
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

    private fun report(msg: String, e: Throwable) {
        Log.w(TAG, msg, e)
        onEvent("$msg: ${e.javaClass.simpleName}")
    }

    companion object {
        private const val TAG = "CareDrive"
        private const val COMFORT_FLOOR_C = 20f
        private const val BEEP_MS = 160
        private const val BEEP_GAP_MS = 280L
    }
}
