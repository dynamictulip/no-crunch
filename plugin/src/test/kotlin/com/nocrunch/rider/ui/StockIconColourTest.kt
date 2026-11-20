package com.nocrunch.rider.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

class StockIconColourTest {
    private val red = Color(0xDB5C5C)

    private fun image(paint: (java.awt.Graphics2D) -> Unit): BufferedImage {
        val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try { paint(g) } finally { g.dispose() }
        return img
    }

    @Test
    fun dominant_colour_is_the_disc_not_the_white_glyph() {
        val img = image { g ->
            g.color = red; g.fillOval(2, 2, 28, 28)
            g.color = Color.WHITE; g.fillRect(14, 8, 4, 12)
        }
        assertEquals(red.rgb and 0xFFFFFF, StockIconColour.dominant(img)!!.rgb and 0xFFFFFF)
    }

    @Test
    fun dominant_colour_ignores_see_through_pixels() {
        val img = image { g ->
            g.color = Color(0, 0, 255, 40); g.fillRect(0, 0, 32, 32)
            g.color = red; g.fillOval(10, 10, 12, 12)
        }
        assertEquals(red.rgb and 0xFFFFFF, StockIconColour.dominant(img)!!.rgb and 0xFFFFFF)
    }

    @Test
    fun a_blank_or_grey_image_has_no_dominant_colour() {
        assertEquals(null, StockIconColour.dominant(image { }))
        assertEquals(null, StockIconColour.dominant(image { g -> g.color = Color.GRAY; g.fillRect(0, 0, 32, 32) }))
    }
}
