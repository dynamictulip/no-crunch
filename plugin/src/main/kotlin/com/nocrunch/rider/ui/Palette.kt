package com.nocrunch.rider.ui

import com.intellij.ui.JBColor
import com.intellij.ui.ColorUtil
import java.awt.Color

/**
 * The plugin's pass and fail colours, used by the editor markers, progress bar and test tree so they always agree.
 * A bluish green and a crimson red: they stay separable under the common types of colour blindness, though close
 * in lightness, so markers also differ by shape.
 */
object Palette {
    val PASS_LIGHT = Color(0x009E73)
    val FAIL_LIGHT = Color(0xC8283C)

    private val PASS_DARK = Color(0x2DBE96)
    private val FAIL_DARK = Color(0xE04558)

    val PASS: JBColor = JBColor(PASS_LIGHT, PASS_DARK)
    val FAIL: JBColor = JBColor(FAIL_LIGHT, FAIL_DARK)

    /** The failure red for text: the same crimson, lighter in the dark theme where [FAIL] is too dim to read as text. */
    val FAIL_TEXT: JBColor = JBColor(FAIL_LIGHT, Color(0xFF7A88))

    /** Same colour, see-through: for results from an earlier run (stale). */
    fun faded(c: Color): Color = ColorUtil.withAlpha(c, 0.55)
}
