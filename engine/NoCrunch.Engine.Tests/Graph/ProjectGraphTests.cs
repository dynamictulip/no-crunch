using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Layout;
using NoCrunch.Engine.Tests.Layout;

namespace NoCrunch.Engine.Tests.Graph;

[TestClass]
public class ProjectGraphTests
{
    private const string Core = "src/Core/Core.csproj";
    private const string CoreTests = "tests/Core.Tests/Core.Tests.csproj";
    private const string Other = "tests/Other.Tests/Other.Tests.csproj";
    private const string App = "src/App/App.csproj";

    internal sealed class FakeQuery(params ProjectInfo[] projects) : IMsBuildQuery
    {
        public Task<IReadOnlyList<string>> ListSolutionProjectsAsync(string mirrorSolutionPath, CancellationToken ct) =>
            Task.FromResult<IReadOnlyList<string>>(projects.Select(p => p.MirrorProjectPath).ToList());

        public Task<ProjectInfo> QueryAsync(string mirrorProjectPath, CancellationToken ct) =>
            Task.FromResult(projects.Single(p => p.MirrorProjectPath == mirrorProjectPath));
    }

    internal static ProjectInfo P(string rel, bool test = false, string[]? refs = null, string[]? items = null) =>
        new(rel, Path.GetFileNameWithoutExtension(rel), "/m/" + rel, test, test, test ? "/m/bin/" + rel + ".dll" : null,
            refs ?? [], items ?? []);

    internal static async Task<ProjectGraph> Load(params ProjectInfo[] projects)
    {
        var root = TempDir.Create();
        File.WriteAllText(Path.Combine(root, "S.slnx"), "");
        return await ProjectGraph.LoadAsync(RepoLayout.Resolve(Path.Combine(root, "S.slnx")), new FakeQuery(projects), CancellationToken.None);
    }

    private static Task<ProjectGraph> Standard() => Load(
        P(Core, items: ["src/Core/Calc.cs", "src/Shared/Linked.cs"]),
        P(App, refs: [Core], items: ["src/App/Program.cs", "src/Shared/Linked.cs"]),
        P(CoreTests, test: true, refs: [App], items: ["tests/Core.Tests/CalcTests.cs"]),
        P(Other, test: true, items: ["tests/Other.Tests/OtherTests.cs"]));

    [TestMethod]
    public async Task OwnersOf_includes_deepest_project_dir()
    {
        var g = await Standard();
        CollectionAssert.AreEquivalent(new[] { Core }, g.OwnersOf("src/Core/Sub/Undeclared.txt").ToArray());
    }

    [TestMethod]
    public async Task OwnersOf_includes_projects_linking_the_file()
    {
        var g = await Standard();
        CollectionAssert.AreEquivalent(new[] { Core, App }, g.OwnersOf("src/Shared/Linked.cs").ToArray());
    }

    [TestMethod]
    public async Task OwnersOf_unowned_file_is_empty()
    {
        var g = await Standard();
        Assert.AreEqual(0, g.OwnersOf("docs/readme.md").Count);
    }

    [TestMethod]
    public async Task DirtyProjects_adds_transitive_dependents()
    {
        var g = await Standard();
        CollectionAssert.AreEquivalent(new[] { Core, App, CoreTests }, g.DirtyProjects(["src/Core/Calc.cs"]).ToArray());
    }

    [TestMethod]
    public async Task DirtyProjects_test_file_only_its_project()
    {
        var g = await Standard();
        CollectionAssert.AreEquivalent(new[] { CoreTests }, g.DirtyProjects(["tests/Core.Tests/CalcTests.cs"]).ToArray());
    }

    [TestMethod]
    [DataRow("Directory.Build.props")]
    [DataRow("src/Directory.Build.targets")]
    [DataRow(".editorconfig")]
    [DataRow("global.json")]
    [DataRow("NuGet.config")]
    [DataRow("Directory.Packages.props")]
    public async Task DirtyProjects_global_build_file_marks_all(string file)
    {
        var g = await Standard();
        Assert.AreEqual(4, g.DirtyProjects([file]).Count);
    }

    [TestMethod]
    public async Task InBuildOrder_dependencies_first()
    {
        var g = await Standard();
        var order = g.InBuildOrder([CoreTests, App, Core, Other]).Select(p => p.RepoRelativePath).ToList();
        Assert.IsTrue(order.IndexOf(Core) < order.IndexOf(App));
        Assert.IsTrue(order.IndexOf(App) < order.IndexOf(CoreTests));
        Assert.AreEqual(4, order.Count);
    }

    [TestMethod]
    public void IsGraphFile_recognises_project_and_build_files()
    {
        Assert.IsTrue(ProjectGraph.IsGraphFile("src/A/A.csproj"));
        Assert.IsTrue(ProjectGraph.IsGraphFile("Directory.Build.props"));
        Assert.IsTrue(ProjectGraph.IsGraphFile("Sample.slnx"));
        Assert.IsFalse(ProjectGraph.IsGraphFile("src/A/A.cs"));
    }
}
