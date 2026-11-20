package com.nocrunch.rider.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.JBColor
import com.intellij.ui.RowIcon as IdeRowIcon
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.LayeredIcon
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.IconUtil
import com.intellij.util.ui.ThreeStateCheckBox
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import com.nocrunch.rider.view.NameColouring
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.SwingConstants

/**
 * Draws a row of the test tree from its [RowLook]: icon, name (in the editor font, colour-coded when that's on) and the
 * counts and notes after it (in the UI font).
 */
internal class TestTreeRenderer(
    private val showCheckboxes: () -> Boolean,
    private val colouring: () -> NameColouring,
    /** Some test in the tree is failing: then only the red crosses are highlighted, not the green ticks. */
    private val anyFailures: () -> Boolean,
) : CheckboxTree.CheckboxTreeCellRenderer() {
    /** Counts, run times and notes after the name, in the normal UI font (the name is monospace). */
    private val stats = SimpleColoredComponent().apply {
        isOpaque = false
        // The new UI draws the selected row as a rounded box inset from the tree's right edge; end inside it.
        ipad = JBUI.insetsRight(14)
    }

    init {
        // The base renderer lays out checkbox and text with a BorderLayout; if that ever changes, stats go in the text.
        if (layout is BorderLayout) add(stats, BorderLayout.EAST)
    }

    private val out: SimpleColoredComponent get() = if (stats.parent == this) stats else textRenderer

    override fun customizeRenderer(tree: JTree, value: Any, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
        val r = textRenderer
        stats.clear()
        stats.font = tree.font
        val editing = showCheckboxes()
        val obj = (value as? CheckedTreeNode)?.userObject
        // The editor's monospace font, so names read like code and data-row columns line up.
        r.font = monospace(tree.font)
        threeStateCheckBox.isVisible = RowPresentation.checkboxVisible(obj, editing)
        // A group with only some tests excluded shows the mixed state, which no node of the tree model can express.
        if (RowPresentation.checkState(obj) == CheckState.PARTIAL) threeStateCheckBox.state = ThreeStateCheckBox.State.DONT_CARE
        val look = RowPresentation.of(obj, editing, anyFailures()) ?: return
        r.icon = icon(look.icon)
        appendName(look.name, look.nameStyle)
        look.stats.forEach { out.append(it.text, attributes(it.style)) }
    }

    private fun appendName(name: String, style: NameStyle) {
        when (style) {
            NameStyle.PROJECT -> appendColoured(name, bold = true, parent = true)
            NameStyle.PARENT -> appendColoured(name, parent = true)
            NameStyle.TEST -> appendColoured(name)
            NameStyle.ARGS -> appendArgs(name)
            NameStyle.GREY -> textRenderer.append(name, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            NameStyle.FAILED -> textRenderer.append(name, FAILED)
            NameStyle.FAILED_BOLD -> textRenderer.append(name, FAILED_BOLD)
            NameStyle.STALE_FAILED -> textRenderer.append(name, STALE_FAILED)
            NameStyle.STALE_FAILED_BOLD -> textRenderer.append(name, STALE_FAILED_BOLD)
        }
    }

    /**
     * Appends a name, colour-coded by part when that's switched on. Only test names are ever snake case, so [parent]
     * rows (projects, namespaces, classes, files) always split into PascalCase words.
     */
    private fun appendColoured(name: String, bold: Boolean = false, parent: Boolean = false) {
        val style = if (bold) SimpleTextAttributes.STYLE_BOLD else SimpleTextAttributes.STYLE_PLAIN
        val mode = colouring().let { if (parent && it != NameColouring.OFF) NameColouring.PASCAL else it }
        NameColouring.split(name, mode).forEach { (text, colour) ->
            textRenderer.append(text, SimpleTextAttributes(style, colour?.let { ThemeColours.nameParts()[it] }))
        }
    }

    /** Theory row arguments: each name and value takes the next colour, whichever colouring mode is on. */
    private fun appendArgs(text: String) {
        if (colouring() == NameColouring.OFF) { textRenderer.append(text, SimpleTextAttributes.REGULAR_ATTRIBUTES); return }
        NameColouring.splitArgs(text).forEach { (t, colour) ->
            textRenderer.append(t, colour?.let { SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ThemeColours.nameParts()[it]) } ?: SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    private fun monospace(base: Font): Font = Font(EditorColorsManager.getInstance().globalScheme.editorFontName, Font.PLAIN, base.size)

    /** Status icon followed by a kind icon (project, namespace, folder, file), like Rider's test tree. */
    private fun icon(i: RowIcon): Icon? {
        val status = i.status?.let { s ->
            val icon = when (s) {
                StatusIcon.PASSED -> AllIcons.RunConfigurations.TestPassed
                StatusIcon.FAILED -> AllIcons.RunConfigurations.TestError
                StatusIcon.SKIPPED -> AllIcons.RunConfigurations.TestSkipped
                StatusIcon.EXCLUDED -> KindIcons.excluded
                StatusIcon.IN_PROGRESS -> AllIcons.Process.Step_1
                StatusIcon.ERROR -> AllIcons.General.Error
                StatusIcon.WARNING -> AllIcons.General.Warning
            }
            // Faded when the result is from an earlier run (stale/queued/running).
            if (i.faded) IconLoader.getTransparentIcon(icon, 0.45f) else icon
        }
        val kind = when (i.kind) {
            null -> null
            KindIcon.PROJECT -> KindIcons.project
            KindIcon.NAMESPACE -> KindIcons.namespace
            KindIcon.FOLDER -> KindIcons.folder
            KindIcon.FILE -> KindIcons.file(i.fileName.orEmpty())
            KindIcon.TEST_SET -> KindIcons.testSet
            KindIcon.TEST -> KindIcons.test
        }
        val shown = when {
            status == null -> kind
            kind == null -> status
            else -> IdeRowIcon(status, EmptyIcon.create(JBUI.scale(4), 1), kind)
        }
        if (!i.partlyExcluded || shown == null) return shown
        // A small excluded icon on the bottom-right corner of the group's own result.
        return LayeredIcon(2).apply {
            setIcon(shown, 0)
            setIcon(IconUtil.scale(KindIcons.excluded, null, 0.6f), 1, SwingConstants.SOUTH_EAST)
        }
    }

    private fun attributes(style: StatStyle): SimpleTextAttributes = when (style) {
        StatStyle.GREY -> SimpleTextAttributes.GRAYED_ATTRIBUTES
        StatStyle.GREY_ITALIC -> SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
        StatStyle.ERROR -> SimpleTextAttributes.ERROR_ATTRIBUTES
        StatStyle.PASSED_BOLD -> PASSED_BOLD
        StatStyle.FAILED_BOLD -> FAILED_BOLD
        StatStyle.MANUAL -> MANUAL
    }

    private companion object {
        val FAILED = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Palette.FAIL)
        val FAILED_BOLD = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Palette.FAIL)
        val PASSED_BOLD = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Palette.PASS)
        val MANUAL = SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, JBColor.BLUE)
        /** Faded palette red for failures from an earlier run (stale, queued or running again). */
        val STALE_RED = Palette.faded(Palette.FAIL)
        val STALE_FAILED = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, STALE_RED)
        val STALE_FAILED_BOLD = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, STALE_RED)
    }
}
