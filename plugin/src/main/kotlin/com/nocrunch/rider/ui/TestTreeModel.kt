package com.nocrunch.rider.ui

import com.nocrunch.rider.view.NameColouring
import com.nocrunch.rider.ProjectBuild
import com.nocrunch.rider.engine.BuildStatus
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.exclusions.TestExclusions
import com.nocrunch.rider.engine.RunFinishedDto
import com.nocrunch.rider.engine.RunKind
import com.nocrunch.rider.engine.TestResultDto
import com.nocrunch.rider.engine.TestState
import com.nocrunch.rider.exclusions.ExclusionRules

/** Pending = queued, running, stale or just discovered. Skipped tests, and excluded tests that haven't been run by hand, aren't counted. */
data class Counts(val passed: Int = 0, val failed: Int = 0, val pending: Int = 0) {
    operator fun plus(o: Counts) = Counts(passed + o.passed, failed + o.failed, pending + o.pending)

    fun label() = "✔ $passed  ✖ $failed  ○ $pending"
}

/**
 * [locked]: can't be included or excluded on its own (theory data rows, which follow their theory).
 * [parentExcluded]: its class is excluded, so "(excluded)" isn't worth repeating on it.
 * [rows]: data rows of a theory whose rows are only known once it runs (they share its UID); shown nested under it.
 * [isRow]: this node is one of those rows.
 * [grouped]: made up by the tool window to hold data rows that were listed as separate tests (xUnit theories with
 * serialisable data, MSTest `DataRow`s). The engine doesn't know its key; running it runs its rows.
 */
data class TestNode(
    val dto: TestResultDto,
    val excluded: Boolean,
    val locked: Boolean = false,
    val rows: List<TestNode> = emptyList(),
    val isRow: Boolean = false,
    val parentExcluded: Boolean = false,
    val grouped: Boolean = false,
    /** Parameter names from the source, for rows whose display name only has values. */
    val argNames: List<String>? = null,
    /** A data row's arguments padded into columns that line up with the other rows of its theory. */
    val aligned: String? = null,
) {
    /** Keys to ask the engine to run for this node. */
    val runKeys: List<String> get() = if (grouped) rows.map { it.dto.key } else listOf(dto.key)

    /**
     * Rows show just their arguments. Everything else drops the class prefix some frameworks put in display names
     * (xUnit's `Namespace.Class.Method(...)`), since the class is already the row above.
     */
    val label: String
        get() = if (isRow) aligned ?: rawLabel else rawLabel

    private val rawLabel: String
        get() = if (isRow) dto.displayName.indexOf('(').takeIf { it >= 0 }?.let { NameColouring.withNames(dto.displayName.substring(it), argNames) } ?: dto.displayName
        else dto.type?.let { dto.displayName.removePrefix("$it.") } ?: dto.displayName

    // Worked out once, when the node is made: the tree renderer and sorting read these over and over.

    /** The result comes from a run the user asked for, not from continuous testing. */
    val manual: Boolean = dto.runKind == RunKind.MANUAL

    /** Outcome to show: the current one, or the last one while the test is stale/queued/running. */
    val shownOutcome: TestState? = if (dto.state.isOutcome) dto.state else dto.lastOutcome

    /** Null when [shownOutcome] is current; otherwise why it isn't (stale, queued, running, ...). */
    val freshness: TestState? = when {
        dto.state.isOutcome -> null
        // Excluded tests have no result this session until you run them by hand.
        dto.state == TestState.EXCLUDED -> TestState.STALE
        else -> dto.state
    }

    /**
     * Its result counts towards its parents' status, counts, run time and freshness. Excluded tests only count while
     * they hold a manual run's result that isn't stale yet.
     */
    val countsForParents: Boolean = !excluded || (manual && freshness != TestState.STALE)

    /** Run time that counts towards parents: excluded tests only count once run by hand. */
    val countedDurationMs: Double? = if (countsForParents) dto.durationMs else null

    val counts: Counts = when {
        rows.isNotEmpty() -> rows.fold(Counts()) { acc, r -> acc + r.counts }
        !countsForParents -> Counts()
        dto.state == TestState.PASSED -> Counts(passed = 1)
        dto.state == TestState.FAILED -> Counts(failed = 1)
        dto.state.isPending -> Counts(pending = 1)
        else -> Counts()
    }
}

/**
 * What a row above tests shows about them, worked out once when the row is made rather than folded over every test
 * each time it's read (the renderer reads these for every painted row).
 */
data class Totals(
    val counts: Counts,
    /** Total run time of the tests below, or null if none has run. */
    val durationMs: Double?,
    val manualCount: Int,
    val shownOutcome: TestState?,
    /** Why some results below aren't current: running, queued or stale; null when everything is current. */
    val freshness: TestState?,
) {
    companion object {
        fun of(tests: List<TestNode>): Totals {
            val counting = tests.filter { it.countsForParents }
            return Totals(
                counts = tests.fold(Counts()) { acc, t -> acc + t.counts },
                durationMs = counting.mapNotNull { it.countedDurationMs }.takeIf { it.isNotEmpty() }?.sum(),
                manualCount = counting.count { it.manual },
                shownOutcome = TestTreeModel.worst(counting.mapNotNull { it.shownOutcome }),
                freshness = TestTreeModel.summarise(counting.mapNotNull { it.freshness }),
            )
        }
    }
}

data class ClassNode(
    val project: String,
    val type: String,
    val excluded: Boolean,
    val children: List<TestNode>,
    val locked: Boolean = false,
    /** Its project is excluded. */
    val parentExcluded: Boolean = false,
) {
    val totals: Totals = Totals.of(children)
    val counts: Counts get() = totals.counts
    val manualCount: Int get() = totals.manualCount
    val durationMs: Double? get() = totals.durationMs
    val shownOutcome: TestState? get() = totals.shownOutcome
    val freshness: TestState? get() = totals.freshness
    /** Every shown outcome is current (nothing stale, queued or running). */
    val isFresh: Boolean get() = freshness == null

    /** Rules that toggling this node adds or removes. Tests without a class are excluded one by one (by UID), never the whole project. */
    val rules: List<ExclusionRuleDto>
        get() = if (type == TestTreeModel.UNKNOWN_CLASS) children.map { TestExclusions.ruleFor(it.dto) } else listOf(ExclusionRuleDto(project, type))
}

data class ProjectNode(
    val path: String,
    val name: String,
    /** Null if not built this session. */
    val buildStatus: BuildStatus?,
    val build: ProjectBuild?,
    val run: RunFinishedDto?,
    val excluded: Boolean,
    val children: List<ClassNode>,
    /** The engine knows tests in it, whether or not the view filters show any of them. */
    val hasTests: Boolean = children.isNotEmpty(),
) {
    val totals: Totals = Totals.of(children.flatMap { it.children })
    val counts: Counts get() = totals.counts
    val manualCount: Int get() = totals.manualCount
    val durationMs: Double? get() = totals.durationMs
    val shownOutcome: TestState? get() = totals.shownOutcome
    val freshness: TestState? get() = totals.freshness
    val isFresh: Boolean get() = freshness == null
    val rule: ExclusionRuleDto get() = ExclusionRuleDto(path)
    /**
     * False for library/app projects that only appear because their build broke: no tests found and never run.
     * Based on every test, not the filtered [children], so filters don't move a test project into "Build errors".
     */
    val isTestProject: Boolean get() = hasTests || run != null
    /** Short name of the project whose broken build stops this one, if any. */
    val blockedByName: String? get() = build?.blockedBy?.let(TestTreeModel::shortName)
}

/** Pure function from engine state to the tree shown in the tool window. */
object TestTreeModel {
    const val UNKNOWN_CLASS = "(no class)"

    fun build(
        tests: Collection<TestResultDto>,
        builds: Map<String, ProjectBuild>,
        rules: List<ExclusionRuleDto>,
        failuresOnly: Boolean,
        runs: Map<String, RunFinishedDto> = emptyMap(),
        hideExcluded: Boolean = false,
        /** Parameter names of a test's method, read from its source; used to label data rows that only show values. */
        paramNames: (TestResultDto) -> List<String>? = { null },
    ): List<ProjectNode> = filter(buildAll(tests, builds, rules, runs, paramNames), failuresOnly, hideExcluded)

    /**
     * The view filters applied to a model built without them. One unfiltered build per batch of results is shared:
     * the progress bar, tool window icon and status bar count every test, and the tree filters it.
     */
    fun filter(projects: List<ProjectNode>, failuresOnly: Boolean, hideExcluded: Boolean): List<ProjectNode> {
        if (!failuresOnly && !hideExcluded) return projects
        return projects.mapNotNull { p ->
            val classes = p.children
                .map { c ->
                    val kept = c.children
                        // Same outcome the tree shows, so failures stay listed (muted) while stale, queued or
                        // running, and a theory stays while any of its rows fails.
                        .filter { !failuresOnly || it.shownOutcome == TestState.FAILED || it.rows.any { r -> r.shownOutcome == TestState.FAILED } }
                        .filter { !hideExcluded || !it.excluded }
                    if (kept.size == c.children.size) c else c.copy(children = kept)
                }
                .filter { it.children.isNotEmpty() }
            val buildBroken = p.buildStatus?.isBroken == true
            when {
                failuresOnly && classes.isEmpty() && !buildBroken -> null
                hideExcluded && classes.isEmpty() && p.hasTests && !buildBroken -> null
                classes.size == p.children.size && classes.indices.all { classes[it] === p.children[it] } -> p
                else -> p.copy(children = classes)
            }
        }
    }

    /** Every project and test, without the view filters. */
    fun buildAll(
        tests: Collection<TestResultDto>,
        builds: Map<String, ProjectBuild>,
        rules: List<ExclusionRuleDto>,
        runs: Map<String, RunFinishedDto> = emptyMap(),
        paramNames: (TestResultDto) -> List<String>? = { null },
    ): List<ProjectNode> {
        val byProject = tests.groupBy { it.project }
        val brokenBuilds = builds.values.filter { it.status.isBroken }.map { it.project }
        val unsupported = runs.values.filter { it.status.isUnsupported }.map { it.project }
        val projectPaths = (byProject.keys + brokenBuilds + unsupported).toSortedSet()

        return projectPaths.map { path ->
            val projectExcluded = ExclusionRules.isExcluded(ExclusionRuleDto(path), rules)
            val classes = byProject[path].orEmpty()
                .groupBy { it.type ?: UNKNOWN_CLASS }
                .toSortedMap()
                .map { (type, members) ->
                    // Tests without a class have no class rule: that row is excluded when all of them are.
                    val classExcluded = if (type == UNKNOWN_CLASS) members.isNotEmpty() && members.all { TestExclusions.isExcluded(it, rules) }
                    else ExclusionRules.isExcluded(ExclusionRuleDto(path, type), rules)
                    // Data rows of theories whose rows were only known at run time nest under their theory.
                    val keys = members.map { it.key }.toSet()
                    val (rowDtos, topLevel) = members.partition { it.theoryKey != null && it.theoryKey in keys }
                    val rowsByTheory = rowDtos.groupBy { it.theoryKey }
                    fun node(d: TestResultDto, isRow: Boolean = false) =
                        TestNode(d, TestExclusions.isExcluded(d, rules), locked = isRow, isRow = isRow, parentExcluded = classExcluded)
                    val nodes = topLevel
                        .sortedWith(compareBy({ it.method ?: it.displayName }, { it.displayName }))
                        .map { d ->
                            node(d).copy(rows = rowsByTheory[d.key].orEmpty().map { node(it, isRow = true) })
                        }
                        .let { groupListedRows(it, rules, classExcluded) }
                        .map { n ->
                            if (n.rows.isEmpty()) n
                            else n.copy(rows = alignRows(n.rows.map { r -> r.copy(argNames = paramNames(r.dto)) }.sortedWith(compareBy(NaturalOrder) { it.label })))
                        }
                    ClassNode(path, type, classExcluded, nodes, parentExcluded = projectExcluded)
                }
            val build = builds[path]
            ProjectNode(path, shortName(path), build?.status, build, runs[path], projectExcluded, classes,
                hasTests = byProject[path].orEmpty().isNotEmpty())
        }.sortedBy { it.name }
    }

    /**
     * Tests that are data rows of one method, each listed as its own test (they all have a variant), nest under a
     * made-up node named after the method, the same way rows only known at run time nest under their theory.
     */
    private fun groupListedRows(nodes: List<TestNode>, rules: List<ExclusionRuleDto>, classExcluded: Boolean): List<TestNode> {
        val byMethod = nodes.filter { it.rows.isEmpty() && it.dto.method != null && it.dto.variant != null }.groupBy { it.dto.method }
            .filterValues { it.size >= 2 }
        if (byMethod.isEmpty()) return nodes
        val grouped = byMethod.mapValues { (_, members) -> group(members, rules, classExcluded) }
        val done = mutableSetOf<String?>()
        return nodes.mapNotNull { n ->
            val m = n.dto.method
            when {
                m !in grouped || n.rows.isNotEmpty() || n.dto.variant == null -> n
                done.add(m) -> grouped.getValue(m)
                else -> null
            }
        }
    }

    private fun alignRows(rows: List<TestNode>): List<TestNode> =
        rows.zip(NameColouring.alignArgs(rows.map { it.label })) { r, a -> r.copy(aligned = a) }

    private fun group(members: List<TestNode>, rules: List<ExclusionRuleDto>, classExcluded: Boolean): TestNode {
        val dtos = members.map { it.dto }
        val first = dtos.first()
        val outcome = worst(members.mapNotNull { it.shownOutcome })
        val pending = GROUP_PENDING_ORDER.firstOrNull { s -> dtos.any { it.state == s } }
        val dto = first.copy(
            key = "${first.project}|${first.type}|${first.method}|(rows)",
            variant = null,
            displayName = members.first().label.substringBefore('(').trim(),
            state = if (dtos.any { it.state == TestState.FAILED }) TestState.FAILED else pending ?: outcome ?: first.state,
            durationMs = dtos.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum(),
            message = null,
            stackTrace = null,
            runKind = if (dtos.any { it.runKind == RunKind.MANUAL }) RunKind.MANUAL else RunKind.AUTO,
            lastOutcome = outcome,
            theoryKey = null,
        )
        val rows = members.map { it.copy(locked = true, isRow = true) }
        return TestNode(dto, TestExclusions.isExcluded(dto, rules), rows = rows, parentExcluded = classExcluded, grouped = true)
    }

    fun shortName(path: String) = path.substringAfterLast('/').substringBeforeLast('.')

    /** Most important freshness first: running, then queued, then anything else counts as stale. */
    fun summarise(freshness: Collection<TestState>): TestState? = when {
        freshness.isEmpty() -> null
        TestState.RUNNING in freshness -> TestState.RUNNING
        TestState.QUEUED in freshness -> TestState.QUEUED
        else -> TestState.STALE
    }

    fun formatDuration(ms: Double): String = when {
        ms < 1_000 -> "${ms.toLong()} ms"
        ms < 60_000 -> String.format(java.util.Locale.ROOT, "%.1f s", ms / 1_000)
        else -> {
            val totalSeconds = (ms / 1_000).toLong()
            String.format(java.util.Locale.ROOT, "%d m %02d s", totalSeconds / 60, totalSeconds % 60)
        }
    }

    fun worst(outcomes: Collection<TestState>): TestState? = when {
        TestState.FAILED in outcomes -> TestState.FAILED
        TestState.PASSED in outcomes -> TestState.PASSED
        TestState.SKIPPED in outcomes -> TestState.SKIPPED
        else -> null
    }

    /** Which not-yet-current state a group of listed data rows shows, most important first. */
    private val GROUP_PENDING_ORDER = listOf(TestState.RUNNING, TestState.QUEUED, TestState.STALE, TestState.DISCOVERED, TestState.EXCLUDED)
}
