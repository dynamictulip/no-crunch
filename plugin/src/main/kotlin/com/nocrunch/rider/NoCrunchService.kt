package com.nocrunch.rider

import com.google.gson.JsonObject
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.nocrunch.rider.engine.BuildFinishedDto
import com.nocrunch.rider.engine.EngineClient
import com.nocrunch.rider.engine.EngineProcess
import com.nocrunch.rider.engine.EngineState
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.InitializeParams
import com.nocrunch.rider.engine.InitializeResult
import com.nocrunch.rider.engine.LogDto
import com.nocrunch.rider.engine.PROTOCOL_VERSION
import com.nocrunch.rider.engine.RunFinishedDto
import com.nocrunch.rider.engine.StateChangedDto
import com.nocrunch.rider.engine.TestResultsDto
import com.nocrunch.rider.engine.TestResultDto
import com.nocrunch.rider.engine.TestsDiscoveredDto
import com.nocrunch.rider.exclusions.ExclusionStore
import com.nocrunch.rider.files.MirrorFolderExcluder
import com.nocrunch.rider.files.RepoPaths
import com.nocrunch.rider.lifecycle.EngineSlot
import com.nocrunch.rider.lifecycle.FileChangeBatcher
import com.nocrunch.rider.settings.NoCrunchSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

data class EngineUiState(val state: EngineState, val queued: Int = 0, val error: String? = null) {
    companion object {
        /** The service is created when the project opens or the tool window shows; the engine starts straight away. */
        fun initial() = EngineUiState(EngineState.STARTING)
    }
}

/** Owns the engine process for one open solution and holds the latest results for the UI. */
@Service(Service.Level.PROJECT)
class NoCrunchService(private val project: Project, private val cs: CoroutineScope) : Disposable {
    private val log = logger<NoCrunchService>()

    private val _state = MutableStateFlow(EngineUiState.initial())
    private val _tests = MutableStateFlow<Map<String, TestResultDto>>(emptyMap())
    private val _builds = MutableStateFlow<Map<String, ProjectBuild>>(emptyMap())
    private val _runs = MutableStateFlow<Map<String, RunFinishedDto>>(emptyMap())
    private val _exclusions = MutableStateFlow<List<ExclusionRuleDto>>(emptyList())

    val state: StateFlow<EngineUiState> = _state.asStateFlow()
    val tests: StateFlow<Map<String, TestResultDto>> = _tests.asStateFlow()
    /** Latest build per project, including ones still in progress. */
    val builds: StateFlow<Map<String, ProjectBuild>> = _builds.asStateFlow()
    val runs: StateFlow<Map<String, RunFinishedDto>> = _runs.asStateFlow()
    val exclusions: StateFlow<List<ExclusionRuleDto>> = _exclusions.asStateFlow()

    /** Scope for UI collectors; cancelled when the project closes. */
    val scope: CoroutineScope get() = cs

    @Volatile var repoRoot: String? = null
        private set
    private val engine = EngineSlot<EngineProcess, EngineClient>()
    private val client: EngineClient? get() = engine.client
    private var exclusionStore: ExclusionStore? = null
    private val crashTimes = ArrayDeque<Long>()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val fileChanges = FileChangeBatcher(cs, 100) { batch -> send("filesChanged", mapOf("paths" to batch)) }

    /** Idempotent: safe to call from startup and whenever the tool window opens. */
    @Synchronized
    fun start() {
        if (!engine.canStart) return
        val solution = findSolution() ?: run {
            _state.value = EngineUiState(EngineState.ERROR, error = "No .sln or .slnx found for this project.")
            return
        }
        val engineDll = findEngineDll() ?: run {
            _state.value = EngineUiState(EngineState.ERROR, error = "Engine not found at ${engineDllPath() ?: "(plugin path unknown)"}")
            return
        }
        MirrorFolderExcluder.ensureIgnored()
        val store = ExclusionStore.forSolution(solution).also { exclusionStore = it }
        _exclusions.value = store.load()
        _state.value = EngineUiState(EngineState.STARTING)

        val settings = NoCrunchSettings.getInstance(project)
        val dotnet = settings.resolveDotnet()
        lateinit var proc: EngineProcess
        proc = EngineProcess(dotnet, engineDll) { code -> onEngineExit(proc, code) }
        if (!engine.begin(proc)) return
        cs.launch(Dispatchers.IO) {
            try {
                // The project may have closed, or a restart overtaken us, before this ran; then don't spawn at all.
                if (!engine.isCurrent(proc)) return@launch
                val c = proc.start()
                // Once this engine is no longer current (failed, superseded), what it says must not touch the UI.
                c.onNotification { method, params -> if (engine.isCurrent(proc)) onNotification(proc, c.gson, method, params) }
                // Overtaken while spawning: this engine belongs to nobody, so stop it. (initialize below is bounded by
                // the client's request timeout.)
                if (!engine.adopt(proc, c)) { proc.stop(); return@launch }
                val s = settings.state
                val result = c.request("initialize", InitializeParams(solution.toString(), dotnet, s.maxParallel, s.debounceMs, s.testTimeoutSeconds),
                    InitializeResult::class.java).get()
                if (result.protocolVersion != PROTOCOL_VERSION) error("Engine protocol ${result.protocolVersion}, plugin expects $PROTOCOL_VERSION")
                repoRoot = result.repoRoot
                c.notify("setExclusions", mapOf("rules" to _exclusions.value))
            } catch (e: Exception) {
                failStart(proc, e)
            }
        }
    }

    /**
     * Startup failed (dotnet missing, initialize failed or timed out, protocol mismatch). The engine may well still be
     * running, so stop it, and free the slot so a later start() can retry. The error stays until then: the stopped
     * engine's notifications are ignored and stopping it means its exit doesn't trigger a restart.
     */
    private fun failStart(proc: EngineProcess, e: Exception) {
        // Under the start() lock, so a start() that follows straight after can't have its "starting" overwritten.
        val wasCurrent = synchronized(this) {
            engine.retire(proc).also { if (it) _state.value = EngineUiState(EngineState.ERROR, error = e.message ?: e.toString()) }
        }
        if (wasCurrent) log.warn("No Crunch engine failed to start", e)
        else log.info("Abandoned No Crunch engine start ended: $e")
        proc.stop()
    }

    /** Stops and starts the engine off the UI thread (stopping waits for the engine to shut down). */
    fun restart() {
        cs.launch(Dispatchers.IO) {
            stopEngine()
            start()
        }
    }

    fun runAll() = send("runAll")

    /** Runs everything: non-excluded tests as a normal run, excluded ones as a manual run. */
    fun runAllIncludingExcluded() {
        runAll()
        val excluded = com.nocrunch.rider.exclusions.TestExclusions.excludedKeys(_tests.value.values, _exclusions.value)
        if (excluded.isNotEmpty()) runTests(excluded)
    }
    fun runTests(keys: Collection<String>) = send("runTests", mapOf("keys" to keys.toList()))
    fun pause() = send("pause")
    fun resume() = send("resume")

    fun setExcluded(changed: List<ExclusionRuleDto>, excluded: Boolean) {
        val updated = _exclusions.updateAndGetRules { rules ->
            changed.fold(rules) { acc, target -> com.nocrunch.rider.exclusions.ExclusionRules.toggle(acc, target, excluded) }
        }
        // One writer, always saving the latest rules, so quick toggles can't land out of order.
        cs.launch(saveDispatcher) { exclusionStore?.save(_exclusions.value) }
        send("setExclusions", mapOf("rules" to updated))
    }

    /** Called from the VFS listener; batched to at most one message per 100 ms. */
    fun filesChanged(paths: Collection<String>) {
        val root = repoRoot ?: return
        val relevant = RepoPaths.relevant(paths, root)
        if (relevant.isEmpty()) return
        fileChanges.add(relevant)
    }

    private fun send(method: String, params: Any? = null) {
        val c = client ?: return
        try {
            c.notify(method, params)
        } catch (e: Exception) {
            log.warn("Failed to send $method to engine", e)
        }
    }

    private fun onNotification(from: EngineProcess, gson: com.google.gson.Gson, method: String, params: JsonObject) {
        when (method) {
            "stateChanged" -> gson.fromJson(params, StateChangedDto::class.java).let {
                // Checked again under the lock failStart() sets its error under, so a late update can't replace it.
                synchronized(this) { if (engine.isCurrent(from)) _state.value = EngineUiState(it.state, it.queued) }
            }
            "projectBuildStarted" -> {
                val project = params["project"].asString
                _builds.update { it + (project to ProjectBuild.started(project)) }
            }
            "projectBuildFinished" -> gson.fromJson(params, BuildFinishedDto::class.java).let { b -> _builds.update { it + (b.project to ProjectBuild.finished(b)) } }
            "testsDiscovered" -> gson.fromJson(params, TestsDiscoveredDto::class.java).let { d ->
                // A discovery list is authoritative for its project: tests that no longer exist disappear.
                _tests.update { current -> current.filterValues { it.project != d.project } + d.tests.associateBy { it.key } }
            }
            "testResults" -> gson.fromJson(params, TestResultsDto::class.java).let { r -> _tests.update { it + r.updates.associateBy { u -> u.key } } }
            "projectRunFinished" -> gson.fromJson(params, RunFinishedDto::class.java).let { r -> _runs.update { it + (r.project to r) } }
            "log" -> gson.fromJson(params, LogDto::class.java).let { if (it.level == "error" || it.level == "warning") log.warn(it.message) else log.info(it.message) }
        }
    }

    private fun onEngineExit(proc: EngineProcess, code: Int) {
        // Already dealt with: stopped, failed to start, or replaced by a newer engine.
        if (!engine.retire(proc)) return
        val now = System.currentTimeMillis()
        val recent = synchronized(crashTimes) {
            crashTimes.addLast(now)
            while (crashTimes.isNotEmpty() && now - crashTimes.first() > 60_000) crashTimes.removeFirst()
            crashTimes.size
        }
        if (recent > 3) {
            _state.value = EngineUiState(EngineState.ERROR, error = "Engine keeps exiting (code $code). See idea.log.")
            return
        }
        log.warn("No Crunch engine exited with code $code; restarting")
        start()
    }

    private fun stopEngine() {
        engine.clear()?.stop()
        _state.value = EngineUiState(EngineState.STOPPED)
    }

    private fun findSolution(): Path? {
        val base = project.basePath?.let { Path.of(it) } ?: return null
        val candidates = Files.list(base).use { s ->
            s.filter { val n = it.fileName.toString(); n.endsWith(".sln") || n.endsWith(".slnx") }.toList()
        }
        return candidates.firstOrNull { it.fileName.toString().substringBeforeLast('.') == project.name } ?: candidates.firstOrNull()
    }

    private fun engineDllPath(): Path? =
        PluginManagerCore.getPlugin(PluginId.getId("com.nocrunch.rider"))?.pluginPath?.resolve("engine/NoCrunch.Engine.dll")

    private fun findEngineDll(): Path? = engineDllPath()?.takeIf { Files.exists(it) }

    /** Project closing: shut the engine down in the background so closing never waits on it. */
    override fun dispose() {
        // From here on nothing can start, and a start still spawning stops its own engine.
        val p = engine.dispose() ?: return
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread { p.stop() }
    }

    private inline fun MutableStateFlow<List<ExclusionRuleDto>>.updateAndGetRules(f: (List<ExclusionRuleDto>) -> List<ExclusionRuleDto>): List<ExclusionRuleDto> {
        while (true) {
            val prev = value
            val next = f(prev)
            if (compareAndSet(prev, next)) return next
        }
    }

    companion object {
        fun getInstance(project: Project): NoCrunchService = project.getService(NoCrunchService::class.java)
    }
}
