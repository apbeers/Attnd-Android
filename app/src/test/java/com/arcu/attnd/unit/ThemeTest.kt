package com.arcu.attnd.unit

import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.domain.EventTheme
import com.arcu.attnd.domain.LiveTheme
import com.arcu.attnd.domain.ThemeColors
import com.arcu.attnd.domain.ThemeContrast
import com.arcu.attnd.domain.ThemeContrast.INK
import com.arcu.attnd.domain.ThemeContrast.WHITE
import com.arcu.attnd.domain.ThemePalette
import com.arcu.attnd.domain.ThemeSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API-023 in the app (iOS `ThemeColors.swift`, `ComponentTests.testThemeDecoding`,
 * `LiveFollowTests.testActivityTheme…`; the server's `events/tests_theme.py` WCAG cases).
 */
class ThemeTest {
    private val surface = 0xFFFFFFFF.toInt()
    private val onSurface = 0xFF1B1830.toInt()
    private val darkSurface = 0xFF141218.toInt()
    private val darkOnSurface = 0xFFE6E0E9.toInt()

    // The API_V2.md example ("sunset").
    private val sunset = EventTheme(
        light = ThemeColors("#B23A1E", "#F2A65A", "#1F7A8C"),
        dark = ThemeColors("#F4895F", "#7A3B24", "#4FB3C4"),
    )

    private fun c(hex: String) = ThemeContrast.parse(hex)!!

    @Test
    fun contrast_matchesKnownWcagValues() {
        assertEquals(21.0, ThemeContrast.contrast(c("#000000"), c("#FFFFFF")), 0.01)
        assertEquals(1.0, ThemeContrast.contrast(c("#FFFFFF"), c("#FFFFFF")), 0.01)
        assertEquals(4.48, ThemeContrast.contrast(c("#777777"), c("#FFFFFF")), 0.01)
        assertEquals(4.54, ThemeContrast.contrast(c("#767676"), c("#FFFFFF")), 0.01)
        assertEquals(1.0, ThemeContrast.luminance(c("#FFFFFF")), 1e-9)
        assertEquals(0.0, ThemeContrast.luminance(c("#000000")), 1e-9)
    }

    @Test
    fun onColor_isWhiteOnDarkFills_inkOnLightOnes() {
        assertEquals(WHITE, ThemeContrast.onColor(c("#B23A1E"))) // server: a readable primary keeps white
        assertEquals(INK, ThemeContrast.onColor(c("#FFCC00"))) // server: a light fill gets dark text
        assertEquals(WHITE, ThemeContrast.onColor(c("#431F86")))
        assertEquals("#120F22", ThemeContrast.hex(INK))
    }

    @Test
    fun parseAndMix() {
        assertNull(ThemeContrast.parse("#12345"))
        assertNull(ThemeContrast.parse("red"))
        assertEquals("#B23A1E", ThemeContrast.hex(c("#b23a1e")))
        assertEquals("#808080", ThemeContrast.hex(ThemeContrast.mix(c("#FFFFFF"), c("#000000"), 0.5))) // 127.5 → 128 (half to even)
        assertEquals("#FFFFFF", ThemeContrast.hex(ThemeContrast.mix(c("#F2A65A"), surface, 0.0)))
    }

    @Test
    fun theme_setsEveryRole_perMode() {
        val light = ThemePalette.slots(sunset, accentHex = "#00FF00", dark = false, surface = surface, onSurface = onSurface)
        assertEquals("#B23A1E", ThemeContrast.hex(light.primary!!)) // the theme wins over accent_color
        assertEquals(WHITE, light.onPrimary)
        assertEquals("#1F7A8C", ThemeContrast.hex(light.tertiary!!))
        assertEquals("#1F7A8C", ThemeContrast.hex(light.highlight!!))
        // The soft wash = the server's --color-brand-soft (14 % over the surface).
        assertEquals(ThemeContrast.mix(c("#F2A65A"), surface, 0.14), light.soft)
        val dark = ThemePalette.slots(sunset, null, dark = true, surface = darkSurface, onSurface = darkOnSurface)
        assertEquals("#F4895F", ThemeContrast.hex(dark.primary!!))
        assertEquals(INK, dark.onPrimary)
        assertEquals("#4FB3C4", ThemeContrast.hex(dark.highlight!!))
        assertEquals(ThemeContrast.mix(c("#7A3B24"), darkSurface, 0.24), dark.soft)
    }

    @Test
    fun everyContainer_hasReadableText() {
        for ((dark, bg, fg) in listOf(Triple(false, surface, onSurface), Triple(true, darkSurface, darkOnSurface))) {
            val s = ThemePalette.slots(sunset, null, dark, bg, fg)
            listOf(
                s.primary to s.onPrimary,
                s.primaryContainer to s.onPrimaryContainer,
                s.secondaryContainer to s.onSecondaryContainer,
                s.tertiaryContainer to s.onTertiaryContainer,
            ).forEach { (container, on) ->
                assertTrue(ThemeContrast.contrast(container!!, on!!) >= ThemeContrast.TEXT_MIN)
            }
        }
    }

    @Test
    fun missingRolesAndModes_fallBackLeniently() {
        // Only a light secondary: primary stays the default (null = the brand), tertiary too.
        val partial = EventTheme(light = ThemeColors(secondary = "#F2A65A"))
        val light = ThemePalette.slots(partial, null, false, surface, onSurface)
        assertNull(light.primary)
        assertNull(light.highlight)
        assertNotNull(light.soft)
        // A missing role in this mode: accent_color first for primary, then the other mode.
        val noDarkPrimary = EventTheme(light = ThemeColors(primary = "#B23A1E", tertiary = "#1F7A8C"), dark = ThemeColors(secondary = "#7A3B24"))
        assertEquals("#00AA00", ThemeContrast.hex(ThemePalette.slots(noDarkPrimary, "00aa00", true, darkSurface, darkOnSurface).primary!!))
        val dark = ThemePalette.slots(noDarkPrimary, null, true, darkSurface, darkOnSurface)
        assertEquals("#B23A1E", ThemeContrast.hex(dark.primary!!))
        assertEquals("#1F7A8C", ThemeContrast.hex(dark.highlight!!))
        // A theme whose colours didn't decode is no theme (ApiMapper), so only the accent applies.
        val bad = ApiMapper.parseBundle("""{"event":{"id":1,"name":"X","event_code":"X","theme":{"light":{"primary":"#12345"}}}}""")!!.event.theme
        assertEquals(ThemeSlots.NONE, ThemePalette.slots(bad, null, false, surface, onSurface))
    }

    @Test
    fun withoutTheme_accentSetsPrimaryOnly_elseTheBrand() {
        val accent = ThemePalette.slots(null, "#FFCC00", false, surface, onSurface)
        assertEquals(ThemeSlots(primary = c("#FFCC00"), onPrimary = INK), accent)
        assertNull(accent.soft)
        assertEquals(ThemeSlots.NONE, ThemePalette.slots(null, null, false, surface, onSurface))
        assertEquals(ThemeSlots.NONE, ThemePalette.slots(EventTheme(), null, false, surface, onSurface))
        assertEquals(ThemeSlots.NONE, ThemePalette.slots(null, "nope", false, surface, onSurface))
    }

    @Test
    fun screenshotMode_keepsTheBrandPurple() {
        assertEquals(ThemeSlots.NONE, ThemePalette.slots(sunset, "#00FF00", false, surface, onSurface, screenshot = true))
    }

    @Test
    fun liveUpdateAccent_isTheLightPrimary_elseAccent() {
        assertEquals(c("#B23A1E"), LiveTheme.accent(sunset, "#00FF00"))
        assertNull(LiveTheme.accent(EventTheme(dark = ThemeColors(primary = "#F4895F")), null))
        assertEquals(c("#00FF00"), LiveTheme.accent(EventTheme(dark = ThemeColors(primary = "#F4895F")), "#00FF00"))
        assertEquals(c("#0B6E4F"), LiveTheme.accent(EventTheme(light = ThemeColors("#0B6E4F", "#CDEBDD", "#C4471B")), null))
        assertNull(LiveTheme.accent(null, null))
    }
}
