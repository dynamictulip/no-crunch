package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.TestResultDto
import com.nocrunch.rider.engine.TestState

/** What a gutter marker shows. Declaration order is precedence when several land on one line: best (first) wins. */
enum class MarkerKind { CAUSE, FAIL, FAIL_STALE, PASS, PASS_STALE }

/**
 * A marker for one editor line. [line] is 0-based. [method] is the test method's name for a marker on a test, so the
 * marker can land on the method's own line rather than on an attribute line above it.
 */
data class LineMarker(val line: Int, val kind: MarkerKind, val tooltip: String, val method: String? = null)

/** Works out which gutter markers the latest test results call for. */
object EditorMarkers {
    /**
     * Markers per file path, at most one per line, sorted by line. A test method gets its latest outcome (hollow while
     * stale). Tests that haven't run, are skipped or are excluded get none.
     */
    fun compute(results: Collection<TestResultDto>, @Suppress("UNUSED_PARAMETER") isRepoFile: (String) -> Boolean): Map<String, List<LineMarker>> {
        val byFile = HashMap<String, MutableList<LineMarker>>()
        results.asSequence()
            .filter { it.filePath != null && it.line != null && it.state != TestState.EXCLUDED }
            .groupBy { it.filePath!! to it.line!! }
            .forEach { (at, group) -> testMarker(at.second, group)?.let { byFile.getOrPut(at.first) { ArrayList() } += it } }
        return byFile.mapValues { (_, markers) -> markers.sortedBy { it.line } }
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
