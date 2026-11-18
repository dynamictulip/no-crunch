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
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import com.nocrunch.rider.NoCrunchService
import com.nocrunch.rider.settings.NoCrunchConfigurable
import kotlinx.coroutines.Dispatchers
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
    /** Some test shown in the tree is failing (used to drop the green tick highlights). Set once per model, not per painted row. */
    private var anyFailures = false
    private val tree = CheckboxTree(Renderer({ choosingTests }, { nameColouring }, { anyFailures }), root, CheckboxTreeBase.CheckPolicy(false, false, false, false))
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
    /** The model the tree shows; null forces the next render to sync (an empty list would match an empty model). */
    private var lastModel: List<ProjectNode>? = null

    /**
     * "Break into First Failing Test": the first failing test in the selection (tree order), debugged by name with a
     * temporary breakpoint on its first line, so the debugger stops as the test starts.
     */
    private val breakIntoFailingAction: AnAction by lazy {
        object : AnAction("Break into First Failing Test", "Debug the first failing test, stopping on its first line",
            AllIcons.Actions.StartDebugger) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isVisible = tree.selectionPaths?.isNotEmpty() == true
                e.presentation.isEnabled = firstFailingSelectedTest() != null
            }
            override fun actionPerformed(e: AnActionEvent) {
                val node = firstFailingSelectedTest() ?: return
                setTemporaryBreakpointAtStart(node)
                debugTest(node)
            }
        }
    }

    /** The first failing test (current or stale result) under the selected rows, in the order the tree shows them. */
    private fun firstFailingSelectedTest(): TestNode? {
        val selected = tree.selectionPaths.orEmpty().sortedBy { tree.getRowForPath(it) }
        for (path in selected) {
            val found = TreeUtil.treeNodeTraverser(path.lastPathComponent as CheckedTreeNode).preOrderDfsTraversal()
                .mapNotNull { (it as? CheckedTreeNode)?.userObject as? TestNode }
                .firstOrNull { it.shownOutcome == "failed" && it.canDebug }
            if (found != null) return found
        }
        return null
    }

    private val TestNode.canDebug: Boolean
        get() = (dto.type != null && dto.method != null) || (dto.filePath != null && dto.line != null)

    /** A breakpoint on the test's first statement that removes itself once hit. Leaves an existing breakpoint alone. */
    private fun setTemporaryBreakpointAtStart(node: TestNode) {
        val path = node.dto.filePath ?: return
        val line = node.dto.line ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file) ?: return
        val bodyLine = TestLocator.firstBodyLine(document.charsSequence, line - 1, node.dto.method?.substringBefore('(')) ?: return
        val manager = com.intellij.xdebugger.XDebuggerManager.getInstance(project).breakpointManager
        val exists = manager.allBreakpoints.any { it is com.intellij.xdebugger.breakpoints.XLineBreakpoint<*> && it.fileUrl == file.url && it.line == bodyLine }
        if (!exists) com.intellij.xdebugger.XDebuggerUtil.getInstance().toggleLineBreakpoint(project, file, bodyLine, true)
    }

    /**
     * Debugs the test by name with a run configuration filtered to it. If that isn't possible (no class/method, or
     * Rider's run-configuration classes have changed), falls back to Rider's caret-based Debug action.
     */
    private fun debugTest(node: TestNode) {
        val d = node.dto
        val root = service.repoRoot
        if (root != null && d.type != null && d.method != null) {
            try {
                val projectFile = java.nio.file.Path.of(root, d.project)
                val args = com.nocrunch.rider.debug.TestFilter.args(com.nocrunch.rider.debug.TestFilter.detect(projectFile), d.type, d.method)
                val name = "Debug ${d.type.substringAfterLast('.')}.${d.method.substringBefore('(')}"
                com.nocrunch.rider.debug.RiderDebugLauncher.debug(project, projectFile, args, name)
                return
            } catch (e: Throwable) {
                if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                LOG.warn("Debug by name failed for ${d.key}; falling back to Rider's caret-based action", e)
            }
        }
        debugInRider(node)
    }

    /**
     * Fallback: Rider's "Debug" context action works out which test to debug from the editor caret, so open the test's file,
     * put the caret on the test method's name, and run that action on the editor. The action is answered by Rider's
     * backend, which needs a moment to see the newly opened editor and caret, so keep trying for a few seconds.
     */
    private fun debugInRider(node: TestNode) {
        val path = node.dto.filePath ?: return
        val line = (node.dto.line ?: return) - 1
        val action = ActionManager.getInstance().getAction(RIDER_DEBUG_TEST_ACTION) ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, file, maxOf(0, line), 0), true) ?: return
        val methodName = node.dto.method?.substringBefore('(')
        editor.caretModel.moveToOffset(TestLocator.caretOffset(editor.document.charsSequence, line, methodName))

        service.scope.launch(Dispatchers.EDT) {
            repeat(DEBUG_ATTEMPTS) { attempt ->
                kotlinx.coroutines.delay(DEBUG_RETRY_MS)
                val accepted = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    ActionManager.getInstance().tryToExecute(action, null, editor.contentComponent, "NoCrunchTreePopup", true)
                        .doWhenDone(Runnable { if (cont.isActive) cont.resumeWith(Result.success(true)) })
                        .doWhenRejected(Runnable { if (cont.isActive) cont.resumeWith(Result.success(false)) })
                }
                LOG.info("Debug Test ${node.dto.key}: attempt ${attempt + 1} ${if (accepted) "accepted" else "rejected"}")
                if (accepted) return@launch
            }
            com.intellij.codeInsight.hint.HintManager.getInstance().showErrorHint(editor, "Rider couldn't find a test to debug here")
        }
    }

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
        // While the tree updates, render() refreshes the details once at the end instead.
        tree.addTreeSelectionListener { if (!rebuilding) showDetails(tree.lastSelectedPathComponent as? CheckedTreeNode) }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) (selectedNode()?.userObject as? TestNode)?.let { navigate(it) }
            }
        })

        // A visible 1px divider (Rider's border colour), so it's clear long rows run under the details pane.
        val splitter = com.intellij.ui.OnePixelSplitter(false, 0.55f).apply {
            firstComponent = JBScrollPane(tree).apply { border = com.intellij.util.ui.JBUI.Borders.empty() }
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
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(runSelectedAction, breakIntoFailingAction, Separator.create(),
            exclusionAction(include = true), exclusionAction(include = false)), "NoCrunchTreePopup")
        val job = service.scope.launch(Dispatchers.EDT) { collect() }
        Disposer.register(parent) { job.cancel() }
    }

    /** The model is built off the EDT, at a steady rate, and shared with the tool window icon and status bar. */
    private suspend fun collect() {
        NoCrunchResults.getInstance(project).snapshots.collect { render(it) }
    }

    private var last: ResultsSnapshot? = null

    /** The empty tree's "Show all tests" link: both view filters off. */
    private fun showAllTests() {
        failuresOnly = false
        settings.state.hideExcluded = false
        lastModel = null
        last?.let(::render)
    }

    private fun render(s: ResultsSnapshot) {
        last = s
        // The shared model has every test (the bar counts them all); the tree shows it through the view filters.
        val model = TestTreeModel.filter(s.projects, failuresOnly, settings.state.hideExcluded)
        bar.update(s.bar)
        // While paused, results can't be trusted, so hide them behind a big Resume button.
        val paused = s.state.state == "paused"
        if (paused) waitingLabel.text = StatusText.waiting(s.state.queued)
        (cards.layout as java.awt.CardLayout).show(cards, if (paused) PAUSED_CARD else RESULTS_CARD)
        val hiddenBy = HiddenBy.of(hasTests = s.projects.any { it.hasTests }, shownEmpty = model.isEmpty(), failuresOnly, settings.state.hideExcluded)
        tree.emptyText.text = StatusText.emptyTree(s.state, hiddenBy)
        if (hiddenBy != null) tree.emptyText.appendSecondaryText("Show all tests", SimpleTextAttributes.LINK_ATTRIBUTES) { showAllTests() }
        if (model == lastModel) return
        lastModel = model
        val failuresBefore = anyFailures
        anyFailures = model.any { it.counts.failed > 0 }
        val treeModel = tree.model as DefaultTreeModel
        val expanded = TreeUtil.collectExpandedPaths(tree).map { pathKey(it) }.toSet()
        val selected = tree.selectionPaths.orEmpty().map { pathKey(it) }.toSet()
        // The selection listener stays quiet while rows change; the details pane is refreshed once at the end.
        rebuilding = true
        try {
            // Updated in place, not rebuilt: reloading the model cleared the selection (and the details pane with
            // it), collapsed a multi-selection to one row and re-expanded everything after Collapse All.
            TreeSync.syncTree(tree, root, specs(model))
            // The green tick highlights depend on whether anything fails anywhere, so every row may look different.
            if (anyFailures != failuresBefore) TreeUtil.treeNodeTraverser(root).forEach { treeModel.nodeChanged(it as CheckedTreeNode) }
            // A row that moved (sorting by status or run time) was taken out and put back, which collapses and
            // deselects it; put that back.
            val reselect = mutableListOf<TreePath>()
            TreeUtil.treeNodeTraverser(root).forEach { node ->
                val path = TreePath((node as CheckedTreeNode).path)
                val key = pathKey(path)
                if (key in expanded && !tree.isExpanded(path)) tree.expandPath(path)
                if (key in selected) reselect += path
            }
            if (reselect.isNotEmpty() && reselect.size != tree.selectionCount) tree.selectionPaths = reselect.toTypedArray()
        } finally {
            rebuilding = false
        }
        // The selected row's results may have changed; the pane leaves itself alone if its text hasn't.
        showDetails(selectedNode())
    }

    private val sortMode: SortMode
        get() = SortMode.entries.firstOrNull { it.name == settings.state.sortMode } ?: SortMode.NAME

    private val nameColouring: NameColouring
        get() = NameColouring.entries.firstOrNull { it.name == settings.state.nameColouring } ?: NameColouring.OFF

    private val viewMode: ViewMode
        get() = ViewMode.entries.firstOrNull { it.name == settings.state.viewMode } ?: ViewMode.CLASSES

    /** The rows the tree should show. Broken non-test projects are pinned above the test projects whatever the sort. */
    private fun specs(model: List<ProjectNode>): List<TreeSpec> {
        val (buildErrors, testProjects) = TreeView.splitBuildErrors(model)
        val errors = if (buildErrors.isEmpty()) emptyList() else listOf(
            TreeSpec(BUILD_ERRORS_KEY, BuildErrorsGroup(buildErrors.size), enabled = false,
                children = buildErrors.map { TreeSpec(it.path, it, enabled = false) }),
        )
        return errors + TreeView.sortProjects(testProjects, sortMode).map { p ->
            TreeSpec(p.path, p, checked = !p.excluded, enabled = choosingTests, children = TreeView.of(p, viewMode, sortMode).map(::spec))
        }
    }

    /** Tests keep their [TestNode] as the row object; classes and groups use their [ViewNode]. */
    private fun spec(v: ViewNode): TreeSpec = when (v) {
        is TestView -> TreeSpec(v.node.dto.key, v.node, !v.excluded, choosingTests && !v.locked,
            v.node.rows.map { TreeSpec(it.dto.key, it, checked = !it.excluded, enabled = false) })
        is ClassView -> TreeSpec(v.node.type, v, !v.excluded, choosingTests && !v.locked, v.children.map(::spec))
        is GroupView -> TreeSpec("${v.kind}:${v.label}", v, !v.excluded, choosingTests && !v.locked, v.children.map(::spec))
    }

    private fun pathKey(path: TreePath): String = path.path.joinToString("/") { (it as? KeyedTreeNode)?.key ?: "root" }

    private fun selectedNode() = tree.lastSelectedPathComponent as? CheckedTreeNode

    private fun showDetails(node: CheckedTreeNode?) {
        when (val o = node?.userObject) {
            is TestNode -> {
                val d = o.dto
                details.show {
                    normal(d.displayName).normal(" — ")
                    when {
                        o.freshness == null -> status(d.state, d.state == "failed")
                        o.shownOutcome != null -> normal("last result: ").status(o.shownOutcome!!, o.shownOutcome == "failed")
                            .normal(" (").normal(o.freshness!!).normal(if (o.freshness == "stale") " — the code changed since this ran" else "").normal(")")
                        else -> normal(d.state)
                    }
                    d.durationMs?.let { normal(" (${"%.0f".format(it)} ms)") }
                    normal("\n")
                    muted(if (o.manual) "Result from a manual run" else "Result from an automatic run")
                    if (o.excluded) muted(" (excluded from automatic runs)")
                    normal("\n")
                    d.message?.let { normal("\n").normal(it).normal("\n") }
                    d.stackTrace?.let { normal("\n").normal(it).normal("\n") }
                }
            }
            is ProjectNode -> {
                val b = o.build
                details.show {
                    normal(o.path).normal("\n")
                    if (b != null) {
                        normal("Build: ").status(b.status, b.status == "failed")
                        b.blockedBy?.let { normal(" (blocked by $it)") }
                        normal("\n")
                        if (b.output.isNotBlank()) normal("\n").normal(b.output).normal("\n")
                    }
                    o.run?.error?.let { normal("\nRun: ").red(o.run.status).normal(" — ").normal(it).normal("\n") }
                }
            }
            is ViewNode -> details.show {
                normal(o.label).normal("\n")
                normal("✔ ${o.counts.passed}  ").status("✖ ${o.counts.failed}", o.counts.failed > 0).normal("  ○ ${o.counts.pending}")
                o.durationMs?.let { normal("   ").normal(TestTreeModel.formatDuration(it)) }
                o.freshness?.let { normal("   (").normal(it).normal(")") }
                normal("\n")
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
                is TestNode -> o.runKeys
                is ViewNode -> o.leaves.flatMap { it.runKeys }
                is ProjectNode -> o.children.flatMap { c -> c.children.flatMap { it.runKeys } }
                else -> emptyList()
            }
        }.distinct()
    }

    /** Selected rows that can be included/excluded on their own (everything except theory data rows), with their rules. */
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
                if (settings.state.hideExcluded) { settings.state.hideExcluded = false; lastModel = null; last?.let(::render) }
                service.runAllIncludingExcluded()
            })
            add(runSelectedAction)
        })
        addSeparator()
        // Tree
        // Standard IntelliJ expand/collapse-all actions (same icons and shortcuts as other trees).
        val expander = com.intellij.ide.DefaultTreeExpander(tree)
        add(com.intellij.ide.CommonActionsManager.getInstance().createExpandAllAction(expander, tree))
        add(com.intellij.ide.CommonActionsManager.getInstance().createCollapseAllAction(expander, tree))
        addSeparator()
        // Filter and display
        add(DefaultActionGroup("Filter", true).apply {
            templatePresentation.icon = AllIcons.General.Filter
            add(object : ToggleAction("Show Failures Only") {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = failuresOnly
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    failuresOnly = state
                    lastModel = null
                    last?.let(::render)
                }
            })
            add(object : ToggleAction("Hide Excluded Tests") {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = settings.state.hideExcluded
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    settings.state.hideExcluded = state
                    lastModel = null
                    last?.let(::render)
                }
            })
        })
        add(DefaultActionGroup("View As", true).apply {
            templatePresentation.icon = AllIcons.Actions.GroupBy
            addSeparator("Group By")
            ViewMode.entries.forEach { mode ->
                add(object : ToggleAction(mode.title) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun isSelected(e: AnActionEvent) = viewMode == mode
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        if (!state) return
                        settings.state.viewMode = mode.name
                        lastModel = null
                        last?.let(::render)
                    }
                })
            }
            addSeparator("Colour Test Names")
            NameColouring.entries.forEach { mode ->
                add(object : ToggleAction(mode.title) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun isSelected(e: AnActionEvent) = nameColouring == mode
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        if (!state) return
                        settings.state.nameColouring = mode.name
                        tree.repaint()
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
                        lastModel = null
                        last?.let(::render)
                    }
                })
            }
        })
        addSeparator()
        // Mode: tick which tests run automatically
        add(object : ToggleAction("Choose Automatic Tests", "Show checkboxes: ticked tests run automatically", AllIcons.Actions.Edit) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = choosingTests
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                choosingTests = state
                lastModel = null
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
        const val BUILD_ERRORS_KEY = "build-errors"
        /** Rider's editor context action that debugs the test(s) at the caret. */
        const val RIDER_DEBUG_TEST_ACTION = "RiderUnitTestDebugContextAction"
        const val DEBUG_ATTEMPTS = 12
        const val DEBUG_RETRY_MS = 250L
        val LOG = com.intellij.openapi.diagnostic.logger<NoCrunchPanel>()

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

    private class Renderer(
        private val showCheckboxes: () -> Boolean,
        private val colouring: () -> NameColouring,
        /** Some test in the tree is failing: then only the red crosses are highlighted, not the green ticks. */
        private val anyFailures: () -> Boolean,
    ) : CheckboxTree.CheckboxTreeCellRenderer() {
        /** Counts, run times and notes after the name, in the normal UI font (the name is monospace). */
        private val stats = com.intellij.ui.SimpleColoredComponent().apply {
            isOpaque = false
            // The new UI draws the selected row as a rounded box inset from the tree's right edge; end inside it.
            ipad = com.intellij.util.ui.JBUI.insetsRight(14)
        }

        init {
            // The base renderer lays out checkbox and text with a BorderLayout; if that ever changes, stats go in the text.
            if (layout is BorderLayout) add(stats, BorderLayout.EAST)
        }

        private val out: com.intellij.ui.SimpleColoredComponent get() = if (stats.parent == this) stats else textRenderer

        override fun customizeRenderer(tree: JTree, value: Any, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val r = textRenderer
            stats.clear()
            stats.font = tree.font
            val editing = showCheckboxes()
            val obj = (value as? CheckedTreeNode)?.userObject
            // The editor's monospace font, so names read like code and data-row columns line up.
            r.font = monospace(tree.font)
            // Build-error rows have nothing to include or exclude.
            // Theory rows follow their theory; they can't be included or excluded on their own.
            checkbox.isVisible = editing && obj !is BuildErrorsGroup && (obj as? ProjectNode)?.isTestProject != false &&
                (obj as? TestNode)?.isRow != true
            when (val o = obj) {
                is BuildErrorsGroup -> {
                    r.icon = AllIcons.General.Error
                    r.append("Build errors", FAILED_BOLD)
                    out.append("  ${o.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is ProjectNode -> {
                    r.icon = withKind(when (o.buildStatus) {
                        "failed" -> AllIcons.General.Error
                        "blocked" -> AllIcons.General.Warning
                        "building" -> AllIcons.Process.Step_1
                        else -> if (o.excluded) KindIcons.excluded else outcomeIcon(o.shownOutcome, o.isFresh)
                    }, KindIcons.project)
                    when {
                        o.excluded -> r.append(o.name, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        o.shownOutcome == "failed" -> r.append(o.name, if (o.isFresh) FAILED_BOLD else STALE_FAILED_BOLD)
                        else -> appendName(o.name, bold = true, parent = true)
                    }
                    if (o.isTestProject) {
                        appendCounts(o.counts, o.excluded)
                        appendDuration(o.durationMs)
                        appendFreshness(o.freshness)
                        appendManual(o.manualCount)
                    }
                    when {
                        o.excluded -> out.append("  (excluded)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        o.buildStatus == "failed" -> out.append("  build failed", SimpleTextAttributes.ERROR_ATTRIBUTES)
                        o.buildStatus == "blocked" -> out.append("  blocked by ${o.blockedByName ?: "build failure"}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        o.run?.status == "unsupportedNotMtp" -> out.append("  unsupported (VSTest)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        o.run?.status == "unsupportedMtpVersion" -> out.append("  unsupported MTP version", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        o.run?.status == "crashed" -> out.append("  test host crashed", SimpleTextAttributes.ERROR_ATTRIBUTES)
                    }
                }
                is ViewNode -> {
                    // Grouping rows and classes show their status beside what they are.
                    val status = if (o.excluded) KindIcons.excluded else outcomeIcon(o.shownOutcome, o.isFresh)
                    r.icon = when {
                        o is GroupView && o.kind == GroupKind.NAMESPACE -> withKind(status, KindIcons.namespace)
                        o is GroupView && o.kind == GroupKind.FOLDER -> withKind(status, KindIcons.folder)
                        o is GroupView -> withKind(status, KindIcons.file(o.label))
                        else -> withKind(status, KindIcons.testSet)
                    }
                    when {
                        // Excluded rows are grey unless they hold a current manual result.
                        o.excluded && o.leaves.none { it.manual && it.freshness == null } -> r.append(o.label, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        o.shownOutcome == "failed" -> r.append(o.label, if (o.isFresh) FAILED else STALE_FAILED)
                        else -> appendName(o.label, parent = true)
                    }
                    appendCounts(o.counts, o.excluded)
                    appendDuration(o.durationMs)
                    appendFreshness(o.freshness)
                    appendManual(o.manualCount)
                    // Without checkboxes, say which rows are left out of automatic runs.
                    if (o.excluded && !o.parentExcluded && !editing) out.append("  (excluded)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
                is TestNode -> {
                    val d = o.dto
                    // A theory or group of data rows shows its status beside the two circles, like a class. A single
                    // test shows its status once there's a result (or while running), otherwise what it is.
                    // Excluded tests always show the excluded icon; a manual run's result goes beside the run time.
                    val status = if (o.excluded) KindIcons.excluded
                        else outcomeIcon(o.shownOutcome, o.freshness == null) ?: AllIcons.Process.Step_1.takeIf { d.state == "running" }
                    r.icon = if (o.rows.isNotEmpty()) withKind(status, KindIcons.testSet) else status ?: KindIcons.test
                    // Failed and excluded rows keep their red or grey; colour coding only applies to the rest.
                    when {
                        // Excluded tests are grey unless they have a current manual result; a stale one goes grey again.
                        o.excluded && !(o.manual && o.freshness == null) -> r.append(o.label, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        o.shownOutcome == "failed" -> r.append(o.label, if (o.freshness == null) FAILED else STALE_FAILED)
                        o.isRow -> appendArgs(o.label)
                        else -> appendName(o.label)
                    }
                    if (o.rows.isNotEmpty()) appendCounts(o.counts, o.excluded)
                    if (o.excluded && o.rows.isEmpty()) {
                        val fresh = o.freshness == null
                        when (o.shownOutcome) {
                            "passed" -> out.append("   ✔", if (fresh) PASSED_BOLD else SimpleTextAttributes.GRAYED_ATTRIBUTES)
                            "failed" -> out.append("   ✖", if (fresh) FAILED_BOLD else SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                    d.durationMs?.takeIf { o.shownOutcome != null }?.let { appendDuration(it) }
                    appendFreshness(o.freshness)
                    // A theory's rows share its run, so only the theory says it was a manual run.
                    if (o.manual && !o.isRow) out.append("  ▸ manual run", MANUAL)
                    else if (o.excluded && !o.parentExcluded && !o.isRow && !editing) out.append("  (excluded)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
            }
        }

        /**
         * Appends a name, colour-coded by part when that's switched on. Failed and excluded rows don't use this.
         * Only test names are ever snake case, so [parent] rows (projects, namespaces, classes, files) always split
         * into PascalCase words.
         */
        private fun appendName(name: String, bold: Boolean = false, parent: Boolean = false) {
            val style = if (bold) SimpleTextAttributes.STYLE_BOLD else SimpleTextAttributes.STYLE_PLAIN
            val mode = colouring().let { if (parent && it != NameColouring.OFF) NameColouring.PASCAL else it }
            NameColouring.split(name, mode).forEach { (text, colour) ->
                textRenderer.append(text, SimpleTextAttributes(style, colour?.let { ThemeColours.nameParts()[it] }))
            }
        }

        private fun monospace(base: java.awt.Font): java.awt.Font {
            val name = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.editorFontName
            return java.awt.Font(name, java.awt.Font.PLAIN, base.size)
        }

        /** Theory row arguments: each name and value takes the next colour, whichever colouring mode is on. */
        private fun appendArgs(text: String) {
            if (colouring() == NameColouring.OFF) { textRenderer.append(text, SimpleTextAttributes.REGULAR_ATTRIBUTES); return }
            NameColouring.splitArgs(text).forEach { (t, colour) ->
                textRenderer.append(t, colour?.let { SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ThemeColours.nameParts()[it]) } ?: SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }

        private companion object {
            val FAILED = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, com.intellij.ui.JBColor.RED)
            val FAILED_BOLD = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, com.intellij.ui.JBColor.RED)
            val PASSED_BOLD = SimpleTextAttributes(
                SimpleTextAttributes.STYLE_BOLD,
                com.intellij.ui.JBColor(java.awt.Color(0x3C, 0x8A, 0x3F), java.awt.Color(0x5F, 0xB8, 0x65)),
            )
                        val MANUAL = SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, com.intellij.ui.JBColor.BLUE)
            /** Muted red for failures from an earlier run (stale, queued or running again). */
            private val STALE_RED = com.intellij.ui.JBColor(java.awt.Color(0xC9, 0x8A, 0x8A), java.awt.Color(0x8C, 0x55, 0x55))
            val STALE_FAILED = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, STALE_RED)
            val STALE_FAILED_BOLD = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, STALE_RED)
        }

        private fun appendCounts(c: Counts, excluded: Boolean) {
            val r = textRenderer
            // Highlight the cross when something fails, otherwise the tick when something passed.
            val allPassed = c.failed == 0 && c.passed > 0 && !excluded && !anyFailures()
            out.append("   ✔ ${c.passed}", if (allPassed) PASSED_BOLD else SimpleTextAttributes.GRAYED_ATTRIBUTES)
            out.append("  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            out.append("✖ ${c.failed}", if (c.failed > 0 && !excluded) FAILED_BOLD else SimpleTextAttributes.GRAYED_ATTRIBUTES)
            out.append("  ○ ${c.pending}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        /** Status icon followed by a kind icon (project, namespace, folder, file), like Rider's test tree. */
        private fun withKind(status: Icon?, kind: Icon): Icon =
            if (status == null) kind else com.intellij.ui.RowIcon(status, com.intellij.util.ui.EmptyIcon.create(com.intellij.util.ui.JBUI.scale(4), 1), kind)

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
            if (ms != null) out.append("  ${TestTreeModel.formatDuration(ms)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        private fun appendFreshness(freshness: String?) {
            val text = when (freshness) {
                null -> return
                "running" -> "running…"
                else -> freshness
            }
            out.append("  $text", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        }

        private fun appendManual(n: Int) {
            if (n > 0) out.append("  ▸ $n manual", MANUAL)
        }

    }
}

/** Row object for the pinned section listing non-test projects whose build broke. */
data class BuildErrorsGroup(val count: Int)
