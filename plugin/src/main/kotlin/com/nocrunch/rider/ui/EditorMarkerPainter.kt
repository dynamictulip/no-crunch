package com.nocrunch.rider.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.nocrunch.rider.NoCrunchService
import com.nocrunch.rider.engine.CoverageFileDto
import com.nocrunch.rider.settings.NoCrunchSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.Icon

/**
 * Paints test markers in the gutter of every open editor of the project, as line highlighters with a gutter icon.
 * Only the highlighters it added are ever removed.
 */
@Service(Service.Level.PROJECT)
class EditorMarkerPainter(private val project: Project, scope: CoroutineScope) : Disposable {
    /** The latest markers per file path, worked out off the EDT whenever results change. */
    @Volatile private var markers: Map<String, List<LineMarker>> = emptyMap()

    /** The engine's latest line coverage per file path; only painted on a file whose open text still matches it. */
    @Volatile private var coverage: Map<String, CoverageFileDto> = emptyMap()

    /** Highlighters this painter added, per editor. Touched on the EDT only. */
    private val painted = HashMap<Editor, List<RangeHighlighter>>()

    /** What each editor was last painted with, so an editor whose markers haven't changed is left alone. */
    private val lastLayout = HashMap<Editor, Map<Int, LineMarker>>()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) paint(event.editor)
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                painted.remove(event.editor)
                lastLayout.remove(event.editor)
            }
        }, this)
        scope.launch(Dispatchers.Default) {
            NoCrunchResults.getInstance(project).snapshots
                .map { snapshot ->
                    // Stack frames repeat across failures: check the disk once per file, not once per frame.
                    val isRepoFile = memoized(::isRepoFile)
                    EditorMarkers.compute(snapshot.tests.values, isRepoFile) to snapshot.coverage
                }
                .collect { (computed, coverageNow) ->
                    markers = computed
                    coverage = coverageNow
                    refresh()
                }
        }
        refresh()
    }

    /** A real source file inside the repo (not its mirror), so a stack frame there can take a marker. */
    private fun isRepoFile(path: String): Boolean {
        val root = NoCrunchService.getInstance(project).repoRoot ?: return false
        return path.startsWith("$root/") && !path.startsWith("$root/.nocrunch/") && File(path).isFile
    }

    /** Repaints every open editor of the project from the current markers. */
    fun refresh() {
        ApplicationManager.getApplication().invokeLater({
            EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach(::paint)
        }, project.disposed)
    }

    private fun paint(editor: Editor) {
        if (!NoCrunchSettings.getInstance(project).state.showEditorMarkers) {
            clear(editor)
            return
        }
        // The editor's file isn't always set the moment the editor is created; the document knows it.
        val path = (editor.virtualFile ?: FileDocumentManager.getInstance().getFile(editor.document))?.path ?: return
        val text = editor.document.immutableCharSequence
        val covered = coverageFor(text, coverage[path])?.let(EditorMarkers::coverageMarkers).orEmpty()
        val layout = layout(text, markers[path].orEmpty() + covered)
        if (lastLayout[editor] == layout) return
        clear(editor)
        painted[editor] = layout.map { (line, marker) ->
            editor.markupModel.addLineHighlighter(null, line, HighlighterLayer.ADDITIONAL_SYNTAX).also {
                it.gutterIconRenderer = MarkerRenderer(marker)
            }
        }
        lastLayout[editor] = layout
    }

    private fun clear(editor: Editor) {
        painted.remove(editor)?.forEach { editor.markupModel.removeHighlighter(it) }
        lastLayout.remove(editor)
    }

    override fun dispose() {
        painted.clear()
        lastLayout.clear()
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

/**
 * The coverage to paint on a file whose open [text] is given, or null if there is none. If the text no longer matches the
 * file the engine collected it against (edited since): still shown, but stale, so the dots go hollow instead of
 * vanishing. Lines may have moved, which is what stale says.
 */
fun coverageFor(text: CharSequence, file: CoverageFileDto?): CoverageFileDto? =
    file?.let { if (it.hash == ContentHash.of(text)) it else it.copy(stale = true) }

/**
 * The 0-based line to paint [marker] on. A test's reported line is often an attribute like `[TestMethod]`, so a marker
 * with a method lands on the line of the method's name. Null when the line is past the end of [text] (the file changed).
 */
fun landingLine(text: CharSequence, marker: LineMarker, lineCount: Int = lineCountOf(text)): Int? {
    if (marker.line !in 0 until lineCount) return null
    val method = marker.method ?: return marker.line
    val offset = TestLocator.caretOffset(text, marker.line, method)
    return text.subSequence(0, offset).count { it == '\n' }
}

private fun lineCountOf(text: CharSequence) = text.count { it == '\n' } + 1

/**
 * Where each marker is painted, by 0-based line. Markers that land on the same line (a test's own line and a failure's)
 * collapse to the best kind; markers past the end of [text] are dropped.
 */
fun layout(text: CharSequence, markers: List<LineMarker>): Map<Int, LineMarker> {
    val byLine = HashMap<Int, LineMarker>()
    // Counted once: a covered file can carry thousands of markers, and rescanning the text for each would be quadratic.
    val lineCount = lineCountOf(text)
    markers.forEach { marker ->
        val line = landingLine(text, marker, lineCount) ?: return@forEach
        byLine.merge(line, marker) { old, new -> if (new.kind < old.kind) new else old }
    }
    return byLine
}
