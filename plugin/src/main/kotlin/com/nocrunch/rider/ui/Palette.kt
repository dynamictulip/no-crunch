package com.nocrunch.rider.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * The plugin's pass and fail colours, used by the editor markers, progress bar and test tree so they always agree.
 * Fail is the stock failed-test icon's red, read from the icon so it follows the theme; pass is a bluish green that
 * stays separable from it under the common types of colour blindness, though close in lightness, so markers also
 * differ by shape.
 */
object Palette {
    val PASS_LIGHT = Color(0x009E73)
    private val PASS_DARK = Color(0x2DBE96)

    val PASS: JBColor = JBColor(PASS_LIGHT, PASS_DARK)

    /** Close to the stock red; only used if the icon's colour can't be read. */
    private val FAIL_FALLBACK = JBColor(Color(0xDB5860), Color(0xDB5C5C))

    /** The stock failed-test icon's red, looked up each time it is used so a theme change takes effect. */
    val FAIL: JBColor = JBColor.lazy { StockIconColour.failedTestRed() ?: FAIL_FALLBACK }

    /** Lines no test covers: a neutral near-black (light theme) or near-white (dark theme), so it is the shape (a dash) that says it, not a hue. */
    val NOT_COVERED: JBColor = JBColor(Color(0x2B2D30), Color(0xE6E8EB))

    /** Same colour, see-through: for results from an earlier run (stale). */
    fun faded(c: Color): Color = ColorUtil.withAlpha(c, 0.55)

    /** [faded] for a theme-aware colour: stays theme-aware. */
    fun faded(c: JBColor): JBColor = JBColor.lazy { ColorUtil.withAlpha(c, 0.55) }

    /** [NOT_COVERED] for a line whose coverage is from before the latest edits. */
    val NOT_COVERED_STALE: JBColor = faded(NOT_COVERED)
}
