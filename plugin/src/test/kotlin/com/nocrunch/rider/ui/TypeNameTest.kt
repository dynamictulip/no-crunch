package com.nocrunch.rider.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TypeNameTest {
    @Test
    fun plus_marks_nesting() =
        assertEquals(
            TypeName(listOf("Shop", "Tests"), listOf("Outer", "Inner")),
            TypeName.parse("Shop.Tests.Outer+Inner", emptySet()),
        )

    @Test
    fun dot_nesting_is_recognised_when_the_outer_class_is_known() =
        assertEquals(
            TypeName(listOf("Shop", "Tests"), listOf("Outer", "Inner")),
            TypeName.parse("Shop.Tests.Outer.Inner", setOf("Shop.Tests.Outer")),
        )

    @Test
    fun dot_without_a_known_outer_class_is_a_namespace() =
        assertEquals(
            TypeName(listOf("Shop", "Tests", "Outer"), listOf("Inner")),
            TypeName.parse("Shop.Tests.Outer.Inner", emptySet()),
        )

    @Test
    fun global_namespace() =
        assertEquals(TypeName(emptyList(), listOf("Plain")), TypeName.parse("Plain", emptySet()))
}
