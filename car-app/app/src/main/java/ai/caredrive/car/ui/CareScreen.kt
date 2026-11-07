package ai.caredrive.car.ui

import ai.caredrive.car.engine.Grade
import ai.caredrive.car.engine.Mood
import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.Reason
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0B1220)
private val Panel = Color(0xFF131C2E)
private val Chip = Color(0xFF1E293B)
private val TextMain = Color(0xFFF1F5F9)
private val TextSub = Color(0xFF94A3B8)

/** Break Korean lines between words (eojeol), not in the middle of a word. */
private val Phrase = TextStyle(lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase))

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

private fun levelLabel(level: Level) = when (level) {
    Level.L0_NORMAL -> "정상"
    Level.L1_CAUTION -> "주의"
    Level.L2_WARNING -> "경고"
    Level.L3_STOPPED -> "정차 후 조치"
}

/** Driver-facing actions the screen can request. */
interface CareActions {
    fun acceptRestStop()
    fun dismiss()
    fun startEcgGuide()
    fun notifyGuardian()
    fun call119()
    fun setMood(mood: Mood)
}

@Composable
fun CareScreen(state: CareState, actions: CareActions, demoBar: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().background(Bg).padding(48.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            StatusPanel(state, Modifier.weight(0.34f).fillMaxHeight())
            Box(Modifier.weight(0.66f).fillMaxHeight()) { MainCard(state, actions) }
        }
        demoBar()
    }
}

@Composable
private fun StatusPanel(state: CareState, modifier: Modifier) {
    val color = levelColor(state.level)
    Column(
        modifier.clip(RoundedCornerShape(32.dp)).background(Panel).padding(44.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("케어드라이브", color = TextSub, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(36.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(18.dp))
                Text(levelLabel(state.level), color = color, fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(state.heartRate?.toString() ?: "--", color = TextMain, fontSize = 120.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.padding(bottom = 24.dp)) {
                    HeartbeatIcon(Color(0xFFF43F5E), Modifier.width(96.dp).height(52.dp))
                    Text("bpm", color = TextSub, fontSize = 36.sp)
                }
            }
            if (!state.parked && state.hrHistory.size >= 2) {
                HeartRateChart(
                    state.hrHistory, state.nowMs, CareState.HR_WINDOW_MS, state.restingHr, state.hrLimit,
                    lineColor = color, modifier = Modifier.fillMaxWidth().height(110.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
            state.glucose?.let {
                Text("혈당 $it mg/dL", color = if (it < 70) Color(0xFFFB923C) else TextSub, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            state.stress?.let {
                val high = state.stressLimit?.let { limit -> it >= limit } == true
                Text("스트레스 $it", color = if (high) Color(0xFFFB923C) else TextSub, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            state.readiness?.let { r ->
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("오늘 컨디션", color = TextSub, fontSize = 28.sp)
                    Spacer(Modifier.width(14.dp))
                    Text(Messages.gradeLabel(r.grade), color = gradeColor(r.grade), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                }
                r.factors.take(3).forEach { f ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        f.label, color = TextMain, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Chip).padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Column {
            Messages.breakLine(state)?.let {
                Text(it, color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
            }
            Text(
                (if (state.connected) "폰 연결됨" else "폰 연결 안 됨") + (if (state.parked) " · 정차" else " · 주행 중"),
                color = if (state.connected) Color(0xFF5EEAD4) else TextSub, fontSize = 26.sp,
            )
            state.lastEvent?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = TextSub, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, style = Phrase)
            }
        }
    }
}

@Composable
private fun MainCard(state: CareState, actions: CareActions) {
    val msg = Messages.forState(state)
    val color = levelColor(state.level)
    when (state.level) {
        Level.L0_NORMAL -> PlainView(msg, if (state.parked && state.drivingMin == 0) state.readiness?.grade?.let(::gradeColor) else null) {
            if (state.parked && state.drivingMin == 0) {
                MoodCheck(state.mood, actions::setMood)
            } else if (state.parked && state.hrHistory.size >= 2) {
                Spacer(Modifier.height(48.dp))
                Text("심박 추이", color = TextMain, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                HeartRateChart(
                    state.hrHistory, state.nowMs, CareState.HR_WINDOW_MS, state.restingHr, state.hrLimit,
                    lineColor = Color(0xFF14B8A6), showLabels = true,
                    modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(24.dp)).background(Panel).padding(28.dp),
                )
            }
        }
        Level.L1_CAUTION -> MessageCard(color, msg) {}
        Level.L2_WARNING -> if (state.guidingToRest) {
            MessageCard(color, Message("휴게소로 안내 중이에요", "도착하면 잠시 쉬어 가세요.", state.lastEvent), map = { RestMapBlock(state) }) {}
        } else {
            MessageCard(color, msg, map = { RestMapBlock(state) }) {
                BigButton("휴게소 안내", color, Modifier.weight(1f), actions::acceptRestStop)
                BigButton("괜찮아", Color(0xFF334155), Modifier.weight(1f), actions::dismiss)
            }
        }
        Level.L3_STOPPED -> MessageCard(color, msg) {
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

@Composable
private fun PlainView(msg: Message, accent: Color?, extra: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(start = 24.dp), verticalArrangement = Arrangement.Center) {
        if (accent != null) {
            Box(Modifier.width(120.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(accent))
            Spacer(Modifier.height(36.dp))
        }
        Text(msg.title, color = TextMain, fontSize = 72.sp, fontWeight = FontWeight.Bold, lineHeight = 88.sp, style = Phrase)
        Spacer(Modifier.height(24.dp))
        Text(msg.body, color = TextSub, fontSize = 40.sp, lineHeight = 52.sp, style = Phrase)
        msg.note?.let {
            Spacer(Modifier.height(20.dp))
            Text(it, color = TextSub, fontSize = 30.sp, style = Phrase)
        }
        extra()
    }
}

/** One tap before departure; parked only, so it never asks for input while driving. */
@Composable
private fun MoodCheck(selected: Mood?, onPick: (Mood) -> Unit) {
    Spacer(Modifier.height(56.dp))
    Text("지금 기분은 어떠세요?", color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Mood.entries.forEach { m ->
            val on = m == selected
            Button(
                onClick = { onPick(m) },
                modifier = Modifier.weight(1f).height(104.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (on) Color(0xFF0F766E) else Chip),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(Messages.moodLabel(m), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else TextMain, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun RestMapBlock(state: CareState) {
    val rest = state.restPreview ?: return
    Spacer(Modifier.height(28.dp))
    Text(
        "${if (rest.isRestStop) "졸음쉼터" else "휴게소"} · ${rest.name} · %.1fkm".format(rest.km),
        color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(12.dp))
    RestStopMap(state.carPos, rest, Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(24.dp)))
}

@Composable
private fun MessageCard(
    color: Color,
    msg: Message,
    map: @Composable () -> Unit = {},
    buttons: @Composable RowScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(32.dp)).background(Panel).padding(64.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.width(120.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(color))
        Spacer(Modifier.height(40.dp))
        Text(msg.title, color = TextMain, fontSize = 80.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 96.sp, style = Phrase)
        Spacer(Modifier.height(24.dp))
        Text(msg.body, color = TextMain, fontSize = 48.sp, lineHeight = 60.sp, style = Phrase)
        msg.note?.let {
            Spacer(Modifier.height(20.dp))
            Text(it, color = TextSub, fontSize = 32.sp, style = Phrase)
        }
        map()
        Spacer(Modifier.height(40.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp), content = buttons)
    }
}

@Composable
private fun BigButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(140.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(label, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}
