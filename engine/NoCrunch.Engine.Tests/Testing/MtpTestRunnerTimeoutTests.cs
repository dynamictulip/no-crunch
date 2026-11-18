using System.Diagnostics;
using NoCrunch.Engine.Testing;
using NoCrunch.Engine.Tests.TestSupport;

namespace NoCrunch.Engine.Tests.Testing;

/// <summary>Runs a mirrored sample with injected slow and hanging tests (never committed to the sample, which must pass).</summary>
[TestClass]
[DoNotParallelize]
public class MtpTestRunnerTimeoutTests
{
    private const string Project = "Shop.Core.Tests";
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(3);
    private static MirroredSample _sample = null!;

    [ClassInitialize]
    public static async Task Init(TestContext _)
    {
        _sample = await MirroredSample.CreateAsync();
        File.WriteAllText(Path.Combine(_sample.Layout.MirrorRoot, "tests/Shop.Core.Tests/InjectedTests.cs"), """
            namespace Shop.Core.Tests;

            [TestClass]
            public class SlowTests
            {
                [TestMethod] public void Slow_1() => Thread.Sleep(1000);
                [TestMethod] public void Slow_2() => Thread.Sleep(1000);
                [TestMethod] public void Slow_3() => Thread.Sleep(1000);
                [TestMethod] public void Slow_4() => Thread.Sleep(1000);
            }

            [TestClass]
            public class HangingTests
            {
                [TestMethod] public void Hangs() => Thread.Sleep(System.Threading.Timeout.Infinite);
            }
            """);
        await _sample.BuildAllAsync();
    }

    private static async Task<(ProjectRunOutcome outcome, Dictionary<string, TestResultUpdate> final)> Run(Func<TestId, bool> shouldRun)
    {
        var updates = new List<TestResultUpdate>();
        var runner = new MtpTestRunner(_sample.Map, Timeout);
        var info = _sample.Graph.Projects.Single(p => p.Name == Project);
        var outcome = await runner.RunAsync(info, shouldRun,
            batch => { lock (updates) updates.AddRange(batch); return Task.CompletedTask; }, CancellationToken.None);
        return (outcome, updates.GroupBy(u => u.Id.Key).ToDictionary(g => g.Key, g => g.Last()));
    }

    [TestMethod]
    [Timeout(300_000)]
    public async Task Suite_longer_than_the_timeout_is_not_stopped_when_each_test_finishes_in_time()
    {
        var watch = Stopwatch.StartNew();
        var (outcome, final) = await Run(id => id.Type == "Shop.Core.Tests.SlowTests");

        Assert.AreEqual(ProjectRunStatus.Completed, outcome.Status, outcome.Error);
        Assert.IsNull(outcome.Error);
        var slow = final.Values.Where(r => r.Id.Type == "Shop.Core.Tests.SlowTests").ToList();
        Assert.AreEqual(4, slow.Count);
        Assert.IsTrue(slow.All(r => r.State == TestState.Passed), string.Join("\n", slow.Select(r => $"{r.Id.Method}: {r.State} {r.Message}")));
        Assert.IsTrue(watch.Elapsed > Timeout, "the suite took longer than the timeout");
    }

    [TestMethod]
    [Timeout(300_000)]
    public async Task Hanging_test_is_failed_and_its_test_host_stopped()
    {
        var before = TestHostPids();
        var (outcome, final) = await Run(id => id.Type != "Shop.Core.Tests.SlowTests");

        Assert.AreEqual(ProjectRunStatus.Completed, outcome.Status, outcome.Error);
        StringAssert.Contains(outcome.Error, "Timed out");
        var hung = final.Values.Single(r => r.Id.Method == "Hangs");
        Assert.AreEqual(TestState.Failed, hung.State);
        Assert.AreEqual("Timed out: no result for 3 s", hung.Message);
        // Tests that never got to run are not left queued or running.
        var ran = final.Values.Where(r => r.Id.Type != "Shop.Core.Tests.SlowTests").ToList();
        Assert.IsTrue(ran.All(r => r.State is TestState.Passed or TestState.Failed),
            string.Join("\n", ran.Select(r => $"{r.Id.Method}: {r.State} {r.Message}")));
        Assert.IsTrue(ran.Where(r => r.State == TestState.Failed && r.Id.Method != "Hangs").All(r => r.Message!.StartsWith("Not run")));

        var leftover = TestHostPids().Except(before).ToList();
        Assert.AreEqual(0, leftover.Count, "the test host was stopped");
    }

    /// <summary>Running test hosts of this sample's mirror.</summary>
    private static HashSet<int> TestHostPids()
    {
        var pids = new HashSet<int>();
        foreach (var p in Process.GetProcesses())
        {
            using (p)
            {
                try
                {
                    if (p.MainModule?.FileName is { } f && f.StartsWith(_sample.Layout.MirrorRoot, StringComparison.Ordinal)) pids.Add(p.Id);
                }
                catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException or NotSupportedException) { }
            }
        }
        return pids;
    }
}
