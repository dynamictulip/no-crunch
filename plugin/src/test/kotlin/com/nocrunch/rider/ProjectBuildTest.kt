package com.nocrunch.rider

import com.nocrunch.rider.engine.BuildFinishedDto
import com.nocrunch.rider.engine.BuildStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProjectBuildTest {
    @Test
    fun a_started_build_is_building_with_no_output() {
        assertEquals(ProjectBuild("src/Core/Core.csproj", BuildStatus.BUILDING, "", null), ProjectBuild.started("src/Core/Core.csproj"))
    }

    @Test
    fun a_finished_build_keeps_what_the_engine_reported() {
        val dto = BuildFinishedDto("tests/Unit/Unit.csproj", BuildStatus.BLOCKED, "out", "src/Core/Core.csproj")
        assertEquals(ProjectBuild("tests/Unit/Unit.csproj", BuildStatus.BLOCKED, "out", "src/Core/Core.csproj"), ProjectBuild.finished(dto))
    }
}
