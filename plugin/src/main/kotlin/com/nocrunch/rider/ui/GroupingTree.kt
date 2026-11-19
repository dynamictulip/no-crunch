package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.RunStatus
import com.nocrunch.rider.view.GroupLevel
import com.nocrunch.rider.view.Grouping
import com.nocrunch.rider.view.SortMode

/**
 * The tree for one grouping: [problems] go in the pinned group titled [problemsTitle], above [rows].
 */
data class GroupedTree(val problems: List<ProjectNode>, val problemsTitle: String, val rows: List<ViewNode>)

/** Turns the model into rows for any [Grouping]. */
object GroupingTree {
    const val BUILD_ERRORS = "Build errors"
    const val PROJECTS_WITH_PROBLEMS = "Projects with problems"

    /**
     * [projects] is the unfiltered model. It is grouped before the view filters drop tests, so whether a row holds a
     * whole class or project (and so which rules ticking it writes) never depends on what the filters hide.
     */
    fun build(
        projects: List<ProjectNode>,
        grouping: Grouping,
        sort: SortMode,
        failuresOnly: Boolean = false,
        hideExcluded: Boolean = false,
    ): GroupedTree {
        val projectOnTop = grouping.levels.firstOrNull() == GroupLevel.PROJECT
        // Test projects show their own status on their rows, but only when there is a project row for every one.
        val problems =
            if (projectOnTop) projects.filter { !it.isTestProject && it.buildStatus?.isBroken == true }
            else projects.filter(::hasProblem)
        val builder = Builder(grouping, failuresOnly, hideExcluded)
        val rows = if (projectOnTop) {
            val problemPaths = problems.mapTo(HashSet()) { it.path }
            projects.filter { it.hasTests || it.path !in problemPaths }.mapNotNull { builder.topProject(it, grouping.levels.drop(1)) }
        } else {
            builder.group(projects.flatMap(builder::items), grouping.levels, parentExcluded = false)
        }
        return GroupedTree(problems, if (projectOnTop) BUILD_ERRORS else PROJECTS_WITH_PROBLEMS, TreeView.sortViews(rows, sort))
    }

    private fun hasProblem(p: ProjectNode): Boolean =
        p.buildStatus?.isBroken == true || p.run?.status == RunStatus.CRASHED || p.run?.status?.isUnsupported == true

    /** One test with everything the levels group it by, worked out once. Null values skip that level. */
    private class Item(
        val test: TestNode,
        val project: ProjectNode,
        /** Null for uid-only tests, which have no class. */
        val type: String?,
        val name: TypeName?,
        val folder: List<String>?,
        val file: String?,
        /** How many leading [TypeName.namespace] segments the project row above already says. */
        val namespaceFrom: Int,
        /** The view filters show it. */
        val kept: Boolean,
    )

    private class Builder(grouping: Grouping, private val failuresOnly: Boolean, private val hideExcluded: Boolean) {
        private val folderInProject = grouping.above(GroupLevel.PROJECT, GroupLevel.FOLDER)
        private val fileInProject = grouping.above(GroupLevel.PROJECT, GroupLevel.FILE)
        private val namespaceInProject = grouping.levels.indexOf(GroupLevel.NAMESPACE).let { it > 0 && grouping.levels[it - 1] == GroupLevel.PROJECT }
        private val namespaceAboveClass = grouping.above(GroupLevel.NAMESPACE, GroupLevel.CLASS)
        // Tests per project and per class in the unfiltered model, to tell a row holding all of one from a part.
        private val projectSizes = HashMap<String, Int>()
        private val classSizes = HashMap<String, Int>()

        fun items(p: ProjectNode): List<Item> {
            val types = p.children.mapNotNullTo(HashSet()) { c -> c.type.takeIf { it != TestTreeModel.UNKNOWN_CLASS } }
            val parser = TypeNameParser(types)
            val dir = p.path.substringBeforeLast('/', "")
            projectSizes[p.path] = p.children.sumOf { it.children.size }
            return p.children.flatMap { c ->
                val type = c.type.takeIf { it != TestTreeModel.UNKNOWN_CLASS }
                val name = type?.let(parser::parse)
                if (type != null) classSizes[classKey(p.path, type)] = c.children.size
                val namespaceFrom = if (name != null && namespaceInProject) projectNamespaceLength(name.namespace, p.name) else 0
                c.children.map { t ->
                    val relative = t.dto.filePath?.let { TreeView.relativeTo(it, dir) }
                    // Without a project row above, folders and files start at the repository root, so same-named
                    // folders in different projects stay apart.
                    fun located(inProject: Boolean) = relative?.let { if (inProject || dir.isEmpty()) it else "$dir/$it" }
                    Item(
                        t, p, type, name,
                        folder = located(folderInProject)?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }?.split('/'),
                        file = located(fileInProject),
                        namespaceFrom = namespaceFrom,
                        kept = TestTreeModel.keeps(t, failuresOnly, hideExcluded),
                    )
                }
            }
        }

        /** A project row at the top holds the whole project and is kept when empty as [TestTreeModel.filter] keeps it. */
        fun topProject(p: ProjectNode, rest: List<GroupLevel>): GroupView? {
            val keepEmpty = p.buildStatus?.isBroken == true || (!failuresOnly && !(hideExcluded && p.hasTests))
            return projectRow(p, items(p), rest, parentExcluded = false, keepEmpty)
        }

        fun group(items: List<Item>, levels: List<GroupLevel>, parentExcluded: Boolean): List<ViewNode> {
            if (levels.isEmpty()) return items.filter { it.kept }.map { TestView(it.test) }
            val rest = levels.drop(1)
            return when (levels.first()) {
                GroupLevel.PROJECT -> split(items, rest, parentExcluded, { it.project.path }) { _, members ->
                    projectRow(members.first().project, members, rest, parentExcluded)
                }
                GroupLevel.FILE -> split(items, rest, parentExcluded, { it.file }) { file, members ->
                    row("file:$file", file.substringAfterLast('/'), GroupKind.FILE, members, parentExcluded) { group(members, rest, it) }
                }
                GroupLevel.FOLDER -> nested(items, 0, rest, parentExcluded, GroupKind.FOLDER, "folder:", "/") { it.folder }
                // Keyed by the namespace as shown, so `Shop.X` and `X` under project `Shop` are one row, as they
                // always were; the project row above keeps the key unique.
                GroupLevel.NAMESPACE -> nested(items, 0, rest, parentExcluded, GroupKind.NAMESPACE, "ns:", ".") { item ->
                    item.name?.namespace?.let { it.subList(item.namespaceFrom, it.size) }
                }
                GroupLevel.CLASS -> classes(items, 0, rest, parentExcluded)
            }
        }

        /** Rows for the items that have a [value]; the rest skip this level and are grouped by the [rest]. */
        private fun split(
            items: List<Item>, rest: List<GroupLevel>, parentExcluded: Boolean,
            value: (Item) -> String?, row: (String, List<Item>) -> GroupView?,
        ): List<ViewNode> {
            val skipped = ArrayList<Item>()
            val groups = LinkedHashMap<String, MutableList<Item>>()
            for (item in items) {
                val v = value(item)
                if (v == null) skipped += item else groups.getOrPut(v) { ArrayList() } += item
            }
            return groups.mapNotNull { (v, members) -> row(v, members) } + group(skipped, rest, parentExcluded)
        }

        /**
         * One row per path segment at [depth], keyed by [keyPrefix] and the path down to it; items whose path ends here
         * sit in the row above.
         */
        private fun nested(
            items: List<Item>, depth: Int, rest: List<GroupLevel>, parentExcluded: Boolean, kind: GroupKind, keyPrefix: String,
            separator: String, segments: (Item) -> List<String>?,
        ): List<ViewNode> {
            val path = { item: Item -> segments(item)?.takeIf { it.size > depth }?.subList(0, depth + 1)?.joinToString(separator) }
            return split(items, rest, parentExcluded, path) { p, members ->
                row(keyPrefix + p, segments(members.first())!![depth], kind, members, parentExcluded) { excluded ->
                    nested(members, depth + 1, rest, excluded, kind, keyPrefix, separator, segments)
                }?.let { collapse(it, separator) }
            }
        }

        /** Class rows at nesting [depth]: outer classes at 0, the classes nested in them below. Never collapsed. */
        private fun classes(items: List<Item>, depth: Int, rest: List<GroupLevel>, parentExcluded: Boolean): List<ViewNode> {
            val hasClass = { item: Item -> item.name != null && item.name.classes.size > depth }
            // Classes keep their project in the key: the same class name in two projects is two classes.
            val siblingTypes = items.filter(hasClass).mapTo(LinkedHashSet()) { classType(it, depth) }
            val shared = if (depth == 0 && !namespaceAboveClass) TreeView.sharedNamespace(siblingTypes.toList()) else ""
            return split(items, rest, parentExcluded, { if (hasClass(it)) classKey(it.project.path, classType(it, depth)) else null }) { k, members ->
                val first = members.first()
                val type = classType(first, depth)
                val label = if (depth > 0 || namespaceAboveClass) first.name!!.classes[depth] else type.removePrefix(shared)
                val own = members.count { it.type == type }
                val whole = own > 0 && own == classSizes[classKey(first.project.path, type)]
                row("class:$k", label, GroupKind.CLASS, members, parentExcluded,
                    wholeRule = if (whole) ExclusionRuleDto(first.project.path, type) else null) { classes(members, depth + 1, rest, it) }
            }
        }

        private fun projectRow(p: ProjectNode, members: List<Item>, rest: List<GroupLevel>, parentExcluded: Boolean, keepEmpty: Boolean = false) =
            row("project:${p.path}", p.name, GroupKind.PROJECT, members, parentExcluded, project = p,
                wholeRule = if (members.size == projectSizes[p.path]) p.rule else null, keepEmpty = keepEmpty) { group(members, rest, it) }

        /** A row, or null when the filters leave nothing in it. [children] gets whether this row is wholly excluded. */
        private fun row(
            key: String, label: String, kind: GroupKind, members: List<Item>, parentExcluded: Boolean,
            project: ProjectNode? = null, wholeRule: ExclusionRuleDto? = null, keepEmpty: Boolean = false,
            children: (Boolean) -> List<ViewNode>,
        ): GroupView? {
            val kids = children(isExcluded(members))
            return if (kids.isEmpty() && !keepEmpty) null else GroupView(key, label, kind, kids, parentExcluded, project, wholeRule)
        }

        // Same rule as GroupView.excluded, worked out before the row exists so its children can be told.
        private fun isExcluded(members: List<Item>): Boolean {
            var any = false
            for (m in members) if (m.kept) {
                if (!m.test.excluded) return false
                any = true
            }
            return any
        }
    }

    /** A row whose only child is a row of the same kind, and no tests of its own, becomes one row (`Orders.Pricing`). */
    private fun collapse(row: GroupView, separator: String): GroupView {
        val only = row.children.singleOrNull() as? GroupView
        return if (only == null || only.kind != row.kind) row
        else row.copy(key = only.key, label = row.label + separator + only.label, children = only.children)
    }

    /**
     * How many leading segments of [namespace] the project's own namespace covers, assumed to match its [projectName]
     * as it does by default: all of them for classes in it, none for other namespaces.
     */
    private fun projectNamespaceLength(namespace: List<String>, projectName: String): Int {
        val joined = namespace.joinToString(".")
        return when {
            joined == projectName -> namespace.size
            joined.startsWith("$projectName.") -> projectName.count { it == '.' } + 1
            else -> 0
        }
    }

    private fun classKey(project: String, type: String) = "$project|$type"

    /** The type name of the class at nesting [depth], as reported (`Ns.Outer`, `Ns.Outer+Inner`). */
    private fun classType(item: Item, depth: Int): String {
        val type = item.type!!
        var segments = item.name!!.namespace.size + depth + 1
        for (i in type.indices) if ((type[i] == '.' || type[i] == '+') && --segments == 0) return type.substring(0, i)
        return type
    }
}
