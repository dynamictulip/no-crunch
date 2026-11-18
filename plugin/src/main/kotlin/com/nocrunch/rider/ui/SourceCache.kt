package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.TestResultDto
import java.io.File

/**
 * Source text by path, for the parameter names of data rows whose display names only show values. A file is re-read
 * when it changes, and forgotten once no test of the latest build points at it (tests move and files get deleted).
 * Reads files, so use it off the EDT; not thread-safe, so from one coroutine at a time.
 */
class SourceCache {
    private val texts = HashMap<String, Pair<Long, String>>()
    private val used = HashSet<String>()

    /** Paths currently held; for tests. */
    val cachedPaths: Set<String> get() = texts.keys.toSet()

    fun paramNames(d: TestResultDto): List<String>? {
        val path = d.filePath ?: return null
        val line = d.line ?: return null
        used += path
        val file = File(path)
        val modified = file.lastModified().takeIf { it > 0 } ?: return null
        val text = texts[path]?.takeIf { it.first == modified }?.second
            ?: runCatching { file.readText() }.getOrNull()?.also { texts[path] = modified to it } ?: return null
        return TestLocator.parameterNames(text, line - 1, d.method?.substringBefore('('))
    }

    /** Call after each build: drops files that no test asked about since the previous call. */
    fun prune() {
        texts.keys.retainAll(used)
        used.clear()
    }
}
