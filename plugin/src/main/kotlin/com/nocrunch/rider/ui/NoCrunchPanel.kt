package com.nocrunch.rider.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckboxTreeListener
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.JBSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import com.nocrunch.rider.NoCrunchService
import com.nocrunch.rider.settings.NoCrunchConfigurable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class NoCrunchPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {
    private val service = NoCrunchService.getInstance(project)
    private val root = CheckedTreeNode("root")
    // No automatic parent/child propagation: each click maps to exactly one change in the exclusion rules.
    private val tree = CheckboxTree(Renderer(), root, CheckboxTreeBase.CheckPolicy(false, false, false, false))
    private val details = DetailsPane(project, parent)
    private val status = JBLabel().apply { border = JBUI.Borders.empty(2, 6) }
    private var failuresOnly = false
    private var rebuilding = false
    private var lastModel: List<ProjectNode> = emptyList()

    /** Runs the selected tests now, including excluded ones; results are marked as a manual run. */
    private val runSelectedAction: AnAction by lazy {
        object : AnAction("Run Selected Tests (Including Excluded)", null, AllIcons.RunConfigurations.TestState.Run) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = tree.selectionPaths?.isNotEmpty() == true
            }
            override fun actionPerformed(e: AnActionEvent) {
                val keys = selectedTestKeys().takeIf { it.isNotEmpty() } ?: return
                // Expand what was run so each test's result is visible as it comes in.
                tree.selectionPaths?.forEach { selected ->
                    TreeUtil.treeNodeTraverser(selected.lastPathComponent as CheckedTreeNode).forEach { node ->
                        tree.expandPath(TreePath((node as CheckedTreeNode).path))
                    }
                }
                service.runTests(keys)
            }
        }
    }


    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.addCheckboxTreeListener(object : CheckboxTreeListener {
            override fun nodeStateChanged(node: CheckedTreeNode) {
                if (rebuilding) return
                val rules = when (val o = node.userObject) {
                    is ProjectNode -> listOf(o.rule)
                    is ClassNode -> o.rules
                    is TestNode -> listOf(TestTreeModel.ruleFor(o.dto))
                    else -> return
                }
                service.setExcluded(rules, excluded = !node.isChecked)
            }
        })
        tree.addTreeSelectionListener { showDetails(tree.lastSelectedPathComponent as? CheckedTreeNode) }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) (selectedNode()?.userObject as? TestNode)?.let { navigate(it) }
            }
        })

        val top = JPanel(BorderLayout()).apply {
            add(JBScrollPane(tree), BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        val splitter = JBSplitter(false, 0.55f).apply {
            firstComponent = top
            secondComponent = details.component
        }
        setContent(splitter)
        toolbar = ActionManager.getInstance().createActionToolbar("NoCrunch", toolbarActions(), true)
            .apply { targetComponent = this@NoCrunchPanel }.component

        tree.emptyText.text = StatusText.emptyTree(service.state.value)
        status.text = StatusText.describe(service.state.value)
        // Covers plugin reloads and any case where the startup activity didn't run.
        service.start()
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(runSelectedAction), "NoCrunchTreePopup")
        val job = service.scope.launch(Dispatchers.EDT) { collect() }
        Disposer.register(parent) { job.cancel() }
    }

    @OptIn(FlowPreview::class)
    private suspend fun collect() {
        combine(service.tests, service.builds, service.runs, service.exclusions, service.state) { t, b, r, e, s -> Snapshot(t, b, r, e, s) }
            .debounce(100)
            .collect { render(it) }
    }

    private data class Snapshot(
        val tests: Map<String, com.nocrunch.rider.engine.TestResultDto>,
        val builds: Map<String, com.nocrunch.rider.engine.BuildFinishedDto>,
        val runs: Map<String, com.nocrunch.rider.engine.RunFinishedDto>,
        val rules: List<com.nocrunch.rider.engine.ExclusionRuleDto>,
        val state: com.nocrunch.rider.EngineUiState,
    )

    private var last: Snapshot? = null

    private fun render(s: Snapshot) {
        last = s
        val model = TestTreeModel.build(s.tests.values, s.builds, s.rules, failuresOnly, s.runs)
        status.text = statusText(s, model)
        tree.emptyText.text = StatusText.emptyTree(s.state)
        if (model == lastModel) return
        lastModel = model
        val expanded = TreeUtil.collectExpandedPaths(tree).map { pathKey(it) }.toSet()
        val selected = tree.selectionPath?.let { pathKey(it) }
        rebuilding = true
        try {
            root.removeAllChildren()
            for (p in model) {
                val pn = CheckedTreeNode(p).apply { isChecked = !p.excluded }
                for (c in p.children) {
                    val cn = CheckedTreeNode(c).apply { isChecked = !c.excluded; isEnabled = !c.locked }
                    for (t in c.children) cn.add(CheckedTreeNode(t).apply { isChecked = !t.excluded; isEnabled = !t.locked })
                    pn.add(cn)
                }
                root.add(pn)
            }
            (tree.model as DefaultTreeModel).reload()
            TreeUtil.treeNodeTraverser(root).forEach { node ->
                val path = TreePath((node as CheckedTreeNode).path)
                val key = pathKey(path)
                if (key in expanded || (expanded.isEmpty() && path.pathCount <= 2)) tree.expandPath(path)
                if (key == selected) tree.selectionPath = path
            }
        } finally {
            rebuilding = false
        }
    }

    private fun statusText(s: Snapshot, model: List<ProjectNode>): String {
        val counts = model.fold(Counts()) { acc, p -> acc + p.counts }
        return "${StatusText.describe(s.state)}   ${counts.label()}"
    }

    private fun pathKey(path: TreePath): String = path.path.joinToString("/") {
        when (val o = (it as? CheckedTreeNode)?.userObject) {
            is ProjectNode -> o.path
            is ClassNode -> o.type
            is TestNode -> o.dto.key
            else -> "root"
        }
    }

    private fun selectedNode() = tree.lastSelectedPathComponent as? CheckedTreeNode

    private fun showDetails(node: CheckedTreeNode?) {
        when (val o = node?.userObject) {
            is TestNode -> {
                val d = o.dto
                val text = buildString {
                    append(d.displayName).append(" — ")
                    when {
                        o.freshness == null -> append(d.state)
                        o.shownOutcome != null -> append("last result: ").append(o.shownOutcome).append(" (").append(o.freshness)
                            .append(if (o.freshness == "stale") " — the code changed since this ran" else "").append(")")
                        else -> append(d.state)
                    }
                    d.durationMs?.let { append(" (${"%.0f".format(it)} ms)") }
                    append('\n')
                    append(if (o.manual) "Result from a manual run" else "Result from an automatic run")
                    if (o.excluded) append(" (excluded from automatic runs)")
                    append('\n')
                    d.message?.let { append('\n').append(it).append('\n') }
                    d.stackTrace?.let { append('\n').append(it).append('\n') }
                }
                details.show(text, d.state == "failed")
            }
            is ProjectNode -> {
                val b = o.build
                val text = buildString {
                    append(o.path).append('\n')
                    if (b != null) {
                        append("Build: ").append(b.status)
                        b.blockedBy?.let { append(" (blocked by $it)") }
                        append('\n')
                        if (b.output.isNotBlank()) append('\n').append(b.output).append('\n')
                    }
                    o.run?.error?.let { append("\nRun: ").append(o.run.status).append(" — ").append(it).append('\n') }
                }
                details.show(text, b?.status == "failed" || o.run?.error != null)
            }
            else -> details.clear()
        }
    }

    private fun navigate(node: TestNode) {
        val path = node.dto.filePath ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        OpenFileDescriptor(project, file, maxOf(0, (node.dto.line ?: 1) - 1), 0).navigate(true)
    }

    private fun selectedTestKeys(): List<String> {
        val paths = tree.selectionPaths ?: return emptyList()
        return paths.flatMap { p ->
            when (val o = (p.lastPathComponent as CheckedTreeNode).userObject) {
                is TestNode -> listOf(o.dto.key)
                is ClassNode -> o.children.map { it.dto.key }
                is ProjectNode -> o.children.flatMap { c -> c.children.map { it.dto.key } }
                else -> emptyList()
            }
        }.distinct()
    }

    private fun toolbarActions() = DefaultActionGroup().apply {
        add(object : AnAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                val paused = service.state.value.state == "paused"
                e.presentation.icon = if (paused) AllIcons.Actions.Resume else AllIcons.Actions.Pause
                e.presentation.text = if (paused) "Resume No Crunch" else "Pause No Crunch"
            }
            override fun actionPerformed(e: AnActionEvent) {
                if (service.state.value.state == "paused") service.resume() else service.pause()
            }
        })
        add(simple("Run All Tests", AllIcons.RunConfigurations.TestState.Run_run) { service.runAll() })
        add(runSelectedAction)
        add(object : ToggleAction("Show Failures Only", null, AllIcons.RunConfigurations.ShowIgnored) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = failuresOnly
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                failuresOnly = state
                lastModel = emptyList()
                last?.let(::render)
            }
        })
        addSeparator()
        add(simple("Restart Engine", AllIcons.Actions.Restart) { service.restart() })
        add(simple("Settings", AllIcons.General.Settings) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, NoCrunchConfigurable::class.java)
        })
    }

    private fun simple(text: String, icon: Icon, action: () -> Unit) = object : AnAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = action()
    }

    private class Renderer : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(tree: JTree, value: Any, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val r = textRenderer
            when (val o = (value as? CheckedTreeNode)?.userObject) {
                is ProjectNode -> {
                    r.icon = when (o.buildStatus) {
                        "failed" -> AllIcons.General.Error
                        "blocked" -> AllIcons.General.Warning
                        "building" -> AllIcons.Process.Step_1
                        else -> outcomeIcon(o.shownOutcome, o.isFresh) ?: stateIcon(o.counts)
                    }
                    r.append(o.name, if (o.excluded) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    appendCounts(o.counts, o.excluded)
                    appendManual(o.manualCount)
                    when {
                        o.excluded -> r.append("  (excluded)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        o.buildStatus == "failed" -> r.append("  build failed", SimpleTextAttributes.ERROR_ATTRIBUTES)
                        o.buildStatus == "blocked" -> r.append("  blocked by build failure", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        o.run?.status == "unsupportedNotMtp" -> r.append("  unsupported (VSTest)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        o.run?.status == "unsupportedMtpVersion" -> r.append("  unsupported MTP version", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        o.run?.status == "crashed" -> r.append("  test host crashed", SimpleTextAttributes.ERROR_ATTRIBUTES)
                    }
                }
                is ClassNode -> {
                    r.icon = outcomeIcon(o.shownOutcome, o.isFresh) ?: stateIcon(o.counts)
                    r.append(o.type, when {
                        o.excluded -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                        o.counts.failed > 0 -> FAILED
                        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    })
                    appendCounts(o.counts, o.excluded)
                    appendManual(o.manualCount)
                }
                is TestNode -> {
                    val d = o.dto
                    r.icon = when {
                        o.excluded && !o.manual && o.shownOutcome == null -> AllIcons.RunConfigurations.TestIgnored
                        else -> outcomeIcon(o.shownOutcome, o.freshness == null) ?: testIcon(d.state)
                    }
                    r.append(d.displayName, when {
                        o.excluded && !o.manual -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                        o.shownOutcome == "failed" -> FAILED
                        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    })
                    d.durationMs?.takeIf { o.shownOutcome != null }?.let {
                        r.append("  ${"%.0f".format(it)} ms", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    o.freshness?.takeIf { o.shownOutcome != null && it != "excluded" }?.let {
                        r.append("  ${if (it == "running") "running…" else it}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    }
                    if (o.manual) r.append("  ▸ manual run", MANUAL)
                    else if (o.excluded) r.append("  excluded", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
            }
        }

        private companion object {
            val FAILED = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, com.intellij.ui.JBColor.RED)
            val FAILED_BOLD = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, com.intellij.ui.JBColor.RED)
            val MANUAL = SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, com.intellij.ui.JBColor.BLUE)
        }

        private fun appendCounts(c: Counts, excluded: Boolean) {
            val r = textRenderer
            r.append("   ✔ ${c.passed}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            r.append("✖ ${c.failed}", if (c.failed > 0 && !excluded) FAILED_BOLD else SimpleTextAttributes.GRAYED_ATTRIBUTES)
            r.append("  ○ ${c.pending}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        /** Outcome icon, faded when the result is from an earlier run (stale/queued/running). */
        private fun outcomeIcon(outcome: String?, fresh: Boolean): Icon? {
            val icon = when (outcome) {
                "passed" -> AllIcons.RunConfigurations.TestPassed
                "failed" -> AllIcons.RunConfigurations.TestError
                "skipped" -> AllIcons.RunConfigurations.TestSkipped
                else -> return null
            }
            return if (fresh) icon else com.intellij.openapi.util.IconLoader.getTransparentIcon(icon, 0.45f)
        }

        private fun appendManual(n: Int) {
            if (n > 0) textRenderer.append("  ▸ $n manual", MANUAL)
        }

        private fun stateIcon(c: Counts): Icon = when {
            c.failed > 0 -> AllIcons.RunConfigurations.TestError
            c.pending > 0 -> AllIcons.RunConfigurations.TestNotRan
            c.passed > 0 -> AllIcons.RunConfigurations.TestPassed
            else -> AllIcons.RunConfigurations.TestNotRan
        }

        private fun testIcon(state: String): Icon = when (state) {
            "passed" -> AllIcons.RunConfigurations.TestPassed
            "failed" -> AllIcons.RunConfigurations.TestError
            "skipped" -> AllIcons.RunConfigurations.TestSkipped
            "excluded" -> AllIcons.RunConfigurations.TestIgnored
            "running" -> AllIcons.Process.Step_1
            "queued" -> AllIcons.RunConfigurations.TestNotRan
            else -> AllIcons.RunConfigurations.TestUnknown // stale, discovered
        }
    }
}
