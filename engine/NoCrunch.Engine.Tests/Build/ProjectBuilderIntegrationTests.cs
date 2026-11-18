using NoCrunch.Engine.Build;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Layout;
using NoCrunch.Engine.Tests.TestSupport;
using NoCrunch.Engine.Workspace;

namespace NoCrunch.Engine.Tests.Build;

[TestClass]
public class ProjectBuilderIntegrationTests
{
    [TestMethod]
    [Timeout(300_000)]
    public async Task Builds_sample_in_mirror_without_touching_repo_bin()
    {
        var mirror = await MirroredSample.CreateAsync();
        var builder = new ProjectBuilder(new ProcessRunner(), mirror.Map, "dotnet");
        await builder.RestoreAsync(mirror.Map.ToMirror(mirror.Layout.SolutionPath), CancellationToken.None);

        var order = mirror.Graph.InBuildOrder(mirror.Graph.Projects.Select(p => p.RepoRelativePath));
        var outcomes = await builder.BuildAsync(order, null, null, CancellationToken.None);

        Assert.IsTrue(outcomes.Values.All(o => o.Status == BuildStatus.Succeeded),
            string.Join("\n", outcomes.Values.Select(o => $"{o.Project}: {o.Status} {o.Output}")));
        Assert.IsTrue(Directory.Exists(Path.Combine(mirror.Layout.MirrorRoot, "tests/Shop.Core.Tests/bin")));
        Assert.IsFalse(Directory.Exists(Path.Combine(mirror.Layout.RepoRoot, "tests/Shop.Core.Tests/bin")));
        Assert.IsTrue(File.Exists(mirror.Graph.Projects.Single(p => p.Name == "Shop.Core.Tests").TargetPath));
    }
}
