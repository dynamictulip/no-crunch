package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.BuildFinishedDto
import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.RunFinishedDto
import com.nocrunch.rider.engine.TestResultDto

data class Counts(val passed: Int = 0, val failed: Int = 0, val other: Int = 0) {
    operator fun plus(o: Counts) = Counts(passed + o.passed, failed + o.failed, other + o.other)
}

data class TestNode(val dto: TestResultDto, val excluded: Boolean) {
    val counts: Counts
        get() = when (dto.state) {
            "passed" -> Counts(passed = 1)
            "failed" -> Counts(failed = 1)
            else -> Counts(other = 1)
        }
}

data class ClassNode(val project: String, val type: String, val excluded: Boolean, val children: List<TestNode>) {
    val counts: Counts get() = children.fold(Counts()) { acc, t -> acc + t.counts }
    val rule: ExclusionRuleDto get() = ExclusionRuleDto(project, type.takeIf { it != TestTreeModel.UNKNOWN_CLASS })
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
                    val classExcluded = projectExcluded ||
                        rules.any { it.project == path && it.type == type && it.method == null && it.uid == null }
                    val nodes = members
                        .sortedWith(compareBy({ it.method ?: it.displayName }, { it.displayName }))
                        .map { TestNode(it, classExcluded || isExcluded(it, rules)) }
                        .filter { !failuresOnly || it.dto.state == "failed" }
                    ClassNode(path, type, classExcluded, nodes)
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
