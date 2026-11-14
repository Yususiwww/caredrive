package ai.caredrive.car.ui

import ai.caredrive.car.model.HrSample
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Heart-rate trend from real samples (not a waveform). No animation: it redraws only
 * when a new sample arrives, so it stays glanceable while driving (Pleos design rules).
 *
 * @param resting personal resting heart rate, drawn as a grey dashed line
 * @param limit caution threshold, drawn as a dashed line in the caution colour
 */
@Composable
fun HeartRateChart(
    samples: List<HrSample>,
    nowMs: Long,
    windowMs: Long,
    resting: Int?,
    limit: Int?,
    lineColor: Color,
    modifier: Modifier = Modifier,
    showLabels: Boolean = false,
) {
    // Scale to the visible readings (±6 bpm, at least a 24 bpm span) so small changes are readable.
    val visibleBpm = samples.filter { it.atMs >= nowMs - windowMs }.map { it.bpm }
    val dataLo = visibleBpm.minOrNull() ?: 60
    val dataHi = visibleBpm.maxOrNull() ?: 100
    val pad = maxOf(6, (24 - (dataHi - dataLo)) / 2)
    val lo = (dataLo - pad).toFloat()
    val hi = (dataHi + pad).toFloat()

    val palette = CareColor
    Column(modifier) {
        if (showLabels) {
            Row(Modifier.fillMaxWidth()) {
                Text("최근 ${windowMs / 60_000}분 · ${visibleBpm.minOrNull() ?: "-"}–${visibleBpm.maxOrNull() ?: "-"} bpm", color = CareColor.TextSecondary, style = CareType.caption, modifier = Modifier.weight(1f))
                if (limit != null) Text("- - 주의 기준 $limit", color = palette.CautionText, style = CareType.caption)
            }
            Spacer(Modifier.height(Space.m))
        }
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val start = nowMs - windowMs
            fun x(t: Long) = ((t - start).toFloat() / windowMs).coerceIn(0f, 1f) * size.width
            fun y(v: Int) = size.height - (v - lo) / (hi - lo) * size.height

            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))
            resting?.takeIf { it > lo && it < hi }?.let { drawLine(palette.Hairline, Offset(0f, y(it)), Offset(size.width, y(it)), 2.dp.toPx(), pathEffect = dash) }
            limit?.takeIf { it > lo && it < hi }?.let { drawLine(palette.CautionText, Offset(0f, y(it)), Offset(size.width, y(it)), 2.dp.toPx(), pathEffect = dash) }

            val visible = samples.filter { it.atMs >= start }
            if (visible.size >= 2) {
                val path = Path().apply {
                    visible.forEachIndexed { i, s -> if (i == 0) moveTo(x(s.atMs), y(s.bpm)) else lineTo(x(s.atMs), y(s.bpm)) }
                }
                drawPath(path, lineColor, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val last = visible.last()
                drawCircle(lineColor, 7.dp.toPx(), Offset(x(last.atMs), y(last.bpm)))
            }
        }
        if (showLabels && resting != null) {
            Spacer(Modifier.height(Space.xs))
            Text("- - 안정 심박 $resting", color = CareColor.TextTertiary, style = CareType.caption, modifier = Modifier.align(Alignment.End))
        }
    }
}
