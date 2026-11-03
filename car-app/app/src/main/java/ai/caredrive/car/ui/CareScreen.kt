package ai.caredrive.car.ui

import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.Reason
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0B1220)
private val Panel = Color(0xFF131C2E)
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

private fun levelLabel(level: Level) = when (level) {
    Level.L0_NORMAL -> "정상"
    Level.L1_CAUTION -> "주의"
    Level.L2_WARNING -> "경고"
    Level.L3_STOPPED -> "정차 후 조치"
}

/** Driver-facing actions the screen can request. Wired to vehicle features in step 2. */
interface CareActions {
    fun acceptRestStop()
    fun dismiss()
    fun startEcgGuide()
    fun notifyGuardian()
    fun call119()
}

@Composable
fun CareScreen(state: CareState, actions: CareActions, demoBar: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().background(Bg).padding(48.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            StatusPanel(state, Modifier.weight(0.34f).fillMaxHeight())
            Box(Modifier.weight(0.66f).fillMaxHeight()) {
                when (state.level) {
                    Level.L0_NORMAL -> NormalView(state)
                    Level.L1_CAUTION -> CautionView(state)
                    Level.L2_WARNING -> WarningView(state, actions)
                    Level.L3_STOPPED -> StoppedView(actions)
                }
            }
        }
        demoBar()
    }
}

@Composable
private fun StatusPanel(state: CareState, modifier: Modifier) {
    val color = levelColor(state.level)
    Column(
        modifier.clip(RoundedCornerShape(32.dp)).background(Panel).padding(48.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("케어드라이브", color = TextSub, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(48.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(20.dp))
                Text(levelLabel(state.level), color = color, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(40.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(state.heartRate?.toString() ?: "--", color = TextMain, fontSize = 140.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(16.dp))
                Text("bpm", color = TextSub, fontSize = 40.sp, modifier = Modifier.padding(bottom = 28.dp))
            }
        }
        Column {
            if (state.cautionMode) {
                Text(
                    "주의 모드 · 최근 부정맥 알림 있음",
                    color = Color(0xFFFACC15), fontSize = 28.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(
                if (state.connected) "워치 연결됨" else "워치 연결 안 됨",
                color = if (state.connected) Color(0xFF5EEAD4) else TextSub, fontSize = 28.sp,
            )
        }
    }
}

@Composable
private fun NormalView(state: CareState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("편안한 운전 중이에요", color = TextMain, fontSize = 72.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Text(
            if (state.cautionMode) "오늘은 1시간마다 휴식을 권해 드릴게요" else "이상이 있으면 음성으로 알려 드릴게요",
            color = TextSub, fontSize = 40.sp,
        )
    }
}

@Composable
private fun CautionView(state: CareState) {
    MessageCard(
        color = levelColor(Level.L1_CAUTION),
        title = "조금 긴장하신 것 같아요",
        body = "실내를 시원하게 맞췄어요.\n천천히 숨을 쉬어 보세요.",
        note = if (state.reason == Reason.HR_HIGH) "평소보다 심박이 높은 상태가 이어지고 있어요" else null,
    ) {}
}

@Composable
private fun WarningView(state: CareState, actions: CareActions) {
    val title = when (state.reason) {
        Reason.IHRN_NEW -> "심장 리듬 알림이 왔어요"
        Reason.HR_LOW -> "심박이 평소보다 많이 낮아요"
        else -> "심박이 계속 높아요"
    }
    MessageCard(
        color = levelColor(Level.L2_WARNING),
        title = title,
        body = "가까운 휴게소로 안내할까요?",
        note = "\"응\" 또는 \"괜찮아\"라고 말해도 돼요",
    ) {
        BigButton("휴게소 안내", levelColor(Level.L2_WARNING), Modifier.weight(1f), actions::acceptRestStop)
        BigButton("괜찮아", Color(0xFF334155), Modifier.weight(1f), actions::dismiss)
    }
}

@Composable
private fun StoppedView(actions: CareActions) {
    MessageCard(
        color = levelColor(Level.L3_STOPPED),
        title = "잠시 쉬어 가세요",
        body = "워치로 심전도를 측정해 보는 걸 권해요.",
        note = "케어드라이브는 진단하지 않아요. 증상이 있으면 의료진과 상의하세요.",
    ) {
        BigButton("심전도 안내", levelColor(Level.L3_STOPPED), Modifier.weight(1f), actions::startEcgGuide)
        BigButton("보호자 알림", Color(0xFF334155), Modifier.weight(1f), actions::notifyGuardian)
        BigButton("119 전화", Color(0xFF7F1D1D), Modifier.weight(1f), actions::call119)
    }
}

@Composable
private fun MessageCard(
    color: Color,
    title: String,
    body: String,
    note: String?,
    buttons: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(32.dp)).background(Panel).padding(64.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.width(120.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(color))
        Spacer(Modifier.height(40.dp))
        Text(title, color = TextMain, fontSize = 80.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 96.sp, style = Phrase)
        Spacer(Modifier.height(24.dp))
        Text(body, color = TextMain, fontSize = 48.sp, lineHeight = 60.sp, style = Phrase)
        if (note != null) {
            Spacer(Modifier.height(20.dp))
            Text(note, color = TextSub, fontSize = 32.sp, style = Phrase)
        }
        Spacer(Modifier.height(56.dp))
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
