package com.nocrunch.rider.ui

import com.intellij.ui.CheckedTreeNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class TreeSyncTest {
    private val root = CheckedTreeNode("root")
    private val model = DefaultTreeModel(root)
    private val events = mutableListOf<String>()

    init {
        model.addTreeModelListener(object : TreeModelListener {
            private fun log(kind: String, e: TreeModelEvent) {
                val parent = (e.treePath.lastPathComponent as DefaultMutableTreeNode).userObject
                events += "$kind $parent ${e.childIndices.toList()}"
            }
            override fun treeNodesInserted(e: TreeModelEvent) = log("inserted", e)
            override fun treeNodesRemoved(e: TreeModelEvent) = log("removed", e)
            override fun treeNodesChanged(e: TreeModelEvent) = log("changed", e)
            override fun treeStructureChanged(e: TreeModelEvent) = log("structure", e)
        })
    }

    /** A row whose key is its name; [data] stands in for its results. */
    private fun row(name: String, data: String = name, vararg children: TreeSpec, checked: Boolean = true) =
        TreeSpec(name, data, checked = checked, children = children.toList())

    private fun sync(vararg rows: TreeSpec) {
        events.clear()
        TreeSync.sync(model, root, rows.toList())
    }

    private fun shape(node: DefaultMutableTreeNode = root): String =
        if (node.childCount == 0) "${node.userObject}"
        else "${node.userObject}(" + (0 until node.childCount).joinToString(" ") { shape(node.getChildAt(it) as DefaultMutableTreeNode) } + ")"

    private fun child(vararg indices: Int): DefaultMutableTreeNode =
        indices.fold(root as DefaultMutableTreeNode) { n, i -> n.getChildAt(i) as DefaultMutableTreeNode }

    @Test
    fun first_sync_inserts_everything_in_one_event() {
        sync(row("A", "A", row("a1"), row("a2")), row("B"))
        assertEquals("root(A(a1 a2) B)", shape())
        assertEquals(listOf("inserted root [0, 1]"), events)
    }

    @Test
    fun the_same_tree_again_changes_nothing() {
        sync(row("A", "A", row("a1")), row("B"))
        val a = child(0)
        sync(row("A", "A", row("a1")), row("B"))
        assertEquals(emptyList<String>(), events)
        assertSame(a, child(0))
    }

    @Test
    fun new_data_for_a_row_updates_that_row_in_place() {
        sync(row("A", "A", row("a1"), row("a2")), row("B"))
        val a2 = child(0, 1)
        sync(row("A", "A*", row("a1"), row("a2", "a2*")), row("B"))
        assertEquals("root(A*(a1 a2*) B)", shape())
        assertEquals(listOf("changed root [0]", "changed A* [1]"), events)
        assertSame(a2, child(0, 1))
    }

    @Test
    fun checkbox_changes_update_the_row() {
        sync(row("A"), row("B"))
        sync(row("A"), row("B", checked = false))
        assertEquals(listOf("changed root [1]"), events)
        assertFalse((child(1) as CheckedTreeNode).isChecked)
    }

    @Test
    fun only_rows_that_went_away_are_removed_and_only_new_ones_inserted() {
        sync(row("A"), row("B"), row("C"), row("D"))
        val (a, c) = child(0) to child(2)
        sync(row("A"), row("C"), row("E"))
        assertEquals("root(A C E)", shape())
        assertEquals(listOf("removed root [1, 3]", "inserted root [2]"), events)
        assertSame(a, child(0))
        assertSame(c, child(1))
    }

    @Test
    fun a_row_that_moves_is_moved_on_its_own() {
        sync(row("A", "A", row("a1")), row("B"), row("C"), row("D"))
        val (a, b) = child(0) to child(1)
        sync(row("B"), row("C"), row("D"), row("A", "A", row("a1")))
        assertEquals("root(B C D A(a1))", shape())
        assertEquals(listOf("removed root [0]", "inserted root [3]"), events)
        assertSame(a, child(3), "the moved row keeps its node and children")
        assertSame(b, child(0))
    }

    @Test
    fun rows_with_the_same_key_stay_distinct() {
        sync(row("A"), TreeSpec("A", "A again"))
        assertEquals("root(A A again)", shape())
        sync(row("A"), TreeSpec("A", "A again"))
        assertEquals(emptyList<String>(), events)
    }
}
