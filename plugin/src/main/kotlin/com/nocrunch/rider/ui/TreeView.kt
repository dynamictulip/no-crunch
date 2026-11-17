package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.ExclusionRuleDto

/** How tests are grouped under each project in the tool window. */
enum class ViewMode(val title: String) {
    CLASSES("Classes"),
    NAMESPACES("Namespaces"),
    FILES("Folders and files"),
}

enum class GroupKind { NAMESPACE, FOLDER, FILE }

/** A row below a project. Aggregates (counts, run time, freshness) are computed from the tests underneath. */
sealed interface ViewNode {
    val label: String
    val leaves: List<TestNode>
    /** Exclusion rules that toggling this row's checkbox adds or removes. */
    val rules: List<ExclusionRuleDto>
    val excluded: Boolean
    val locked: Boolean

    val counts: Counts get() = leaves.fold(Counts()) { acc, t -> acc + t.counts }
    val durationMs: Double? get() = leaves.mapNotNull { it.countedDurationMs }.takeIf { it.isNotEmpty() }?.sum()
    val manualCount: Int get() = leaves.count { it.manual }
    val shownOutcome: String? get() = TestTreeModel.worst(leaves.mapNotNull { it.shownOutcome })
    val freshness: String? get() = TestTreeModel.summarise(leaves.filterNot { it.excluded && !it.manual }.mapNotNull { it.freshness })
    val isFresh: Boolean get() = freshness == null
}

data class TestView(val node: TestNode) : ViewNode {
    override val label get() = node.dto.displayName
    override val leaves get() = listOf(node)
    override val rules get() = listOf(TestTreeModel.ruleFor(node.dto))
    override val excluded get() = node.excluded
    override val locked get() = node.locked
}

data class ClassView(val node: ClassNode, override val label: String) : ViewNode {
    val children: List<TestView> get() = node.children.map(::TestView)
    override val leaves get() = node.children
    override val rules get() = node.rules
    override val excluded get() = node.excluded
    override val locked get() = node.locked
}

data class GroupView(
    override val label: String,
    val kind: GroupKind,
    val children: List<ViewNode>,
    override val locked: Boolean,
) : ViewNode {
    override val leaves get() = children.flatMap { it.leaves }
    override val rules get() = children.flatMap { it.rules }.distinct()
    override val excluded get() = children.isNotEmpty() && children.all { it.excluded }
}

object TreeView {
    const val NO_FILE = "(no file)"

    fun of(project: ProjectNode, mode: ViewMode): List<ViewNode> = when (mode) {
        ViewMode.CLASSES -> project.children.map { ClassView(it, it.type) }
        ViewMode.NAMESPACES -> byNamespace(project)
        ViewMode.FILES -> byFile(project)
    }

    private fun byNamespace(project: ProjectNode): List<ViewNode> {
        val (withNs, without) = project.children.partition { it.type.contains('.') && it.type != TestTreeModel.UNKNOWN_CLASS }
        val groups = withNs.groupBy { it.type.substringBeforeLast('.') }.toSortedMap().map { (ns, classes) ->
            GroupView(ns, GroupKind.NAMESPACE, classes.map { ClassView(it, it.type.substringAfterLast('.')) }, project.excluded)
        }
        return groups + without.map { ClassView(it, it.type) }
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
