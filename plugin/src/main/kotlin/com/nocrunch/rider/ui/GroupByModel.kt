package com.nocrunch.rider.ui

import com.nocrunch.rider.view.GroupLevel
import com.nocrunch.rider.view.Grouping

/** The Group by popup's state: which levels are ticked and their order. Ticked levels nest top to bottom. */
class GroupByModel(initial: Grouping) {
    private var ticked: List<GroupLevel> = initial.levels

    /** Ticked levels first, in grouping order, then the unticked ones in [GroupLevel] order. */
    val rows: List<Pair<GroupLevel, Boolean>>
        get() = ticked.map { it to true } + GroupLevel.entries.filter { it !in ticked }.map { it to false }

    val grouping: Grouping get() = Grouping(ticked)

    fun toggle(level: GroupLevel) {
        ticked = if (level in ticked) ticked - level else ticked + level
    }

    fun moveUp(level: GroupLevel) = move(level, -1)

    fun moveDown(level: GroupLevel) = move(level, 1)

    private fun move(level: GroupLevel, by: Int) {
        val i = ticked.indexOf(level)
        val j = i + by
        if (i < 0 || j !in ticked.indices) return
        ticked = ticked.toMutableList().also { it[i] = it[j]; it[j] = level }
    }
}
