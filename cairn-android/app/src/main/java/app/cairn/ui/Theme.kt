package app.cairn.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.cairn.R

/** Lume neutrals (same values as the other apps) with Cairn's one accent, a quiet slate teal. */
@Immutable
class CairnColors(
    val canvas: Color,
    val grouped: Color,
    val raised: Color,
    val hairline: Color,
    val ink: Color,
    val inkSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val dark: Boolean,
)

val DarkColors = CairnColors(
    canvas = Color(0xFF1C1C1E), grouped = Color(0xFF252527), raised = Color(0xFF2C2C2E),
    hairline = Color(0x1FFFFFFF), ink = Color(0xFFF2F2F5), inkSecondary = Color(0xFFA1A1A6),
    accent = Color(0xFF8CC0CF), onAccent = Color(0xFF101418), dark = true,
)
val LightColors = CairnColors(
    canvas = Color(0xFFFFFFFF), grouped = Color(0xFFF4F4F6), raised = Color(0xFFFFFFFF),
    hairline = Color(0x243C3C43), ink = Color(0xFF1D1D1F), inkSecondary = Color(0xFF5E5E63),
    accent = Color(0xFF2F6677), onAccent = Color(0xFFFFFFFF), dark = false,
)

val LocalCairnColors = staticCompositionLocalOf { DarkColors }

val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)

private fun style(size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Geist, fontWeight = weight, fontSize = size.sp)

private val Type = Typography(
    headlineMedium = style(28, FontWeight.SemiBold),
    titleLarge = style(20, FontWeight.SemiBold),
    titleMedium = style(16, FontWeight.SemiBold),
    bodyLarge = style(16),
    bodyMedium = style(14),
    labelLarge = style(14, FontWeight.SemiBold),
    labelMedium = style(12),
    labelSmall = style(12),
)

@Composable
fun CairnTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) DarkColors else LightColors
    val scheme = if (c.dark) {
        darkColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.canvas, onBackground = c.ink,
            surface = c.canvas, onSurface = c.ink, surfaceVariant = c.grouped, onSurfaceVariant = c.inkSecondary,
            outline = c.hairline, secondaryContainer = c.raised, onSecondaryContainer = c.ink)
    } else {
        lightColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.canvas, onBackground = c.ink,
            surface = c.canvas, onSurface = c.ink, surfaceVariant = c.grouped, onSurfaceVariant = c.inkSecondary,
            outline = c.hairline, secondaryContainer = c.grouped, onSecondaryContainer = c.ink)
    }
    CompositionLocalProvider(LocalCairnColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = Type, content = content)
    }
}
