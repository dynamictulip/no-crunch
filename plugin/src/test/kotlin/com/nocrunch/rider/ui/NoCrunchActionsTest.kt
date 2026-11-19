package com.nocrunch.rider.ui

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class NoCrunchActionsTest {
    @Test
    fun `restart engine and settings stay usable while paused`() {
        assertTrue(NoCrunchActions.enabledWhilePaused(NoCrunchActions.RESTART_ENGINE))
        assertTrue(NoCrunchActions.enabledWhilePaused(NoCrunchActions.SETTINGS))
    }

    @Test
    fun `other toolbar actions grey out while paused`() {
        assertFalse(NoCrunchActions.enabledWhilePaused("Run"))
        assertFalse(NoCrunchActions.enabledWhilePaused(null))
    }
}
