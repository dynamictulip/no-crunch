package com.nocrunch.rider.ui

import com.nocrunch.rider.engine.BuildStatus
import com.nocrunch.rider.engine.RunStatus
import com.nocrunch.rider.engine.TestState

/** Row object for the pinned section listing non-test projects whose build broke. */
data class BuildErrorsGroup(val count: Int)

/** Row object for the pinned section of projects with problems ([GroupedTree.problemsTitle] says which kind). */
data class ProblemsGroup(val title: String, val count: Int)

/** The status half of a row's icon. */
enum class StatusIcon { PASSED, FAILED, SKIPPED, EXCLUDED, IN_PROGRESS, ERROR, WARNING }

/** What the row is (project, namespace, ...), drawn after its status like Rider's test tree. */
enum class KindIcon { PROJECT, NAMESPACE, FOLDER, FILE, TEST_SET, TEST }

/**
 * A row's icon: a status, a kind, or the status followed by the kind. [faded]: the status is an outcome from an earlier
 * run (stale, queued or running again). [fileName] picks the file-type icon for [KindIcon.FILE].
 */
data class RowIcon(
    val status: StatusIcon?, val kind: KindIcon?, val faded: Boolean = false, val fileName: String? = null,
    /** Some tests below are excluded, some not: the status stays the group's result and the renderer adds a small excluded badge. */
    val partlyExcluded: Boolean = false,
)

/** A row's checkbox: [PARTIAL] when only some of the tests below are excluded. */
enum class CheckState { CHECKED, UNCHECKED, PARTIAL }

/** How a row's name is drawn. The name styles ([PROJECT], [PARENT], [TEST], [ARGS]) are colour-coded when that's on. */
enum class NameStyle {
    /** Bold, split into PascalCase words when colour-coded. */
    PROJECT,
    /** A namespace, folder, file or class: split into PascalCase words when colour-coded. */
    PARENT,
    /** A test name, split by the chosen colouring. */
    TEST,
    /** A theory data row's arguments. */
    ARGS,
    GREY,
    FAILED,
    FAILED_BOLD,
    /** Muted red: a failure from an earlier run. */
    STALE_FAILED,
    STALE_FAILED_BOLD,
}

/** Styles of the counts, run time and notes after a row's name. */
enum class StatStyle { GREY, GREY_ITALIC, ERROR, PASSED_BOLD, FAILED_BOLD, MANUAL }

data class Segment(val text: String, val style: StatStyle)

data class RowLook(val icon: RowIcon, val name: String, val nameStyle: NameStyle, val stats: List<Segment>)

/** What each row of the test tree shows: which icon, how its name is drawn and what follows it. No Swing here. */
object RowPresentation {
    /**
     * [editing]: the checkboxes for choosing automatic tests are showing. [anyFailures]: some test in the tree fails,
     * so only the red crosses are highlighted, not the green ticks. Null for anything that isn't a row of the tree.
     */
    fun of(o: Any?, editing: Boolean, anyFailures: Boolean): RowLook? = when (o) {
        is BuildErrorsGroup -> RowLook(RowIcon(StatusIcon.ERROR, null), "Build errors", NameStyle.FAILED_BOLD,
            listOf(Segment("  ${o.count}", StatStyle.GREY)))
        is ProblemsGroup -> RowLook(RowIcon(StatusIcon.ERROR, null), o.title, NameStyle.FAILED_BOLD,
            listOf(Segment("  ${o.count}", StatStyle.GREY)))
        is ProjectNode -> project(o, o.totals, o.excluded, 0, anyFailures)
        is GroupView -> if (o.kind == GroupKind.PROJECT && o.project != null) projectGroup(o, o.project, anyFailures) else view(o, editing, anyFailures)
        is ViewNode -> view(o, editing, anyFailures)
        is TestNode -> test(o, editing, anyFailures)
        else -> null
    }

    /** Build-error and problem rows have nothing to include or exclude; theory rows follow their theory. */
    fun checkboxVisible(o: Any?, editing: Boolean): Boolean = editing && hasCheckbox(o)

    /** How the row's checkbox is drawn; null for rows without one. */
    fun checkState(o: Any?): CheckState? {
        if (!hasCheckbox(o)) return null
        return when {
            o is GroupView && o.partlyExcluded -> CheckState.PARTIAL
            isExcluded(o) -> CheckState.UNCHECKED
            else -> CheckState.CHECKED
        }
    }

    private fun hasCheckbox(o: Any?): Boolean =
        when (o) {
            is ProjectNode -> o.isTestProject
            is TestNode -> !o.isRow
            is ViewNode -> true
            else -> false
        }

    private fun isExcluded(o: Any?): Boolean = when (o) {
        is ProjectNode -> o.excluded
        is GroupView -> groupExcluded(o)
        is ViewNode -> o.excluded
        is TestNode -> o.excluded
        else -> false
    }

    /** A project row with no tests (it never built) has no leaves to be excluded, so its own rule decides. */
    private fun groupExcluded(o: GroupView): Boolean = if (o.leaves.isEmpty()) o.project?.excluded == true else o.excluded

    private fun projectGroup(o: GroupView, project: ProjectNode, anyFailures: Boolean): RowLook =
        project(project, o.totals, groupExcluded(o), o.excludedCount.takeIf { o.partlyExcluded } ?: 0, anyFailures, o.label)

    /**
     * A project's status row. [excluded] and [partlyExcluded] (the number of excluded tests, 0 when not partly) come from
     * the row itself, as a project row below a namespace may hold only part of the project.
     */
    private fun project(o: ProjectNode, totals: Totals, excluded: Boolean, partlyExcluded: Int, anyFailures: Boolean, name: String = o.name): RowLook {
        val fresh = totals.freshness == null
        val icon = when (o.buildStatus) {
            BuildStatus.FAILED -> RowIcon(StatusIcon.ERROR, KindIcon.PROJECT)
            BuildStatus.BLOCKED -> RowIcon(StatusIcon.WARNING, KindIcon.PROJECT)
            BuildStatus.BUILDING -> RowIcon(StatusIcon.IN_PROGRESS, KindIcon.PROJECT)
            else -> if (excluded) RowIcon(StatusIcon.EXCLUDED, KindIcon.PROJECT) else outcome(totals.shownOutcome, fresh, KindIcon.PROJECT)
        }.copy(partlyExcluded = partlyExcluded > 0)
        val style = when {
            excluded -> NameStyle.GREY
            totals.shownOutcome == TestState.FAILED -> if (fresh) NameStyle.FAILED_BOLD else NameStyle.STALE_FAILED_BOLD
            else -> NameStyle.PROJECT
        }
        val stats = mutableListOf<Segment>()
        if (o.isTestProject) {
            stats += counts(totals.counts, excluded, anyFailures)
            stats += duration(totals.durationMs)
            stats += freshness(totals.freshness)
            stats += manual(totals.manualCount)
        }
        stats += partlyExcludedCount(partlyExcluded)
        when {
            excluded -> stats += Segment("  (excluded)", StatStyle.GREY_ITALIC)
            o.buildStatus == BuildStatus.FAILED -> stats += Segment("  build failed", StatStyle.ERROR)
            o.buildStatus == BuildStatus.BLOCKED -> stats += Segment("  blocked by ${o.blockedByName ?: "build failure"}", StatStyle.GREY_ITALIC)
            o.run?.status == RunStatus.UNSUPPORTED_NOT_MTP -> stats += Segment("  unsupported (VSTest)", StatStyle.GREY_ITALIC)
            o.run?.status == RunStatus.UNSUPPORTED_MTP_VERSION -> stats += Segment("  unsupported MTP version", StatStyle.GREY_ITALIC)
            o.run?.status == RunStatus.CRASHED -> stats += Segment("  test host crashed", StatStyle.ERROR)
        }
        return RowLook(icon, name, style, stats)
    }

    /** Grouping rows and classes show their status beside what they are. */
    private fun view(o: ViewNode, editing: Boolean, anyFailures: Boolean): RowLook {
        val kind = when {
            o is GroupView && o.kind == GroupKind.NAMESPACE -> KindIcon.NAMESPACE
            o is GroupView && o.kind == GroupKind.FOLDER -> KindIcon.FOLDER
            o is GroupView && o.kind == GroupKind.FILE -> KindIcon.FILE
            o is GroupView && o.kind == GroupKind.PROJECT -> KindIcon.PROJECT
            else -> KindIcon.TEST_SET
        }
        val partly = (o as? GroupView)?.takeIf { it.partlyExcluded }?.excludedCount ?: 0
        val icon = (if (o.excluded) RowIcon(StatusIcon.EXCLUDED, kind) else outcome(o.shownOutcome, o.isFresh, kind))
            .copy(fileName = if (kind == KindIcon.FILE) o.label else null, partlyExcluded = partly > 0)
        val style = when {
            // Excluded rows are grey unless they hold a current manual result.
            o.excluded && o.leaves.none { it.manual && it.freshness == null } -> NameStyle.GREY
            o.shownOutcome == TestState.FAILED -> if (o.isFresh) NameStyle.FAILED else NameStyle.STALE_FAILED
            else -> NameStyle.PARENT
        }
        val stats = counts(o.counts, o.excluded, anyFailures) + duration(o.durationMs) + freshness(o.freshness) + manual(o.manualCount) + partlyExcludedCount(partly)
        // Without checkboxes, say which rows are left out of automatic runs.
        val note = if (o.excluded && !o.parentExcluded && !editing) listOf(Segment("  (excluded)", StatStyle.GREY_ITALIC)) else emptyList()
        return RowLook(icon, o.label, style, stats + note)
    }

    /**
     * A theory or group of data rows shows its status beside the two circles, like a class. A single test shows its
     * status once there's a result (or while running), otherwise what it is. Excluded tests always show the excluded
     * icon; a manual run's result goes beside the run time.
     */
    private fun test(o: TestNode, editing: Boolean, anyFailures: Boolean): RowLook {
        val fresh = o.freshness == null
        val status = when {
            o.excluded -> RowIcon(StatusIcon.EXCLUDED, null)
            else -> outcome(o.shownOutcome, fresh, null).takeIf { it.status != null }
                ?: RowIcon(StatusIcon.IN_PROGRESS, null).takeIf { o.dto.state == TestState.RUNNING }
        }
        val icon = when {
            o.rows.isNotEmpty() -> (status ?: RowIcon(null, null)).copy(kind = KindIcon.TEST_SET)
            else -> status ?: RowIcon(null, KindIcon.TEST)
        }
        // Failed and excluded rows keep their red or grey; colour coding only applies to the rest.
        val style = when {
            // Excluded tests are grey unless they have a current manual result; a stale one goes grey again.
            o.excluded && !(o.manual && fresh) -> NameStyle.GREY
            o.shownOutcome == TestState.FAILED -> if (fresh) NameStyle.FAILED else NameStyle.STALE_FAILED
            o.isRow -> NameStyle.ARGS
            else -> NameStyle.TEST
        }
        val stats = mutableListOf<Segment>()
        if (o.rows.isNotEmpty()) stats += counts(o.counts, o.excluded, anyFailures)
        if (o.excluded && o.rows.isEmpty()) {
            when (o.shownOutcome) {
                TestState.PASSED -> stats += Segment("   ✔", if (fresh) StatStyle.PASSED_BOLD else StatStyle.GREY)
                TestState.FAILED -> stats += Segment("   ✖", if (fresh) StatStyle.FAILED_BOLD else StatStyle.GREY)
                else -> {}
            }
        }
        if (o.shownOutcome != null) stats += duration(o.dto.durationMs)
        stats += freshness(o.freshness)
        // A theory's rows share its run, so only the theory says it was a manual run.
        if (o.manual && !o.isRow) stats += Segment("  ▸ manual run", StatStyle.MANUAL)
        else if (o.excluded && !o.parentExcluded && !o.isRow && !editing) stats += Segment("  (excluded)", StatStyle.GREY_ITALIC)
        return RowLook(icon, o.label, style, stats)
    }

    /** Outcome icon beside [kind], faded when the result is from an earlier run; just [kind] when there's no outcome. */
    private fun outcome(outcome: TestState?, fresh: Boolean, kind: KindIcon?): RowIcon {
        val status = when (outcome) {
            TestState.PASSED -> StatusIcon.PASSED
            TestState.FAILED -> StatusIcon.FAILED
            TestState.SKIPPED -> StatusIcon.SKIPPED
            else -> return RowIcon(null, kind)
        }
        return RowIcon(status, kind, faded = !fresh)
    }

    /** Highlights the cross when something fails, otherwise the tick when something passed. */
    private fun counts(c: Counts, excluded: Boolean, anyFailures: Boolean): List<Segment> {
        val allPassed = c.failed == 0 && c.passed > 0 && !excluded && !anyFailures
        return listOf(
            Segment("   ✔ ${c.passed}", if (allPassed) StatStyle.PASSED_BOLD else StatStyle.GREY),
            Segment("  ", StatStyle.GREY),
            Segment("✖ ${c.failed}", if (c.failed > 0 && !excluded) StatStyle.FAILED_BOLD else StatStyle.GREY),
            Segment("  ○ ${c.pending}", StatStyle.GREY),
        )
    }

    private fun partlyExcludedCount(n: Int): List<Segment> = if (n > 0) listOf(Segment("  · $n excluded", StatStyle.GREY_ITALIC)) else emptyList()

    private fun duration(ms: Double?): List<Segment> =
        if (ms == null) emptyList() else listOf(Segment("  ${TestTreeModel.formatDuration(ms)}", StatStyle.GREY))

    private fun freshness(freshness: TestState?): List<Segment> = when (freshness) {
        null -> emptyList()
        TestState.RUNNING -> listOf(Segment("  running…", StatStyle.GREY_ITALIC))
        else -> listOf(Segment("  ${freshness.wire}", StatStyle.GREY_ITALIC))
    }

    private fun manual(n: Int): List<Segment> = if (n > 0) listOf(Segment("  ▸ $n manual", StatStyle.MANUAL)) else emptyList()
}
