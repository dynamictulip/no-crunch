package com.nocrunch.rider.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CupcakeIconTest {
    @Test
    fun paused_shows_the_pause_cupcake_whatever_the_results() {
        BarColour.entries.forEach { assertEquals(CupcakeIcon.Variant.PAUSED, CupcakeIcon.variantFor(it, paused = true)) }
    }

    @Test
    fun icon_follows_the_bar_colour() {
        assertEquals(CupcakeIcon.Variant.PASS, CupcakeIcon.variantFor(BarColour.PASSING))
        assertEquals(CupcakeIcon.Variant.FAIL, CupcakeIcon.variantFor(BarColour.FAILING))
        assertEquals(CupcakeIcon.Variant.PLAIN, CupcakeIcon.variantFor(BarColour.RUNNING))
        assertEquals(CupcakeIcon.Variant.PLAIN, CupcakeIcon.variantFor(BarColour.NEUTRAL))
    }

    private val files = CupcakeIcon.Variant.entries.flatMap { v ->
        listOf("", "_dark", "@20x20", "@20x20_dark").map { v.path.replace(".svg", "$it.svg") }
    }

    @Test
    fun each_variant_has_light_dark_and_new_ui_sizes() {
        files.forEach { p -> requireNotNull(CupcakeIcon::class.java.getResource(p)) { "missing $p" } }
    }

    @Test
    fun colours_are_set_on_each_shape_so_the_ide_can_recolour_selected_icons() {
        files.forEach { p ->
            val svg = CupcakeIcon::class.java.getResource(p)!!.readText()
            val root = Regex("<svg[^>]*>").find(svg)!!.value
            kotlin.test.assertFalse(root.contains("stroke=\""), "$p: no stroke on the root element")
            Regex("<path[^>]*>").findAll(svg).forEach { path ->
                kotlin.test.assertTrue(path.value.contains("stroke=\""), "$p: every path sets its own stroke: ${path.value}")
            }
        }
    }
}
