package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.BuildFinishedDto
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.RunFinishedDto
import com.nocrunch.rider.engine.TestResultDto
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

    /** Run time that counts towards parents: excluded tests only count once run by hand. */
    val countedDurationMs: Double? get() = if (excluded && !manual) null else dto.durationMs

    /** The result comes from a run the user asked for, not from continuous testing. */
    val manual: Boolean get() = dto.runKind == "manual"

    /** Outcome to show: the current one, or the last one while the test is stale/queued/running. */
    val shownOutcome: String? get() = if (dto.state in OUTCOMES) dto.state else dto.lastOutcome

    /** Null when [shownOutcome] is current; otherwise why it isn't (stale, queued, running, ...). */
    val freshness: String? get() = when {
        dto.state in OUTCOMES -> null
        // Excluded tests have no result this session until you run them by hand.
        dto.state == "excluded" -> "stale"
        else -> dto.state
    }

    val counts: Counts
        get() = when {
            rows.isNotEmpty() -> rows.fold(Counts()) { acc, r -> acc + r.counts }
            excluded && !manual -> Counts()
            dto.state == "passed" -> Counts(passed = 1)
            dto.state == "failed" -> Counts(failed = 1)
            dto.state in PENDING_STATES -> Counts(pending = 1)
            else -> Counts()
        }

    private companion object {
        val PENDING_STATES = setOf("queued", "running", "stale", "discovered")
        val OUTCOMES = setOf("passed", "failed", "skipped")
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
    val counts: Counts get() = children.fold(Counts()) { acc, t -> acc + t.counts }
    val manualCount: Int get() = children.count { it.manual }
    /** Total run time of the tests below, or null if none has run. */
    val durationMs: Double? get() = children.mapNotNull { it.countedDurationMs }.takeIf { it.isNotEmpty() }?.sum()
    val shownOutcome: String? get() = TestTreeModel.worst(children.mapNotNull { it.shownOutcome })
    /** Why some results below aren't current: running, queued or stale; null when everything is current. */
    val freshness: String? get() = TestTreeModel.summarise(children.filterNot { it.excluded && !it.manual }.mapNotNull { it.freshness })
    /** Every shown outcome is current (nothing stale, queued or running). */
    val isFresh: Boolean get() = freshness == null

    /** Rules that toggling this node adds or removes. Tests without a class are excluded one by one (by UID), never the whole project. */
    val rules: List<ExclusionRuleDto>
        get() = if (type == TestTreeModel.UNKNOWN_CLASS) children.map { TestTreeModel.ruleFor(it.dto) } else listOf(ExclusionRuleDto(project, type))
}

data class ProjectNode(
    val path: String,
    val name: String,
    /** building, succeeded, failed, blocked, cancelled, or null if not built this session. */
    val buildStatus: String?,
    val build: BuildFinishedDto?,
    val run: RunFinishedDto?,
    val excluded: Boolean,
    val children: List<ClassNode>,
) {
    val counts: Counts get() = children.fold(Counts()) { acc, c -> acc + c.counts }
    val manualCount: Int get() = children.sumOf { it.manualCount }
    val durationMs: Double? get() = children.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum()
    val shownOutcome: String? get() = TestTreeModel.worst(children.mapNotNull { it.shownOutcome })
    val freshness: String? get() = TestTreeModel.summarise(children.mapNotNull { it.freshness })
    val isFresh: Boolean get() = freshness == null
    val rule: ExclusionRuleDto get() = ExclusionRuleDto(path)
    /** False for library/app projects that only appear because their build broke: no tests found and never run. */
    val isTestProject: Boolean get() = children.isNotEmpty() || run != null
    /** Short name of the project whose broken build stops this one, if any. */
    val blockedByName: String? get() = build?.blockedBy?.let(TestTreeModel::shortName)
}

/** Pure function from engine state to the tree shown in the tool window. */
object TestTreeModel {
    const val UNKNOWN_CLASS = "(no class)"

    fun build(
        tests: Collection<TestResultDto>,
        builds: Map<String, BuildFinishedDto>,
        rules: List<ExclusionRuleDto>,
        failuresOnly: Boolean,
        runs: Map<String, RunFinishedDto> = emptyMap(),
        hideExcluded: Boolean = false,
        /** Parameter names of a test's method, read from its source; used to label data rows that only show values. */
        paramNames: (TestResultDto) -> List<String>? = { null },
    ): List<ProjectNode> {
        val byProject = tests.groupBy { it.project }
        val brokenBuilds = builds.values.filter { it.status in setOf("failed", "blocked") }.map { it.project }
        val unsupported = runs.values.filter { it.status.startsWith("unsupported") }.map { it.project }
        val projectPaths = (byProject.keys + brokenBuilds + unsupported).toSortedSet()

        return projectPaths.mapNotNull { path ->
            val projectExcluded = ExclusionRules.isExcluded(ExclusionRuleDto(path), rules)
            val classes = byProject[path].orEmpty()
                .groupBy { it.type ?: UNKNOWN_CLASS }
                .toSortedMap()
                .map { (type, members) ->
                    // Tests without a class have no class rule: that row is excluded when all of them are.
                    val classExcluded = if (type == UNKNOWN_CLASS) members.isNotEmpty() && members.all { isExcluded(it, rules) }
                    else ExclusionRules.isExcluded(ExclusionRuleDto(path, type), rules)
                    // Data rows of theories whose rows were only known at run time nest under their theory.
                    val keys = members.map { it.key }.toSet()
                    val (rowDtos, topLevel) = members.partition { it.theoryKey != null && it.theoryKey in keys }
                    val rowsByTheory = rowDtos.groupBy { it.theoryKey }
                    fun node(d: TestResultDto, isRow: Boolean = false) =
                        TestNode(d, isExcluded(d, rules), locked = isRow, isRow = isRow, parentExcluded = classExcluded)
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
                        .filter { !failuresOnly || it.dto.state == "failed" }
                        .filter { !hideExcluded || !it.excluded }
                    ClassNode(path, type, classExcluded, nodes, parentExcluded = projectExcluded)
                }
                .filter { !failuresOnly || it.children.isNotEmpty() }
                .filter { !hideExcluded || it.children.isNotEmpty() }
            val build = builds[path]
            val buildBroken = build?.status in setOf("failed", "blocked")
            if (failuresOnly && classes.isEmpty() && !buildBroken) return@mapNotNull null
            if (hideExcluded && classes.isEmpty() && byProject[path].orEmpty().isNotEmpty() && !buildBroken) return@mapNotNull null
            ProjectNode(path, shortName(path), build?.status, build, runs[path], projectExcluded, classes)
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
        val pending = listOf("running", "queued", "stale", "discovered", "excluded").firstOrNull { s -> dtos.any { it.state == s } }
        val dto = first.copy(
            key = "${first.project}|${first.type}|${first.method}|(rows)",
            variant = null,
            displayName = members.first().label.substringBefore('(').trim(),
            state = if (dtos.any { it.state == "failed" }) "failed" else pending ?: outcome ?: first.state,
            durationMs = dtos.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum(),
            message = null,
            stackTrace = null,
            runKind = if (dtos.any { it.runKind == "manual" }) "manual" else "auto",
            lastOutcome = outcome,
            theoryKey = null,
        )
        val rows = members.map { it.copy(locked = true, isRow = true) }
        return TestNode(dto, isExcluded(dto, rules), rows = rows, parentExcluded = classExcluded, grouped = true)
    }

    fun shortName(path: String) = path.substringAfterLast('/').substringBeforeLast('.')

    /** Most important freshness first: running, then queued, then anything else counts as stale. */
    fun summarise(freshness: Collection<String>): String? = when {
        freshness.isEmpty() -> null
        "running" in freshness -> "running"
        "queued" in freshness -> "queued"
        else -> "stale"
    }

    fun formatDuration(ms: Double): String = when {
        ms < 1_000 -> "${ms.toLong()} ms"
        ms < 60_000 -> String.format(java.util.Locale.ROOT, "%.1f s", ms / 1_000)
        else -> {
            val totalSeconds = (ms / 1_000).toLong()
            String.format(java.util.Locale.ROOT, "%d m %02d s", totalSeconds / 60, totalSeconds % 60)
        }
    }

    fun worst(outcomes: Collection<String>): String? = when {
        "failed" in outcomes -> "failed"
        "passed" in outcomes -> "passed"
        "skipped" in outcomes -> "skipped"
        else -> null
    }

    /** Keys of every known test that is currently excluded. */
    fun excludedKeys(tests: Collection<TestResultDto>, rules: List<ExclusionRuleDto>): List<String> =
        tests.filter { isExcluded(it, rules) }.map { it.key }

    /** Same matching as the engine's ExclusionSet: the most specific rule wins, so include rules can override. */
    fun isExcluded(t: TestResultDto, rules: List<ExclusionRuleDto>): Boolean =
        ExclusionRules.isExcluded(ExclusionRuleDto(t.project, t.type, t.method, t.uid), rules)

    fun ruleFor(t: TestResultDto): ExclusionRuleDto =
        if (t.type != null && t.method != null) ExclusionRuleDto(t.project, t.type, t.method) else ExclusionRuleDto(t.project, uid = t.uid)
}
