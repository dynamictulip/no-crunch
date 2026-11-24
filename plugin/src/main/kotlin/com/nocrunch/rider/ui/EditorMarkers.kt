package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.CoverageFileDto
import com.nocrunch.rider.engine.TestResultDto
import com.nocrunch.rider.engine.TestState

/**
 * What a gutter marker shows. Declaration order is precedence when several land on one line: best (first) wins, so a
 * coverage dash never hides a test or failure marker.
 */
enum class MarkerKind { CAUSE, FAIL, FAIL_STALE, PASS, PASS_STALE, NOT_COVERED, NOT_COVERED_STALE }

/**
 * A marker for one editor line. [line] is 0-based. [method] is the test method's name for a marker on a test, so the
 * marker can land on the method's own line rather than on an attribute line above it.
 */
data class LineMarker(val line: Int, val kind: MarkerKind, val tooltip: String, val method: String? = null)

/** Works out which gutter markers the latest test results call for. */
object EditorMarkers {
    /**
     * Markers per file path, at most one per line, sorted by line. A test method gets its latest outcome (hollow while
     * stale). A failing test also marks the line it threw from, in the first stack frame inside the repo that
     * [isRepoFile] accepts. Tests that haven't run, are skipped or are excluded get none.
     */
    fun compute(results: Collection<TestResultDto>, isRepoFile: (String) -> Boolean): Map<String, List<LineMarker>> {
        val byLine = HashMap<Pair<String, Int>, LineMarker>()
        fun add(file: String, marker: LineMarker) {
            byLine.merge(file to marker.line, marker) { old, new -> if (new.kind < old.kind) new else old }
        }
        val located = results.filter { it.filePath != null && it.line != null && it.state != TestState.EXCLUDED }
        located.groupBy { it.filePath!! to it.line!! }.forEach { (at, group) -> testMarker(at.second, group)?.let { add(at.first, it) } }
        located.filter { shownOutcome(it) == TestState.FAILED }.forEach { r ->
            val (file, line) = topRepoFrame(r.stackTrace, isRepoFile) ?: return@forEach
            add(file, causeMarker(line, r))
        }
        return byLine.entries.groupBy({ it.key.first }, { it.value }).mapValues { (_, markers) -> markers.sortedBy { it.line } }
    }

    /**
     * Markers for the line coverage of one source file: a circle where only passing tests ran the line, a diamond where
     * a failing test did, a dash where none did. Stale data gives the stale variants. Lines are 0-based, sorted.
     */
    fun coverageMarkers(file: CoverageFileDto): List<LineMarker> {
        val stale = file.stale
        fun tip(text: String) = if (stale) "$text, stale" else text
        val passing = file.passing.map { LineMarker(it - 1, if (stale) MarkerKind.PASS_STALE else MarkerKind.PASS, tip("Hit only by passing tests")) }
        val failing = file.failing.map { LineMarker(it - 1, if (stale) MarkerKind.FAIL_STALE else MarkerKind.FAIL, tip("Hit by failing tests")) }
        val notCovered = file.notCovered.map { LineMarker(it - 1, if (stale) MarkerKind.NOT_COVERED_STALE else MarkerKind.NOT_COVERED, tip("Not covered by any test")) }
        return (passing + failing + notCovered).sortedBy { it.line }
    }

    private val STACK_FRAME = Regex("""in (/[^\n]+?):line (\d+)""")

    /** The first stack frame (`in /path/File.cs:line 12`) in a file [isRepoFile] accepts, as path and 1-based line. */
    fun topRepoFrame(stackTrace: String?, isRepoFile: (String) -> Boolean): Pair<String, Int>? =
        stackTrace?.let { STACK_FRAME.findAll(it) }
            ?.map { it.groupValues[1] to it.groupValues[2].toInt() }
            ?.firstOrNull { isRepoFile(it.first) }

    private fun causeMarker(line: Int, r: TestResultDto): LineMarker {
        val name = r.method?.substringBefore('(') ?: r.displayName
        return if (r.state.isOutcome) {
            val reason = r.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: name
            LineMarker(line - 1, MarkerKind.CAUSE, "Cause of failure: $reason")
        } else {
            LineMarker(line - 1, MarkerKind.FAIL_STALE, "Failing, stale: $name")
        }
    }

    private fun shownOutcome(r: TestResultDto): TestState? = if (r.state.isOutcome) r.state else r.lastOutcome

    /** One marker for the results (theory variants) a test method line holds: its worst outcome, hollow if that is stale. */
    private fun testMarker(line: Int, group: List<TestResultDto>): LineMarker? {
        val worst = TestTreeModel.worst(group.mapNotNull { shownOutcome(it) }) ?: return null
        if (worst != TestState.FAILED && worst != TestState.PASSED) return null
        val contributors = group.filter { shownOutcome(it) == worst }
        val stale = contributors.any { !it.state.isOutcome }
        val first = contributors.first()
        val name = first.method?.substringBefore('(') ?: first.displayName
        val (kind, label) = when {
            worst == TestState.FAILED && stale -> MarkerKind.FAIL_STALE to "Failing, stale"
            worst == TestState.FAILED -> MarkerKind.FAIL to "Failing"
            stale -> MarkerKind.PASS_STALE to "Passing, stale"
            else -> MarkerKind.PASS to "Passing"
        }
        return LineMarker(line - 1, kind, "$label: $name", first.method?.substringBefore('('))
    }
}

/** [f] that asks once per input: for a check that touches the disk, within one batch of results. */
fun <A, B> memoized(f: (A) -> B): (A) -> B {
    val cache = HashMap<A, B>()
    return { a -> cache.getOrPut(a) { f(a) } }
}
