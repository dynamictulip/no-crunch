package com.nocrunch.rider.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The engine writes enums as camelCase strings (EngineServer.CreateFormatter: JsonStringEnumConverter(CamelCase)).
 * These samples have the shapes of its notifications.
 */
class WireEnumsTest {
    private val gson = EngineJson.gson

    private fun testResult(state: String, runKind: String = "auto", lastOutcome: String? = null) = """
        {"key":"tests/Unit/Unit.csproj|A.Calc|Adds","project":"tests/Unit/Unit.csproj","type":"A.Calc","method":"Adds",
         "uid":"A.Calc.Adds","variant":null,"displayName":"Adds","state":"$state","durationMs":12.5,"message":null,
         "stackTrace":null,"filePath":"/repo/tests/Unit/Calc.cs","line":10,"runKind":"$runKind",
         "lastOutcome":${lastOutcome?.let { "\"$it\"" } ?: "null"},"theoryKey":null}
    """.trimIndent()

    @Test
    fun every_test_state_the_engine_sends_maps_to_its_constant() {
        val expected = mapOf(
            "discovered" to TestState.DISCOVERED, "queued" to TestState.QUEUED, "running" to TestState.RUNNING,
            "passed" to TestState.PASSED, "failed" to TestState.FAILED, "skipped" to TestState.SKIPPED,
            "excluded" to TestState.EXCLUDED, "stale" to TestState.STALE,
        )
        for ((wire, state) in expected) {
            val dto = gson.fromJson(testResult(wire), TestResultDto::class.java)
            assertEquals(state, dto.state, wire)
            assertEquals(wire, state.wire)
        }
    }

    @Test
    fun last_outcome_and_run_kind_map_and_null_stays_null() {
        val stale = gson.fromJson(testResult("stale", runKind = "manual", lastOutcome = "failed"), TestResultDto::class.java)
        assertEquals(TestState.FAILED, stale.lastOutcome)
        assertEquals(RunKind.MANUAL, stale.runKind)

        val fresh = gson.fromJson(testResult("passed"), TestResultDto::class.java)
        assertNull(fresh.lastOutcome)
        assertEquals(RunKind.AUTO, fresh.runKind)
    }

    @Test
    fun test_results_and_discovery_lists_map_every_entry() {
        val updates = gson.fromJson("""{"updates":[${testResult("running")},${testResult("failed")}]}""", TestResultsDto::class.java)
        assertEquals(listOf(TestState.RUNNING, TestState.FAILED), updates.updates.map { it.state })

        val discovered = gson.fromJson("""{"project":"tests/Unit/Unit.csproj","tests":[${testResult("discovered")}]}""",
            TestsDiscoveredDto::class.java)
        assertEquals(TestState.DISCOVERED, discovered.tests.single().state)
    }

    @Test
    fun every_build_status_maps() {
        val expected = mapOf(
            "succeeded" to BuildStatus.SUCCEEDED, "failed" to BuildStatus.FAILED,
            "blocked" to BuildStatus.BLOCKED, "cancelled" to BuildStatus.CANCELLED,
        )
        for ((wire, status) in expected) {
            val dto = gson.fromJson("""{"project":"src/Core/Core.csproj","status":"$wire","output":"","blockedBy":null}""",
                BuildFinishedDto::class.java)
            assertEquals(status, dto.status, wire)
        }
    }

    @Test
    fun every_run_status_maps() {
        val expected = mapOf(
            "completed" to RunStatus.COMPLETED, "cancelled" to RunStatus.CANCELLED, "crashed" to RunStatus.CRASHED,
            "unsupportedMtpVersion" to RunStatus.UNSUPPORTED_MTP_VERSION, "unsupportedNotMtp" to RunStatus.UNSUPPORTED_NOT_MTP,
        )
        for ((wire, status) in expected) {
            val dto = gson.fromJson("""{"project":"tests/Unit/Unit.csproj","status":"$wire","error":null}""", RunFinishedDto::class.java)
            assertEquals(status, dto.status, wire)
        }
    }

    @Test
    fun every_engine_state_the_engine_sends_maps() {
        val expected = mapOf(
            "idle" to EngineState.IDLE, "syncing" to EngineState.SYNCING, "building" to EngineState.BUILDING,
            "testing" to EngineState.TESTING, "paused" to EngineState.PAUSED,
        )
        for ((wire, state) in expected) {
            val dto = gson.fromJson("""{"state":"$wire","queued":3}""", StateChangedDto::class.java)
            assertEquals(state, dto.state, wire)
            assertEquals(3, dto.queued)
        }
    }

    @Test
    fun values_from_a_newer_engine_fall_back_instead_of_failing() {
        assertEquals(TestState.UNKNOWN, gson.fromJson(testResult("flaky"), TestResultDto::class.java).state)
        assertEquals(TestState.UNKNOWN, gson.fromJson(testResult("stale", lastOutcome = "inconclusive"), TestResultDto::class.java).lastOutcome)
        assertEquals(RunKind.AUTO, gson.fromJson(testResult("passed", runKind = "scheduled"), TestResultDto::class.java).runKind)
        assertEquals(BuildStatus.UNKNOWN,
            gson.fromJson("""{"project":"p","status":"skipped","output":"","blockedBy":null}""", BuildFinishedDto::class.java).status)
        assertEquals(RunStatus.UNKNOWN, gson.fromJson("""{"project":"p","status":"timedOut","error":null}""", RunFinishedDto::class.java).status)
        assertEquals(EngineState.UNKNOWN, gson.fromJson("""{"state":"indexing","queued":0}""", StateChangedDto::class.java).state)
    }

    @Test
    fun writing_uses_the_wire_names() {
        assertEquals("\"unsupportedMtpVersion\"", gson.toJson(RunStatus.UNSUPPORTED_MTP_VERSION))
        assertEquals("\"manual\"", gson.toJson(RunKind.MANUAL))
    }

    @Test
    fun test_state_groupings() {
        assertEquals(setOf(TestState.PASSED, TestState.FAILED, TestState.SKIPPED), TestState.entries.filter { it.isOutcome }.toSet())
        assertEquals(setOf(TestState.QUEUED, TestState.RUNNING, TestState.STALE, TestState.DISCOVERED),
            TestState.entries.filter { it.isPending }.toSet())
    }

    @Test
    fun build_status_groupings() {
        assertEquals(setOf(BuildStatus.FAILED, BuildStatus.BLOCKED), BuildStatus.entries.filter { it.isBroken }.toSet())
    }

    @Test
    fun run_status_groupings() {
        assertEquals(setOf(RunStatus.UNSUPPORTED_MTP_VERSION, RunStatus.UNSUPPORTED_NOT_MTP), RunStatus.entries.filter { it.isUnsupported }.toSet())
    }

    @Test
    fun engine_state_groupings() {
        assertEquals(setOf(EngineState.SYNCING, EngineState.BUILDING, EngineState.TESTING), EngineState.entries.filter { it.isUpdating }.toSet())
        assertEquals(setOf(EngineState.STARTING, EngineState.SYNCING, EngineState.BUILDING, EngineState.TESTING),
            EngineState.entries.filter { it.isBusy }.toSet())
        assertTrue(EngineState.BUILDING.isBusy)
        assertFalse(EngineState.UNKNOWN.isBusy)
    }
}
