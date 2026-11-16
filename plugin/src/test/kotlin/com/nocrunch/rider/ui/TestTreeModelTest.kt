package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.BuildFinishedDto
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.TestResultDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestTreeModelTest {
    private val unit = "tests/Unit/Unit.csproj"
    private val e2e = "tests/E2E/E2E.csproj"

    private fun t(project: String, type: String?, method: String?, state: String, uid: String = "$type.$method") =
        TestResultDto(
            key = if (type != null && method != null) "$project|$type|$method" else "$project|uid:$uid",
            project = project, type = type, method = method, uid = uid, variant = null,
            displayName = method ?: uid, state = state, durationMs = 1.0, message = null, stackTrace = null, filePath = null, line = null,
        )

    private val tests = listOf(
        t(unit, "A.Calc", "Adds", "passed"),
        t(unit, "A.Calc", "Subtracts", "failed"),
        t(unit, "A.Other", "Works", "passed"),
        t(e2e, "B.Flow", "Checkout", "passed"),
    )

    @Test
    fun groups_by_project_then_class() {
        val tree = TestTreeModel.build(tests, emptyMap(), emptyList(), failuresOnly = false)

        assertEquals(listOf("E2E", "Unit"), tree.map { it.name })
        val unitNode = tree.single { it.path == unit }
        assertEquals(listOf("A.Calc", "A.Other"), unitNode.children.map { it.type })
        assertEquals(listOf("Adds", "Subtracts"), unitNode.children[0].children.map { it.dto.method })
    }

    @Test
    fun parent_exclusion_marks_children_excluded() {
        val tree = TestTreeModel.build(tests, emptyMap(), listOf(ExclusionRuleDto(e2e), ExclusionRuleDto(unit, "A.Other")), false)

        val e2eNode = tree.single { it.path == e2e }
        assertTrue(e2eNode.excluded)
        assertTrue(e2eNode.children.all { c -> c.excluded && c.children.all { it.excluded } })
        val unitNode = tree.single { it.path == unit }
        assertFalse(unitNode.excluded)
        assertTrue(unitNode.children.single { it.type == "A.Other" }.excluded)
        assertFalse(unitNode.children.single { it.type == "A.Calc" }.excluded)
    }

    @Test
    fun method_rule_excludes_single_test() {
        val tree = TestTreeModel.build(tests, emptyMap(), listOf(ExclusionRuleDto(unit, "A.Calc", "Adds")), false)
        val calc = tree.single { it.path == unit }.children.single { it.type == "A.Calc" }
        assertTrue(calc.children.single { it.dto.method == "Adds" }.excluded)
        assertFalse(calc.children.single { it.dto.method == "Subtracts" }.excluded)
        assertFalse(calc.excluded)
    }

    @Test
    fun counts_roll_up() {
        val tree = TestTreeModel.build(tests, emptyMap(), emptyList(), false)
        val unitNode = tree.single { it.path == unit }
        assertEquals(Counts(passed = 2, failed = 1, other = 0), unitNode.counts)
        assertEquals(Counts(passed = 1, failed = 1, other = 0), unitNode.children.single { it.type == "A.Calc" }.counts)
    }

    @Test
    fun failures_only_keeps_failed_and_ancestors() {
        val tree = TestTreeModel.build(tests, emptyMap(), emptyList(), failuresOnly = true)
        assertEquals(listOf(unit), tree.map { it.path })
        assertEquals(listOf("A.Calc"), tree.single().children.map { it.type })
        assertEquals(listOf("Subtracts"), tree.single().children.single().children.map { it.dto.method })
    }

    @Test
    fun build_failed_project_has_status() {
        val builds = mapOf(
            "src/Core/Core.csproj" to BuildFinishedDto("src/Core/Core.csproj", "failed", "error CS1002", null),
            unit to BuildFinishedDto(unit, "blocked", "", "src/Core/Core.csproj"),
            "src/Lib/Lib.csproj" to BuildFinishedDto("src/Lib/Lib.csproj", "succeeded", "", null),
        )
        val tree = TestTreeModel.build(tests, builds, emptyList(), failuresOnly = true)

        assertEquals("failed", tree.single { it.path == "src/Core/Core.csproj" }.buildStatus)
        assertEquals("blocked", tree.single { it.path == unit }.buildStatus)
        assertFalse(tree.any { it.path == "src/Lib/Lib.csproj" }, "healthy non-test projects are not shown")
    }

    @Test
    fun uid_only_tests_grouped_under_unknown_class() {
        val tree = TestTreeModel.build(listOf(t(unit, null, null, "passed", uid = "u-1")), emptyMap(),
            listOf(ExclusionRuleDto(unit, uid = "u-1")), false)
        val cls = tree.single().children.single()
        assertEquals(TestTreeModel.UNKNOWN_CLASS, cls.type)
        assertTrue(cls.children.single().excluded)
    }
}
