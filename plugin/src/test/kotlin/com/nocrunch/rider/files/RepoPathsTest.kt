package com.nocrunch.rider.files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RepoPathsTest {
    @TempDir lateinit var dir: Path

    @Test
    fun keeps_paths_under_root_and_drops_mirror_and_outside() {
        val root = dir.toRealPath().resolve("repo").also { Files.createDirectories(it) }.toString()
        val result = RepoPaths.relevant(listOf("$root/src/A.cs", "$root/.nocrunch/S/mirror/src/A.cs", "/elsewhere/B.cs", root), root)
        assertEquals(listOf("$root/src/A.cs", root), result)
    }

    @Test
    fun accepts_paths_through_a_symlink_to_the_root() {
        val real = dir.toRealPath().resolve("real").also { Files.createDirectories(it.resolve("src")) }
        val link = Files.createSymbolicLink(dir.resolve("link"), real)

        val result = RepoPaths.relevant(listOf("$link/src/A.cs", "$link/src/Deleted.cs"), real.toString())

        assertEquals(listOf("$real/src/A.cs", "$real/src/Deleted.cs"), result)
    }
}
