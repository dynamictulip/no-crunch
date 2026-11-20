package com.nocrunch.rider.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MarkerIconTest {
    @Test
    fun icons_are_square() {
        MarkerKind.entries.forEach { kind ->
            val icon = MarkerIcon(kind)
            assertEquals(icon.iconWidth, icon.iconHeight, "$kind")
        }
    }

    @Test
    fun precedence_follows_declaration_order() {
        assertEquals(
            listOf(MarkerKind.CAUSE, MarkerKind.FAIL, MarkerKind.FAIL_STALE, MarkerKind.PASS, MarkerKind.PASS_STALE),
            MarkerKind.entries,
        )
    }
}
