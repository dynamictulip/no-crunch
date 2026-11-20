package com.nocrunch.rider.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/** A big coloured bar across the top of the tool window, filled by the share of tests with a current result. */
class ProgressBar : JComponent() {
    private var state = BarState(BarColour.NEUTRAL, 0.0, "")

    init {
        border = JBUI.Borders.empty(4, 6)
        font = JBUI.Fonts.label().asBold()
    }

    fun update(s: BarState) {
        if (s == state) return
        state = s
        toolTipText = s.text
        repaint()
    }

    override fun getPreferredSize() = Dimension(super.getPreferredSize().width, JBUI.scale(30))

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val i = insets
            val x = i.left; val y = i.top
            val w = width - i.left - i.right; val h = height - i.top - i.bottom
            val arc = JBUI.scale(6)
            val fill = colour(state.colour)
            g2.color = JBColor(Color(0xE4, 0xE4, 0xE4), Color(0x3A, 0x3D, 0x41))
            g2.fillRoundRect(x, y, w, h, arc, arc)
            g2.color = fill
            g2.fillRoundRect(x, y, (w * state.fraction.coerceIn(0.0, 1.0)).toInt(), h, arc, arc)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = font
            g2.color = if (state.fraction > 0.5) Color.WHITE else UIUtil.getLabelForeground()
            val fm = g2.fontMetrics
            g2.drawString(state.text, x + JBUI.scale(8), y + (h + fm.ascent - fm.descent) / 2)
        } finally {
            g2.dispose()
        }
    }

    private fun colour(c: BarColour): Color = when (c) {
        BarColour.PASSING -> Palette.PASS
        BarColour.FAILING -> Palette.FAIL
        BarColour.RUNNING -> JBColor(Color(0x3D, 0x7E, 0xD8), Color(0x35, 0x6D, 0xBD))
        BarColour.NEUTRAL -> JBColor(Color(0xB0, 0xB0, 0xB0), Color(0x5A, 0x5D, 0x61))
    }
}
