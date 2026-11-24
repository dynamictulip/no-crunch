using NoCrunch.Engine.Coverage;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Layout;
using NoCrunch.Engine.Tests.Graph;
using NoCrunch.Engine.Tests.Layout;

namespace NoCrunch.Engine.Tests.Coverage;

[TestClass]
public class CoverageTargetsTests
{
    private const string Test = "tests/T/T.csproj";

    private static ProjectInfo Proj(string rel, string? targetDll, bool test = false, params string[] refs) =>
        new(rel, Path.GetFileNameWithoutExtension(rel), "/m/" + rel, test, test, targetDll, refs, []);

    private static async Task<ProjectGraph> Load(params ProjectInfo[] projects)
    {
        var root = TempDir.Create();
        File.WriteAllText(Path.Combine(root, "S.slnx"), "");
        return await ProjectGraph.LoadAsync(RepoLayout.Resolve(Path.Combine(root, "S.slnx")), new ProjectGraphTests.FakeQuery(projects), CancellationToken.None);
    }

    /// <summary>An output folder holding the given files (a name with .dll also gets a .pdb unless it ends in "!").</summary>
    private static string Output(params string[] files)
    {
        var dir = TempDir.Create();
        foreach (var f in files) File.WriteAllText(Path.Combine(dir, f), f);
        return dir;
    }

    [TestMethod]
    public async Task Select_includes_transitive_production_references()
    {
        var output = Output("T.dll", "A.dll", "A.pdb", "B.dll", "B.pdb");
        var graph = await Load(
            Proj(Test, Path.Combine(output, "T.dll"), true, "src/A/A.csproj"),
            Proj("src/A/A.csproj", Path.Combine(output, "A.dll"), refs: "src/B/B.csproj"),
            Proj("src/B/B.csproj", Path.Combine(output, "B.dll")));

        var set = CoverageTargets.Select(graph.Find(Test)!, graph, output);

        CollectionAssert.AreEqual(new[] { "src/A/A.csproj", "src/B/B.csproj" }, set.Production.Select(p => p.RepoRelativePath).ToArray());
        CollectionAssert.AreEqual(new[] { "A.dll", "B.dll" }, set.DllNames.ToArray());
        Assert.AreEqual(0, set.Missing.Count);
    }

    [TestMethod]
    public async Task Select_excludes_test_and_helper_test_projects()
    {
        var output = Output("T.dll", "Helper.dll", "Helper.pdb", "A.dll", "A.pdb", "B.dll", "B.pdb");
        var graph = await Load(
            Proj(Test, Path.Combine(output, "T.dll"), true, "tests/Helper/Helper.csproj", "src/A/A.csproj"),
            Proj("tests/Helper/Helper.csproj", Path.Combine(output, "Helper.dll"), true, "src/B/B.csproj"),
            Proj("src/A/A.csproj", Path.Combine(output, "A.dll")),
            Proj("src/B/B.csproj", Path.Combine(output, "B.dll")));

        var set = CoverageTargets.Select(graph.Find(Test)!, graph, output);

        CollectionAssert.AreEqual(new[] { "src/A/A.csproj", "src/B/B.csproj" }, set.Production.Select(p => p.RepoRelativePath).ToArray());
    }

    [TestMethod]
    public async Task Select_skips_projects_whose_dll_or_pdb_is_missing()
    {
        var output = Output("T.dll", "A.dll", "A.pdb", "NoPdb.dll");
        var graph = await Load(
            Proj(Test, Path.Combine(output, "T.dll"), true, "src/A/A.csproj", "src/NoPdb/NoPdb.csproj", "src/NoTarget/NoTarget.csproj"),
            Proj("src/A/A.csproj", Path.Combine(output, "A.dll")),
            Proj("src/NoPdb/NoPdb.csproj", Path.Combine(output, "NoPdb.dll")),
            Proj("src/NoTarget/NoTarget.csproj", null));

        var set = CoverageTargets.Select(graph.Find(Test)!, graph, output);

        CollectionAssert.AreEqual(new[] { "src/A/A.csproj" }, set.Production.Select(p => p.RepoRelativePath).ToArray());
        CollectionAssert.AreEqual(new[] { "src/NoPdb/NoPdb.csproj", "src/NoTarget/NoTarget.csproj" }, set.Missing.Select(p => p.RepoRelativePath).ToArray());
    }

    [TestMethod]
    public void CopyOutput_copies_all_files_and_leaves_the_source_untouched()
    {
        var output = Output("T.dll", "A.dll");
        Directory.CreateDirectory(Path.Combine(output, "runtimes", "x"));
        File.WriteAllText(Path.Combine(output, "runtimes", "x", "native.dll"), "native");
        var dest = Path.Combine(TempDir.Create(), "copy");

        var app = CoverageTargets.CopyOutput(output, dest, Path.Combine(output, "T.dll"));

        Assert.AreEqual(Path.Combine(dest, "T.dll"), app);
        Assert.AreEqual("native", File.ReadAllText(Path.Combine(dest, "runtimes", "x", "native.dll")));
        File.WriteAllText(Path.Combine(dest, "A.dll"), "instrumented");
        Assert.AreEqual("A.dll", File.ReadAllText(Path.Combine(output, "A.dll")), "the original is not affected by changes to the copy");
    }

    [TestMethod]
    public void CopyOutput_replaces_an_existing_destination()
    {
        var output = Output("T.dll");
        var dest = Output("stale.dll", "T.dll");

        CoverageTargets.CopyOutput(output, dest, Path.Combine(output, "T.dll"));

        Assert.IsFalse(File.Exists(Path.Combine(dest, "stale.dll")));
        Assert.AreEqual("T.dll", File.ReadAllText(Path.Combine(dest, "T.dll")));
    }
}
