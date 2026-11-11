package ai.caredrive.car.ui

import ai.caredrive.car.engine.Grade
import ai.caredrive.car.engine.Mood
import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.Reason
import ai.caredrive.car.model.DataState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0B1220)
private val Panel = Color(0xFF131C2E)
private val Chip = Color(0xFF1E293B)
private val TextMain = Color(0xFFF1F5F9)
private val TextSub = Color(0xFF94A3B8)
/** Old or missing watch data is dimmed, never shown as if it were current. */
private const val StaleAlpha = 0.35f

/** Break Korean lines between words (eojeol), not in the middle of a word. */
private val Phrase = TextStyle(fontFamily = AstaSans, lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase))

/**
 * Text sizes (see docs/research/2026-11-11-차량-UI-레퍼런스.md): at least 30sp everywhere and
 * at least 38sp for anything visible while driving (ISO 15008 legibility at the emulator's 1 dp = 1 px).
 */
private val MinSp = 30.sp
private val DrivingSp = 38.sp

/** Below this width only the message card and a one-line status are shown (Pleos split window). */
private val CompactBelow = 1200.dp

/** Width used when the demo forces the narrow layout: about half of the Pleos app area. */
private val DemoCompactWidth = 820.dp

private fun levelColor(level: Level) = when (level) {
    Level.L0_NORMAL -> Color(0xFF14B8A6)
    Level.L1_CAUTION -> Color(0xFFEAB308)
    Level.L2_WARNING -> Color(0xFFF97316)
    Level.L3_STOPPED -> Color(0xFFF43F5E)
}

private fun gradeColor(g: Grade) = when (g) {
    Grade.GOOD -> Color(0xFF14B8A6)
    Grade.FAIR -> Color(0xFFEAB308)
    Grade.LOW -> Color(0xFFF97316)
}

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

/** Seconds a caution stays as a full card before it shrinks to one line (no buttons at L1). */
private const val CautionShrinkMs = 8_000L
private val Warn = Color(0xFFFB923C)

@Composable
fun CareScreen(state: CareState, actions: CareActions, forceCompact: Boolean = false, demoBar: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().background(Bg).padding(40.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (forceCompact || maxWidth < CompactBelow) {
                Column(Modifier.fillMaxHeight().then(if (forceCompact) Modifier.width(DemoCompactWidth) else Modifier.fillMaxWidth())) {
                    CompactStatus(state)
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) { MainCard(state, actions, compact = true) }
                }
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    StatusPanel(state, Modifier.weight(0.34f).fillMaxHeight())
                    Box(Modifier.weight(0.66f).fillMaxHeight()) { MainCard(state, actions, compact = false) }
                }
            }
        }
        demoBar()
    }
}

@Composable
private fun LevelBadge(level: Level, iconSize: Int, fontSize: TextUnit) {
    val color = levelColor(level)
    Row(verticalAlignment = Alignment.CenterVertically) {
        LevelIcon(level, color, Modifier.size(iconSize.dp))
        Spacer(Modifier.width(16.dp))
        Text(Messages.levelLabel(level), color = color, fontSize = fontSize, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun HeartRate(bpm: Int?, numberSp: TextUnit, iconWidth: Int, fresh: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (fresh) 1f else StaleAlpha)) {
        Text(bpm?.toString() ?: "--", color = TextMain, fontSize = numberSp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        HeartbeatIcon(Color(0xFFF43F5E), Modifier.width(iconWidth.dp).height((iconWidth * 0.55f).dp))
    }
}

/** Narrow window: level, heart rate and next break on one card; details only in the wide layout. */
@Composable
private fun CompactStatus(state: CareState) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel).padding(horizontal = 32.dp, vertical = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LevelBadge(state.level, 52, 44.sp)
            Spacer(Modifier.weight(1f))
            HeartRate(state.heartRate, 72.sp, 72, state.data == DataState.LIVE)
        }
        val line = listOfNotNull(Messages.breakLine(state), state.glucose?.let { "혈당 $it" }).joinToString(" · ")
        if (line.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(line, color = TextMain, fontSize = DrivingSp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Messages.dataLine(state)?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Warn, fontSize = DrivingSp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/**
 * Wide window. While driving it shows only level, heart rate, glucose and next break (one glance);
 * chart, stress, condition chips and the event log appear when parked.
 */
@Composable
private fun StatusPanel(state: CareState, modifier: Modifier) {
    val driving = !state.parked
    Column(
        modifier.clip(RoundedCornerShape(32.dp)).background(Panel).padding(44.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("케어드라이브", color = TextSub, fontSize = MinSp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(36.dp))
            LevelBadge(state.level, 60, 52.sp)
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.alpha(if (state.data == DataState.LIVE) 1f else StaleAlpha)) {
                Text(state.heartRate?.toString() ?: "--", color = TextMain, fontSize = 120.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.padding(bottom = 24.dp)) {
                    HeartbeatIcon(Color(0xFFF43F5E), Modifier.width(96.dp).height(52.dp))
                    Text("bpm", color = TextSub, fontSize = DrivingSp)
                }
            }
            Messages.dataLine(state)?.let {
                Text(it, color = Warn, fontSize = DrivingSp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            state.glucose?.let {
                Text("혈당 $it", color = if (it < 70) Color(0xFFFB923C) else TextMain, fontSize = DrivingSp, fontWeight = FontWeight.Bold)
            }
            if (!driving) {
                state.stress?.let {
                    val high = state.stressLimit?.let { limit -> it >= limit } == true
                    Text("스트레스 $it", color = if (high) Color(0xFFFB923C) else TextSub, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
                state.readiness?.let { r ->
                    Spacer(Modifier.height(28.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("오늘 컨디션", color = TextSub, fontSize = 32.sp)
                        Spacer(Modifier.width(14.dp))
                        Text(Messages.gradeLabel(r.grade), color = gradeColor(r.grade), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    r.factors.take(3).forEach { f ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            f.label, color = TextMain, fontSize = MinSp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Chip).padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        Column {
            Messages.breakLine(state)?.let {
                Text(it, color = TextMain, fontSize = if (driving) DrivingSp else 34.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
            }
            Text(
                (if (state.connected) "폰 연결됨" else "폰 연결 안 됨") + (if (driving) "" else " · 정차"),
                color = if (state.connected) Color(0xFF5EEAD4) else TextSub, fontSize = if (driving) DrivingSp else MinSp, maxLines = 1,
            )
            if (!driving) state.lastEvent?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = TextSub, fontSize = MinSp, maxLines = 2, overflow = TextOverflow.Ellipsis, style = Phrase)
            }
        }
    }
}

@Composable
private fun MainCard(state: CareState, actions: CareActions, compact: Boolean) {
    val msg = Messages.forState(state)
    val color = levelColor(state.level)
    state.emergencyCountdown?.let { left ->
        // Confirm first, cancel second (Pleos system popup order); at most two buttons.
        MessageCard(Level.L3_STOPPED, Messages.emergency(left), compact) {
            BigButton("지금 전화", Color(0xFF7F1D1D), Modifier.weight(1f), actions::call119Now)
            BigButton("취소", Color(0xFF334155), Modifier.weight(1f), actions::cancel119)
        }
        return
    }
    when (state.level) {
        Level.L0_NORMAL -> PlainView(msg, if (state.parked && state.drivingMin == 0) state.readiness?.grade?.let(::gradeColor) else null, compact, !state.parked) {
            if (state.parked && state.drivingMin == 0) {
                MoodCheck(state.mood, compact, actions::setMood)
            } else if (state.parked && state.hrHistory.size >= 2) {
                Spacer(Modifier.height(40.dp))
                Text("심박 추이", color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                HeartRateChart(
                    state.hrHistory, state.nowMs, CareState.HR_WINDOW_MS, state.restingHr, state.hrLimit,
                    lineColor = Color(0xFF14B8A6), showLabels = true,
                    modifier = Modifier.fillMaxWidth().height(if (compact) 280.dp else 340.dp).clip(RoundedCornerShape(24.dp)).background(Panel).padding(28.dp),
                )
            }
        }
        Level.L1_CAUTION -> {
            // Caution is information only: full card for a few seconds, then one quiet line.
            var shrunk by remember(state.reason) { mutableStateOf(false) }
            LaunchedEffect(state.reason) { delay(CautionShrinkMs); shrunk = true }
            if (shrunk) CautionLine(msg, compact) else MessageCard(state.level, msg, compact) {}
        }
        // Navigation owns the route once accepted; our map was only a preview.
        Level.L2_WARNING -> if (state.guidingToRest) {
            MessageCard(state.level, Messages.guiding.copy(note = state.lastEvent), compact) {}
        } else {
            MessageCard(state.level, msg, compact, map = { RestMapBlock(state, compact) }) {
                BigButton("휴게소 안내", color, Modifier.weight(1f), actions::acceptRestStop)
                BigButton("괜찮아", Color(0xFF334155), Modifier.weight(1f), actions::dismiss)
            }
        }
        Level.L3_STOPPED -> MessageCard(state.level, msg, compact) {
            if (state.reason == Reason.IHRN_NEW) {
                BigButton("심전도 안내", color, Modifier.weight(1f), actions::startEcgGuide)
            } else {
                BigButton("휴식 끝", color, Modifier.weight(1f), actions::dismiss)
            }
            BigButton("보호자 알림", Color(0xFF334155), Modifier.weight(1f), actions::notifyGuardian)
            BigButton("119 전화", Color(0xFF7F1D1D), Modifier.weight(1f), actions::call119)
        }
    }
}

/** Shrunk caution: level shape, title and the short advice, no card and no buttons. */
@Composable
private fun CautionLine(msg: Message, compact: Boolean) {
    Column(Modifier.fillMaxSize().padding(start = if (compact) 8.dp else 24.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LevelIcon(Level.L1_CAUTION, levelColor(Level.L1_CAUTION), Modifier.size(56.dp))
            Spacer(Modifier.width(20.dp))
            Text(msg.title, color = TextMain, fontSize = if (compact) 48.sp else 56.sp, fontWeight = FontWeight.Bold, lineHeight = if (compact) 60.sp else 68.sp, style = Phrase)
        }
        Spacer(Modifier.height(16.dp))
        Text(msg.body, color = TextSub, fontSize = DrivingSp, lineHeight = 50.sp, style = Phrase, modifier = Modifier.padding(start = 76.dp))
    }
}

@Composable
private fun PlainView(msg: Message, accent: Color?, compact: Boolean, driving: Boolean, extra: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(start = if (compact) 8.dp else 24.dp), verticalArrangement = Arrangement.Center) {
        if (accent != null) {
            Box(Modifier.width(120.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(accent))
            Spacer(Modifier.height(32.dp))
        }
        Text(msg.title, color = TextMain, fontSize = if (compact) 60.sp else 72.sp, fontWeight = FontWeight.Bold, lineHeight = if (compact) 74.sp else 88.sp, style = Phrase)
        Spacer(Modifier.height(20.dp))
        Text(msg.body, color = TextSub, fontSize = 44.sp, lineHeight = 56.sp, style = Phrase)
        msg.note?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = TextSub, fontSize = if (driving) DrivingSp else 34.sp, lineHeight = 46.sp, style = Phrase)
        }
        extra()
    }
}

/** One tap before departure; parked only, so it never asks for input while driving. */
@Composable
private fun MoodCheck(selected: Mood?, compact: Boolean, onPick: (Mood) -> Unit) {
    Spacer(Modifier.height(48.dp))
    Text("지금 기분은 어떠세요?", color = TextMain, fontSize = 36.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)) {
        Mood.entries.forEach { m ->
            val on = m == selected
            Button(
                onClick = { onPick(m) },
                modifier = Modifier.weight(1f).height(108.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (on) Color(0xFF0F766E) else Chip),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(Messages.moodLabel(m), fontSize = if (compact) 32.sp else 34.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else TextMain, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun RestMapBlock(state: CareState, compact: Boolean) {
    val rest = state.restPreview ?: return
    Spacer(Modifier.height(24.dp))
    Text(
        "${rest.name} · %.1fkm".format(rest.km),
        color = TextMain, fontSize = DrivingSp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(12.dp))
    RestStopMap(state.carPos, rest, Modifier.fillMaxWidth().height(if (compact) 220.dp else 280.dp).clip(RoundedCornerShape(24.dp)))
}

/** Level shown by shape and word as well as colour, so the card stands on its own in a narrow window. */
@Composable
private fun MessageCard(
    level: Level,
    msg: Message,
    compact: Boolean,
    map: @Composable () -> Unit = {},
    buttons: @Composable RowScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(32.dp)).background(Panel).padding(if (compact) 40.dp else 64.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        LevelBadge(level, if (compact) 52 else 60, if (compact) 40.sp else 44.sp)
        Spacer(Modifier.height(32.dp))
        Text(msg.title, color = TextMain, fontSize = if (compact) 64.sp else 80.sp, fontWeight = FontWeight.ExtraBold, lineHeight = if (compact) 78.sp else 96.sp, style = Phrase)
        Spacer(Modifier.height(20.dp))
        Text(msg.body, color = TextMain, fontSize = if (compact) 44.sp else 52.sp, lineHeight = if (compact) 56.sp else 64.sp, style = Phrase)
        msg.note?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = TextSub, fontSize = DrivingSp, lineHeight = 48.sp, style = Phrase)
        }
        map()
        Spacer(Modifier.height(36.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 32.dp), content = buttons)
    }
}

@Composable
private fun BigButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(130.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(label, fontSize = DrivingSp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}
