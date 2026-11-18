package com.nocrunch.rider.exclusions

import com.nocrunch.rider.engine.ExclusionRuleDto
import com.nocrunch.rider.engine.TestResultDto

/** [ExclusionRules] applied to the tests the engine reports. */
object TestExclusions {
    /** Same matching as the engine's ExclusionSet: the most specific rule wins, so include rules can override. */
    fun isExcluded(t: TestResultDto, rules: List<ExclusionRuleDto>): Boolean =
        ExclusionRules.isExcluded(ExclusionRuleDto(t.project, t.type, t.method, t.uid), rules)

    /** Keys of every known test that is currently excluded. */
    fun excludedKeys(tests: Collection<TestResultDto>, rules: List<ExclusionRuleDto>): List<String> =
        tests.filter { isExcluded(it, rules) }.map { it.key }

    /** The rule that includes or excludes just this test: by class and method, or by UID when it has no class. */
    fun ruleFor(t: TestResultDto): ExclusionRuleDto =
        if (t.type != null && t.method != null) ExclusionRuleDto(t.project, t.type, t.method) else ExclusionRuleDto(t.project, uid = t.uid)
}
