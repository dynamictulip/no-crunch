package com.nocrunch.rider.ui

import com.intellij.ui.JBColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaletteTest {
    @Test
    fun pass_and_fail_light_values_are_the_spec_hexes() {
        assertEquals(0x009E73, Palette.PASS_LIGHT.rgb and 0xFFFFFF)
        assertEquals(0xC8283C, Palette.FAIL_LIGHT.rgb and 0xFFFFFF)
    }

    @Test
    fun faded_keeps_the_hue_and_lowers_alpha() {
        val faded = Palette.faded(Palette.PASS_LIGHT)
        assertEquals(Palette.PASS_LIGHT.rgb and 0xFFFFFF, faded.rgb and 0xFFFFFF)
        assertTrue(Math.abs(faded.alpha - (0.55 * 255).toInt()) <= 1, "alpha was ${faded.alpha}")
    }

    @Test
    fun a_faded_theme_aware_colour_follows_the_theme() {
        val faded = Palette.faded(Palette.FAIL)
        try {
            JBColor.setDark(false)
            assertEquals(Palette.FAIL_LIGHT.rgb and 0xFFFFFF, faded.rgb and 0xFFFFFF)
            JBColor.setDark(true)
            assertEquals(0xE04558, faded.rgb and 0xFFFFFF)
        } finally {
            JBColor.setDark(false)
        }
    }
}
