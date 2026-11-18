package com.nocrunch.rider.engine

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Uses `/bin/sh script` in place of `dotnet NoCrunch.Engine.dll`: a fake engine that never answers and has a child. */
class EngineProcessTest {
    @TempDir lateinit var dir: Path

    private fun fakeEngine(): Pair<Path, Path> {
        val pidFile = dir.resolve("child.pid")
        val script = dir.resolve("engine.sh")
        // The child stands in for an MSBuild node or test host the engine started.
        Files.writeString(script, "sleep 300 &\necho \$! > '$pidFile'\nwait\n")
        return script to pidFile
    }

    private fun waitForPid(pidFile: Path): Long {
        val deadline = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < deadline) {
            Files.readString(pidFile.takeIf { Files.exists(it) } ?: continue).trim().toLongOrNull()?.let { return it }
            Thread.sleep(20)
        }
        error("fake engine never started its child")
    }

    @Test
    fun stop_kills_the_engine_and_its_descendants() {
        val (script, pidFile) = fakeEngine()
        val proc = EngineProcess("/bin/sh", script, gracePeriod = Duration.ofMillis(200)) {}
        proc.start()
        val child = ProcessHandle.of(waitForPid(pidFile)).orElseThrow()

        proc.stop()

        assertTrue(child.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS) != null)
        assertFalse(child.isAlive)
    }

    @Test
    fun start_after_stop_does_not_spawn() {
        val (script, pidFile) = fakeEngine()
        val proc = EngineProcess("/bin/sh", script, gracePeriod = Duration.ofMillis(200)) {}

        proc.stop()

        assertThrows<IllegalStateException> { proc.start() }
        Thread.sleep(200)
        assertFalse(Files.exists(pidFile))
    }
}
