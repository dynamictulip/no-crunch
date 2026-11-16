namespace NoCrunch.Engine.Workspace;

/// <summary>Directories that are never mirrored.</summary>
public static class FileFilter
{
    private static readonly HashSet<string> ExcludedDirs = new(StringComparer.Ordinal)
    {
        "bin", "obj", ".git", ".idea", ".vs", "TestResults", "node_modules", ".nocrunch",
    };

    public static bool IsExcluded(string repoRelativePath)
    {
        var parts = repoRelativePath.Replace('\\', '/').Split('/', StringSplitOptions.RemoveEmptyEntries);
        // A file named like an excluded dir (e.g. a file called "bin") is fine; only directory segments count,
        // except ".git" which can be a file in worktrees.
        for (var i = 0; i < parts.Length; i++)
        {
            var isLast = i == parts.Length - 1;
            if (ExcludedDirs.Contains(parts[i]) && (!isLast || parts[i] == ".git")) return true;
        }
        return false;
    }

    public static bool IsExcludedDirName(string name) => ExcludedDirs.Contains(name);
}
