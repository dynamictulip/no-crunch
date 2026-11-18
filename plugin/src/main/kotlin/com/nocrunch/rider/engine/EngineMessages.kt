package com.nocrunch.rider.engine

/** Wire types for the engine's JSON-RPC protocol (camelCase; enums as camelCase strings, see [WireEnum]). Keep in sync with EngineServer.cs. */

const val PROTOCOL_VERSION = 1

/** [testTimeoutSeconds]: stop a test run that reports nothing for this long and fail its unfinished tests; 0 = no timeout, null = engine default (30). */
data class InitializeParams(
    val solutionPath: String,
    val dotnetPath: String?,
    val maxParallel: Int,
    val debounceMs: Int,
    val testTimeoutSeconds: Int? = null,
)

data class InitializeResult(val protocolVersion: Int, val repoRoot: String, val mirrorRoot: String)

/** [include]: an exception that brings back tests a broader rule excludes. */
data class ExclusionRuleDto(
    val project: String,
    val type: String? = null,
    val method: String? = null,
    val uid: String? = null,
    val include: Boolean = false,
)

data class TestResultDto(
    val key: String,
    val project: String,
    val type: String?,
    val method: String?,
    val uid: String,
    val variant: String?,
    val displayName: String,
    val state: TestState,
    val durationMs: Double?,
    val message: String?,
    val stackTrace: String?,
    val filePath: String?,
    val line: Int?,
    /** Continuous run or one the user asked for. */
    val runKind: RunKind? = RunKind.AUTO,
    /** The last finished outcome (passed/failed/skipped) while the test is stale, queued, running or excluded. */
    val lastOutcome: TestState? = null,
    /** Set on a data row of a theory whose rows are only known at run time: the theory's key. */
    val theoryKey: String? = null,
)

/** status: succeeded, failed, blocked or cancelled. */
data class BuildFinishedDto(val project: String, val status: BuildStatus, val output: String, val blockedBy: String?)

data class RunFinishedDto(val project: String, val status: RunStatus, val error: String?)

/** state: idle, syncing, building, testing or paused. */
data class StateChangedDto(val state: EngineState, val queued: Int)

data class TestsDiscoveredDto(val project: String, val tests: List<TestResultDto>)

data class TestResultsDto(val updates: List<TestResultDto>)

data class LogDto(val level: String, val message: String)
