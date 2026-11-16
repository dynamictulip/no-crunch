package com.nocrunch.rider.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import java.io.File

class NoCrunchSettingsState : BaseState() {
    var debounceMs by property(300)
    /** 0 = half the CPU cores. */
    var maxParallel by property(0)
    /** Empty = find `dotnet` automatically. */
    var dotnetPath by string("")
    /** Tool window: hide excluded projects, classes and tests. */
    var hideExcluded by property(false)
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
