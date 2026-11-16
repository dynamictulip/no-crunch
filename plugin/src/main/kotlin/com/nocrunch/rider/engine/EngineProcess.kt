package com.nocrunch.rider.engine

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Path

/** Launches `dotnet NoCrunch.Engine.dll` and wires its stdio to an [EngineClient]. */
class EngineProcess(private val dotnet: String, private val engineDll: Path, private val onExit: (exitCode: Int) -> Unit) {
    private val log = logger<EngineProcess>()
    private var process: Process? = null
    private var client: EngineClient? = null
    @Volatile private var stopping = false

    fun start(): EngineClient {
        val p = ProcessBuilder(dotnet, engineDll.toString())
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        process = p
        Thread({ p.errorStream.bufferedReader().forEachLine { log.info("[engine] $it") } }, "NoCrunch engine stderr")
            .apply { isDaemon = true; start() }
        p.onExit().thenAccept { if (!stopping) onExit(it.exitValue()) }
        return EngineClient(p.inputStream, p.outputStream).also { client = it }
    }

    fun stop() {
        stopping = true
        try {
            client?.request("shutdown", null, Any::class.java)?.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        client?.close()
        process?.let { if (!it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly() }
    }
}
