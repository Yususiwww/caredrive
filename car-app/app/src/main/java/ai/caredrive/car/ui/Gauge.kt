package ai.caredrive.car.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 240-degree dial opening at the bottom, like a speedometer (design D). */
private const val DialStart = 150f
private const val DialSweep = 240f

/** Below this size a dial is a "rail" dial: thicker strokes, no scale numbers. */
private val SmallDial = 320.dp

/**
 * Instrument dial: track, thin minor and major ticks, value arc and a needle. Static; it moves only
 * when the value changes (no animation). [value] null shows "--" and no arc.
 * [valueText] replaces the number (e.g. "정차 중"), drawn smaller.
 */
@Composable
fun Dial(
    value: Int?,
    min: Int,
    max: Int,
    major: Int,
    minor: Int,
    color: Color,
    label: String,
    unit: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    valueText: String? = null,
    dimmed: Boolean = false,
) {
    val p = CareColor
    val small = size < SmallDial
    // Narrow-window top row: the readout must sit inside the ring, so everything steps down.
    val tiny = size < 220.dp
    val capStyle = if (tiny) CareType.chip.copy(fontSize = 22.sp, lineHeight = 26.sp) else if (small) CareType.chip else CareType.labelRegular
    val measurer = rememberTextMeasurer()
    val scaleStyle = CareType.chip.copy(color = p.TextTertiary, fontSize = 26.sp)
    Box(modifier.size(size).alpha(if (dimmed) StaleAlpha else 1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = this.size.minDimension
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val sw = s * (if (small) 0.035f else 0.024f)
            val r = s / 2 * 0.88f - sw / 2
            fun ang(x: Float) = DialStart + DialSweep * ((x - min) / (max - min)).coerceIn(0f, 1f)
            arc(p.GaugeTrack, c, r, DialStart, DialSweep, sw)
            val v = value?.toFloat()
            if (v != null && v > min) arc(color, c, r, DialStart, ang(v) - DialStart, sw)
            val tr = r - sw
            var x = min
            while (x <= max) {
                val isMajor = (x - min) % major == 0
                val a = ang(x.toFloat())
                val len = s * if (isMajor) (if (small) 0.05f else 0.042f) else (if (small) 0.026f else 0.02f)
                val w = (s * if (isMajor) (if (small) 0.009f else 0.005f) else (if (small) 0.006f else 0.003f)).coerceAtLeast(1.5f)
                drawLine(if (isMajor) p.TickMajor else p.Tick, polar(c, tr, a), polar(c, tr - len, a), w)
                if (isMajor && !small) {
                    val layout = measurer.measure(x.toString(), scaleStyle)
                    val at = polar(c, tr - s * 0.085f, a)
                    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2, at.y - layout.size.height / 2))
                }
                x += minor
            }
            if (v != null && v > min) {
                val a = ang(v)
                drawLine(
                    p.TextPrimary, polar(c, r + sw * 0.9f, a), polar(c, tr - s * (if (small) 0.06f else 0.055f), a),
                    s * if (small) 0.014f else 0.008f, cap = StrokeCap.Round,
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = size * if (tiny) 0.08f else 0.04f)) {
            Text(label, color = p.TextSecondary, style = capStyle, maxLines = 1)
            if (valueText != null) {
                Text(valueText, color = p.TextPrimary, style = if (tiny) CareType.chip else if (small) CareType.label else CareType.title, maxLines = 1)
            } else {
                Text(
                    value?.toString() ?: "--", color = p.TextPrimary, maxLines = 1,
                    style = when {
                        size >= 480.dp -> CareType.gaugeBig
                        tiny -> CareType.gaugeSmall.copy(fontSize = 50.sp, lineHeight = 54.sp)
                        small -> CareType.gaugeSmall
                        else -> CareType.gaugeMid
                    },
                )
            }
            unit?.let { Text(it, color = p.TextSecondary, style = capStyle, maxLines = 1) }
        }
    }
}

/**
 * Countdown ring for the no-pulse check: a full circle of seconds with a tick every 10 s and a
 * longer one each minute. The arc is what is left. Redrawn once a second by the state, no animation.
 */
@Composable
fun CountdownRing(secondsLeft: Int?, totalSeconds: Int, size: Dp, label: String, modifier: Modifier = Modifier) {
    val p = CareColor
    val left = secondsLeft?.coerceIn(0, totalSeconds)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = this.size.minDimension
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val sw = s * 0.034f
            val r = s / 2 - sw
            arc(p.GaugeTrack, c, r, -90f, 360f, sw)
            if (left != null && left > 0) arc(p.Danger, c, r, -90f, 360f * left / totalSeconds, sw)
            val tr = r - sw * 1.2f
            for (t in 0 until totalSeconds step 10) {
                val major = t % 60 == 0
                val a = -90f + 360f * t / totalSeconds
                drawLine(
                    if (major) p.TickMajor else p.Tick, polar(c, tr, a), polar(c, tr - s * (if (major) 0.05f else 0.025f), a),
                    (s * if (major) 0.008f else 0.004f).coerceAtLeast(1.5f),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = p.TextSecondary, style = CareType.labelRegular, maxLines = 1)
            Text(
                left?.let { "%d:%02d".format(it / 60, it % 60) } ?: "--:--",
                color = p.TextPrimary, maxLines = 1,
                style = if (size >= 440.dp) CareType.countdown else CareType.countdownCompact,
            )
        }
    }
}

/**
 * Drowsiness as a thin bar on a tick scale. No number: the score is a design example, so the screen
 * only shows "more or less". Longer bar and a warmer colour together, never colour alone.
 */
@Composable
fun DrowsinessBar(score: Int, modifier: Modifier = Modifier) {
    val p = CareColor
    val fill = when {
        score >= 60 -> p.Warning
        score >= 40 -> p.Caution
        else -> p.TextSecondary
    }
    Column(modifier) {
        Text("졸음", color = p.TextSecondary, style = CareType.chip, maxLines = 1)
        Spacer(Modifier.height(Space.xs))
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            for (i in 0..10) {
                val x = size.width * i / 10
                drawLine(if (i % 5 == 0) p.TickMajor else p.Tick, Offset(x, 0f), Offset(x, size.height), 1.5f)
            }
            val h = 5.dp.toPx()
            drawRect(p.GaugeTrack, Offset(0f, size.height / 2 - h / 2), Size(size.width, h))
            drawRect(fill, Offset(0f, size.height / 2 - h / 2), Size(size.width * score.coerceIn(0, 100) / 100f, h))
        }
    }
}

/** Small outlined chip for a vehicle signal ("D · 72 km/h", "비상등 켜짐"): proof the app reads the car. */
@Composable
fun SignalChip(text: String, modifier: Modifier = Modifier, hazard: Boolean = false) {
    val p = CareColor
    Row(
        modifier.clip(Radius.small).border(1.5.dp, p.ControlEdge, Radius.small).background(p.SurfaceHigh)
            .padding(horizontal = Space.m, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hazard) {
            HazardMark(p.Warning, Modifier.size(28.dp))
            Spacer(Modifier.width(Space.xs))
        }
        Text(text, color = p.TextPrimary, style = CareType.chip, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Hazard-light symbol: two nested triangles, drawn as outlines. */
@Composable
fun HazardMark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.minDimension
        val c = Offset(size.width / 2, size.height / 2 + w * 0.06f)
        drawPath(triangle(c, w * 0.5f), color, style = Stroke(w * 0.09f, join = StrokeJoin.Round))
        drawPath(triangle(c, w * 0.24f), color, style = Stroke(w * 0.08f, join = StrokeJoin.Round))
    }
}

/** Checklist mark: filled circle with a check when done, an empty ring when not (shape, not colour). */
@Composable
fun CheckDot(done: Boolean, modifier: Modifier = Modifier) {
    val p = CareColor
    Canvas(modifier) {
        val w = size.minDimension
        val c = Offset(size.width / 2, size.height / 2)
        if (done) {
            drawCircle(p.TextPrimary, w / 2, c)
            val path = Path().apply {
                moveTo(c.x - w * 0.22f, c.y + w * 0.01f)
                lineTo(c.x - w * 0.06f, c.y + w * 0.17f)
                lineTo(c.x + w * 0.24f, c.y - w * 0.15f)
            }
            drawPath(path, p.Background, style = Stroke(w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawCircle(p.TickMajor, w / 2 - w * 0.05f, c, style = Stroke(w * 0.08f))
        }
    }
}

/** Fits a square gauge into whatever space the layout gives it. */
@Composable
fun FitDial(maxSize: Dp, content: @Composable (Dp) -> Unit) {
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        content(minOf(maxWidth, maxHeight, maxSize))
    }
}

private fun DrawScope.arc(color: Color, c: Offset, r: Float, start: Float, sweep: Float, width: Float) {
    if (abs(sweep) < 0.01f) return
    drawArc(color, start, sweep, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(width, cap = StrokeCap.Butt))
}

private fun polar(c: Offset, r: Float, deg: Float): Offset {
    val t = Math.toRadians(deg.toDouble())
    return Offset(c.x + r * cos(t).toFloat(), c.y + r * sin(t).toFloat())
}

private fun triangle(c: Offset, r: Float) = Path().apply {
    for (i in 0 until 3) {
        val a = Math.toRadians(-90.0 + 120.0 * i)
        val p = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}
