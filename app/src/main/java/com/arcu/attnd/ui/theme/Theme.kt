package com.arcu.attnd.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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

/**
 * App theme: Material 3 Expressive ([MaterialExpressiveTheme] with the expressive motion scheme, the
 * default expressive shapes). The base is always the brand-purple scheme (no Material You dynamic
 * colour, so the wallpaper never replaces the brand). [accentColor] is the event's `accent_color`;
 * when present it replaces the scheme's primary (with a readable onPrimary).
 */
@Composable
fun AttndTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) DarkColorScheme else LightColorScheme
    val colorScheme = if (accentColor != null) {
        base.copy(
            primary = accentColor,
            onPrimary = if (accentColor.luminance() > 0.5f) Color.Black else Color.White,
        )
    } else {
        base
    }

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
        content = content,
    )
}
