package com.nocrunch.rider.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TestFilterTest {
    @TempDir lateinit var dir: Path

    @Test
    fun builds_each_frameworks_single_method_filter() {
        assertEquals(listOf("--filter", "FullyQualifiedName=Shop.InventoryTests.Adds"),
            TestFilter.args(TestFramework.MSTEST, "Shop.InventoryTests", "Adds"))
        assertEquals(listOf("-method", "Shop.T.RunTimeRowTheories.Ten_percent_off"),
            TestFilter.args(TestFramework.XUNIT, "Shop.T.RunTimeRowTheories", "Ten_percent_off(System.String,Shop.Catalog.Product)"))
        assertEquals(listOf("--filter-method", "Shop.T.RunTimeRowTheories.Ten_percent_off"),
            TestFilter.args(TestFramework.XUNIT_MTP, "Shop.T.RunTimeRowTheories", "Ten_percent_off(System.String)"))
        assertEquals(listOf("--treenode-filter", "/*/Shop/InventoryTests/Adds"),
            TestFilter.args(TestFramework.TUNIT, "Shop.InventoryTests", "Adds"))
    }

    @Test
    fun escapes_filter_operators_in_mstest_names() {
        assertEquals("FullyQualifiedName=A.Odd\\=Name", TestFilter.args(TestFramework.MSTEST, "A", "Odd=Name")[1])
    }

    @Test
    fun detects_the_framework_from_the_project_and_restored_packages() {
        fun project(csproj: String, assets: String? = null): Path {
            val d = Files.createTempDirectory(dir, "p")
            val f = d.resolve("T.csproj").also { Files.writeString(it, csproj) }
            assets?.let { Files.createDirectories(d.resolve("obj")); Files.writeString(d.resolve("obj/project.assets.json"), it) }
            return f
        }
        assertEquals(TestFramework.MSTEST, TestFilter.detect(project("""<Project Sdk="MSTest.Sdk"/>""")))
        assertEquals(TestFramework.XUNIT, TestFilter.detect(project("""<PackageReference Include="xunit.v3.mtp-v2" />""")))
        assertEquals(TestFramework.XUNIT, TestFilter.detect(project("<Project/>", assets = """{"libraries":{"xunit.v3.core/4.0.3":{}}}""")))
        assertEquals(TestFramework.XUNIT_MTP, TestFilter.detect(project(
            """<PackageReference Include="xunit.v3" /><UseMicrosoftTestingPlatformRunner>true</UseMicrosoftTestingPlatformRunner>""")))
        assertEquals(TestFramework.NUNIT, TestFilter.detect(project("""<PackageReference Include="NUnit" />""")))
        assertEquals(TestFramework.TUNIT, TestFilter.detect(project("""<PackageReference Include="TUnit" />""")))
    }
}
