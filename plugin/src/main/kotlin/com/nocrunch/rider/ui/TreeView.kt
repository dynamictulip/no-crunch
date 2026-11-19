package com.nocrunch.rider.ui

import com.nocrunch.rider.view.SortMode
import com.nocrunch.rider.view.ViewMode
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

data class ClassView(val node: ClassNode, override val label: String, val sort: SortMode = SortMode.NAME) : ViewNode {
    val children: List<TestView> by lazy { TreeView.sortTests(node.children.map(::TestView), sort) }
    override val leaves get() = node.children
    override val totals get() = node.totals
    override val rules get() = node.rules
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

    fun of(project: ProjectNode, mode: ViewMode, sort: SortMode = SortMode.NAME): List<ViewNode> {
        val nodes = when (mode) {
            ViewMode.CLASSES -> {
                val prefix = sharedNamespace(project.children.map { it.type })
                project.children.map { ClassView(it, it.type.removePrefix(prefix)) }
            }
            ViewMode.NAMESPACES -> byNamespace(project)
            ViewMode.FILES -> byFile(project)
        }
        return sortViews(nodes, sort)
    }

    fun sortProjects(projects: List<ProjectNode>, sort: SortMode): List<ProjectNode> {
        val byTests = comparator<ProjectNode>(sort, { it.name }, { p -> p.children.flatMap { it.children } }, { it.durationMs })
        // Broken builds outrank any test result: failed builds first, then projects blocked by them.
        return if (sort == SortMode.STATUS) projects.sortedWith(compareBy<ProjectNode> { buildRank(it.buildStatus) }.then(byTests))
        else projects.sortedWith(byTests)
    }

    /** Splits off non-test projects whose build failed: they go in the pinned "Build errors" section, not among test projects. */
    fun splitBuildErrors(projects: List<ProjectNode>): Pair<List<ProjectNode>, List<ProjectNode>> =
        projects.partition { !it.isTestProject && it.buildStatus?.isBroken == true }

    internal fun buildRank(status: BuildStatus?): Int = when (status) {
        BuildStatus.FAILED -> 0
        BuildStatus.BLOCKED -> 1
        else -> 2
    }

    fun sortTests(tests: List<TestView>, sort: SortMode): List<TestView> =
        tests.sortedWith(comparator(sort, { it.label }, { it.leaves }, { it.node.countedDurationMs }))

    /** Sorts rows, and recursively the rows inside groups and classes. */
    internal fun sortViews(nodes: List<ViewNode>, sort: SortMode): List<ViewNode> =
        nodes.map {
            when (it) {
                is GroupView -> it.copy(children = sortViews(it.children, sort))
                is ClassView -> it.copy(sort = sort)
                is TestView -> it
            }
        }.sortedWith(
            // Like a file explorer, name order keeps folders first, and "(no file)" and tests that skip a level last.
            // Broken builds outrank any test result, as in sortProjects.
            when (sort) {
                SortMode.NAME -> compareBy<ViewNode> { kindRank(it) }.then(comparator(sort, { it.label }, { it.leaves }, { it.durationMs }))
                SortMode.STATUS -> compareBy<ViewNode> { buildRank((it as? GroupView)?.project?.buildStatus) }
                    .then(comparator(sort, { it.label }, { it.leaves }, { it.durationMs }))
                SortMode.DURATION -> comparator(sort, { it.label }, { it.leaves }, { it.durationMs })
            }
        )

    private fun kindRank(v: ViewNode): Int = when {
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

    /**
     * Namespaces are shown relative to the project's own (assumed to match the project name, as it does by default):
     * classes in it sit directly under the project, and `Shop.Tests.Pricing` shows as `Pricing`. Other namespaces keep
     * their full name.
     */
    private fun byNamespace(project: ProjectNode): List<ViewNode> {
        val root = project.name
        fun relative(ns: String) = if (ns == root) "" else ns.removePrefix("$root.")
        val (withNs, without) = project.children.partition { it.type.contains('.') && it.type != TestTreeModel.UNKNOWN_CLASS }
        val byNs = withNs.groupBy { relative(it.type.substringBeforeLast('.')) }
        val groups = byNs.filterKeys { it.isNotEmpty() }.toSortedMap().map { (ns, classes) ->
            GroupView("namespace:$ns", ns, GroupKind.NAMESPACE, classes.map { ClassView(it, it.type.substringAfterLast('.')) }, project.excluded)
        }
        val atRoot = byNs[""].orEmpty().map { ClassView(it, it.type.substringAfterLast('.')) }
        return groups + atRoot + without.map { ClassView(it, it.type) }
    }

    private fun byFile(project: ProjectNode): List<ViewNode> {
        val projectDir = project.path.substringBeforeLast('/', "")
        val tests = project.children.flatMap { it.children }
        val byRelative = tests.groupBy { t -> t.dto.filePath?.let { relativeTo(it, projectDir) } }
        val noFile = byRelative[null].orEmpty()
        val withFile = byRelative.filterKeys { it != null }.mapKeys { it.key!! }

        fun fileGroup(rel: String, members: List<TestNode>) = GroupView(
            "file:$rel", rel.substringAfterLast('/'), GroupKind.FILE,
            members.sortedWith(compareBy({ it.dto.method ?: it.dto.displayName }, { it.dto.displayName })).map(::TestView),
            project.excluded,
        )

        val folders = withFile.keys.filter { it.contains('/') }.groupBy { it.substringBeforeLast('/') }.toSortedMap().map { (dir, files) ->
            GroupView("folder:$dir", dir, GroupKind.FOLDER, files.sorted().map { fileGroup(it, withFile.getValue(it)) }, project.excluded)
        }
        val rootFiles = withFile.keys.filterNot { it.contains('/') }.sorted().map { fileGroup(it, withFile.getValue(it)) }
        val unknown = if (noFile.isEmpty()) emptyList() else listOf(GroupView("file:$NO_FILE", NO_FILE, GroupKind.FILE, noFile.map(::TestView), project.excluded))
        return folders + rootFiles + unknown
    }

    /** Path of [file] inside the project's folder, or just the file name if it lives elsewhere. */
    internal fun relativeTo(file: String, projectDir: String): String {
        val marker = "/$projectDir/"
        val idx = if (projectDir.isEmpty()) -1 else file.indexOf(marker)
        return if (idx >= 0) file.substring(idx + marker.length) else file.substringAfterLast('/')
    }
}
