namespace NoCrunch.Engine.Layout;

/// <summary>The single place that translates between repo paths and mirror paths.</summary>
public sealed class PathMap(string repoRoot, string mirrorRoot)
{
    public string RepoRoot { get; } = Path.TrimEndingDirectorySeparator(repoRoot);
    public string MirrorRoot { get; } = Path.TrimEndingDirectorySeparator(mirrorRoot);

    public string ToMirror(string repoPath) => Swap(repoPath, RepoRoot, MirrorRoot);

    public string ToRepo(string mirrorPath) => Swap(mirrorPath, MirrorRoot, RepoRoot);

    /// <summary>Repo-relative path with '/' separators.</summary>
    public string RepoRelative(string repoPath) =>
        Path.GetRelativePath(RepoRoot, Path.GetFullPath(repoPath)).Replace('\\', '/');

    public string RepoPath(string repoRelative) => Path.Combine(RepoRoot, repoRelative);

    public string MirrorPath(string repoRelative) => Path.Combine(MirrorRoot, repoRelative);

    /// <summary>Rewrites every occurrence of the mirror root in free text (stack traces, build output).</summary>
    public string RewriteText(string text) =>
        string.IsNullOrEmpty(text) ? text : text.Replace(MirrorRoot, RepoRoot, StringComparison.Ordinal);

    private static string Swap(string path, string from, string to)
    {
        var full = Path.GetFullPath(path);
        if (full == from) return to;
        if (!full.StartsWith(from + Path.DirectorySeparatorChar, StringComparison.Ordinal))
            throw new ArgumentException($"'{path}' is not under '{from}'");
        return to + full[from.Length..];
    }
}
