using NoCrunch.Engine.Build;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Layout;
using NoCrunch.Engine.Workspace;

namespace NoCrunch.Engine.Tests.TestSupport;

/// <summary>A temp copy of the sample with its mirror initialised and graph loaded.</summary>
internal sealed record MirroredSample(RepoLayout Layout, PathMap Map, SharedMirrorWorkspace Workspace, ProjectGraph Graph)
{
    public static async Task<MirroredSample> CreateAsync()
    {
        var repo = SampleSolution.CopyToTemp();
        var layout = RepoLayout.Resolve(Path.Combine(repo, "Sample.slnx"));
        var map = new PathMap(layout.RepoRoot, layout.MirrorRoot);
        var ws = new SharedMirrorWorkspace(layout, map);
        await ws.InitializeAsync(CancellationToken.None);
        var graph = await ProjectGraph.LoadAsync(layout, new DotnetMsBuildQuery(new ProcessRunner(), map, "dotnet"), CancellationToken.None);
        return new MirroredSample(layout, map, ws, graph);
    }

    /// <summary>Restores and builds every project in the mirror.</summary>
    public async Task BuildAllAsync()
    {
        var builder = new ProjectBuilder(new ProcessRunner(), Map, "dotnet");
        await builder.RestoreAsync(Map.ToMirror(Layout.SolutionPath), CancellationToken.None);
        var outcomes = await builder.BuildAsync(Graph.InBuildOrder(Graph.Projects.Select(p => p.RepoRelativePath)), null, null, CancellationToken.None);
        var bad = outcomes.Values.Where(o => o.Status != BuildStatus.Succeeded).ToList();
        if (bad.Count > 0) throw new InvalidOperationException(string.Join("\n", bad.Select(o => o.Project + ": " + o.Output)));
    }
}
