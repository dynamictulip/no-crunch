package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.CoverageStatusDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoverageNoticeTest {
    @Test
    fun unavailable_status_message_names_the_install_command() {
        val engineMessage = "Line coverage is off: dotnet-coverage 18.0 or newer was not found. Install it with: dotnet tool install --global dotnet-coverage"

        assertEquals(engineMessage, CoverageNotice.text(CoverageStatusDto(false, engineMessage)))
    }

    @Test
    fun a_status_without_a_message_still_says_how_to_install() {
        val text = CoverageNotice.text(CoverageStatusDto(false, null))!!

        assertTrue(text.contains("dotnet tool install --global dotnet-coverage"), text)
    }

    @Test
    fun available_status_shows_nothing() {
        assertNull(CoverageNotice.text(CoverageStatusDto(true, null)))
        assertNull(CoverageNotice.text(null))
    }
}
