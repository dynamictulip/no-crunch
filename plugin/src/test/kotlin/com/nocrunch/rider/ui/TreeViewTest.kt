package com.nocrunch.rider.ui

import com.nocrunch.rider.view.ViewMode
import com.nocrunch.rider.engine.TestState
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.TestResultDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TreeViewTest {
    private val unit = "tests/Unit/Unit.csproj"

    private fun t(type: String, method: String, state: TestState, file: String?, ms: Double = 10.0) = TestResultDto(
        key = "$unit|$type|$method", project = unit, type = type, method = method, uid = "$type.$method", variant = null,
        displayName = method, state = state, durationMs = ms, message = null, stackTrace = null,
        filePath = file, line = 1,
    )

    private val tests = listOf(
        t("Shop.Unit.Pricing.CalcTests", "Adds", TestState.PASSED, "/repo/tests/Unit/Pricing/CalcTests.cs"),
        t("Shop.Unit.Pricing.CalcTests", "Subtracts", TestState.FAILED, "/repo/tests/Unit/Pricing/CalcTests.cs"),
        t("Shop.Unit.Pricing.TaxTests", "Vat", TestState.PASSED, "/repo/tests/Unit/Pricing/TaxTests.cs"),
        t("Shop.Unit.OtherTests", "Works", TestState.PASSED, "/repo/tests/Unit/OtherTests.cs"),
        t("Shop.Unit.OtherTests", "NoLocation", TestState.PASSED, null),
    )

    private fun project() = TestTreeModel.build(tests, emptyMap(), emptyList(), false).single()

    private fun labels(nodes: List<ViewNode>): List<String> = nodes.map { it.label }

    @Test
    fun classes_view_is_classes_with_full_names() {
        val view = TreeView.of(project(), ViewMode.CLASSES)
        assertEquals(listOf("OtherTests", "Pricing.CalcTests", "Pricing.TaxTests"), labels(view))
        assertTrue(view.all { it is ClassView })
    }

    @Test
    fun namespaces_view_groups_classes_under_their_namespace() {
        val view = TreeView.of(project(), ViewMode.NAMESPACES)
        assertEquals(listOf("Shop.Unit", "Shop.Unit.Pricing"), labels(view))
        val pricing = view.single { it.label == "Shop.Unit.Pricing" } as GroupView
        assertEquals(GroupKind.NAMESPACE, pricing.kind)
        assertEquals(listOf("CalcTests", "TaxTests"), labels(pricing.children))
        assertEquals(Counts(passed = 2, failed = 1, pending = 0), pricing.counts)
        assertEquals(30.0, pricing.durationMs)
    }

    @Test
    fun namespaces_are_shown_relative_to_the_projects_own_namespace() {
        val shop = "tests/Shop.Unit/Shop.Unit.csproj"
        val p = TestTreeModel.build(tests.map { it.copy(project = shop, key = it.key.replace(unit, shop)) }, emptyMap(), emptyList(), false).single()

        val view = TreeView.of(p, ViewMode.NAMESPACES)

        assertEquals(listOf("OtherTests", "Pricing"), labels(view), "classes in the project's namespace sit directly under it")
        assertTrue(view.single { it.label == "OtherTests" } is ClassView)
        assertEquals(listOf("CalcTests", "TaxTests"), labels((view.single { it.label == "Pricing" } as GroupView).children))
    }

    @Test
    fun files_view_groups_tests_by_folder_then_file() {
        val view = TreeView.of(project(), ViewMode.FILES)
        assertEquals(listOf("Pricing", "OtherTests.cs", TreeView.NO_FILE), labels(view))
        val folder = view.first() as GroupView
        assertEquals(GroupKind.FOLDER, folder.kind)
        assertEquals(listOf("CalcTests.cs", "TaxTests.cs"), labels(folder.children))
        val calc = folder.children.first() as GroupView
        assertEquals(GroupKind.FILE, calc.kind)
        assertEquals(listOf("Adds", "Subtracts"), calc.children.map { (it as TestView).node.dto.method })
    }

    @Test
    fun group_checkbox_rules_cover_everything_below() {
        val namespace = TreeView.of(project(), ViewMode.NAMESPACES).single { it.label == "Shop.Unit.Pricing" } as GroupView
        assertEquals(
            listOf(ExclusionRuleDto(unit, "Shop.Unit.Pricing.CalcTests"), ExclusionRuleDto(unit, "Shop.Unit.Pricing.TaxTests")),
            namespace.rules,
        )
        val file = (TreeView.of(project(), ViewMode.FILES).first() as GroupView).children.first() as GroupView
        assertEquals(
            listOf(ExclusionRuleDto(unit, "Shop.Unit.Pricing.CalcTests", "Adds"), ExclusionRuleDto(unit, "Shop.Unit.Pricing.CalcTests", "Subtracts")),
            file.rules,
        )
    }

    @Test
    fun group_is_excluded_when_everything_below_is() {
        val p = TestTreeModel.build(tests, emptyMap(), listOf(ExclusionRuleDto(unit, "Shop.Unit.Pricing.CalcTests"), ExclusionRuleDto(unit, "Shop.Unit.Pricing.TaxTests")), false).single()
        val view = TreeView.of(p, ViewMode.NAMESPACES)
        assertTrue((view.single { it.label == "Shop.Unit.Pricing" } as GroupView).excluded)
        assertTrue(!(view.single { it.label == "Shop.Unit" } as GroupView).excluded)
    }
}
