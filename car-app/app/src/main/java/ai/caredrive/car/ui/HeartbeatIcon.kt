package ai.caredrive.car.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Static heartbeat symbol (a pictogram, not measured data). Drawn once, no animation,
 * so it is fine while driving.
 */
@Composable
fun HeartbeatIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val mid = h * 0.5f
        // (x, y) in 0..1 of the box: flat → small bump → tall spike up → deep down → bump → small bump → flat
        val pts = listOf(
            0.04f to 0.50f, 0.22f to 0.50f, 0.28f to 0.30f, 0.34f to 0.52f,
            0.42f to 0.06f, 0.52f to 0.94f, 0.60f to 0.26f, 0.68f to 0.56f,
            0.73f to 0.40f, 0.78f to 0.50f, 0.96f to 0.50f,
        ).map { (x, y) -> Offset(x * w, y * h) }

        // Smooth curve through the points (Catmull-Rom → cubic Bézier).
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 0 until pts.size - 1) {
                val p0 = pts[maxOf(i - 1, 0)]
                val p1 = pts[i]
                val p2 = pts[i + 1]
                val p3 = pts[minOf(i + 2, pts.size - 1)]
                cubicTo(
                    p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                    p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                    p2.x, p2.y,
                )
            }
        }
        val stroke = (h * 0.07f).coerceAtLeast(3.dp.toPx())
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, stroke * 1.1f, Offset(pts.first().x, mid))
        drawCircle(color, stroke * 1.1f, Offset(pts.last().x, mid))
    }
}
