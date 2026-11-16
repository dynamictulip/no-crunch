using System.Collections.Concurrent;
using NoCrunch.Engine.Build;
using NoCrunch.Engine.Core;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Layout;
using NoCrunch.Engine.Testing;
using NoCrunch.Engine.Tests.Graph;
using NoCrunch.Engine.Tests.Layout;
using NoCrunch.Engine.Workspace;

namespace NoCrunch.Engine.Tests.Core;

[TestClass]
public class EngineCoreTests
{
    private const string Core = "src/Core/Core.csproj";
    private const string CoreTests = "tests/Core.Tests/Core.Tests.csproj";
    private const string Other = "tests/Other.Tests/Other.Tests.csproj";
    private const string Legacy = "tests/Legacy.Tests/Legacy.Tests.csproj";
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(10);

    private sealed class FakeWorkspace : IWorkspaceStrategy
    {
        public ConcurrentQueue<string> Calls { get; } = new();
        public Func<IReadOnlyCollection<string>, IReadOnlyCollection<string>> OnSync { get; set; } = paths => paths.ToList();
        public IReadOnlyCollection<string> ReconcileResult { get; set; } = [];
        /// <summary>Runs after changes are applied (and reported to the progress sink) but before Sync returns.</summary>
        public Func<CancellationToken, Task> SyncGate { get; set; } = _ => Task.CompletedTask;
        private readonly HashSet<string> _alreadyInMirror = [];

        public Task InitializeAsync(CancellationToken ct) { Calls.Enqueue("init"); return Task.CompletedTask; }

        public async Task<IReadOnlyCollection<string>> SyncAsync(IReadOnlyCollection<string> paths, CancellationToken ct, ISet<string>? progress = null)
        {
            Calls.Enqueue("sync:" + string.Join(",", paths.Order()));
            // Like the real mirror: a file already copied with the same content is not reported again.
            var changed = OnSync(paths).Where(p => { lock (_alreadyInMirror) return _alreadyInMirror.Add(p); }).ToList();
            foreach (var c in changed) progress?.Add(c);
            await SyncGate(ct);
            return changed;
        }

        public Task<IReadOnlyCollection<string>> ReconcileAsync(CancellationToken ct, ISet<string>? progress = null)
        {
            Calls.Enqueue("reconcile");
            foreach (var c in ReconcileResult) progress?.Add(c);
            return Task.FromResult(ReconcileResult);
        }

        public string MirrorPathOf(string repoRelativePath) => "/m/" + repoRelativePath;
    }

    private sealed class FakeBuild : IBuildStep
    {
        public ConcurrentQueue<string[]> Builds { get; } = new();
        public int Restores;
        public int RestoreFailuresRemaining;
        public Func<CancellationToken, Task> Gate { get; set; } = _ => Task.CompletedTask;
        public HashSet<string> Failing { get; } = [];

        public Task RestoreAsync(string mirrorSolutionPath, CancellationToken ct)
        {
            Interlocked.Increment(ref Restores);
            if (Interlocked.Decrement(ref RestoreFailuresRemaining) >= 0) throw new InvalidOperationException("Restore failed: feed unreachable");
            return Task.CompletedTask;
        }

        public async Task<IReadOnlyDictionary<string, BuildOutcome>> BuildAsync(IReadOnlyList<ProjectInfo> inOrder,
            Func<ProjectInfo, Task>? onStarted, Func<BuildOutcome, Task>? onFinished, CancellationToken ct)
        {
            Builds.Enqueue(inOrder.Select(p => p.RepoRelativePath).ToArray());
            var result = new Dictionary<string, BuildOutcome>();
            try { await Gate(ct); }
            catch (OperationCanceledException)
            {
                foreach (var p in inOrder) result[p.RepoRelativePath] = new BuildOutcome(p.RepoRelativePath, BuildStatus.Cancelled, "", null);
                return result;
            }
            foreach (var p in inOrder)
            {
                var o = new BuildOutcome(p.RepoRelativePath, Failing.Contains(p.RepoRelativePath) ? BuildStatus.Failed : BuildStatus.Succeeded, "", null);
                result[p.RepoRelativePath] = o;
                if (onFinished is not null) await onFinished(o);
            }
            return result;
        }
    }

    private sealed class FakeTests : ITestStep
    {
        public ConcurrentQueue<string> Runs { get; } = new();
        public Dictionary<string, (string method, TestState state)[]> Tests { get; } = new()
        {
            [CoreTests] = [("A", TestState.Passed), ("B", TestState.Passed)],
            [Other] = [("O", TestState.Passed)],
        };
        public ConcurrentBag<string> Executed { get; } = [];

        public async Task<ProjectRunOutcome> RunAsync(ProjectInfo project, Func<TestId, bool> shouldRun,
            Func<IReadOnlyList<TestResultUpdate>, Task> onUpdates, CancellationToken ct)
        {
            Runs.Enqueue(project.RepoRelativePath);
            var ids = Tests[project.RepoRelativePath].Select(t => (id: new TestId(project.RepoRelativePath, "T", t.method, t.method), t.state)).ToList();
            await onUpdates(ids.Select(t => U(t.id, shouldRun(t.id) ? TestState.Queued : TestState.Excluded)).ToList());
            var ran = ids.Where(t => shouldRun(t.id)).ToList();
            foreach (var t in ran) Executed.Add(t.id.Key);
            if (ran.Count > 0) await onUpdates(ran.Select(t => U(t.id, t.state)).ToList());
            return new ProjectRunOutcome(project.RepoRelativePath, ProjectRunStatus.Completed, null);
        }
    }

    private sealed class Recorder : IEngineEvents
    {
        public ConcurrentQueue<string> Log { get; } = new();
        public ConcurrentQueue<TestResultUpdate> Results { get; } = new();
        public ConcurrentQueue<ProjectRunOutcome> RunsFinished { get; } = new();
        public ConcurrentQueue<(string project, int count)> Discoveries { get; } = new();
        private int _idle;
        public SemaphoreSlim IdleSignal { get; } = new(0);

        public Task StateChanged(EngineState state, int queuedProjects)
        {
            Log.Enqueue("state:" + state);
            if (state == EngineState.Idle) { Interlocked.Increment(ref _idle); IdleSignal.Release(); }
            return Task.CompletedTask;
        }
        public Task ProjectBuildStarted(string project) => Task.CompletedTask;
        public Task ProjectBuildFinished(BuildOutcome outcome) { Log.Enqueue("built:" + outcome.Project + ":" + outcome.Status); return Task.CompletedTask; }
        public Task TestsDiscovered(string project, IReadOnlyList<TestResultUpdate> tests)
        {
            Discoveries.Enqueue((project, tests.Count));
            foreach (var t in tests) Results.Enqueue(t);
            return Task.CompletedTask;
        }
        public Task TestResults(IReadOnlyList<TestResultUpdate> updates) { foreach (var u in updates) Results.Enqueue(u); return Task.CompletedTask; }
        public Task ProjectRunFinished(ProjectRunOutcome outcome) { RunsFinished.Enqueue(outcome); return Task.CompletedTask; }
        public Task Log_(string level, string message) => Task.CompletedTask;
        Task IEngineEvents.Log(string level, string message) => Task.CompletedTask;

        public async Task WaitIdleAsync()
        {
            if (!await IdleSignal.WaitAsync(Wait)) Assert.Fail("Engine did not become idle. Log: " + string.Join(" | ", Log));
        }
    }

    private static TestResultUpdate U(TestId id, TestState s) => new(id, id.Method ?? id.Uid, s, null, null, null, null, null);

    private sealed record Harness(EngineCore Core, FakeWorkspace Ws, FakeBuild Build, FakeTests Tests, Recorder Events, string Root, ResultStore Store, GraphGate Graph);

    private sealed class GraphGate
    {
        public Func<CancellationToken, Task> Gate { get; set; } = _ => Task.CompletedTask;
    }

    private static async Task<Harness> CreateAsync(Action<FakeBuild>? configureBuild = null, ResultStore? store = null, bool start = true)
    {
        var root = TempDir.Create();
        File.WriteAllText(Path.Combine(root, "S.slnx"), "");
        var layout = RepoLayout.Resolve(Path.Combine(root, "S.slnx"));
        var query = new ProjectGraphTests.FakeQuery(
            ProjectGraphTests.P(Core, items: ["src/Core/Calc.cs"]),
            ProjectGraphTests.P(CoreTests, test: true, refs: [Core], items: ["tests/Core.Tests/CalcTests.cs"]),
            ProjectGraphTests.P(Other, test: true, items: ["tests/Other.Tests/OtherTests.cs"]),
            ProjectGraphTests.P(Legacy, test: true) with { IsMtp = false });
        var ws = new FakeWorkspace { OnSync = paths => paths.Select(p => Path.GetRelativePath(root, p).Replace('\\', '/')).ToList() };
        var build = new FakeBuild();
        configureBuild?.Invoke(build);
        var tests = new FakeTests();
        var events = new Recorder();
        store ??= new ResultStore(Path.Combine(layout.StateDir, "results.json"));
        var gate = new GraphGate();
        var core = new EngineCore(ws, async ct => { await gate.Gate(ct); return await ProjectGraph.LoadAsync(layout, query, ct); }, build, tests, store, events,
            new PathMap(layout.RepoRoot, layout.MirrorRoot), layout.SolutionPath)
        {
            Debounce = TimeSpan.FromMilliseconds(80),
            MaxParallelTestProjects = 2,
            RetryDelay = TimeSpan.FromMilliseconds(200),
        };
        var h = new Harness(core, ws, build, tests, events, root, store, gate);
        if (start)
        {
            await core.StartAsync(CancellationToken.None);
            await events.WaitIdleAsync();
        }
        return h;
    }

    private static void Drain(Harness h)
    {
        while (h.Build.Builds.TryDequeue(out _)) { }
        while (h.Tests.Runs.TryDequeue(out _)) { }
        while (h.Ws.Calls.TryDequeue(out _)) { }
        while (h.Events.Results.TryDequeue(out _)) { }
        h.Tests.Executed.Clear();
    }

    private string Abs(Harness h, string rel) => Path.Combine(h.Root, rel);

    [TestMethod]
    public async Task Startup_emits_stale_then_runs_all_tests()
    {
        var root = TempDir.Create();
        var store = new ResultStore(Path.Combine(root, "results.json"));
        var old = U(new TestId(Other, "T", "O", "O"), TestState.Passed);
        store.Save([old]);

        var h = await CreateAsync(store: store);

        var first = h.Events.Results.First(r => r.Id.Key == old.Id.Key);
        Assert.AreEqual(TestState.Stale, first.State, "stored results are shown stale first");
        CollectionAssert.Contains(h.Tests.Runs.ToArray(), CoreTests, "test project without stored results runs");
        CollectionAssert.Contains(h.Tests.Runs.ToArray(), Other, "every test project runs once startup has finished");
        Assert.AreEqual(TestState.Passed, h.Events.Results.Last(r => r.Id.Key == old.Id.Key).State, "stale result is replaced");
        Assert.AreEqual(1, h.Build.Restores);
        Assert.IsTrue(h.Ws.Calls.Contains("reconcile"));
    }

    [TestMethod]
    public async Task Debounces_bursts_into_one_cycle()
    {
        var h = await CreateAsync();
        Drain(h);

        h.Core.FilesChanged([Abs(h, "src/Core/Calc.cs")]);
        await Task.Delay(20);
        h.Core.FilesChanged([Abs(h, "tests/Core.Tests/CalcTests.cs")]);
        await h.Events.WaitIdleAsync();

        Assert.AreEqual(1, h.Build.Builds.Count);
        CollectionAssert.AreEqual(new[] { Core, CoreTests }, h.Build.Builds.Single());
    }

    [TestMethod]
    public async Task Change_during_cycle_cancels_and_unions()
    {
        var h = await CreateAsync();
        Drain(h);
        var firstBuildStarted = new TaskCompletionSource();
        var cancelledFirst = new TaskCompletionSource();
        var calls = 0;
        h.Build.Gate = async ct =>
        {
            if (Interlocked.Increment(ref calls) != 1) return;
            firstBuildStarted.SetResult();
            try { await Task.Delay(Timeout.Infinite, ct); }
            catch (OperationCanceledException) { cancelledFirst.SetResult(); throw; }
        };

        h.Core.FilesChanged([Abs(h, "tests/Other.Tests/OtherTests.cs")]);
        await firstBuildStarted.Task.WaitAsync(Wait);
        h.Core.FilesChanged([Abs(h, "src/Core/Calc.cs")]);
        await cancelledFirst.Task.WaitAsync(Wait);
        await h.Events.WaitIdleAsync();

        var builds = h.Build.Builds.ToArray();
        Assert.AreEqual(2, builds.Length);
        CollectionAssert.AreEquivalent(new[] { Core, CoreTests, Other }, builds[1]);
        Assert.AreEqual(1, h.Tests.Runs.Count(r => r == Other), "Other runs once, in the second cycle");
    }

    [TestMethod]
    public async Task Burst_over_200_files_triggers_reconcile_not_sync()
    {
        var h = await CreateAsync();
        Drain(h);
        h.Ws.ReconcileResult = ["src/Core/Calc.cs"];

        h.Core.FilesChanged(Enumerable.Range(0, 201).Select(i => Abs(h, $"src/Core/F{i}.cs")).ToList());
        await h.Events.WaitIdleAsync();

        CollectionAssert.Contains(h.Ws.Calls.ToArray(), "reconcile");
        Assert.IsFalse(h.Ws.Calls.Any(c => c.StartsWith("sync:")));
        CollectionAssert.AreEqual(new[] { Core, CoreTests }, h.Build.Builds.Single());
    }

    [TestMethod]
    public async Task Graph_file_change_reloads_graph_and_restores()
    {
        var h = await CreateAsync();
        Drain(h);
        var restoresBefore = h.Build.Restores;

        h.Core.FilesChanged([Abs(h, "src/Core/Core.csproj")]);
        await h.Events.WaitIdleAsync();

        Assert.AreEqual(restoresBefore + 1, h.Build.Restores);
        CollectionAssert.AreEqual(new[] { Core, CoreTests }, h.Build.Builds.Single());
    }

    [TestMethod]
    public async Task Previously_failing_projects_run_first()
    {
        var h = await CreateAsync();
        h.Tests.Tests[Other] = [("O", TestState.Failed)];
        h.Core.RunAll();
        await h.Events.WaitIdleAsync();
        h.Core.MaxParallelTestProjects = 1;
        Drain(h);

        h.Core.RunAll();
        await h.Events.WaitIdleAsync();

        Assert.AreEqual(Other, h.Tests.Runs.First());
    }

    [TestMethod]
    public async Task Excluded_tests_not_run_but_RunTests_runs_them()
    {
        var h = await CreateAsync();
        h.Core.SetExclusions(new ExclusionSet([new ExclusionRule(CoreTests, "T", "A", null)]));
        Drain(h);

        h.Core.FilesChanged([Abs(h, "src/Core/Calc.cs")]);
        await h.Events.WaitIdleAsync();
        CollectionAssert.AreEquivalent(new[] { $"{CoreTests}|T|B" }, h.Tests.Executed.ToArray());
        Assert.AreEqual(TestState.Excluded, h.Events.Results.Last(r => r.Id.Method == "A").State);

        Drain(h);
        while (h.Events.Discoveries.TryDequeue(out _)) { }
        h.Core.RunTests([$"{CoreTests}|T|A"]);
        await h.Events.WaitIdleAsync();
        CollectionAssert.AreEquivalent(new[] { $"{CoreTests}|T|A" }, h.Tests.Executed.ToArray());
        Assert.AreEqual(0, h.Events.Discoveries.Count, "a manual-only run must not replace the project's test list");
    }

    [TestMethod]
    public async Task Paused_queues_changes_and_Resume_runs_them()
    {
        var h = await CreateAsync();
        Drain(h);

        h.Core.Pause();
        h.Core.FilesChanged([Abs(h, "src/Core/Calc.cs")]);
        await Task.Delay(300);
        Assert.AreEqual(0, h.Build.Builds.Count);
        CollectionAssert.Contains(h.Events.Log.ToArray(), "state:Paused");

        h.Core.Resume();
        await h.Events.WaitIdleAsync();
        CollectionAssert.AreEqual(new[] { Core, CoreTests }, h.Build.Builds.Single());
    }

    [TestMethod]
    public async Task Non_mtp_test_project_reported_unsupported()
    {
        var h = await CreateAsync();
        var legacy = h.Events.RunsFinished.Single(r => r.Project == Legacy);
        Assert.AreEqual(ProjectRunStatus.UnsupportedNotMtp, legacy.Status);
        Assert.IsFalse(h.Tests.Runs.Contains(Legacy));
    }

    [TestMethod]
    public async Task Failed_build_reruns_with_next_cycle_and_skips_tests()
    {
        var h = await CreateAsync(b => b.Failing.Add(Core));
        Assert.IsFalse(h.Tests.Runs.Contains(CoreTests), "tests of a failed dependency are not run");
        h.Build.Failing.Clear();
        Drain(h);

        h.Core.FilesChanged([Abs(h, "tests/Other.Tests/OtherTests.cs")]);
        await h.Events.WaitIdleAsync();

        CollectionAssert.Contains(h.Build.Builds.Single(), Core, "previously failed project is rebuilt");
    }

    [TestMethod]
    public async Task Cancel_during_graph_load_keeps_synced_changes()
    {
        var h = await CreateAsync();
        Drain(h);
        var graphStarted = new TaskCompletionSource();
        var calls = 0;
        h.Graph.Gate = async ct =>
        {
            if (Interlocked.Increment(ref calls) != 1) return;
            graphStarted.SetResult();
            await Task.Delay(Timeout.Infinite, ct);
        };

        h.Core.FilesChanged([Abs(h, "src/Core/Core.csproj")]);
        await graphStarted.Task.WaitAsync(Wait);
        h.Core.FilesChanged([Abs(h, "tests/Other.Tests/OtherTests.cs")]);
        await h.Events.WaitIdleAsync();

        CollectionAssert.AreEquivalent(new[] { Core, CoreTests, Other }, h.Build.Builds.Single());
    }

    [TestMethod]
    public async Task Cancel_during_sync_keeps_files_already_copied()
    {
        var h = await CreateAsync();
        Drain(h);
        var syncStarted = new TaskCompletionSource();
        var calls = 0;
        h.Ws.SyncGate = async ct =>
        {
            if (Interlocked.Increment(ref calls) != 1) return;
            syncStarted.SetResult();
            await Task.Delay(Timeout.Infinite, ct);
        };

        h.Core.FilesChanged([Abs(h, "src/Core/Calc.cs")]);
        await syncStarted.Task.WaitAsync(Wait);
        h.Core.FilesChanged([Abs(h, "tests/Other.Tests/OtherTests.cs")]);
        await h.Events.WaitIdleAsync();

        CollectionAssert.AreEquivalent(new[] { Core, CoreTests, Other }, h.Build.Builds.Single());
    }

    [TestMethod]
    public async Task Failed_restore_keeps_changes_and_is_retried()
    {
        var h = await CreateAsync();
        Drain(h);
        h.Build.RestoreFailuresRemaining = 1;

        h.Core.FilesChanged([Abs(h, "src/Core/Core.csproj")]);
        var deadline = DateTime.UtcNow + Wait;
        while (h.Build.Builds.IsEmpty && DateTime.UtcNow < deadline) await Task.Delay(50);

        Assert.IsTrue(h.Build.Restores >= 3, $"restore retried (startup + failed + retry), was {h.Build.Restores}");
        CollectionAssert.AreEquivalent(new[] { Core, CoreTests }, h.Build.Builds.Single());
    }

    [TestMethod]
    public async Task Manual_run_keeps_other_results_in_the_project()
    {
        var h = await CreateAsync();
        Drain(h);

        h.Core.RunTests([$"{CoreTests}|T|A"]);
        await h.Events.WaitIdleAsync();

        Assert.IsFalse(h.Events.Results.Any(r => r.Id.Method == "B" && r.State == TestState.Stale), "untouched test must not go stale");
        Assert.AreEqual(TestState.Passed, h.Store.Load()[$"{CoreTests}|T|B"].State);
    }

    [TestMethod]
    public async Task Manual_results_are_marked_and_survive_automatic_runs()
    {
        var h = await CreateAsync();
        h.Core.SetExclusions(new ExclusionSet([new ExclusionRule(CoreTests, "T", "A", null)]));
        h.Core.RunTests([$"{CoreTests}|T|A"]);
        await h.Events.WaitIdleAsync();

        var manual = h.Events.Results.Last(r => r.Id.Method == "A");
        Assert.AreEqual(TestState.Passed, manual.State);
        Assert.AreEqual(RunKind.Manual, manual.RunKind);

        h.Core.FilesChanged([Abs(h, "src/Core/Calc.cs")]);
        await h.Events.WaitIdleAsync();

        var afterAuto = h.Store.Load()[$"{CoreTests}|T|A"];
        Assert.AreEqual(RunKind.Manual, afterAuto.RunKind, "an automatic run must not replace a manual result");
        Assert.AreEqual(TestState.Stale, afterAuto.State, "the code changed since the manual run");
        Assert.AreEqual(RunKind.Auto, h.Store.Load()[$"{CoreTests}|T|B"].RunKind);
    }

    [TestMethod]
    public async Task Dispose_twice_does_not_throw()
    {
        var h = await CreateAsync();
        await h.Core.DisposeAsync();
        await h.Core.DisposeAsync();
    }

    [TestMethod]
    public async Task Results_persisted_after_cycle()
    {
        var h = await CreateAsync();
        var saved = h.Store.Load();
        Assert.AreEqual(TestState.Passed, saved[$"{CoreTests}|T|A"].State);
    }
}
