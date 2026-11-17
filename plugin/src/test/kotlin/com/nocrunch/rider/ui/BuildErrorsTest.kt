package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.BuildFinishedDto
import com.nocrunch.rider.engine.TestResultDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BuildErrorsTest {
    private fun t(project: String, method: String) = TestResultDto(
        key = "$project|T|$method", project = project, type = "T", method = method, uid = method, variant = null,
        displayName = method, state = "stale", durationMs = null, message = null, stackTrace = null, filePath = null, line = null,
    )

    private val core = "src/Shop.Core/Shop.Core.csproj"
    private val coreTests = "tests/Shop.Core.Tests/Shop.Core.Tests.csproj"
    private val brokenTests = "tests/Broken.Tests/Broken.Tests.csproj"

    private val projects = TestTreeModel.build(
        listOf(t(coreTests, "a"), t(brokenTests, "b")),
        mapOf(
            core to BuildFinishedDto(core, "failed", "error", null),
            coreTests to BuildFinishedDto(coreTests, "blocked", "", core),
            brokenTests to BuildFinishedDto(brokenTests, "failed", "error", null),
        ),
        emptyList(), false,
    )

    @Test
    fun non_test_projects_with_broken_builds_move_to_build_errors() {
        val (errors, tests) = TreeView.splitBuildErrors(projects)
        assertEquals(listOf(core), errors.map { it.path })
        assertEquals(listOf(brokenTests, coreTests), tests.map { it.path })
    }

    @Test
    fun blocked_projects_name_the_project_that_blocks_them() {
        assertEquals("Shop.Core", projects.single { it.path == coreTests }.blockedByName)
    }
}
