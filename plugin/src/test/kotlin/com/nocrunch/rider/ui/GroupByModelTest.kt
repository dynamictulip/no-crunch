package com.nocrunch.rider.ui

import com.nocrunch.rider.view.GroupLevel.CLASS
import com.nocrunch.rider.view.GroupLevel.FILE
import com.nocrunch.rider.view.GroupLevel.FOLDER
import com.nocrunch.rider.view.GroupLevel.NAMESPACE
import com.nocrunch.rider.view.GroupLevel.PROJECT
import com.nocrunch.rider.view.Grouping
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GroupByModelTest {
    @Test
    fun `ticked levels come first in order`() {
        val model = GroupByModel(Grouping(listOf(CLASS, PROJECT)))
        assertEquals(
            listOf(CLASS to true, PROJECT to true, FOLDER to false, FILE to false, NAMESPACE to false),
            model.rows,
        )
    }

    @Test
    fun `toggle adds at the end of the ticked levels`() {
        val model = GroupByModel(Grouping(listOf(PROJECT, CLASS)))
        model.toggle(FOLDER)
        assertEquals(Grouping(listOf(PROJECT, CLASS, FOLDER)), model.grouping)
        model.toggle(PROJECT)
        assertEquals(Grouping(listOf(CLASS, FOLDER)), model.grouping)
    }

    @Test
    fun `move up and down reorder ticked levels`() {
        val model = GroupByModel(Grouping(listOf(PROJECT, CLASS)))
        model.toggle(FOLDER)
        model.moveUp(FOLDER)
        model.moveUp(FOLDER)
        assertEquals(Grouping(listOf(FOLDER, PROJECT, CLASS)), model.grouping)
        model.moveDown(FOLDER)
        assertEquals(Grouping(listOf(PROJECT, FOLDER, CLASS)), model.grouping)
    }

    @Test
    fun `moving past the ends does nothing`() {
        val model = GroupByModel(Grouping(listOf(PROJECT, CLASS)))
        model.moveUp(PROJECT)
        model.moveDown(CLASS)
        model.moveUp(FOLDER)
        model.moveDown(FOLDER)
        assertEquals(Grouping(listOf(PROJECT, CLASS)), model.grouping)
    }

    @Test
    fun `unticking everything gives tests only`() {
        val model = GroupByModel(Grouping(listOf(PROJECT, CLASS)))
        model.toggle(PROJECT)
        model.toggle(CLASS)
        assertEquals(Grouping(emptyList()), model.grouping)
    }
}
