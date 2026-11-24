using NoCrunch.Engine.Layout;
using NoCrunch.Engine.Testing;

namespace NoCrunch.Engine.Coverage;

/// <summary>One coverage pass over one test project. See <see cref="ICoverageRun"/>.</summary>
internal sealed class CoverageRun : ICoverageRun
{
    private readonly ICoverageSession _session;
    private readonly PathMap _map;
    private readonly string _testProject;
    private readonly IReadOnlyList<string> _instrumented;
    private readonly Func<string, string, Task> _log;
    private readonly string? _copyDir;
    private readonly List<ClassCoverage> _classes = [];
    private readonly List<(string TestClass, TestResultUpdate Test)> _failing = [];
    private bool _failed;

    /// <param name="instrumented">Repo-relative paths of the production projects whose DLLs were instrumented.</param>
    /// <param name="copyDir">The copied output folder to delete on dispose, if any.</param>
    public CoverageRun(ICoverageSession session, PathMap map, string testProject, string targetPath,
        IReadOnlyList<string> instrumented, Func<string, string, Task> log, string? copyDir)
    {
        _session = session;
        _map = map;
        _testProject = testProject;
        TargetPath = targetPath;
        _instrumented = instrumented;
        _log = log;
        _copyDir = copyDir;
    }

    public string TargetPath { get; }

    public TestProjectPass? Pass { get; private set; }

    public async Task ClassFinishedAsync(string testClass, IReadOnlyList<TestResultUpdate> results, CancellationToken ct)
    {
        if (_failed) return;
        try
        {
            var files = await SnapshotAsync(ct);
            _classes.Add(new ClassCoverage(testClass, files));
            foreach (var r in results.Where(r => r.State == TestState.Failed)) _failing.Add((testClass, r));
        }
        catch (Exception ex)
        {
            await Fail($"Coverage snapshot after {testClass} failed: {ex.Message}", ex, ct);
        }
    }

    public async Task FinishedAsync(RunAlone runAlone, CancellationToken ct)
    {
        if (_failed) return;
        var failingHit = new Dictionary<string, HashSet<int>>(StringComparer.Ordinal);
        var unrefined = new HashSet<string>(StringComparer.Ordinal);
        try
        {
            foreach (var (testClass, test) in _failing)
            {
                ct.ThrowIfCancellationRequested();
                try
                {
                    var state = await runAlone(test, ct);
                    var files = await SnapshotAsync(ct);
                    if (state != TestState.Failed)
                    {
                        await _log("warning", $"{test.Id.Key} failed, then passed when rerun alone (flaky?); its lines are not marked as hit by a failing test.");
                        continue;
                    }
                    foreach (var (file, lines) in files)
                    {
                        if (!failingHit.TryGetValue(file, out var set)) failingHit[file] = set = [];
                        set.UnionWith(lines.Hit);
                    }
                }
                catch (Exception ex) when (ex is not OperationCanceledException || !ct.IsCancellationRequested)
                {
                    unrefined.Add(testClass);
                    await _log("warning", $"Could not collect coverage for failing test {test.Id.Key}: {ex.Message}");
                    try { await _session.SnapshotCoberturaAsync(ct); } catch (Exception) { /* discard what the broken rerun left behind */ }
                }
            }
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            _failed = true;
            return;
        }

        Pass = new TestProjectPass(
            _testProject, _classes.ToList(),
            failingHit.ToDictionary(kv => kv.Key, kv => (IReadOnlySet<int>)kv.Value, StringComparer.Ordinal),
            unrefined, HashesOf(_classes.SelectMany(c => c.Files.Keys).Concat(failingHit.Keys)),
            _instrumented.ToHashSet(StringComparer.Ordinal));
    }

    public async ValueTask DisposeAsync()
    {
        await _session.DisposeAsync();
        if (_copyDir is not null)
        {
            try { if (Directory.Exists(_copyDir)) Directory.Delete(_copyDir, recursive: true); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    private async Task Fail(string message, Exception ex, CancellationToken ct)
    {
        _failed = true;
        Pass = null;
        if (!(ex is OperationCanceledException && ct.IsCancellationRequested)) await _log("warning", message);
    }

    private async Task<Dictionary<string, LineSet>> SnapshotAsync(CancellationToken ct)
    {
        var report = CoberturaParser.Parse(await _session.SnapshotCoberturaAsync(ct));
        var files = new Dictionary<string, LineSet>(StringComparer.Ordinal);
        foreach (var (path, lines) in report)
            if (ToRepoRelative(path) is { } rel) files[rel] = lines;
        return files;
    }

    /// <summary>The repo-relative path of a file named by the report (a mirror path), or null if it is not a repo source file.</summary>
    private string? ToRepoRelative(string path)
    {
        string repo;
        try { repo = _map.ToRepo(path); }
        catch (ArgumentException) { return null; }
        var rel = _map.RepoRelative(repo);
        return rel.StartsWith("obj/", StringComparison.Ordinal) || rel.Contains("/obj/", StringComparison.Ordinal)
               || rel.StartsWith(".nocrunch/", StringComparison.Ordinal) ? null : rel;
    }

    private Dictionary<string, string> HashesOf(IEnumerable<string> files)
    {
        var hashes = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var rel in files.Distinct(StringComparer.Ordinal))
        {
            try { hashes[rel] = ContentHash.OfFile(_map.MirrorPath(rel)); }
            catch (IOException) { }
        }
        return hashes;
    }
}
