package com.nocrunch.rider.files

import java.io.File

object RepoPaths {
    /**
     * Paths under [repoRoot] (which the engine reports with symlinks resolved), canonicalised so that a repo
     * opened through a symlink still matches. Mirror paths (`.nocrunch`) are dropped.
     */
    fun relevant(paths: Collection<String>, repoRoot: String): List<String> =
        paths.asSequence()
            .map { if (it == repoRoot || it.startsWith("$repoRoot/")) it else canonical(it) }
            .filter { (it == repoRoot || it.startsWith("$repoRoot/")) && !isNoise(it.removePrefix(repoRoot)) }
            .distinct()
            .toList()

    /** Folders whose changes never matter to builds or tests, plus No Crunch's own exclusions file. */
    private val IGNORED_DIRS = setOf(".nocrunch", ".git", ".idea", ".vs", "bin", "obj", "node_modules", "TestResults")

    private fun isNoise(relative: String): Boolean {
        val parts = relative.split('/').filter { it.isNotEmpty() }
        return parts.dropLast(1).any { it in IGNORED_DIRS } || parts.lastOrNull() in setOf(".nocrunch", ".git")
            || relative.endsWith(".nocrunch.user")
    }

    private fun canonical(path: String): String = try {
        File(path).canonicalPath
    } catch (_: java.io.IOException) {
        path
    }
}
