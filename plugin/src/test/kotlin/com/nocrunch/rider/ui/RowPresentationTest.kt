package com.nocrunch.rider.ui

import com.nocrunch.rider.ProjectBuild
import com.nocrunch.rider.engine.BuildStatus
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.RunFinishedDto
import com.nocrunch.rider.engine.RunKind
import com.nocrunch.rider.engine.RunStatus
import com.nocrunch.rider.engine.TestResultDto
import com.nocrunch.rider.engine.TestState
import com.nocrunch.rider.view.GroupLevel
import com.nocrunch.rider.view.Grouping
import com.nocrunch.rider.view.SortMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RowPresentationTest {
    private val unit = "tests/Unit/Unit.csproj"

    private fun t(method: String, state: TestState, type: String = "Shop.Unit.Calc", ms: Double? = 12.0, last: TestState? = null,
                  runKind: RunKind = RunKind.AUTO, variant: String? = null, theory: String? = null) = TestResultDto(
        key = listOfNotNull(unit, type, method, variant).joinToString("|"), project = unit, type = type, method = method,
        uid = "$type.$method", variant = variant, displayName = variant ?: method, state = state, durationMs = ms, message = null,
        stackTrace = null, filePath = "/repo/tests/Unit/Calc.cs", line = 10, runKind = runKind, lastOutcome = last, theoryKey = theory,
    )

    private fun model(vararg tests: TestResultDto, rules: List<ExclusionRuleDto> = emptyList(),
                      builds: Map<String, ProjectBuild> = emptyMap(), runs: Map<String, RunFinishedDto> = emptyMap()) =
        TestTreeModel.buildAll(tests.toList(), builds, rules, runs)

    private fun test(vararg tests: TestResultDto, rules: List<ExclusionRuleDto> = emptyList()): TestNode =
        model(*tests, rules = rules).single().children.single().children.first()

    private fun look(o: Any, editing: Boolean = false, anyFailures: Boolean = false) = RowPresentation.of(o, editing, anyFailures)!!

    /** Stats as text, each segment tagged with its style. */
    private fun RowLook.statsText() = stats.joinToString("") { "${it.text}<${it.style}>" }

    @Test
    fun a_failing_test_shows_the_failure_icon_and_a_red_name() {
        val l = look(test(t("Adds", TestState.FAILED)))
        assertEquals(RowIcon(StatusIcon.FAILED, kind = null), l.icon)
        assertEquals("Adds", l.name)
        assertEquals(NameStyle.FAILED, l.nameStyle)
        assertEquals("  12 ms<GREY>", l.statsText())
    }

    @Test
    fun a_failure_from_an_earlier_run_is_faded_and_says_why() {
        val l = look(test(t("Adds", TestState.STALE, last = TestState.FAILED)))
        assertEquals(RowIcon(StatusIcon.FAILED, kind = null, faded = true), l.icon)
        assertEquals(NameStyle.STALE_FAILED, l.nameStyle)
        assertEquals("  12 ms<GREY>  stale<GREY_ITALIC>", l.statsText())
    }

    @Test
    fun a_test_running_for_the_first_time_shows_progress() {
        val l = look(test(t("Adds", TestState.RUNNING, ms = null)))
        assertEquals(RowIcon(StatusIcon.IN_PROGRESS, kind = null), l.icon)
        assertEquals(NameStyle.TEST, l.nameStyle)
        assertEquals("  running…<GREY_ITALIC>", l.statsText())
    }

    @Test
    fun a_test_with_no_result_shows_what_it_is() {
        val l = look(test(t("Adds", TestState.DISCOVERED, ms = null)))
        assertEquals(RowIcon(null, KindIcon.TEST), l.icon)
        assertEquals("  discovered<GREY_ITALIC>", l.statsText())
    }

    @Test
    fun a_passing_test_shows_no_run_time_until_it_has_an_outcome() {
        assertEquals("  12 ms<GREY>", look(test(t("Adds", TestState.PASSED))).statsText())
        assertEquals("  queued<GREY_ITALIC>", look(test(t("Adds", TestState.QUEUED))).statsText())
    }

    @Test
    fun an_excluded_test_is_grey_and_says_so_unless_choosing_tests() {
        val node = test(t("Adds", TestState.EXCLUDED, ms = null), rules = listOf(ExclusionRuleDto(unit, "Shop.Unit.Calc", "Adds")))
        val l = look(node)
        assertEquals(RowIcon(StatusIcon.EXCLUDED, kind = null), l.icon)
        assertEquals(NameStyle.GREY, l.nameStyle)
        assertEquals("  stale<GREY_ITALIC>  (excluded)<GREY_ITALIC>", l.statsText())
        assertEquals("  stale<GREY_ITALIC>", look(node, editing = true).statsText(), "the checkbox says it instead")
    }

    @Test
    fun a_test_in_an_excluded_class_does_not_repeat_excluded() {
        val node = test(t("Adds", TestState.EXCLUDED, ms = null), rules = listOf(ExclusionRuleDto(unit, "Shop.Unit.Calc")))
        assertEquals("  stale<GREY_ITALIC>", look(node).statsText())
    }

    @Test
    fun an_excluded_test_run_by_hand_shows_its_result_and_the_manual_run() {
        val node = test(t("Adds", TestState.PASSED, runKind = RunKind.MANUAL), rules = listOf(ExclusionRuleDto(unit, "Shop.Unit.Calc", "Adds")))
        val l = look(node)
        assertEquals(RowIcon(StatusIcon.EXCLUDED, kind = null), l.icon)
        assertEquals(NameStyle.TEST, l.nameStyle, "a current manual result isn't greyed out")
        assertEquals("   ✔<PASSED_BOLD>  12 ms<GREY>  ▸ manual run<MANUAL>", l.statsText())

        val failedStale = test(t("Adds", TestState.STALE, runKind = RunKind.MANUAL, last = TestState.FAILED),
            rules = listOf(ExclusionRuleDto(unit, "Shop.Unit.Calc", "Adds")))
        assertEquals(NameStyle.GREY, look(failedStale).nameStyle)
        assertEquals("   ✖<GREY>  12 ms<GREY>  stale<GREY_ITALIC>  ▸ manual run<MANUAL>", look(failedStale).statsText())
    }

    @Test
    fun a_theory_shows_counts_and_its_rows_show_arguments() {
        val theoryKey = "$unit|Shop.Unit.Calc|Adds"
        val theory = test(
            t("Adds", TestState.FAILED, runKind = RunKind.MANUAL),
            t("Adds", TestState.FAILED, variant = "Adds(1)", theory = theoryKey, runKind = RunKind.MANUAL),
            t("Adds", TestState.PASSED, variant = "Adds(2)", theory = theoryKey, runKind = RunKind.MANUAL),
        )
        val l = look(theory, anyFailures = true)
        assertEquals(RowIcon(StatusIcon.FAILED, KindIcon.TEST_SET), l.icon)
        assertEquals("   ✔ 1<GREY>  <GREY>✖ 1<FAILED_BOLD>  ○ 0<GREY>  12 ms<GREY>  ▸ manual run<MANUAL>", l.statsText())

        val row = look(theory.rows.single { it.dto.state == TestState.PASSED })
        assertEquals("(2)", row.name)
        assertEquals(NameStyle.ARGS, row.nameStyle)
        assertEquals("  12 ms<GREY>", row.statsText(), "only the theory says it was a manual run")
    }

    @Test
    fun counts_highlight_the_tick_only_when_nothing_fails_anywhere() {
        val project = model(t("Adds", TestState.PASSED)).single()
        assertEquals("   ✔ 1<PASSED_BOLD>  <GREY>✖ 0<GREY>  ○ 0<GREY>  12 ms<GREY>", look(project).statsText())
        assertEquals("   ✔ 1<GREY>  <GREY>✖ 0<GREY>  ○ 0<GREY>  12 ms<GREY>", look(project, anyFailures = true).statsText())
    }

    @Test
    fun a_project_shows_its_kind_beside_its_status() {
        val passing = look(model(t("Adds", TestState.PASSED)).single())
        assertEquals(RowIcon(StatusIcon.PASSED, KindIcon.PROJECT), passing.icon)
        assertEquals("Unit", passing.name)
        assertEquals(NameStyle.PROJECT, passing.nameStyle)

        val failing = look(model(t("Adds", TestState.STALE, last = TestState.FAILED)).single())
        assertEquals(RowIcon(StatusIcon.FAILED, KindIcon.PROJECT, faded = true), failing.icon)
        assertEquals(NameStyle.STALE_FAILED_BOLD, failing.nameStyle)
        assertEquals(NameStyle.FAILED_BOLD, look(model(t("Adds", TestState.FAILED)).single()).nameStyle)
    }

    @Test
    fun a_broken_or_running_build_replaces_the_project_status() {
        val core = "src/Core/Core.csproj"
        val builds = mapOf(
            core to ProjectBuild(core, BuildStatus.FAILED, "error"),
            unit to ProjectBuild(unit, BuildStatus.BLOCKED, blockedBy = core),
        )
        val projects = model(t("Adds", TestState.PASSED), builds = builds)
        val broken = look(projects.single { it.path == core })
        assertEquals(RowIcon(StatusIcon.ERROR, KindIcon.PROJECT), broken.icon)
        assertEquals("  build failed<ERROR>", broken.statsText(), "not a test project: no counts")

        val blocked = look(projects.single { it.path == unit })
        assertEquals(RowIcon(StatusIcon.WARNING, KindIcon.PROJECT), blocked.icon)
        assertTrue(blocked.statsText().endsWith("  blocked by Core<GREY_ITALIC>"), blocked.statsText())

        val building = look(model(t("Adds", TestState.PASSED), builds = mapOf(unit to ProjectBuild.started(unit))).single())
        assertEquals(RowIcon(StatusIcon.IN_PROGRESS, KindIcon.PROJECT), building.icon)
    }

    @Test
    fun a_run_problem_is_named_after_the_counts() {
        fun suffix(status: RunStatus) =
            look(model(t("Adds", TestState.PASSED), runs = mapOf(unit to RunFinishedDto(unit, status, null))).single()).stats.last()
        assertEquals(Segment("  unsupported (VSTest)", StatStyle.GREY_ITALIC), suffix(RunStatus.UNSUPPORTED_NOT_MTP))
        assertEquals(Segment("  unsupported MTP version", StatStyle.GREY_ITALIC), suffix(RunStatus.UNSUPPORTED_MTP_VERSION))
        assertEquals(Segment("  test host crashed", StatStyle.ERROR), suffix(RunStatus.CRASHED))
        assertEquals(Segment("  12 ms", StatStyle.GREY), suffix(RunStatus.COMPLETED))
    }

    @Test
    fun an_excluded_project_is_grey() {
        val l = look(model(t("Adds", TestState.EXCLUDED, ms = null), rules = listOf(ExclusionRuleDto(unit))).single())
        assertEquals(RowIcon(StatusIcon.EXCLUDED, KindIcon.PROJECT), l.icon)
        assertEquals(NameStyle.GREY, l.nameStyle)
        assertEquals("   ✔ 0<GREY>  <GREY>✖ 0<GREY>  ○ 0<GREY>  (excluded)<GREY_ITALIC>", l.statsText())
    }

    private fun rows(project: ProjectNode, vararg levels: GroupLevel) =
        GroupingTree.build(listOf(project), Grouping(levels.toList()), SortMode.NAME).rows

    @Test
    fun classes_and_groups_show_their_kind() {
        val project = model(t("Adds", TestState.FAILED), t("Vat", TestState.PASSED, type = "Shop.Unit.Pricing.Tax")).single()
        val cls = rows(project, GroupLevel.CLASS).first()
        val clsLook = look(cls)
        assertEquals(RowIcon(StatusIcon.FAILED, KindIcon.TEST_SET), clsLook.icon)
        assertEquals(NameStyle.FAILED, clsLook.nameStyle)

        val namespace = (rows(project, GroupLevel.PROJECT, GroupLevel.NAMESPACE, GroupLevel.CLASS).single().let { it as GroupView }
            .children.single() as GroupView).children.single { it.label == "Pricing" }
        assertEquals(RowIcon(StatusIcon.PASSED, KindIcon.NAMESPACE), look(namespace).icon)
        assertEquals(NameStyle.PARENT, look(namespace).nameStyle)

        val file = rows(project, GroupLevel.FILE).single()
        assertEquals(RowIcon(StatusIcon.FAILED, KindIcon.FILE, fileName = "Calc.cs"), look(file).icon)
    }

    @Test
    fun an_excluded_class_says_so_unless_its_project_is_excluded() {
        // A second, included class keeps the project from being wholly excluded by the class rule alone.
        val cls = { rules: List<ExclusionRuleDto> ->
            val project = model(t("Adds", TestState.EXCLUDED, ms = null), t("Other", TestState.PASSED, type = "Shop.Unit.Other"), rules = rules).single()
            (rows(project, GroupLevel.PROJECT, GroupLevel.CLASS).single() as GroupView).children.single { it.label == "Calc" }
        }
        val excluded = look(cls(listOf(ExclusionRuleDto(unit, "Shop.Unit.Calc"))))
        assertEquals(RowIcon(StatusIcon.EXCLUDED, KindIcon.TEST_SET), excluded.icon)
        assertEquals(NameStyle.GREY, excluded.nameStyle)
        assertTrue(excluded.statsText().endsWith("  (excluded)<GREY_ITALIC>"))
        assertFalse(look(cls(listOf(ExclusionRuleDto(unit)))).statsText().contains("(excluded)"))
    }

    @Test
    fun the_problems_section_is_red_with_its_count() {
        val l = look(ProblemsGroup("Projects with problems", 2))
        assertEquals(RowIcon(StatusIcon.ERROR, kind = null), l.icon)
        assertEquals("Projects with problems", l.name)
        assertEquals(NameStyle.FAILED_BOLD, l.nameStyle)
        assertEquals("  2<GREY>", l.statsText())
    }

    @Test
    fun anything_else_has_no_look() {
        assertNull(RowPresentation.of("root", editing = false, anyFailures = false))
    }

    @Test
    fun checkboxes_show_only_while_choosing_and_only_on_rows_that_can_be_toggled() {
        val theoryKey = "$unit|Shop.Unit.Calc|Adds"
        val theory = test(t("Adds", TestState.PASSED), t("Adds", TestState.PASSED, variant = "Adds(1)", theory = theoryKey))
        val nonTestProject = model(builds = mapOf("src/Core/Core.csproj" to ProjectBuild("src/Core/Core.csproj", BuildStatus.FAILED))).single()

        assertFalse(RowPresentation.checkboxVisible(theory, editing = false))
        assertTrue(RowPresentation.checkboxVisible(theory, editing = true))
        assertFalse(RowPresentation.checkboxVisible(theory.rows.single(), editing = true), "theory rows follow their theory")
        assertFalse(RowPresentation.checkboxVisible(ProblemsGroup("Build errors", 1), editing = true))
        assertFalse(RowPresentation.checkboxVisible(nonTestProject, editing = true))
        assertTrue(RowPresentation.checkboxVisible(model(t("Adds", TestState.PASSED)).single(), editing = true))
    }

    private fun tests(n: Int) = (1..n).map { t("M$it", TestState.PASSED) }

    /** A class row holding every test of the fixture class, as the grouping tree builds it. */
    private fun classGroup(project: ProjectNode, wholeRule: ExclusionRuleDto? = null): GroupView {
        val cls = project.children.single()
        return GroupView("c", "Calc", GroupKind.CLASS, cls.children.map(::TestView), false, wholeRule = wholeRule)
    }

    @Test
    fun partly_excluded_group_shows_count_and_badge() {
        val rules = (1..12).map { ExclusionRuleDto(unit, "Shop.Unit.Calc", "M$it") }
        val g = classGroup(model(*tests(73).toTypedArray(), rules = rules).single())
        assertTrue(g.partlyExcluded)
        val l = look(g)
        assertEquals(Segment("  · 12 excluded", StatStyle.GREY_ITALIC), l.stats.last())
        assertTrue(l.icon.partlyExcluded)
        assertEquals(StatusIcon.PASSED, l.icon.status, "the badge sits on the group's result, not on an excluded icon")
        assertEquals(KindIcon.TEST_SET, l.icon.kind)
        assertEquals(NameStyle.PARENT, l.nameStyle)
    }

    @Test
    fun a_class_excluded_by_its_rule_except_one_reincluded_test_shows_the_partial_markers() {
        val rules = listOf(ExclusionRuleDto(unit, "Shop.Unit.Calc"), ExclusionRuleDto(unit, "Shop.Unit.Calc", "M2", include = true))
        val g = classGroup(model(*tests(3).toTypedArray(), rules = rules).single(), wholeRule = rules[0])
        val l = look(g)
        assertEquals(Segment("  · 2 excluded", StatStyle.GREY_ITALIC), l.stats.last())
        assertTrue(l.icon.partlyExcluded)
        assertNotEquals(StatusIcon.EXCLUDED, l.icon.status)
    }

    @Test
    fun wholly_excluded_group_looks_as_today() {
        val rules = listOf(ExclusionRuleDto(unit, "Shop.Unit.Calc"))
        val l = look(classGroup(model(*tests(3).toTypedArray(), rules = rules).single(), rules[0]))
        assertEquals(RowIcon(StatusIcon.EXCLUDED, KindIcon.TEST_SET), l.icon)
        assertEquals(NameStyle.GREY, l.nameStyle)
        assertEquals(Segment("  (excluded)", StatStyle.GREY_ITALIC), l.stats.last())
        assertTrue(l.stats.none { it.text.contains("· ") })
    }

    @Test
    fun project_group_shows_project_status() {
        val p = model(t("Adds", TestState.PASSED), runs = mapOf(unit to RunFinishedDto(unit, RunStatus.CRASHED, null))).single()
        val g = GroupView("p", p.name, GroupKind.PROJECT, listOf(classGroup(p)), false, project = p)
        val l = look(g)
        assertEquals(look(p), l)
        assertEquals(Segment("  test host crashed", StatStyle.ERROR), l.stats.last())
        assertEquals(KindIcon.PROJECT, l.icon.kind)
    }

    @Test
    fun a_project_group_without_tests_looks_excluded_when_its_project_is() {
        val p = ProjectNode(unit, "Unit", null, null, null, excluded = true, children = emptyList())
        val l = look(GroupView("p", "Unit", GroupKind.PROJECT, emptyList(), false, project = p))
        assertEquals(look(p), l)
        assertEquals(RowIcon(StatusIcon.EXCLUDED, KindIcon.PROJECT), l.icon)
    }

    @Test
    fun a_partly_excluded_project_group_gets_the_badge_before_its_status_note() {
        val rules = listOf(ExclusionRuleDto(unit, "Shop.Unit.Calc", "M1"))
        val p = model(*tests(2).toTypedArray(), rules = rules, runs = mapOf(unit to RunFinishedDto(unit, RunStatus.CRASHED, null))).single()
        val l = look(GroupView("p", p.name, GroupKind.PROJECT, listOf(classGroup(p)), false, project = p))
        assertTrue(l.icon.partlyExcluded)
        assertEquals(listOf(Segment("  · 1 excluded", StatStyle.GREY_ITALIC), Segment("  test host crashed", StatStyle.ERROR)), l.stats.takeLast(2))
    }

    @Test
    fun problems_group_row() {
        val l = look(ProblemsGroup("Projects with problems", 2))
        assertEquals("Projects with problems", l.name)
        assertEquals(RowIcon(StatusIcon.ERROR, kind = null), l.icon)
        assertEquals("  2<GREY>", l.statsText())
        assertFalse(RowPresentation.checkboxVisible(ProblemsGroup("x", 1), editing = true))
    }

    @Test
    fun check_state() {
        val none = classGroup(model(*tests(3).toTypedArray()).single())
        val some = classGroup(model(*tests(3).toTypedArray(), rules = listOf(ExclusionRuleDto(unit, "Shop.Unit.Calc", "M1"))).single())
        val all = classGroup(model(*tests(3).toTypedArray(), rules = listOf(ExclusionRuleDto(unit, "Shop.Unit.Calc"))).single())
        assertEquals(CheckState.CHECKED, RowPresentation.checkState(none))
        assertEquals(CheckState.PARTIAL, RowPresentation.checkState(some))
        assertEquals(CheckState.UNCHECKED, RowPresentation.checkState(all))
        assertEquals(CheckState.UNCHECKED, RowPresentation.checkState(test(t("Adds", TestState.EXCLUDED, ms = null), rules = listOf(ExclusionRuleDto(unit)))))
        assertEquals(CheckState.CHECKED, RowPresentation.checkState(test(t("Adds", TestState.PASSED))))
        assertNull(RowPresentation.checkState(ProblemsGroup("Build errors", 1)))
        assertNull(RowPresentation.checkState("root"))
    }
}
