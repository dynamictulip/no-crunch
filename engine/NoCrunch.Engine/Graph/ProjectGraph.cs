using NoCrunch.Engine.Layout;

namespace NoCrunch.Engine.Graph;

/// <summary>Project dependency graph plus file ownership, used to compute what a change makes dirty.</summary>
public sealed class ProjectGraph
{
    private static readonly HashSet<string> GlobalBuildFileNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "Directory.Build.props", "Directory.Build.targets", "Directory.Packages.props", "global.json", "NuGet.config", ".editorconfig",
    };

    private readonly Dictionary<string, ProjectInfo> _byPath;
    private readonly Dictionary<string, HashSet<string>> _dependents = new(StringComparer.Ordinal);
    private readonly Dictionary<string, HashSet<string>> _itemOwners = new(StringComparer.Ordinal);

    private ProjectGraph(IEnumerable<ProjectInfo> projects)
    {
        _byPath = projects.ToDictionary(p => p.RepoRelativePath, StringComparer.Ordinal);
        foreach (var p in _byPath.Values)
        {
            foreach (var r in p.ProjectReferences)
                Add(_dependents, r, p.RepoRelativePath);
            foreach (var item in p.ItemFiles)
                Add(_itemOwners, item, p.RepoRelativePath);
        }
    }

    public IReadOnlyCollection<ProjectInfo> Projects => _byPath.Values;

    public ProjectInfo? Find(string repoRelativePath) => _byPath.GetValueOrDefault(repoRelativePath);

    public static async Task<ProjectGraph> LoadAsync(RepoLayout layout, IMsBuildQuery query, CancellationToken ct)
    {
        var map = new PathMap(layout.RepoRoot, layout.MirrorRoot);
        var mirrorSln = map.ToMirror(layout.SolutionPath);
        var projectPaths = await query.ListSolutionProjectsAsync(mirrorSln, ct);
        var results = new ProjectInfo[projectPaths.Count];
        await Parallel.ForEachAsync(Enumerable.Range(0, projectPaths.Count),
            new ParallelOptions { MaxDegreeOfParallelism = Math.Max(1, Environment.ProcessorCount / 2), CancellationToken = ct },
            async (i, token) => results[i] = await query.QueryAsync(projectPaths[i], token));
        return new ProjectGraph(results);
    }

    public static bool IsGlobalBuildFile(string repoRelativePath) =>
        GlobalBuildFileNames.Contains(FileName(repoRelativePath));

    public static bool IsGraphFile(string repoRelativePath)
    {
        var name = FileName(repoRelativePath);
        var ext = Path.GetExtension(name);
        return GlobalBuildFileNames.Contains(name) && name != ".editorconfig"
               || ext is ".csproj" or ".fsproj" or ".vbproj" or ".props" or ".targets" or ".sln" or ".slnx";
    }

    /// <summary>Projects that declare the file as an item, plus the deepest project whose directory contains it.</summary>
    public IReadOnlySet<string> OwnersOf(string repoRelativePath)
    {
        var owners = new HashSet<string>(StringComparer.Ordinal);
        if (_itemOwners.TryGetValue(repoRelativePath, out var declared)) owners.UnionWith(declared);
        var deepest = _byPath.Values
            .Where(p => p.Directory.Length == 0 || repoRelativePath.StartsWith(p.Directory + "/", StringComparison.Ordinal))
            .MaxBy(p => p.Directory.Length);
        if (deepest is not null) owners.Add(deepest.RepoRelativePath);
        return owners;
    }

    public IReadOnlySet<string> DirtyProjects(IEnumerable<string> changedRepoRelativePaths)
    {
        var dirty = new HashSet<string>(StringComparer.Ordinal);
        var queue = new Queue<string>();
        foreach (var path in changedRepoRelativePaths)
        {
            if (IsGlobalBuildFile(path)) return _byPath.Keys.ToHashSet(StringComparer.Ordinal);
            foreach (var owner in OwnersOf(path))
                if (dirty.Add(owner)) queue.Enqueue(owner);
        }
        while (queue.Count > 0)
        {
            if (!_dependents.TryGetValue(queue.Dequeue(), out var deps)) continue;
            foreach (var d in deps)
                if (dirty.Add(d)) queue.Enqueue(d);
        }
        return dirty;
    }

    /// <summary>The given projects ordered so each comes after the projects it references (Kahn's algorithm).</summary>
    public IReadOnlyList<ProjectInfo> InBuildOrder(IEnumerable<string> projectPaths)
    {
        var set = projectPaths.Where(_byPath.ContainsKey).ToHashSet(StringComparer.Ordinal);
        var inDegree = set.ToDictionary(p => p, p => _byPath[p].ProjectReferences.Count(set.Contains), StringComparer.Ordinal);
        var ready = new SortedSet<string>(inDegree.Where(kv => kv.Value == 0).Select(kv => kv.Key), StringComparer.Ordinal);
        var order = new List<ProjectInfo>();
        while (ready.Count > 0)
        {
            var next = ready.Min!;
            ready.Remove(next);
            order.Add(_byPath[next]);
            if (!_dependents.TryGetValue(next, out var deps)) continue;
            foreach (var d in deps.Where(set.Contains))
                if (--inDegree[d] == 0) ready.Add(d);
        }
        // Cycles shouldn't happen in valid MSBuild graphs; append leftovers so nothing is silently dropped.
        order.AddRange(set.Where(p => order.All(o => o.RepoRelativePath != p)).Order(StringComparer.Ordinal).Select(p => _byPath[p]));
        return order;
    }

    private static string FileName(string path) => path[(path.LastIndexOf('/') + 1)..];

    private static void Add(Dictionary<string, HashSet<string>> dict, string key, string value)
    {
        if (!dict.TryGetValue(key, out var set)) dict[key] = set = new HashSet<string>(StringComparer.Ordinal);
        set.Add(value);
    }
}
