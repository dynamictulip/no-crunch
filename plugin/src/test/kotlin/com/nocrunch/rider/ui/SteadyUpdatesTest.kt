package com.nocrunch.rider.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SteadyUpdatesTest {
    @Test
    fun a_steady_stream_of_results_still_updates_the_ui_and_ends_on_the_latest() = runBlocking {
        // Results every 10 ms for half a second (never a 100 ms pause), then quiet.
        val updates = flow {
            repeat(50) { emit(it); delay(10) }
            delay(400)
        }.atSteadyRate().toList()

        assertTrue(updates.size >= 3, "updates while results keep coming: $updates")
        assertEquals(49, updates.last())
    }
}
