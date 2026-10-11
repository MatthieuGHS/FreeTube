package fr.appartfinder.freetube.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The shared web/app tokens (bg, surface, surface-2, border, text, text-muted, accent) mapped onto M3 roles. */
private data class Tokens(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
)

private val Light = Tokens(
    bg = Color(0xFFFFFFFF), surface = Color(0xFFF4F4F5), surface2 = Color(0xFFE9E9EC), border = Color(0xFFE4E4E7),
    text = Color(0xFF18181B), muted = Color(0xFF6B6B73), accent = Color(0xFFDC3E42), onAccent = Color(0xFFFFFFFF),
)

private val Dark = Tokens(
    bg = Color(0xFF0B0B0C), surface = Color(0xFF161618), surface2 = Color(0xFF222225), border = Color(0xFF26262A),
    text = Color(0xFFF4F4F5), muted = Color(0xFFA1A1AA), accent = Color(0xFFFF6369), onAccent = Color(0xFF0B0B0C),
)

private fun Tokens.scheme(dark: Boolean) = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = accent, onPrimary = onAccent, inversePrimary = accent,
    primaryContainer = surface2, onPrimaryContainer = text,
    secondary = muted, onSecondary = bg, secondaryContainer = surface2, onSecondaryContainer = text,
    tertiary = accent, onTertiary = onAccent, tertiaryContainer = surface2, onTertiaryContainer = text,
    background = bg, onBackground = text,
    surface = bg, onSurface = text, surfaceVariant = surface, onSurfaceVariant = muted,
    surfaceTint = bg, surfaceBright = surface, surfaceDim = bg,
    surfaceContainerLowest = bg, surfaceContainerLow = surface, surfaceContainer = surface,
    surfaceContainerHigh = surface, surfaceContainerHighest = surface2,
    inverseSurface = text, inverseOnSurface = bg,
    error = accent, onError = onAccent, errorContainer = surface2, onErrorContainer = accent,
    // outline draws input borders (needs 3:1 against bg); outlineVariant draws hairline dividers.
    outline = muted, outlineVariant = border,
)

private val base = Typography()

private val FreeTubeTypography = Typography(
    headlineSmall = base.headlineSmall.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
)

// M3 menus and text fields both read extraSmall: inputs get 8, menus pass shapes.medium (12) explicitly.
private val FreeTubeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

/** Follows the system light/dark setting. No dynamic color: the app must look like the web. */
@Composable
fun FreeTubeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = (if (dark) Dark else Light).scheme(dark),
        typography = FreeTubeTypography,
        shapes = FreeTubeShapes,
        content = content,
    )
}

/** Durations and times: tabular figures so digits do not jitter while they change. */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")
