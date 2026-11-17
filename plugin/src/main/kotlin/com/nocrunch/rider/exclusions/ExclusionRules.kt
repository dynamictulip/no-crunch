package com.nocrunch.rider.exclusions

import com.nocrunch.rider.engine.ExclusionRuleDto

/**
 * Exclusion rules, matched the same way as the engine's ExclusionSet: a rule covers a project, a class, or one test,
 * and the most specific rule that covers something decides whether it's excluded. Include rules are exceptions
 * that bring part of an excluded project or class back.
 */
object ExclusionRules {
    /** 0 = project, 1 = class, 2 = single test. */
    private fun specificity(r: ExclusionRuleDto) = if (r.uid != null || r.method != null) 2 else if (r.type != null) 1 else 0

    /** True if [rule] applies to everything [target] names (a project, a class, or one test). */
    fun covers(rule: ExclusionRuleDto, target: ExclusionRuleDto): Boolean = rule.project == target.project && when {
        rule.uid != null -> rule.uid == target.uid
        rule.type == null -> true
        rule.type != target.type -> false
        else -> rule.method == null || rule.method == target.method
    }

    /** Most specific covering rule wins; if two equally specific rules disagree, exclusion wins. */
    fun isExcluded(target: ExclusionRuleDto, rules: List<ExclusionRuleDto>): Boolean {
        var best: ExclusionRuleDto? = null
        for (r in rules) {
            if (!covers(r, target)) continue
            val b = best
            if (b == null || specificity(r) > specificity(b) || (specificity(r) == specificity(b) && !r.include)) best = r
        }
        return best != null && !best.include
    }

    /**
     * Includes or excludes [target]. Every rule at or below it is cleared, so everything underneath follows it again;
     * then a rule is added only if [target] would otherwise inherit the opposite from a broader rule.
     */
    fun toggle(rules: List<ExclusionRuleDto>, target: ExclusionRuleDto, excluded: Boolean): List<ExclusionRuleDto> {
        val plain = target.copy(include = false)
        val remaining = rules.filterNot { covers(plain, it.copy(include = false)) }
        return if (isExcluded(plain, remaining) == excluded) remaining else remaining + plain.copy(include = !excluded)
    }
}
