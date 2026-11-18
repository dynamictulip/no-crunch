package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.TestResultDto
import com.nocrunch.rider.engine.TestState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DebugTargetTest {
    private fun node(method: String?, state: TestState, type: String? = "A.Calc", file: String? = null, line: Int? = null,
                     last: TestState? = null) = TestNode(
        TestResultDto(
            key = "p|$type|$method", project = "p", type = type, method = method, uid = "u-$method", variant = null,
            displayName = method ?: "u", state = state, durationMs = null, message = null, stackTrace = null, filePath = file, line = line,
            lastOutcome = last,
        ),
        excluded = false,
    )

    @Test
    fun a_test_can_be_debugged_by_name_or_by_location() {
        assertTrue(DebugTarget.canDebug(node("Adds", TestState.FAILED)))
        assertTrue(DebugTarget.canDebug(node(null, TestState.FAILED, type = null, file = "/r/Calc.cs", line = 3)))
        assertFalse(DebugTarget.canDebug(node(null, TestState.FAILED, type = null)))
    }

    @Test
    fun the_first_failing_test_in_order_is_chosen_including_stale_failures() {
        val tests = sequenceOf(
            node("Passes", TestState.PASSED),
            node(null, TestState.FAILED, type = null),
            node("StaleFail", TestState.STALE, last = TestState.FAILED),
            node("Fails", TestState.FAILED),
        )
        assertEquals("StaleFail", DebugTarget.firstFailing(tests)?.dto?.method)
        assertNull(DebugTarget.firstFailing(sequenceOf(node("Passes", TestState.PASSED))))
    }
}
