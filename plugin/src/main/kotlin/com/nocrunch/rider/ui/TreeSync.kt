package com.nocrunch.rider.ui

import com.intellij.ui.CheckedTreeNode
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** The row a tree should show. [key] identifies it among its siblings from one update to the next. */
data class TreeSpec(
    val key: String,
    val userObject: Any,
    val checked: Boolean = true,
    val enabled: Boolean = true,
    val children: List<TreeSpec> = emptyList(),
)

/** A tree row that remembers which [TreeSpec] key it shows. */
class KeyedTreeNode(val key: String, userObject: Any) : CheckedTreeNode(userObject)

/**
 * Updates a tree in place to match a new [TreeSpec] list, keeping each row's node while its key stays. Only rows that
 * appear, go away, move or change are reported to the model, so the JTree keeps its selection, expanded rows and
 * scroll position (reloading the whole model clears them all).
 */
object TreeSync {
    /**
     * [sync] for a tree whose root is hidden. Swing collapses a node when its last child goes, and rows under a
     * collapsed hidden root never show, so once the tree had been empty (e.g. Show Failures Only with nothing failing)
     * nothing would appear again. The root is kept open, and top-level rows that weren't there before open expanded;
     * rows that stayed keep whatever expansion the user gave them.
     */
    fun syncTree(tree: JTree, root: DefaultMutableTreeNode, desired: List<TreeSpec>) {
        val before = children(root).map { it.key }.toSet()
        sync(tree.model as DefaultTreeModel, root, desired)
        if (root.childCount > 0) tree.expandPath(TreePath(root))
        for (row in children(root)) if (row.key !in before) tree.expandPath(TreePath(arrayOf(root, row)))
    }

    fun sync(model: DefaultTreeModel, parent: DefaultMutableTreeNode, desired: List<TreeSpec>) {
        val keys = uniqueKeys(desired)
        val wanted = keys.withIndex().associate { (i, k) -> k to i }

        // 1. Rows that went away.
        val gone = children(parent).withIndex().filter { (_, n) -> n.key !in wanted }
        remove(model, parent, gone.map { it.index })

        // 2. Rows that moved: keep the longest run already in the right order, take the rest out to put back.
        val kept = children(parent)
        val stay = longestIncreasing(kept.map { wanted.getValue(it.key) }).map { kept[it] }.toSet()
        val moving = kept.withIndex().filter { (_, n) -> n !in stay }
        remove(model, parent, moving.map { it.index })
        val reusable = (kept.associateBy { it.key })

        // 3. Put moved and new rows in place, in order, so each lands at its final index.
        val inserted = mutableListOf<Int>()
        for ((i, spec) in desired.withIndex()) {
            val existing = reusable[keys[i]]
            if (existing != null && existing in stay) continue
            parent.insert(existing ?: build(spec, keys[i]), i)
            inserted += i
        }
        if (inserted.isNotEmpty()) model.nodesWereInserted(parent, inserted.toIntArray())

        // 4. Update the rows that were already there, then their children.
        for ((i, spec) in desired.withIndex()) {
            val node = parent.getChildAt(i) as KeyedTreeNode
            if (node.key !in reusable) continue
            if (node.userObject != spec.userObject || node.isChecked != spec.checked || node.isEnabled != spec.enabled) {
                node.userObject = spec.userObject
                node.isChecked = spec.checked
                node.isEnabled = spec.enabled
                model.nodeChanged(node)
            }
            sync(model, node, spec.children)
        }
    }

    private fun children(parent: DefaultMutableTreeNode): List<KeyedTreeNode> =
        (0 until parent.childCount).map { parent.getChildAt(it) as KeyedTreeNode }

    private fun remove(model: DefaultTreeModel, parent: DefaultMutableTreeNode, indices: List<Int>) {
        if (indices.isEmpty()) return
        val nodes = indices.map { parent.getChildAt(it) }
        indices.asReversed().forEach { parent.remove(it) }
        model.nodesWereRemoved(parent, indices.toIntArray(), nodes.toTypedArray())
    }

    private fun build(spec: TreeSpec, key: String): KeyedTreeNode = KeyedTreeNode(key, spec.userObject).apply {
        isChecked = spec.checked
        isEnabled = spec.enabled
        uniqueKeys(spec.children).forEachIndexed { i, k -> add(build(spec.children[i], k)) }
    }

    /** Siblings should have distinct keys; if some don't, later ones get a count so each still has its own node. */
    private fun uniqueKeys(specs: List<TreeSpec>): List<String> {
        val seen = HashMap<String, Int>()
        return specs.map { s ->
            val n = seen.merge(s.key, 1, Int::plus)!!
            if (n == 1) s.key else "${s.key}#$n"
        }
    }

    /** Indices into [values] of a longest strictly increasing subsequence. */
    private fun longestIncreasing(values: List<Int>): List<Int> {
        if (values.isEmpty()) return emptyList()
        val tails = mutableListOf<Int>() // index in values of the smallest tail of each length
        val previous = IntArray(values.size) { -1 }
        for (i in values.indices) {
            var lo = 0
            var hi = tails.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (values[tails[mid]] < values[i]) lo = mid + 1 else hi = mid
            }
            if (lo > 0) previous[i] = tails[lo - 1]
            if (lo == tails.size) tails += i else tails[lo] = i
        }
        val result = ArrayList<Int>()
        var i = tails.last()
        while (i >= 0) { result += i; i = previous[i] }
        return result.asReversed()
    }
}
