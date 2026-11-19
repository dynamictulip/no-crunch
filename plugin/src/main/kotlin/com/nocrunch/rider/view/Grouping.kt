package com.nocrunch.rider.view

// Settings save levels by enum name, so renaming a constant loses users' choices.

/** One level the test tree can group by. */
enum class GroupLevel(val title: String) {
    PROJECT("Project"),
    FOLDER("Folder"),
    FILE("File"),
    NAMESPACE("Namespace"),
    CLASS("Class"),
}

/** The levels the tree nests, outermost first. No levels means tests only, flat. */
data class Grouping(val levels: List<GroupLevel>) {
    val title: String get() = if (levels.isEmpty()) "Tests only" else levels.joinToString(SEPARATOR) { it.title }

    /** "-" for no levels: a blank setting means "never saved", which falls back to the old view mode. */
    fun toSetting(): String = if (levels.isEmpty()) NONE else levels.joinToString(",") { it.name }

    fun has(level: GroupLevel): Boolean = level in levels

    /** True when [level] is nested outside [other]; false if either isn't grouped by. */
    fun above(level: GroupLevel, other: GroupLevel): Boolean {
        val i = levels.indexOf(level)
        val j = levels.indexOf(other)
        return i >= 0 && j >= 0 && i < j
    }

    companion object {
        private const val SEPARATOR = " › "
        private const val NONE = "-"

        val DEFAULT = Grouping(listOf(GroupLevel.PROJECT, GroupLevel.CLASS))

        /** The saved grouping, or the old [ViewMode] named [legacyViewMode] when nothing was saved yet. */
        fun fromSetting(saved: String?, legacyViewMode: String?): Grouping {
            if (saved.isNullOrBlank()) return fromViewMode(ViewMode.fromSetting(legacyViewMode))
            if (saved == NONE) return Grouping(emptyList())
            val levels = saved.split(",").mapNotNull { name -> GroupLevel.entries.firstOrNull { it.name == name.trim() } }
            return Grouping(levels.distinct())
        }

        fun fromViewMode(mode: ViewMode): Grouping = when (mode) {
            ViewMode.CLASSES -> DEFAULT
            ViewMode.NAMESPACES -> Grouping(listOf(GroupLevel.PROJECT, GroupLevel.NAMESPACE, GroupLevel.CLASS))
            ViewMode.FILES -> Grouping(listOf(GroupLevel.PROJECT, GroupLevel.FOLDER, GroupLevel.FILE))
        }
    }
}

/** The few groupings the user picked last, for quick switching. */
object RecentGroupings {
    const val MAX = 3

    /** [used] first, any earlier copy of it dropped, oldest beyond [MAX] forgotten. */
    fun remember(recent: List<Grouping>, used: Grouping): List<Grouping> =
        (listOf(used) + recent.filter { it != used }).take(MAX)

    fun fromSetting(saved: String?): List<Grouping> =
        if (saved.isNullOrBlank()) emptyList()
        else saved.split(";").filter { it.isNotBlank() }.map { Grouping.fromSetting(it, null) }.distinct().take(MAX)

    fun toSetting(recent: List<Grouping>): String = recent.joinToString(";") { it.toSetting() }
}
