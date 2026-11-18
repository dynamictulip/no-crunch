using NoCrunch.Engine.Build;
using NoCrunch.Engine.Core;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Testing;
using NoCrunch.Engine.Tests.Graph;

namespace NoCrunch.Engine.Tests.Core;

[TestClass]
public class CyclePlannerTests
{
    private const string Core = "src/Core/Core.csproj";
    private const string CoreTests = "tests/Core.Tests/Core.Tests.csproj";
    private const string Other = "tests/Other.Tests/Other.Tests.csproj";
    private const string Legacy = "tests/Legacy.Tests/Legacy.Tests.csproj";

    private static Task<ProjectGraph> Graph() => ProjectGraphTests.Load(
        ProjectGraphTests.P(Core, items: ["src/Core/Calc.cs"]),
        ProjectGraphTests.P(CoreTests, test: true, refs: [Core], items: ["tests/Core.Tests/CalcTests.cs"]),
        ProjectGraphTests.P(Other, test: true, items: ["tests/Other.Tests/OtherTests.cs"]),
        ProjectGraphTests.P(Legacy, test: true) with { IsMtp = false });

    private static HashSet<string> Set(params string[] items) => new(items, StringComparer.Ordinal);

    private static CycleRequest Request(string[]? projects = null, string[]? files = null, bool all = false,
        string[]? manualKeys = null, string[]? rerunKeys = null) =>
        new(Set(projects ?? []), Set(files ?? []), all, Set(manualKeys ?? []), Set(rerunKeys ?? []));

    private static CyclePlan Plan(ProjectGraph graph, CycleRequest request, ExclusionSet? exclusions = null,
        string[]? unhealthy = null, string[]? built = null, string[]? failing = null) =>
        CyclePlanner.Plan(request, graph, exclusions ?? ExclusionSet.Empty, Set(unhealthy ?? []),
            // By default everything has been built this session, so only what the request touches is built.
            built is null ? graph.Projects.Select(p => p.RepoRelativePath).ToHashSet() : Set(built), Set(failing ?? []));

    [TestMethod]
    public async Task Changed_files_and_requested_projects_are_built_with_their_dependents()
    {
        var plan = Plan(await Graph(), Request(projects: [Other], files: ["src/Core/Calc.cs"]));
        CollectionAssert.AreEquivalent(new[] { Core, CoreTests, Other }, plan.Build.ToArray());
    }

    [TestMethod]
    public async Task Run_all_plans_every_project()
    {
        var plan = Plan(await Graph(), Request(all: true));
        CollectionAssert.AreEquivalent(new[] { Core, CoreTests, Other, Legacy }, plan.Build.ToArray());
    }

    [TestMethod]
    public async Task A_test_asked_for_in_an_unchanged_project_runs_that_project_manual_only()
    {
        var plan = Plan(await Graph(), Request(files: ["tests/Other.Tests/OtherTests.cs"],
            manualKeys: [$"{CoreTests}|T|A", $"{Other}|T|O"], rerunKeys: [$"{CoreTests}|T|B"]));

        CollectionAssert.AreEquivalent(new[] { CoreTests, Other }, plan.Build.ToArray());
        CollectionAssert.AreEquivalent(new[] { CoreTests }, plan.ManualOnly.ToArray(), "Other changed, so all its tests run");
        CollectionAssert.AreEquivalent(new[] { Other }, plan.Stale.ToArray(), "a manual-only project's other results stay as they are");
        CollectionAssert.AreEquivalent(new[] { $"{CoreTests}|T|A", $"{Other}|T|O", $"{CoreTests}|T|B" }, plan.RunKeys.ToArray());
    }

    [TestMethod]
    public async Task Malformed_or_unknown_test_keys_plan_nothing()
    {
        var plan = Plan(await Graph(), Request(manualKeys: ["not-a-test-key", "tests/Gone/Gone.csproj|T|A"]));
        Assert.IsTrue(plan.IsEmpty);
    }

    [TestMethod]
    public async Task A_wholly_excluded_test_project_is_skipped_unless_a_test_in_it_is_asked_for()
    {
        var graph = await Graph();
        var excludeOther = new ExclusionSet([new ExclusionRule(Other, null, null, null)]);

        var plan = Plan(graph, Request(all: true), excludeOther);
        CollectionAssert.AreEquivalent(new[] { Other }, plan.Skipped.ToArray());
        CollectionAssert.DoesNotContain(plan.Build.ToArray(), Other);

        plan = Plan(graph, Request(all: true, manualKeys: [$"{Other}|T|O"]), excludeOther);
        Assert.AreEqual(0, plan.Skipped.Count);
        CollectionAssert.Contains(plan.Build.ToArray(), Other);
    }

    [TestMethod]
    public async Task Projects_that_failed_to_build_join_the_next_cycle_with_their_dependents_but_never_start_one()
    {
        var graph = await Graph();

        Assert.IsTrue(Plan(graph, Request(), unhealthy: [Core]).IsEmpty);

        var plan = Plan(graph, Request(projects: [Other]), unhealthy: [Core]);
        CollectionAssert.AreEquivalent(new[] { Core, CoreTests, Other }, plan.Build.ToArray());
    }

    [TestMethod]
    public async Task References_not_built_this_session_are_built_and_the_rest_are_only_built_against()
    {
        var graph = await Graph();

        var plan = Plan(graph, Request(projects: [CoreTests]), built: [Core]);
        CollectionAssert.AreEquivalent(new[] { CoreTests }, plan.Build.ToArray());
        CollectionAssert.AreEquivalent(new[] { Core, CoreTests }, plan.CycleProjects.ToArray(), "a change to Core still affects this cycle");

        plan = Plan(graph, Request(projects: [CoreTests]), built: []);
        CollectionAssert.AreEquivalent(new[] { Core, CoreTests }, plan.Build.ToArray());
    }

    [TestMethod]
    public async Task Test_projects_run_failing_first_once_they_and_their_references_have_built()
    {
        var graph = await Graph();
        var plan = Plan(graph, Request(all: true), failing: [Other]);
        var succeeded = plan.Build.ToDictionary(p => p, p => new BuildOutcome(p, BuildStatus.Succeeded, "", null));

        CollectionAssert.AreEqual(new[] { CoreTests, Legacy, Other }, plan.TestProjects.Select(p => p.RepoRelativePath).Order().ToArray());
        CollectionAssert.AreEqual(new[] { Other, CoreTests }, Names(CyclePlanner.RunOrder(plan, graph, succeeded, Set())),
            "previously failing first; the VSTest project is not run");
        CollectionAssert.AreEqual(new[] { Other }, Names(CyclePlanner.RunOrder(plan, graph, succeeded, Set(Core))),
            "a test project built against a broken reference is not run");
        var otherFailed = new Dictionary<string, BuildOutcome>(succeeded) { [Other] = new(Other, BuildStatus.Failed, "", null) };
        CollectionAssert.AreEqual(new[] { CoreTests }, Names(CyclePlanner.RunOrder(plan, graph, otherFailed, Set())));
    }

    private static string[] Names(IEnumerable<ProjectInfo> projects) => projects.Select(p => p.RepoRelativePath).ToArray();
}
