package com.nocrunch.rider.exclusions

import com.nocrunch.rider.engine.ExclusionRuleDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ExclusionStoreTest {
    @TempDir lateinit var dir: Path

    @Test
    fun missing_file_loads_empty() {
        assertTrue(ExclusionStore(dir.resolve("none.nocrunch.user")).load().isEmpty())
    }

    @Test
    fun roundtrip_preserves_rules() {
        val store = ExclusionStore(dir.resolve("S.nocrunch.user"))
        val rules = listOf(
            ExclusionRuleDto("tests/E2E/E2E.csproj"),
            ExclusionRuleDto("tests/Unit/Unit.csproj", "A.Slow", null, null),
            ExclusionRuleDto("tests/Unit/Unit.csproj", "A.C", "One", null),
            ExclusionRuleDto("tests/Unit/Unit.csproj", null, null, "uid-1"),
            ExclusionRuleDto("tests/E2E/E2E.csproj", "B.Smoke", include = true),
        )
        store.save(rules)

        assertEquals(rules, ExclusionStore(dir.resolve("S.nocrunch.user")).load())
        val json = Files.readString(dir.resolve("S.nocrunch.user"))
        assertTrue(json.contains("\"version\": 1"), json)
        assertTrue(json.contains("\"excluded\""), json)
        assertTrue(json.contains("\"included\""), json)
        assertTrue(!json.contains("\"include\""), "exceptions go in their own list, not a flag on each rule: $json")
    }

    @Test
    fun corrupt_file_loads_empty() {
        val file = dir.resolve("bad.nocrunch.user")
        Files.writeString(file, "{ not json")
        assertTrue(ExclusionStore(file).load().isEmpty())
    }

    @Test
    fun file_named_after_solution() {
        val store = ExclusionStore.forSolution(dir.resolve("My Shop.slnx"))
        assertEquals(dir.resolve("My Shop.nocrunch.user"), store.file)
    }

    @Test
    fun concurrent_saves_never_throw_and_leave_a_complete_file() {
        val store = ExclusionStore(dir.resolve("S.nocrunch.user"))
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = (1..8).map { n ->
            Thread {
                repeat(25) { i ->
                    try { store.save(List(n) { ExclusionRuleDto("p$it.csproj", "T$i") }) } catch (e: Throwable) { errors += e }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue(errors.isEmpty(), errors.joinToString { it.toString() })
        assertTrue(store.load().isNotEmpty())
    }
}
