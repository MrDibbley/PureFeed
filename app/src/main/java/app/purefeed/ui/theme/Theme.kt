package app.purefeed.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * PureFeed's own palette (from the redesign mock-up): slate ground, sea-green accent,
 * amber badges. Deliberately not Facebook's look. Follows the system light/dark setting.
 */
@Immutable
data class PureFeedColors(
    val ground: Color,      // screen background, top bar
    val surface: Color,     // cards, inactive chips
    val bar: Color,         // bottom bar
    val line: Color,        // dividers, chip borders
    val text: Color,
    val muted: Color,       // secondary text, inactive icons
    val accent: Color,      // active chip, active tab, logo
    val onAccent: Color,
    val accentSoft: Color,  // pill behind the active tab icon
    val activeTab: Color,   // active tab icon/label (readable on the bar)
    val badge: Color,
    val onBadge: Color,
)

private val Dark = PureFeedColors(
    ground = Color(0xFF0E1416), surface = Color(0xFF172024), bar = Color(0xFF111A1D),
    line = Color(0xFF263338), text = Color(0xFFE8EEF0), muted = Color(0xFF9AABB2),
    accent = Color(0xFF3CC6A8), onAccent = Color(0xFF06231C), accentSoft = Color(0xFF173A35),
    activeTab = Color(0xFF3CC6A8), badge = Color(0xFFF2B04C), onBadge = Color(0xFF2A1A00),
)

private val Light = PureFeedColors(
    ground = Color(0xFFF2F5F4), surface = Color(0xFFFFFFFF), bar = Color(0xFFFFFFFF),
    line = Color(0xFFDCE3E0), text = Color(0xFF13201C), muted = Color(0xFF54635E),
    accent = Color(0xFF0E8C73), onAccent = Color(0xFFFFFFFF), accentSoft = Color(0xFFD7EFE9),
    activeTab = Color(0xFF0B7A64), badge = Color(0xFFF2B04C), onBadge = Color(0xFF2A1A00),
)

val LocalPureFeedColors = staticCompositionLocalOf { Dark }

@Composable
fun PureFeedTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) Dark else Light
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft,
            background = c.ground, onBackground = c.text, surface = c.ground, onSurface = c.text,
            surfaceVariant = c.surface, onSurfaceVariant = c.muted, outline = c.line, outlineVariant = c.line,
            surfaceContainer = c.surface, surfaceContainerHigh = c.surface, tertiary = c.badge, onTertiary = c.onBadge,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft,
            background = c.ground, onBackground = c.text, surface = c.ground, onSurface = c.text,
            surfaceVariant = c.surface, onSurfaceVariant = c.muted, outline = c.line, outlineVariant = c.line,
            surfaceContainer = c.surface, surfaceContainerHigh = c.surface, tertiary = c.badge, onTertiary = c.onBadge,
        )
    }
    CompositionLocalProvider(LocalPureFeedColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
