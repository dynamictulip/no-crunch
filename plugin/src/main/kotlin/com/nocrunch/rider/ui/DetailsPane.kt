package com.nocrunch.rider.ui

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import javax.swing.JComponent

/** Shows a failure message + stack trace (or build output) with clickable file links. */
class DetailsPane(private val project: Project, parent: Disposable) {
    private val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply {
        addFilter(DotnetLocationFilter(project))
        setViewer(true)
    }.console.also { Disposer.register(parent, it) }

    val component: JComponent get() = console.component

    /** What's showing, so redrawing the same text (a result batch that didn't touch this row) is skipped. */
    private var shown: List<DetailPart>? = null

    /**
     * Shows [parts] (see [DetailsText]): normal, with only the failure words in red and side notes in grey. Leaves the
     * console alone if the text is what it already shows, so its scroll position survives updates to other rows.
     * Null clears it.
     */
    fun show(parts: List<DetailPart>?) {
        if (parts == null) return clear()
        if (parts == shown) return
        shown = parts
        console.clear()
        parts.forEach { console.print(it.text, type(it.style)) }
    }

    private fun type(style: DetailStyle): ConsoleViewContentType = when (style) {
        DetailStyle.NORMAL -> ConsoleViewContentType.NORMAL_OUTPUT
        DetailStyle.RED -> ConsoleViewContentType.ERROR_OUTPUT
        DetailStyle.MUTED -> MUTED
    }

    private companion object {
        val MUTED = ConsoleViewContentType(
            "NoCrunchMuted",
            com.intellij.openapi.editor.markup.TextAttributes().apply { foregroundColor = com.intellij.ui.JBColor.GRAY },
        )
    }

    fun clear() {
        shown = null
        console.clear()
    }
}

/** Links `in /path/File.cs:line 12` (stack traces) and `/path/File.cs(12,5): error` (compiler output). */
private class DotnetLocationFilter(private val project: Project) : Filter {
    private val stack = Regex("""in (/[^\n]+?):line (\d+)""")
    private val compiler = Regex("""(/[^\n(]+?\.(?:cs|fs|vb|csproj|props|targets))\((\d+),(\d+)\)""")

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val start = entireLength - line.length
        val items = mutableListOf<Filter.ResultItem>()
        for (m in stack.findAll(line)) link(m.groups[1]!!, m.groupValues[2].toInt(), start)?.let(items::add)
        for (m in compiler.findAll(line)) link(m.groups[1]!!, m.groupValues[2].toInt(), start)?.let(items::add)
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun link(path: MatchGroup, line: Int, offset: Int): Filter.ResultItem? {
        val file = LocalFileSystem.getInstance().findFileByPath(path.value) ?: return null
        return Filter.ResultItem(offset + path.range.first, offset + path.range.last + 1, OpenFileHyperlinkInfo(project, file, line - 1))
    }
}
