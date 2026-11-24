package com.nocrunch.rider

import com.nocrunch.rider.engine.CoverageFileDto
import com.nocrunch.rider.engine.CoverageStatusDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CoverageStateTest {
    private fun file(path: String) = CoverageFileDto(path, "h", false, emptyList(), listOf(1), emptyList())

    @Test
    fun each_update_replaces_the_whole_set() {
        val state = CoverageState()

        state.replaceFiles(listOf(file("/a.cs"), file("/b.cs")))
        state.replaceFiles(listOf(file("/b.cs")))

        assertEquals(setOf("/b.cs"), state.files.value.keys)
    }

    @Test
    fun reset_forgets_files_and_status_so_dots_do_not_outlive_the_engine_that_sent_them() {
        val state = CoverageState()
        state.replaceFiles(listOf(file("/a.cs")))
        state.setStatus(CoverageStatusDto(false, "off"))

        state.reset()

        assertEquals(emptyMap<String, CoverageFileDto>(), state.files.value)
        assertNull(state.status.value)
    }
}
