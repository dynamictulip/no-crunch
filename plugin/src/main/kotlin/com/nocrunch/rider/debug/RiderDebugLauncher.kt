package com.nocrunch.rider.debug

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import com.jetbrains.rider.run.configurations.project.DotNetProjectConfiguration
import com.jetbrains.rider.run.configurations.project.DotNetProjectConfigurationType
import java.nio.file.Path

/**
 * Debugs one test by name: a temporary ".NET Project" run configuration for the test project, with arguments that
 * filter it to that test, started with the Debug executor. Rider builds the project first as it does for any
 * configuration, and the configuration stays in the run list so the same test can be debugged again.
 */
object RiderDebugLauncher {
    fun debug(project: Project, projectFile: Path, args: List<String>, name: String) {
        val type = ConfigurationTypeUtil.findConfigurationType(DotNetProjectConfigurationType::class.java)
        val runManager = RunManager.getInstance(project)
        val settings = runManager.findConfigurationByTypeAndName(type, name)
            ?: runManager.createConfiguration(name, type.configurationFactories.first()).also {
                it.isTemporary = true
                runManager.addConfiguration(it)
            }
        val parameters = (settings.configuration as DotNetProjectConfiguration).parameters
        parameters.projectFilePath = projectFile.toString()
        parameters.programParameters = ParametersListUtil.join(args)
        parameters.trackProjectArguments = false
        parameters.trackProjectExePath = true
        parameters.trackProjectWorkingDirectory = true
        runManager.selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
    }
}
