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
            .filter { (it == repoRoot || it.startsWith("$repoRoot/")) && !it.contains("/.nocrunch/") && !it.endsWith("/.nocrunch") }
            .distinct()
            .toList()

    private fun canonical(path: String): String = try {
        File(path).canonicalPath
    } catch (_: java.io.IOException) {
        path
    }
}
