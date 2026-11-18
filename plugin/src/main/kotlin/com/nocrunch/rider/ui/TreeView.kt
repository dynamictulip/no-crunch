package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.ExclusionRuleDto

/** How tests are grouped under each project in the tool window. */
enum class ViewMode(val title: String) {
    CLASSES("Classes"),
    NAMESPACES("Namespaces"),
    FILES("Folders and files"),
}

enum class GroupKind { NAMESPACE, FOLDER, FILE }

/** Order of rows at every level of the tree. */
enum class SortMode(val title: String) {
    NAME("Name"),
    STATUS("Status"),
    DURATION("Run time"),
}

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
    val shownOutcome: String? get() = totals.shownOutcome
    val freshness: String? get() = totals.freshness
    val isFresh: Boolean get() = freshness == null
}

data class TestView(val node: TestNode) : ViewNode {
    override val label get() = node.label
    override val leaves = listOf(node)
    override val totals = Totals.of(leaves)
    override val rules get() = listOf(TestTreeModel.ruleFor(node.dto))
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

data class GroupView(
    override val label: String,
    val kind: GroupKind,
    val children: List<ViewNode>,
    override val parentExcluded: Boolean,
) : ViewNode {
    override val locked get() = false
    override val leaves = children.flatMap { it.leaves }
    override val totals = Totals.of(leaves)
    override val rules get() = children.flatMap { it.rules }.distinct()
    override val excluded = children.isNotEmpty() && children.all { it.excluded }
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
        projects.partition { !it.isTestProject && it.buildStatus in setOf("failed", "blocked") }

    private fun buildRank(status: String?): Int = when (status) {
        "failed" -> 0
        "blocked" -> 1
        else -> 2
    }

    fun sortTests(tests: List<TestView>, sort: SortMode): List<TestView> =
        tests.sortedWith(comparator(sort, { it.label }, { it.leaves }, { it.node.countedDurationMs }))

    /** Sorts rows, and recursively the rows inside groups and classes. */
    private fun sortViews(nodes: List<ViewNode>, sort: SortMode): List<ViewNode> =
        nodes.map {
            when (it) {
                is GroupView -> it.copy(children = sortViews(it.children, sort))
                is ClassView -> it.copy(sort = sort)
                is TestView -> it
            }
        }.sortedWith(
            // Like a file explorer, name order keeps folders first and "(no file)" last.
            if (sort == SortMode.NAME) compareBy<ViewNode> { kindRank(it) }.then(comparator(sort, { it.label }, { it.leaves }, { it.durationMs }))
            else comparator(sort, { it.label }, { it.leaves }, { it.durationMs })
        )

    private fun kindRank(v: ViewNode): Int = when {
        v is GroupView && v.label == NO_FILE -> 2
        v is GroupView && v.kind == GroupKind.FOLDER -> 0
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
        t.shownOutcome == "failed" -> if (t.freshness == null) 0 else 1
        t.shownOutcome == null -> 2
        t.shownOutcome == "passed" -> if (t.freshness == null) 4 else 3
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
            GroupView(ns, GroupKind.NAMESPACE, classes.map { ClassView(it, it.type.substringAfterLast('.')) }, project.excluded)
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
            rel.substringAfterLast('/'), GroupKind.FILE,
            members.sortedWith(compareBy({ it.dto.method ?: it.dto.displayName }, { it.dto.displayName })).map(::TestView),
            project.excluded,
        )

        val folders = withFile.keys.filter { it.contains('/') }.groupBy { it.substringBeforeLast('/') }.toSortedMap().map { (dir, files) ->
            GroupView(dir, GroupKind.FOLDER, files.sorted().map { fileGroup(it, withFile.getValue(it)) }, project.excluded)
        }
        val rootFiles = withFile.keys.filterNot { it.contains('/') }.sorted().map { fileGroup(it, withFile.getValue(it)) }
        val unknown = if (noFile.isEmpty()) emptyList() else listOf(GroupView(NO_FILE, GroupKind.FILE, noFile.map(::TestView), project.excluded))
        return folders + rootFiles + unknown
    }

    /** Path of [file] inside the project's folder, or just the file name if it lives elsewhere. */
    private fun relativeTo(file: String, projectDir: String): String {
        val marker = "/$projectDir/"
        val idx = if (projectDir.isEmpty()) -1 else file.indexOf(marker)
        return if (idx >= 0) file.substring(idx + marker.length) else file.substringAfterLast('/')
    }
}
