package com.arcu.attnd.domain

import kotlin.math.pow
import kotlin.math.round

/**
 * API-023 colour themes in the app (iOS `ThemeColors.swift` / `LiveThemeMapping`, the server's
 * `events/theme.py` maths). Colours are ARGB `Int`s (opaque), so this stays pure Kotlin.
 *
 * - **primary** → Material's `primary` (the tint: buttons, selected items, links), instead of
 *   `accent_color`; its on-colour is white or near-black ink, whichever the server's WCAG rule
 *   picks (white when ≥ 4.5:1, else ink when ≥ 4.5:1, else the higher one).
 * - **secondary** → soft surfaces: a light wash on cards ([ThemeSlots.soft]) and
 *   `secondaryContainer` (navigation indicators, selected chips).
 * - **tertiary** → highlights: progress bars ([ThemeSlots.highlight]) and `tertiary`.
 *
 * Fallbacks per role, leniently (like iOS `ThemePalette.dynamic`): primary = this mode's, else
 * `accent_color`, else the other mode's, else the brand purple (null = keep the base scheme);
 * secondary / tertiary = this mode's, else the other mode's, else the default. Screenshot mode
 * keeps the brand purple (no theme, no accent).
 */
object ThemeContrast {
    /** White and the server's darkest text (`INK`): the two on-colours a fill may get. */
    const val WHITE: Int = 0xFFFFFFFF.toInt()
    const val INK: Int = 0xFF120F22.toInt()
    const val TEXT_MIN = 4.5

    /** "#RRGGBB" → opaque ARGB, else null. */
    fun parse(hex: String?): Int? = ThemeColors.hex(hex)?.let { (0xFF000000L or it.substring(1).toLong(16)).toInt() }

    fun hex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

    private fun channel(color: Int, shift: Int): Int = (color shr shift) and 0xFF

    /** WCAG 2.2 relative luminance (sRGB). */
    fun luminance(color: Int): Double {
        fun lin(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(channel(color, 16)) + 0.7152 * lin(channel(color, 8)) + 0.0722 * lin(channel(color, 0))
    }

    /** WCAG contrast ratio, 1 … 21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [weight] of [a] over [b] (0 = b, 1 = a), rounded half to even like the server. */
    fun mix(a: Int, b: Int, weight: Double): Int {
        fun part(shift: Int): Int =
            round(channel(a, shift) * weight + channel(b, shift) * (1 - weight)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (part(16) shl 16) or (part(8) shl 8) or part(0)
    }

    /** Text on a filled [fill] (the server's `--color-on-brand` rule). */
    fun onColor(fill: Int): Int = when {
        contrast(fill, WHITE) >= TEXT_MIN -> WHITE
        contrast(fill, INK) >= TEXT_MIN -> INK
        contrast(fill, WHITE) >= contrast(fill, INK) -> WHITE
        else -> INK
    }

    /** [preferred] (the scheme's on-surface) when readable on [background], else [onColor]. */
    fun readableOn(background: Int, preferred: Int): Int =
        if (contrast(preferred, background) >= TEXT_MIN) preferred else onColor(background)
}

/** The scheme colours a theme (or `accent_color`) sets; null = keep the base scheme's. */
data class ThemeSlots(
    val primary: Int? = null,
    val onPrimary: Int? = null,
    val primaryContainer: Int? = null,
    val onPrimaryContainer: Int? = null,
    val secondaryContainer: Int? = null,
    val onSecondaryContainer: Int? = null,
    val tertiary: Int? = null,
    val onTertiary: Int? = null,
    val tertiaryContainer: Int? = null,
    val onTertiaryContainer: Int? = null,
    /** The secondary wash on cards (iOS `themeSoft`); null without a theme. */
    val soft: Int? = null,
    /** Progress bars and highlights (iOS `themeHighlight`); null without a theme. */
    val highlight: Int? = null,
) {
    val isEmpty: Boolean get() = this == NONE

    companion object {
        val NONE = ThemeSlots()
    }
}

object ThemePalette {
    // The server's mixes over the surface: the soft wash (`--color-brand-soft`), the stronger
    // line (`--color-brand-line`, used for containers that must read as selected).
    private fun softWeight(dark: Boolean) = if (dark) 0.24 else 0.14
    private fun lineWeight(dark: Boolean) = if (dark) 0.45 else 0.34
    private fun containerWeight(dark: Boolean) = if (dark) 0.32 else 0.18

    /** `accent_color` as an opaque colour (it may come as "RRGGBB" or "#AARRGGBB"), else null. */
    fun accent(accentHex: String?): Int? = DisplayConfig.parseHexColor(accentHex)?.let { (it or 0xFF000000L).toInt() }

    /** The primary for a mode: the theme's, else `accent_color`, else the other mode's (iOS order). */
    fun primary(theme: EventTheme?, accentHex: String?, dark: Boolean): Int? =
        ThemeContrast.parse(theme?.colors(dark)?.primary) ?: accent(accentHex) ?: ThemeContrast.parse(theme?.colors(!dark)?.primary)

    fun secondaryHex(theme: EventTheme?, dark: Boolean): String? =
        theme?.colors(dark)?.secondary ?: theme?.colors(!dark)?.secondary

    fun tertiaryHex(theme: EventTheme?, dark: Boolean): String? =
        theme?.colors(dark)?.tertiary ?: theme?.colors(!dark)?.tertiary

    /**
     * The slots for one appearance. [surface] / [onSurface] are the base scheme's (containers are
     * mixed over the surface; their text stays on-surface when readable). Without a theme only
     * `accent_color` applies, to `primary` / `onPrimary` as before.
     */
    fun slots(
        theme: EventTheme?,
        accentHex: String?,
        dark: Boolean,
        surface: Int,
        onSurface: Int,
        screenshot: Boolean = false,
    ): ThemeSlots {
        if (screenshot) return ThemeSlots.NONE
        val themed = theme?.takeUnless { it.isEmpty }
        val primary = primary(themed, accentHex, dark)
        if (themed == null) {
            return if (primary == null) ThemeSlots.NONE else ThemeSlots(primary = primary, onPrimary = ThemeContrast.onColor(primary))
        }
        val secondary = ThemeContrast.parse(secondaryHex(themed, dark))
        val tertiary = ThemeContrast.parse(tertiaryHex(themed, dark))
        val primaryContainer = primary?.let { ThemeContrast.mix(it, surface, containerWeight(dark)) }
        val secondaryContainer = secondary?.let { ThemeContrast.mix(it, surface, lineWeight(dark)) }
        val tertiaryContainer = tertiary?.let { ThemeContrast.mix(it, surface, containerWeight(dark)) }
        return ThemeSlots(
            primary = primary,
            onPrimary = primary?.let(ThemeContrast::onColor),
            primaryContainer = primaryContainer,
            onPrimaryContainer = primaryContainer?.let { ThemeContrast.readableOn(it, onSurface) },
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = secondaryContainer?.let { ThemeContrast.readableOn(it, onSurface) },
            tertiary = tertiary,
            onTertiary = tertiary?.let(ThemeContrast::onColor),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = tertiaryContainer?.let { ThemeContrast.readableOn(it, onSurface) },
            soft = secondary?.let { ThemeContrast.mix(it, surface, softWeight(dark)) },
            highlight = tertiary,
        )
    }
}

/** The Live Update's colours (iOS `liveActivityAccentHex`): urgent stays red. */
object LiveTheme {
    /** The accent: the theme's light primary, else `accent_color`; null = the brand purple. */
    fun accent(theme: EventTheme?, accentHex: String?): Int? =
        ThemeContrast.parse(theme?.light?.primary) ?: ThemePalette.accent(accentHex)
}
