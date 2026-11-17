package com.nocrunch.rider.debug

import java.nio.file.Files
import java.nio.file.Path

/** Test frameworks, which each filter to one test differently when their test app is run directly. */
enum class TestFramework {
    MSTEST, NUNIT, TUNIT,
    /** xUnit v3 run directly uses its own console runner... */
    XUNIT,
    /** ...unless the project sets UseMicrosoftTestingPlatformRunner, when it takes MTP's options. */
    XUNIT_MTP,
}

/**
 * Command-line arguments that make a test app run a single test method (all of its data rows).
 * See https://learn.microsoft.com/dotnet/core/testing/selective-unit-tests,
 * https://learn.microsoft.com/dotnet/core/testing/migrating-vstest-microsoft-testing-platform (xUnit v3 on MTP),
 * and the test app's own `-?` help (xUnit v3's native `-method`).
 */
object TestFilter {
    fun args(framework: TestFramework, type: String, method: String): List<String> {
        val name = method.substringBefore('(').substringBefore('<')
        val fqn = "$type.$name"
        return when (framework) {
            TestFramework.MSTEST, TestFramework.NUNIT -> listOf("--filter", "FullyQualifiedName=${escapeVsTest(fqn)}")
            TestFramework.XUNIT -> listOf("-method", fqn)
            TestFramework.XUNIT_MTP -> listOf("--filter-method", fqn)
            TestFramework.TUNIT -> {
                val ns = type.substringBeforeLast('.', "")
                val cls = type.substringAfterLast('.')
                listOf("--treenode-filter", "/*/${ns.ifEmpty { "*" }}/$cls/$name")
            }
        }
    }

    /** VSTest-style filter values escape the operator characters with a backslash. */
    private fun escapeVsTest(value: String) = value.replace(Regex("""[\\()&|=!~]""")) { "\\" + it.value }

    private val MTP_RUNNER = Regex("""<usemicrosofttestingplatformrunner>\s*true""")

    /**
     * Works out the framework from the project file, any Directory.Build.props above it, and (after a restore)
     * the packages it resolved, which also catches package references set centrally.
     */
    fun detect(projectFile: Path): TestFramework {
        val msbuild = buildList {
            runCatching { add(Files.readString(projectFile)) }
            generateSequence(projectFile.parent) { it.parent }
                .map { it.resolve("Directory.Build.props") }
                .filter { Files.isRegularFile(it) }
                .forEach { f -> runCatching { add(Files.readString(f)) } }
        }.joinToString("\n").lowercase()
        val assets = runCatching { Files.readString(projectFile.resolveSibling("obj").resolve("project.assets.json")) }
            .getOrDefault("").lowercase()
        val texts = msbuild + "\n" + assets
        return when {
            "xunit.v3" in texts -> if (MTP_RUNNER.containsMatchIn(msbuild)) TestFramework.XUNIT_MTP else TestFramework.XUNIT
            Regex("""["/ ]tunit""").containsMatchIn(texts) -> TestFramework.TUNIT
            "nunit" in texts -> TestFramework.NUNIT
            else -> TestFramework.MSTEST
        }
    }
}
