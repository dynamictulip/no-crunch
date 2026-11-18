using NoCrunch.Engine.Testing;

namespace NoCrunch.Engine.Core;

/// <summary>
/// The latest result of every known test, and the rules for how each new report changes it. Thread-safe: test projects
/// run in parallel and report as they go, while exclusions can change at any time.
/// </summary>
internal sealed class ResultBook
{
    private readonly object _lock = new();
    private readonly Dictionary<string, TestResultUpdate> _results = new(StringComparer.Ordinal);

    /// <summary>Takes in the results saved by an earlier session, all stale until they run again. Returns them as now shown.</summary>
    public IReadOnlyList<TestResultUpdate> Restore(IReadOnlyDictionary<string, TestResultUpdate> stored)
    {
        lock (_lock)
        {
            foreach (var (key, r) in stored)
                _results[key] = IsExcludedResult(r)
                    // Excluded tests start every session blank: no result from earlier (manual) runs.
                    ? new TestResultUpdate(r.Id, r.DisplayName, TestState.Stale, null, null, null, r.FilePath, r.Line)
                    : CarryForward(r with { State = TestState.Stale }, r);
            return _results.Values.ToList();
        }
    }

    public IReadOnlyList<TestResultUpdate> All()
    {
        lock (_lock) return _results.Values.ToList();
    }

    /// <summary>Projects with a failing test, so they can run first.</summary>
    public IReadOnlySet<string> FailingProjects()
    {
        lock (_lock)
            return _results.Values.Where(r => r.State == TestState.Failed).Select(r => r.Id.Project).ToHashSet(StringComparer.Ordinal);
    }

    /// <summary>Marks the results of these projects stale (their code changed). Returns the ones that changed.</summary>
    public IReadOnlyList<TestResultUpdate> MarkStale(IReadOnlySet<string> projects)
    {
        lock (_lock)
        {
            var stale = _results.Values
                .Where(r => projects.Contains(r.Id.Project) && r.State is not (TestState.Excluded or TestState.Stale))
                .Select(r => CarryForward(r with { State = TestState.Stale }, r)).ToList();
            foreach (var r in stale) _results[r.Id.Key] = r;
            return stale;
        }
    }

    /// <summary>
    /// Marks tests the new exclusions cover as excluded. Returns those results, and the keys of tests that were excluded
    /// and no longer are (they need running).
    /// </summary>
    public (IReadOnlyList<TestResultUpdate> NowExcluded, IReadOnlyList<string> NowIncluded) ApplyExclusions(ExclusionSet set)
    {
        lock (_lock)
        {
            // A manual result of an excluded test is kept: it's what the user ran by hand.
            var nowExcluded = _results.Values.Where(r => r.State != TestState.Excluded && r.RunKind != RunKind.Manual && set.IsExcluded(r.Id))
                .Select(r => CarryForward(r with { State = TestState.Excluded }, r)).ToList();
            foreach (var r in nowExcluded) _results[r.Id.Key] = r;
            var nowIncluded = _results.Values.Where(r => r.State == TestState.Excluded && !set.IsExcluded(r.Id)).Select(r => r.Id.Key).ToList();
            return (nowExcluded, nowIncluded);
        }
    }

    /// <param name="runKeys">Tests to run even if excluded: manual runs plus re-included tests.</param>
    /// <param name="manualKeys">The subset the user asked to run; their results are tagged <see cref="RunKind.Manual"/>.</param>
    /// <param name="manualOnly">The project runs only for the tests asked for; its other results are left as they are.</param>
    public ProjectRun StartRun(string project, IReadOnlySet<string> runKeys, IReadOnlySet<string> manualKeys, bool manualOnly) =>
        new(this, project, runKeys, manualKeys, manualOnly);

    /// <summary>
    /// The results of one test project's run. Its first report is the full discovered list (<see cref="Discovered"/>),
    /// later ones are state changes (<see cref="Updated"/>).
    /// </summary>
    public sealed class ProjectRun
    {
        private readonly ResultBook _book;
        private readonly string _project;
        private readonly IReadOnlySet<string> _runKeys;
        private readonly IReadOnlySet<string> _manualKeys;
        private readonly bool _manualOnly;
        private readonly HashSet<string> _runUids;
        private readonly HashSet<string> _manualUids;
        // Theories whose data rows can't be listed up front (e.g. xUnit with non-serialisable data) are discovered once,
        // then report one result per row with the theory's UID. Map UID -> theory key, and collect each theory's rows.
        private readonly Dictionary<string, string> _theories = new(StringComparer.Ordinal);
        private readonly Dictionary<string, List<TestResultUpdate>> _rowsSeen = new(StringComparer.Ordinal);

        internal ProjectRun(ResultBook book, string project, IReadOnlySet<string> runKeys, IReadOnlySet<string> manualKeys, bool manualOnly)
        {
            _book = book;
            _project = project;
            _runKeys = runKeys;
            _manualKeys = manualKeys;
            _manualOnly = manualOnly;
            // Asking for one theory row runs the whole theory: rows share the theory's UID, and only the UID can be run.
            lock (book._lock)
            {
                _runUids = book.UidsOf(runKeys);
                _manualUids = book.UidsOf(manualKeys);
            }
        }

        /// <summary>Whether the test was asked for (by hand, or re-included), so runs even if excluded.</summary>
        public bool IsRequested(TestId id) => _runKeys.Contains(id.Key) || _runUids.Contains(id.Uid);

        private TestResultUpdate Tag(TestResultUpdate r) =>
            r with { RunKind = _manualKeys.Contains(r.Id.Key) || _manualUids.Contains(r.Id.Uid) ? RunKind.Manual : RunKind.Auto };

        /// <summary>
        /// Takes the run's first report, the full list of the project's tests. Returns what to show: for a manual-only
        /// run, just the requested tests; otherwise the project's full list.
        /// </summary>
        public IReadOnlyList<TestResultUpdate> Discovered(IReadOnlyList<TestResultUpdate> raw)
        {
            var batch = raw.Select(Tag).ToList();
            var discovered = new List<TestResultUpdate>(batch.Count);
            var results = _book._results;
            lock (_book._lock)
            {
                var previous = results.Values.Where(r => r.Id.Project == _project).ToDictionary(r => r.Id.Key, StringComparer.Ordinal);
                if (!_manualOnly)
                    foreach (var key in previous.Keys) results.Remove(key);
                foreach (var r in batch) _theories.TryAdd(r.Id.Uid, r.Id.Key);
                // Rows from earlier runs follow their theory (queued, excluded...) until the run reports them again.
                var rows = previous.Values
                    .Where(p => _theories.TryGetValue(p.Id.Uid, out var tk) && tk != p.Id.Key)
                    .Select(p => (theory: batch.First(b => b.Id.Key == _theories[p.Id.Uid]), row: p))
                    .Select(x => x.row with
                    {
                        State = x.theory.State, RunKind = x.theory.RunKind, TheoryKey = x.theory.Id.Key,
                        DurationMs = null, Message = null, StackTrace = null,
                    });
                foreach (var r in batch.Concat(rows.ToList()))
                {
                    var hasPrevious = previous.TryGetValue(r.Id.Key, out var prev);
                    // A manual-only run reports untouched tests as Excluded; keep their previous result instead.
                    if (_manualOnly && !IsRequested(r.Id) && hasPrevious) continue;
                    // An automatic run skips excluded tests; keep the result of an earlier manual run of them.
                    var keep = r.State == TestState.Excluded && hasPrevious && prev!.RunKind == RunKind.Manual
                               && prev.State != TestState.Excluded;
                    var current = keep ? prev! : CarryForward(r, hasPrevious ? prev : null);
                    results[r.Id.Key] = current;
                    discovered.Add(current);
                }
            }
            return _manualOnly ? discovered.Where(r => IsRequested(r.Id)).ToList() : discovered;
        }

        /// <summary>Takes a later report of state changes. Returns them as now shown, with any theory they set running.</summary>
        public IReadOnlyList<TestResultUpdate> Updated(IReadOnlyList<TestResultUpdate> raw)
        {
            var batch = raw.Select(Tag).ToList();
            var results = _book._results;
            lock (_book._lock)
            {
                // Tag theory rows with their theory so clients can nest them (names alone can't tell them apart).
                var merged = batch
                    .Select(r => _theories.TryGetValue(r.Id.Uid, out var tk) && tk != r.Id.Key ? r with { TheoryKey = tk } : r)
                    .Select(r => CarryForward(r, results.GetValueOrDefault(r.Id.Key))).ToList();
                foreach (var r in merged) results[r.Id.Key] = r;
                // A row result means its theory is running; the theory's own result is settled when the run ends.
                foreach (var r in merged.ToList())
                {
                    if (r.TheoryKey is not { } tk) continue;
                    if (!_rowsSeen.TryGetValue(tk, out var seen)) _rowsSeen[tk] = seen = [];
                    seen.RemoveAll(x => x.Id.Key == r.Id.Key);
                    seen.Add(r);
                    if (results.TryGetValue(tk, out var theory) && theory.State != TestState.Running)
                    {
                        var running = CarryForward(Tag(theory with { State = TestState.Running }), theory);
                        results[tk] = running;
                        merged.Add(running);
                    }
                }
                return merged;
            }
        }

        /// <summary>
        /// Once the run has completed, gives each theory the worst result of the rows it just reported, and drops rows it
        /// no longer produces. Returns the project's full result list, which replaces what clients have for it; null when
        /// no theory reported rows.
        /// </summary>
        public IReadOnlyList<TestResultUpdate>? SettleTheories()
        {
            var results = _book._results;
            lock (_book._lock)
            {
                if (_rowsSeen.Count == 0) return null;
                foreach (var (theoryKey, rows) in _rowsSeen)
                {
                    if (!results.TryGetValue(theoryKey, out var theory)) continue;
                    var finished = rows.Where(r => r.IsOutcome).ToList();
                    var failed = finished.Count(r => r.State == TestState.Failed);
                    var state = failed > 0 ? TestState.Failed
                        : finished.Any(r => r.State == TestState.Passed) ? TestState.Passed
                        : finished.Count > 0 ? TestState.Skipped : theory.State;
                    results[theoryKey] = Tag(theory with
                    {
                        State = state,
                        LastOutcome = null,
                        DurationMs = finished.Sum(r => r.DurationMs ?? 0),
                        Message = failed > 0 ? $"{failed} of {finished.Count} data rows failed" : null,
                        StackTrace = null,
                    });
                    var current = rows.Select(r => r.Id.Key).ToHashSet(StringComparer.Ordinal);
                    var gone = results.Values
                        .Where(r => r.Id.Project == _project && r.Id.Uid == theory.Id.Uid && r.Id.Key != theoryKey && !current.Contains(r.Id.Key))
                        .Select(r => r.Id.Key).ToList();
                    foreach (var key in gone) results.Remove(key);
                }
                return results.Values.Where(r => r.Id.Project == _project).ToList();
            }
        }

        /// <summary>
        /// After the test host died, nothing more will be reported for this run: tests it was running fail (the crash is
        /// the likeliest cause, and the user needs to see it), and tests it never reached go back to stale with their last outcome.
        /// </summary>
        public IReadOnlyList<TestResultUpdate> SettleCrash(string? error)
        {
            var results = _book._results;
            lock (_book._lock)
            {
                var settled = results.Values
                    .Where(r => r.Id.Project == _project && r.State is TestState.Running or TestState.Queued)
                    .Select(r => r.State == TestState.Running
                        ? r with
                        {
                            State = TestState.Failed, LastOutcome = null, DurationMs = null, StackTrace = null,
                            Message = "The test host crashed while this test was running" + (error is null ? "." : ": " + error),
                        }
                        : CarryForward(r with { State = TestState.Stale }, r))
                    .ToList();
                foreach (var r in settled) results[r.Id.Key] = r;
                return settled;
            }
        }
    }

    /// <summary>UIDs of the known tests with these keys. Call under <see cref="_lock"/>.</summary>
    private HashSet<string> UidsOf(IReadOnlySet<string> keys) =>
        keys.Count == 0 ? [] : _results.Values.Where(r => keys.Contains(r.Id.Key)).Select(r => r.Id.Uid).ToHashSet(StringComparer.Ordinal);

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
}
