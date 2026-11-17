package com.nocrunch.rider.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
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
    /** Checkboxes only show while choosing which tests run automatically (ticked = runs). */
    private var choosingTests = false
    private val tree = CheckboxTree(Renderer { choosingTests }, root, CheckboxTreeBase.CheckPolicy(false, false, false, false))
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
    private var failuresOnly = false
    private val settings = com.nocrunch.rider.settings.NoCrunchSettings.getInstance(project)
    private var rebuilding = false
    private var lastModel: List<ProjectNode> = emptyList()

    /** Runs the selected tests now, including excluded ones; results are marked as a manual run. */
    private val runSelectedAction: AnAction by lazy {
        object : AnAction("Run Selected Tests", null, AllIcons.RunConfigurations.TestState.Run) {
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
                    is ViewNode -> o.rules
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

        val splitter = JBSplitter(false, 0.55f).apply {
            firstComponent = JBScrollPane(tree)
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
        toolbar = ActionManager.getInstance().createActionToolbar("NoCrunch", pausable(toolbarActions()), true)
            .apply { targetComponent = this@NoCrunchPanel }.component

        tree.emptyText.text = StatusText.emptyTree(service.state.value)
        bar.update(ProgressBarModel.from(Counts(), service.state.value, 0))
        // Covers plugin reloads and any case where the startup activity didn't run.
        service.start()
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(runSelectedAction, Separator.create(),
            exclusionAction(include = true), exclusionAction(include = false)), "NoCrunchTreePopup")
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
        val model = TestTreeModel.build(s.tests.values, s.builds, s.rules, failuresOnly, s.runs, settings.state.hideExcluded)
        bar.update(barState(s))
        // While paused, results can't be trusted, so hide them behind a big Resume button.
        val paused = s.state.state == "paused"
        if (paused) waitingLabel.text = StatusText.waiting(s.state.queued)
        (cards.layout as java.awt.CardLayout).show(cards, if (paused) PAUSED_CARD else RESULTS_CARD)
        tree.emptyText.text = StatusText.emptyTree(s.state)
        if (model == lastModel) return
        lastModel = model
        val expanded = TreeUtil.collectExpandedPaths(tree).map { pathKey(it) }.toSet()
        val selected = tree.selectionPath?.let { pathKey(it) }
        rebuilding = true
        try {
            root.removeAllChildren()
            val (buildErrors, testProjects) = TreeView.splitBuildErrors(model)
            // Broken non-test projects are pinned above the test projects whatever the sort.
            if (buildErrors.isNotEmpty()) {
                val group = CheckedTreeNode(BuildErrorsGroup(buildErrors.size)).apply { isEnabled = false }
                for (p in buildErrors) group.add(CheckedTreeNode(p).apply { isEnabled = false })
                root.add(group)
            }
            for (p in TreeView.sortProjects(testProjects, sortMode)) {
                val pn = CheckedTreeNode(p).apply { isChecked = !p.excluded; isEnabled = choosingTests }
                for (v in TreeView.of(p, viewMode, sortMode)) pn.add(treeNode(v))
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

    private val sortMode: SortMode
        get() = SortMode.entries.firstOrNull { it.name == settings.state.sortMode } ?: SortMode.NAME

    private val viewMode: ViewMode
        get() = ViewMode.entries.firstOrNull { it.name == settings.state.viewMode } ?: ViewMode.CLASSES

    /** Tests keep their [TestNode] as the row object; classes and groups use their [ViewNode]. */
    private fun treeNode(v: ViewNode): CheckedTreeNode = when (v) {
        is TestView -> CheckedTreeNode(v.node).apply { isChecked = !v.excluded; isEnabled = choosingTests && !v.locked }
        is ClassView -> CheckedTreeNode(v).apply {
            isChecked = !v.excluded; isEnabled = choosingTests && !v.locked
            v.children.forEach { add(treeNode(it)) }
        }
        is GroupView -> CheckedTreeNode(v).apply {
            isChecked = !v.excluded; isEnabled = choosingTests && !v.locked
            v.children.forEach { add(treeNode(it)) }
        }
    }

    private fun barState(s: Snapshot): BarState = ProgressBarModel.forService(s.tests, s.builds, s.runs, s.rules, s.state)

    private fun pathKey(path: TreePath): String = path.path.joinToString("/") {
        when (val o = (it as? CheckedTreeNode)?.userObject) {
            is ProjectNode -> o.path
            is BuildErrorsGroup -> "build-errors"
            is ClassView -> o.node.type
            is GroupView -> "${o.kind}:${o.label}"
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
            is ViewNode -> {
                val text = buildString {
                    append(o.label).append('\n')
                    append(o.counts.label())
                    o.durationMs?.let { append("   ").append(TestTreeModel.formatDuration(it)) }
                    o.freshness?.let { append("   (").append(it).append(")") }
                    append('\n')
                }
                details.show(text, o.counts.failed > 0)
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
                is ViewNode -> o.leaves.map { it.dto.key }
                is ProjectNode -> o.children.flatMap { c -> c.children.map { it.dto.key } }
                else -> emptyList()
            }
        }.distinct()
    }

    /** Selected rows that can be included/excluded on their own (not locked by an excluded parent), with their rules. */
    private fun selectedExclusionTargets(): List<Pair<Boolean, List<com.nocrunch.rider.engine.ExclusionRuleDto>>> =
        tree.selectionPaths.orEmpty().mapNotNull { p ->
            when (val o = (p.lastPathComponent as? CheckedTreeNode)?.userObject) {
                is ProjectNode -> if (o.isTestProject) o.excluded to listOf(o.rule) else null
                is ViewNode -> if (o.locked) null else o.excluded to o.rules
                is TestNode -> if (o.locked) null else o.excluded to listOf(TestTreeModel.ruleFor(o.dto))
                else -> null
            }
        }

    /** Right-click "Include in / Exclude from Automatic Runs"; shown only when it would change something. */
    private fun exclusionAction(include: Boolean) =
        object : AnAction(if (include) "Include in Automatic Runs" else "Exclude from Automatic Runs") {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = selectedExclusionTargets().any { (excluded, _) -> excluded == include }
            }
            override fun actionPerformed(e: AnActionEvent) {
                val rules = selectedExclusionTargets().filter { (excluded, _) -> excluded == include }.flatMap { it.second }
                if (rules.isNotEmpty()) service.setExcluded(rules, excluded = !include)
            }
        }

    /** Greys out every toolbar action except Settings while No Crunch is paused. */
    private fun pausable(group: DefaultActionGroup): DefaultActionGroup = DefaultActionGroup().apply {
        group.getChildActionsOrStubs().forEach { a ->
            add(if (a is Separator || a.templatePresentation.text == "Settings") a else wrapForPause(a))
        }
    }

    private fun wrapForPause(a: AnAction): AnAction {
        val paused = { service.state.value.state == "paused" }
        return if (a is com.intellij.openapi.actionSystem.ActionGroup) {
            object : com.intellij.openapi.actionSystem.ActionGroupWrapper(a) {
                override fun update(e: AnActionEvent) { super.update(e); if (paused()) e.presentation.isEnabled = false }
            }
        } else {
            object : com.intellij.openapi.actionSystem.AnActionWrapper(a) {
                override fun update(e: AnActionEvent) { super.update(e); if (paused()) e.presentation.isEnabled = false }
            }
        }
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
        addSeparator()
        // One "Run" dropdown instead of three run buttons.
        add(DefaultActionGroup("Run", true).apply {
            templatePresentation.icon = AllIcons.Actions.Execute
            add(simple("Run All Tests", AllIcons.RunConfigurations.TestState.Run_run) { service.runAll() })
            add(simple("Run All Tests Including Excluded", RUN_ALL_INCLUDING_EXCLUDED) {
                // Show results for excluded tests too, even if they were hidden.
                if (settings.state.hideExcluded) { settings.state.hideExcluded = false; lastModel = emptyList(); last?.let(::render) }
                service.runAllIncludingExcluded()
            })
            add(runSelectedAction)
        })
        addSeparator()
        add(object : ToggleAction("Show Failures Only", null, AllIcons.RunConfigurations.ShowIgnored) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = failuresOnly
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                failuresOnly = state
                lastModel = emptyList()
                last?.let(::render)
            }
        })
        // Standard IntelliJ expand/collapse-all actions (same icons and shortcuts as other trees).
        val expander = com.intellij.ide.DefaultTreeExpander(tree)
        add(com.intellij.ide.CommonActionsManager.getInstance().createExpandAllAction(expander, tree))
        add(com.intellij.ide.CommonActionsManager.getInstance().createCollapseAllAction(expander, tree))
        add(object : ToggleAction("Choose Automatic Tests", "Show checkboxes: ticked tests run automatically", AllIcons.Actions.Checked) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = choosingTests
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                choosingTests = state
                lastModel = emptyList()
                last?.let(::render)
            }
        })
        add(DefaultActionGroup("View As", true).apply {
            templatePresentation.icon = AllIcons.Actions.GroupBy
            ViewMode.entries.forEach { mode ->
                add(object : ToggleAction(mode.title) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun isSelected(e: AnActionEvent) = viewMode == mode
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        if (!state) return
                        settings.state.viewMode = mode.name
                        lastModel = emptyList()
                        last?.let(::render)
                    }
                })
            }
        })
        add(DefaultActionGroup("Sort By", true).apply {
            templatePresentation.icon = AllIcons.ObjectBrowser.Sorted
            SortMode.entries.forEach { mode ->
                add(object : ToggleAction(mode.title) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun isSelected(e: AnActionEvent) = sortMode == mode
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        if (!state) return
                        settings.state.sortMode = mode.name
                        lastModel = emptyList()
                        last?.let(::render)
                    }
                })
            }
        })
        add(object : ToggleAction("Hide Excluded Tests", null, AllIcons.Actions.ToggleVisibility) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = settings.state.hideExcluded
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                settings.state.hideExcluded = state
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

    private companion object {
        const val RESULTS_CARD = "results"
        const val PAUSED_CARD = "paused"

        /** The run-all icon with a small "+" badge: run everything, plus the excluded tests. */
        val RUN_ALL_INCLUDING_EXCLUDED: Icon = com.intellij.ui.LayeredIcon(2).apply {
            setIcon(AllIcons.RunConfigurations.TestState.Run_run, 0)
            setIcon(com.intellij.util.IconUtil.scale(AllIcons.General.Add, null, 0.6f), 1, javax.swing.SwingConstants.SOUTH_EAST)
        }
    }

    private fun simple(text: String, icon: Icon, action: () -> Unit) = object : AnAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = action()
    }

    private class Renderer(private val showCheckboxes: () -> Boolean) : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(tree: JTree, value: Any, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val r = textRenderer
            val editing = showCheckboxes()
            val obj = (value as? CheckedTreeNode)?.userObject
            // Build-error rows have nothing to include or exclude.
            checkbox.isVisible = editing && obj !is BuildErrorsGroup && (obj as? ProjectNode)?.isTestProject != false
            when (val o = obj) {
                is BuildErrorsGroup -> {
                    r.icon = AllIcons.General.Error
                    r.append("Build errors", FAILED_BOLD)
                    r.append("  ${o.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is ProjectNode -> {
                    r.icon = when (o.buildStatus) {
                        "failed" -> AllIcons.General.Error
                        "blocked" -> AllIcons.General.Warning
                        "building" -> AllIcons.Process.Step_1
                        else -> outcomeIcon(o.shownOutcome, o.isFresh) ?: stateIcon(o.counts)
                    }
                    r.append(o.name, when {
                        o.excluded -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                        o.shownOutcome == "failed" -> if (o.isFresh) FAILED_BOLD else STALE_FAILED_BOLD
                        else -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    })
                    if (o.isTestProject) {
                        appendCounts(o.counts, o.excluded)
                        appendDuration(o.durationMs)
                        appendFreshness(o.freshness)
                        appendManual(o.manualCount)
                    }
                    when {
                        o.excluded -> r.append("  (excluded)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        o.buildStatus == "failed" -> r.append("  build failed", SimpleTextAttributes.ERROR_ATTRIBUTES)
                        o.buildStatus == "blocked" -> r.append("  blocked by ${o.blockedByName ?: "build failure"}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        o.run?.status == "unsupportedNotMtp" -> r.append("  unsupported (VSTest)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        o.run?.status == "unsupportedMtpVersion" -> r.append("  unsupported MTP version", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        o.run?.status == "crashed" -> r.append("  test host crashed", SimpleTextAttributes.ERROR_ATTRIBUTES)
                    }
                }
                is ViewNode -> {
                    r.icon = outcomeIcon(o.shownOutcome, o.isFresh) ?: stateIcon(o.counts)
                    r.append(o.label, when {
                        o.excluded && o.manualCount == 0 -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                        o.shownOutcome == "failed" -> if (o.isFresh) FAILED else STALE_FAILED
                        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    })
                    appendCounts(o.counts, o.excluded)
                    appendDuration(o.durationMs)
                    appendFreshness(o.freshness)
                    appendManual(o.manualCount)
                    // Without checkboxes, say which rows are left out of automatic runs.
                    if (o.excluded && !editing) r.append("  (excluded)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
                is TestNode -> {
                    val d = o.dto
                    r.icon = when {
                        o.excluded && !o.manual && o.shownOutcome == null -> AllIcons.RunConfigurations.TestUnknown
                        else -> outcomeIcon(o.shownOutcome, o.freshness == null) ?: testIcon(d.state)
                    }
                    r.append(d.displayName, when {
                        o.excluded && !o.manual -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                        o.shownOutcome == "failed" -> if (o.freshness == null) FAILED else STALE_FAILED
                        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    })
                    d.durationMs?.takeIf { o.shownOutcome != null }?.let { appendDuration(it) }
                    appendFreshness(o.freshness)
                    if (o.manual) r.append("  ▸ manual run", MANUAL)
                    else if (o.excluded && !o.locked && !editing) r.append("  (excluded)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
            }
        }

        private companion object {
            val FAILED = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, com.intellij.ui.JBColor.RED)
            val FAILED_BOLD = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, com.intellij.ui.JBColor.RED)
            val MANUAL = SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, com.intellij.ui.JBColor.BLUE)
            /** Muted red for failures from an earlier run (stale, queued or running again). */
            private val STALE_RED = com.intellij.ui.JBColor(java.awt.Color(0xC9, 0x8A, 0x8A), java.awt.Color(0x8C, 0x55, 0x55))
            val STALE_FAILED = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, STALE_RED)
            val STALE_FAILED_BOLD = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, STALE_RED)
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

        private fun appendDuration(ms: Double?) {
            if (ms != null) textRenderer.append("  ${TestTreeModel.formatDuration(ms)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        private fun appendFreshness(freshness: String?) {
            val text = when (freshness) {
                null -> return
                "running" -> "running…"
                else -> freshness
            }
            textRenderer.append("  $text", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
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

/** Row object for the pinned section listing non-test projects whose build broke. */
data class BuildErrorsGroup(val count: Int)
