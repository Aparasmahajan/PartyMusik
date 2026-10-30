package io.mahajan.partymusik.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens from docs/18 §B.4. Status colours always ship with a glyph and text (§B.5). */
@Immutable
data class PartyColors(
    val ground: Color,
    val card: Color,
    val elevated: Color,
    val fill: Color,
    val chrome: Color,
    val label: Color,
    val secondary: Color,
    val tertiary: Color,
    val separator: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val warn: Color,
    val error: Color,
    val callGo: Color,
    val callGoSoft: Color,
    val callEnd: Color,
    val isDark: Boolean,
)

val LightPartyColors = PartyColors(
    ground = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    elevated = Color(0xFFFFFFFF),
    fill = Color(0xFFE5E5EA),
    chrome = Color(0xF7F9F9F9),
    label = Color(0xFF1D1D1F),
    secondary = Color(0xFF6C6C70),
    tertiary = Color(0xFFAEAEB2),
    separator = Color(0xFFD1D1D6),
    accent = Color(0xFF5B3FD1),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFECE8FB),
    warn = Color(0xFFB25000),
    error = Color(0xFFD70015),
    callGo = Color(0xFF248A3D),
    callGoSoft = Color(0xFFE3F4E7),
    callEnd = Color(0xFFD70015),
    isDark = false,
)

val DarkPartyColors = PartyColors(
    ground = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    elevated = Color(0xFF2C2C2E),
    fill = Color(0xFF3A3A3C),
    chrome = Color(0xF5161618),
    label = Color(0xFFFFFFFF),
    secondary = Color(0xFF98989F),
    tertiary = Color(0xFF636366),
    separator = Color(0xFF38383A),
    accent = Color(0xFFA895FF),
    onAccent = Color(0xFF000000),
    accentSoft = Color(0xFF2C2547),
    warn = Color(0xFFFF9F0A),
    error = Color(0xFFFF453A),
    callGo = Color(0xFF30D158),
    callGoSoft = Color(0xFF12301B),
    callEnd = Color(0xFFD70015),
    isDark = true,
)

/** Type scale (docs/18 §B.4). Uses the platform's system sans. */
object PartyType {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val title2 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
    val title3 = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val tab = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium)
}

/** Corner radii and the 4 pt spacing grid. */
object PartyShape {
    val artwork = RoundedCornerShape(24.dp)
    val tile = RoundedCornerShape(20.dp)
    val group = RoundedCornerShape(18.dp)
    val sheet = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val button = RoundedCornerShape(14.dp)
    val icon = RoundedCornerShape(11.dp)
    val pill = RoundedCornerShape(percent = 50)
}

object PartySpace {
    val gutter = 16.dp
    val gap = 14.dp
    val section = 24.dp
    val minTouch = 44.dp
}

val LocalPartyColors = staticCompositionLocalOf { LightPartyColors }

object Party {
    val colors: PartyColors
        @Composable get() = LocalPartyColors.current
}

/**
 * Root theme. Material 3 stays underneath so stock components (sliders, switches, sheets, dialogs)
 * pick up the same palette.
 */
@Composable
fun PartyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkPartyColors else LightPartyColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.label,
            secondary = c.accent, onSecondary = c.onAccent,
            background = c.ground, onBackground = c.label, surface = c.card, onSurface = c.label,
            surfaceVariant = c.fill, onSurfaceVariant = c.secondary, surfaceContainerLow = c.card,
            surfaceContainer = c.elevated, surfaceContainerHigh = c.elevated, surfaceContainerHighest = c.fill,
            outline = c.separator, outlineVariant = c.separator, error = c.error,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.label,
            secondary = c.accent, onSecondary = c.onAccent,
            background = c.ground, onBackground = c.label, surface = c.card, onSurface = c.label,
            surfaceVariant = c.fill, onSurfaceVariant = c.secondary, surfaceContainerLow = c.card,
            surfaceContainer = c.elevated, surfaceContainerHigh = c.elevated, surfaceContainerHighest = c.fill,
            outline = c.separator, outlineVariant = c.separator, error = c.error,
        )
    }
    CompositionLocalProvider(LocalPartyColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
