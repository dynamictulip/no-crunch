package com.nocrunch.rider.ui

import com.intellij.ui.JBColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaletteTest {
    @Test
    fun pass_light_value_is_the_spec_hex() {
        assertEquals(0x009E73, Palette.PASS_LIGHT.rgb and 0xFFFFFF)
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
            for (dark in listOf(false, true)) {
                JBColor.setDark(dark)
                assertEquals(Palette.FAIL.rgb and 0xFFFFFF, faded.rgb and 0xFFFFFF, "dark=$dark")
            }
        } finally {
            JBColor.setDark(false)
        }
    }

    @Test
    fun the_failure_red_is_a_real_red_in_both_themes() {
        try {
            for (dark in listOf(false, true)) {
                JBColor.setDark(dark)
                val c = Palette.FAIL
                assertTrue(c.red > 150 && c.red > c.green + 60 && c.red > c.blue + 40, "dark=$dark was ${Integer.toHexString(c.rgb)}")
            }
        } finally {
            JBColor.setDark(false)
        }
    }
}
