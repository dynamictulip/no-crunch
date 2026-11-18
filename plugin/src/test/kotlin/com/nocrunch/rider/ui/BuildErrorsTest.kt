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
    fun test_projects_stay_test_projects_when_view_filters_hide_all_their_tests() {
        val builds = mapOf(
            core to BuildFinishedDto(core, "failed", "error", null),
            brokenTests to BuildFinishedDto(brokenTests, "failed", "error", null),
        )
        val tests = listOf(t(brokenTests, "b"))
        for ((failuresOnly, hideExcluded) in listOf(true to false, false to true)) {
            val rules = if (hideExcluded) listOf(com.nocrunch.rider.engine.ExclusionRuleDto(brokenTests)) else emptyList()
            val (errors, testProjects) = TreeView.splitBuildErrors(
                TestTreeModel.build(tests, builds, rules, failuresOnly, hideExcluded = hideExcluded))
            assertEquals(listOf(core), errors.map { it.path }, "failuresOnly=$failuresOnly hideExcluded=$hideExcluded")
            assertEquals(listOf(brokenTests), testProjects.map { it.path }, "failuresOnly=$failuresOnly hideExcluded=$hideExcluded")
        }
    }

    @Test
    fun blocked_projects_name_the_project_that_blocks_them() {
        assertEquals("Shop.Core", projects.single { it.path == coreTests }.blockedByName)
    }
}
