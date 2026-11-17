package com.nocrunch.rider.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestLocatorTest {
    private val source = """
        |public class InventoryTests
        |{
        |    [TestMethod]
        |    public void New_inventory_is_empty() => Assert.AreEqual(0, 0);
        |
        |    [Theory]
        |    [MemberData(nameof(Rows))]
        |    public void Generic<T>(string s) { }
        |}
    """.trimMargin()

    private fun at(offset: Int) = source.substring(offset).substringBefore('\n')

    @Test
    fun skips_attributes_to_the_method_name() {
        assertEquals("New_inventory_is_empty() => Assert.AreEqual(0, 0);", at(TestLocator.caretOffset(source, 2, "New_inventory_is_empty")))
        assertEquals("Generic<T>(string s) { }", at(TestLocator.caretOffset(source, 5, "Generic")))
    }

    @Test
    fun falls_back_to_the_first_non_blank_on_the_line() {
        assertEquals("[TestMethod]", at(TestLocator.caretOffset(source, 2, null)))
        assertEquals("[TestMethod]", at(TestLocator.caretOffset(source, 2, "NotThere")))
    }
}
