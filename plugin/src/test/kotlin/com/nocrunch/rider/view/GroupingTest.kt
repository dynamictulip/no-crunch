package com.nocrunch.rider.view

import com.nocrunch.rider.view.GroupLevel.CLASS
import com.nocrunch.rider.view.GroupLevel.FILE
import com.nocrunch.rider.view.GroupLevel.FOLDER
import com.nocrunch.rider.view.GroupLevel.NAMESPACE
import com.nocrunch.rider.view.GroupLevel.PROJECT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Groupings are saved in the workspace file by level name, and old view modes must keep converting. */
class GroupingTest {
    @Test
    fun converts_each_old_view() {
        assertEquals(listOf(PROJECT, CLASS), Grouping.fromSetting("", "CLASSES").levels)
        assertEquals(listOf(PROJECT, NAMESPACE, CLASS), Grouping.fromSetting("", "NAMESPACES").levels)
        assertEquals(listOf(PROJECT, FOLDER, FILE), Grouping.fromSetting("", "FILES").levels)
        assertEquals(Grouping.DEFAULT, Grouping.fromSetting(null, null))
    }

    @Test
    fun saved_grouping_wins_over_old_view() {
        assertEquals(listOf(FOLDER, CLASS), Grouping.fromSetting("FOLDER,CLASS", "NAMESPACES").levels)
    }

    @Test
    fun round_trips_and_ignores_junk() {
        assertEquals("NAMESPACE,CLASS", Grouping(listOf(NAMESPACE, CLASS)).toSetting())
        assertEquals(listOf(CLASS), Grouping.fromSetting("CLASS,BOGUS,CLASS", null).levels)
    }

    @Test
    fun nothing_ticked_is_saved_as_a_dash() {
        assertEquals("-", Grouping(emptyList()).toSetting())
        assertEquals(emptyList<GroupLevel>(), Grouping.fromSetting("-", "NAMESPACES").levels)
    }

    @Test
    fun title_names_the_levels() {
        assertEquals("Project › Folder › Class", Grouping(listOf(PROJECT, FOLDER, CLASS)).title)
        assertEquals("Tests only", Grouping(emptyList()).title)
    }

    @Test
    fun has_and_above_follow_the_order() {
        val g = Grouping(listOf(PROJECT, NAMESPACE, CLASS))
        assertEquals(true, g.has(NAMESPACE))
        assertEquals(false, g.has(FILE))
        assertEquals(true, g.above(PROJECT, CLASS))
        assertEquals(false, g.above(CLASS, PROJECT))
        assertEquals(false, g.above(PROJECT, FILE))
    }

    @Test
    fun recent_keeps_three_newest_without_duplicates() {
        val a = Grouping(listOf(PROJECT))
        val b = Grouping(listOf(CLASS))
        val c = Grouping(listOf(FILE))
        val d = Grouping(listOf(FOLDER))
        val recent = listOf(a, b, c, a, d).fold(emptyList<Grouping>()) { acc, g -> RecentGroupings.remember(acc, g) }
        assertEquals(listOf(d, a, c), recent)
    }

    @Test
    fun a_visit_remembers_where_it_started_then_where_it_ended() {
        val a = Grouping(listOf(PROJECT, CLASS))
        val b = Grouping(listOf(CLASS))
        assertEquals(listOf(b, a), RecentGroupings.afterVisit(emptyList(), a, b))
        assertEquals(listOf(b, a), RecentGroupings.afterVisit(listOf(b), a, b))
        assertEquals(listOf(a), RecentGroupings.afterVisit(listOf(a), a, a))
        assertEquals(emptyList<Grouping>(), RecentGroupings.afterVisit(emptyList(), a, a))
    }

    @Test
    fun recent_round_trips() {
        val recent = listOf(Grouping(listOf(PROJECT, CLASS)), Grouping(emptyList()))
        assertEquals("PROJECT,CLASS;-", RecentGroupings.toSetting(recent))
        assertEquals(recent, RecentGroupings.fromSetting("PROJECT,CLASS;-"))
        assertEquals(emptyList<Grouping>(), RecentGroupings.fromSetting(""))
    }
}
