using System.Diagnostics;
using System.Security.Cryptography;
using NoCrunch.Engine.Build;
using NoCrunch.Engine.Coverage;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Testing;
using NoCrunch.Engine.Tests.TestSupport;

namespace NoCrunch.Engine.Tests.Coverage;

/// <summary>
/// The real thing on the sample solution (a temp copy with its Add broken and an uncalled method added): real
/// dotnet-coverage, real test hosts. Inconclusive when dotnet-coverage is not installed.
/// </summary>
[TestClass]
[DoNotParallelize]
public class CoverageEndToEndTests
{
    private const string InventoryRel = "src/Shop.Catalog/Inventory.cs";
    private static MirroredSample? _sample;
    private static string? _unavailable;
    private static TestProjectPass? _catalogPass;
    private static TestProjectPass? _theoryPass;
    private static IReadOnlyList<FileMarkup> _markup = [];
    private static string _dllHashBefore = "";
    private static string _dllHashAfter = "";
    private static int _collectorsBefore;
    private static int _collectorsAfter;

    [ClassInitialize]
    public static async Task Init(TestContext _)
    {
        var runner = new ProcessRunner();
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (await DotnetCoverageTool.DetectAsync(runner, home, Environment.GetEnvironmentVariable("PATH"), CancellationToken.None) is null)
        {
            _unavailable = "dotnet-coverage is not installed";
            return;
        }
        _sample = await MirroredSample.CreateAsync(repo =>
        {
            var file = Path.Combine(repo, "src", "Shop.Catalog", "Inventory.cs");
            var text = File.ReadAllText(file)
                .Replace("existing.Quantity + quantity)", "existing.Quantity + quantity + 2)")
                .Replace("    public int QuantityOf", "    public int Unused()\n    {\n        var x = 41;\n        return x + 1;\n    }\n\n    public int QuantityOf");
            File.WriteAllText(file, text);
        });
        await _sample.BuildAllAsync();

        var factory = await CoverageRunFactory.CreateAsync(runner, home, Environment.GetEnvironmentVariable("PATH"), _sample.Map, _sample.Layout,
            (_, _) => Task.CompletedTask, CancellationToken.None);
        var catalog = _sample.Graph.Projects.Single(p => p.Name == "Shop.Catalog.Tests");
        var catalogDll = Path.Combine(Path.GetDirectoryName(catalog.TargetPath)!, "Shop.Catalog.dll");
        _dllHashBefore = Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(catalogDll)));
        _collectorsBefore = Process.GetProcessesByName("dotnet-coverage").Length;

        _catalogPass = await Collect(factory, catalog);
        _theoryPass = await Collect(factory, _sample.Graph.Projects.Single(p => p.Name == "Shop.Theory.Tests"));

        _dllHashAfter = Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(catalogDll)));
        _collectorsAfter = Process.GetProcessesByName("dotnet-coverage").Length;

        var book = new CoverageBook();
        foreach (var pass in new[] { _catalogPass, _theoryPass }.OfType<TestProjectPass>()) book.Replace(pass);
        var graph = _sample.Graph;
        _markup = book.Markup(f => graph.OwnersOf(f),
            prod => graph.WithDependents([prod]).Where(t => graph.Find(t)?.IsTestProject == true).ToHashSet(StringComparer.Ordinal));
    }

    private static async Task<TestProjectPass?> Collect(CoverageRunFactory factory, ProjectInfo project)
    {
        await using var run = await factory.StartAsync(project, _sample!.Graph, CancellationToken.None);
        if (run is null) return null;
        var outcome = await new MtpTestRunner(_sample.Map).RunAsync(project, _ => true, _ => Task.CompletedTask, CancellationToken.None, run);
        Assert.AreEqual(ProjectRunStatus.Completed, outcome.Status, outcome.Error);
        return run.Pass;
    }

    private static void RequireTool()
    {
        if (_unavailable is not null) Assert.Inconclusive(_unavailable);
    }

    private static int LineOf(string repoRelative, string text) =>
        File.ReadAllLines(_sample!.Map.RepoPath(repoRelative)).Select((l, i) => (l, i)).First(x => x.l.Contains(text)).i + 1;

    private static FileMarkup Markup(string file) => _markup.Single(m => m.Path == file);

    [TestMethod]
    [Timeout(900_000)]
    public void Catalog_tests_cover_sku_parser_as_passing_and_the_broken_add_as_failing_and_flag_the_uncalled_method()
    {
        RequireTool();
        Assert.IsNotNull(_catalogPass, "the Catalog test project got a pass");

        var sku = Markup("src/Shop.Catalog/SkuParser.cs");
        Assert.IsTrue(sku.Passing.Count > 0);
        Assert.AreEqual(0, sku.Failing.Count);
        Assert.AreEqual(0, sku.NotCovered.Count, "every SkuParser line runs");

        var inventory = Markup(InventoryRel);
        var broken = LineOf(InventoryRel, "existing.Quantity + quantity + 2");
        CollectionAssert.Contains(inventory.Failing.ToArray(), broken, "failing: " + string.Join(",", inventory.Failing));
        var uncalled = LineOf(InventoryRel, "return x + 1;");
        CollectionAssert.Contains(inventory.NotCovered.ToArray(), uncalled, "not covered: " + string.Join(",", inventory.NotCovered));
        CollectionAssert.DoesNotContain(inventory.Passing.ToArray(), uncalled);
    }

    [TestMethod]
    [Timeout(900_000)]
    public void Xunit_project_gets_coverage_too()
    {
        RequireTool();
        Assert.IsNotNull(_theoryPass, "the xUnit Theory test project got a pass");
        Assert.IsTrue(_theoryPass.Classes.Count > 0);
        Assert.IsTrue(_theoryPass.Classes.SelectMany(c => c.Files.Values).Any(f => f.Hit.Count > 0));
    }

    [TestMethod]
    [Timeout(900_000)]
    public void Original_build_output_is_untouched()
    {
        RequireTool();
        Assert.AreEqual(_dllHashBefore, _dllHashAfter);
    }

    [TestMethod]
    [Timeout(900_000)]
    public void No_collector_process_remains()
    {
        RequireTool();
        Assert.AreEqual(_collectorsBefore, _collectorsAfter);
    }

    [TestMethod]
    [Timeout(900_000)]
    public void Cobertura_filenames_map_back_into_the_repo()
    {
        RequireTool();
        var files = new[] { _catalogPass, _theoryPass }.OfType<TestProjectPass>().SelectMany(p => p.Classes).SelectMany(c => c.Files.Keys).Distinct().ToList();
        Assert.IsTrue(files.Count > 0);
        foreach (var rel in files)
        {
            Assert.IsTrue(File.Exists(_sample!.Map.RepoPath(rel)), rel);
            Assert.IsFalse(rel.Contains(".nocrunch") || rel.Contains("obj/"), rel);
        }
    }
}
