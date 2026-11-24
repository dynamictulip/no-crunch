package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.CoverageStatusDto

/** What to tell the user when line coverage can't be collected. */
object CoverageNotice {
    const val INSTALL_COMMAND = "dotnet tool install --global dotnet-coverage"

    /** The text to show for [status], or null when coverage is available (or the engine hasn't said yet). */
    fun text(status: CoverageStatusDto?): String? =
        if (status == null || status.available) null
        else status.message?.takeIf { it.isNotBlank() } ?: "Line coverage is off: dotnet-coverage was not found. Install it with: $INSTALL_COMMAND"
}
