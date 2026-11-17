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
import com.nocrunch.rider.settings.NoCrunchSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** state: starting, idle, syncing, building, testing, paused, stopped, error */
data class EngineUiState(val state: String, val queued: Int = 0, val error: String? = null) {
    companion object {
        /** The service is created when the project opens or the tool window shows; the engine starts straight away. */
        fun initial() = EngineUiState("starting")
    }
}

/** Owns the engine process for one open solution and holds the latest results for the UI. */
@Service(Service.Level.PROJECT)
class NoCrunchService(private val project: Project, private val cs: CoroutineScope) : Disposable {
    private val log = logger<NoCrunchService>()

    private val _state = MutableStateFlow(EngineUiState.initial())
    private val _tests = MutableStateFlow<Map<String, TestResultDto>>(emptyMap())
    private val _builds = MutableStateFlow<Map<String, BuildFinishedDto>>(emptyMap())
    private val _runs = MutableStateFlow<Map<String, RunFinishedDto>>(emptyMap())
    private val _exclusions = MutableStateFlow<List<ExclusionRuleDto>>(emptyList())

    val state: StateFlow<EngineUiState> = _state.asStateFlow()
    val tests: StateFlow<Map<String, TestResultDto>> = _tests.asStateFlow()
    /** Latest build per project; status "building" while in progress. */
    val builds: StateFlow<Map<String, BuildFinishedDto>> = _builds.asStateFlow()
    val runs: StateFlow<Map<String, RunFinishedDto>> = _runs.asStateFlow()
    val exclusions: StateFlow<List<ExclusionRuleDto>> = _exclusions.asStateFlow()

    /** Scope for UI collectors; cancelled when the project closes. */
    val scope: CoroutineScope get() = cs

    @Volatile var repoRoot: String? = null
        private set
    @Volatile private var client: EngineClient? = null
    private var process: EngineProcess? = null
    private var exclusionStore: ExclusionStore? = null
    private val crashTimes = ArrayDeque<Long>()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val pendingFiles = LinkedHashSet<String>()
    private var flushJob: Job? = null

    /** Idempotent: safe to call from startup and whenever the tool window opens. */
    @Synchronized
    fun start() {
        if (process != null) return
        val solution = findSolution() ?: run {
            _state.value = EngineUiState("error", error = "No .sln or .slnx found for this project.")
            return
        }
        val engineDll = findEngineDll() ?: run {
            _state.value = EngineUiState("error", error = "Engine not found at ${engineDllPath() ?: "(plugin path unknown)"}")
            return
        }
        MirrorFolderExcluder.ensureIgnored()
        val store = ExclusionStore.forSolution(solution).also { exclusionStore = it }
        _exclusions.value = store.load()
        _state.value = EngineUiState("starting")

        val settings = NoCrunchSettings.getInstance(project)
        val dotnet = settings.resolveDotnet()
        val proc = EngineProcess(dotnet, engineDll) { code -> onEngineExit(code) }
        process = proc
        cs.launch(Dispatchers.IO) {
            try {
                val c = proc.start()
                c.onNotification(::onNotification)
                client = c
                val s = settings.state
                val result = c.request("initialize", InitializeParams(solution.toString(), dotnet, s.maxParallel, s.debounceMs),
                    InitializeResult::class.java).get()
                if (result.protocolVersion != PROTOCOL_VERSION) error("Engine protocol ${result.protocolVersion}, plugin expects $PROTOCOL_VERSION")
                repoRoot = result.repoRoot
                c.notify("setExclusions", mapOf("rules" to _exclusions.value))
            } catch (e: Exception) {
                log.warn("No Crunch engine failed to start", e)
                _state.value = EngineUiState("error", error = e.message)
            }
        }
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
        val excluded = com.nocrunch.rider.ui.TestTreeModel.excludedKeys(_tests.value.values, _exclusions.value)
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
        synchronized(pendingFiles) {
            pendingFiles += relevant
            if (flushJob?.isActive == true) return
            flushJob = cs.launch(Dispatchers.IO) {
                delay(100)
                val batch = synchronized(pendingFiles) { pendingFiles.toList().also { pendingFiles.clear() } }
                send("filesChanged", mapOf("paths" to batch))
            }
        }
    }

    private fun send(method: String, params: Any? = null) {
        val c = client ?: return
        try {
            c.notify(method, params)
        } catch (e: Exception) {
            log.warn("Failed to send $method to engine", e)
        }
    }

    private fun onNotification(method: String, params: JsonObject) {
        val gson = client?.gson ?: return
        when (method) {
            "stateChanged" -> gson.fromJson(params, StateChangedDto::class.java).let { _state.value = EngineUiState(it.state, it.queued) }
            "projectBuildStarted" -> {
                val project = params["project"].asString
                _builds.update { it + (project to BuildFinishedDto(project, "building", "", null)) }
            }
            "projectBuildFinished" -> gson.fromJson(params, BuildFinishedDto::class.java).let { b -> _builds.update { it + (b.project to b) } }
            "testsDiscovered" -> gson.fromJson(params, TestsDiscoveredDto::class.java).let { d ->
                // A discovery list is authoritative for its project: tests that no longer exist disappear.
                _tests.update { current -> current.filterValues { it.project != d.project } + d.tests.associateBy { it.key } }
            }
            "testResults" -> gson.fromJson(params, TestResultsDto::class.java).let { r -> _tests.update { it + r.updates.associateBy { u -> u.key } } }
            "projectRunFinished" -> gson.fromJson(params, RunFinishedDto::class.java).let { r -> _runs.update { it + (r.project to r) } }
            "log" -> gson.fromJson(params, LogDto::class.java).let { if (it.level == "error") log.warn(it.message) else log.info(it.message) }
        }
    }

    private fun onEngineExit(code: Int) {
        client = null
        process = null
        val now = System.currentTimeMillis()
        val recent = synchronized(crashTimes) {
            crashTimes.addLast(now)
            while (crashTimes.isNotEmpty() && now - crashTimes.first() > 60_000) crashTimes.removeFirst()
            crashTimes.size
        }
        if (recent > 3) {
            _state.value = EngineUiState("error", error = "Engine keeps exiting (code $code). See idea.log.")
            return
        }
        log.warn("No Crunch engine exited with code $code; restarting")
        start()
    }

    private fun stopEngine() {
        val p = process
        process = null
        client = null
        p?.stop()
        _state.value = EngineUiState("stopped")
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
        val p = process ?: return
        process = null
        client = null
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
