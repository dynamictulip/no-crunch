package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.BuildFinishedDto
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.RunFinishedDto
import com.nocrunch.rider.engine.TestResultDto

/** Pending = queued, running, stale or just discovered. Skipped tests, and excluded tests that haven't been run by hand, aren't counted. */
data class Counts(val passed: Int = 0, val failed: Int = 0, val pending: Int = 0) {
    operator fun plus(o: Counts) = Counts(passed + o.passed, failed + o.failed, pending + o.pending)

    fun label() = "✔ $passed  ✖ $failed  ○ $pending"
}

/** [locked]: excluded by an ancestor's rule, so it can't be toggled on its own. */
data class TestNode(val dto: TestResultDto, val excluded: Boolean, val locked: Boolean = false) {
    /** The result comes from a run the user asked for, not from continuous testing. */
    val manual: Boolean get() = dto.runKind == "manual"

    val counts: Counts
        get() = when {
            excluded && !manual -> Counts()
            dto.state == "passed" -> Counts(passed = 1)
            dto.state == "failed" -> Counts(failed = 1)
            dto.state in PENDING_STATES -> Counts(pending = 1)
            else -> Counts()
        }

    private companion object {
        val PENDING_STATES = setOf("queued", "running", "stale", "discovered")
    }
}

data class ClassNode(val project: String, val type: String, val excluded: Boolean, val children: List<TestNode>, val locked: Boolean = false) {
    val counts: Counts get() = children.fold(Counts()) { acc, t -> acc + t.counts }
    val manualCount: Int get() = children.count { it.manual }

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
    val rule: ExclusionRuleDto get() = ExclusionRuleDto(path)
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
    ): List<ProjectNode> {
        val byProject = tests.groupBy { it.project }
        val brokenBuilds = builds.values.filter { it.status in setOf("failed", "blocked") }.map { it.project }
        val unsupported = runs.values.filter { it.status.startsWith("unsupported") }.map { it.project }
        val projectPaths = (byProject.keys + brokenBuilds + unsupported).toSortedSet()

        return projectPaths.mapNotNull { path ->
            val projectExcluded = rules.any { it.project == path && it.type == null && it.method == null && it.uid == null }
            val classes = byProject[path].orEmpty()
                .groupBy { it.type ?: UNKNOWN_CLASS }
                .toSortedMap()
                .map { (type, members) ->
                    val ownClassRule = type != UNKNOWN_CLASS &&
                        rules.any { it.project == path && it.type == type && it.method == null && it.uid == null }
                    val classExcluded = projectExcluded || ownClassRule ||
                        (type == UNKNOWN_CLASS && members.isNotEmpty() && members.all { isExcluded(it, rules) })
                    val nodes = members
                        .sortedWith(compareBy({ it.method ?: it.displayName }, { it.displayName }))
                        .map { TestNode(it, projectExcluded || ownClassRule || isExcluded(it, rules), locked = projectExcluded || ownClassRule) }
                        .filter { !failuresOnly || it.dto.state == "failed" }
                    ClassNode(path, type, classExcluded, nodes, locked = projectExcluded)
                }
                .filter { !failuresOnly || it.children.isNotEmpty() }
            val build = builds[path]
            val buildBroken = build?.status in setOf("failed", "blocked")
            if (failuresOnly && classes.isEmpty() && !buildBroken) return@mapNotNull null
            ProjectNode(path, path.substringAfterLast('/').substringBeforeLast('.'), build?.status, build, runs[path], projectExcluded, classes)
        }.sortedBy { it.name }
    }

    /** Same matching as the engine's ExclusionSet. */
    fun isExcluded(t: TestResultDto, rules: List<ExclusionRuleDto>): Boolean = rules.any { r ->
        r.project == t.project && when {
            r.uid != null -> r.uid == t.uid
            r.type == null -> true
            r.type != t.type -> false
            else -> r.method == null || r.method == t.method
        }
    }

    fun ruleFor(t: TestResultDto): ExclusionRuleDto =
        if (t.type != null && t.method != null) ExclusionRuleDto(t.project, t.type, t.method) else ExclusionRuleDto(t.project, uid = t.uid)
}
