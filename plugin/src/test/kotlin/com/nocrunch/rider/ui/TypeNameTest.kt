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

    @Test
    fun the_outermost_known_class_ends_the_namespace() =
        assertEquals(
            TypeName(listOf("Ns"), listOf("Outer", "Inner", "Deep")),
            TypeNameParser(setOf("Ns.Outer", "Ns.Outer.Inner")).parse("Ns.Outer.Inner.Deep"),
        )

    @Test
    fun a_known_type_that_is_a_prefix_of_another_does_not_move_the_split() {
        val parser = TypeNameParser(setOf("Ns.Outer", "Ns.Outer.Inner"))
        assertEquals(TypeName(listOf("Ns"), listOf("Outer", "Inner")), parser.parse("Ns.Outer.Inner"))
        assertEquals(TypeName(listOf("Ns"), listOf("Outer")), parser.parse("Ns.Outer"))
    }

    @Test
    fun plus_with_deeper_segments() =
        assertEquals(
            TypeName(listOf("Ns"), listOf("A", "B", "C")),
            TypeNameParser(emptySet()).parse("Ns.A+B+C"),
        )

    @Test
    fun the_outer_class_is_inferred_from_a_known_plus_type() =
        assertEquals(
            TypeName(listOf("Ns"), listOf("Outer", "Other")),
            TypeNameParser(setOf("Ns.Outer+Inner")).parse("Ns.Outer.Other"),
        )

    @Test
    fun the_convenience_function_agrees_with_the_parser() =
        assertEquals(
            TypeNameParser(setOf("Ns.Outer")).parse("Ns.Outer.Inner"),
            TypeName.parse("Ns.Outer.Inner", setOf("Ns.Outer")),
        )
}
