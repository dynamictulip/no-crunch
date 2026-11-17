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
        |    public void Generic<T>(string s = "(x)")
        |    {
        |        // set up
        |        var x = 1;
        |    }
        |
        |    [TestMethod]
        |    public async Task Arrow_on_next_line() =>
        |        await Task.Delay(1);
        |
        |    [TestMethod]
        |    public void Empty() { }
        |}
    """.trimMargin()

    private fun at(offset: Int) = source.substring(offset).substringBefore('\n')
    private fun lineText(line: Int?) = line?.let { source.lines()[it].trim() }

    @Test
    fun skips_attributes_to_the_method_name() {
        assertEquals("New_inventory_is_empty() => Assert.AreEqual(0, 0);", at(TestLocator.caretOffset(source, 2, "New_inventory_is_empty")))
        assertEquals("Generic<T>(string s = \"(x)\")", at(TestLocator.caretOffset(source, 5, "Generic")))
    }

    @Test
    fun falls_back_to_the_first_non_blank_on_the_line() {
        assertEquals("[TestMethod]", at(TestLocator.caretOffset(source, 2, null)))
        assertEquals("[TestMethod]", at(TestLocator.caretOffset(source, 2, "NotThere")))
    }

    @Test
    fun first_body_line_is_the_first_statement() {
        assertEquals("public void New_inventory_is_empty() => Assert.AreEqual(0, 0);", lineText(TestLocator.firstBodyLine(source, 2, "New_inventory_is_empty")))
        assertEquals("var x = 1;", lineText(TestLocator.firstBodyLine(source, 5, "Generic")))
        assertEquals("await Task.Delay(1);", lineText(TestLocator.firstBodyLine(source, 13, "Arrow_on_next_line")))
        assertEquals("public void Empty() { }", lineText(TestLocator.firstBodyLine(source, 17, "Empty")))
        assertEquals(null, TestLocator.firstBodyLine(source, 2, "NotThere"))
    }

    @Test
    fun parameter_names_skip_types_attributes_and_defaults() {
        val src = """
            |[TestMethod]
            |[DataRow(25)]
            |public async Task Waits(int ms) { }
            |
            |public void Many(Dictionary<string, int> map, [Range(1, 2)] int count, string @class = "a, b") { }
            |public void None() { }
        """.trimMargin()
        assertEquals(listOf("ms"), TestLocator.parameterNames(src, 0, "Waits"))
        assertEquals(listOf("map", "count", "class"), TestLocator.parameterNames(src, 4, "Many"))
        assertEquals(emptyList<String>(), TestLocator.parameterNames(src, 5, "None"))
        assertEquals(null, TestLocator.parameterNames(src, 0, "Missing"))
    }
}
