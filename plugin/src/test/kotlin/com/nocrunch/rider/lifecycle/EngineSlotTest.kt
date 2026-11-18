package com.nocrunch.rider.lifecycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineSlotTest {
    private class Proc(val name: String)
    private class Client(val name: String)

    @Test
    fun one_engine_at_a_time() {
        val slot = EngineSlot<Proc, Client>()
        val p1 = Proc("1")
        assertTrue(slot.canStart)
        assertTrue(slot.begin(p1))
        assertFalse(slot.canStart)
        assertFalse(slot.begin(Proc("2")))
        assertTrue(slot.isCurrent(p1))
    }

    @Test
    fun adopted_client_is_the_current_one() {
        val slot = EngineSlot<Proc, Client>()
        val p = Proc("1")
        slot.begin(p)
        val c = Client("1")
        assertTrue(slot.adopt(p, c))
        assertSame(c, slot.client)
    }

    @Test
    fun superseded_start_cannot_install_its_client() {
        val slot = EngineSlot<Proc, Client>()
        val old = Proc("old")
        slot.begin(old)
        assertSame(old, slot.clear()) // restart
        val new = Proc("new")
        slot.begin(new)
        val newClient = Client("new")
        slot.adopt(new, newClient)

        assertFalse(slot.adopt(old, Client("old")))
        assertFalse(slot.isCurrent(old))
        assertSame(newClient, slot.client)
    }

    @Test
    fun retiring_a_stale_engine_leaves_the_current_one_alone() {
        val slot = EngineSlot<Proc, Client>()
        val old = Proc("old")
        slot.begin(old)
        slot.clear()
        val new = Proc("new")
        slot.begin(new)

        assertFalse(slot.retire(old)) // e.g. the old engine's exit callback or failed startup arriving late
        assertTrue(slot.isCurrent(new))
    }

    @Test
    fun retiring_the_current_engine_allows_a_new_start() {
        val slot = EngineSlot<Proc, Client>()
        val p = Proc("1")
        slot.begin(p)
        slot.adopt(p, Client("1"))

        assertTrue(slot.retire(p))
        assertFalse(slot.retire(p)) // only once: a failed start and its exit callback can't both act on it
        assertNull(slot.client)
        assertFalse(slot.isCurrent(p))
        assertTrue(slot.canStart)
    }

    @Test
    fun nothing_starts_after_dispose() {
        val slot = EngineSlot<Proc, Client>()
        val p = Proc("1")
        slot.begin(p)

        assertSame(p, slot.dispose())
        assertFalse(slot.isCurrent(p))
        assertFalse(slot.adopt(p, Client("1")))
        assertFalse(slot.canStart)
        assertFalse(slot.begin(Proc("2")))
        assertNull(slot.dispose())
        assertEquals(null, slot.client)
    }
}
