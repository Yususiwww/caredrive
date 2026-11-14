package ai.caredrive.car.ui

import ai.caredrive.car.engine.Mood
import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.DataState
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.Reason
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Below this width only the message card and a one-line status are shown (Pleos split window). */
private val CompactBelow = 1200.dp

/** Width used when the demo forces the narrow layout: about half of the Pleos app area. */
private val DemoCompactWidth = 820.dp

/** Seconds a caution stays as a full card before it shrinks to one line (no buttons at L1). */
private const val CautionShrinkMs = 8_000L

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
}

/**
 * One screen, one job. The status side shows level and heart rate (the one big number); the main
 * side shows the one message for this moment. Anything the main side already says is not repeated.
 */
@Composable
fun CareScreen(state: CareState, actions: CareActions, forceCompact: Boolean = false, demoBar: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().careBackdrop(CareColor).padding(Space.xl)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (forceCompact || maxWidth < CompactBelow) {
                Column(Modifier.fillMaxHeight().then(if (forceCompact) Modifier.width(DemoCompactWidth) else Modifier.fillMaxWidth())) {
                    CompactStatus(state)
                    Spacer(Modifier.height(Space.l))
                    Box(Modifier.weight(1f).fillMaxWidth()) { MainCard(state, actions, compact = true) }
                }
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                    StatusPanel(state, Modifier.weight(0.34f).fillMaxHeight())
                    Box(Modifier.weight(0.66f).fillMaxHeight()) { MainCard(state, actions, compact = false) }
                }
            }
        }
        demoBar()
    }
}

private fun CareState.isPreDrive() = parked && drivingMin == 0

/** The main side already says the break plan in these two cases, so the status side stays quiet. */
private fun CareState.showBreakInStatus() = !(level == Level.L0_NORMAL && (isPreDrive() || !parked))

@Composable
private fun LevelBadge(level: Level, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LevelIcon(level, CareColor.level(level), Modifier.size(if (compact) 48.dp else 56.dp))
        Spacer(Modifier.width(Space.m))
        Text(Messages.levelLabel(level), color = CareColor.levelLabel(level), style = CareType.label.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1)
    }
}

/** Narrow window: level and heart rate on one row, then at most one status line. */
@Composable
private fun CompactStatus(state: CareState) {
    val fresh = state.data == DataState.LIVE
    Column(Modifier.fillMaxWidth().clip(Radius.card).background(CareColor.Surface).padding(horizontal = Space.xl, vertical = Space.l)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LevelBadge(state.level, compact = true)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (fresh) 1f else StaleAlpha)) {
                Text(state.heartRate?.toString() ?: "--", color = CareColor.TextPrimary, style = CareType.numberCompact, maxLines = 1)
                Spacer(Modifier.width(Space.s))
                HeartbeatIcon(CareColor.TextTertiary, Modifier.width(64.dp).height(36.dp))
            }
        }
        val line = listOfNotNull(
            Messages.breakLine(state).takeIf { state.showBreakInStatus() },
            state.glucose?.let { "혈당 $it" },
        ).joinToString(" · ")
        if (line.isNotEmpty()) {
            Spacer(Modifier.height(Space.xs))
            Text(line, color = CareColor.TextPrimary, style = CareType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Messages.dataLine(state)?.let {
            Spacer(Modifier.height(Space.xs))
            Text(it, color = CareColor.CautionText, style = CareType.label, maxLines = 1)
        }
    }
}

/**
 * Wide window. Heart rate is the one big number. While driving only level, heart rate, glucose and
 * the next break are here (one glance); stress and the last event appear when parked.
 * Today's condition lives on the main side before departure, so it is not repeated here.
 */
@Composable
private fun StatusPanel(state: CareState, modifier: Modifier) {
    val driving = !state.parked
    val fresh = state.data == DataState.LIVE
    Column(
        modifier.clip(Radius.card).background(CareColor.Surface).padding(Space.xxl),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            LevelBadge(state.level, compact = false)
            Spacer(Modifier.height(Space.xl))
            Column(Modifier.alpha(if (fresh) 1f else StaleAlpha)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(state.heartRate?.toString() ?: "--", color = CareColor.TextPrimary, style = CareType.number, maxLines = 1)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.padding(bottom = Space.l)) {
                        HeartbeatIcon(CareColor.TextTertiary, Modifier.width(88.dp).height(48.dp))
                        Text("bpm", color = CareColor.TextSecondary, style = CareType.labelRegular)
                    }
                }
                state.glucose?.let {
                    Spacer(Modifier.height(Space.xs))
                    Text("혈당 $it", color = if (it < 70) CareColor.WarningText else CareColor.TextPrimary, style = CareType.label)
                }
                if (!driving) state.stress?.let {
                    val high = state.stressLimit?.let { limit -> it >= limit } == true
                    Spacer(Modifier.height(Space.xs))
                    Text("스트레스 $it", color = if (high) CareColor.WarningText else CareColor.TextSecondary, style = CareType.labelRegular)
                }
            }
            Messages.dataLine(state)?.let {
                Spacer(Modifier.height(Space.m))
                Text(it, color = CareColor.CautionText, style = CareType.label, maxLines = 1)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (state.showBreakInStatus()) Messages.breakLine(state)?.let {
                Text(it, color = CareColor.TextPrimary, style = CareType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Only say something when it is wrong: a working connection needs no line.
            if (!state.connected) Text("폰 연결 안 됨", color = CareColor.TextTertiary, style = CareType.labelRegular, maxLines = 1)
            if (!driving) state.lastEvent?.let {
                Text(it, color = CareColor.TextTertiary, style = CareType.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MainCard(state: CareState, actions: CareActions, compact: Boolean) {
    val msg = Messages.forState(state)
    state.emergencyCountdown?.let { left ->
        // Confirm first, cancel second (Pleos system popup order); at most two buttons.
        MessageCard(Level.L3_STOPPED, Messages.emergency(left), compact) {
            CareButton("지금 전화", ButtonKind.Danger, actions::call119Now)
            CareButton("취소", ButtonKind.Secondary, actions::cancel119)
        }
        return
    }
    when (state.level) {
        Level.L0_NORMAL -> PlainView(msg, compact) {
            if (state.isPreDrive()) {
                MoodCheck(state.mood, compact, actions::setMood)
            } else if (state.parked && state.hrHistory.size >= 2) {
                Spacer(Modifier.height(Space.xxl))
                Text("심박 추이", color = CareColor.TextPrimary, style = CareType.label)
                Spacer(Modifier.height(Space.m))
                HeartRateChart(
                    state.hrHistory, state.nowMs, CareState.HR_WINDOW_MS, state.restingHr, state.hrLimit,
                    lineColor = CareColor.Accent, showLabels = true,
                    modifier = Modifier.fillMaxWidth().height(if (compact) 280.dp else 340.dp)
                        .clip(Radius.card).background(CareColor.Surface).padding(Space.xl),
                )
            }
        }
        Level.L1_CAUTION -> {
            // Caution is information only: full card for a few seconds, then one quiet line.
            var shrunk by remember(state.reason) { mutableStateOf(false) }
            LaunchedEffect(state.reason) { delay(CautionShrinkMs); shrunk = true }
            if (shrunk) CautionLine(msg, compact) else MessageCard(state.level, msg, compact)
        }
        // Navigation owns the route once accepted; our map was only a preview.
        Level.L2_WARNING -> if (state.guidingToRest) {
            MessageCard(state.level, Messages.guiding.copy(note = state.lastEvent), compact)
        } else {
            MessageCard(state.level, msg, compact, map = { RestMapBlock(state, compact) }) {
                CareButton("휴게소 안내", ButtonKind.Primary, actions::acceptRestStop)
                // Quiets the same reason for 15 minutes, so it is "later", not "no".
                CareButton("다음에", ButtonKind.Secondary, actions::dismiss)
            }
        }
        // Parked: one blue action first, then the two people the driver can reach.
        Level.L3_STOPPED -> MessageCard(state.level, msg, compact) {
            if (state.reason == Reason.IHRN_NEW) {
                CareButton("심전도 재기", ButtonKind.Primary, actions::startEcgGuide)
            } else {
                CareButton("휴식 마치기", ButtonKind.Primary, actions::dismiss)
            }
            CareButton("보호자 알림", ButtonKind.Secondary, actions::notifyGuardian)
            CareButton("119 전화", ButtonKind.Danger, actions::call119)
        }
    }
}

/** Shrunk caution: level shape, title and the short advice, no card and no buttons. */
@Composable
private fun CautionLine(msg: Message, compact: Boolean) {
    val icon = 56.dp
    Column(Modifier.fillMaxSize().padding(start = if (compact) Space.xs else Space.l), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LevelIcon(Level.L1_CAUTION, CareColor.Caution, Modifier.size(icon))
            Spacer(Modifier.width(Space.l))
            Text(msg.title, color = CareColor.TextPrimary, style = (if (compact) CareType.bodyCompact else CareType.body).copy(fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.height(Space.s))
        Text(msg.body, color = CareColor.TextSecondary, style = CareType.labelRegular, modifier = Modifier.padding(start = icon + Space.l))
    }
}

/** Calm states: no card, just the sentence on the background. */
@Composable
private fun PlainView(msg: Message, compact: Boolean, extra: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(start = if (compact) Space.xs else Space.l), verticalArrangement = Arrangement.Center) {
        Text(msg.title, color = CareColor.TextPrimary, style = if (compact) CareType.titleCompact else CareType.title)
        Spacer(Modifier.height(Space.m))
        Text(msg.body, color = CareColor.TextSecondary, style = if (compact) CareType.bodyCompact else CareType.body)
        msg.note?.let {
            Spacer(Modifier.height(Space.m))
            Text(it, color = CareColor.TextTertiary, style = CareType.labelRegular)
        }
        extra()
    }
}

/** One tap before departure; parked only, so it never asks for input while driving. */
@Composable
private fun MoodCheck(selected: Mood?, compact: Boolean, onPick: (Mood) -> Unit) {
    Spacer(Modifier.height(Space.xxl))
    Text("지금 기분은 어떠세요?", color = CareColor.TextPrimary, style = CareType.label)
    Spacer(Modifier.height(Space.m))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) Space.s else Space.m)) {
        Mood.entries.forEach { m ->
            val on = m == selected
            Button(
                onClick = { onPick(m) },
                modifier = Modifier.weight(1f).height(ChoiceHeight),
                shape = Radius.control,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (on) CareColor.Accent else CareColor.Surface,
                    contentColor = if (on) CareColor.OnAccent else CareColor.TextPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(Messages.moodLabel(m), style = CareType.label, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun RestMapBlock(state: CareState, compact: Boolean) {
    val rest = state.restPreview ?: return
    Spacer(Modifier.height(Space.l))
    Text(
        "${rest.name} · %.1fkm".format(rest.km),
        color = CareColor.TextPrimary, style = CareType.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(Space.s))
    RestStopMap(
        state.carPos, rest,
        // Light map tiles need an edge on a white card.
        Modifier.fillMaxWidth().height(if (compact) 200.dp else 280.dp).clip(Radius.control).border(1.dp, CareColor.Hairline, Radius.control),
    )
}

/**
 * Alert card. Level shown by shape and word as well as colour, so the card stands on its own in a
 * narrow window. Three type levels: title, body, label (level word and note share the label size).
 */
@Composable
private fun MessageCard(
    level: Level,
    msg: Message,
    compact: Boolean,
    map: @Composable () -> Unit = {},
    buttons: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().clip(Radius.card).background(CareColor.Surface).padding(if (compact) Space.xl else Space.xxxl),
        verticalArrangement = Arrangement.Center,
    ) {
        LevelBadge(level, compact)
        Spacer(Modifier.height(Space.l))
        Text(msg.title, color = CareColor.TextPrimary, style = if (compact) CareType.titleCompact else CareType.title)
        Spacer(Modifier.height(Space.m))
        Text(msg.body, color = CareColor.TextSecondary, style = if (compact) CareType.bodyCompact else CareType.body)
        msg.note?.let {
            Spacer(Modifier.height(Space.m))
            Text(it, color = CareColor.TextTertiary, style = CareType.labelRegular)
        }
        map()
        if (buttons != null) {
            Spacer(Modifier.height(if (compact) Space.xl else Space.xxl))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m), content = buttons)
        }
    }
}

/** Primary is the one blue action; Danger is only for calling 119. */
private enum class ButtonKind { Primary, Secondary, Danger }

@Composable
private fun RowScope.CareButton(label: String, kind: ButtonKind, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f).height(ButtonHeight),
        shape = Radius.control,
        colors = with(CareColor) {
            when (kind) {
                ButtonKind.Primary -> ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
                ButtonKind.Secondary -> ButtonDefaults.buttonColors(containerColor = SurfaceHigh, contentColor = TextPrimary)
                ButtonKind.Danger -> ButtonDefaults.buttonColors(containerColor = DangerContainer, contentColor = Color.White)
            }
        },
        contentPadding = PaddingValues(horizontal = Space.xs),
    ) {
        Text(label, style = CareType.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}
