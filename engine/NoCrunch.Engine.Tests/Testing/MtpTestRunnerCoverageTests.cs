using NoCrunch.Engine.Coverage;
using NoCrunch.Engine.Testing;
using NoCrunch.Engine.Tests.TestSupport;

namespace NoCrunch.Engine.Tests.Testing;

/// <summary>Runs a mirrored sample with injected tests (never committed to the sample, which must pass) through a recording coverage run.</summary>
[TestClass]
[DoNotParallelize]
public class MtpTestRunnerCoverageTests
{
    private const string Core = "Shop.Core.Tests";
    private const string Flaky = "Shop.Core.Tests.FlakyTests";
    private static MirroredSample _sample = null!;
    private static string _probeFile = null!;
    /// <summary>The injected hanging test only hangs while this file exists, so runs of the whole project are not affected.</summary>
    private static string _hangFile = null!;

    [ClassInitialize]
    public static async Task Init(TestContext _)
    {
        _sample = await MirroredSample.CreateAsync();
        _probeFile = Path.Combine(Path.GetTempPath(), "nocrunch-probe-" + Guid.NewGuid().ToString("N") + ".txt");
        _hangFile = Path.Combine(Path.GetTempPath(), "nocrunch-hang-" + Guid.NewGuid().ToString("N") + ".flag");
        File.WriteAllText(Path.Combine(_sample.Layout.MirrorRoot, "tests/Shop.Core.Tests/InjectedCoverageTests.cs"), $$"""
            namespace Shop.Core.Tests;

            [TestClass]
            public class FlakyTests
            {
                private static int _calls;
                [TestMethod] public void Fails_first_then_passes() { if (Interlocked.Increment(ref _calls) == 1) Assert.Fail("first time"); }
                [TestMethod] public void Always_fails() => Assert.Fail("always");
            }

            [TestClass]
            public class RerunHangTests
            {
                private static int _calls;
                [TestMethod] public void Fails_then_hangs() { if (Interlocked.Increment(ref _calls) == 1) Assert.Fail("first time"); if (File.Exists(@"{{_hangFile}}")) Thread.Sleep(System.Threading.Timeout.Infinite); }
            }

            [TestClass]
            public class ProbeTests
            {
                [TestMethod] public void Writes_base_directory() => File.WriteAllText(@"{{_probeFile}}", AppContext.BaseDirectory);
            }
            """);
        await _sample.BuildAllAsync();
    }

    private sealed class RecordingCoverage(string targetPath, TimeSpan? classDelay = null) : ICoverageRun
    {
        public string TargetPath { get; } = targetPath;
        public TestProjectPass? Pass => null;
        public List<(string Class, List<TestResultUpdate> Results)> Classes { get; } = [];
        public Dictionary<string, TestState> Alone { get; } = [];
        public bool Finished { get; private set; }

        public async Task ClassFinishedAsync(string testClass, IReadOnlyList<TestResultUpdate> results, CancellationToken ct)
        {
            if (classDelay is { } d) await Task.Delay(d, ct);
            lock (Classes) Classes.Add((testClass, results.ToList()));
        }

        public async Task FinishedAsync(RunAlone runAlone, CancellationToken ct)
        {
            foreach (var failed in Classes.SelectMany(c => c.Results).Where(r => r.State == TestState.Failed).ToList())
                Alone[failed.Id.Method!] = await runAlone(failed, ct);
            Finished = true;
        }

        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }

    private static async Task<(ProjectRunOutcome outcome, List<TestResultUpdate> updates)> Run(
        string project, RecordingCoverage? coverage, Func<TestId, bool>? shouldRun = null, TimeSpan? timeout = null)
    {
        var updates = new List<TestResultUpdate>();
        var info = _sample.Graph.Projects.Single(p => p.Name == project);
        var outcome = await new MtpTestRunner(_sample.Map, timeout).RunAsync(info, shouldRun ?? (_ => true),
            batch => { lock (updates) updates.AddRange(batch); return Task.CompletedTask; }, CancellationToken.None, coverage);
        return (outcome, updates);
    }

    private static RecordingCoverage OwnTarget(string project) => new(_sample.Graph.Projects.Single(p => p.Name == project).TargetPath!);

    private static bool IsOutcome(TestState s) => s is TestState.Passed or TestState.Failed or TestState.Skipped;

    [TestMethod]
    [Timeout(300_000)]
    public async Task Each_class_runs_as_its_own_slice_and_ClassFinished_gets_that_classes_results()
    {
        var coverage = OwnTarget(Core);

        var (outcome, _) = await Run(Core, coverage);

        Assert.AreEqual(ProjectRunStatus.Completed, outcome.Status, outcome.Error);
        var names = coverage.Classes.Select(c => c.Class).ToList();
        CollectionAssert.AreEquivalent(names.Distinct().ToList(), names, "one ClassFinished per class");
        foreach (var expected in new[] { Flaky, "Shop.Core.Tests.PriceCalculatorTests", "Shop.Core.Tests.ProbeTests" })
            CollectionAssert.Contains(names, expected);
        foreach (var (cls, results) in coverage.Classes)
        {
            Assert.IsTrue(results.Count > 0, cls);
            Assert.IsTrue(results.All(r => r.Id.Type == cls && IsOutcome(r.State)), cls + ": " + string.Join(",", results.Select(r => r.State)));
        }
    }

    [TestMethod]
    [Timeout(300_000)]
    public async Task Every_queued_test_runs_exactly_once()
    {
        // The xUnit theory project: rows share their theory's UID and only appear at run time.
        var coverage = OwnTarget("Shop.Theory.Tests");

        var (outcome, updates) = await Run("Shop.Theory.Tests", coverage);

        Assert.AreEqual(ProjectRunStatus.Completed, outcome.Status, outcome.Error);
        var finals = updates.GroupBy(u => u.Id.Key).Select(g => g.Last()).Where(u => IsOutcome(u.State)).ToDictionary(u => u.Id.Key, u => u.State);
        var sliced = coverage.Classes.SelectMany(c => c.Results).Where(r => IsOutcome(r.State)).ToList();
        Assert.IsTrue(finals.Count > 0);
        CollectionAssert.AreEquivalent(finals.Keys.ToList(), sliced.Select(r => r.Id.Key).ToList(), "each result appears in exactly one slice");
        foreach (var r in sliced) Assert.AreEqual(finals[r.Id.Key], r.State, r.Id.Key);
    }

    [TestMethod]
    [Timeout(300_000)]
    public async Task Finished_gets_a_RunAlone_that_reruns_one_test_and_returns_its_state()
    {
        var coverage = OwnTarget(Core);

        await Run(Core, coverage, id => id.Type == Flaky);

        Assert.IsTrue(coverage.Finished);
        Assert.AreEqual(TestState.Failed, coverage.Alone["Always_fails"]);
        Assert.AreEqual(TestState.Passed, coverage.Alone["Fails_first_then_passes"]);
    }

    [TestMethod]
    [Timeout(300_000)]
    public async Task A_rerun_does_not_change_the_reported_result()
    {
        var coverage = OwnTarget(Core);

        var (_, updates) = await Run(Core, coverage, id => id.Type == Flaky);

        var last = updates.Last(u => u.Id.Method == "Fails_first_then_passes");
        Assert.AreEqual(TestState.Failed, last.State, "the rerun passed, but the reported result stays the first run's");
        Assert.AreEqual(TestState.Passed, coverage.Alone["Fails_first_then_passes"]);
    }

    [TestMethod]
    [Timeout(300_000)]
    public async Task Target_path_override_is_the_one_launched()
    {
        var info = _sample.Graph.Projects.Single(p => p.Name == Core);
        var copy = Path.Combine(Path.GetTempPath(), "nocrunch tests", "copy-" + Guid.NewGuid().ToString("N")[..8]);
        var app = CoverageTargets.CopyOutput(Path.GetDirectoryName(info.TargetPath)!, copy, info.TargetPath!);
        if (File.Exists(_probeFile)) File.Delete(_probeFile);

        var (outcome, _) = await Run(Core, new RecordingCoverage(app), id => id.Type == "Shop.Core.Tests.ProbeTests");

        Assert.AreEqual(ProjectRunStatus.Completed, outcome.Status, outcome.Error);
        // (macOS resolves /var to /private/var, so compare real paths.)
        StringAssert.StartsWith(Path.TrimEndingDirectorySeparator(File.ReadAllText(_probeFile)), Path.TrimEndingDirectorySeparator(NoCrunch.Engine.Layout.RealPath.Of(copy)));
    }

    [TestMethod]
    [Timeout(300_000)]
    public async Task A_slow_snapshot_does_not_trip_the_test_timeout()
    {
        var coverage = new RecordingCoverage(_sample.Graph.Projects.Single(p => p.Name == Core).TargetPath!, classDelay: TimeSpan.FromSeconds(4));

        var (outcome, updates) = await Run(Core, coverage, id => id.Type == "Shop.Core.Tests.ProbeTests", timeout: TimeSpan.FromSeconds(2));

        Assert.AreEqual(ProjectRunStatus.Completed, outcome.Status, outcome.Error);
        Assert.IsNull(outcome.Error);
        Assert.AreEqual(TestState.Passed, updates.Last(u => u.Id.Method == "Writes_base_directory").State);
    }

    [TestMethod]
    [Timeout(300_000)]
    public async Task A_rerun_that_hangs_trips_the_test_timeout_and_the_run_reports_it()
    {
        var coverage = OwnTarget(Core);
        File.WriteAllText(_hangFile, "");

        (ProjectRunOutcome outcome, List<TestResultUpdate> updates) result;
        try { result = await Run(Core, coverage, id => id.Type == "Shop.Core.Tests.RerunHangTests", timeout: TimeSpan.FromSeconds(3)); }
        finally { File.Delete(_hangFile); }
        var (outcome, updates) = result;

        Assert.AreEqual(ProjectRunStatus.Completed, outcome.Status);
        StringAssert.Contains(outcome.Error, "Timed out", "the hung rerun is reported as a timeout, not swallowed");
        Assert.IsFalse(coverage.Finished, "coverage did not finish after the host was stopped");
        Assert.AreEqual(TestState.Failed, updates.Last(u => u.Id.Method == "Fails_then_hangs").State);
    }
}
