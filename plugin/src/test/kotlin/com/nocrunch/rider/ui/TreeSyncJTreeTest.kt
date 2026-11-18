package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.TestState
import com.intellij.ui.CheckedTreeNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** TreeSync driving a real JTree set up like the tool window's: hidden root, projects expanded on first load. */
class TreeSyncJTreeTest {
    private val root = CheckedTreeNode("root")
    private val model = DefaultTreeModel(root)
    private val tree = JTree(model).apply { isRootVisible = false; showsRootHandles = true }

    private fun project(name: String, vararg tests: String) =
        TreeSpec(name, name, children = listOf(TreeSpec("$name.Class", "$name.Class", children = tests.map { TreeSpec(it, it) })))

    private fun visibleRows(): List<String> = (0 until tree.rowCount).map { (tree.getPathForRow(it).lastPathComponent as KeyedTreeNode).key }

    private fun firstLoad() {
        for (i in 0 until root.childCount) tree.expandPath(TreePath(arrayOf(root, root.getChildAt(i))))
    }

    private fun t(project: String, type: String, method: String, state: TestState) = com.nocrunch.rider.engine.TestResultDto(
        key = "$project|$type|$method", project = project, type = type, method = method, uid = "$type.$method", variant = null,
        displayName = method, state = state, durationMs = 1.0, message = null, stackTrace = null, filePath = null, line = null,
    )

    /** What the panel does: filter the shared model, turn it into rows, sync. */
    private fun render(all: List<ProjectNode>, failuresOnly: Boolean) {
        val model = TestTreeModel.filter(all, failuresOnly, hideExcluded = false)
        fun spec(v: ViewNode): TreeSpec = when (v) {
            is TestView -> TreeSpec(v.node.dto.key, v.node)
            is ClassView -> TreeSpec(v.node.type, v, children = v.children.map(::spec))
            is GroupView -> TreeSpec("${v.kind}:${v.label}", v, children = v.children.map(::spec))
        }
        val (_, testProjects) = TreeView.splitBuildErrors(model)
        TreeSync.sync(model = this.model, parent = root, desired = TreeView.sortProjects(testProjects, SortMode.NAME).map { p ->
            TreeSpec(p.path, p, children = TreeView.of(p, ViewMode.CLASSES, SortMode.NAME).map(::spec))
        })
    }

    @Test
    fun show_failures_only_on_the_real_model_keeps_the_failing_tests_visible() {
        val catalog = "tests/Shop.Catalog.Tests/Shop.Catalog.Tests.csproj"
        val core = "tests/Shop.Core.Tests/Shop.Core.Tests.csproj"
        val all = TestTreeModel.buildAll(listOf(
            t(catalog, "Shop.Catalog.Tests.InventoryTests", "New_inventory_is_empty", TestState.FAILED),
            t(catalog, "Shop.Catalog.Tests.InventoryTests", "Add_increases_quantity", TestState.PASSED),
            t(catalog, "Shop.Catalog.Tests.ProductTests", "Has_a_name", TestState.PASSED),
            t(core, "Shop.Core.Tests.PriceCalculatorTests", "Total_multiplies", TestState.PASSED),
        ), emptyMap(), emptyList())
        render(all, failuresOnly = false)
        firstLoad()

        render(all, failuresOnly = true)

        assertEquals(listOf(catalog, "Shop.Catalog.Tests.InventoryTests"), visibleRows())
    }

    @Test
    fun rows_show_again_after_the_tree_has_been_empty() {
        // Show Failures Only with nothing failing empties the tree; the hidden root then collapses.
        TreeSync.syncTree(tree, root, listOf(project("A", "a1"), project("B", "b1")))
        TreeSync.syncTree(tree, root, emptyList())

        TreeSync.syncTree(tree, root, listOf(project("B", "b1")))

        assertEquals(listOf("B", "B.Class"), visibleRows())
    }

    @Test
    fun projects_open_expanded_when_they_first_appear() {
        TreeSync.syncTree(tree, root, listOf(project("A", "a1")))

        TreeSync.syncTree(tree, root, listOf(project("A", "a1"), project("B", "b1")))

        assertEquals(listOf("A", "A.Class", "B", "B.Class"), visibleRows())
    }

    @Test
    fun a_collapsed_project_stays_collapsed_when_its_results_change() {
        TreeSync.syncTree(tree, root, listOf(project("A", "a1"), project("B", "b1")))
        tree.collapsePath(TreePath(arrayOf(root, root.getChildAt(0))))

        TreeSync.syncTree(tree, root, listOf(project("A", "a1", "a2"), project("B", "b1")))

        assertEquals(listOf("A", "B", "B.Class"), visibleRows())
    }

    @Test
    fun filtering_down_to_one_project_keeps_it_visible() {
        TreeSync.sync(model, root, listOf(project("A", "a1"), project("B", "b1", "b2"), project("C", "c1")))
        firstLoad()

        TreeSync.sync(model, root, listOf(project("B", "b2")))

        assertEquals(listOf("B", "B.Class"), visibleRows())
    }

    @Test
    fun filtering_down_to_a_project_that_was_not_first_keeps_it_visible() {
        TreeSync.sync(model, root, listOf(project("A", "a1"), project("B", "b1")))
        firstLoad()

        TreeSync.sync(model, root, listOf(project("B", "b1")))

        assertEquals(listOf("B", "B.Class"), visibleRows())
    }
}
