package ai.caredrive.car.ui

import ai.caredrive.car.engine.Factor
import ai.caredrive.car.engine.FactorKind
import ai.caredrive.car.engine.Grade
import ai.caredrive.car.engine.Mood
import ai.caredrive.car.engine.Readiness
import ai.caredrive.car.model.Ask
import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.Checkin
import ai.caredrive.car.model.Contacting
import ai.caredrive.car.model.DataState
import ai.caredrive.car.model.GeoPos
import ai.caredrive.car.model.HrSample
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.PulseState
import ai.caredrive.car.model.Reason
import ai.caredrive.car.model.RestPreview
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * TEMPORARY, debug build only: renders [CareScreen] with a fixed state so every design-D screen can be
 * screenshotted, including the new ones that have no demo preset yet. Not part of the product.
 *
 * adb shell am start -n ai.caredrive.car/.ui.DesignGalleryActivity --es screen nopulse --ez compact true --es theme light
 */
class DesignGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "drive"
        val compact = intent.getBooleanExtra("compact", false)
        val dark = intent.getStringExtra("theme") != "light"
        val state = galleryState(screen)
        val actions = object : CareActions {
            override fun acceptRestStop() {}
            override fun dismiss() {}
            override fun startEcgGuide() {}
            override fun notifyGuardian() {}
            override fun call119() {}
            override fun call119Now() {}
            override fun cancel119() {}
            override fun setMood(mood: Mood) {}
        }
        setContent { CareTheme(dark = dark) { CareScreen(state, actions, forceCompact = compact) } }
    }
}

private val now = 40 * 60_000L
private val history = (0..40).map { HrSample(now - (40 - it) * 45_000L, 74 + (it % 7) - 3 + if (it > 30) 6 else 0) }
private val drive = CareState(
    heartRate = 76, drivingMin = 42, nextBreakInMin = 60, connected = true, parked = false, speedKmh = 72,
    drowsiness = 28, hrHistory = history, nowMs = now, restingHr = 68, hrLimit = 110,
    readiness = Readiness(Grade.FAIR, listOf(Factor(FactorKind.SLEEP_SHORT, "수면 4시간 40분")), 90),
)
private val rest = RestPreview("성남졸음쉼터", 37.43, 127.13, 3.6, true)

private fun galleryState(id: String): CareState = when (id) {
    "predrive" -> drive.copy(parked = true, drivingMin = 0, speedKmh = null, nextBreakInMin = 90, mood = Mood.TIRED, drowsiness = null)
    "drive" -> drive
    "playing" -> drive.copy(nowPlaying = "편안한 플레이리스트", drowsiness = 46)
    "caution" -> drive.copy(level = Level.L1_CAUTION, reason = Reason.HR_SUSTAINED, heartRate = 108)
    "glucose" -> drive.copy(level = Level.L2_WARNING, reason = Reason.GLUCOSE_LOW, glucose = 62, restPreview = rest, carPos = GeoPos(37.40, 127.11))
    "guiding" -> drive.copy(level = Level.L2_WARNING, reason = Reason.GLUCOSE_LOW, guidingToRest = true, lastEvent = "성남졸음쉼터로 안내를 시작했어요")
    "stopped" -> drive.copy(level = Level.L3_STOPPED, reason = Reason.IHRN_NEW, parked = true, speedKmh = null)
    "nodata" -> drive.copy(data = DataState.STALE)
    "e119" -> drive.copy(level = Level.L3_STOPPED, reason = Reason.IHRN_NEW, parked = true, speedKmh = null, emergencyCountdown = 7)
    "cardiac" -> drive.copy(level = Level.L2_WARNING, reason = Reason.IHRN_NEW, heartRate = 146, checkin = Checkin(Ask.CARDIAC_CHECK, now, 22, 30))
    "stress" -> drive.copy(level = Level.L1_CAUTION, reason = Reason.STRESS_HIGH, heartRate = 98, checkin = Checkin(Ask.STRESS_TALK, now))
    "drowsy" -> drive.copy(level = Level.L1_CAUTION, reason = Reason.DROWSY, heartRate = 62, drowsiness = 52, checkin = Checkin(Ask.DROWSY, now))
    "nopulse" -> drive.copy(level = Level.L3_STOPPED, reason = Reason.NO_PULSE, pulse = PulseState.ABSENT, heartRate = null, checkin = Checkin(Ask.NO_PULSE, now, 161, 180))
    "nopulse2" -> drive.copy(level = Level.L3_STOPPED, reason = Reason.NO_PULSE, pulse = PulseState.ABSENT, heartRate = null, hazardOn = true, speedKmh = 0, checkin = Checkin(Ask.NO_PULSE, now, 95, 180, preAlerted = true))
    "contacting" -> drive.copy(level = Level.L3_STOPPED, reason = Reason.NO_PULSE, pulse = PulseState.ABSENT, heartRate = null, parked = true, hazardOn = true, contacting = Contacting(true, true, Reason.NO_PULSE))
    else -> drive
}
