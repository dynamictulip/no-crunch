using NoCrunch.Engine.Coverage;

namespace NoCrunch.Engine.Tests.Coverage;

[TestClass]
public class CoberturaParserTests
{
    private static string Report(string sources, string classes) =>
        $"<?xml version=\"1.0\"?><coverage><sources>{sources}</sources><packages><package><classes>{classes}</classes></package></packages></coverage>";

    private static string Class(string file, params (int line, int hits)[] lines) =>
        $"<class filename=\"{file}\"><lines>{string.Join("", lines.Select(l => $"<line number=\"{l.line}\" hits=\"{l.hits}\"/>"))}</lines></class>";

    [TestMethod]
    public void Hit_lines_are_those_with_hits_above_zero()
    {
        var xml = Report("", Class("/m/A.cs", (1, 0), (2, 5), (3, 0)) + Class("/m/A.cs", (3, 3), (4, 0)));

        var map = CoberturaParser.Parse(xml);

        var a = map["/m/A.cs"];
        CollectionAssert.AreEquivalent(new[] { 1, 2, 3, 4 }, a.Executable.ToArray());
        CollectionAssert.AreEquivalent(new[] { 2, 3 }, a.Hit.ToArray());
    }

    [TestMethod]
    public void Relative_filenames_resolve_against_the_first_source()
    {
        var xml = Report("<source>/m/src</source><source>/other</source>", Class("Sub/B.cs", (1, 1)));

        var map = CoberturaParser.Parse(xml);

        CollectionAssert.AreEqual(new[] { "/m/src/Sub/B.cs" }, map.Keys.ToArray());
    }

    [TestMethod]
    public void Empty_report_gives_empty_map()
    {
        Assert.AreEqual(0, CoberturaParser.Parse(Report("", "")).Count);
        Assert.AreEqual(0, CoberturaParser.Parse("").Count);
    }
}
