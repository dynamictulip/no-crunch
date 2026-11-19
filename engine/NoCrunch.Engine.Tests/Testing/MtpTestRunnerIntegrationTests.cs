using NoCrunch.Engine.Build;
using NoCrunch.Engine.Testing;
using NoCrunch.Engine.Tests.TestSupport;

namespace NoCrunch.Engine.Tests.Testing;

[TestClass]
public class MtpTestRunnerIntegrationTests
{
    private static MirroredSample _sample = null!;

    [ClassInitialize]
    public static async Task Init(TestContext _)
    {
        _sample = await MirroredSample.CreateAsync();
        await _sample.BuildAllAsync();
    }

    private static async Task<(ProjectRunOutcome outcome, List<TestResultUpdate> updates)> Run(
        MirroredSample sample, string project, Func<TestId, bool>? shouldRun = null, CancellationToken ct = default)
    {
        var updates = new List<TestResultUpdate>();
        var runner = new MtpTestRunner(sample.Map);
        var info = sample.Graph.Projects.Single(p => p.Name == project);
        var outcome = await runner.RunAsync(info, shouldRun ?? (_ => true),
            batch => { lock (updates) updates.AddRange(batch); return Task.CompletedTask; }, ct);
        return (outcome, updates);
    }

    private static Dictionary<string, TestResultUpdate> Final(IEnumerable<TestResultUpdate> updates) =>
        updates.GroupBy(u => u.Id.Key).ToDictionary(g => g.Key, g => g.Last());

    [TestMethod]
    [Timeout(120_000)]
    public async Task Discovers_and_runs_sample_core_tests()
    {
        var (outcome, updates) = await Run(_sample, "Shop.Core.Tests");

        Assert.AreEqual(ProjectRunStatus.Completed, outcome.Status, outcome.Error);
        var final = Final(updates).Values.ToList();
        Assert.AreEqual(2, final.Count);
        foreach (var r in final)
        {
            Assert.AreEqual(TestState.Passed, r.State, r.Message + "\n" + r.StackTrace);
            Assert.AreEqual("Shop.Core.Tests.PriceCalculatorTests", r.Id.Type);
            Assert.AreEqual("tests/Shop.Core.Tests/Shop.Core.Tests.csproj", r.Id.Project);
            Assert.IsNotNull(r.FilePath);
            StringAssert.StartsWith(r.FilePath, _sample.Layout.RepoRoot);
            Assert.IsFalse(r.FilePath!.Contains(".nocrunch"), r.FilePath);
        }
        CollectionAssert.AreEquivalent(new[] { "Total_multiplies", "Total_zero_qty" }, final.Select(r => r.Id.Method).ToArray());
    }

    [TestMethod]
    [Timeout(120_000)]
    public async Task XUnit_theory_with_unserialisable_data_reports_its_rows_under_the_theory_uid()
    {
        var (outcome, updates) = await Run(_sample, "Shop.Theory.Tests");

        Assert.AreEqual(ProjectRunStatus.Completed, outcome.Status, outcome.Error);
        var discovered = updates.Where(u => u.State == TestState.Queued && u.Id.Method?.StartsWith("Ten_percent_off_never_costs_more") == true).ToList();
        Assert.AreEqual(1, discovered.Count, "the theory is discovered once, without its rows");
        var rows = Final(updates).Values.Where(u => u.Id.Uid == discovered[0].Id.Uid && u.Id.Key != discovered[0].Id.Key).ToList();
        Assert.AreEqual(3, rows.Count, "each data row reports a result with the theory's UID");
        Assert.IsTrue(rows.All(r => r.State == TestState.Passed), string.Join("\n", rows.Select(r => r.Message)));

        var listed = Final(updates).Values.Where(u => u.Id.Method?.StartsWith("Bulk_percent") == true).ToList();
        Assert.AreEqual(4, listed.Count, "serialisable rows are listed one by one");
        Assert.AreEqual(4, listed.Select(r => r.Id.Uid).Distinct().Count(), "each with its own UID");
    }

    [TestMethod]
    [Timeout(120_000)]
    public async Task Rows_whose_display_names_are_cut_to_the_same_text_stay_separate_tests()
    {
        var (outcome, updates) = await Run(_sample, "Shop.Theory.Tests");

        Assert.AreEqual(ProjectRunStatus.Completed, outcome.Status, outcome.Error);
        var rows = Final(updates).Values.Where(u => u.Id.Method?.StartsWith("Rows_whose_long_arguments_look_the_same") == true).ToList();
        Assert.AreEqual(2, rows.Count, "two rows, two tests: " + string.Join(", ", rows.Select(r => r.Id.Key)));
        Assert.AreEqual(rows[0].DisplayName, rows[1].DisplayName, "xUnit shows them alike (the precondition of this test)");
        Assert.IsTrue(rows.All(r => r.State == TestState.Passed), string.Join("\n", rows.Select(r => r.Message)));

        var listed = Final(updates).Values.Where(u => u.Id.Method?.StartsWith("Bulk_percent") == true).ToList();
        Assert.IsTrue(listed.All(r => !r.Id.Key.Contains(r.Id.Uid)), "keys that don't clash are left as they were");
    }

    [TestMethod]
    [Timeout(120_000)]
    public async Task ShouldRun_false_reports_excluded_and_does_not_run()
    {
        var (outcome, updates) = await Run(_sample, "Shop.Core.Tests", id => id.Method != "Total_zero_qty");

        Assert.AreEqual(ProjectRunStatus.Completed, outcome.Status, outcome.Error);
        var final = Final(updates);
        Assert.AreEqual(TestState.Excluded, final.Values.Single(r => r.Id.Method == "Total_zero_qty").State);
        Assert.AreEqual(TestState.Passed, final.Values.Single(r => r.Id.Method == "Total_multiplies").State);
        Assert.IsFalse(updates.Any(u => u.Id.Method == "Total_zero_qty" && u.State is TestState.Passed or TestState.Running));
    }

    [TestMethod]
    [Timeout(300_000)]
    public async Task Failing_test_reports_message_and_repo_stack()
    {
        var sample = await MirroredSample.CreateAsync();
        var calc = Path.Combine(sample.Layout.MirrorRoot, "src/Shop.Core/PriceCalculator.cs");
        File.WriteAllText(calc, File.ReadAllText(calc).Replace("unit * qty", "unit + qty"));
        await sample.BuildAllAsync();

        var (outcome, updates) = await Run(sample, "Shop.Core.Tests");

        Assert.AreEqual(ProjectRunStatus.Completed, outcome.Status, outcome.Error);
        var failed = Final(updates).Values.Single(r => r.Id.Method == "Total_multiplies");
        Assert.AreEqual(TestState.Failed, failed.State);
        Assert.IsFalse(string.IsNullOrEmpty(failed.Message));
        Assert.IsNotNull(failed.StackTrace);
        Assert.IsFalse(failed.StackTrace!.Contains(".nocrunch"), failed.StackTrace);
    }

    [TestMethod]
    [Timeout(60_000)]
    public async Task Cancellation_returns_Cancelled()
    {
        using var cts = new CancellationTokenSource();
        await cts.CancelAsync();

        var (outcome, _) = await Run(_sample, "Shop.Core.Tests", ct: cts.Token);

        Assert.AreEqual(ProjectRunStatus.Cancelled, outcome.Status);
    }
}
