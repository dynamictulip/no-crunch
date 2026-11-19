package com.nocrunch.rider.ui

import com.nocrunch.rider.view.SortMode
import com.nocrunch.rider.engine.BuildStatus
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.TestState
import com.nocrunch.rider.exclusions.TestExclusions

enum class GroupKind { PROJECT, FOLDER, FILE, NAMESPACE, CLASS }

/**
 * A row below a project. Aggregates (counts, run time, freshness) are worked out from the tests underneath once, when
 * the row is made: the renderer reads them for every painted row.
 */
sealed interface ViewNode {
    val label: String
    val leaves: List<TestNode>
    /** Exclusion rules that toggling this row's checkbox adds or removes. */
    val rules: List<ExclusionRuleDto>
    val excluded: Boolean
    /** Can't be included or excluded on its own (theory data rows). */
    val locked: Boolean
    /** The row above it is excluded, so "(excluded)" isn't worth repeating. */
    val parentExcluded: Boolean
    val totals: Totals

    val counts: Counts get() = totals.counts
    val durationMs: Double? get() = totals.durationMs
    val manualCount: Int get() = totals.manualCount
    val shownOutcome: TestState? get() = totals.shownOutcome
    val freshness: TestState? get() = totals.freshness
    val isFresh: Boolean get() = freshness == null
}

data class TestView(val node: TestNode) : ViewNode {
    override val label get() = node.label
    override val leaves = listOf(node)
    override val totals = Totals.of(leaves)
    override val rules get() = listOf(TestExclusions.ruleFor(node.dto))
    override val excluded get() = node.excluded
    override val locked get() = node.locked
    override val parentExcluded get() = node.parentExcluded
}

/**
 * One row for any grouping level. [key] identifies the row across rebuilds (expansion state); [wholeRule] is the class
 * or project rule when the row holds everything in that class or project, so ticking it writes one rule, not one per test.
 */
data class GroupView(
    val key: String,
    override val label: String,
    val kind: GroupKind,
    val children: List<ViewNode>,
    override val parentExcluded: Boolean,
    /** Set on PROJECT rows only, for the renderer's build status. */
    val project: ProjectNode? = null,
    val wholeRule: ExclusionRuleDto? = null,
) : ViewNode {
    override val locked get() = false
    override val leaves = children.flatMap { it.leaves }
    override val totals = Totals.of(leaves)
    override val rules: List<ExclusionRuleDto> get() {
        // A whole-project rule covers everything below it. A whole-class rule covers its own tests, but a nested
        // class keeps its own rule.
        val covered = when {
            wholeRule == null -> children
            kind == GroupKind.PROJECT -> emptyList()
            else -> children.filter { it is GroupView && it.kind == GroupKind.CLASS }
        }
        return (listOfNotNull(wholeRule) + covered.flatMap { it.rules }).distinct()
    }
    override val excluded = leaves.isNotEmpty() && leaves.all { it.excluded }
    val excludedCount = leaves.count { it.excluded }
    val partlyExcluded = excludedCount > 0 && !excluded
}

object TreeView {
    const val NO_FILE = "(no file)"

    internal fun buildRank(status: BuildStatus?): Int = when (status) {
        BuildStatus.FAILED -> 0
        BuildStatus.BLOCKED -> 1
        else -> 2
    }

    /** Sorts rows, and recursively the rows inside groups and classes. */
    internal fun sortViews(nodes: List<ViewNode>, sort: SortMode, parent: GroupKind? = null): List<ViewNode> =
        nodes.map {
            when (it) {
                is GroupView -> it.copy(children = sortViews(it.children, sort, it.kind))
                is TestView -> it
            }
        }.sortedWith(
            // Like a file explorer, name order keeps folders first, and "(no file)" and tests that skip a level last;
            // but a class's own tests come before the classes nested in it, as in the source.
            // Broken builds outrank any test result.
            when (sort) {
                SortMode.NAME -> compareBy<ViewNode> { kindRank(it, parent) }.then(comparator(sort, { it.label }, { it.leaves }, { it.durationMs }))
                SortMode.STATUS -> compareBy<ViewNode> { buildRank((it as? GroupView)?.project?.buildStatus) }
                    .then(comparator(sort, { it.label }, { it.leaves }, { it.durationMs }))
                SortMode.DURATION -> comparator(sort, { it.label }, { it.leaves }, { it.durationMs })
            }
        )

    private fun kindRank(v: ViewNode, parent: GroupKind?): Int = when {
        v is TestView && parent == GroupKind.CLASS -> -1
        v is GroupView && v.label == NO_FILE -> 2
        v is GroupView && v.kind == GroupKind.FOLDER -> 0
        v is TestView -> 2
        else -> 1
    }

    private fun <T> comparator(sort: SortMode, name: (T) -> String, leaves: (T) -> List<TestNode>, duration: (T) -> Double?): Comparator<T> {
        val byName = compareBy<T, String>(NaturalOrder) { name(it) }
        return when (sort) {
            SortMode.NAME -> byName
            SortMode.STATUS -> compareBy<T> { leaves(it).minOfOrNull(::statusRank) ?: Int.MAX_VALUE }.then(byName)
            SortMode.DURATION -> compareBy<T> { duration(it) == null }.thenByDescending { duration(it) ?: 0.0 }.then(byName)
        }
    }

    /** Lower is worse: failing, failing but stale, not run yet, passing but stale, passing, skipped, excluded. */
    fun statusRank(t: TestNode): Int = when {
        t.excluded && !t.manual -> 6
        t.shownOutcome == TestState.FAILED -> if (t.freshness == null) 0 else 1
        t.shownOutcome == null -> 2
        t.shownOutcome == TestState.PASSED -> if (t.freshness == null) 4 else 3
        else -> 5
    }

    /**
     * Classes view drops the namespace every class in the project shares, so `Shop.Unit.OtherTests` and
     * `Shop.Unit.Pricing.CalcTests` show as `OtherTests` and `Pricing.CalcTests`. Returns it with its trailing dot.
     */
    fun sharedNamespace(types: List<String>): String {
        val namespaces = types.filter { it.contains('.') && it != TestTreeModel.UNKNOWN_CLASS }.map { it.substringBeforeLast('.').split('.') }
        if (namespaces.isEmpty() || namespaces.size < types.size) return ""
        val common = namespaces.reduce { acc, ns -> acc.zip(ns).takeWhile { (a, b) -> a == b }.map { it.first } }
        return if (common.isEmpty()) "" else common.joinToString(".") + "."
    }

    /** Path of [file] inside the project's folder, or just the file name if it lives elsewhere. */
    internal fun relativeTo(file: String, projectDir: String): String {
        val marker = "/$projectDir/"
        val idx = if (projectDir.isEmpty()) -1 else file.indexOf(marker)
        return if (idx >= 0) file.substring(idx + marker.length) else file.substringAfterLast('/')
    }
}
