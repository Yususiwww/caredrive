package ai.caredrive.car.ui

import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.Level
import ai.caredrive.car.model.Reason
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

/** Debug-only bar to jump between levels while building screens (step 1). */
@Composable
fun DemoBar(onState: (CareState) -> Unit) {
    val presets = listOf(
        "L0 정상" to CareState(Level.L0_NORMAL, heartRate = 72, connected = true),
        "주의 모드" to CareState(Level.L0_NORMAL, heartRate = 74, cautionMode = true, connected = true),
        "L1 주의" to CareState(Level.L1_CAUTION, Reason.HR_HIGH, heartRate = 108, connected = true),
        "L2 심박" to CareState(Level.L2_WARNING, Reason.HR_SUSTAINED, heartRate = 126, connected = true),
        "L2 부정맥" to CareState(Level.L2_WARNING, Reason.IHRN_NEW, heartRate = 96, cautionMode = true, connected = true),
        "L3 정차" to CareState(Level.L3_STOPPED, Reason.IHRN_NEW, heartRate = 90, connected = true),
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("DEMO", color = Color(0xFF64748B), fontSize = 24.sp, modifier = Modifier.padding(top = 18.dp, end = 8.dp))
        presets.forEach { (label, state) ->
            OutlinedButton(onClick = { onState(state) }, modifier = Modifier.height(72.dp)) {
                Text(label, fontSize = 24.sp, color = Color(0xFFCBD5E1))
            }
        }
    }
}
