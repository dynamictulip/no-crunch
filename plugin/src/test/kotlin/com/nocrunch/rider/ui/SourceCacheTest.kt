package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.TestResultDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SourceCacheTest {
    @TempDir
    lateinit var dir: File

    private fun row(file: File, method: String = "Adds(System.Int32,System.Int32)") = TestResultDto(
        key = "p|C|$method|${file.name}", project = "p", type = "C", method = method, uid = "u", variant = "C.Adds(1, 2)",
        displayName = "C.Adds(1, 2)", state = "passed", durationMs = null, message = null, stackTrace = null,
        filePath = file.path, line = 1,
    )

    private fun source(name: String, params: String) = File(dir, name).apply { writeText("public void Adds($params) { }\n") }

    @Test
    fun reads_parameter_names_from_the_source() {
        val cache = SourceCache()
        assertEquals(listOf("a", "b"), cache.paramNames(row(source("A.cs", "int a, int b"))))
    }

    @Test
    fun rereads_a_file_once_it_changes() {
        val cache = SourceCache()
        val file = source("A.cs", "int a, int b")
        cache.paramNames(row(file))
        file.writeText("public void Adds(int x, int y) { }\n")
        file.setLastModified(file.lastModified() + 2_000)
        assertEquals(listOf("x", "y"), cache.paramNames(row(file)))
    }

    @Test
    fun forgets_files_no_test_of_the_last_build_pointed_at() {
        val cache = SourceCache()
        val a = source("A.cs", "int a, int b")
        val b = source("B.cs", "int c, int d")
        cache.paramNames(row(a))
        cache.paramNames(row(b))
        cache.prune()
        assertEquals(setOf(a.path, b.path), cache.cachedPaths)

        cache.paramNames(row(b))
        cache.prune()
        assertEquals(setOf(b.path), cache.cachedPaths)
    }
}
