package com.nocrunch.rider.ui

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.Icon

/**
 * A gutter marker. The shape says what happened (circle passing, diamond failing, cross the cause of a failure, dash
 * not covered) and hollow (or, for the dash, faded) means the result is stale, so the meaning never rests on colour alone. Drawn in code so it scales with the display.
 */
class MarkerIcon(private val kind: MarkerKind) : Icon {
    private val size get() = JBUI.scale(SIZE)

    override fun getIconWidth() = size
    override fun getIconHeight() = size

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.translate(x, y)
            val u = size / SIZE.toDouble()
            g2.color = when (kind) {
                MarkerKind.PASS, MarkerKind.PASS_STALE -> Palette.PASS
                MarkerKind.NOT_COVERED -> Palette.NOT_COVERED
                MarkerKind.NOT_COVERED_STALE -> Palette.NOT_COVERED_STALE
                else -> Palette.FAIL
            }
            when (kind) {
                MarkerKind.PASS -> g2.fill(java.awt.geom.Ellipse2D.Double(3.5 * u, 3.5 * u, 7 * u, 7 * u))
                MarkerKind.PASS_STALE -> {
                    g2.stroke = BasicStroke((1.5 * u).toFloat())
                    g2.draw(java.awt.geom.Ellipse2D.Double(4.25 * u, 4.25 * u, 5.5 * u, 5.5 * u))
                }
                MarkerKind.FAIL -> g2.fill(diamond(u, 1.5))
                MarkerKind.FAIL_STALE -> {
                    g2.stroke = BasicStroke((1.5 * u).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.draw(diamond(u, 2.25))
                }
                MarkerKind.NOT_COVERED, MarkerKind.NOT_COVERED_STALE ->
                    g2.fill(java.awt.geom.RoundRectangle2D.Double(2.5 * u, 6 * u, 9 * u, 2 * u, 2 * u, 2 * u))
                MarkerKind.CAUSE -> {
                    g2.stroke = BasicStroke((3 * u).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.draw(java.awt.geom.Line2D.Double(3 * u, 3 * u, 11 * u, 11 * u))
                    g2.draw(java.awt.geom.Line2D.Double(11 * u, 3 * u, 3 * u, 11 * u))
                }
            }
        } finally {
            g2.dispose()
        }
    }

    private fun diamond(u: Double, inset: Double) = Path2D.Double().apply {
        moveTo(7 * u, inset * u)
        lineTo((14 - inset) * u, 7 * u)
        lineTo(7 * u, (14 - inset) * u)
        lineTo(inset * u, 7 * u)
        closePath()
    }

    private companion object {
        const val SIZE = 14
    }
}
