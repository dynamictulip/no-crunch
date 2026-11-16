package com.nocrunch.rider.engine

/** Wire types for the engine's JSON-RPC protocol (camelCase; enums as camelCase strings). Keep in sync with EngineServer.cs. */

const val PROTOCOL_VERSION = 1

data class InitializeParams(val solutionPath: String, val dotnetPath: String?, val maxParallel: Int, val debounceMs: Int)

data class InitializeResult(val protocolVersion: Int, val repoRoot: String, val mirrorRoot: String)

data class ExclusionRuleDto(val project: String, val type: String? = null, val method: String? = null, val uid: String? = null)

data class TestResultDto(
    val key: String,
    val project: String,
    val type: String?,
    val method: String?,
    val uid: String,
    val variant: String?,
    val displayName: String,
    /** discovered, queued, running, passed, failed, skipped, excluded, stale */
    val state: String,
    val durationMs: Double?,
    val message: String?,
    val stackTrace: String?,
    val filePath: String?,
    val line: Int?,
    /** auto (continuous run) or manual (the user asked for it). */
    val runKind: String? = "auto",
    /** The last finished outcome (passed/failed/skipped) while the test is stale, queued, running or excluded. */
    val lastOutcome: String? = null,
)

/** status: succeeded, failed, blocked, cancelled */
data class BuildFinishedDto(val project: String, val status: String, val output: String, val blockedBy: String?)

/** status: completed, cancelled, crashed, unsupportedMtpVersion, unsupportedNotMtp */
data class RunFinishedDto(val project: String, val status: String, val error: String?)

/** state: idle, syncing, building, testing, paused */
data class StateChangedDto(val state: String, val queued: Int)

data class TestsDiscoveredDto(val project: String, val tests: List<TestResultDto>)

data class TestResultsDto(val updates: List<TestResultDto>)

data class LogDto(val level: String, val message: String)
