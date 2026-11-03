package ai.caredrive.car.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Debug-only bar. Same presets as `adb shell am broadcast -a ai.caredrive.car.DEMO --es preset <id>`. */
@Composable
fun DemoBar(status: String?, onPreset: (String) -> Unit) {
    val presets = listOf(
        "출발 전" to "predrive", "주행" to "drive", "휴식 시간" to "break", "긴장" to "tense",
        "저혈당" to "glucose", "부정맥" to "ihrn", "정차" to "park", "시나리오 ▶" to "scenario",
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(status ?: "DEMO", color = Color(0xFF64748B), fontSize = 22.sp, maxLines = 1, modifier = Modifier.padding(top = 18.dp, end = 8.dp))
        presets.forEach { (label, id) ->
            OutlinedButton(onClick = { onPreset(id) }, modifier = Modifier.height(68.dp)) {
                Text(label, fontSize = 22.sp, color = Color(0xFFCBD5E1), maxLines = 1)
            }
        }
    }
}
