using NoCrunch.Engine.Build;
using NoCrunch.Engine.Coverage;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Layout;
using NoCrunch.Engine.Testing;
using NoCrunch.Engine.Workspace;

namespace NoCrunch.Engine.Core;

/// <summary>
/// Turns file changes into build + test cycles: debounces bursts, cancels a running cycle when new work arrives
/// (merging its work into the next cycle), runs failing projects first, and persists results.
/// What a cycle builds and tests is worked out by <see cref="CyclePlanner"/>; test results are kept by <see cref="ResultBook"/>.
/// </summary>
public sealed class EngineCore(
    IWorkspaceStrategy workspace,
    Func<CancellationToken, Task<ProjectGraph>> loadGraph,
    IBuildStep build,
    ITestStep tests,
    ResultStore store,
    IEngineEvents events,
    PathMap map,
    string solutionPath,
    ICoverageRunFactory? coverageRuns = null,
    CoverageStore? coverageStore = null) : IAsyncDisposable
{
    public const int ReconcileThreshold = 200;

    private readonly object _lock = new();
    private readonly CancellationTokenSource _stop = new();
    private readonly ResultBook _results = new();
    private readonly CoverageBook _coverage = new();
    /// <summary>Coverage restored from disk is sent once the first cycle has loaded the project graph (it is needed to place not-covered lines).</summary>
    private bool _coverageRestorePending;
    /// <summary>Held while the complete coverage set is computed and sent, so an older set never arrives after a newer one.</summary>
    private readonly SemaphoreSlim _coverageSend = new(1, 1);
    private readonly HashSet<string> _unhealthy = new(StringComparer.Ordinal);
    private readonly HashSet<string> _builtThisSession = new(StringComparer.Ordinal);
    private Pending _pending = new();
    private TaskCompletionSource _wake = NewSignal();
    private ExclusionSet _exclusions = ExclusionSet.Empty;
    private ProjectGraph? _graph;
    /// <summary>Projects the running cycle builds, tests or builds against; null until it has worked them out.</summary>
    private IReadOnlySet<string>? _cycleProjects;
    private bool _paused;
    private Task? _loop;
    private TimeSpan? _nextRetry;

    public TimeSpan Debounce { get; set; } = TimeSpan.FromMilliseconds(300);
    /// <summary>Longest a cycle waits for changes to go quiet, so a file that never stops changing can't hold off testing.</summary>
    public TimeSpan MaxDebounce { get; set; } = TimeSpan.FromSeconds(2);
    public int MaxParallelTestProjects { get; set; } = Math.Max(1, Environment.ProcessorCount / 2);
    /// <summary>First delay before retrying a cycle that failed (e.g. restore could not reach a feed); doubles up to 5 minutes.</summary>
    public TimeSpan RetryDelay { get; set; } = TimeSpan.FromSeconds(15);

    private string MirrorSolutionPath => map.ToMirror(solutionPath);

    public async Task StartAsync(CancellationToken ct)
    {
        var shown = _results.Restore(store.Load());
        lock (_lock)
        {
            _pending.Reconcile = true;
            _pending.Initialize = true;
            _pending.NeedGraph = true;
            _pending.Restore = true;
            // Once the mirror is up to date, run every (non-excluded) test so results are fresh after a restart.
            _pending.All = true;
        }
        if (shown.Count > 0) await events.TestResults(shown);
        if (coverageStore is not null && coverageStore.Load() is { Count: > 0 } savedCoverage)
        {
            _coverage.Restore(savedCoverage);
            _coverageRestorePending = true;
        }
        if (coverageRuns is not null) await events.CoverageStatus(await coverageRuns.CheckAsync(ct));
        _loop = Task.Run(LoopAsync, CancellationToken.None);
        Signal();
    }

    public void FilesChanged(IReadOnlyCollection<string> repoPaths)
    {
        // Ignore changes that can't affect builds or tests (.git, bin/obj, git-ignored folders, our own files).
        var tracked = repoPaths.Where(workspace.Tracks).ToList();
        if (tracked.Count == 0) return;
        var relative = tracked.Select(RepoRelative).ToList();
        lock (_lock)
        {
            _pending.Files.UnionWith(tracked);
            _pending.FilesRel.UnionWith(relative);
            if (_pending.Files.Count > ReconcileThreshold) { _pending.Reconcile = true; _pending.Files.Clear(); _pending.FilesRel.Clear(); }
        }
        Signal();
    }

    public void SetExclusions(ExclusionSet set)
    {
        IReadOnlyList<TestResultUpdate> nowExcluded;
        IReadOnlyList<string> nowIncluded;
        List<string> projectsBack;
        lock (_lock)
        {
            // Test projects that were skipped as wholly excluded and no longer are: build and run them now.
            projectsBack = _graph?.Projects
                .Where(p => p.IsTestProject && _exclusions.ExcludesWholeProject(p.RepoRelativePath) && !set.ExcludesWholeProject(p.RepoRelativePath))
                .Select(p => p.RepoRelativePath).ToList() ?? [];
            _pending.Projects.UnionWith(projectsBack);
            _exclusions = set;
            // Under the engine lock too, so no test run sees the new exclusions before the results reflect them.
            (nowExcluded, nowIncluded) = _results.ApplyExclusions(set);
        }
        if (nowExcluded.Count > 0) _ = events.TestResults(nowExcluded);
        if (nowIncluded.Count > 0)
        {
            lock (_lock) _pending.RerunKeys.UnionWith(nowIncluded);
        }
        if (nowIncluded.Count > 0 || projectsBack.Count > 0) Signal();
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
            // Debounce: wait for a quiet period, but never longer than MaxDebounce in all.
            var debounceUntil = DateTime.UtcNow + MaxDebounce;
            while (true)
            {
                var left = debounceUntil - DateTime.UtcNow;
                if (left <= TimeSpan.Zero) break;
                var next = CurrentSignal();
                if (await Task.WhenAny(next, Task.Delay(Debounce < left ? Debounce : left, stop)) != next) break;
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
            while (await Task.WhenAny(cycle, CurrentSignal()) != cycle)
            {
                if (!ShouldInterrupt()) continue;
                await cycleCts.CancelAsync();
                break;
            }
            await cycle; // never throws; requeues its work if cancelled
            // Work that arrived during the cycle without interrupting it is waiting: start on it.
            lock (_lock)
            {
                _cycleProjects = null;
                if (!_pending.IsEmpty) _wake.TrySetResult();
            }
        }
    }

    /// <summary>
    /// A signal interrupts the running cycle only for a pause, or for new work that touches the projects the cycle is
    /// building or testing. Anything else (a retry timer, a resume, a change to another project or to a file in no
    /// project) waits for the next cycle. Checked and reset under one lock, so work that arrives in between always
    /// leaves the signal set.
    /// </summary>
    private bool ShouldInterrupt()
    {
        lock (_lock)
        {
            if (_paused || (!_pending.IsEmpty && PendingTouchesCycle())) return true;
            if (_wake.Task.IsCompleted) _wake = NewSignal();
            return false;
        }
    }

    /// <summary>Call under <see cref="_lock"/>.</summary>
    private bool PendingTouchesCycle()
    {
        // Until the cycle has worked out its projects (or with no graph to map files), any new work interrupts it.
        if (_cycleProjects is not { } running || _graph is not { } graph) return true;
        var p = _pending;
        if (p.Reconcile || p.Initialize || p.NeedGraph || p.Restore || p.All) return true;
        // Project files can change references, and .gitignore which files are in the mirror: neither maps to a fixed set.
        if (p.FilesRel.Any(f => ProjectGraph.IsGraphFile(f) || Path.GetFileName(f) == ".gitignore")) return true;
        var touched = graph.DirtyProjects(p.FilesRel).ToHashSet(StringComparer.Ordinal);
        touched.UnionWith(p.Projects);
        touched.UnionWith(p.ManualKeys.Concat(p.RerunKeys).Select(CyclePlanner.ProjectOfKey).OfType<string>());
        return touched.Overlaps(running);
    }

    private string RepoRelative(string repoPath)
    {
        try { return map.RepoRelative(RealPath.Of(repoPath)); }
        catch (Exception e) when (e is ArgumentException or IOException) { return map.RepoRelative(repoPath); }
    }

    private async Task RunCycleAsync(Pending snap, CancellationToken ct)
    {
        CyclePlan? plan = null;
        var needGraph = snap.NeedGraph;
        var restore = snap.Restore;
        // Every change copied into the mirror is recorded here as it happens. If this cycle is cancelled or fails,
        // these are handed to the next cycle: the mirror already has them, so a re-sync would not report them again.
        var synced = new HashSet<string>(snap.SyncedRel, StringComparer.Ordinal);
        // How far the cycle got, so an interrupted or failed cycle hands on only what it hadn't finished.
        var progress = new CycleProgress();
        try
        {
            await events.StateChanged(EngineState.Syncing, 0);
            if (snap.Reconcile) synced.UnionWith(await workspace.ReconcileAsync(ct, synced));
            else if (snap.Files.Count > 0) synced.UnionWith(await workspace.SyncAsync(snap.Files.ToList(), ct, synced));
            if (snap.Initialize) await workspace.InitializeAsync(ct);
            progress.Synced = true;

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
            if (_coverageRestorePending)
            {
                _coverageRestorePending = false;
                await SendCoverageAsync(graph);
            }

            ExclusionSet exclusions;
            lock (_lock) exclusions = _exclusions;
            plan = CyclePlanner.Plan(new CycleRequest(snap.Projects, synced, snap.All, snap.ManualKeys, snap.RerunKeys),
                graph, exclusions, _unhealthy, _builtThisSession, _results.FailingProjects());
            if (plan.IsEmpty)
            {
                await events.StateChanged(EngineState.Idle, 0);
                return;
            }
            lock (_lock) _cycleProjects = plan.CycleProjects;

            var stale = _results.MarkStale(plan.Stale);
            if (stale.Count > 0) await events.TestResults(stale);
            if (_coverage.MarkStale(plan.Stale)) await SendCoverageAsync(graph);
            await events.StateChanged(EngineState.Building, plan.Build.Count);
            var outcomes = await build.BuildAsync(graph.InBuildOrder(plan.Build),
                p => events.ProjectBuildStarted(p.RepoRelativePath),
                async outcome =>
                {
                    // Recorded as each build finishes, so a cancelled cycle still gets credit for what it built.
                    if (outcome.Status == BuildStatus.Succeeded)
                    {
                        lock (_lock)
                        {
                            _unhealthy.Remove(outcome.Project);
                            _builtThisSession.Add(outcome.Project);
                            if (graph.Find(outcome.Project) is { IsTestProject: false }) progress.Done.Add(outcome.Project);
                        }
                    }
                    await events.ProjectBuildFinished(outcome);
                }, ct);
            ct.ThrowIfCancellationRequested();
            foreach (var (project, outcome) in outcomes)
                if (outcome.Status is not (BuildStatus.Succeeded or BuildStatus.Cancelled)) _unhealthy.Add(project);

            foreach (var p in plan.TestProjects.Where(p => !p.IsMtp))
            {
                await events.ProjectRunFinished(new ProjectRunOutcome(p.RepoRelativePath, ProjectRunStatus.UnsupportedNotMtp,
                    "Not a Microsoft.Testing.Platform test project (VSTest is not supported yet)."));
                lock (_lock) progress.Done.Add(p.RepoRelativePath);
            }

            var runnable = CyclePlanner.RunOrder(plan, graph, outcomes, _unhealthy);

            await events.StateChanged(EngineState.Testing, runnable.Count);
            if (MaxParallelTestProjects <= 1)
            {
                foreach (var p in runnable) await RunProjectAsync(p, graph, plan, snap.ManualKeys, progress, ct);
            }
            else
            {
                await Parallel.ForEachAsync(runnable, new ParallelOptions { MaxDegreeOfParallelism = MaxParallelTestProjects, CancellationToken = ct },
                    async (p, token) => await RunProjectAsync(p, graph, plan, snap.ManualKeys, progress, token));
            }
            ct.ThrowIfCancellationRequested();

            try { store.Save(_results.All()); coverageStore?.Save(_coverage.All()); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // The results already reached the client; rerunning the cycle would not make the disk writable.
                await events.Log("warning", "Could not save results; they will not survive a restart: " + ex.Message);
            }
            _nextRetry = null;
            await events.StateChanged(EngineState.Idle, 0);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            Requeue(snap, plan, synced, needGraph, restore, progress);
        }
        catch (Exception ex)
        {
            Requeue(snap, plan, synced, needGraph, restore, progress);
            var delay = _nextRetry ?? RetryDelay;
            _nextRetry = TimeSpan.FromTicks(Math.Min(delay.Ticks * 2, TimeSpan.FromMinutes(5).Ticks));
            await events.Log("error", $"Cycle failed; retrying in {delay.TotalSeconds:0.#}s: " + map.RewriteText(ex.ToString()));
            await events.StateChanged(EngineState.Idle, 0);
            _ = Task.Delay(delay, _stop.Token).ContinueWith(t => { if (!t.IsCanceled) Signal(); }, TaskScheduler.Default);
        }
    }

    /// <summary>How far a cycle got. <see cref="Done"/> is guarded by <see cref="_lock"/>.</summary>
    private sealed class CycleProgress
    {
        /// <summary>The changed files are in the mirror and the workspace is initialised.</summary>
        public bool Synced;
        /// <summary>Projects with nothing left to do: built (non-test projects) or with their tests run.</summary>
        public HashSet<string> Done { get; } = new(StringComparer.Ordinal);
    }

    /// <summary>Hands everything an interrupted or failed cycle had not finished to the next cycle.</summary>
    /// <param name="plan">The cycle's plan, or null if it stopped before planning.</param>
    private void Requeue(Pending snap, CyclePlan? plan, IEnumerable<string> synced, bool needGraph, bool restore,
        CycleProgress progress)
    {
        lock (_lock)
        {
            var done = progress.Done;
            _pending.Projects.UnionWith((plan?.Build ?? snap.Projects).Where(p => !done.Contains(p)));
            // Once planned, the changed files, "run all" and the requested tests are already reflected in the plan.
            bool StillWanted(string key) => plan is null || (CyclePlanner.ProjectOfKey(key) is { } p && !done.Contains(p));
            _pending.ManualKeys.UnionWith(snap.ManualKeys.Where(StillWanted));
            _pending.RerunKeys.UnionWith(snap.RerunKeys.Where(StillWanted));
            if (plan is null)
            {
                _pending.SyncedRel.UnionWith(synced);
                _pending.All |= snap.All;
            }
            _pending.NeedGraph |= needGraph;
            _pending.Restore |= restore;
            if (!progress.Synced)
            {
                _pending.Initialize |= snap.Initialize;
                // Re-syncing is safe: files already copied report no change, and are carried in SyncedRel instead.
                if (snap.Reconcile) _pending.Reconcile = true;
                else { _pending.Files.UnionWith(snap.Files); _pending.FilesRel.UnionWith(snap.FilesRel); }
            }
        }
    }

    /// <param name="manualKeys">The tests the user asked to run; their results are tagged <see cref="RunKind.Manual"/>.</param>
    private async Task RunProjectAsync(ProjectInfo project, ProjectGraph graph, CyclePlan plan, IReadOnlySet<string> manualKeys,
        CycleProgress progress, CancellationToken ct)
    {
        var coverage = await StartCoverageAsync(project, graph, plan, ct);
        try
        {
            await RunProjectCoreAsync(project, graph, plan, manualKeys, progress, coverage, ct);
        }
        finally
        {
            if (coverage is not null) await coverage.DisposeAsync();
        }
    }

    /// <summary>A coverage run for the project, or null (coverage off, unavailable, or the project can't be covered, or only some of its tests run).</summary>
    private async Task<ICoverageRun?> StartCoverageAsync(ProjectInfo project, ProjectGraph graph, CyclePlan plan, CancellationToken ct)
    {
        // A manual-only run covers just the tests asked for: that is not a pass over the whole project.
        if (coverageRuns is null || plan.ManualOnly.Contains(project.RepoRelativePath)) return null;
        try
        {
            return await coverageRuns.StartAsync(project, graph, ct);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            await events.Log("warning", $"Coverage skipped for {project.RepoRelativePath}: {ex.Message}");
            return null;
        }
    }

    /// <summary>Sends the complete current line states to the client, with absolute repo paths.</summary>
    private async Task SendCoverageAsync(ProjectGraph graph)
    {
        await _coverageSend.WaitAsync();
        try
        {
            var markup = _coverage.Markup(file => graph.OwnersOf(file),
                production => graph.WithDependents([production]).Where(t => graph.Find(t)?.IsTestProject == true).ToHashSet(StringComparer.Ordinal));
            await events.CoverageUpdated(markup.Select(m => m with { Path = map.RepoPath(m.Path) }).ToList());
        }
        finally
        {
            _coverageSend.Release();
        }
    }

    private async Task RunProjectCoreAsync(ProjectInfo project, ProjectGraph graph, CyclePlan plan, IReadOnlySet<string> manualKeys,
        CycleProgress progress, ICoverageRun? coverage, CancellationToken ct)
    {
        var first = true;
        var manualOnly = plan.ManualOnly.Contains(project.RepoRelativePath);
        var run = _results.StartRun(project.RepoRelativePath, plan.RunKeys, manualKeys, manualOnly);
        var outcome = await tests.RunAsync(project,
            id =>
            {
                if (run.IsRequested(id)) return true;
                if (manualOnly) return false;
                lock (_lock) return !_exclusions.IsExcluded(id);
            },
            async raw =>
            {
                if (ct.IsCancellationRequested) return;
                if (first)
                {
                    first = false;
                    var discovered = run.Discovered(raw);
                    if (manualOnly) await events.TestResults(discovered);
                    else await events.TestsDiscovered(project.RepoRelativePath, discovered);
                }
                else await events.TestResults(run.Updated(raw));
            }, ct, coverage);
        if (!ct.IsCancellationRequested && outcome.Status == ProjectRunStatus.Completed && run.SettleTheories() is { } all)
            await events.TestsDiscovered(project.RepoRelativePath, all);
        if (!ct.IsCancellationRequested && outcome.Status == ProjectRunStatus.Crashed)
        {
            var settled = run.SettleCrash(outcome.Error);
            if (settled.Count > 0) await events.TestResults(settled);
        }
        if (!ct.IsCancellationRequested && outcome.Status == ProjectRunStatus.Completed && coverage?.Pass is { } pass)
        {
            _coverage.Replace(pass);
            await SendCoverageAsync(graph);
        }
        if (!ct.IsCancellationRequested) await events.ProjectRunFinished(outcome);
        if (!ct.IsCancellationRequested && outcome.Status != ProjectRunStatus.Cancelled)
            lock (_lock) progress.Done.Add(project.RepoRelativePath);
    }

    private sealed class Pending
    {
        public HashSet<string> Files { get; } = new(StringComparer.Ordinal);
        /// <summary><see cref="Files"/> as repo-relative paths, to match them against the project graph.</summary>
        public HashSet<string> FilesRel { get; } = new(StringComparer.Ordinal);
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

        /// <summary>Changed files not yet built; a pending full reconcile counts as "more than the threshold".</summary>
        public int WaitingFiles => Reconcile ? Math.Max(Files.Count + SyncedRel.Count, ReconcileThreshold + 1) : Files.Count + SyncedRel.Count;

        public bool IsEmpty => Files.Count == 0 && SyncedRel.Count == 0 && Projects.Count == 0 && ManualKeys.Count == 0 && RerunKeys.Count == 0
                               && !Reconcile && !Initialize && !NeedGraph && !Restore && !All;
    }
}
