using System.Text.Json;
using NoCrunch.Engine.Build;
using NoCrunch.Engine.Core;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Layout;
using NoCrunch.Engine.Protocol;
using NoCrunch.Engine.Testing;
using NoCrunch.Engine.Workspace;

namespace NoCrunch.Engine.Tests.Protocol;

[TestClass]
public class EngineServerTests
{
    private static readonly InitializeParams Params = new("/repo/S.slnx", null, 0, 0);
    private static readonly InitializeResult Result = new(EngineServer.ProtocolVersion, "/repo", "/repo/.nocrunch/S/mirror");

    /// <summary>A workspace that parks the engine loop until it is stopped.</summary>
    private sealed class ParkedWorkspace : IWorkspaceStrategy
    {
        public async Task InitializeAsync(CancellationToken ct) => await Task.Delay(Timeout.Infinite, ct);
        public Task<IReadOnlyCollection<string>> SyncAsync(IReadOnlyCollection<string> changedRepoPaths, CancellationToken ct, ISet<string>? progress = null) =>
            Task.FromResult<IReadOnlyCollection<string>>([]);
        public Task<IReadOnlyCollection<string>> ReconcileAsync(CancellationToken ct, ISet<string>? progress = null) =>
            Task.FromResult<IReadOnlyCollection<string>>([]);
        public string MirrorPathOf(string repoRelativePath) => "/m/" + repoRelativePath;
        public bool Tracks(string repoPath) => true;
    }

    private sealed class Unused : IBuildStep, ITestStep
    {
        public Task RestoreAsync(string mirrorSolutionPath, CancellationToken ct) => throw new NotSupportedException();
        public Task<IReadOnlyDictionary<string, BuildOutcome>> BuildAsync(IReadOnlyList<ProjectInfo> inOrder,
            Func<ProjectInfo, Task>? onStarted, Func<BuildOutcome, Task>? onFinished, CancellationToken ct) => throw new NotSupportedException();
        public Task<ProjectRunOutcome> RunAsync(ProjectInfo project, Func<TestId, bool> shouldRun,
            Func<IReadOnlyList<TestResultUpdate>, Task> onUpdates, CancellationToken ct) => throw new NotSupportedException();
    }

    private sealed class Events(bool failResults) : IEngineEvents
    {
        public Task StateChanged(EngineState state, int queuedProjects) => Task.CompletedTask;
        public Task ProjectBuildStarted(string project) => Task.CompletedTask;
        public Task ProjectBuildFinished(BuildOutcome outcome) => Task.CompletedTask;
        public Task TestsDiscovered(string project, IReadOnlyList<TestResultUpdate> tests) => Task.CompletedTask;
        public Task TestResults(IReadOnlyList<TestResultUpdate> updates) =>
            failResults ? throw new IOException("client went away") : Task.CompletedTask;
        public Task ProjectRunFinished(ProjectRunOutcome outcome) => Task.CompletedTask;
        public Task Log(string level, string message) => Task.CompletedTask;
    }

    /// <summary>A core with fake parts. When <paramref name="failStart"/>, its StartAsync throws (it replays a stored result to events that fail).</summary>
    private static EngineCore NewCore(bool failStart)
    {
        var dir = Path.Combine(Path.GetTempPath(), "nocrunch tests", Guid.NewGuid().ToString("N")[..8]);
        var store = new ResultStore(Path.Combine(dir, "results.json"));
        if (failStart)
            store.Save([new TestResultUpdate(new TestId("p", "T", "M", "u"), "M", TestState.Passed, 1, null, null, null, null)]);
        var unused = new Unused();
        return new EngineCore(new ParkedWorkspace(), _ => throw new NotSupportedException(), unused, unused, store,
            new Events(failStart), new PathMap("/repo", "/repo/.nocrunch/S/mirror"), "/repo/S.slnx");
    }

    [TestMethod]
    public async Task Initialize_can_be_retried_after_the_core_fails_to_start()
    {
        var attempts = 0;
        await using var server = new EngineServer((_, _) => (NewCore(failStart: ++attempts == 1), Result));

        await Assert.ThrowsExactlyAsync<IOException>(() => server.InitializeAsync(Params));
        var result = await server.InitializeAsync(Params);

        Assert.AreEqual(2, attempts);
        Assert.AreEqual(Result, result);
    }

    [TestMethod]
    public async Task Initialize_twice_is_rejected_after_success()
    {
        await using var server = new EngineServer((_, _) => (NewCore(failStart: false), Result));

        await server.InitializeAsync(Params);
        var ex = await Assert.ThrowsExactlyAsync<InvalidOperationException>(() => server.InitializeAsync(Params));
        StringAssert.Contains(ex.Message, "Already initialized");
    }

    [TestMethod]
    [Timeout(30_000)]
    public async Task Concurrent_initialize_calls_create_one_core()
    {
        var created = 0;
        using var gate = new ManualResetEventSlim();
        await using var server = new EngineServer((_, _) =>
        {
            Interlocked.Increment(ref created);
            gate.Wait(TimeSpan.FromSeconds(10));
            return (NewCore(failStart: false), Result);
        });

        var first = Task.Run(() => server.InitializeAsync(Params));
        var second = Task.Run(() => server.InitializeAsync(Params));
        await Task.Delay(300);
        gate.Set();
        var outcomes = await Task.WhenAll(Settle(first), Settle(second));

        Assert.AreEqual(1, created);
        Assert.AreEqual(1, outcomes.Count(o => o is null), "one call succeeds");
        Assert.AreEqual(1, outcomes.Count(o => o is InvalidOperationException), "the other is told it's already initialized");
    }

    private static async Task<Exception?> Settle(Task t)
    {
        try { await t; return null; }
        catch (Exception e) { return e; }
    }

    [TestMethod]
    public async Task Notifications_before_initialize_are_ignored()
    {
        await using var server = new EngineServer();

        server.FilesChanged(new FilesChangedParams(["/repo/a.cs"]));
        server.SetExclusions(new SetExclusionsParams([]));
        server.RunAll();
        server.RunTests(new RunTestsParams(["k"]));
        server.Pause();
        server.Resume();
    }

    [TestMethod]
    public void Test_timeout_is_30_seconds_when_absent_and_none_when_zero()
    {
        var options = EngineServer.CreateFormatter().JsonSerializerOptions;
        var absent = JsonSerializer.Deserialize<InitializeParams>("""{"solutionPath":"/s.slnx","maxParallel":0,"debounceMs":0}""", options)!;
        var zero = JsonSerializer.Deserialize<InitializeParams>("""{"solutionPath":"/s.slnx","maxParallel":0,"debounceMs":0,"testTimeoutSeconds":0}""", options)!;
        var five = JsonSerializer.Deserialize<InitializeParams>("""{"solutionPath":"/s.slnx","maxParallel":0,"debounceMs":0,"testTimeoutSeconds":5}""", options)!;

        Assert.AreEqual(TimeSpan.FromSeconds(30), EngineServer.TestTimeout(absent));
        Assert.IsNull(EngineServer.TestTimeout(zero));
        Assert.AreEqual(TimeSpan.FromSeconds(5), EngineServer.TestTimeout(five));
    }
}
