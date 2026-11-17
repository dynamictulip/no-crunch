package com.nocrunch.rider.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CupcakeIconTest {
    @Test
    fun icon_follows_the_bar_colour() {
        assertEquals(CupcakeIcon.Variant.PASS, CupcakeIcon.variantFor(BarColour.PASSING))
        assertEquals(CupcakeIcon.Variant.FAIL, CupcakeIcon.variantFor(BarColour.FAILING))
        assertEquals(CupcakeIcon.Variant.PLAIN, CupcakeIcon.variantFor(BarColour.RUNNING))
        assertEquals(CupcakeIcon.Variant.PLAIN, CupcakeIcon.variantFor(BarColour.NEUTRAL))
    }

    @Test
    fun each_variant_has_light_and_dark_svgs() {
        CupcakeIcon.Variant.entries.forEach { v ->
            listOf(v.path, v.path.replace(".svg", "_dark.svg")).forEach { p ->
                requireNotNull(CupcakeIcon::class.java.getResource(p)) { "missing $p" }
            }
        }
    }
}
