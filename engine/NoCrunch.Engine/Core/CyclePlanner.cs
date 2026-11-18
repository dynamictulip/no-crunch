using NoCrunch.Engine.Build;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Testing;

namespace NoCrunch.Engine.Core;

/// <summary>What a cycle was asked to do, once its changed files are in the mirror.</summary>
/// <param name="Projects">Projects to build and test regardless of files (e.g. left over from an interrupted cycle).</param>
/// <param name="ChangedFiles">Repo-relative paths copied into the mirror.</param>
/// <param name="ManualKeys">Tests the user asked to run, by <see cref="TestId.Key"/>.</param>
/// <param name="RerunKeys">Tests to run once as part of normal (automatic) testing, e.g. just re-included.</param>
internal sealed record CycleRequest(
    IReadOnlySet<string> Projects,
    IReadOnlySet<string> ChangedFiles,
    bool All,
    IReadOnlySet<string> ManualKeys,
    IReadOnlySet<string> RerunKeys);

/// <param name="Build">Projects to build, then test if they are test projects. Empty when there is nothing to do.</param>
/// <param name="ManualOnly">Projects in the cycle only for tests asked for by hand: only those tests run.</param>
/// <param name="Skipped">Test projects excluded as a whole, left out of the cycle.</param>
/// <param name="CycleProjects">What the cycle builds, tests or builds against: new work touching these interrupts it.</param>
/// <param name="RunKeys">Tests to run even if excluded: manual runs plus re-included tests.</param>
/// <param name="RunFirst">Projects with failing tests, which run before the rest.</param>
/// <param name="TestProjects">The test projects in <see cref="Build"/>, in build order.</param>
internal sealed record CyclePlan(
    IReadOnlySet<string> Build,
    IReadOnlySet<string> ManualOnly,
    IReadOnlySet<string> Skipped,
    IReadOnlySet<string> CycleProjects,
    IReadOnlySet<string> RunKeys,
    IReadOnlySet<string> RunFirst,
    IReadOnlyList<ProjectInfo> TestProjects)
{
    public bool IsEmpty => Build.Count == 0;

    /// <summary>Projects whose results go stale: a manual-only project keeps the results of tests it doesn't run.</summary>
    public IReadOnlySet<string> Stale => Build.Where(p => !ManualOnly.Contains(p)).ToHashSet(StringComparer.Ordinal);
}

/// <summary>Works out which projects a cycle builds and tests. Pure: everything it needs is passed in.</summary>
internal static class CyclePlanner
{
    /// <param name="unhealthy">Projects whose last build failed.</param>
    /// <param name="builtThisSession">Projects with build outputs from this session.</param>
    /// <param name="failing">Projects with failing tests.</param>
    public static CyclePlan Plan(CycleRequest request, ProjectGraph graph, ExclusionSet exclusions,
        IReadOnlySet<string> unhealthy, IReadOnlySet<string> builtThisSession, IReadOnlySet<string> failing)
    {
        var dirty = new HashSet<string>(request.Projects, StringComparer.Ordinal);
        dirty.UnionWith(graph.DirtyProjects(request.ChangedFiles));
        if (request.All) dirty.UnionWith(graph.Projects.Select(p => p.RepoRelativePath));
        var runKeys = request.ManualKeys.Concat(request.RerunKeys).ToHashSet(StringComparer.Ordinal);
        var manualProjects = runKeys.Select(ProjectOfKey).OfType<string>().Where(p => graph.Find(p) is not null).ToHashSet(StringComparer.Ordinal);
        var manualOnly = manualProjects.Where(p => !dirty.Contains(p)).ToHashSet(StringComparer.Ordinal);
        dirty.UnionWith(manualProjects);

        // Test projects excluded as a whole aren't built or run, unless a test in them was asked for by hand.
        var skipped = graph.Projects
            .Where(p => p.IsTestProject && exclusions.ExcludesWholeProject(p.RepoRelativePath) && !manualProjects.Contains(p.RepoRelativePath))
            .Select(p => p.RepoRelativePath).ToHashSet(StringComparer.Ordinal);
        dirty.ExceptWith(skipped);

        if (dirty.Count == 0)
            return new CyclePlan(dirty, manualOnly, skipped, new HashSet<string>(), runKeys, failing, []);

        // Projects that failed earlier get another go, with their dependents; references not yet built
        // this session are included so a project never builds against missing outputs.
        dirty.UnionWith(graph.WithDependents(unhealthy));
        dirty.ExceptWith(skipped);
        var closure = graph.WithReferences(dirty);
        dirty.UnionWith(closure.Where(p => unhealthy.Contains(p) || !builtThisSession.Contains(p)));

        return new CyclePlan(dirty, manualOnly, skipped, closure.Concat(dirty).ToHashSet(StringComparer.Ordinal), runKeys, failing,
            graph.InBuildOrder(dirty).Where(p => p.IsTestProject).ToList());
    }

    /// <summary>
    /// The plan's test projects that can run once the build is done: MTP projects that built, with no reference left
    /// broken. Previously failing projects go first, for the quickest news on whether they are fixed.
    /// </summary>
    public static IReadOnlyList<ProjectInfo> RunOrder(CyclePlan plan, ProjectGraph graph,
        IReadOnlyDictionary<string, BuildOutcome> outcomes, IReadOnlySet<string> unhealthy) =>
        plan.TestProjects
            .Where(p => p.IsMtp && outcomes.GetValueOrDefault(p.RepoRelativePath)?.Status == BuildStatus.Succeeded)
            .Where(p => !graph.WithReferences([p.RepoRelativePath]).Any(unhealthy.Contains))
            .OrderByDescending(p => plan.RunFirst.Contains(p.RepoRelativePath))
            .ThenBy(p => p.RepoRelativePath, StringComparer.Ordinal)
            .ToList();

    /// <summary>The project part of a <see cref="TestId.Key"/>, or null if the key is malformed (it then matches no test).</summary>
    public static string? ProjectOfKey(string key) => key.IndexOf('|') is var i and >= 0 ? key[..i] : null;
}
