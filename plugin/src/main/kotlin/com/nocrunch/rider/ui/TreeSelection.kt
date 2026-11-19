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
    fun exclusionTargets(selected: List<Any?>): List<Pair<Boolean, List<ExclusionRuleDto>>> = selected.flatMap { o ->
        when (o) {
            is ProjectNode -> if (o.isTestProject) listOf(o.excluded to listOf(o.rule)) else emptyList()
            // A partly excluded group is both: it can be included (everything) or excluded (everything).
            is GroupView -> when {
                !toggleable(o) -> emptyList()
                o.partlyExcluded -> listOf(true to o.rules, false to o.rules)
                else -> listOf(RowPresentation.groupExcluded(o) to o.rules)
            }
            is ViewNode -> if (o.locked) emptyList() else listOf(o.excluded to o.rules)
            is TestNode -> if (o.locked) emptyList() else listOf(o.excluded to listOf(TestExclusions.ruleFor(o.dto)))
            else -> emptyList()
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
