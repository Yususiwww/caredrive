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
    NO_PULSE_ASK, NO_PULSE_PRE_ALERT, NO_PULSE_CALL, NO_PULSE_CANCELLED, PULSE_BACK, OFF_WRIST, WEAK_SIGNAL,
    STRESS_ASK, STRESS_PLAY, STRESS_OK,
    DROWSY_ASK, DROWSY_REST, DROWSY_OK,
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
    private val snoozeUntil = mutableMapOf<Ask, Long>()

    /** NO_PULSE is active: the main screen shows this before any risk level. */
    val noPulseActive get() = checkin?.ask == Ask.NO_PULSE

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
                lastReask = now
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
     * Called every tick with the risk decision. Starts questions and runs timers.
     * [parked] = gear P (or demo override).
     */
    fun update(now: Long, level: Level, reason: Reason, parked: Boolean): List<Effect> {
        val c = checkin
        // A heart warning outranks an open conversation question.
        val cardiac = !parked && level == Level.L2_WARNING && reason in CARDIAC && now >= (snoozeUntil[Ask.CARDIAC_CHECK] ?: 0L)
        if (c != null && !(cardiac && (c.ask == Ask.STRESS_TALK || c.ask == Ask.DROWSY))) return tick(now, c, parked)
        if (parked) return emptyList()
        val ask = when {
            level == Level.L2_WARNING && reason in CARDIAC -> Ask.CARDIAC_CHECK
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
            Ask.NO_PULSE -> emptyList()
        }
    }

    private fun tick(now: Long, c: Checkin, parked: Boolean): List<Effect> {
        val elapsedS = ((now - c.startedMs) / 1000).toInt()
        return when (c.ask) {
            Ask.CARDIAC_CHECK -> when {
                c.helpRequested && parked -> startContacting(cardiacReason)
                c.helpRequested -> emptyList()
                elapsedS >= CARDIAC_ANSWER_S -> help(now, c, answer = "none", parked = parked)
                else -> { checkin = c.copy(secondsLeft = CARDIAC_ANSWER_S - elapsedS); emptyList() }
            }
            Ask.NO_PULSE -> {
                val out = mutableListOf<Effect>()
                var next = c.copy(secondsLeft = (NO_PULSE_CALL_S - elapsedS).coerceAtLeast(0))
                if (elapsedS >= NO_PULSE_CALL_S) {
                    checkin = null
                    contacting = Contacting(contacts = true, emergency119 = true, reason = Reason.NO_PULSE)
                    out += Effect.Log(Ask.NO_PULSE, "none")
                    out += Effect.Call(listOf("contacts", "119_dial"), Reason.NO_PULSE, "call")
                    out += Effect.Speak(Cue.NO_PULSE_CALL)
                    if (parked) out += Effect.CabinLight(true)
                    return out
                }
                if (elapsedS >= NO_PULSE_PRE_ALERT_S && !c.preAlerted) {
                    next = next.copy(preAlerted = true)
                    out += Effect.Call(listOf("contacts"), Reason.NO_PULSE, "pre_alert")
                    out += Effect.Speak(Cue.NO_PULSE_PRE_ALERT)
                } else if (elapsedS < NO_PULSE_PRE_ALERT_S && now - lastReask >= NO_PULSE_REASK_S * 1000L) {
                    // Re-ask in the first minute only, so the 60 s announcement is not cut off.
                    lastReask = now
                    out += Effect.Speak(Cue.NO_PULSE_ASK)
                }
                checkin = next
                out
            }
            // No answer to a conversation question: let it go quietly (the caution screen stays).
            Ask.STRESS_TALK, Ask.DROWSY -> {
                if (elapsedS >= TALK_TIMEOUT_S) { checkin = null; snoozeUntil[c.ask] = now + SNOOZE_MS; listOf(Effect.Log(c.ask, "none")) }
                else emptyList()
            }
        }
    }

    /** Why the cardiac question was asked (sent with the call). */
    private var cardiacReason = Reason.IHRN_NEW

    private fun help(now: Long, c: Checkin, answer: String, parked: Boolean): List<Effect> {
        checkin = c.copy(helpRequested = true, secondsLeft = null, totalSeconds = null)
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
            Ask.CARDIAC_CHECK -> if (c.helpRequested) emptyList() else if (yes) help(now, c, "help", parked) else {
                checkin = null
                snoozeUntil[ask] = now + SNOOZE_MS
                listOf(Effect.Log(ask, "ok"), Effect.Speak(Cue.CARDIAC_OK))
            }
            Ask.NO_PULSE -> {
                checkin = null
                val out = mutableListOf<Effect>(Effect.Log(ask, "ok"), Effect.Speak(Cue.NO_PULSE_CANCELLED))
                if (c.preAlerted) out += Effect.Call(listOf("contacts"), Reason.NO_PULSE, "cancel")
                out + red(false)
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
        const val NO_PULSE_REASK_S = 20
        const val NO_PULSE_PRE_ALERT_S = 60
        const val NO_PULSE_CALL_S = 180
        const val TALK_TIMEOUT_S = 30
        const val SNOOZE_MS = 15 * 60_000L
        const val PLAYLIST_NAME = "편안한 플레이리스트"
        val CARDIAC = setOf(Reason.IHRN_NEW, Reason.HR_LOW, Reason.HR_HIGH, Reason.HR_SUSTAINED)
        val STRESS = setOf(Reason.HR_SUSTAINED, Reason.STRESS_HIGH)
    }
}
