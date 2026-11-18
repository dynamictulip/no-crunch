package com.nocrunch.rider.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NoCrunchSettingsStateTest {
    @Test
    fun test_timeout_defaults_to_30_seconds() {
        assertEquals(30, NoCrunchSettingsState().testTimeoutSeconds)
    }
}
