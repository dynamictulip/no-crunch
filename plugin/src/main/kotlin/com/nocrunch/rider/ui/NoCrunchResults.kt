package com.nocrunch.rider.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.nocrunch.rider.EngineUiState
import com.nocrunch.rider.NoCrunchService
import com.nocrunch.rider.ProjectBuild
import com.nocrunch.rider.engine.CoverageFileDto
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.RunFinishedDto
import com.nocrunch.rider.engine.TestResultDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn

/** Everything the UI shows for one batch of engine updates. */
data class ResultsSnapshot(
    val tests: Map<String, TestResultDto>,
    val builds: Map<String, ProjectBuild>,
    val runs: Map<String, RunFinishedDto>,
    val rules: List<ExclusionRuleDto>,
    val state: EngineUiState,
    /** Every project and test, before the tool window's view filters. */
    val projects: List<ProjectNode>,
    /** The engine's latest line coverage per absolute file path. */
    val coverage: Map<String, CoverageFileDto> = emptyMap(),
) {
    /** Counts of every test, shared by the progress bar, tool window icon and status bar widget so they agree. */
    val counts: Counts = ProgressBarModel.totals(projects)
    val brokenBuilds: Int = ProgressBarModel.brokenBuilds(builds)
    val bar: BarState = ProgressBarModel.from(counts, state, brokenBuilds)

    companion object {
        fun empty(state: EngineUiState) = ResultsSnapshot(emptyMap(), emptyMap(), emptyMap(), emptyList(), state, emptyList())
    }
}

/** How often the UI catches up with the engine's results. */
const val UI_UPDATE_MS = 100L

/**
 * The latest value every [UI_UPDATE_MS] while values keep coming, so the UI keeps up with a steady stream of results
 * (debounce would hold everything back until the stream paused). The engine's flows never complete, so the last
 * value always goes out on the next tick.
 */
@OptIn(FlowPreview::class)
fun <T> Flow<T>.atSteadyRate(): Flow<T> = sample(UI_UPDATE_MS)

/**
 * Builds the model once per batch of engine updates, off the EDT (it reads source files for data rows' parameter
 * names), and shares it with the tool window, its icon and the status bar widget.
 */
@Service(Service.Level.PROJECT)
class NoCrunchResults(project: Project, scope: CoroutineScope) {
    private val engine = NoCrunchService.getInstance(project)
    private val sources = SourceCache()

    val snapshots: StateFlow<ResultsSnapshot> =
        combine(
            combine(engine.tests, engine.builds, engine.runs, engine.exclusions, engine.state) { t, b, r, e, s -> Input(t, b, r, e, s) },
            engine.coverage,
        ) { input, coverage -> input.copy(coverage = coverage) }
            .atSteadyRate()
            .map { i ->
                val projects = TestTreeModel.buildAll(i.tests.values, i.builds, i.rules, i.runs, sources::paramNames)
                sources.prune()
                ResultsSnapshot(i.tests, i.builds, i.runs, i.rules, i.state, projects, i.coverage)
            }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, ResultsSnapshot.empty(engine.state.value))

    private data class Input(
        val tests: Map<String, TestResultDto>,
        val builds: Map<String, ProjectBuild>,
        val runs: Map<String, RunFinishedDto>,
        val rules: List<ExclusionRuleDto>,
        val state: EngineUiState,
        val coverage: Map<String, CoverageFileDto> = emptyMap(),
    )

    companion object {
        fun getInstance(project: Project): NoCrunchResults = project.service()
    }
}
