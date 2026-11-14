package ai.caredrive.car.engine

import ai.caredrive.car.model.Ask
import ai.caredrive.car.model.Checkin
import ai.caredrive.car.model.Contacting
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.PulseState
import ai.caredrive.car.model.Reason

/** Lines the car says (and shows). The words live in ui/Messages.kt. */
enum class Cue {
    CARDIAC_ASK, CARDIAC_HELP, CARDIAC_OK, CONTACTING,
    NO_PULSE_ASK, NO_PULSE_DRIVER_INPUT, NO_PULSE_PRE_ALERT, NO_PULSE_CALL, NO_PULSE_CANCELLED, PULSE_BACK, OFF_WRIST, WEAK_SIGNAL,
    STRESS_ASK, STRESS_PLAY, STRESS_OK,
    DROWSY_ASK, DROWSY_REST, DROWSY_OK,
    STATIONARY_ASK, STATIONARY_PLAY,
    CONTACT_CANCELLED,
}

/** What the car should do; MainActivity carries these out with CarServices and the relay. */
sealed interface Effect {
    data class Speak(val cue: Cue) : Effect
    /** Beeps before speech: 1 caution, 2 warning, 3 no pulse. */
    data class Chime(val beeps: Int, val then: Cue) : Effect
    data class AmbientRed(val on: Boolean) : Effect
    data class CabinLight(val on: Boolean) : Effect
    data object AddRestStop : Effect
    data object CoolCabin : Effect
    /** Relay `call`: to = "contacts", "119_dial"; stage = pre_alert / call / cancel. */
    data class Call(val to: List<String>, val reason: Reason, val stage: String) : Effect
    /** Relay `play` (phone opens the playlist; only after the driver said yes). */
    data class Play(val playlist: String) : Effect
    /** Relay `ask` with the driver's answer, for the phone's event log. answer = help / ok / none / rest / play / cancel / pulse_back. */
    data class Log(val ask: Ask, val answer: String?) : Effect
}

/**
 * The car asks the driver and acts on the answer (spec: docs/decisions/2026-11-15-응급-대화-졸음-설계.md).
 * Pure Kotlin, time passed in (simulated clock), so every timing is unit-tested.
 * Timings are design examples; real values need medical review.
 */
class CheckinEngine {
    var checkin: Checkin? = null; private set
    var contacting: Contacting? = null; private set
    var nowPlaying: String? = null; private set
    var pulse: PulseState = PulseState.PRESENT; private set
    var ambientRed = false; private set
    private var lastReask = 0L
    /** Last steering or brake input from the car (simulated clock), see [onDriverInput]. */
    private var lastInputAt: Long? = null
    // NO_PULSE timer: counts only while the driver shows no input, so it can pause without being cancelled.
    private var noPulseElapsedMs = 0L
    private var noPulseLastTick = 0L
    private var inputDuringCheck = false
    private var inputAnnounced = false
    private val snoozeUntil = mutableMapOf<Ask, Long>()

    /** NO_PULSE is active: the main screen shows this before any risk level. */
    val noPulseActive get() = checkin?.ask == Ask.NO_PULSE

    /** STATIONARY with no answer: counting down to the call. */
    val stationaryEscalated get() = checkin?.let { it.ask == Ask.STATIONARY && it.escalated } == true

    /** Watch pulse state from the phone. Only a change to ABSENT starts the question. */
    fun onPulse(now: Long, state: PulseState): List<Effect> {
        val prev = pulse
        pulse = state
        if (state == prev) return emptyList()
        return when (state) {
            PulseState.ABSENT -> {
                if (noPulseActive) return emptyList()
                contacting = null
                checkin = Checkin(Ask.NO_PULSE, now, NO_PULSE_CALL_S, NO_PULSE_CALL_S)
                startEscalation(now)
                listOf(Effect.Log(Ask.NO_PULSE, null), Effect.Chime(3, Cue.NO_PULSE_ASK)) + red(true)
            }
            PulseState.PRESENT -> if (noPulseActive) {
                checkin = null
                listOf(Effect.Log(Ask.NO_PULSE, "pulse_back"), Effect.Speak(Cue.PULSE_BACK)) + red(false)
            } else emptyList()
            // Never escalates (false alarms). A running NO_PULSE timer keeps running: only the driver's answer stops it.
            PulseState.OFF_WRIST -> listOf(Effect.Speak(Cue.OFF_WRIST))
            PulseState.WEAK -> listOf(Effect.Speak(Cue.WEAK_SIGNAL))
        }
    }

    /**
     * Steering (a few degrees or a reversal) or a brake press, reported by the car.
     * During a NO_PULSE check this means the driver is evidently active: likely a watch sensor problem.
     */
    fun onDriverInput(now: Long) { lastInputAt = now }

    /** When the current countdown (no pulse / no answer) began: only input after this counts. */
    private var escalationStart = 0L

    /** Steering or brake within the last 20 s, while the check is open (earlier input says nothing about now). */
    private fun driverActive(now: Long) = lastInputAt?.let { it >= escalationStart && now - it <= DRIVER_INPUT_WINDOW_MS } ?: false

    /**
     * Called every tick with the risk decision. Starts questions and runs timers.
     * [parked] = gear P (or demo override). [stationaryDue] = [Stationary.due] (in gear, still, no input).
     */
    fun update(
        now: Long, level: Level, reason: Reason, parked: Boolean,
        stationaryDue: Boolean = false, carMoving: Boolean = false,
    ): List<Effect> {
        val c = checkin
        // Driving off again answers the "정체 확인" by itself: the driver is awake.
        if (c?.ask == Ask.STATIONARY && carMoving) {
            snoozeUntil[Ask.STATIONARY] = now + STATIONARY_SNOOZE_MS
            if (c.escalated) return cancelEscalation(c, Reason.STATIONARY)
            checkin = null
            return listOf(Effect.Log(Ask.STATIONARY, "moved"))
        }
        // A heart warning outranks an open conversation question.
        val cardiac = !parked && level == Level.L2_WARNING && reason in CARDIAC && now >= (snoozeUntil[Ask.CARDIAC_CHECK] ?: 0L)
        if (c != null && !(cardiac && (c.ask == Ask.STRESS_TALK || c.ask == Ask.DROWSY))) return tick(now, c, parked)
        if (parked) return emptyList()
        val ask = when {
            level == Level.L2_WARNING && reason in CARDIAC -> Ask.CARDIAC_CHECK
            stationaryDue -> Ask.STATIONARY
            level == Level.L1_CAUTION && reason in STRESS -> Ask.STRESS_TALK
            level == Level.L1_CAUTION && reason == Reason.DROWSY -> Ask.DROWSY
            else -> return emptyList()
        }
        if (now < (snoozeUntil[ask] ?: 0L)) return emptyList()
        return when (ask) {
            Ask.CARDIAC_CHECK -> {
                cardiacReason = reason
                checkin = Checkin(ask, now, CARDIAC_ANSWER_S, CARDIAC_ANSWER_S)
                listOf(Effect.Chime(2, Cue.CARDIAC_ASK))
            }
            Ask.STRESS_TALK -> { checkin = Checkin(ask, now); listOf(Effect.Chime(1, Cue.STRESS_ASK)) }
            Ask.DROWSY -> { checkin = Checkin(ask, now); listOf(Effect.Chime(1, Cue.DROWSY_ASK), Effect.CoolCabin) }
            Ask.STATIONARY -> {
                checkin = Checkin(ask, now, STATIONARY_ANSWER_S, STATIONARY_ANSWER_S)
                listOf(Effect.Log(ask, null), Effect.Chime(1, Cue.STATIONARY_ASK))
            }
            Ask.NO_PULSE -> emptyList()
        }
    }

    private fun startEscalation(now: Long) {
        escalationStart = now
        lastReask = now
        noPulseElapsedMs = 0L; noPulseLastTick = now; inputDuringCheck = false; inputAnnounced = false
    }

    private fun tick(now: Long, c: Checkin, parked: Boolean): List<Effect> {
        val elapsedS = ((now - c.startedMs) / 1000).toInt()
        return when (c.ask) {
            Ask.CARDIAC_CHECK -> when {
                (c.helpRequested || c.escalated) && parked -> startContacting(cardiacReason)
                // Still moving, ladder running: any steering/brake input stops it (the driver is acting).
                c.escalated && driverActive(now) -> {
                    checkin = c.copy(escalated = false, helpRequested = true, secondsLeft = null, totalSeconds = null, paused = false)
                    helpAt = now
                    listOf(Effect.Log(Ask.CARDIAC_CHECK, "driver_input"))
                }
                c.escalated -> escalationTick(now, c, parked, cardiacReason)
                // Help asked (or no answer) but the car keeps moving: no answer and no input for 60 s more → same ladder as no pulse.
                c.helpRequested && now - maxOf(helpAt, lastInputAt ?: 0L) >= CARDIAC_MOVING_QUIET_S * 1000L -> {
                    checkin = c.copy(helpRequested = false, escalated = true, startedMs = now,
                        secondsLeft = NO_PULSE_CALL_S, totalSeconds = NO_PULSE_CALL_S)
                    startEscalation(now)
                    listOf(Effect.Log(Ask.CARDIAC_CHECK, "none_moving"), Effect.Chime(3, Cue.NO_PULSE_ASK)) + red(true)
                }
                c.helpRequested -> emptyList()
                elapsedS >= CARDIAC_ANSWER_S -> help(now, c, answer = "none", parked = parked)
                else -> { checkin = c.copy(secondsLeft = CARDIAC_ANSWER_S - elapsedS); emptyList() }
            }
            Ask.NO_PULSE -> escalationTick(now, c, parked, Reason.NO_PULSE)
            Ask.STATIONARY -> when {
                c.escalated -> escalationTick(now, c, parked, Reason.STATIONARY)
                elapsedS < STATIONARY_ANSWER_S -> { checkin = c.copy(secondsLeft = STATIONARY_ANSWER_S - elapsedS); emptyList() }
                // No answer, but the driver moved the wheel or the brake: awake. Leave it.
                lastInputAt?.let { it >= c.startedMs } == true -> {
                    checkin = null
                    snoozeUntil[Ask.STATIONARY] = now + STATIONARY_SNOOZE_MS
                    listOf(Effect.Log(Ask.STATIONARY, "driver_input"))
                }
                // No answer and no input: treat it like no pulse (loud re-ask, red light, pre-alert, call).
                else -> {
                    checkin = Checkin(Ask.STATIONARY, now, NO_PULSE_CALL_S, NO_PULSE_CALL_S, escalated = true)
                    startEscalation(now)
                    listOf(Effect.Log(Ask.STATIONARY, "none"), Effect.Chime(3, Cue.NO_PULSE_ASK)) + red(true)
                }
            }
            // No answer to a conversation question: let it go quietly (the caution screen stays).
            Ask.STRESS_TALK, Ask.DROWSY -> {
                if (elapsedS >= TALK_TIMEOUT_S) { checkin = null; snoozeUntil[c.ask] = now + SNOOZE_MS; listOf(Effect.Log(c.ask, "none")) }
                else emptyList()
            }
        }
    }

    /** NO_PULSE, and STATIONARY without an answer: re-ask, guardian pre-alert, then the call. Driver input pauses it. */
    private fun escalationTick(now: Long, c: Checkin, parked: Boolean, reason: Reason): List<Effect> {
        val out = mutableListOf<Effect>()
        // Car signals: steering or brake within the last 20 s pauses the timer (not cancelled).
        val active = driverActive(now)
        if (!active) noPulseElapsedMs += (now - noPulseLastTick).coerceAtLeast(0)
        noPulseLastTick = now
        if (active) {
            inputDuringCheck = true
            if (!inputAnnounced) {
                inputAnnounced = true
                out += Effect.Log(c.ask, "driver_input")
                out += Effect.Speak(Cue.NO_PULSE_DRIVER_INPUT)
            }
        }
        val elapsedS = (noPulseElapsedMs / 1000).toInt()
        // No input at all: the person may be unconscious, so the guardian hears earlier (30 s instead of 60 s).
        val preAlertS = if (inputDuringCheck) NO_PULSE_PRE_ALERT_S else NO_PULSE_PRE_ALERT_STILL_S
        var next = c.copy(secondsLeft = (NO_PULSE_CALL_S - elapsedS).coerceAtLeast(0), paused = active)
        if (elapsedS >= NO_PULSE_CALL_S) {
            checkin = null
            contacting = Contacting(contacts = true, emergency119 = true, reason = reason)
            out += Effect.Log(c.ask, "none")
            out += Effect.Call(listOf("contacts", "119_dial"), reason, "call")
            out += Effect.Speak(Cue.NO_PULSE_CALL)
            if (parked) out += Effect.CabinLight(true)
            return out
        }
        if (!active && elapsedS >= preAlertS && !c.preAlerted) {
            next = next.copy(preAlerted = true)
            out += Effect.Call(listOf("contacts"), reason, "pre_alert")
            out += Effect.Speak(Cue.NO_PULSE_PRE_ALERT)
        } else if (!active && elapsedS < preAlertS && now - lastReask >= NO_PULSE_REASK_S * 1000L) {
            // Re-ask only before the pre-alert, so that announcement is not cut off.
            lastReask = now
            out += Effect.Speak(Cue.NO_PULSE_ASK)
        }
        checkin = next
        return out
    }

    /** Why the cardiac question was asked (sent with the call). */
    private var cardiacReason = Reason.IHRN_NEW

    /** When help was asked (or assumed after 30 s): start of the 60 s quiet window while moving. */
    private var helpAt = 0L

    private fun help(now: Long, c: Checkin, answer: String, parked: Boolean): List<Effect> {
        checkin = c.copy(helpRequested = true, secondsLeft = null, totalSeconds = null)
        helpAt = now
        val out = listOf(Effect.Log(Ask.CARDIAC_CHECK, answer), Effect.Speak(Cue.CARDIAC_HELP)) + red(true) + Effect.AddRestStop
        return if (parked) out + startContacting(cardiacReason) else out
    }

    private fun startContacting(reason: Reason): List<Effect> {
        checkin = null
        contacting = Contacting(contacts = true, emergency119 = true, reason = reason)
        return listOf(
            Effect.Call(listOf("contacts", "119_dial"), reason, "call"),
            Effect.Speak(Cue.CONTACTING),
            Effect.CabinLight(true),
        )
    }

    /**
     * Driver tapped a button. [yes] is the first (primary) button:
     * CARDIAC_CHECK 도움이 필요해요 / 괜찮아요, NO_PULSE 괜찮아요 (either value cancels),
     * STRESS_TALK 좀 그래요 / 괜찮아요, DROWSY 쉬어 갈게요 / 괜찮아요.
     */
    fun answer(now: Long, ask: Ask, yes: Boolean, parked: Boolean): List<Effect> {
        val c = checkin?.takeIf { it.ask == ask } ?: return emptyList()
        return when (ask) {
            Ask.CARDIAC_CHECK -> if (c.escalated) {
                // Any answer stops the ladder. Help: back to "stop and we contact". OK: done.
                if (yes) {
                    checkin = c.copy(escalated = false, helpRequested = true, secondsLeft = null, totalSeconds = null)
                    helpAt = now
                    listOf(Effect.Log(ask, "help"), Effect.Speak(Cue.CARDIAC_HELP))
                } else {
                    snoozeUntil[ask] = now + SNOOZE_MS
                    cancelEscalation(c, cardiacReason)
                }
            } else if (c.helpRequested) emptyList() else if (yes) help(now, c, "help", parked) else {
                checkin = null
                snoozeUntil[ask] = now + SNOOZE_MS
                listOf(Effect.Log(ask, "ok"), Effect.Speak(Cue.CARDIAC_OK))
            }
            Ask.NO_PULSE -> cancelEscalation(c, Reason.NO_PULSE)
            Ask.STATIONARY -> {
                // Any answer means the driver is awake: no "정체 확인" for 2 hours.
                snoozeUntil[ask] = now + STATIONARY_SNOOZE_MS
                when {
                    c.escalated -> cancelEscalation(c, Reason.STATIONARY)
                    yes -> {
                        checkin = null
                        nowPlaying = PLAYLIST_NAME
                        listOf(Effect.Log(ask, "play"), Effect.Speak(Cue.STATIONARY_PLAY), Effect.Play("calm"))
                    }
                    else -> { checkin = null; listOf(Effect.Log(ask, "ok")) }
                }
            }
            Ask.STRESS_TALK -> {
                checkin = null
                // Either answer closes the topic for 15 minutes: the car asked once and does not nag.
                snoozeUntil[ask] = now + SNOOZE_MS
                if (yes) {
                    nowPlaying = PLAYLIST_NAME
                    listOf(Effect.Log(ask, "play"), Effect.Speak(Cue.STRESS_PLAY), Effect.Play("calm"), Effect.CoolCabin)
                } else {
                    snoozeUntil[ask] = now + SNOOZE_MS
                    listOf(Effect.Log(ask, "ok"), Effect.Speak(Cue.STRESS_OK))
                }
            }
            Ask.DROWSY -> {
                checkin = null
                snoozeUntil[ask] = now + SNOOZE_MS
                if (yes) listOf(Effect.Log(ask, "rest"), Effect.Speak(Cue.DROWSY_REST), Effect.AddRestStop)
                else { snoozeUntil[ask] = now + SNOOZE_MS; listOf(Effect.Log(ask, "ok"), Effect.Speak(Cue.DROWSY_OK)) }
            }
        }
    }

    private fun cancelEscalation(c: Checkin, reason: Reason): List<Effect> {
        checkin = null
        val out = mutableListOf<Effect>(Effect.Log(c.ask, "ok"), Effect.Speak(Cue.NO_PULSE_CANCELLED))
        if (c.preAlerted) out += Effect.Call(listOf("contacts"), reason, "cancel")
        return out + red(false)
    }

    /** Driver tapped 취소 on the contacting screen. */
    fun cancelContact(): List<Effect> {
        val c = contacting ?: return emptyList()
        contacting = null
        return listOf(Effect.Call(listOf("contacts", "119_dial"), c.reason, "cancel"), Effect.Speak(Cue.CONTACT_CANCELLED)) + red(false) +
            Effect.CabinLight(false)
    }

    /** New trip: forget questions, snoozes and playback. */
    fun reset(): List<Effect> {
        val out = red(false)
        checkin = null; contacting = null; nowPlaying = null; pulse = PulseState.PRESENT; snoozeUntil.clear()
        return out
    }

    private fun red(on: Boolean): List<Effect> {
        if (ambientRed == on) return emptyList()
        ambientRed = on
        return listOf(Effect.AmbientRed(on))
    }

    companion object {
        const val CARDIAC_ANSWER_S = 30
        /** After help/no answer while still moving: this long with no answer and no input starts the ladder. */
        const val CARDIAC_MOVING_QUIET_S = 60
        const val NO_PULSE_REASK_S = 20
        const val STATIONARY_ANSWER_S = 30
        const val STATIONARY_SNOOZE_MS = 2 * 60 * 60_000L
        const val NO_PULSE_PRE_ALERT_S = 60
        /** Pre-alert when the car saw no steering or brake input at all during the check. */
        const val NO_PULSE_PRE_ALERT_STILL_S = 30
        const val DRIVER_INPUT_WINDOW_MS = 20_000L
        const val NO_PULSE_CALL_S = 180
        const val TALK_TIMEOUT_S = 30
        const val SNOOZE_MS = 15 * 60_000L
        const val PLAYLIST_NAME = "편안한 플레이리스트"
        val CARDIAC = setOf(Reason.IHRN_NEW, Reason.HR_LOW, Reason.HR_HIGH, Reason.HR_SUSTAINED)
        val STRESS = setOf(Reason.HR_SUSTAINED, Reason.STRESS_HIGH)
    }
}
