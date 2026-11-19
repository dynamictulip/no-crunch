package com.nocrunch.rider.ui

import com.nocrunch.rider.ProjectBuild
import com.nocrunch.rider.engine.BuildStatus
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.RunFinishedDto
import com.nocrunch.rider.engine.RunStatus
import com.nocrunch.rider.engine.TestResultDto
import com.nocrunch.rider.engine.TestState
import com.nocrunch.rider.exclusions.TestExclusions
import com.nocrunch.rider.view.GroupLevel
import com.nocrunch.rider.view.GroupLevel.CLASS
import com.nocrunch.rider.view.GroupLevel.FILE
import com.nocrunch.rider.view.GroupLevel.FOLDER
import com.nocrunch.rider.view.GroupLevel.NAMESPACE
import com.nocrunch.rider.view.GroupLevel.PROJECT
import com.nocrunch.rider.view.Grouping
import com.nocrunch.rider.view.SortMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroupingTreeTest {
    private val unit = "tests/Unit/Unit.csproj"

    private fun t(
        type: String?, method: String, file: String?, state: TestState = TestState.PASSED, project: String = unit, uid: String = "$type.$method",
    ) = TestResultDto(
        key = "$project|$type|$method", project = project, type = type, method = method, uid = uid, variant = null,
        displayName = method, state = state, durationMs = 10.0, message = null, stackTrace = null,
        filePath = file, line = 1,
    )

    // Today's TreeViewTest fixture.
    private val tests = listOf(
        t("Shop.Unit.Pricing.CalcTests", "Adds", "/repo/tests/Unit/Pricing/CalcTests.cs"),
        t("Shop.Unit.Pricing.CalcTests", "Subtracts", "/repo/tests/Unit/Pricing/CalcTests.cs", TestState.FAILED),
        t("Shop.Unit.Pricing.TaxTests", "Vat", "/repo/tests/Unit/Pricing/TaxTests.cs"),
        t("Shop.Unit.OtherTests", "Works", "/repo/tests/Unit/OtherTests.cs"),
        t("Shop.Unit.OtherTests", "NoLocation", null),
    )

    private fun model(
        tests: List<TestResultDto>,
        builds: Map<String, ProjectBuild> = emptyMap(),
        rules: List<ExclusionRuleDto> = emptyList(),
        runs: Map<String, RunFinishedDto> = emptyMap(),
    ) = TestTreeModel.buildAll(tests, builds, rules, runs)

    private fun build(
        projects: List<ProjectNode>, vararg levels: GroupLevel, sort: SortMode = SortMode.NAME,
        failuresOnly: Boolean = false, hideExcluded: Boolean = false,
    ) = GroupingTree.build(projects, Grouping(levels.toList()), sort, failuresOnly, hideExcluded)

    private fun rows(tests: List<TestResultDto>, vararg levels: GroupLevel) = build(model(tests), *levels).rows

    private fun labels(nodes: List<ViewNode>) = nodes.map { it.label }
    private fun keys(nodes: List<ViewNode>) = nodes.map { (it as GroupView).key }
    private fun List<ViewNode>.group(label: String) = single { it.label == label } as GroupView
    private fun methods(nodes: List<ViewNode>) = nodes.map { (it as TestView).node.dto.method }

    @Test
    fun classes_grouping_matches_todays_classes_view() {
        val project = rows(tests, PROJECT, CLASS).single() as GroupView
        assertEquals("Unit", project.label)
        assertEquals("project:$unit", project.key)
        assertEquals(GroupKind.PROJECT, project.kind)
        assertEquals(listOf("OtherTests", "Pricing.CalcTests", "Pricing.TaxTests"), labels(project.children))
        assertEquals(
            listOf("class:$unit|Shop.Unit.OtherTests", "class:$unit|Shop.Unit.Pricing.CalcTests", "class:$unit|Shop.Unit.Pricing.TaxTests"),
            keys(project.children),
        )
        assertTrue(project.children.all { (it as GroupView).kind == GroupKind.CLASS })
        assertEquals(listOf("NoLocation", "Works"), methods(project.children.group("OtherTests").children))
    }

    @Test
    fun namespaces_grouping_matches_todays_view_but_nested() {
        val project = rows(tests, PROJECT, NAMESPACE, CLASS).single() as GroupView
        // Today: flat `Shop.Unit` and `Shop.Unit.Pricing`. The single-child `Shop` › `Unit` chain collapses.
        val shopUnit = project.children.single() as GroupView
        assertEquals("Shop.Unit", shopUnit.label)
        assertEquals("ns:Shop.Unit", shopUnit.key)
        assertEquals(GroupKind.NAMESPACE, shopUnit.kind)
        assertEquals(listOf("OtherTests", "Pricing"), labels(shopUnit.children))
        val pricing = shopUnit.children.group("Pricing")
        assertEquals("ns:Shop.Unit.Pricing", pricing.key)
        assertEquals(listOf("CalcTests", "TaxTests"), labels(pricing.children))
        assertEquals(Counts(passed = 2, failed = 1, pending = 0), pricing.counts)
        assertEquals(30.0, pricing.durationMs)
    }

    @Test
    fun namespaces_are_shown_relative_to_the_projects_own_namespace() {
        val shop = "tests/Shop.Unit/Shop.Unit.csproj"
        val project = rows(tests.map { it.copy(project = shop, key = it.key.replace(unit, shop)) }, PROJECT, NAMESPACE, CLASS).single() as GroupView

        assertEquals(listOf("OtherTests", "Pricing"), labels(project.children), "classes in the project's namespace sit directly under it")
        assertEquals(GroupKind.CLASS, project.children.group("OtherTests").kind)
        assertEquals("ns:Pricing", project.children.group("Pricing").key)
        assertEquals(listOf("CalcTests", "TaxTests"), labels(project.children.group("Pricing").children))
    }

    @Test
    fun relative_and_absolute_namespaces_merge_under_their_project() {
        val shop = "src/Shop/Shop.csproj"
        val project = rows(listOf(t("Shop.X.A", "One", null, project = shop), t("X.B", "Two", null, project = shop)), PROJECT, NAMESPACE, CLASS)
            .single() as GroupView
        val x = project.children.single() as GroupView
        assertEquals("X", x.label, "one row, as today's namespaces view")
        assertEquals("ns:X", x.key)
        assertEquals(listOf("A", "B"), labels(x.children))
    }

    @Test
    fun namespaces_are_full_when_project_is_not_directly_above() {
        val shop = "tests/Shop.Unit/Shop.Unit.csproj"
        val moved = tests.map { it.copy(project = shop, key = it.key.replace(unit, shop), filePath = it.filePath?.replace("/Unit/", "/Shop.Unit/")) }
        assertEquals(listOf("Shop.Unit"), labels(rows(moved, NAMESPACE, PROJECT, CLASS)))
        val project = rows(moved, PROJECT, FOLDER, NAMESPACE, CLASS).single() as GroupView
        assertEquals("Shop.Unit.Pricing", project.children.group("Pricing").children.single().label)
    }

    @Test
    fun files_grouping_matches_todays_view_but_nested() {
        val project = rows(tests, PROJECT, FOLDER, FILE).single() as GroupView
        // Today the test without a file sat in a "(no file)" group, last; it now skips Folder and File, and tests
        // still come after groups.
        assertEquals(listOf("Pricing", "OtherTests.cs", "NoLocation"), labels(project.children))
        val folder = project.children.first() as GroupView
        assertEquals(GroupKind.FOLDER, folder.kind)
        assertEquals("folder:Pricing", folder.key)
        assertEquals(listOf("CalcTests.cs", "TaxTests.cs"), labels(folder.children))
        val calc = folder.children.first() as GroupView
        assertEquals(GroupKind.FILE, calc.kind)
        assertEquals("file:Pricing/CalcTests.cs", calc.key)
        assertEquals(listOf("Adds", "Subtracts"), methods(calc.children))

        val nested = rows(tests + t("Shop.Unit.Pricing.Discounts.DiscountTests", "Ten", "/repo/tests/Unit/Pricing/Discounts/DiscountTests.cs"), PROJECT, FOLDER, FILE)
        val pricing = (nested.single() as GroupView).children.group("Pricing")
        assertEquals(listOf("Discounts", "CalcTests.cs", "TaxTests.cs"), labels(pricing.children), "folders first")
        assertEquals("folder:Pricing/Discounts", pricing.children.group("Discounts").key)
    }

    @Test
    fun folders_are_repository_relative_without_project_above() {
        val rows = rows(tests, FOLDER, FILE)
        assertEquals(listOf("tests/Unit", "NoLocation"), labels(rows), "single-child chain collapsed with /")
        val folder = rows.first() as GroupView
        assertEquals("folder:tests/Unit", folder.key)
        assertEquals(listOf("folder:tests/Unit/Pricing", "file:tests/Unit/OtherTests.cs"), keys(folder.children))
        assertEquals("file:tests/Unit/Pricing/CalcTests.cs", (folder.children.group("Pricing").children.first() as GroupView).key)
    }

    @Test
    fun group_checkbox_rules_cover_everything_below() {
        val pricing = ((rows(tests, PROJECT, NAMESPACE, CLASS).single() as GroupView).children.single() as GroupView).children.group("Pricing")
        assertEquals(
            listOf(ExclusionRuleDto(unit, "Shop.Unit.Pricing.CalcTests"), ExclusionRuleDto(unit, "Shop.Unit.Pricing.TaxTests")),
            pricing.rules,
        )
        val file = ((rows(tests, PROJECT, FOLDER, FILE).single() as GroupView).children.first() as GroupView).children.first() as GroupView
        assertEquals(
            listOf(ExclusionRuleDto(unit, "Shop.Unit.Pricing.CalcTests", "Adds"), ExclusionRuleDto(unit, "Shop.Unit.Pricing.CalcTests", "Subtracts")),
            file.rules,
        )
    }

    @Test
    fun group_is_excluded_when_everything_below_is() {
        val p = model(tests, rules = listOf(ExclusionRuleDto(unit, "Shop.Unit.Pricing.CalcTests"), ExclusionRuleDto(unit, "Shop.Unit.Pricing.TaxTests")))
        val shopUnit = (build(p, PROJECT, NAMESPACE, CLASS).rows.single() as GroupView).children.single() as GroupView
        val pricing = shopUnit.children.group("Pricing")
        assertTrue(pricing.excluded)
        assertFalse(shopUnit.excluded)
        assertTrue((pricing.children.first() as GroupView).parentExcluded)
        assertFalse(pricing.parentExcluded)
    }

    @Test
    fun single_child_chains_collapse() {
        val rows = rows(listOf(t("A.B.C1.X", "One", null), t("A.B.C2.Y", "Two", null)), NAMESPACE, CLASS)
        val ab = rows.single() as GroupView
        assertEquals("A.B", ab.label)
        assertEquals("ns:A.B", ab.key)
        assertEquals(listOf("C1", "C2"), labels(ab.children))
        assertEquals(listOf("ns:A.B.C1", "ns:A.B.C2"), keys(ab.children))
        assertEquals(listOf("X"), labels(ab.children.group("C1").children))
    }

    @Test
    fun nested_classes_nest_under_their_outer_class() {
        val outer = rows(listOf(t("Ns.Outer", "Own", null), t("Ns.Outer+Inner", "Deep", null)), PROJECT, NAMESPACE, CLASS)
            .let { ((it.single() as GroupView).children.single() as GroupView).children.single() as GroupView }
        assertEquals("Outer", outer.label)
        assertEquals("class:$unit|Ns.Outer", outer.key)
        assertEquals(listOf("Own", "Inner"), labels(outer.children), "the class's own tests before its nested class rows")
        val inner = outer.children.group("Inner")
        assertEquals(GroupKind.CLASS, inner.kind)
        assertEquals("class:$unit|Ns.Outer+Inner", inner.key)
        assertEquals(ExclusionRuleDto(unit, "Ns.Outer"), outer.wholeRule)
        assertEquals(ExclusionRuleDto(unit, "Ns.Outer+Inner"), inner.wholeRule)
        assertEquals(listOf(ExclusionRuleDto(unit, "Ns.Outer"), ExclusionRuleDto(unit, "Ns.Outer+Inner")), outer.rules)

        val withoutNamespace = rows(listOf(t("Ns.Outer", "Own", null), t("Ns.Outer+Inner", "Deep", null), t("Other.D", "X", null)), PROJECT, CLASS)
            .let { (it.single() as GroupView).children }
        assertEquals(listOf("Ns.Outer", "Other.D"), labels(withoutNamespace), "the outer class's full name, as today")
        assertEquals("Inner", withoutNamespace.group("Ns.Outer").children.group("Inner").label)
    }

    @Test
    fun outer_class_without_own_tests_still_has_a_row() {
        val ns = rows(listOf(t("Ns.Outer+Inner", "Deep", null)), NAMESPACE, CLASS).single() as GroupView
        val outer = ns.children.single() as GroupView
        assertEquals("Outer", outer.label)
        assertEquals("class:$unit|Ns.Outer", outer.key)
        assertNull(outer.wholeRule, "no tests of its own, so no class rule of its own")
        assertEquals(listOf("Inner"), labels(outer.children))
        assertEquals(listOf(ExclusionRuleDto(unit, "Ns.Outer+Inner")), outer.rules)
    }

    @Test
    fun split_class_rows_have_no_class_rule() {
        val split = listOf(
            t("Ns.C", "A", "/repo/tests/Unit/One/C.cs"),
            t("Ns.C", "B", "/repo/tests/Unit/Two/C.cs"),
            t("Ns.D", "E", "/repo/tests/Unit/One/D.cs"),
        )
        val project = rows(split, PROJECT, FOLDER, CLASS).single() as GroupView
        val one = project.children.group("One")
        val two = project.children.group("Two")
        val cInOne = one.children.group("C")
        assertEquals("class:$unit|Ns.C", cInOne.key)
        assertNull(cInOne.wholeRule)
        assertNull(two.children.group("C").wholeRule)
        assertEquals(listOf(TestExclusions.ruleFor(split[0])), cInOne.rules)
        assertEquals(ExclusionRuleDto(unit, "Ns.D"), one.children.group("D").wholeRule)
        assertEquals(ExclusionRuleDto(unit), project.wholeRule)
    }

    @Test
    fun project_below_namespace_holds_part_of_the_project() {
        val rows = rows(listOf(t("A.X", "One", null), t("B.Y", "Two", null)), NAMESPACE, PROJECT)
        assertEquals(listOf("A", "B"), labels(rows))
        for (ns in rows) {
            val project = (ns as GroupView).children.single() as GroupView
            assertEquals(GroupKind.PROJECT, project.kind)
            assertEquals("project:$unit", project.key)
            assertEquals(unit, project.project?.path)
            assertNull(project.wholeRule)
            assertEquals(1, project.leaves.size)
        }
        val whole = rows(listOf(t("A.X", "One", null), t("A.Y", "Two", null)), NAMESPACE, PROJECT).single() as GroupView
        assertEquals(ExclusionRuleDto(unit), (whole.children.single() as GroupView).wholeRule, "every test of the project is under it")
    }

    private val core = "src/Shop.Core/Shop.Core.csproj"
    private val coreTests = "tests/Shop.Core.Tests/Shop.Core.Tests.csproj"
    private val brokenTests = "tests/Broken.Tests/Broken.Tests.csproj"
    private val crashed = "tests/Crashed.Tests/Crashed.Tests.csproj"
    private val fine = "tests/Fine.Tests/Fine.Tests.csproj"

    @Test
    fun problems_group_when_project_is_not_top() {
        val projects = model(
            listOf(t("Ns.C", "a", null, project = crashed), t("Ns.D", "b", null, project = fine)),
            builds = mapOf(core to ProjectBuild(core, BuildStatus.FAILED, "error", null)),
            runs = mapOf(crashed to RunFinishedDto(crashed, RunStatus.CRASHED, "boom")),
        )

        val notTop = build(projects, NAMESPACE, PROJECT, CLASS)
        assertEquals("Projects with problems", notTop.problemsTitle)
        assertEquals(listOf(crashed, core), notTop.problems.map { it.path }, "model order")
        assertEquals(listOf("project:$crashed", "project:$fine"), keys((notTop.rows.single() as GroupView).children), "a problem project with tests keeps its rows")

        val noProject = build(projects, CLASS)
        assertEquals("Projects with problems", noProject.problemsTitle)
        assertEquals(listOf(crashed, core), noProject.problems.map { it.path })

        val top = build(projects, PROJECT, CLASS)
        assertEquals("Build errors", top.problemsTitle)
        assertEquals(listOf(core), top.problems.map { it.path })
        assertEquals(listOf("project:$crashed", "project:$fine"), keys(top.rows))
    }

    @Test
    fun unsupported_and_broken_test_projects_are_problems_when_project_is_not_top() {
        val unsupported = "tests/Old.Tests/Old.Tests.csproj"
        val projects = model(
            listOf(t("Ns.C", "a", null, project = brokenTests)),
            builds = mapOf(brokenTests to ProjectBuild(brokenTests, BuildStatus.FAILED, "error", null)),
            runs = mapOf(unsupported to RunFinishedDto(unsupported, RunStatus.UNSUPPORTED_NOT_MTP, null)),
        )
        assertEquals(listOf(brokenTests, unsupported), build(projects, CLASS).problems.map { it.path })
        // A test project without tests still gets its own row when Project is the top level.
        val top = build(projects, PROJECT, CLASS)
        assertEquals(emptyList<String>(), top.problems.map { it.path })
        assertEquals(listOf("project:$brokenTests", "project:$unsupported"), keys(top.rows))
    }

    @Test
    fun test_projects_stay_test_projects_when_view_filters_hide_all_their_tests() {
        val builds = mapOf(
            core to ProjectBuild(core, BuildStatus.FAILED, "error", null),
            brokenTests to ProjectBuild(brokenTests, BuildStatus.FAILED, "error", null),
        )
        for ((failuresOnly, hideExcluded) in listOf(true to false, false to true)) {
            val rules = if (hideExcluded) listOf(ExclusionRuleDto(brokenTests)) else emptyList()
            val tree = build(model(listOf(t("T", "b", null, project = brokenTests)), builds, rules), PROJECT, CLASS,
                failuresOnly = failuresOnly, hideExcluded = hideExcluded)
            val label = "failuresOnly=$failuresOnly hideExcluded=$hideExcluded"
            assertEquals(listOf(core), tree.problems.map { it.path }, label)
            val project = tree.rows.single() as GroupView
            assertEquals("project:$brokenTests", project.key, label)
            assertTrue(project.children.isEmpty(), label)
        }
    }

    @Test
    fun filters_drop_empty_groups_and_projects_as_today() {
        val projects = model(listOf(
            t("Ns.C", "a", null, TestState.FAILED, project = coreTests),
            t("Ns.D", "b", null, project = coreTests),
            t("Ns.E", "c", null, project = fine),
        ))
        val tree = build(projects, PROJECT, CLASS, failuresOnly = true)
        assertEquals(listOf("project:$coreTests"), keys(tree.rows))
        assertEquals(listOf("C"), labels((tree.rows.single() as GroupView).children), "today's trimming of the shared namespace")
    }

    @Test
    fun broken_projects_sort_first_by_status() {
        val projects = model(
            listOf(t("Ns.C", "a", null, TestState.FAILED, project = coreTests), t("Ns.D", "b", null, project = brokenTests)),
            builds = mapOf(brokenTests to ProjectBuild(brokenTests, BuildStatus.FAILED, "error", null)),
        )
        assertEquals(listOf("project:$brokenTests", "project:$coreTests"), keys(build(projects, PROJECT, CLASS, sort = SortMode.STATUS).rows))
    }

    @Test
    fun same_class_in_two_projects_stays_two_rows() {
        val other = "tests/Other/Other.csproj"
        val ns = rows(listOf(t("Ns.C", "a", null), t("Ns.C", "a", null, project = other)), NAMESPACE, CLASS).single() as GroupView
        assertEquals(listOf("C", "C"), labels(ns.children))
        assertEquals(setOf("class:$unit|Ns.C", "class:$other|Ns.C"), keys(ns.children).toSet())
        assertEquals(setOf(ExclusionRuleDto(unit, "Ns.C"), ExclusionRuleDto(other, "Ns.C")), ns.children.map { (it as GroupView).wholeRule }.toSet())
    }

    @Test
    fun tests_without_a_file_skip_folder_and_file() {
        val project = rows(tests, PROJECT, FOLDER, FILE).single() as GroupView
        val noLocation = project.children.last() as TestView
        assertEquals("NoLocation", noLocation.node.dto.method)
        assertEquals(tests.size, project.leaves.size, "nothing is dropped")
    }

    @Test
    fun filters_dont_make_a_split_class_look_whole() {
        val split = listOf(
            t("Ns.C", "A", "/repo/tests/Unit/One/C.cs", TestState.FAILED),
            t("Ns.C", "B", "/repo/tests/Unit/Two/C.cs"),
        )
        val project = build(model(split), PROJECT, FOLDER, CLASS, failuresOnly = true).rows.single() as GroupView
        assertEquals(listOf("One"), labels(project.children))
        val c = project.children.group("One").children.single() as GroupView
        assertNull(c.wholeRule)
        assertEquals(listOf(TestExclusions.ruleFor(split[0])), c.rules)
    }

    @Test
    fun uid_only_and_global_namespace_tests() {
        val mixed = listOf(
            t(null, "Loose", null, uid = "uid-1").copy(method = null, key = "$unit|uid-1"),
            t("Global", "Top", null),
            t("Ns.C", "Inside", null),
        )
        val project = rows(mixed, PROJECT, NAMESPACE, CLASS).single() as GroupView
        assertEquals(listOf("Global", "Ns", "Loose"), labels(project.children))
        val global = project.children.group("Global")
        assertEquals(GroupKind.CLASS, global.kind)
        assertEquals(ExclusionRuleDto(unit, "Global"), global.wholeRule)
        val loose = project.children.last() as TestView
        assertEquals(listOf(ExclusionRuleDto(unit, uid = "uid-1")), loose.rules)
        assertEquals(listOf("Global", "Ns.C", "Loose"), labels(rows(mixed, CLASS)))
    }

    @Test
    fun keys_are_unique_among_siblings() {
        val other = "tests/Other/Other.csproj"
        val many = tests + listOf(
            t("Shop.Unit.Pricing.CalcTests", "Adds", "/repo/tests/Other/Pricing/CalcTests.cs", project = other),
            t("Shop.Unit.OtherTests", "Works", "/repo/tests/Other/OtherTests.cs", project = other),
            t("Pricing.X", "Y", "/repo/tests/Unit/X.cs"),
        )
        val groupings = listOf(
            listOf(PROJECT, CLASS), listOf(PROJECT, NAMESPACE, CLASS), listOf(PROJECT, FOLDER, FILE), listOf(NAMESPACE, CLASS),
            listOf(FOLDER, CLASS), listOf(CLASS, FILE), listOf(FILE, PROJECT, NAMESPACE), listOf(NAMESPACE, FOLDER, PROJECT, CLASS),
            emptyList(),
        )
        fun check(nodes: List<ViewNode>, where: String) {
            val ks = nodes.map { if (it is GroupView) it.key else (it as TestView).node.dto.key }
            assertEquals(ks.size, ks.toSet().size, "$where: $ks")
            nodes.filterIsInstance<GroupView>().forEach { check(it.children, "$where/${it.key}") }
        }
        for (levels in groupings) {
            val tree = build(model(many), *levels.toTypedArray())
            check(tree.rows, levels.toString())
            assertEquals(many.size, tree.rows.sumOf { it.leaves.size }, levels.toString())
        }
    }

    @Test
    fun nothing_ticked_is_a_flat_list_of_tests() {
        val rows = rows(tests, *emptyArray<GroupLevel>())
        assertTrue(rows.all { it is TestView })
        assertEquals(listOf("Adds", "NoLocation", "Subtracts", "Vat", "Works"), labels(rows))
    }
}
