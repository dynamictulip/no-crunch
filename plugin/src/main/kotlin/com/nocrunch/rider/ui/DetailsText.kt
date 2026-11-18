package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.BuildStatus
import com.nocrunch.rider.engine.TestState

/** Normal text, red for the failure words only, or muted for side notes. */
enum class DetailStyle { NORMAL, RED, MUTED }

data class DetailPart(val text: String, val style: DetailStyle)

/** What the details pane shows for the selected row. No Swing here. */
object DetailsText {
    /** Null when there's nothing to show: the pane is cleared. */
    fun of(o: Any?): List<DetailPart>? = when (o) {
        is TestNode -> build { test(o) }
        is ProjectNode -> build { project(o) }
        is ViewNode -> build { view(o) }
        else -> null
    }

    private fun build(f: Builder.() -> Unit): List<DetailPart> = Builder().apply(f).parts

    private fun Builder.test(o: TestNode) {
        val d = o.dto
        normal(d.displayName).normal(" — ")
        when {
            o.freshness == null -> status(d.state.wire, d.state == TestState.FAILED)
            o.shownOutcome != null -> normal("last result: ").status(o.shownOutcome.wire, o.shownOutcome == TestState.FAILED)
                .normal(" (").normal(o.freshness.wire).normal(if (o.freshness == TestState.STALE) " — the code changed since this ran" else "").normal(")")
            else -> normal(d.state.wire)
        }
        d.durationMs?.let { normal(" (${"%.0f".format(it)} ms)") }
        normal("\n")
        muted(if (o.manual) "Result from a manual run" else "Result from an automatic run")
        if (o.excluded) muted(" (excluded from automatic runs)")
        normal("\n")
        d.message?.let { normal("\n").normal(it).normal("\n") }
        d.stackTrace?.let { normal("\n").normal(it).normal("\n") }
    }

    private fun Builder.project(o: ProjectNode) {
        val b = o.build
        normal(o.path).normal("\n")
        if (b != null) {
            normal("Build: ").status(b.status.wire, b.status == BuildStatus.FAILED)
            b.blockedBy?.let { normal(" (blocked by $it)") }
            normal("\n")
            if (b.output.isNotBlank()) normal("\n").normal(b.output).normal("\n")
        }
        o.run?.error?.let { normal("\nRun: ").red(o.run.status.wire).normal(" — ").normal(it).normal("\n") }
    }

    private fun Builder.view(o: ViewNode) {
        normal(o.label).normal("\n")
        normal("✔ ${o.counts.passed}  ").status("✖ ${o.counts.failed}", o.counts.failed > 0).normal("  ○ ${o.counts.pending}")
        o.durationMs?.let { normal("   ").normal(TestTreeModel.formatDuration(it)) }
        o.freshness?.let { normal("   (").normal(it.wire).normal(")") }
        normal("\n")
    }

    private class Builder {
        val parts = mutableListOf<DetailPart>()
        fun normal(s: String) = apply { parts += DetailPart(s, DetailStyle.NORMAL) }
        fun red(s: String) = apply { parts += DetailPart(s, DetailStyle.RED) }
        fun muted(s: String) = apply { parts += DetailPart(s, DetailStyle.MUTED) }
        /** [s] in red if [failed], otherwise normal. */
        fun status(s: String, failed: Boolean) = if (failed) red(s) else normal(s)
    }
}
