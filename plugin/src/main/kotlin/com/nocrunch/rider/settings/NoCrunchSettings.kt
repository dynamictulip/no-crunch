package com.nocrunch.rider.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.nocrunch.rider.view.NameColouring
import com.nocrunch.rider.view.SortMode
import com.nocrunch.rider.view.ViewMode
import java.io.File

class NoCrunchSettingsState : BaseState() {
    var debounceMs by property(300)
    /** 0 = half the CPU cores. */
    var maxParallel by property(0)
    /** Stop a test run that reports no result for this many seconds and fail its unfinished tests. 0 = no timeout. */
    var testTimeoutSeconds by property(30)
    /** Empty = find `dotnet` automatically. */
    var dotnetPath by string("")
    /** Tool window: hide excluded projects, classes and tests. */
    var hideExcluded by property(false)
    /** Old tool window grouping, a [ViewMode] name. Only read to convert it to [grouping] when none is saved yet. */
    var viewMode by string(ViewMode.DEFAULT.name)
    /** Tool window grouping: comma-separated [GroupLevel] names, outermost first. Blank = never saved. */
    var grouping by string("")
    /** The last few groupings picked, most recent first: [grouping] values joined by `;`. */
    var recentGroupings by string("")
    /** Tool window row order: a [SortMode] name. */
    var sortMode by string(SortMode.DEFAULT.name)
    /** Tool window test-name colouring: a [NameColouring] name. */
    var nameColouring by string(NameColouring.DEFAULT.name)
    /** Show test status markers in the editor gutter. */
    var showEditorMarkers by property(true)
}

@Service(Service.Level.PROJECT)
@State(name = "NoCrunchSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class NoCrunchSettings : SimplePersistentStateComponent<NoCrunchSettingsState>(NoCrunchSettingsState()) {
    /** GUI apps on macOS don't get the shell PATH, so look in the usual install locations too. */
    fun resolveDotnet(): String {
        state.dotnetPath?.takeIf { it.isNotBlank() }?.let { return it }
        val candidates = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "dotnet") } +
            listOf(
                File("/usr/local/share/dotnet/dotnet"),
                File("/opt/homebrew/bin/dotnet"),
                File(System.getProperty("user.home"), ".dotnet/dotnet"),
            )
        return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath ?: "dotnet"
    }

    companion object {
        fun getInstance(project: Project): NoCrunchSettings = project.getService(NoCrunchSettings::class.java)
    }
}
