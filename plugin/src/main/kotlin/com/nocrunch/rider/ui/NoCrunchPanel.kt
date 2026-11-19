package com.nocrunch.rider.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckboxTreeListener
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import com.nocrunch.rider.NoCrunchService
import com.nocrunch.rider.engine.EngineState
import com.nocrunch.rider.settings.NoCrunchSettings
import com.nocrunch.rider.view.Grouping
import com.nocrunch.rider.view.NameColouring
import com.nocrunch.rider.view.SortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The No Crunch tool window: progress bar, test tree and details pane. Lays them out, wires them to the shared results
 * and keeps the tree in step ([render]). Rows are drawn by [TestTreeRenderer], actions live in [NoCrunchActions] and
 * debugging in [TestDebugger].
 */
class NoCrunchPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {
    private val service = NoCrunchService.getInstance(project)
    private val settings = NoCrunchSettings.getInstance(project)
    private val root = CheckedTreeNode("root")
    /** Checkboxes only show while choosing which tests run automatically (ticked = runs). */
    private var choosingTests = false
    private var failuresOnly = false
    /** Some test shown in the tree is failing (used to drop the green tick highlights). Set once per model, not per painted row. */
    private var anyFailures = false
    // No automatic parent/child propagation: each click maps to exactly one change in the exclusion rules.
    private val tree = CheckboxTree(TestTreeRenderer({ choosingTests }, { nameColouring }, { anyFailures }), root,
        CheckboxTreeBase.CheckPolicy(false, false, false, false))
    private val details = DetailsPane(project, parent)
    private val bar = ProgressBar()
    private val cards = JPanel(java.awt.CardLayout())
    private val waitingLabel = JBLabel().apply { foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground() }
    private val pausedView: JPanel = JPanel(java.awt.GridBagLayout()).apply {
        val column = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            isOpaque = false
            fun centred(c: javax.swing.JComponent) = c.apply { alignmentX = java.awt.Component.CENTER_ALIGNMENT }
            add(centred(JBLabel("<html><div style='text-align:center'>No Crunch is paused.<br>Results are hidden until you resume.</div></html>")
                .apply { foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground() }))
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(14)))
            add(centred(javax.swing.JButton("Resume", AllIcons.Actions.Execute).apply {
                font = com.intellij.util.ui.JBFont.h3().asBold()
                com.intellij.ui.ClientProperty.put(this, com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI.DEFAULT_STYLE_KEY, true)
                addActionListener { service.resume() }
            }))
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(10)))
            add(centred(waitingLabel))
        }
        add(column)
    }
    private var rebuilding = false
    /** What the tree shows, with the grouping that made it; null forces the next render to sync (an empty tree would match an empty one). */
    private var lastModel: Pair<Grouping, GroupedTree>? = null
    private var last: ResultsSnapshot? = null

    private val actions = NoCrunchActions(project, service, settings, object : ActionHost {
        override val tree: CheckboxTree get() = this@NoCrunchPanel.tree
        override var failuresOnly: Boolean
            get() = this@NoCrunchPanel.failuresOnly
            set(value) { this@NoCrunchPanel.failuresOnly = value }
        override var choosingTests: Boolean
            get() = this@NoCrunchPanel.choosingTests
            set(value) { this@NoCrunchPanel.choosingTests = value }
        override fun redraw() = this@NoCrunchPanel.redraw()
        override fun selectedObjects() = tree.selectionPaths.orEmpty().map { (it.lastPathComponent as? CheckedTreeNode)?.userObject }
        override fun firstFailingSelectedTest() = this@NoCrunchPanel.firstFailingSelectedTest()
    }, TestDebugger(project, service))

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.addCheckboxTreeListener(object : CheckboxTreeListener {
            override fun nodeStateChanged(node: CheckedTreeNode) {
                if (rebuilding) return
                val rules = TreeSelection.checkboxRules(node.userObject) ?: return
                service.setExcluded(rules, excluded = !node.isChecked)
            }
        })
        // While the tree updates, render() refreshes the details once at the end instead.
        tree.addTreeSelectionListener { if (!rebuilding) showDetails(tree.lastSelectedPathComponent as? CheckedTreeNode) }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) (selectedNode()?.userObject as? TestNode)?.let { navigate(it) }
            }
        })

        // A visible 1px divider (Rider's border colour), so it's clear long rows run under the details pane.
        val splitter = com.intellij.ui.OnePixelSplitter(false, 0.55f).apply {
            firstComponent = JBScrollPane(tree).apply { border = JBUI.Borders.empty() }
            secondComponent = details.component
        }
        // NCrunch-style status bar across the top, above the tree and details.
        val results = JPanel(BorderLayout()).apply {
            add(bar, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
        cards.add(results, RESULTS_CARD)
        cards.add(pausedView, PAUSED_CARD)
        setContent(cards)
        toolbar = ActionManager.getInstance().createActionToolbar("NoCrunch", actions.toolbar(), true)
            .apply { targetComponent = this@NoCrunchPanel }.component

        tree.emptyText.text = StatusText.emptyTree(service.state.value)
        bar.update(ProgressBarModel.from(Counts(), service.state.value, 0))
        // Covers plugin reloads and any case where the startup activity didn't run.
        service.start()
        PopupHandler.installPopupMenu(tree, actions.popup(), "NoCrunchTreePopup")
        val job = service.scope.launch(Dispatchers.EDT) { collect() }
        Disposer.register(parent) { job.cancel() }
    }

    /** The model is built off the EDT, at a steady rate, and shared with the tool window icon and status bar. */
    private suspend fun collect() {
        NoCrunchResults.getInstance(project).snapshots.collect { render(it) }
    }

    /** Rebuilds the tree from the latest results although they haven't changed: a view option has. */
    private fun redraw() {
        lastModel = null
        last?.let(::render)
    }

    /** The empty tree's "Show all tests" link: both view filters off. */
    private fun showAllTests() {
        failuresOnly = false
        settings.state.hideExcluded = false
        redraw()
    }

    private fun render(s: ResultsSnapshot) {
        last = s
        // The shared model has every test (the bar counts them all); the tree shows it grouped and through the view filters.
        val grouping = currentGrouping
        val grouped = GroupingTree.build(s.projects, grouping, sortMode, failuresOnly, settings.state.hideExcluded)
        bar.update(s.bar)
        // While paused, results can't be trusted, so hide them behind a big Resume button.
        val paused = s.state.state == EngineState.PAUSED
        if (paused) waitingLabel.text = StatusText.waiting(s.state.queued)
        (cards.layout as java.awt.CardLayout).show(cards, if (paused) PAUSED_CARD else RESULTS_CARD)
        val hiddenBy = HiddenBy.of(hasTests = s.projects.any { it.hasTests }, shownEmpty = grouped.rows.isEmpty() && grouped.problems.isEmpty(), failuresOnly, settings.state.hideExcluded)
        tree.emptyText.text = StatusText.emptyTree(s.state, hiddenBy)
        if (hiddenBy != null) tree.emptyText.appendSecondaryText("Show all tests", SimpleTextAttributes.LINK_ATTRIBUTES) { showAllTests() }
        if (lastModel == grouping to grouped) return
        lastModel = grouping to grouped
        val failuresBefore = anyFailures
        anyFailures = grouped.rows.any { it.counts.failed > 0 }
        val treeModel = tree.model as DefaultTreeModel
        val expanded = TreeUtil.collectExpandedPaths(tree).map { pathKey(it) }.toSet()
        val selected = tree.selectionPaths.orEmpty().map { pathKey(it) }.toSet()
        val selectedTests = tree.selectionPaths.orEmpty().mapNotNull { ((it.lastPathComponent as? CheckedTreeNode)?.userObject as? TestNode)?.dto?.key }.toSet()
        // Row keys below the project change with the grouping, so a row's own last key is the fallback for expansion.
        val expandedLast = expanded.map { it.substringAfterLast('/') }.toSet()
        // The selection listener stays quiet while rows change; the details pane is refreshed once at the end.
        rebuilding = true
        try {
            // Updated in place, not rebuilt: reloading the model cleared the selection (and the details pane with
            // it), collapsed a multi-selection to one row and re-expanded everything after Collapse All.
            TreeSync.syncTree(tree, root, specs(grouped))
            // The green tick highlights depend on whether anything fails anywhere, so every row may look different.
            if (anyFailures != failuresBefore) TreeUtil.treeNodeTraverser(root).forEach { treeModel.nodeChanged(it as CheckedTreeNode) }
            // A row that moved (sorting by status or run time) was taken out and put back, which collapses and
            // deselects it; put that back.
            val reselect = mutableListOf<TreePath>()
            val matchedTests = mutableSetOf<String>()
            val byTestKey = mutableMapOf<String, TreePath>()
            TreeUtil.treeNodeTraverser(root).forEach { node ->
                val path = TreePath((node as CheckedTreeNode).path)
                val key = pathKey(path)
                if (!tree.isExpanded(path) && !node.isLeaf && (key in expanded || (node as? KeyedTreeNode)?.key in expandedLast)) tree.expandPath(path)
                if (key in selected) {
                    reselect += path
                    (node.userObject as? TestNode)?.let { matchedTests += it.dto.key }
                }
                (node.userObject as? TestNode)?.let { byTestKey.putIfAbsent(it.dto.key, path) }
            }
            // A regrouped test sits under different rows, so its full path no longer matches; find it by its test key.
            for (testKey in selectedTests - matchedTests) {
                val path = byTestKey[testKey] ?: continue
                reselect += path
                tree.makeVisible(path)
            }
            if (reselect.isNotEmpty() && reselect.size != tree.selectionCount) tree.selectionPaths = reselect.toTypedArray()
        } finally {
            rebuilding = false
        }
        // The selected row's results may have changed; the pane leaves itself alone if its text hasn't.
        showDetails(selectedNode())
    }

    private val sortMode: SortMode get() = SortMode.fromSetting(settings.state.sortMode)

    private val nameColouring: NameColouring get() = NameColouring.fromSetting(settings.state.nameColouring)

    /** Read through [Grouping.fromSetting] so a saved old view mode still converts until a grouping is chosen. */
    private val currentGrouping: Grouping get() = Grouping.fromSetting(settings.state.grouping, settings.state.viewMode)

    /** The rows the tree should show. Projects with problems are pinned above the rest whatever the sort. */
    private fun specs(t: GroupedTree): List<TreeSpec> {
        val problems = if (t.problems.isEmpty()) emptyList() else listOf(
            TreeSpec(PROBLEMS_KEY, ProblemsGroup(t.problemsTitle, t.problems.size), enabled = false,
                children = t.problems.map { TreeSpec(it.path, it, enabled = false) }),
        )
        return problems + t.rows.map(::spec)
    }

    /** Tests keep their [TestNode] as the row object; every group level uses its [GroupView]. */
    private fun spec(v: ViewNode): TreeSpec = when (v) {
        is TestView -> TreeSpec(v.node.dto.key, v.node, !v.excluded, choosingTests && !v.locked,
            v.node.rows.map { TreeSpec(it.dto.key, it, checked = !it.excluded, enabled = false) })
        // A partly excluded row draws a partial box but is not checked, so clicking it includes everything under it.
        is GroupView -> TreeSpec(v.key, v, RowPresentation.checkState(v) == CheckState.CHECKED,
            choosingTests && TreeSelection.checkboxRules(v) != null, v.children.map(::spec))
    }

    private fun pathKey(path: TreePath): String = path.path.joinToString("/") { (it as? KeyedTreeNode)?.key ?: "root" }

    private fun selectedNode() = tree.lastSelectedPathComponent as? CheckedTreeNode

    private fun showDetails(node: CheckedTreeNode?) = details.show(DetailsText.of(node?.userObject))

    /** The first failing test (current or stale result) under the selected rows, in the order the tree shows them. */
    private fun firstFailingSelectedTest(): TestNode? {
        val selected = tree.selectionPaths.orEmpty().sortedBy { tree.getRowForPath(it) }
        return DebugTarget.firstFailing(selected.asSequence().flatMap { path ->
            TreeUtil.treeNodeTraverser(path.lastPathComponent as CheckedTreeNode).preOrderDfsTraversal()
                .mapNotNull { (it as? CheckedTreeNode)?.userObject as? TestNode }.asSequence()
        })
    }

    private fun navigate(node: TestNode) {
        val path = node.dto.filePath ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        OpenFileDescriptor(project, file, maxOf(0, (node.dto.line ?: 1) - 1), 0).navigate(true)
    }

    private companion object {
        const val RESULTS_CARD = "results"
        const val PAUSED_CARD = "paused"
        const val PROBLEMS_KEY = "problems"
    }
}
