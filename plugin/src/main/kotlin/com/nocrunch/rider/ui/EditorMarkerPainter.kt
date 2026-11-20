package com.nocrunch.rider.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import javax.swing.Icon

/**
 * Paints test markers in the gutter of every open editor of the project, as line highlighters with a gutter icon.
 * Only the highlighters it added are ever removed.
 */
@Service(Service.Level.PROJECT)
class EditorMarkerPainter(private val project: Project, @Suppress("UNUSED_PARAMETER") scope: CoroutineScope) : Disposable {
    /** Highlighters this painter added, per editor. Touched on the EDT only. */
    private val painted = HashMap<Editor, List<RangeHighlighter>>()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) paint(event.editor)
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                painted.remove(event.editor)
            }
        }, this)
        refresh()
    }

    /** Repaints every open editor of the project from the current markers. */
    fun refresh() {
        ApplicationManager.getApplication().invokeLater({
            EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach(::paint)
        }, project.disposed)
    }

    private fun paint(editor: Editor) {
        painted.remove(editor)?.forEach { editor.markupModel.removeHighlighter(it) }
        val path = editor.virtualFile?.path ?: return
        val added = markersFor(path).mapNotNull { marker ->
            if (marker.line !in 0 until editor.document.lineCount) return@mapNotNull null
            editor.markupModel.addLineHighlighter(null, marker.line, HighlighterLayer.ADDITIONAL_SYNTAX).also {
                it.gutterIconRenderer = MarkerRenderer(marker)
            }
        }
        if (added.isNotEmpty()) painted[editor] = added
    }

    // PROBE: removed in Task 5. Marks line 1 of every open file so we can see gutter icons render in Rider.
    private fun markersFor(@Suppress("UNUSED_PARAMETER") path: String): List<LineMarker> =
        listOf(LineMarker(0, MarkerKind.PASS, "probe"))

    override fun dispose() {
        painted.clear()
    }

    private class MarkerRenderer(private val marker: LineMarker) : GutterIconRenderer() {
        override fun getIcon(): Icon = MarkerIcon(marker.kind)
        override fun getTooltipText(): String = marker.tooltip
        override fun getAlignment() = Alignment.LEFT
        override fun equals(other: Any?) = other is MarkerRenderer && other.marker.kind == marker.kind && other.marker.tooltip == marker.tooltip
        override fun hashCode() = 31 * marker.kind.hashCode() + marker.tooltip.hashCode()
    }

    companion object {
        fun getInstance(project: Project): EditorMarkerPainter = project.service()
    }
}
