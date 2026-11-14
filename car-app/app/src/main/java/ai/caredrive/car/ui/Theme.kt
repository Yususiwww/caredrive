package ai.caredrive.car.ui

import ai.caredrive.car.R
import ai.caredrive.car.model.Level
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Asta Sans, the Pleos Connect system font (SIL OFL 1.1, third_party/AstaSans-OFL.txt).
 * One variable font file; each weight picks its point on the weight axis.
 */
val AstaSans = FontFamily(
    listOf(400, 500, 700, 800).map { w ->
        Font(R.font.asta_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

/**
 * Colour tokens. A TDS grey scale, one blue accent for actions and the chosen option, and level
 * colours that only ever mean a level (ISO 2575: green normal, yellow/orange caution, red danger).
 * Level is never colour alone: see [LevelIcon]. Two palettes share the same names; light is the
 * default because the Pleos system chrome around the app is light.
 */
@Immutable
class CarePalette(
    val isLight: Boolean,
    // Greys: page, card, secondary button, dashed lines, then text from quiet to strong.
    val Background: Color,
    /** Soft radial light behind the cards: bright near the top left, falling to [BackdropEdge]. */
    val BackdropGlow: Color,
    val BackdropEdge: Color,
    val Surface: Color,
    val SurfaceHigh: Color,
    val Hairline: Color,
    val TextTertiary: Color,
    val TextSecondary: Color,
    val TextPrimary: Color,
    /** The only accent: primary buttons, selected option, the car on the map, the heart-rate line. */
    val Accent: Color,
    val OnAccent: Color,
    // Level colours (meaning only). As fills of the level shape.
    val Normal: Color,
    val Caution: Color,
    val Warning: Color,
    val Danger: Color,
    /** Filled emergency button (119), white text on it. */
    val DangerContainer: Color,
    /** Caution and warning as text or thin lines: yellow and orange text fail contrast on white. */
    val CautionText: Color,
    val WarningText: Color,
) {
    /** Mark inside the yellow and orange level shapes; the same on both themes because it sits on the fill. */
    val OnLight = Color(0xFF191F28) // TDS grey900

    fun level(level: Level) = when (level) {
        Level.L0_NORMAL -> Normal
        Level.L1_CAUTION -> Caution
        Level.L2_WARNING -> Warning
        Level.L3_STOPPED -> Danger
    }

    /** Level word beside the shape. On white the shape carries the colour and the word stays grey900. */
    fun levelLabel(level: Level) = if (isLight) TextPrimary else level(level)

    /** Outline for the pale level shapes on white, so yellow and orange keep a visible edge. */
    fun levelEdge(level: Level): Color? = if (!isLight) null else when (level) {
        Level.L1_CAUTION -> CautionText
        Level.L2_WARNING -> WarningText
        else -> null
    }
}

val LightPalette = CarePalette(
    isLight = true,
    Background = Color(0xFFF2F4F6), // TDS grey100
    BackdropGlow = Color(0xFFFFFFFF),
    BackdropEdge = Color(0xFFD9DEE3), // between grey200 and grey300
    Surface = Color(0xFFFFFFFF),
    SurfaceHigh = Color(0xFFF2F4F6), // grey100, secondary button on a white card
    Hairline = Color(0xFFB0B8C1), // grey400
    TextTertiary = Color(0xFF6B7684), // grey600, 4.8:1 on white
    TextSecondary = Color(0xFF4E5968), // grey700
    TextPrimary = Color(0xFF191F28), // grey900
    Accent = Color(0xFF3182F6), // blue500
    OnAccent = Color.White,
    Normal = Color(0xFF03B26C), // green500
    Caution = Color(0xFFFFC342), // yellow500
    Warning = Color(0xFFFE9800), // orange500
    Danger = Color(0xFFF04452), // red500
    DangerContainer = Color(0xFFD22030), // red700, white text 5.2:1
    CautionText = Color(0xFFA15C00), // dark amber, 5.2:1 on white
    WarningText = Color(0xFFC2410C), // dark orange, 5.2:1 on white
)

val DarkPalette = CarePalette(
    isLight = false,
    Background = Color(0xFF0F1115),
    BackdropGlow = Color(0xFF22262E),
    BackdropEdge = Color(0xFF0B0D10),
    Surface = Color(0xFF1B1E24),
    SurfaceHigh = Color(0xFF30343D),
    Hairline = Color(0xFF4E5968), // grey700
    TextTertiary = Color(0xFF8B95A1), // grey500, 5.6:1 on Surface
    TextSecondary = Color(0xFFB0B8C1), // grey400
    TextPrimary = Color(0xFFF2F4F6), // grey100
    Accent = Color(0xFF3182F6),
    OnAccent = Color.White,
    Normal = Color(0xFF03B26C),
    Caution = Color(0xFFFFC342),
    Warning = Color(0xFFFE9800),
    Danger = Color(0xFFF04452),
    DangerContainer = Color(0xFF8A1C27), // white text 9:1
    CautionText = Color(0xFFFFC342),
    WarningText = Color(0xFFFE9800),
)

val LocalCarePalette = staticCompositionLocalOf { LightPalette }

/** Current palette. Read it in composition; inside a Canvas lambda, read it once before drawing. */
val CareColor: CarePalette
    @Composable @ReadOnlyComposable get() = LocalCarePalette.current

/** Old or missing watch data is dimmed, never shown as if it were current. */
/** Page background: a still, soft grey gradient (no motion). Light glows from the top left like a studio backdrop. */
fun Modifier.careBackdrop(p: CarePalette) = drawBehind {
    drawRect(p.BackdropEdge)
    drawRect(
        Brush.radialGradient(
            0f to p.BackdropGlow, 0.45f to p.BackdropGlow.copy(alpha = 0.85f).compositeOver(p.BackdropEdge), 1f to p.BackdropEdge,
            center = Offset(size.width * 0.32f, size.height * 0.18f),
            radius = maxOf(size.width, size.height) * 0.95f,
        )
    )
}

const val StaleAlpha = 0.35f

/** 4-based spacing scale. Every padding and gap comes from here. */
object Space {
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val xxxl = 64.dp
}

/** One radius family: cards, controls, small marks. */
object Radius {
    val card = RoundedCornerShape(32.dp)
    val control = RoundedCornerShape(24.dp)
    val small = RoundedCornerShape(16.dp)
}

/** Touch target for every driver button (Android for Cars asks for at least 76dp). */
val ButtonHeight = 128.dp

/** Mood choice height: parked only, still well above the car minimum. */
val ChoiceHeight = 104.dp

/**
 * Type tokens. Three levels per screen at most: title, body, label.
 * At the emulator's 1dp = 1px, every size is at least 30sp, and 38sp or more for anything seen
 * while driving (docs/research/2026-11-11-차량-UI-레퍼런스.md, ISO 15008).
 * Asta Sans stays the face; weight and size carry the hierarchy.
 */
object CareType {
    /** Break Korean lines between words (eojeol), not in the middle of a word. */
    private val base = TextStyle(fontFamily = AstaSans, lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase))

    /** The one big number on the screen: heart rate in the wide layout. */
    val number = base.copy(fontSize = 120.sp, lineHeight = 128.sp, fontWeight = FontWeight.Bold)
    /** Heart rate in the narrow status card. */
    val numberCompact = base.copy(fontSize = 72.sp, lineHeight = 80.sp, fontWeight = FontWeight.Bold)

    val title = base.copy(fontSize = 76.sp, lineHeight = 92.sp, fontWeight = FontWeight.ExtraBold)
    val titleCompact = base.copy(fontSize = 62.sp, lineHeight = 76.sp, fontWeight = FontWeight.ExtraBold)

    val body = base.copy(fontSize = 48.sp, lineHeight = 62.sp, fontWeight = FontWeight.Medium)
    val bodyCompact = base.copy(fontSize = 44.sp, lineHeight = 56.sp, fontWeight = FontWeight.Medium)

    /** Level word, status lines, notes, buttons: the smallest text seen while driving. */
    val label = base.copy(fontSize = 38.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold)
    val labelRegular = label.copy(fontWeight = FontWeight.Medium)

    /** Parked-only detail (chart legend, event line). Never below the 30sp floor. */
    val caption = base.copy(fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium)

    /** Debug demo bar only; not part of the product. */
    val debug = base.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium)
}

private fun TextStyle.asta() = copy(fontFamily = AstaSans)

/** Light by default. [dark] comes from the debug intent or the system night mode (MainActivity). */
@Composable
fun CareTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val t = Typography()
    val typography = Typography(
        displayLarge = t.displayLarge.asta(), displayMedium = t.displayMedium.asta(), displaySmall = t.displaySmall.asta(),
        headlineLarge = t.headlineLarge.asta(), headlineMedium = t.headlineMedium.asta(), headlineSmall = t.headlineSmall.asta(),
        titleLarge = t.titleLarge.asta(), titleMedium = t.titleMedium.asta(), titleSmall = t.titleSmall.asta(),
        bodyLarge = t.bodyLarge.asta(), bodyMedium = t.bodyMedium.asta(), bodySmall = t.bodySmall.asta(),
        labelLarge = t.labelLarge.asta(), labelMedium = t.labelMedium.asta(), labelSmall = t.labelSmall.asta(),
    )
    val colors = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.Accent, onPrimary = p.OnAccent,
        background = p.Background, onBackground = p.TextPrimary,
        surface = p.Surface, onSurface = p.TextPrimary,
        surfaceVariant = p.SurfaceHigh, onSurfaceVariant = p.TextSecondary,
        outline = p.Hairline, error = p.Danger,
    )
    CompositionLocalProvider(LocalCarePalette provides p) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}
