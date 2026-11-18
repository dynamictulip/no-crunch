package com.nocrunch.rider.view

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

    private fun args(text: String) = NameColouring.splitArgs(text).joinToString(" ") { (t, c) -> "$t:${c ?: "-"}" }

    @Test
    fun theory_rows_give_each_argument_name_and_value_the_next_colour() =
        assertEquals(
            "(:- quantity:0 : :- 10:1 , :- expected:2 : :- 5:0 , :- sku:1 : :- \"ABC-1234\":2 ):-",
            args("(quantity: 10, expected: 5, sku: \"ABC-1234\")"),
        )

    @Test
    fun values_with_commas_inside_quotes_or_braces_stay_whole() =
        assertEquals(
            "(:- scenario:0 : :- \"a, b\":1 , :- product:2 : :- Product { Sku = X, Price = 1 }:0 ):-",
            args("(scenario: \"a, b\", product: Product { Sku = X, Price = 1 })"),
        )

    @Test
    fun unnamed_arguments_each_take_the_next_colour() =
        assertEquals("(:- 100:0 ,:- 10:1 ,:- 90:2 ):-", args("(100,10,90)"))

    @Test
    fun text_that_is_not_an_argument_list_stays_plain() =
        assertEquals("row 1:-", args("row 1"))

    @Test
    fun names_are_added_to_rows_that_only_have_values() {
        assertEquals("(ms: 150)", NameColouring.withNames("(150)", listOf("ms")))
        assertEquals("(price: 100, percent: 10, expected: 90)", NameColouring.withNames("(100,10,90)", listOf("price", "percent", "expected")))
        assertEquals("(quantity: 1)", NameColouring.withNames("(quantity: 1)", listOf("quantity")), "already named")
        assertEquals("(1, 2)", NameColouring.withNames("(1, 2)", listOf("a")), "count mismatch")
    }

    @Test
    fun rows_line_up_with_numbers_right_aligned() =
        assertEquals(
            listOf(
                "(price:  0.05, percent: 50, expected:  0.03)",
                "(price: 19.99, percent: 15, expected: 16.99)",
                "(price:   100, percent:  0, expected:   100)",
            ),
            NameColouring.alignArgs(listOf(
                "(price: 0.05, percent: 50, expected: 0.03)",
                "(price: 19.99, percent: 15, expected: 16.99)",
                "(price: 100, percent: 0, expected: 100)",
            )),
        )

    @Test
    fun text_columns_are_left_aligned() =
        assertEquals(
            listOf("(sku: \"abc-1234\", valid: False)", "(sku: \"ABC1234\",  valid: True)"),
            NameColouring.alignArgs(listOf("(sku: \"abc-1234\", valid: False)", "(sku: \"ABC1234\", valid: True)")),
        )

    @Test
    fun rows_with_different_arguments_are_left_alone() {
        val rows = listOf("(a: 1)", "(b: 22)")
        assertEquals(rows, NameColouring.alignArgs(rows))
    }

    @Test
    fun aligned_rows_still_colour_names_and_values() =
        assertEquals("(:- price:0 :   :- 100:1 ):-", args("(price:   100)"))
}
