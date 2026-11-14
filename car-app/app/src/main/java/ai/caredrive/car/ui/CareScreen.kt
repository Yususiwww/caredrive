package ai.caredrive.car.ui

import ai.caredrive.car.engine.Mood
import ai.caredrive.car.model.Ask
import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.Checkin
import ai.caredrive.car.model.Contacting
import ai.caredrive.car.model.DataState
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.PulseState
import ai.caredrive.car.model.Reason
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Below this width the narrow layout is used (Pleos split window). */
private val CompactBelow = 1200.dp

/** Width used when the demo forces the narrow layout: about half of the Pleos app area. */
private val DemoCompactWidth = 820.dp

/** Seconds a caution stays as title plus line before it shrinks to one quiet line (no buttons at L1). */
private const val CautionShrinkMs = 8_000L

/** Left rail of the wide layout on L2/L3 and question screens: the gauges step aside for the message. */
private val RailWidth = 360.dp

/** Driver-facing actions the screen can request. */
interface CareActions {
    fun acceptRestStop()
    fun dismiss()
    fun startEcgGuide()
    fun notifyGuardian()
    /** Starts a cancellable countdown; the call is placed when it ends. */
    fun call119()
    fun call119Now()
    fun cancel119()
    fun setMood(mood: Mood)
    /**
     * Answer to an on-screen question ([CareState.checkin]). [yes] is the first button of
     * [Messages.buttons] (도움이 필요해요 / 좀 그래요 / 쉬어 갈게요, and NO_PULSE's only 괜찮아요).
     */
    fun answer(ask: Ask, yes: Boolean) {}
    /** [취소] on the contacting screen. */
    fun cancelContact() {}
}

/**
 * Design D, "콕핏 계기판". A status strip with the level on top. While driving normally the screen is
 * two instrument dials (heart rate, next break) and one sentence. When the car has something to say
 * (L2/L3, a question, a call) the dials step into a left rail and the message takes the centre.
 */
@Composable
fun CareScreen(state: CareState, actions: CareActions, forceCompact: Boolean = false, demoBar: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().careBackdrop(CareColor).padding(horizontal = Space.xxl, vertical = Space.xl)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val compact = forceCompact || maxWidth < CompactBelow
            Column(Modifier.fillMaxHeight().then(if (forceCompact) Modifier.width(DemoCompactWidth) else Modifier.fillMaxWidth())) {
                StatusStrip(state, compact)
                Box(Modifier.weight(1f).fillMaxWidth().padding(top = Space.l)) { Body(state, actions, compact) }
            }
        }
        demoBar()
    }
}

private fun CareState.isPreDrive() = parked && drivingMin == 0

@Composable
private fun Body(state: CareState, actions: CareActions, compact: Boolean) {
    val checkin = state.checkin
    val msg = Messages.forState(state)
    when {
        state.contacting != null -> RailLayout(state, compact) { ContactingStage(state, state.contacting, actions, compact) }
        state.emergencyCountdown != null -> RailLayout(state, compact) {
            // Confirm first, cancel second (Pleos system popup order); at most two buttons.
            Stage(Messages.emergency(state.emergencyCountdown), compact) {
                CareButton("지금 전화", ButtonKind.Danger, actions::call119Now, compact)
                CareButton("취소", ButtonKind.Secondary, actions::cancel119, compact)
            }
        }
        checkin?.ask == Ask.NO_PULSE -> NoPulseScreen(state, checkin, actions, compact)
        checkin != null -> RailLayout(state, compact) { CheckinStage(state, checkin, actions, compact) }
        state.level == Level.L0_NORMAL && !state.parked -> DialsLayout(state, msg, compact)
        state.level == Level.L1_CAUTION -> {
            // Caution is information only: title and line for a few seconds, then one quiet line.
            var shrunk by remember(state.reason) { mutableStateOf(false) }
            LaunchedEffect(state.reason) { delay(CautionShrinkMs); shrunk = true }
            DialsLayout(state, msg, compact, quiet = shrunk)
        }
        else -> RailLayout(state, compact) { LevelStage(state, msg, actions, compact) }
    }
}

// ---------------------------------------------------------------------------------------------
// Frame: status strip, dials, rail
// ---------------------------------------------------------------------------------------------

/** Tell-tale strip: level shape and word underlined in the level colour, then quiet status on the right. */
@Composable
private fun StatusStrip(state: CareState, compact: Boolean) {
    val p = CareColor
    val levelColor = p.level(state.level)
    Row(
        Modifier.fillMaxWidth().height(if (compact) 80.dp else 88.dp).hairline(p.Hairline, bottom = true),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.fillMaxHeight().drawBehind {
                val h = 4.dp.toPx()
                drawRect(levelColor, Offset(0f, size.height - h), androidx.compose.ui.geometry.Size(size.width, h))
            }.padding(end = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LevelIcon(state.level, levelColor, Modifier.size(if (compact) 44.dp else 52.dp))
            Spacer(Modifier.width(Space.m))
            Text(Messages.levelLabel(state.level), color = p.levelLabel(state.level), style = CareType.label, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        Messages.dataLine(state)?.let {
            Text(it, color = p.CautionText, style = CareType.labelRegular, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } ?: run {
            // Only say something when it is wrong: a working connection needs no line.
            if (!compact && !state.connected) Text("폰 연결 안 됨", color = p.TextTertiary, style = CareType.labelRegular, maxLines = 1)
        }
    }
}

/** Vehicle signals the app reacts to, as chips (gear and speed, hazard lights, watch on the wrist). */
private fun CareState.signals(): List<Pair<String, Boolean>> = listOfNotNull(
    (if (parked) "P · 정차" else "D" + (speedKmh?.let { " · $it km/h" } ?: "")) to false,
    (Messages.HAZARD_ON to true).takeIf { hazardOn },
    ("워치 미착용" to false).takeIf { pulse == PulseState.OFF_WRIST },
    ("워치 신호 약함" to false).takeIf { pulse == PulseState.WEAK },
)

@Composable
private fun SignalChips(state: CareState, vertical: Boolean) {
    val chips = state.signals()
    if (vertical) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs), horizontalAlignment = Alignment.Start) {
            chips.forEach { (t, hz) -> SignalChip(t, hazard = hz) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) { chips.forEach { (t, hz) -> SignalChip(t, hazard = hz) } }
    }
}

@Composable
private fun NowPlaying(state: CareState, modifier: Modifier = Modifier) {
    state.nowPlaying?.let {
        Text(Messages.nowPlayingLine(it), color = CareColor.TextSecondary, style = CareType.labelRegular, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
    }
}

/** Heart rate on 40–160 bpm. Coloured with the level while it is the whole screen, quiet grey in the rail. */
@Composable
private fun HrDial(state: CareState, size: Dp, inRail: Boolean) {
    Dial(
        value = state.heartRate.takeIf { state.pulse != PulseState.ABSENT },
        min = 40, max = 160, major = 20, minor = if (inRail) 10 else 5,
        color = if (inRail) CareColor.TextSecondary else CareColor.level(state.level),
        label = "심박", unit = "bpm", size = size,
        dimmed = state.data != DataState.LIVE,
    )
}

/** Minutes to the next break on 0–120 (or the day's interval if longer). Parked: "정차 중". */
@Composable
private fun BreakDial(state: CareState, size: Dp, inRail: Boolean) {
    val next = state.nextBreakInMin
    val stopped = state.parked && !state.isPreDrive()
    val max = maxOf(120, ((state.readiness?.breakIntervalMin ?: 0) + 29) / 30 * 30)
    Dial(
        value = if (stopped) null else next?.coerceAtLeast(0),
        min = 0, max = max, major = 30, minor = if (inRail) 10 else 5,
        color = CareColor.TextPrimary,
        label = when {
            stopped -> "휴식"
            state.isPreDrive() -> "휴식 간격"
            else -> "다음 휴식"
        },
        unit = when {
            stopped -> null
            state.isPreDrive() -> "분마다"
            next != null && next <= 0 -> "지났어요"
            else -> "분 뒤"
        },
        valueText = if (stopped) "정차 중" else null,
        size = size,
    )
}

/** L0 driving and L1: the two dials are the screen, with one sentence underneath. */
@Composable
private fun DialsLayout(state: CareState, msg: Message, compact: Boolean, quiet: Boolean = false) {
    val p = CareColor
    Column(Modifier.fillMaxSize()) {
        if (compact) {
            SignalChips(state, vertical = false)
            Spacer(Modifier.height(Space.m))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val gap = if (compact) Space.l else Space.xxxl
            val mid = if (compact) 0.dp else 280.dp
            val d = minOf(maxHeight - if (compact) 72.dp else 0.dp, (maxWidth - mid - gap * 2) / 2, 580.dp)
            if (compact) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        HrDial(state, d, inRail = d < 320.dp)
                        BreakDial(state, d, inRail = d < 320.dp)
                    }
                    state.drowsiness?.let { DrowsinessBar(it, Modifier.width(360.dp)) }
                }
            } else {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    HrDial(state, d, inRail = false)
                    Column(Modifier.width(mid), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.width(1.5.dp).height(120.dp).background(p.Tick))
                        Spacer(Modifier.height(Space.l))
                        state.drowsiness?.let { DrowsinessBar(it, Modifier.fillMaxWidth()) }
                        Spacer(Modifier.height(Space.l))
                        SignalChips(state, vertical = true)
                    }
                    BreakDial(state, d, inRail = false)
                }
            }
        }
        // Foot: one rule, then title and one line, centred under the dials.
        Column(
            Modifier.fillMaxWidth().hairline(p.Hairline, bottom = false).padding(top = if (compact) Space.l else Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (quiet) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LevelIcon(state.level, p.level(state.level), Modifier.size(44.dp))
                    Spacer(Modifier.width(Space.m))
                    Text(msg.title, color = p.TextPrimary, style = CareType.label, maxLines = 1)
                }
                Text(msg.body, color = p.TextSecondary, style = CareType.labelRegular, textAlign = TextAlign.Center, maxLines = 1)
            } else {
                Text(msg.title, color = p.TextPrimary, style = if (compact) CareType.titleCompact else CareType.title, textAlign = TextAlign.Center, maxLines = 2)
                Spacer(Modifier.height(Space.xs))
                Text(msg.body, color = p.TextSecondary, style = if (compact) CareType.bodyCompact else CareType.body, textAlign = TextAlign.Center, maxLines = 2)
            }
            NowPlaying(state, Modifier.padding(top = Space.s))
        }
    }
}

/**
 * The dials retreat: a left rail (wide) or a top row (narrow) with both dials and the car signals,
 * and the [stage] takes the rest.
 */
@Composable
private fun RailLayout(state: CareState, compact: Boolean, stage: @Composable ColumnScope.() -> Unit) {
    val p = CareColor
    if (compact) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().hairline(p.Hairline, bottom = true).padding(bottom = Space.m), verticalAlignment = Alignment.CenterVertically) {
                HrDial(state, 184.dp, inRail = true)
                Spacer(Modifier.width(Space.s))
                BreakDial(state, 184.dp, inRail = true)
                Spacer(Modifier.width(Space.l))
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    SignalChips(state, vertical = true)
                    NowPlaying(state)
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = Space.l), content = stage)
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(RailWidth).fillMaxHeight().hairline(p.Hairline, end = true).padding(end = Space.l, bottom = Space.s),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                HrDial(state, 260.dp, inRail = true)
                Spacer(Modifier.height(Space.l))
                BreakDial(state, 260.dp, inRail = true)
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    SignalChips(state, vertical = true)
                    NowPlaying(state)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = Space.xxxl, top = Space.l), content = stage)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Stages
// ---------------------------------------------------------------------------------------------

/**
 * The message in the centre: title, the lead line, an optional quiet aside, an optional side block
 * (map, ring), then the buttons pinned to the bottom, confirm first.
 */
@Composable
private fun ColumnScope.Stage(
    msg: Message,
    compact: Boolean,
    side: (@Composable () -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    buttons: (@Composable RowScope.() -> Unit)? = null,
) {
    val p = CareColor
    @Composable
    fun Texts(modifier: Modifier) = Column(modifier) {
        Text(msg.title, color = p.TextPrimary, style = if (compact) CareType.titleCompact else CareType.title)
        Spacer(Modifier.height(Space.m))
        Text(msg.body, color = p.TextPrimary, style = if (compact) CareType.bodyCompact else CareType.body)
        msg.note?.let {
            Spacer(Modifier.height(Space.m))
            Text(it, color = p.TextSecondary, style = CareType.labelRegular, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (side != null && !compact) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
            Texts(Modifier.weight(1f))
            side()
        }
    } else {
        Texts(Modifier.fillMaxWidth())
    }
    if (below != null) below() else Spacer(Modifier.weight(1f))
    if (buttons != null) {
        Spacer(Modifier.height(if (compact) Space.l else Space.xl))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m), content = buttons)
    }
}

@Composable
private fun ColumnScope.LevelStage(state: CareState, msg: Message, actions: CareActions, compact: Boolean) {
    when (state.level) {
        Level.L0_NORMAL -> if (state.isPreDrive()) {
            Stage(msg, compact, below = {
                Spacer(Modifier.weight(1f))
                MoodCheck(state.mood, compact, actions::setMood)
            })
        } else {
            Stage(msg, compact, below = {
                if (state.hrHistory.size >= 2) {
                    Spacer(Modifier.height(Space.xl))
                    HeartRateChart(
                        state.hrHistory, state.nowMs, CareState.HR_WINDOW_MS, state.restingHr, state.hrLimit,
                        lineColor = CareColor.Accent, showLabels = true,
                        modifier = Modifier.fillMaxWidth().weight(1f).heightIn(max = 380.dp)
                            .clip(Radius.control).border(1.5.dp, CareColor.Hairline, Radius.control)
                            .background(CareColor.Surface).padding(Space.l),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            })
        }
        // Normally drawn by DialsLayout; kept so the when stays exhaustive.
        Level.L1_CAUTION -> Stage(msg, compact)
        // Navigation owns the route once accepted; our map was only a preview.
        Level.L2_WARNING -> if (state.guidingToRest) {
            Stage(Messages.guiding.copy(note = state.lastEvent), compact)
        } else {
            Stage(
                msg, compact,
                side = { RestMapBlock(state, Modifier.width(520.dp).height(420.dp)) },
                below = if (compact) ({
                    Spacer(Modifier.height(Space.l))
                    RestMapBlock(state, Modifier.fillMaxWidth().weight(1f))
                }) else null,
            ) {
                CareButton("휴게소 안내", ButtonKind.Primary, actions::acceptRestStop, compact)
                // Quiets the same reason for 15 minutes, so it is "later", not "no".
                CareButton("다음에", ButtonKind.Secondary, actions::dismiss, compact)
            }
        }
        // Parked: one blue action first, then the two people the driver can reach.
        Level.L3_STOPPED -> Stage(msg, compact) {
            if (state.reason == Reason.IHRN_NEW) {
                CareButton("심전도 재기", ButtonKind.Primary, actions::startEcgGuide, compact)
            } else {
                CareButton("휴식 마치기", ButtonKind.Primary, actions::dismiss, compact)
            }
            CareButton("보호자 알림", ButtonKind.Secondary, actions::notifyGuardian, compact)
            CareButton("119 전화", ButtonKind.Danger, actions::call119, compact)
        }
    }
}

/** Rest-stop preview: a framed map with the name and distance under it, like a HUD inset. */
@Composable
private fun RestMapBlock(state: CareState, modifier: Modifier) {
    val rest = state.restPreview ?: return
    val p = CareColor
    Column(modifier.clip(Radius.control).border(1.5.dp, p.Hairline, Radius.control).background(p.Surface)) {
        RestStopMap(state.carPos, rest, Modifier.fillMaxWidth().weight(1f, fill = true).heightIn(min = 120.dp, max = 380.dp))
        Row(
            Modifier.fillMaxWidth().hairline(p.Hairline, bottom = false).padding(horizontal = Space.l, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(rest.name, color = p.TextPrimary, style = CareType.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Space.m))
            Text("%.1f".format(rest.km), color = p.WarningText, style = CareType.gaugeSmall.copy(fontSize = CareType.label.fontSize), maxLines = 1)
            Text(" km", color = p.TextSecondary, style = CareType.chip, maxLines = 1)
        }
    }
}

/** One tap before departure; parked only, so it never asks for input while driving. */
@Composable
private fun MoodCheck(selected: Mood?, compact: Boolean, onPick: (Mood) -> Unit) {
    val p = CareColor
    Text("지금 기분은 어떠세요?", color = p.TextPrimary, style = CareType.label)
    Spacer(Modifier.height(Space.m))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) Space.s else Space.m)) {
        Mood.entries.forEach { m ->
            val on = m == selected
            Button(
                onClick = { onPick(m) },
                modifier = Modifier.weight(1f).height(ChoiceHeight)
                    .then(if (on) Modifier else Modifier.border(2.dp, p.ControlEdge, Radius.control)),
                shape = Radius.control,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (on) p.Accent else p.SurfaceHigh,
                    contentColor = if (on) p.OnAccent else p.TextPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(Messages.moodLabel(m), style = CareType.label, maxLines = 1, softWrap = false)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Questions, no pulse, contacting (docs/decisions/2026-11-15-응급-대화-졸음-설계.md, section 7)
// ---------------------------------------------------------------------------------------------

/** Flows A, C, D: the question in the centre and two big answers, the action first. */
@Composable
private fun ColumnScope.CheckinStage(state: CareState, checkin: Checkin, actions: CareActions, compact: Boolean) {
    val msg = Messages.forCheckin(checkin)
    if (checkin.helpRequested) {
        // Help is on its way once the car stops: no more answers, just the hazard-light state.
        Stage(msg, compact, below = {
            Spacer(Modifier.height(Space.xl))
            HazardRow(state.hazardOn)
            Spacer(Modifier.weight(1f))
        })
        return
    }
    Stage(msg, compact) {
        Messages.buttons(checkin.ask).forEachIndexed { i, label ->
            CareButton(label, if (i == 0) ButtonKind.Primary else ButtonKind.Secondary, { actions.answer(checkin.ask, i == 0) }, compact, tall = true)
        }
    }
}

/**
 * Flow B: watch on the wrist, no pulse. A big ring counting down to the automatic call, the
 * question, the hazard-light state, and one very large [괜찮아요]. Nothing else competes.
 */
@Composable
private fun NoPulseScreen(state: CareState, checkin: Checkin, actions: CareActions, compact: Boolean) {
    val msg = Messages.forCheckin(checkin)
    val total = checkin.totalSeconds ?: 180
    val p = CareColor
    @Composable
    fun Words() {
        // "괜찮으세요?" is the whole question, so it gets a size step up; the longer pre-alert line does not.
        val big = !compact && msg.title.length <= 8
        Text(msg.title, color = p.TextPrimary, style = if (compact) CareType.titleCompact else if (big) CareType.title.copy(fontSize = CareType.title.fontSize * 1.2f, lineHeight = CareType.title.lineHeight * 1.2f) else CareType.title)
        Spacer(Modifier.height(Space.s))
        Text(msg.body, color = p.TextPrimary, style = if (compact) CareType.bodyCompact else CareType.body)
        msg.note?.let {
            Spacer(Modifier.height(Space.s))
            Text(it, color = p.TextSecondary, style = CareType.labelRegular, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    val ok: @Composable (Modifier) -> Unit = { mod ->
        Button(
            onClick = { actions.answer(Ask.NO_PULSE, true) },
            modifier = mod.height(if (compact) 160.dp else 176.dp),
            shape = Radius.control,
            colors = ButtonDefaults.buttonColors(containerColor = p.Accent, contentColor = p.OnAccent),
        ) { Text(Messages.buttons(Ask.NO_PULSE).first(), style = CareType.title.copy(fontSize = CareType.titleCompact.fontSize), maxLines = 1) }
    }
    if (compact) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CountdownRing(checkin.secondsLeft, total, 340.dp, "자동 연락까지")
                Spacer(Modifier.width(Space.l))
                Column(Modifier.weight(1f)) { Words() }
            }
            Spacer(Modifier.height(Space.l))
            HazardRow(state.hazardOn)
            Spacer(Modifier.weight(1f))
            ok(Modifier.fillMaxWidth())
        }
    } else {
        RailLayout(state, compact = false) {
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                FitDial(560.dp) { CountdownRing(checkin.secondsLeft, total, it, "자동 연락까지") }
                Spacer(Modifier.width(Space.xxxl))
                Column(Modifier.weight(1f)) {
                    Words()
                    Spacer(Modifier.height(Space.xl))
                    HazardRow(state.hazardOn)
                }
            }
            Spacer(Modifier.height(Space.l))
            ok(Modifier.fillMaxWidth())
        }
    }
}

/** The app cannot switch the hazard lights on; it asks, then confirms what the car reports. */
@Composable
private fun HazardRow(on: Boolean) {
    val p = CareColor
    Row(
        Modifier.clip(Radius.small).border(1.5.dp, if (on) p.ControlEdge else p.Warning, Radius.small)
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) CheckDot(true, Modifier.size(40.dp)) else HazardMark(p.Warning, Modifier.size(44.dp))
        Spacer(Modifier.width(Space.m))
        Text(if (on) Messages.HAZARD_ON else "비상등을 켜 주세요", color = p.TextPrimary, style = CareType.label, maxLines = 1)
    }
}

/** Who the phone is reaching, as a checklist, and one [취소]. */
@Composable
private fun ColumnScope.ContactingStage(state: CareState, c: Contacting, actions: CareActions, compact: Boolean) {
    Stage(Messages.contacting(c), compact, below = {
        Spacer(Modifier.height(Space.xl))
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Messages.contactLines(c).forEach { ChecklistRow(it, true) }
            Spacer(Modifier.height(Space.xs))
            HazardRow(state.hazardOn)
        }
        Spacer(Modifier.weight(1f))
    }) {
        CareButton("취소", ButtonKind.Secondary, actions::cancelContact, compact, fill = compact)
    }
}

@Composable
private fun ChecklistRow(text: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CheckDot(done, Modifier.size(48.dp))
        Spacer(Modifier.width(Space.l))
        Text(text, color = if (done) CareColor.TextPrimary else CareColor.TextTertiary, style = CareType.label, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Buttons and small helpers
// ---------------------------------------------------------------------------------------------

/** Primary is the one blue action; Danger is only for calling 119. */
private enum class ButtonKind { Primary, Secondary, Danger }

/**
 * Wide: buttons keep a natural width, left aligned under the message (design D). Narrow: they share
 * the row. [tall] for question answers, which are the whole job of the screen.
 */
@Composable
private fun RowScope.CareButton(label: String, kind: ButtonKind, onClick: () -> Unit, compact: Boolean, tall: Boolean = false, fill: Boolean = compact) {
    val p = CareColor
    val width = when {
        fill -> Modifier.weight(1f)
        kind == ButtonKind.Primary -> Modifier.widthIn(min = 440.dp)
        else -> Modifier.widthIn(min = 320.dp)
    }
    Button(
        onClick = onClick,
        modifier = width.height(if (tall) ButtonHeight + 16.dp else ButtonHeight)
            .then(if (kind == ButtonKind.Secondary) Modifier.border(2.dp, p.ControlEdge, Radius.control) else Modifier),
        shape = Radius.control,
        colors = when (kind) {
            ButtonKind.Primary -> ButtonDefaults.buttonColors(containerColor = p.Accent, contentColor = p.OnAccent)
            ButtonKind.Secondary -> ButtonDefaults.buttonColors(containerColor = p.SurfaceHigh, contentColor = p.TextPrimary)
            ButtonKind.Danger -> ButtonDefaults.buttonColors(containerColor = p.DangerContainer, contentColor = Color.White)
        },
        contentPadding = PaddingValues(horizontal = Space.l),
    ) {
        Text(label, style = CareType.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** One hairline rule on a side of the element, like the cluster's tell-tale strip. */
private fun Modifier.hairline(color: Color, bottom: Boolean? = null, end: Boolean = false) = drawBehind {
    val w = 1.5.dp.toPx()
    when {
        end -> drawLine(color, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
        bottom == true -> drawLine(color, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
        bottom == false -> drawLine(color, Offset(0f, w / 2), Offset(size.width, w / 2), w)
    }
}
