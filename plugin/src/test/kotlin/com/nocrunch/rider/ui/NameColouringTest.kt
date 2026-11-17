package com.nocrunch.rider.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NameColouringTest {
    private fun show(name: String, mode: NameColouring) =
        NameColouring.split(name, mode).joinToString(" ") { (text, colour) -> "$text:${colour ?: "-"}" }

    @Test
    fun off_leaves_the_name_whole() =
        assertEquals("Total_includes_tax:-", show("Total_includes_tax", NameColouring.OFF))

    @Test
    fun snake_case_cycles_three_colours_and_leaves_underscores_plain() =
        assertEquals(
            "rounds:0 _:- half:1 _:- up:2 _:- to:0 _:- pence:1",
            show("rounds_half_up_to_pence", NameColouring.SNAKE),
        )

    @Test
    fun snake_case_with_no_underscores_is_one_part() =
        assertEquals("TotalIncludesTax:0", show("TotalIncludesTax", NameColouring.SNAKE))

    @Test
    fun pascal_case_cycles_three_colours_per_word() =
        assertEquals(
            "Rounds:0 Half:1 Up:2 To:0 Pence:1",
            show("RoundsHalfUpToPence", NameColouring.PASCAL),
        )

    @Test
    fun pascal_case_keeps_acronyms_and_digits_together() =
        assertEquals(
            "Get:0 HTTP:1 Response:2 Returns:0 404:1",
            show("GetHTTPResponseReturns404", NameColouring.PASCAL),
        )

    @Test
    fun pascal_case_also_breaks_on_underscores() =
        assertEquals(
            "Total:0 _:- When:1 Empty:2 _:- Is:0 Zero:1",
            show("Total_WhenEmpty_IsZero", NameColouring.PASCAL),
        )

    @Test
    fun pascal_case_starting_lower_case() =
        assertEquals("total:0 Is:1 Zero:2", show("totalIsZero", NameColouring.PASCAL))

    @Test
    fun data_arguments_get_a_colour_per_value() =
        assertEquals(
            "off:0  (:- 100:1 ,:- 10:2 ,:- 90:0 ):-",
            show("off (100,10,90)", NameColouring.SNAKE),
        )

    @Test
    fun dots_and_slashes_separate_parts_like_underscores() {
        assertEquals("Shop:0 .:- Catalog:1 .:- Tests:2", show("Shop.Catalog.Tests", NameColouring.SNAKE))
        assertEquals("Pricing:0 /:- Rules:1 /:- Bulk:2 Discount:0", show("Pricing/Rules/BulkDiscount", NameColouring.PASCAL))
    }

    @Test
    fun digits_inside_an_acronym_stay_with_it() {
        assertEquals("Shop:0 .:- E2E:1 .:- Tests:2", show("Shop.E2E.Tests", NameColouring.PASCAL))
        assertEquals("E2E:0 Checkout:1 Tests:2", show("E2ECheckoutTests", NameColouring.PASCAL))
        assertEquals("HTTP2:0 Request:1", show("HTTP2Request", NameColouring.PASCAL))
        assertEquals("Get:0 2:1 Items:2", show("Get2Items", NameColouring.PASCAL))
    }
}
