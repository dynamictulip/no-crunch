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
            foreach (var (key, r) in stored)
                _results[key] = IsExcludedResult(r)
                    // Excluded tests start every session blank: no result from earlier (manual) runs.
                    ? new TestResultUpdate(r.Id, r.DisplayName, TestState.Stale, null, null, null, r.FilePath, r.Line)
                    : CarryForward(r with { State = TestState.Stale }, r);
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
        // Ignore changes that can't affect builds or tests (.git, bin/obj, git-ignored folders, our own files).
        var tracked = repoPaths.Where(workspace.Tracks).ToList();
        if (tracked.Count == 0) return;
        lock (_lock)
        {
            _pending.Files.UnionWith(tracked);
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
            // A manual result of an excluded test is kept: it's what the user ran by hand.
            nowExcluded = _results.Values.Where(r => r.State != TestState.Excluded && r.RunKind != RunKind.Manual && set.IsExcluded(r.Id))
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
        var announcedWaiting = -1;
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
                    // While paused, "queued" is the number of changed files waiting (re-announced as it grows).
                    var waiting = _pending.WaitingFiles;
                    if (waiting != announcedWaiting) _ = events.StateChanged(EngineState.Paused, waiting);
                    announcedWaiting = waiting;
                    continue;
                }
                announcedWaiting = -1;
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
            // Restore before reading the graph: test settings that come from NuGet packages (e.g. xUnit v3 marking a
            // project as an MTP test app) are only imported once restore has written obj/*.nuget.g.props.
            if (needGraph) restore = true;
            if (restore)
            {
                await build.RestoreAsync(MirrorSolutionPath, ct);
                restore = false;
            }
            if (needGraph)
            {
                _graph = await loadGraph(ct);
                needGraph = false;
            }
            var graph = _graph!;

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
        // Asking for one theory row runs the whole theory: rows share the theory's UID, and only the UID can be run.
        HashSet<string> runUids, manualUids;
        lock (_lock)
        {
            runUids = UidsOf(runKeys);
            manualUids = UidsOf(manualKeys);
        }
        TestResultUpdate Tag(TestResultUpdate r) =>
            r with { RunKind = manualKeys.Contains(r.Id.Key) || manualUids.Contains(r.Id.Uid) ? RunKind.Manual : RunKind.Auto };

        // Theories whose data rows can't be listed up front (e.g. xUnit with non-serialisable data) are discovered once,
        // then report one result per row with the theory's UID. Map UID -> theory key, and collect each theory's rows.
        var theories = new Dictionary<string, string>(StringComparer.Ordinal);
        var rowsSeen = new Dictionary<string, List<TestResultUpdate>>(StringComparer.Ordinal);

        var outcome = await tests.RunAsync(project,
            id =>
            {
                if (runKeys.Contains(id.Key) || runUids.Contains(id.Uid)) return true;
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
                        foreach (var r in batch) theories.TryAdd(r.Id.Uid, r.Id.Key);
                        // Rows from earlier runs follow their theory (queued, excluded...) until the run reports them again.
                        var rows = previous.Values
                            .Where(p => theories.TryGetValue(p.Id.Uid, out var tk) && tk != p.Id.Key)
                            .Select(p => (theory: batch.First(b => b.Id.Key == theories[p.Id.Uid]), row: p))
                            .Select(x => x.row with
                            {
                                State = x.theory.State, RunKind = x.theory.RunKind, TheoryKey = x.theory.Id.Key,
                                DurationMs = null, Message = null, StackTrace = null,
                            });
                        foreach (var r in batch.Concat(rows.ToList()))
                        {
                            var hasPrevious = previous.TryGetValue(r.Id.Key, out var prev);
                            // A manual-only run reports untouched tests as Excluded; keep their previous result instead.
                            if (manualOnly && !runKeys.Contains(r.Id.Key) && !runUids.Contains(r.Id.Uid) && hasPrevious) continue;
                            // An automatic run skips excluded tests; keep the result of an earlier manual run of them.
                            var keep = r.State == TestState.Excluded && hasPrevious && prev!.RunKind == RunKind.Manual
                                       && prev.State != TestState.Excluded;
                            var current = keep ? prev! : CarryForward(r, hasPrevious ? prev : null);
                            _results[r.Id.Key] = current;
                            discovered.Add(current);
                        }
                    }
                    if (manualOnly) await events.TestResults(discovered.Where(r => runKeys.Contains(r.Id.Key) || runUids.Contains(r.Id.Uid)).ToList());
                    else await events.TestsDiscovered(project.RepoRelativePath, discovered);
                }
                else
                {
                    List<TestResultUpdate> merged;
                    lock (_lock)
                    {
                        // Tag theory rows with their theory so clients can nest them (names alone can't tell them apart).
                        merged = batch
                            .Select(r => theories.TryGetValue(r.Id.Uid, out var tk) && tk != r.Id.Key ? r with { TheoryKey = tk } : r)
                            .Select(r => CarryForward(r, _results.GetValueOrDefault(r.Id.Key))).ToList();
                        foreach (var r in merged) _results[r.Id.Key] = r;
                        // A row result means its theory is running; the theory's own result is settled when the run ends.
                        foreach (var r in merged.ToList())
                        {
                            if (r.TheoryKey is not { } tk) continue;
                            if (!rowsSeen.TryGetValue(tk, out var seen)) rowsSeen[tk] = seen = [];
                            seen.RemoveAll(x => x.Id.Key == r.Id.Key);
                            seen.Add(r);
                            if (_results.TryGetValue(tk, out var theory) && theory.State != TestState.Running)
                            {
                                var running = CarryForward(Tag(theory with { State = TestState.Running }), theory);
                                _results[tk] = running;
                                merged.Add(running);
                            }
                        }
                    }
                    await events.TestResults(merged);
                }
            }, ct);
        if (!ct.IsCancellationRequested && outcome.Status == ProjectRunStatus.Completed && rowsSeen.Count > 0)
            await events.TestsDiscovered(project.RepoRelativePath, SettleTheories(project.RepoRelativePath, rowsSeen, Tag));
        if (!ct.IsCancellationRequested) await events.ProjectRunFinished(outcome);
    }

    /// <summary>
    /// Gives each theory the worst result of the rows it just reported, and drops rows it no longer produces.
    /// Returns the project's full result list, which replaces what clients have for it.
    /// </summary>
    private List<TestResultUpdate> SettleTheories(string project, Dictionary<string, List<TestResultUpdate>> rowsSeen,
        Func<TestResultUpdate, TestResultUpdate> tag)
    {
        lock (_lock)
        {
            foreach (var (theoryKey, rows) in rowsSeen)
            {
                if (!_results.TryGetValue(theoryKey, out var theory)) continue;
                var finished = rows.Where(r => r.IsOutcome).ToList();
                var failed = finished.Count(r => r.State == TestState.Failed);
                var state = failed > 0 ? TestState.Failed
                    : finished.Any(r => r.State == TestState.Passed) ? TestState.Passed
                    : finished.Count > 0 ? TestState.Skipped : theory.State;
                _results[theoryKey] = tag(theory with
                {
                    State = state,
                    LastOutcome = null,
                    DurationMs = finished.Sum(r => r.DurationMs ?? 0),
                    Message = failed > 0 ? $"{failed} of {finished.Count} data rows failed" : null,
                    StackTrace = null,
                });
                var current = rows.Select(r => r.Id.Key).ToHashSet(StringComparer.Ordinal);
                var gone = _results.Values
                    .Where(r => r.Id.Project == project && r.Id.Uid == theory.Id.Uid && r.Id.Key != theoryKey && !current.Contains(r.Id.Key))
                    .Select(r => r.Id.Key).ToList();
                foreach (var key in gone) _results.Remove(key);
            }
            return _results.Values.Where(r => r.Id.Project == project).ToList();
        }
    }

    /// <summary>UIDs of the known tests with these keys. Call under <see cref="_lock"/>.</summary>
    private HashSet<string> UidsOf(IReadOnlySet<string> keys) =>
        keys.Count == 0 ? [] : _results.Values.Where(r => keys.Contains(r.Id.Key)).Select(r => r.Id.Uid).ToHashSet(StringComparer.Ordinal);

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

    /// <summary>Stored results that belong to excluded tests: manual runs, or tests last seen as excluded.</summary>
    private static bool IsExcludedResult(TestResultUpdate r) => r.RunKind == RunKind.Manual || r.State == TestState.Excluded;

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

        /// <summary>Changed files not yet built; a pending full reconcile counts as "more than the threshold".</summary>
        public int WaitingFiles => Reconcile ? Math.Max(Files.Count + SyncedRel.Count, ReconcileThreshold + 1) : Files.Count + SyncedRel.Count;

        public bool IsEmpty => Files.Count == 0 && SyncedRel.Count == 0 && Projects.Count == 0 && ManualKeys.Count == 0 && RerunKeys.Count == 0
                               && !Reconcile && !Initialize && !NeedGraph && !Restore && !All && !UnknownTestProjects;
    }
}
