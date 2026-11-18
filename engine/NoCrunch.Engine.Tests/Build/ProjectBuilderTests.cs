using NoCrunch.Engine.Build;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Layout;
using NoCrunch.Engine.Tests.Graph;

namespace NoCrunch.Engine.Tests.Build;

[TestClass]
public class ProjectBuilderTests
{
    private const string Core = "src/Core/Core.csproj";
    private const string CoreTests = "tests/Core.Tests/Core.Tests.csproj";
    private const string Other = "tests/Other.Tests/Other.Tests.csproj";

    private sealed class FakeRunner(Func<string, CancellationToken, Task<ProcessResult>> onBuild) : IProcessRunner
    {
        public List<string> Built { get; } = [];

        public Task<ProcessResult> RunAsync(string file, IReadOnlyList<string> args, string workingDir, CancellationToken ct)
        {
            var project = args[1];
            lock (Built) Built.Add(project);
            return onBuild(project, ct);
        }
    }

    private static readonly PathMap Map = new("/repo", "/repo/.nocrunch/S/mirror");

    private static ProjectInfo P(string rel, params string[] refs) =>
        new(rel, Path.GetFileNameWithoutExtension(rel), Map.MirrorPath(rel), false, false, null, refs, []);

    private static readonly ProjectInfo[] Ordered = [P(Core), P(Other), P(CoreTests, Core)];

    [TestMethod]
    public async Task Failed_dependency_blocks_dependents_only()
    {
        var runner = new FakeRunner((proj, _) => Task.FromResult(proj.Contains("/src/Core/")
            ? new ProcessResult(1, "Calc.cs(3,1): error CS1002: ; expected", "")
            : new ProcessResult(0, "", "")));
        var builder = new ProjectBuilder(runner, Map, "dotnet");

        var outcomes = await builder.BuildAsync(Ordered, null, null, CancellationToken.None);

        Assert.AreEqual(BuildStatus.Failed, outcomes[Core].Status);
        StringAssert.Contains(outcomes[Core].Output, "CS1002");
        Assert.AreEqual(BuildStatus.Blocked, outcomes[CoreTests].Status);
        Assert.AreEqual(Core, outcomes[CoreTests].BlockedBy);
        Assert.AreEqual(BuildStatus.Succeeded, outcomes[Other].Status);
        Assert.IsFalse(runner.Built.Any(b => b.Contains("Core.Tests")), "blocked project must not be built");
    }

    [TestMethod]
    public async Task Cancellation_marks_remaining_cancelled()
    {
        using var cts = new CancellationTokenSource();
        var runner = new FakeRunner(async (proj, ct) =>
        {
            if (proj.Contains("Other")) { await cts.CancelAsync(); ct.ThrowIfCancellationRequested(); }
            return new ProcessResult(0, "", "");
        });
        var builder = new ProjectBuilder(runner, Map, "dotnet");

        var outcomes = await builder.BuildAsync(Ordered, null, null, cts.Token);

        Assert.AreEqual(BuildStatus.Succeeded, outcomes[Core].Status);
        Assert.AreEqual(BuildStatus.Cancelled, outcomes[Other].Status);
        Assert.AreEqual(BuildStatus.Cancelled, outcomes[CoreTests].Status);
    }

    [TestMethod]
    public async Task Output_paths_rewritten_to_repo()
    {
        var runner = new FakeRunner((_, _) => Task.FromResult(
            new ProcessResult(1, "/repo/.nocrunch/S/mirror/src/Core/Calc.cs(3,1): error CS1002", "")));
        var builder = new ProjectBuilder(runner, Map, "dotnet");

        var outcomes = await builder.BuildAsync([P(Core)], null, null, CancellationToken.None);

        Assert.AreEqual("/repo/src/Core/Calc.cs(3,1): error CS1002", outcomes[Core].Output.Trim());
    }

    [TestMethod]
    public async Task Callbacks_fire_for_each_project()
    {
        var runner = new FakeRunner((_, _) => Task.FromResult(new ProcessResult(0, "", "")));
        var started = new List<string>();
        var finished = new List<BuildOutcome>();

        await new ProjectBuilder(runner, Map, "dotnet").BuildAsync(Ordered,
            p => { started.Add(p.RepoRelativePath); return Task.CompletedTask; },
            o => { finished.Add(o); return Task.CompletedTask; }, CancellationToken.None);

        CollectionAssert.AreEqual(new[] { Core, Other, CoreTests }, started);
        Assert.AreEqual(3, finished.Count);
    }
}
