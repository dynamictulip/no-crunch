package com.nocrunch.rider.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.Icon

/**
 * Reads colours off Rider's own icons, so the plugin's reds match the stock failed-test icon in whatever theme is
 * active, without copying a hex that the platform may change. The icon is drawn off-screen and its dominant saturated
 * colour taken (the white `!` and grey edges don't count).
 */
object StockIconColour {
    @Volatile private var cached: Pair<Boolean, Color?>? = null

    /** The red of the stock failed-test icon for the current light or dark theme; null if it can't be read. */
    fun failedTestRed(): Color? {
        val bright = JBColor.isBright()
        cached?.takeIf { it.first == bright }?.let { return it.second }
        val colour = runCatching { of(AllIcons.RunConfigurations.TestError) }.getOrNull()
        cached = bright to colour
        return colour
    }

    fun of(icon: Icon): Color? {
        val w = icon.iconWidth.coerceAtLeast(1)
        val h = icon.iconHeight.coerceAtLeast(1)
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            icon.paintIcon(null, g, 0, 0)
        } finally {
            g.dispose()
        }
        return dominant(image)
    }

    /** The most common colour among opaque, clearly coloured pixels; null when there are none. */
    fun dominant(image: BufferedImage): Color? {
        val counts = HashMap<Int, Int>()
        val hsb = FloatArray(3)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val argb = image.getRGB(x, y)
            if ((argb ushr 24) < 200) continue
            Color.RGBtoHSB((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, hsb)
            if (hsb[1] < 0.35f || hsb[2] < 0.3f) continue
            counts.merge(argb and 0xFFFFFF, 1, Int::plus)
        }
        return counts.maxByOrNull { it.value }?.key?.let { Color(it) }
    }
}
