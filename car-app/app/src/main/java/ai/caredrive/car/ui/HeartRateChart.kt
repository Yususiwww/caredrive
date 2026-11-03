package ai.caredrive.car.ui

import ai.caredrive.car.model.HrSample
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.sp

/**
 * Heart-rate trend from real samples (not a waveform). No animation: it redraws only
 * when a new sample arrives, so it stays glanceable while driving (Pleos design rules).
 *
 * @param resting personal resting heart rate, drawn as a grey dashed line
 * @param limit caution threshold, drawn as an amber dashed line
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
    val values = samples.map { it.bpm } + listOfNotNull(resting, limit)
    val lo = ((values.minOrNull() ?: 60) - 10).coerceAtMost(50).toFloat()
    val hi = ((values.maxOrNull() ?: 100) + 10).coerceAtLeast(110).toFloat()

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val start = nowMs - windowMs
            fun x(t: Long) = ((t - start).toFloat() / windowMs).coerceIn(0f, 1f) * size.width
            fun y(v: Int) = size.height - (v - lo) / (hi - lo) * size.height

            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))
            resting?.let { drawLine(Color(0xFF475569), Offset(0f, y(it)), Offset(size.width, y(it)), 2.dp.toPx(), pathEffect = dash) }
            limit?.let { drawLine(Color(0xFFEAB308), Offset(0f, y(it)), Offset(size.width, y(it)), 2.dp.toPx(), pathEffect = dash) }

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
        if (showLabels) {
            Text("최근 ${windowMs / 60_000}분", color = Color(0xFF94A3B8), fontSize = 22.sp, modifier = Modifier.align(Alignment.TopStart))
            if (limit != null) Text("주의 기준 $limit", color = Color(0xFFEAB308), fontSize = 22.sp, modifier = Modifier.align(Alignment.TopEnd))
            if (resting != null) Text("안정 심박 $resting", color = Color(0xFF94A3B8), fontSize = 22.sp, modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}
