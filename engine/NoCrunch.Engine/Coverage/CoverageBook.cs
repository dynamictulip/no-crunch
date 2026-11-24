namespace NoCrunch.Engine.Coverage;

/// <summary>What one test class hit: per repo-relative source file, the lines it ran (files it did not touch are left out).</summary>
public sealed record ClassCoverage(string TestClass, IReadOnlyDictionary<string, IReadOnlySet<int>> Files);

/// <summary>
/// One complete coverage pass over one test project.
/// </summary>
/// <param name="Executable">Repo-relative file to its executable lines, kept once for the pass (not per class).</param>
/// <param name="FailingHit">Repo-relative file to the lines hit by failing tests that were rerun alone.</param>
/// <param name="UnrefinedFailingClasses">Classes with a failing test whose alone-rerun did not give usable data.</param>
/// <param name="FileHashes">Repo-relative file to <see cref="ContentHash"/> of the file the pass was collected against.</param>
/// <param name="Assemblies">Repo-relative paths of the production projects that were instrumented.</param>
public sealed record TestProjectPass(
    string TestProject,
    IReadOnlyList<ClassCoverage> Classes,
    IReadOnlyDictionary<string, IReadOnlySet<int>> Executable,
    IReadOnlyDictionary<string, IReadOnlySet<int>> FailingHit,
    IReadOnlySet<string> UnrefinedFailingClasses,
    IReadOnlyDictionary<string, string> FileHashes,
    IReadOnlySet<string> Assemblies,
    bool Stale = false);

/// <summary>A file's line states (1-based, sorted). <see cref="Path"/> is repo-relative inside the book.</summary>
public sealed record FileMarkup(string Path, string Hash, bool Stale,
    IReadOnlyList<int> NotCovered, IReadOnlyList<int> Passing, IReadOnlyList<int> Failing);

/// <summary>
/// Merges the latest pass of every test project into per-file line states. A line is failing if a failing test hit it,
/// otherwise unmarked if a class with an unrefined failure hit it, otherwise passing if any test hit it, otherwise
/// not covered, but only when every test project that covers the file's production project has a pass for it.
/// No marker beats a wrong marker.
/// </summary>
public sealed class CoverageBook
{
    private readonly object _lock = new();
    private readonly Dictionary<string, TestProjectPass> _passes = new(StringComparer.Ordinal);

    public void Replace(TestProjectPass pass)
    {
        lock (_lock) _passes[pass.TestProject] = pass;
    }

    /// <summary>Marks the passes of the given test projects stale. True if any pass changed.</summary>
    public bool MarkStale(IReadOnlySet<string> staleTestProjects)
    {
        var changed = false;
        lock (_lock)
        {
            foreach (var key in _passes.Keys.Where(staleTestProjects.Contains).ToList())
            {
                if (_passes[key].Stale) continue;
                _passes[key] = _passes[key] with { Stale = true };
                changed = true;
            }
        }
        return changed;
    }

    public IReadOnlyList<TestProjectPass> All()
    {
        lock (_lock) return _passes.Values.OrderBy(p => p.TestProject, StringComparer.Ordinal).ToList();
    }

    /// <summary>Loads passes saved earlier. They are stale until a new pass replaces them.</summary>
    public void Restore(IEnumerable<TestProjectPass> passes)
    {
        lock (_lock)
            foreach (var p in passes) _passes[p.TestProject] = p with { Stale = true };
    }

    /// <param name="ownersOf">The production projects that own a repo-relative file.</param>
    /// <param name="testProjectsCovering">Every test project (MTP or not) whose references include a production project.</param>
    public IReadOnlyList<FileMarkup> Markup(Func<string, IReadOnlySet<string>> ownersOf,
        Func<string, IReadOnlySet<string>> testProjectsCovering)
    {
        List<TestProjectPass> passes;
        lock (_lock) passes = _passes.Values.ToList();
        var files = passes.SelectMany(p => p.Executable.Keys).Distinct(StringComparer.Ordinal)
            .OrderBy(f => f, StringComparer.Ordinal);
        var result = new List<FileMarkup>();
        foreach (var file in files)
            if (MarkupFor(file, passes, ownersOf, testProjectsCovering) is { } m) result.Add(m);
        return result;
    }

    private static FileMarkup? MarkupFor(string file, List<TestProjectPass> passes,
        Func<string, IReadOnlySet<string>> ownersOf, Func<string, IReadOnlySet<string>> testProjectsCovering)
    {
        var contributing = passes.Where(p => p.Executable.ContainsKey(file)).ToList();
        var hashes = contributing.Select(p => p.FileHashes.GetValueOrDefault(file)).Distinct().ToList();
        if (hashes.Count != 1 || hashes[0] is not { } hash) return null;

        var executable = new HashSet<int>();
        var hit = new HashSet<int>();
        var unrefinedHit = new HashSet<int>();
        var failing = new HashSet<int>();
        foreach (var pass in contributing)
        {
            if (pass.FailingHit.TryGetValue(file, out var f)) failing.UnionWith(f);
            executable.UnionWith(pass.Executable[file]);
            foreach (var cls in pass.Classes.Where(c => c.Files.ContainsKey(file)))
            {
                var lines = cls.Files[file];
                hit.UnionWith(lines);
                if (pass.UnrefinedFailingClasses.Contains(cls.TestClass)) unrefinedHit.UnionWith(lines);
            }
        }

        var passing = hit.Where(l => !failing.Contains(l) && !unrefinedHit.Contains(l));
        var notCovered = NotCoveredAllowed(file, passes, ownersOf, testProjectsCovering)
            ? executable.Where(l => !hit.Contains(l) && !failing.Contains(l))
            : [];
        var markup = new FileMarkup(file, hash, contributing.Any(p => p.Stale),
            notCovered.Order().ToList(), passing.Order().ToList(), failing.Order().ToList());
        return markup.NotCovered.Count + markup.Passing.Count + markup.Failing.Count == 0 ? null : markup;
    }

    private static bool NotCoveredAllowed(string file, List<TestProjectPass> passes,
        Func<string, IReadOnlySet<string>> ownersOf, Func<string, IReadOnlySet<string>> testProjectsCovering)
    {
        var owners = ownersOf(file);
        if (owners.Count == 0) return false;
        return owners.All(owner => testProjectsCovering(owner).All(t =>
            passes.Any(p => p.TestProject == t && p.Assemblies.Contains(owner))));
    }
}
