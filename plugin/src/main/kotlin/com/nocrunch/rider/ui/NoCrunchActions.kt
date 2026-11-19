package com.nocrunch.rider.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionGroupWrapper
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.LayeredIcon
import com.intellij.util.IconUtil
import com.intellij.util.ui.tree.TreeUtil
import com.nocrunch.rider.NoCrunchService
import com.nocrunch.rider.engine.EngineState
import com.nocrunch.rider.settings.NoCrunchConfigurable
import com.nocrunch.rider.settings.NoCrunchSettings
import com.nocrunch.rider.view.NameColouring
import com.nocrunch.rider.view.SortMode
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingConstants
import javax.swing.tree.TreePath

/** What the tool window's actions need from the panel. */
internal interface ActionHost {
    val tree: CheckboxTree
    /** Show Failures Only (not saved). */
    var failuresOnly: Boolean
    /** The checkboxes for choosing automatic tests are showing. */
    var choosingTests: Boolean
    /** Rebuilds the tree from the latest results, even if they haven't changed (a view option has). */
    fun redraw()
    /** Row objects of the selected rows. */
    fun selectedObjects(): List<Any?>
    /** The first failing test under the selected rows, in the order the tree shows them. */
    fun firstFailingSelectedTest(): TestNode?
}

/** The tool window's toolbar and the tree's right-click menu. */
internal class NoCrunchActions(
    private val project: Project,
    private val service: NoCrunchService,
    private val settings: NoCrunchSettings,
    private val host: ActionHost,
    private val debugger: TestDebugger,
) {
    private val tree get() = host.tree
    private val paused get() = service.state.value.state == EngineState.PAUSED

    /** The toolbar: everything except Settings and Restart Engine greys out while No Crunch is paused. */
    fun toolbar(): DefaultActionGroup = pausable(toolbarActions())

    fun popup(): DefaultActionGroup = DefaultActionGroup(runSelectedAction, breakIntoFailingAction, Separator.create(),
        exclusionAction(include = true), exclusionAction(include = false))

    /**
     * "Break into First Failing Test": the first failing test in the selection (tree order), debugged by name with a
     * temporary breakpoint on its first line, so the debugger stops as the test starts.
     */
    private val breakIntoFailingAction: AnAction =
        object : AnAction("Break into First Failing Test", "Debug the first failing test, stopping on its first line",
            AllIcons.Actions.StartDebugger) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isVisible = tree.selectionPaths?.isNotEmpty() == true
                e.presentation.isEnabled = host.firstFailingSelectedTest() != null
            }
            override fun actionPerformed(e: AnActionEvent) {
                debugger.breakInto(host.firstFailingSelectedTest() ?: return)
            }
        }

    /** Runs the selected tests now, including excluded ones; results are marked as a manual run. */
    private val runSelectedAction: AnAction =
        object : AnAction("Run Selected Tests", null, AllIcons.RunConfigurations.TestState.Run) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = tree.selectionPaths?.isNotEmpty() == true
            }
            override fun actionPerformed(e: AnActionEvent) {
                val keys = TreeSelection.runKeys(host.selectedObjects()).takeIf { it.isNotEmpty() } ?: return
                // Expand what was run so each test's result is visible as it comes in.
                tree.selectionPaths?.forEach { selected ->
                    TreeUtil.treeNodeTraverser(selected.lastPathComponent as CheckedTreeNode).forEach { node ->
                        tree.expandPath(TreePath((node as CheckedTreeNode).path))
                    }
                }
                service.runTests(keys)
            }
        }

    /** Right-click "Include in / Exclude from Automatic Runs"; shown only when it would change something. */
    private fun exclusionAction(include: Boolean) =
        object : AnAction(if (include) "Include in Automatic Runs" else "Exclude from Automatic Runs") {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = TreeSelection.exclusionTargets(host.selectedObjects()).any { (excluded, _) -> excluded == include }
            }
            override fun actionPerformed(e: AnActionEvent) {
                val rules = TreeSelection.exclusionTargets(host.selectedObjects()).filter { (excluded, _) -> excluded == include }.flatMap { it.second }
                if (rules.isNotEmpty()) service.setExcluded(rules, excluded = !include)
            }
        }

    /** Greys out every toolbar action except Settings and Restart Engine while No Crunch is paused. */
    private fun pausable(group: DefaultActionGroup): DefaultActionGroup = DefaultActionGroup().apply {
        group.getChildActionsOrStubs().forEach { a ->
            add(if (a is Separator || enabledWhilePaused(a.templatePresentation.text)) a else wrapForPause(a))
        }
    }

    private fun wrapForPause(a: AnAction): AnAction =
        if (a is ActionGroup) {
            object : ActionGroupWrapper(a) {
                override fun update(e: AnActionEvent) { super.update(e); if (paused) e.presentation.isEnabled = false }
            }
        } else {
            object : AnActionWrapper(a) {
                override fun update(e: AnActionEvent) { super.update(e); if (paused) e.presentation.isEnabled = false }
            }
        }

    private fun toolbarActions() = DefaultActionGroup().apply {
        add(object : AnAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.icon = if (paused) AllIcons.Actions.Resume else AllIcons.Actions.Pause
                e.presentation.text = if (paused) "Resume No Crunch" else "Pause No Crunch"
            }
            override fun actionPerformed(e: AnActionEvent) {
                if (paused) service.resume() else service.pause()
            }
        })
        addSeparator()
        // One "Run" dropdown instead of three run buttons.
        add(DefaultActionGroup("Run", true).apply {
            templatePresentation.icon = AllIcons.Actions.Execute
            add(simple("Run All Tests", AllIcons.RunConfigurations.TestState.Run_run) { service.runAll() })
            add(simple("Run All Tests Including Excluded", RUN_ALL_INCLUDING_EXCLUDED) {
                // Show results for excluded tests too, even if they were hidden.
                if (settings.state.hideExcluded) { settings.state.hideExcluded = false; host.redraw() }
                service.runAllIncludingExcluded()
            })
            add(runSelectedAction)
        })
        addSeparator()
        // Tree
        // Standard IntelliJ expand/collapse-all actions (same icons and shortcuts as other trees).
        val expander = DefaultTreeExpander(tree)
        add(CommonActionsManager.getInstance().createExpandAllAction(expander, tree))
        add(CommonActionsManager.getInstance().createCollapseAllAction(expander, tree))
        addSeparator()
        // Filter and display
        add(DefaultActionGroup("Filter", true).apply {
            templatePresentation.icon = AllIcons.General.Filter
            add(toggle("Show Failures Only", { host.failuresOnly }) { host.failuresOnly = it; host.redraw() })
            add(toggle("Hide Excluded Tests", { settings.state.hideExcluded }) { settings.state.hideExcluded = it; host.redraw() })
        })
        add(object : AnAction("Group By", "Choose how the tests are grouped", AllIcons.Actions.GroupBy) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) {
                val anchor = e.inputEvent?.component as? JComponent ?: tree
                GroupByPopup(settings) { host.redraw() }.showUnder(anchor)
            }
        })
        add(DefaultActionGroup("Colour Test Names", true).apply {
            templatePresentation.icon = AllIcons.Actions.Highlighting
            NameColouring.entries.forEach { mode ->
                add(choice(mode.title, { NameColouring.fromSetting(settings.state.nameColouring) == mode }) {
                    settings.state.nameColouring = mode.name
                    // Only how names are painted changes, not the rows.
                    tree.repaint()
                })
            }
        })
        add(DefaultActionGroup("Sort By", true).apply {
            templatePresentation.icon = AllIcons.ObjectBrowser.Sorted
            SortMode.entries.forEach { mode ->
                add(choice(mode.title, { SortMode.fromSetting(settings.state.sortMode) == mode }) {
                    settings.state.sortMode = mode.name
                    host.redraw()
                })
            }
        })
        addSeparator()
        // Mode: tick which tests run automatically
        add(object : ToggleAction("Choose Automatic Tests", "Show checkboxes: ticked tests run automatically", AllIcons.Actions.Edit) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = host.choosingTests
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                host.choosingTests = state
                host.redraw()
            }
        })
        addSeparator()
        add(simple(SETTINGS, AllIcons.General.Settings) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, NoCrunchConfigurable::class.java)
        })
        // Far right and always available: a fresh engine reruns every test, e.g. after a test host crashed.
        add(simple(RESTART_ENGINE, AllIcons.Actions.Restart,
            "Restart the No Crunch engine and rerun all tests (e.g. after a test host crashed)") { service.restart() })
    }

    private fun simple(text: String, icon: Icon, description: String? = null, action: () -> Unit) =
        object : AnAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = action()
        }

    private fun toggle(text: String, isOn: () -> Boolean, set: (Boolean) -> Unit) = object : ToggleAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = isOn()
        override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
    }

    /** One of a set of radio-like options: choosing it again does nothing. */
    private fun choice(text: String, isOn: () -> Boolean, choose: () -> Unit) = toggle(text, isOn) { if (it) choose() }

    companion object {
        const val SETTINGS = "Settings"
        const val RESTART_ENGINE = "Restart Engine"

        /** Toolbar actions that stay usable while paused: Settings, and restarting (which also unpauses). */
        fun enabledWhilePaused(text: String?): Boolean = text == SETTINGS || text == RESTART_ENGINE

        /** The run-all icon with a small "+" badge: run everything, plus the excluded tests. */
        private val RUN_ALL_INCLUDING_EXCLUDED: Icon = LayeredIcon(2).apply {
            setIcon(AllIcons.RunConfigurations.TestState.Run_run, 0)
            setIcon(IconUtil.scale(AllIcons.General.Add, null, 0.6f), 1, SwingConstants.SOUTH_EAST)
        }
    }
}
