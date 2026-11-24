package com.nocrunch.rider.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContentHashTest {
    /** The same digest the engine's ContentHashTests expect for "a\nb\n". */
    private val abNewline = "911169ddaaf146aff539f58c26c489af3b892dff0fe283c1c264c65ae5aa59a2"

    @Test
    fun of_a_b_newline_is_the_known_digest() {
        assertEquals(abNewline, ContentHash.of("a\nb\n"))
    }

    @Test
    fun crlf_hashes_like_lf() {
        assertEquals(abNewline, ContentHash.of("a\r\nb\n"))
    }
}
