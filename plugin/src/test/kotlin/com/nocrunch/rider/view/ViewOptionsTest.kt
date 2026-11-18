package com.nocrunch.rider.view

import com.nocrunch.rider.settings.NoCrunchSettingsState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The view options are saved in the workspace file by enum name, so existing settings must keep loading. */
class ViewOptionsTest {
    @Test
    fun saved_names_are_unchanged() {
        assertEquals(listOf("CLASSES", "NAMESPACES", "FILES"), ViewMode.entries.map { it.name })
        assertEquals(listOf("NAME", "STATUS", "DURATION"), SortMode.entries.map { it.name })
        assertEquals(listOf("OFF", "SNAKE", "PASCAL"), NameColouring.entries.map { it.name })
    }

    @Test
    fun saved_names_load_as_their_option() {
        ViewMode.entries.forEach { assertEquals(it, ViewMode.fromSetting(it.name)) }
        SortMode.entries.forEach { assertEquals(it, SortMode.fromSetting(it.name)) }
        NameColouring.entries.forEach { assertEquals(it, NameColouring.fromSetting(it.name)) }
    }

    @Test
    fun missing_or_unknown_names_load_as_the_default() {
        assertEquals(ViewMode.CLASSES, ViewMode.fromSetting(null))
        assertEquals(ViewMode.CLASSES, ViewMode.fromSetting("TREE"))
        assertEquals(SortMode.NAME, SortMode.fromSetting(null))
        assertEquals(SortMode.NAME, SortMode.fromSetting("SIZE"))
        assertEquals(NameColouring.OFF, NameColouring.fromSetting(null))
        assertEquals(NameColouring.OFF, NameColouring.fromSetting("RAINBOW"))
    }

    @Test
    fun new_settings_start_on_the_defaults() {
        val state = NoCrunchSettingsState()
        assertEquals("CLASSES", state.viewMode)
        assertEquals("NAME", state.sortMode)
        assertEquals("OFF", state.nameColouring)
    }
}
