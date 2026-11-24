using NoCrunch.Engine.Coverage;
using NoCrunch.Engine.Tests.Layout;

namespace NoCrunch.Engine.Tests.Coverage;

[TestClass]
public class CoverageStoreTests
{
    private static string NewPath() => Path.Combine(TempDir.Create(), "sub", "coverage.json");

    private static TestProjectPass Sample() => new(
        "tests/T1.csproj",
        [new ClassCoverage("Ns.C", new Dictionary<string, IReadOnlySet<int>> { ["src/A.cs"] = new HashSet<int> { 2 } })],
        new Dictionary<string, IReadOnlySet<int>> { ["src/A.cs"] = new HashSet<int> { 1, 2, 3 } },
        new Dictionary<string, IReadOnlySet<int>> { ["src/A.cs"] = new HashSet<int> { 2 } },
        new HashSet<string> { "Ns.Bad" },
        new Dictionary<string, string> { ["src/A.cs"] = "abc" },
        new HashSet<string> { "src/Prod.csproj" },
        Stale: true);

    [TestMethod]
    public void Round_trips_a_pass()
    {
        var path = NewPath();
        new CoverageStore(path).Save([Sample()]);

        var loaded = new CoverageStore(path).Load().Single();
        var expected = Sample();

        Assert.AreEqual(expected.TestProject, loaded.TestProject);
        Assert.AreEqual(expected.Stale, loaded.Stale);
        CollectionAssert.AreEquivalent(expected.Assemblies.ToArray(), loaded.Assemblies.ToArray());
        CollectionAssert.AreEquivalent(expected.UnrefinedFailingClasses.ToArray(), loaded.UnrefinedFailingClasses.ToArray());
        Assert.AreEqual("abc", loaded.FileHashes["src/A.cs"]);
        CollectionAssert.AreEquivalent(new[] { 2 }, loaded.FailingHit["src/A.cs"].ToArray());
        var cls = loaded.Classes.Single();
        Assert.AreEqual("Ns.C", cls.TestClass);
        CollectionAssert.AreEquivalent(new[] { 1, 2, 3 }, loaded.Executable["src/A.cs"].ToArray());
        CollectionAssert.AreEquivalent(new[] { 2 }, cls.Files["src/A.cs"].ToArray());
    }

    [TestMethod]
    public void Missing_file_loads_empty() => Assert.AreEqual(0, new CoverageStore(NewPath()).Load().Count);

    [TestMethod]
    public void Corrupt_file_loads_empty()
    {
        var path = NewPath();
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, "{ not json");

        Assert.AreEqual(0, new CoverageStore(path).Load().Count);
    }
}
