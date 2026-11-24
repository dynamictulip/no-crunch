using NoCrunch.Engine.Coverage;

namespace NoCrunch.Engine.Tests.Coverage;

[TestClass]
public class CoverageBookTests
{
    private const string Prod = "src/Prod.csproj";
    private const string File1 = "src/Prod/A.cs";

    private static LineSet L(int[] executable, int[] hit) => new(executable.ToHashSet(), hit.ToHashSet());

    private static IReadOnlySet<int> Set(params int[] lines) => lines.ToHashSet();

    private static TestProjectPass Pass(string testProject = "tests/T1.csproj", params ClassCoverage[] classes) => new(
        testProject, classes, new Dictionary<string, IReadOnlySet<int>>(), new HashSet<string>(),
        new Dictionary<string, string> { [File1] = "h" }, new HashSet<string> { Prod });

    private static ClassCoverage Class(string name, int[] executable, int[] hit) =>
        new(name, new Dictionary<string, LineSet> { [File1] = L(executable, hit) });

    private static IReadOnlyList<FileMarkup> Markup(CoverageBook book, params string[] coveringTestProjects) =>
        book.Markup(_ => new HashSet<string> { Prod }, _ => coveringTestProjects.Length == 0 ? new HashSet<string> { "tests/T1.csproj" } : coveringTestProjects.ToHashSet());

    [TestMethod]
    public void Line_hit_by_passing_class_is_passing()
    {
        var book = new CoverageBook();
        book.Replace(Pass(classes: Class("C", [1, 2, 3], [1, 2])));

        var m = Markup(book).Single();

        Assert.AreEqual(File1, m.Path);
        Assert.AreEqual("h", m.Hash);
        CollectionAssert.AreEqual(new[] { 1, 2 }, m.Passing.ToArray());
        CollectionAssert.AreEqual(Array.Empty<int>(), m.Failing.ToArray());
    }

    [TestMethod]
    public void Line_in_failing_hit_is_failing_even_if_also_hit_by_passing()
    {
        var book = new CoverageBook();
        book.Replace(Pass(classes: Class("C", [1, 2, 3], [1, 2])) with { FailingHit = new Dictionary<string, IReadOnlySet<int>> { [File1] = Set(2) } });

        var m = Markup(book).Single();

        CollectionAssert.AreEqual(new[] { 2 }, m.Failing.ToArray());
        CollectionAssert.AreEqual(new[] { 1 }, m.Passing.ToArray());
    }

    [TestMethod]
    public void Executable_line_hit_by_nothing_is_not_covered_when_all_covering_test_projects_have_a_pass()
    {
        var book = new CoverageBook();
        book.Replace(Pass(classes: Class("C", [1, 2, 3], [1, 2])));

        CollectionAssert.AreEqual(new[] { 3 }, Markup(book).Single().NotCovered.ToArray());
    }

    [TestMethod]
    public void Unhit_line_gets_no_marker_while_a_covering_test_project_has_no_pass()
    {
        var book = new CoverageBook();
        book.Replace(Pass(classes: Class("C", [1, 2, 3], [1, 2])));

        var m = Markup(book, "tests/T1.csproj", "tests/T2.csproj").Single();

        Assert.AreEqual(0, m.NotCovered.Count);
        CollectionAssert.AreEqual(new[] { 1, 2 }, m.Passing.ToArray());
    }

    [TestMethod]
    public void Unhit_line_gets_no_marker_when_the_assembly_was_not_instrumented_in_a_pass()
    {
        var book = new CoverageBook();
        book.Replace(Pass(classes: Class("C", [1, 2, 3], [1, 2])) with { Assemblies = new HashSet<string>() });

        Assert.AreEqual(0, Markup(book).Single().NotCovered.Count);
    }

    [TestMethod]
    public void Lines_hit_by_a_class_with_an_unrefined_failure_get_no_marker_unless_in_failing_hit()
    {
        var book = new CoverageBook();
        book.Replace(Pass(classes: [Class("Bad", [1, 2, 3, 4], [1, 2]), Class("Good", [1, 2, 3, 4], [3])]) with
        {
            UnrefinedFailingClasses = new HashSet<string> { "Bad" },
            FailingHit = new Dictionary<string, IReadOnlySet<int>> { [File1] = Set(2) },
        });

        var m = Markup(book).Single();

        CollectionAssert.AreEqual(new[] { 2 }, m.Failing.ToArray());
        CollectionAssert.AreEqual(new[] { 3 }, m.Passing.ToArray());   // line 1 is hit by Bad only: no marker
        CollectionAssert.AreEqual(new[] { 4 }, m.NotCovered.ToArray());
    }

    [TestMethod]
    public void Replace_swaps_one_test_projects_contribution_and_keeps_the_others()
    {
        var book = new CoverageBook();
        book.Replace(Pass("tests/T1.csproj", Class("C", [1, 2, 3], [1])));
        book.Replace(Pass("tests/T2.csproj", Class("C", [1, 2, 3], [2])));
        book.Replace(Pass("tests/T1.csproj", Class("C", [1, 2, 3], [3])));

        var m = Markup(book, "tests/T1.csproj", "tests/T2.csproj").Single();

        CollectionAssert.AreEqual(new[] { 2, 3 }, m.Passing.ToArray());
        CollectionAssert.AreEqual(new[] { 1 }, m.NotCovered.ToArray());
    }

    [TestMethod]
    public void Markup_is_stale_when_any_contributing_pass_is_stale()
    {
        var book = new CoverageBook();
        book.Replace(Pass("tests/T1.csproj", Class("C", [1, 2], [1])));
        book.Replace(Pass("tests/T2.csproj", Class("C", [1, 2], [2])) with { Stale = true });

        Assert.IsTrue(Markup(book, "tests/T1.csproj", "tests/T2.csproj").Single().Stale);
    }

    [TestMethod]
    public void MarkStale_reports_whether_anything_changed()
    {
        var book = new CoverageBook();
        book.Replace(Pass(classes: Class("C", [1], [1])));
        var stale = new HashSet<string> { "tests/T1.csproj" };

        Assert.IsTrue(book.MarkStale(stale));
        Assert.IsFalse(book.MarkStale(stale));
        Assert.IsFalse(book.MarkStale(new HashSet<string> { "tests/Other.csproj" }));
        Assert.IsTrue(Markup(book).Single().Stale);
    }

    [TestMethod]
    public void Replace_clears_stale()
    {
        var book = new CoverageBook();
        book.Replace(Pass(classes: Class("C", [1], [1])));
        book.MarkStale(new HashSet<string> { "tests/T1.csproj" });
        book.Replace(Pass(classes: Class("C", [1], [1])));

        Assert.IsFalse(Markup(book).Single().Stale);
    }

    [TestMethod]
    public void File_is_omitted_when_contributing_passes_disagree_on_its_hash()
    {
        var book = new CoverageBook();
        book.Replace(Pass("tests/T1.csproj", Class("C", [1], [1])));
        book.Replace(Pass("tests/T2.csproj", Class("C", [1], [1])) with { FileHashes = new Dictionary<string, string> { [File1] = "other" } });

        Assert.AreEqual(0, Markup(book, "tests/T1.csproj", "tests/T2.csproj").Count);
    }

    [TestMethod]
    public void Restore_marks_passes_stale()
    {
        var book = new CoverageBook();
        book.Restore([Pass(classes: Class("C", [1], [1]))]);

        Assert.IsTrue(Markup(book).Single().Stale);
        Assert.IsTrue(book.All().Single().Stale);
    }
}
