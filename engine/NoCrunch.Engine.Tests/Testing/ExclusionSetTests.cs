using System.Text.Json;
using NoCrunch.Engine.Testing;

namespace NoCrunch.Engine.Tests.Testing;

[TestClass]
public class ExclusionSetTests
{
    private const string E2E = "tests/E2E/E2E.csproj";
    private const string Unit = "tests/Unit/Unit.csproj";

    // ---- Shared cases: testdata/exclusions.json at the repo root, also run by the plugin's ExclusionRulesTest ----

    private sealed record CaseFile(string About, List<Case> Cases);
    private sealed record Case(string Name, List<CaseRule> Rules, List<CaseTest> Tests);
    private sealed record CaseRule(string Project, string? Type, string? Method, string? Uid, bool Include = false);
    private sealed record CaseTest(string Project, string? Type, string? Method, string Uid, string? Variant, bool Excluded);

    /// <summary>Copied next to the test assembly by the csproj.</summary>
    private static readonly Lazy<IReadOnlyDictionary<string, Case>> SharedCases = new(() =>
    {
        var path = Path.Combine(AppContext.BaseDirectory, "testdata", "exclusions.json");
        var file = JsonSerializer.Deserialize<CaseFile>(File.ReadAllText(path), new JsonSerializerOptions(JsonSerializerDefaults.Web))
                   ?? throw new InvalidDataException($"{path} is empty");
        return file.Cases.ToDictionary(c => c.Name);
    });

    public static IEnumerable<TestDataRow<string>> SharedCaseNames =>
        SharedCases.Value.Keys.Select(name => new TestDataRow<string>(name) { DisplayName = name });

    [TestMethod]
    [DynamicData(nameof(SharedCaseNames))]
    public void Shared_case(string name)
    {
        var c = SharedCases.Value[name];
        var set = new ExclusionSet(c.Rules.Select(r => new ExclusionRule(r.Project, r.Type, r.Method, r.Uid, r.Include)));

        var wrong = c.Tests
            .Where(t => set.IsExcluded(new TestId(t.Project, t.Type, t.Method, t.Uid, t.Variant)) != t.Excluded)
            .Select(t => $"  {t} should{(t.Excluded ? "" : " not")} be excluded")
            .ToList();
        Assert.IsEmpty(wrong, $"'{name}':\n{string.Join("\n", wrong)}");
    }

    [TestMethod]
    public void Shared_cases_are_loaded() => Assert.IsGreaterThan(10, SharedCases.Value.Count);

    // ---- Engine-only: the plugin has no equivalent of ExcludesWholeProject ----

    [TestMethod]
    public void Project_rule_excludes_whole_project()
    {
        var set = new ExclusionSet([new ExclusionRule(E2E, null, null, null)]);
        Assert.IsTrue(set.ExcludesWholeProject(E2E));
        Assert.IsFalse(set.ExcludesWholeProject(Unit));
    }

    [TestMethod]
    public void Class_or_test_rules_alone_do_not_exclude_whole_project()
    {
        var set = new ExclusionSet([new ExclusionRule(Unit, "A.C", null, null), new ExclusionRule(Unit, "A.D", "One", null)]);
        Assert.IsFalse(set.ExcludesWholeProject(Unit));
    }

    [TestMethod]
    public void Any_include_rule_inside_stops_project_being_wholly_excluded()
    {
        var set = new ExclusionSet([new ExclusionRule(Unit, null, null, null), new ExclusionRule(Unit, null, null, "u1", Include: true)]);
        Assert.IsFalse(set.ExcludesWholeProject(Unit));
    }

    [TestMethod]
    public void Project_match_is_case_sensitive() =>
        Assert.IsFalse(new ExclusionSet([new ExclusionRule(Unit, null, null, null)]).ExcludesWholeProject(Unit.ToLowerInvariant()));

    [TestMethod]
    public void Empty_excludes_nothing()
    {
        Assert.IsFalse(ExclusionSet.Empty.IsExcluded(new TestId(Unit, "A", "B", "u1")));
        Assert.IsFalse(ExclusionSet.Empty.ExcludesWholeProject(Unit));
    }
}
