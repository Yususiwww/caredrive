package ai.caredrive.car.ui

import ai.caredrive.car.model.Level
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Level symbol whose shape differs per level, so the level is clear without relying on colour
 * (colour-blind and older drivers): circle with check, diamond "!", triangle "!", stop octagon.
 * Static drawing, no animation. Pass the level's [CarePalette.level] colour. On the light theme the
 * pale yellow and orange shapes get a thin dark edge so the outline still reads on white.
 */
@Composable
fun LevelIcon(level: Level, color: Color, modifier: Modifier = Modifier) {
    val palette = CareColor
    val edge = palette.levelEdge(level)
    Canvas(modifier) {
        val w = size.minDimension
        val c = Offset(size.width / 2, size.height / 2)
        // Dark (design D): every mark is cut out in the panel colour, like a tell-tale lamp.
        val mark = when {
            !palette.isLight -> palette.Background
            level == Level.L1_CAUTION || level == Level.L2_WARNING -> palette.OnLight
            else -> Color.White
        }
        val stroke = w * 0.11f
        when (level) {
            Level.L0_NORMAL -> {
                drawCircle(color, w / 2, c)
                val check = Path().apply {
                    moveTo(c.x - w * 0.22f, c.y + w * 0.01f)
                    lineTo(c.x - w * 0.06f, c.y + w * 0.17f)
                    lineTo(c.x + w * 0.24f, c.y - w * 0.15f)
                }
                drawPath(check, mark, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Level.L1_CAUTION -> {
                val shape = polygon(c, w / 2, 4, -PI / 2)
                drawPath(shape, color)
                edge?.let { drawPath(shape, it, style = Stroke(w * 0.04f, join = StrokeJoin.Round)) }
                exclamation(c, w * 0.62f, mark, stroke)
            }
            Level.L2_WARNING -> {
                // Triangle with the centroid a little low, like a road warning sign.
                val r = w * 0.56f
                val shape = polygon(Offset(c.x, c.y + w * 0.08f), r, 3, -PI / 2)
                drawPath(shape, color)
                edge?.let { drawPath(shape, it, style = Stroke(w * 0.04f, join = StrokeJoin.Round)) }
                exclamation(Offset(c.x, c.y + w * 0.1f), w * 0.5f, mark, stroke)
            }
            Level.L3_STOPPED -> {
                drawPath(polygon(c, w / 2, 8, PI / 8), color)
                drawLine(mark, Offset(c.x - w * 0.24f, c.y), Offset(c.x + w * 0.24f, c.y), stroke * 1.2f, cap = StrokeCap.Round)
            }
        }
    }
}

private fun polygon(c: Offset, r: Float, sides: Int, start: Double) = Path().apply {
    for (i in 0 until sides) {
        val a = start + 2 * PI * i / sides
        val p = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

private fun DrawScope.exclamation(c: Offset, h: Float, color: Color, stroke: Float) {
    drawLine(color, Offset(c.x, c.y - h * 0.42f), Offset(c.x, c.y + h * 0.12f), stroke, cap = StrokeCap.Round)
    drawCircle(color, stroke * 0.62f, Offset(c.x, c.y + h * 0.38f))
}
