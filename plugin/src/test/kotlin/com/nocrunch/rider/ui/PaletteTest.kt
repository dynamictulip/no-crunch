package com.nocrunch.rider.ui

import com.intellij.ui.JBColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

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

    private fun luminance(c: Color): Double {
        fun ch(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = luminance(a).let { x -> luminance(b).let { y -> maxOf(x, y) to minOf(x, y) } }
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun failure_text_is_readable_in_both_themes() {
        try {
            JBColor.setDark(false)
            assertTrue(contrast(Color(Palette.FAIL_TEXT.rgb), Color.WHITE) >= 4.5, "light")
            JBColor.setDark(true)
            assertTrue(contrast(Color(Palette.FAIL_TEXT.rgb), Color(0x1E1F22)) >= 6.0, "dark")
        } finally {
            JBColor.setDark(false)
        }
    }
}
