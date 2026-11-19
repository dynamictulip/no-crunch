package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.exclusions.TestExclusions

/** What the selected rows of the test tree (their row objects) mean for the run and include/exclude actions. */
object TreeSelection {
    /** Keys to ask the engine to run for the selected rows. */
    fun runKeys(selected: List<Any?>): List<String> = selected.flatMap { o ->
        when (o) {
            is TestNode -> o.runKeys
            is ViewNode -> o.leaves.flatMap { it.runKeys }
            is ProjectNode -> o.children.flatMap { c -> c.children.flatMap { it.runKeys } }
            else -> emptyList()
        }
    }.distinct()

    /** Selected rows that can be included/excluded on their own (everything except theory data rows): excluded now, and their rules. */
    fun exclusionTargets(selected: List<Any?>): List<Pair<Boolean, List<ExclusionRuleDto>>> = selected.mapNotNull { o ->
        when (o) {
            is ProjectNode -> if (o.isTestProject) o.excluded to listOf(o.rule) else null
            is GroupView -> if (!toggleable(o)) null else RowPresentation.groupExcluded(o) to o.rules
            is ViewNode -> if (o.locked) null else o.excluded to o.rules
            is TestNode -> if (o.locked) null else o.excluded to listOf(TestExclusions.ruleFor(o.dto))
            else -> null
        }
    }

    /** Rules that ticking or unticking a row's checkbox changes; null for rows without one. */
    fun checkboxRules(o: Any?): List<ExclusionRuleDto>? = when (o) {
        is ProjectNode -> listOf(o.rule)
        is GroupView -> if (!toggleable(o)) null else o.rules
        is ViewNode -> o.rules
        is TestNode -> listOf(TestExclusions.ruleFor(o.dto))
        else -> null
    }

    /** False for a project row of a project with no tests, which has nothing to include or exclude (as ProjectNode rows). */
    private fun toggleable(o: GroupView): Boolean = o.project?.isTestProject != false
}
