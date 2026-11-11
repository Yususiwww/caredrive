package ai.caredrive.car.ui

import ai.caredrive.car.R
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * Asta Sans, the Pleos Connect system font (SIL OFL 1.1, third_party/AstaSans-OFL.txt).
 * One variable font file; each weight picks its point on the weight axis.
 */
val AstaSans = FontFamily(
    listOf(400, 500, 700, 800).map { w ->
        Font(R.font.asta_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

private fun TextStyle.asta() = copy(fontFamily = AstaSans)

@Composable
fun CareTheme(content: @Composable () -> Unit) {
    val t = Typography()
    val typography = Typography(
        displayLarge = t.displayLarge.asta(), displayMedium = t.displayMedium.asta(), displaySmall = t.displaySmall.asta(),
        headlineLarge = t.headlineLarge.asta(), headlineMedium = t.headlineMedium.asta(), headlineSmall = t.headlineSmall.asta(),
        titleLarge = t.titleLarge.asta(), titleMedium = t.titleMedium.asta(), titleSmall = t.titleSmall.asta(),
        bodyLarge = t.bodyLarge.asta(), bodyMedium = t.bodyMedium.asta(), bodySmall = t.bodySmall.asta(),
        labelLarge = t.labelLarge.asta(), labelMedium = t.labelMedium.asta(), labelSmall = t.labelSmall.asta(),
    )
    MaterialTheme(typography = typography, content = content)
}
