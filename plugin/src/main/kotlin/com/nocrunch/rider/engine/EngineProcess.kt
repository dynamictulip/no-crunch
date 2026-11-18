package com.nocrunch.rider.engine

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Launches `dotnet NoCrunch.Engine.dll` and wires its stdio to an [EngineClient].
 * Single use: once [stop] has been called, [start] refuses, so a stop that races a start can't leave an engine running.
 */
class EngineProcess(
    private val dotnet: String,
    private val engineDll: Path,
    private val gracePeriod: Duration = Duration.ofSeconds(5),
    private val onExit: (exitCode: Int) -> Unit,
) {
    private val log = logger<EngineProcess>()
    // Guarded by `this`; start() holds the lock while spawning, so stop() sees whatever it spawned.
    private var process: Process? = null
    private var client: EngineClient? = null
    @Volatile private var stopping = false

    @Synchronized
    fun start(): EngineClient {
        check(!stopping) { "Engine process already stopped" }
        check(process == null) { "Engine process already started" }
        val builder = ProcessBuilder(dotnet, engineDll.toString()).redirectError(ProcessBuilder.Redirect.PIPE)
        // GUI apps on macOS don't inherit the shell PATH; the engine and its children (MSBuild, test hosts) need dotnet on it.
        val dotnetFile = java.io.File(dotnet)
        if (dotnetFile.isAbsolute) {
            val env = builder.environment()
            val dir = dotnetFile.canonicalFile.parent
            env["PATH"] = dir + java.io.File.pathSeparator + env["PATH"].orEmpty()
            env.putIfAbsent("DOTNET_ROOT", dir)
        }
        val p = builder.start()
        process = p
        Thread({ p.errorStream.bufferedReader().forEachLine { log.info("[engine] $it") } }, "NoCrunch engine stderr")
            .apply { isDaemon = true; start() }
        p.onExit().thenAccept { if (!stopping) onExit(it.exitValue()) }
        return EngineClient(p.inputStream, p.outputStream).also { client = it }
    }

    fun stop() {
        stopping = true
        // Waits for a start() in progress, so its process is seen here; any later start() refuses.
        val (p, c) = synchronized(this) { process to client }
        // Once the engine exits its children are reparented and no longer its descendants, so note them first.
        val children = p?.descendantSnapshot().orEmpty()
        try {
            c?.request("shutdown", null, Any::class.java)?.get(gracePeriod.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
        c?.close()
        if (p == null) return
        if (!p.waitFor(gracePeriod.toMillis(), TimeUnit.MILLISECONDS)) {
            val late = p.descendantSnapshot()
            p.destroyForcibly()
            late.forEach { it.destroyForcibly() }
        }
        // MSBuild nodes and test hosts outlive a graceful exit too if the engine didn't stop them.
        children.forEach { if (it.isAlive) it.destroyForcibly() }
    }

    private fun Process.descendantSnapshot(): List<ProcessHandle> =
        try { toHandle().descendants().toList() } catch (_: Exception) { emptyList() }
}
