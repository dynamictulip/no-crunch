package com.nocrunch.rider.ui

/** What a gutter marker shows. Declaration order is precedence when several land on one line: best (first) wins. */
enum class MarkerKind { CAUSE, FAIL, FAIL_STALE, PASS, PASS_STALE }

/**
 * A marker for one editor line. [line] is 0-based. [method] is the test method's name for a marker on a test, so the
 * marker can land on the method's own line rather than on an attribute line above it.
 */
data class LineMarker(val line: Int, val kind: MarkerKind, val tooltip: String, val method: String? = null)
