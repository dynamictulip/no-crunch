package com.nocrunch.rider.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NaturalOrderTest {
    @Test
    fun numbers_compare_by_value() =
        assertEquals(listOf("a", "a1", "a2", "a10"), listOf("a10", "a2", "a", "a1").sortedWith(NaturalOrder))

    @Test
    fun data_rows_sort_by_their_values() =
        assertEquals(
            listOf("(ms: 25)", "(ms: 75)", "(ms: 150)", "(ms: 300)", "(ms: 600)"),
            listOf("(ms: 150)", "(ms: 25)", "(ms: 300)", "(ms: 600)", "(ms: 75)").sortedWith(NaturalOrder),
        )

    @Test
    fun letters_ignore_case() =
        assertEquals(listOf("alpha", "Beta", "gamma"), listOf("gamma", "Beta", "alpha").sortedWith(NaturalOrder))
}
