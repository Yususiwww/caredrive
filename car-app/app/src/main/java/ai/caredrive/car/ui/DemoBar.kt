package ai.caredrive.car.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Debug-only bar. Same presets as `adb shell am broadcast -a ai.caredrive.car.DEMO --es preset <id>`. */
@Composable
fun DemoBar(status: String?, onPreset: (String) -> Unit) {
    val presets = listOf(
        "출발 전" to "predrive", "주행" to "drive", "휴식 시간" to "break", "긴장" to "tense", "스트레스" to "stress",
        "저혈당" to "glucose", "부정맥" to "ihrn", "정차" to "park", "워치 끊김" to "nodata", "시나리오 ▶" to "scenario",
    )
    Row(
        Modifier.fillMaxWidth().padding(top = Space.l),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(status ?: "DEMO", color = CareColor.TextTertiary, style = CareType.debug, maxLines = 1, modifier = Modifier.padding(top = Space.l, end = Space.xs))
        presets.forEach { (label, id) ->
            OutlinedButton(
                onClick = { onPreset(id) },
                modifier = Modifier.height(68.dp),
                shape = Radius.small,
                border = BorderStroke(1.dp, CareColor.Hairline),
            ) {
                Text(label, style = CareType.debug, color = CareColor.TextSecondary, maxLines = 1)
            }
        }
    }
}
