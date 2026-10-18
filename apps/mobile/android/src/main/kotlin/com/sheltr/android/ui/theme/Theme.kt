package com.sheltr.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.sheltr.shared.settings.TextScale

/** Which palette [SheltrTheme] renders. A plain [Boolean] parameter on [SheltrTheme] maps to one of these. */
enum class ContrastMode { NORMAL, HIGH }

private val NormalColors = lightColorScheme()

/**
 * Maximizes the contrast between text and its background: pure black/white instead of Material3's default
 * mid-tone surfaces, which can wash out in direct sunlight or for low-vision users -- the scenario this mode
 * exists for in a disaster-prep app.
 */
private val HighContrastColors = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    secondary = Color.Black,
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    error = Color.Black,
    onError = Color.White,
    // Material3's default lightColorScheme() leaves these at mid-tone gray values even when the roles above are
    // overridden -- Cards (CardDefaults' container), the filled TextField's container, and the Switch's track
    // all read one of these roles, not `surface`/`background` directly, so without this they stayed visibly
    // gray/washed-out under "high contrast" (the bug this finding reports). Pushed to the same pure black/white
    // as every other role above, consistent with this mode's stated goal.
    surfaceVariant = Color.White,
    onSurfaceVariant = Color.Black,
    outline = Color.Black,
    outlineVariant = Color.Black,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
)

/**
 * Wraps Material3's [MaterialTheme] with the normal or [ContrastMode.HIGH] palette, and overrides
 * [LocalDensity] so text scales by [TextScale.multiplier] multiplied with -- not replacing -- the OS font scale
 * already in [LocalDensity.current].
 */
@Composable
fun SheltrTheme(
    highContrast: Boolean,
    textScale: TextScale,
    content: @Composable () -> Unit,
) {
    val contrastMode = if (highContrast) ContrastMode.HIGH else ContrastMode.NORMAL
    val colorScheme = when (contrastMode) {
        ContrastMode.NORMAL -> NormalColors
        ContrastMode.HIGH -> HighContrastColors
    }

    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density,
        fontScale = baseDensity.fontScale * textScale.multiplier,
    )

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
