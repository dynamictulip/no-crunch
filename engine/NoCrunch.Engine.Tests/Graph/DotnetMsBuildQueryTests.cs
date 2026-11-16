using NoCrunch.Engine.Build;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Layout;
using NoCrunch.Engine.Tests.TestSupport;
using NoCrunch.Engine.Workspace;

namespace NoCrunch.Engine.Tests.Graph;

[TestClass]
public class DotnetMsBuildQueryTests
{
    [TestMethod]
    [Timeout(180_000)]
    public async Task Query_reads_sample_projects()
    {
        var repo = SampleSolution.CopyToTemp();
        var layout = RepoLayout.Resolve(Path.Combine(repo, "Sample.slnx"));
        var map = new PathMap(layout.RepoRoot, layout.MirrorRoot);
        await new SharedMirrorWorkspace(layout, map).InitializeAsync(CancellationToken.None);
        var query = new DotnetMsBuildQuery(new ProcessRunner(), map, "dotnet");

        var graph = await ProjectGraph.LoadAsync(layout, query, CancellationToken.None);

        Assert.AreEqual(6, graph.Projects.Count);
        var coreTests = graph.Projects.Single(p => p.Name == "Shop.Core.Tests");
        Assert.IsTrue(coreTests.IsMtp);
        Assert.IsTrue(coreTests.IsTestProject);
        CollectionAssert.AreEqual(new[] { "src/Shop.Core/Shop.Core.csproj" }, coreTests.ProjectReferences.ToArray());
        CollectionAssert.Contains(coreTests.ItemFiles.ToArray(), "tests/Shop.Core.Tests/PriceCalculatorTests.cs");
        StringAssert.StartsWith(coreTests.TargetPath, layout.MirrorRoot);
        Assert.IsFalse(graph.Projects.Single(p => p.Name == "Shop.Core").IsTestProject);
    }
}
