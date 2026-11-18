package com.nocrunch.rider

import com.nocrunch.rider.engine.BuildFinishedDto
import com.nocrunch.rider.engine.BuildStatus

/** A project's latest build this session: in progress ([BuildStatus.BUILDING]) or how it finished. */
data class ProjectBuild(
    val project: String,
    val status: BuildStatus,
    val output: String = "",
    /** The project whose broken build stops this one. */
    val blockedBy: String? = null,
) {
    companion object {
        fun started(project: String) = ProjectBuild(project, BuildStatus.BUILDING)

        fun finished(dto: BuildFinishedDto) = ProjectBuild(dto.project, dto.status, dto.output, dto.blockedBy)
    }
}
