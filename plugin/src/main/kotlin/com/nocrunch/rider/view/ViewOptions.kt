package com.nocrunch.rider.view

// The tool window's view options. Settings save them by enum name, so renaming a constant loses users' choices.

/** The tool window's old grouping choice, kept only so saved settings convert to a [Grouping]. Nothing else may read it. */
enum class ViewMode(val title: String) {
    CLASSES("Classes"),
    NAMESPACES("Namespaces"),
    FILES("Folders and files");

    companion object {
        val DEFAULT = CLASSES

        /** The option saved as [name], or [DEFAULT] if none is saved or it's no longer known. */
        fun fromSetting(name: String?): ViewMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Order of rows at every level of the tree. */
enum class SortMode(val title: String) {
    NAME("Name"),
    STATUS("Status"),
    DURATION("Run time");

    companion object {
        val DEFAULT = NAME

        /** The option saved as [name], or [DEFAULT] if none is saved or it's no longer known. */
        fun fromSetting(name: String?): SortMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
