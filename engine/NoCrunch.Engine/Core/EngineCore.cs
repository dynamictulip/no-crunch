using NoCrunch.Engine.Build;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Layout;
using NoCrunch.Engine.Testing;
using NoCrunch.Engine.Workspace;

namespace NoCrunch.Engine.Core;

/// <summary>
/// Turns file changes into build + test cycles: debounces bursts, cancels a running cycle when new work arrives
/// (merging its work into the next cycle), runs failing projects first, and persists results.
/// </summary>
public sealed class EngineCore(
    IWorkspaceStrategy workspace,
    Func<CancellationToken, Task<ProjectGraph>> loadGraph,
    IBuildStep build,
    ITestStep tests,
    ResultStore store,
    IEngineEvents events,
    PathMap map,
    string solutionPath) : IAsyncDisposable
{
    public const int ReconcileThreshold = 200;

    private readonly object _lock = new();
    private readonly CancellationTokenSource _stop = new();
    private readonly Dictionary<string, TestResultUpdate> _results = new(StringComparer.Ordinal);
    private readonly HashSet<string> _unhealthy = new(StringComparer.Ordinal);
    private readonly HashSet<string> _builtThisSession = new(StringComparer.Ordinal);
    private Pending _pending = new();
    private TaskCompletionSource _wake = NewSignal();
    private ExclusionSet _exclusions = ExclusionSet.Empty;
    private ProjectGraph? _graph;
    private bool _paused;
    private Task? _loop;
    private TimeSpan? _nextRetry;

    public TimeSpan Debounce { get; set; } = TimeSpan.FromMilliseconds(300);
    public int MaxParallelTestProjects { get; set; } = Math.Max(1, Environment.ProcessorCount / 2);
    /// <summary>First delay before retrying a cycle that failed (e.g. restore could not reach a feed); doubles up to 5 minutes.</summary>
    public TimeSpan RetryDelay { get; set; } = TimeSpan.FromSeconds(15);

    private string MirrorSolutionPath => map.ToMirror(solutionPath);

    public async Task StartAsync(CancellationToken ct)
    {
        var stored = store.Load();
        lock (_lock)
        {
            foreach (var (key, r) in stored) _results[key] = CarryForward(r with { State = TestState.Stale }, r);
            _pending.Reconcile = true;
            _pending.Initialize = true;
            _pending.NeedGraph = true;
            _pending.Restore = true;
            // Once the mirror is up to date, run every (non-excluded) test so results are fresh after a restart.
            _pending.All = true;
        }
        if (stored.Count > 0) await events.TestResults(_results.Values.ToList());
        _loop = Task.Run(LoopAsync, CancellationToken.None);
        Signal();
    }

    public void FilesChanged(IReadOnlyCollection<string> repoPaths)
    {
        lock (_lock)
        {
            _pending.Files.UnionWith(repoPaths);
            if (_pending.Files.Count > ReconcileThreshold) { _pending.Reconcile = true; _pending.Files.Clear(); }
        }
        Signal();
    }

    public void SetExclusions(ExclusionSet set)
    {
        List<TestResultUpdate> nowExcluded;
        List<string> nowIncluded;
        lock (_lock)
        {
            _exclusions = set;
            nowExcluded = _results.Values.Where(r => r.State != TestState.Excluded && set.IsExcluded(r.Id))
                .Select(r => CarryForward(r with { State = TestState.Excluded }, r)).ToList();
            foreach (var r in nowExcluded) _results[r.Id.Key] = r;
            nowIncluded = _results.Values.Where(r => r.State == TestState.Excluded && !set.IsExcluded(r.Id)).Select(r => r.Id.Key).ToList();
        }
        if (nowExcluded.Count > 0) _ = events.TestResults(nowExcluded);
        if (nowIncluded.Count > 0)
        {
            lock (_lock) _pending.RerunKeys.UnionWith(nowIncluded);
            Signal();
        }
    }

    public void RunAll()
    {
        lock (_lock) _pending.All = true;
        Signal();
    }

    /// <summary>Runs the given tests (by <see cref="TestId.Key"/>), even if excluded.</summary>
    public void RunTests(IReadOnlyCollection<string> testKeys)
    {
        lock (_lock) _pending.ManualKeys.UnionWith(testKeys);
        Signal();
    }

    public void Pause()
    {
        lock (_lock) _paused = true;
        Signal();
    }

    public void Resume()
    {
        lock (_lock) _paused = false;
        Signal();
    }

    private int _disposed;

    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _disposed, 1) == 1) return;
        await _stop.CancelAsync();
        if (_loop is not null)
        {
            try { await _loop; } catch (OperationCanceledException) { }
        }
        _stop.Dispose();
    }

    // ---- loop ----------------------------------------------------------------------------------------------

    private static TaskCompletionSource NewSignal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    private void Signal() { lock (_lock) _wake.TrySetResult(); }

    private Task CurrentSignal() { lock (_lock) return _wake.Task; }

    private void ResetSignal() { lock (_lock) if (_wake.Task.IsCompleted) _wake = NewSignal(); }

    private async Task LoopAsync()
    {
        var stop = _stop.Token;
        var announcedPause = false;
        while (!stop.IsCancellationRequested)
        {
            await CurrentSignal().WaitAsync(stop);
            ResetSignal();
            while (true) // debounce: wait for a quiet period
            {
                var next = CurrentSignal();
                if (await Task.WhenAny(next, Task.Delay(Debounce, stop)) != next) break;
                ResetSignal();
            }

            Pending snapshot;
            lock (_lock)
            {
                if (_paused)
                {
                    if (!announcedPause) _ = events.StateChanged(EngineState.Paused, _pending.Projects.Count);
                    announcedPause = true;
                    continue;
                }
                announcedPause = false;
                if (_pending.IsEmpty) continue;
                snapshot = _pending;
                _pending = new Pending();
            }

            using var cycleCts = CancellationTokenSource.CreateLinkedTokenSource(stop);
            var cycle = RunCycleAsync(snapshot, cycleCts.Token);
            var interrupt = CurrentSignal();
            if (await Task.WhenAny(cycle, interrupt) == interrupt && !cycle.IsCompleted)
                await cycleCts.CancelAsync();
            await cycle; // never throws; requeues its work if cancelled
        }
    }

    private async Task RunCycleAsync(Pending snap, CancellationToken ct)
    {
        var dirty = new HashSet<string>(snap.Projects, StringComparer.Ordinal);
        var needGraph = snap.NeedGraph;
        var restore = snap.Restore;
        // Every change copied into the mirror is recorded here as it happens. If this cycle is cancelled or fails,
        // these are handed to the next cycle: the mirror already has them, so a re-sync would not report them again.
        var synced = new HashSet<string>(snap.SyncedRel, StringComparer.Ordinal);
        try
        {
            await events.StateChanged(EngineState.Syncing, 0);
            if (snap.Reconcile) synced.UnionWith(await workspace.ReconcileAsync(ct, synced));
            else if (snap.Files.Count > 0) synced.UnionWith(await workspace.SyncAsync(snap.Files.ToList(), ct, synced));
            if (snap.Initialize) await workspace.InitializeAsync(ct);

            if (_graph is null || synced.Any(ProjectGraph.IsGraphFile)) needGraph = true;
            if (needGraph)
            {
                _graph = await loadGraph(ct);
                restore = true;
                needGraph = false;
            }
            var graph = _graph!;
            if (restore)
            {
                await build.RestoreAsync(MirrorSolutionPath, ct);
                restore = false;
            }

            dirty.UnionWith(graph.DirtyProjects(synced));
            if (snap.All) dirty.UnionWith(graph.Projects.Select(p => p.RepoRelativePath));
            var runKeys = snap.ManualKeys.Concat(snap.RerunKeys).ToHashSet(StringComparer.Ordinal);
            var manualProjects = runKeys.Select(ProjectOfKey).Where(p => graph.Find(p) is not null).ToHashSet(StringComparer.Ordinal);
            var manualOnly = manualProjects.Where(p => !dirty.Contains(p)).ToHashSet(StringComparer.Ordinal);
            dirty.UnionWith(manualProjects);
            if (snap.UnknownTestProjects)
            {
                lock (_lock)
                    dirty.UnionWith(graph.Projects.Where(p => p.IsTestProject && !_results.Values.Any(r => r.Id.Project == p.RepoRelativePath))
                        .Select(p => p.RepoRelativePath));
            }

            if (dirty.Count == 0)
            {
                await events.StateChanged(EngineState.Idle, 0);
                return;
            }

            // Projects that failed earlier get another go, with their dependents; references not yet built
            // this session are included so a project never builds against missing outputs.
            dirty.UnionWith(graph.WithDependents(_unhealthy));
            var closure = graph.WithReferences(dirty);
            dirty.UnionWith(closure.Where(p => _unhealthy.Contains(p) || !_builtThisSession.Contains(p)));

            HashSet<string> previouslyFailing;
            lock (_lock)
                previouslyFailing = _results.Values.Where(r => r.State == TestState.Failed).Select(r => r.Id.Project).ToHashSet(StringComparer.Ordinal);
            await MarkStaleAsync(graph, dirty.Where(p => !manualOnly.Contains(p)).ToHashSet(StringComparer.Ordinal));
            await events.StateChanged(EngineState.Building, dirty.Count);
            var outcomes = await build.BuildAsync(graph.InBuildOrder(dirty),
                p => events.ProjectBuildStarted(p.RepoRelativePath),
                events.ProjectBuildFinished, ct);
            ct.ThrowIfCancellationRequested();
            foreach (var (project, outcome) in outcomes)
            {
                if (outcome.Status == BuildStatus.Succeeded) { _unhealthy.Remove(project); _builtThisSession.Add(project); }
                else if (outcome.Status != BuildStatus.Cancelled) _unhealthy.Add(project);
            }

            var testProjects = graph.InBuildOrder(dirty).Where(p => p.IsTestProject).ToList();
            foreach (var p in testProjects.Where(p => !p.IsMtp))
                await events.ProjectRunFinished(new ProjectRunOutcome(p.RepoRelativePath, ProjectRunStatus.UnsupportedNotMtp,
                    "Not a Microsoft.Testing.Platform test project (VSTest is not supported yet)."));

            var runnable = testProjects
                .Where(p => p.IsMtp && outcomes.GetValueOrDefault(p.RepoRelativePath)?.Status == BuildStatus.Succeeded)
                .Where(p => !graph.WithReferences([p.RepoRelativePath]).Any(_unhealthy.Contains))
                .OrderByDescending(p => previouslyFailing.Contains(p.RepoRelativePath))
                .ThenBy(p => p.RepoRelativePath, StringComparer.Ordinal)
                .ToList();

            await events.StateChanged(EngineState.Testing, runnable.Count);
            if (MaxParallelTestProjects <= 1)
            {
                foreach (var p in runnable) await RunProjectAsync(p, runKeys, snap.ManualKeys, manualOnly.Contains(p.RepoRelativePath), ct);
            }
            else
            {
                await Parallel.ForEachAsync(runnable, new ParallelOptions { MaxDegreeOfParallelism = MaxParallelTestProjects, CancellationToken = ct },
                    async (p, token) => await RunProjectAsync(p, runKeys, snap.ManualKeys, manualOnly.Contains(p.RepoRelativePath), token));
            }
            ct.ThrowIfCancellationRequested();

            List<TestResultUpdate> toSave;
            lock (_lock) toSave = _results.Values.ToList();
            store.Save(toSave);
            _nextRetry = null;
            await events.StateChanged(EngineState.Idle, 0);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            Requeue(snap, dirty, synced, needGraph, restore);
        }
        catch (Exception ex)
        {
            Requeue(snap, dirty, synced, needGraph, restore);
            var delay = _nextRetry ?? RetryDelay;
            _nextRetry = TimeSpan.FromTicks(Math.Min(delay.Ticks * 2, TimeSpan.FromMinutes(5).Ticks));
            await events.Log("error", $"Cycle failed; retrying in {delay.TotalSeconds:0.#}s: " + map.RewriteText(ex.ToString()));
            await events.StateChanged(EngineState.Idle, 0);
            _ = Task.Delay(delay, _stop.Token).ContinueWith(t => { if (!t.IsCanceled) Signal(); }, TaskScheduler.Default);
        }
    }

    /// <summary>Hands everything an interrupted or failed cycle had not finished to the next cycle.</summary>
    private void Requeue(Pending snap, IEnumerable<string> dirty, IEnumerable<string> synced, bool needGraph, bool restore)
    {
        lock (_lock)
        {
            _pending.Projects.UnionWith(dirty);
            _pending.SyncedRel.UnionWith(synced);
            _pending.ManualKeys.UnionWith(snap.ManualKeys);
            _pending.RerunKeys.UnionWith(snap.RerunKeys);
            _pending.NeedGraph |= needGraph;
            _pending.Restore |= restore;
            _pending.All |= snap.All;
            _pending.UnknownTestProjects |= snap.UnknownTestProjects;
            _pending.Initialize |= snap.Initialize;
            // Re-syncing is safe: files already copied report no change, and are carried in SyncedRel instead.
            if (snap.Reconcile) _pending.Reconcile = true;
            else _pending.Files.UnionWith(snap.Files);
        }
    }

    /// <param name="runKeys">Tests to run even if excluded: manual runs plus re-included tests.</param>
    /// <param name="manualKeys">The subset the user asked to run; their results are tagged <see cref="RunKind.Manual"/>.</param>
    private async Task RunProjectAsync(ProjectInfo project, IReadOnlySet<string> runKeys, IReadOnlySet<string> manualKeys,
        bool manualOnly, CancellationToken ct)
    {
        var first = true;
        TestResultUpdate Tag(TestResultUpdate r) => r with { RunKind = manualKeys.Contains(r.Id.Key) ? RunKind.Manual : RunKind.Auto };

        var outcome = await tests.RunAsync(project,
            id =>
            {
                if (runKeys.Contains(id.Key)) return true;
                if (manualOnly) return false;
                lock (_lock) return !_exclusions.IsExcluded(id);
            },
            async raw =>
            {
                if (ct.IsCancellationRequested) return;
                var batch = raw.Select(Tag).ToList();
                if (first)
                {
                    first = false;
                    var discovered = new List<TestResultUpdate>(batch.Count);
                    lock (_lock)
                    {
                        var previous = _results.Values.Where(r => r.Id.Project == project.RepoRelativePath)
                            .ToDictionary(r => r.Id.Key, StringComparer.Ordinal);
                        if (!manualOnly)
                            foreach (var key in previous.Keys) _results.Remove(key);
                        foreach (var r in batch)
                        {
                            var hasPrevious = previous.TryGetValue(r.Id.Key, out var prev);
                            // A manual-only run reports untouched tests as Excluded; keep their previous result instead.
                            if (manualOnly && !runKeys.Contains(r.Id.Key) && hasPrevious) continue;
                            // An automatic run skips excluded tests; keep the result of an earlier manual run of them.
                            var keep = r.State == TestState.Excluded && hasPrevious && prev!.RunKind == RunKind.Manual
                                       && prev.State != TestState.Excluded;
                            var current = keep ? prev! : CarryForward(r, hasPrevious ? prev : null);
                            _results[r.Id.Key] = current;
                            discovered.Add(current);
                        }
                    }
                    if (manualOnly) await events.TestResults(discovered.Where(r => runKeys.Contains(r.Id.Key)).ToList());
                    else await events.TestsDiscovered(project.RepoRelativePath, discovered);
                }
                else
                {
                    List<TestResultUpdate> merged;
                    lock (_lock)
                    {
                        merged = batch.Select(r => CarryForward(r, _results.GetValueOrDefault(r.Id.Key))).ToList();
                        foreach (var r in merged) _results[r.Id.Key] = r;
                    }
                    await events.TestResults(merged);
                }
            }, ct);
        if (!ct.IsCancellationRequested) await events.ProjectRunFinished(outcome);
    }

    private async Task MarkStaleAsync(ProjectGraph graph, IReadOnlySet<string> dirty)
    {
        List<TestResultUpdate> stale;
        lock (_lock)
        {
            stale = _results.Values
                .Where(r => dirty.Contains(r.Id.Project) && r.State is not (TestState.Excluded or TestState.Stale))
                .Select(r => CarryForward(r with { State = TestState.Stale }, r)).ToList();
            foreach (var r in stale) _results[r.Id.Key] = r;
        }
        if (stale.Count > 0) await events.TestResults(stale);
    }

    /// <summary>
    /// While a test is stale, queued, running or excluded it keeps showing its last outcome (and that run's message,
    /// stack trace and duration) so the user can still see what happened last time.
    /// </summary>
    private static TestResultUpdate CarryForward(TestResultUpdate incoming, TestResultUpdate? previous)
    {
        if (incoming.IsOutcome || previous is null) return incoming;
        var last = previous.IsOutcome ? previous.State : previous.LastOutcome;
        if (last is null) return incoming;
        return incoming with
        {
            LastOutcome = last,
            Message = incoming.Message ?? previous.Message,
            StackTrace = incoming.StackTrace ?? previous.StackTrace,
            DurationMs = incoming.DurationMs ?? previous.DurationMs,
            FilePath = incoming.FilePath ?? previous.FilePath,
            Line = incoming.Line ?? previous.Line,
        };
    }

    private static string ProjectOfKey(string key) => key[..key.IndexOf('|')];

    private sealed class Pending
    {
        public HashSet<string> Files { get; } = new(StringComparer.Ordinal);
        /// <summary>Repo-relative paths already copied into the mirror whose projects still need building.</summary>
        public HashSet<string> SyncedRel { get; } = new(StringComparer.Ordinal);
        public HashSet<string> Projects { get; } = new(StringComparer.Ordinal);
        public HashSet<string> ManualKeys { get; } = new(StringComparer.Ordinal);
        /// <summary>Tests to run once as part of normal (automatic) testing, e.g. just re-included.</summary>
        public HashSet<string> RerunKeys { get; } = new(StringComparer.Ordinal);
        public bool Reconcile { get; set; }
        public bool Initialize { get; set; }
        public bool NeedGraph { get; set; }
        public bool Restore { get; set; }
        public bool All { get; set; }
        public bool UnknownTestProjects { get; set; }

        public bool IsEmpty => Files.Count == 0 && SyncedRel.Count == 0 && Projects.Count == 0 && ManualKeys.Count == 0 && RerunKeys.Count == 0
                               && !Reconcile && !Initialize && !NeedGraph && !Restore && !All && !UnknownTestProjects;
    }
}
