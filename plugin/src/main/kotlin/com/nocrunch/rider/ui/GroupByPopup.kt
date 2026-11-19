package com.nocrunch.rider.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.nocrunch.rider.settings.NoCrunchSettings
import com.nocrunch.rider.view.GroupLevel
import com.nocrunch.rider.view.Grouping
import com.nocrunch.rider.view.RecentGroupings
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The "Group by" popup: recently used groupings to switch to in one click, and the levels to tick and reorder.
 * Every change is saved and the tree redrawn straight away.
 */
internal class GroupByPopup(private val settings: NoCrunchSettings, private val redraw: () -> Unit) {
    private val state get() = settings.state
    private val current get() = Grouping.fromSetting(state.grouping, state.viewMode)

    private val model = GroupByModel(current)
    private val startedWith = current
    private val content = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(6, 8)
    }
    private var popup: JBPopup? = null

    fun showUnder(anchor: JComponent) {
        render()
        val p = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) = remember()
            })
            .createPopup()
        popup = p
        p.showUnderneathOf(anchor)
    }

    private fun apply() {
        state.grouping = model.grouping.toSetting()
        redraw()
        render()
    }

    private fun remember() {
        // Only groupings the user actually moved to; opening and closing must not reshuffle the list.
        val now = current
        if (now == startedWith) return
        state.recentGroupings = RecentGroupings.toSetting(RecentGroupings.remember(RecentGroupings.fromSetting(state.recentGroupings), now))
    }

    private fun render() {
        content.removeAll()
        val recent = RecentGroupings.fromSetting(state.recentGroupings)
        if (recent.isNotEmpty()) {
            content.add(heading("Recent"))
            recent.forEach { content.add(recentRow(it)) }
            content.add(heading("Group by"))
        }
        model.rows.forEach { (level, ticked) -> content.add(levelRow(level, ticked)) }
        content.revalidate()
        content.repaint()
        popup?.pack(true, true)
    }

    private fun heading(text: String) = JBLabel(text).apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        border = JBUI.Borders.empty(4, 0, 2, 0)
        alignmentX = 0f
    }

    private fun recentRow(grouping: Grouping) = JPanel(BorderLayout()).apply {
        alignmentX = 0f
        add(JBLabel(grouping.title, if (grouping == current) AllIcons.Actions.Checked else null, JBLabel.LEFT).apply {
            iconTextGap = JBUI.scale(4)
            if (grouping != current) border = JBUI.Borders.emptyLeft(AllIcons.Actions.Checked.iconWidth + 4)
        }, BorderLayout.CENTER)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                state.grouping = grouping.toSetting()
                redraw()
                popup?.cancel()
            }
        })
    }

    private fun levelRow(level: GroupLevel, ticked: Boolean) = JPanel(BorderLayout()).apply {
        alignmentX = 0f
        add(JBCheckBox(level.title, ticked).apply {
            isOpaque = false
            addActionListener { this@GroupByPopup.model.toggle(level); this@GroupByPopup.apply() }
        }, BorderLayout.CENTER)
        if (ticked) {
            val levels = model.grouping.levels
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(arrow("Move up", AllIcons.Actions.MoveUp, levels.first() != level) { model.moveUp(level); apply() })
                add(arrow("Move down", AllIcons.Actions.MoveDown, levels.last() != level) { model.moveDown(level); apply() })
            }, BorderLayout.EAST)
        }
    }

    private fun arrow(tip: String, icon: javax.swing.Icon, enabled: Boolean, action: () -> Unit) = JButton(icon).apply {
        toolTipText = tip
        isEnabled = enabled
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusable = false
        margin = JBUI.emptyInsets()
        addActionListener { action() }
    }
}
