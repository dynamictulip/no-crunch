namespace NoCrunch.Engine.Layout;

public static class RealPath
{
    /// <summary>Resolves symlinks in every path segment (e.g. macOS /var → /private/var), so one file has one path.</summary>
    public static string Of(string path)
    {
        var full = Path.GetFullPath(path);
        var root = Path.GetPathRoot(full)!;
        var current = root;
        foreach (var segment in full[root.Length..].Split(Path.DirectorySeparatorChar, StringSplitOptions.RemoveEmptyEntries))
        {
            current = Path.Combine(current, segment);
            FileSystemInfo info = Directory.Exists(current) ? new DirectoryInfo(current) : new FileInfo(current);
            if (info.Exists && info.LinkTarget is not null)
                current = Path.GetFullPath(info.ResolveLinkTarget(returnFinalTarget: true)!.FullName);
        }
        return current;
    }
}
