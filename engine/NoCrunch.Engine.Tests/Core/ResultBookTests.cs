using NoCrunch.Engine.Core;
using NoCrunch.Engine.Testing;

namespace NoCrunch.Engine.Tests.Core;

[TestClass]
public class ResultBookTests
{
    private const string P = "tests/P.Tests/P.Tests.csproj";
    private static readonly TestId A = new(P, "T", "A", "A");
    private static readonly TestId B = new(P, "T", "B", "B");
    private static readonly TestId Theory = new(P, "T", "Th", "Th");
    private static TestId Row(string variant) => Theory with { Variant = variant };

    private static TestResultUpdate U(TestId id, TestState s, RunKind kind = RunKind.Auto, string? message = null) =>
        new(id, id.Variant ?? id.Method!, s, null, message, null, null, null, kind);

    private static ResultBook With(params TestResultUpdate[] results)
    {
        var book = new ResultBook();
        var run = book.StartRun(P, new HashSet<string>(), new HashSet<string>(), manualOnly: false);
        run.Discovered(results.Select(r => r with { State = TestState.Queued }).ToList());
        run.Updated(results.Where(r => r.State != TestState.Queued).ToList());
        return book;
    }

    private static Dictionary<string, TestResultUpdate> ByKey(ResultBook book) => book.All().ToDictionary(r => r.Id.Key);

    [TestMethod]
    public void Restored_results_are_stale_with_their_last_outcome_but_excluded_or_manual_ones_start_blank()
    {
        var book = new ResultBook();
        var shown = book.Restore(new Dictionary<string, TestResultUpdate>
        {
            [A.Key] = U(A, TestState.Failed, message: "boom"),
            [B.Key] = U(B, TestState.Passed, RunKind.Manual, "by hand"),
        });

        var byKey = shown.ToDictionary(r => r.Id.Key);
        Assert.AreEqual(TestState.Stale, byKey[A.Key].State);
        Assert.AreEqual(TestState.Failed, byKey[A.Key].LastOutcome);
        Assert.AreEqual("boom", byKey[A.Key].Message);
        Assert.AreEqual(TestState.Stale, byKey[B.Key].State);
        Assert.IsNull(byKey[B.Key].LastOutcome, "an excluded test's earlier manual result is not carried into a new session");
        Assert.IsNull(byKey[B.Key].Message);
        Assert.AreEqual(RunKind.Auto, byKey[B.Key].RunKind);
    }

    [TestMethod]
    public void Marking_stale_keeps_the_last_outcome_and_leaves_excluded_tests_alone()
    {
        var book = With(U(A, TestState.Failed, message: "expected 1"), U(B, TestState.Excluded));

        var stale = book.MarkStale(new HashSet<string> { P });

        Assert.AreEqual(1, stale.Count);
        Assert.AreEqual(TestState.Stale, stale[0].State);
        Assert.AreEqual(TestState.Failed, stale[0].LastOutcome);
        Assert.AreEqual("expected 1", stale[0].Message);
        Assert.AreEqual(TestState.Excluded, ByKey(book)[B.Key].State);
    }

    [TestMethod]
    public void Failing_projects_are_those_with_a_failed_test()
    {
        var book = With(U(A, TestState.Failed), U(B, TestState.Passed));
        CollectionAssert.AreEquivalent(new[] { P }, book.FailingProjects().ToArray());
    }

    [TestMethod]
    public void Exclusions_mark_automatic_results_excluded_keep_manual_ones_and_report_tests_included_again()
    {
        var book = With(U(A, TestState.Passed), U(B, TestState.Passed));
        var manual = book.StartRun(P, new HashSet<string> { B.Key }, new HashSet<string> { B.Key }, manualOnly: true);
        manual.Discovered([U(A, TestState.Excluded), U(B, TestState.Queued)]);
        manual.Updated([U(B, TestState.Passed)]);

        var (excluded, included) = book.ApplyExclusions(new ExclusionSet([new ExclusionRule(P, "T", null, null)]));

        CollectionAssert.AreEqual(new[] { A.Key }, excluded.Select(r => r.Id.Key).ToArray(), "B's manual result is kept");
        Assert.AreEqual(TestState.Passed, excluded[0].LastOutcome);
        Assert.AreEqual(0, included.Count);

        (excluded, included) = book.ApplyExclusions(ExclusionSet.Empty);
        Assert.AreEqual(0, excluded.Count);
        CollectionAssert.AreEqual(new[] { A.Key }, included.ToArray());
    }

    [TestMethod]
    public void A_manual_only_run_keeps_untouched_results_and_reports_only_what_was_asked_for()
    {
        var book = With(U(A, TestState.Failed), U(B, TestState.Passed));
        var run = book.StartRun(P, new HashSet<string> { B.Key }, new HashSet<string> { B.Key }, manualOnly: true);

        var reported = run.Discovered([U(A, TestState.Excluded), U(B, TestState.Queued)]);

        CollectionAssert.AreEqual(new[] { B.Key }, reported.Select(r => r.Id.Key).ToArray());
        Assert.AreEqual(RunKind.Manual, reported[0].RunKind);
        Assert.AreEqual(TestState.Failed, ByKey(book)[A.Key].State);
    }

    [TestMethod]
    public void An_automatic_run_keeps_an_earlier_manual_result_of_an_excluded_test()
    {
        var book = new ResultBook();
        var manual = book.StartRun(P, new HashSet<string> { A.Key }, new HashSet<string> { A.Key }, manualOnly: false);
        manual.Discovered([U(A, TestState.Queued)]);
        manual.Updated([U(A, TestState.Passed)]);

        var auto = book.StartRun(P, new HashSet<string>(), new HashSet<string>(), manualOnly: false);
        var discovered = auto.Discovered([U(A, TestState.Excluded)]);

        Assert.AreEqual(TestState.Passed, discovered.Single().State);
        Assert.AreEqual(RunKind.Manual, discovered.Single().RunKind);
    }

    [TestMethod]
    public void Rows_from_an_earlier_run_follow_their_theory_until_reported_again()
    {
        var book = new ResultBook();
        var first = book.StartRun(P, new HashSet<string>(), new HashSet<string>(), manualOnly: false);
        first.Discovered([U(Theory, TestState.Queued)]);
        first.Updated([U(Row("Th(1)"), TestState.Failed, message: "row failed")]);
        first.SettleTheories();

        var second = book.StartRun(P, new HashSet<string>(), new HashSet<string>(), manualOnly: false);
        var discovered = second.Discovered([U(Theory, TestState.Queued)]).ToDictionary(r => r.Id.Key);

        var row = discovered[Row("Th(1)").Key];
        Assert.AreEqual(TestState.Queued, row.State);
        Assert.AreEqual(Theory.Key, row.TheoryKey);
        Assert.AreEqual(TestState.Failed, row.LastOutcome);
        Assert.AreEqual("row failed", row.Message, "the last outcome's message is carried with it");
    }

    [TestMethod]
    public void A_row_result_names_its_theory_and_marks_the_theory_running()
    {
        var book = new ResultBook();
        var run = book.StartRun(P, new HashSet<string>(), new HashSet<string>(), manualOnly: false);
        run.Discovered([U(Theory, TestState.Queued)]);

        var updated = run.Updated([U(Row("Th(1)"), TestState.Passed)]).ToDictionary(r => r.Id.Key);

        Assert.AreEqual(Theory.Key, updated[Row("Th(1)").Key].TheoryKey);
        Assert.AreEqual(TestState.Running, updated[Theory.Key].State);
    }

    [TestMethod]
    public void A_settled_theory_takes_its_worst_row_and_drops_rows_no_longer_reported()
    {
        var book = new ResultBook();
        var first = book.StartRun(P, new HashSet<string>(), new HashSet<string>(), manualOnly: false);
        first.Discovered([U(Theory, TestState.Queued)]);
        first.Updated([U(Row("Th(1)"), TestState.Passed), U(Row("Th(2)"), TestState.Passed)]);
        first.SettleTheories();

        var second = book.StartRun(P, new HashSet<string>(), new HashSet<string>(), manualOnly: false);
        second.Discovered([U(Theory, TestState.Queued)]);
        second.Updated([U(Row("Th(1)"), TestState.Failed)]);
        var settled = second.SettleTheories()!.ToDictionary(r => r.Id.Key);

        Assert.AreEqual(TestState.Failed, settled[Theory.Key].State);
        Assert.AreEqual("1 of 1 data rows failed", settled[Theory.Key].Message);
        Assert.IsFalse(settled.ContainsKey(Row("Th(2)").Key));
        Assert.IsFalse(ByKey(book).ContainsKey(Row("Th(2)").Key));
    }

    [TestMethod]
    public void Settling_theories_is_a_no_op_when_no_rows_were_reported()
    {
        var book = new ResultBook();
        var run = book.StartRun(P, new HashSet<string>(), new HashSet<string>(), manualOnly: false);
        run.Discovered([U(A, TestState.Queued)]);
        run.Updated([U(A, TestState.Passed)]);
        Assert.IsNull(run.SettleTheories());
    }

    [TestMethod]
    public void After_a_crash_the_running_test_fails_and_queued_ones_go_back_to_stale()
    {
        var book = With(U(A, TestState.Passed), U(B, TestState.Passed));
        var run = book.StartRun(P, new HashSet<string>(), new HashSet<string>(), manualOnly: false);
        run.Discovered([U(A, TestState.Queued), U(B, TestState.Queued)]);
        run.Updated([U(A, TestState.Running)]);

        var settled = run.SettleCrash("exit code 134").ToDictionary(r => r.Id.Key);

        Assert.AreEqual(TestState.Failed, settled[A.Key].State);
        StringAssert.Contains(settled[A.Key].Message, "exit code 134");
        Assert.AreEqual(TestState.Stale, settled[B.Key].State);
        Assert.AreEqual(TestState.Passed, settled[B.Key].LastOutcome);
    }
}
