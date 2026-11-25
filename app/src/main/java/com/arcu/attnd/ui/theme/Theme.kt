package com.arcu.attnd.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.arcu.attnd.domain.EventTheme
import com.arcu.attnd.domain.ThemePalette
import com.arcu.attnd.domain.ThemeSlots

private val DarkColorScheme = darkColorScheme(
    primary = AttndPurpleDark,
    onPrimary = AttndOnPurpleDark,
    primaryContainer = AttndPurpleContainerDark,
    onPrimaryContainer = AttndOnPurpleContainerDark,
    inversePrimary = AttndPurple,
    secondary = PurpleGrey80,
    tertiary = Pink80,
)

// Material 3 Expressive's light baseline (its on-container colours are the higher-contrast tone 30),
// with the brand purple on top.
private val LightColorScheme = expressiveLightColorScheme().copy(
    primary = AttndPurple,
    onPrimary = AttndOnPurple,
    primaryContainer = AttndPurpleContainer,
    onPrimaryContainer = AttndOnPurpleContainer,
    inversePrimary = AttndInversePurple,
    secondary = PurpleGrey40,
    tertiary = Pink40,
)

/** API-023 secondary: the soft wash on cards (iOS `themeSoft`); null without an event theme. */
val LocalThemeSoft = staticCompositionLocalOf<Color?> { null }

/** API-023 tertiary: progress bars and highlights (iOS `themeHighlight`); null without an event theme. */
val LocalThemeHighlight = staticCompositionLocalOf<Color?> { null }

/** The progress bar colour: the event theme's tertiary, else Material's default. */
val themedProgressColor: Color
    @Composable get() = LocalThemeHighlight.current ?: ProgressIndicatorDefaults.linearColor

/** Card colours with the event theme's soft wash (iOS `blockCardBackground`), else the elevated default. */
@Composable
fun themedElevatedCardColors(): CardColors =
    LocalThemeSoft.current?.let { CardDefaults.elevatedCardColors(containerColor = it) } ?: CardDefaults.elevatedCardColors()

/**
 * App theme: Material 3 Expressive ([MaterialExpressiveTheme] with the expressive motion scheme, the
 * default expressive shapes). The base is always the brand-purple scheme (no Material You dynamic
 * colour, so the wallpaper never replaces the brand). On top, [ThemePalette.slots]: the event's
 * API-023 [eventTheme] (primary → `primary`, secondary → `secondaryContainer` + the card wash,
 * tertiary → `tertiary` + progress bars, on-colours by the server's WCAG rule), else its
 * `accent_color` ([accentColor], primary only). [screenshot] keeps the brand purple.
 */
@Composable
fun AttndTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    eventTheme: EventTheme? = null,
    accentColor: String? = null,
    screenshot: Boolean = false,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) DarkColorScheme else LightColorScheme
    val slots = remember(eventTheme, accentColor, darkTheme, screenshot) {
        ThemePalette.slots(eventTheme, accentColor, darkTheme, base.surface.toArgb(), base.onSurface.toArgb(), screenshot)
    }
    val colorScheme = remember(base, slots) { base.with(slots) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = Typography,
    ) {
        CompositionLocalProvider(
            LocalThemeSoft provides slots.soft?.let(::Color),
            LocalThemeHighlight provides slots.highlight?.let(::Color),
            content = content,
        )
    }
}

private fun ColorScheme.with(slots: ThemeSlots): ColorScheme {
    if (slots.isEmpty) return this
    fun Int?.or(fallback: Color): Color = this?.let(::Color) ?: fallback
    return copy(
        primary = slots.primary.or(primary),
        onPrimary = slots.onPrimary.or(onPrimary),
        primaryContainer = slots.primaryContainer.or(primaryContainer),
        onPrimaryContainer = slots.onPrimaryContainer.or(onPrimaryContainer),
        secondaryContainer = slots.secondaryContainer.or(secondaryContainer),
        onSecondaryContainer = slots.onSecondaryContainer.or(onSecondaryContainer),
        tertiary = slots.tertiary.or(tertiary),
        onTertiary = slots.onTertiary.or(onTertiary),
        tertiaryContainer = slots.tertiaryContainer.or(tertiaryContainer),
        onTertiaryContainer = slots.onTertiaryContainer.or(onTertiaryContainer),
        surfaceTint = slots.primary.or(surfaceTint),
    )
}
