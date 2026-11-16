using NoCrunch.Engine.Layout;

namespace NoCrunch.Engine.Workspace;

/// <summary>One copy of the whole repo at .nocrunch/&lt;Solution&gt;/mirror.</summary>
public sealed class SharedMirrorWorkspace(RepoLayout layout, PathMap map) : IWorkspaceStrategy
{
    private const string StopperContent = "<Project>\n  <!-- No Crunch: stops MSBuild import chains escaping the mirror into the real repo. -->\n</Project>\n";
    private static readonly string[] StopperFiles = ["Directory.Build.props", "Directory.Build.targets", "Directory.Packages.props"];

    public async Task InitializeAsync(CancellationToken ct)
    {
        Directory.CreateDirectory(layout.MirrorRoot);
        foreach (var name in StopperFiles)
        {
            var path = Path.Combine(layout.NoCrunchDir, name);
            if (!File.Exists(path)) await File.WriteAllTextAsync(path, StopperContent, ct);
        }
        if (layout.IsGitRepo) await AddGitExcludeAsync(ct);
        await ReconcileAsync(ct);
    }

    public Task<IReadOnlyCollection<string>> SyncAsync(IReadOnlyCollection<string> changedRepoPaths, CancellationToken ct)
    {
        var changed = new SortedSet<string>(StringComparer.Ordinal);
        foreach (var repoPath in changedRepoPaths)
        {
            ct.ThrowIfCancellationRequested();
            string rel;
            try { rel = map.RepoRelative(repoPath); }
            catch (ArgumentException) { continue; }
            if (rel.StartsWith("..", StringComparison.Ordinal) || rel == "." || FileFilter.IsExcluded(rel)) continue;

            var src = map.RepoPath(rel);
            var dst = map.MirrorPath(rel);
            if (Directory.Exists(src)) ReconcileDir(src, dst, changed, ct);
            else if (File.Exists(src)) { if (CopyIfDifferent(src, dst)) changed.Add(rel); }
            else if (Directory.Exists(dst)) DeleteMirrorDir(dst, changed);
            else if (File.Exists(dst)) { File.Delete(dst); changed.Add(rel); }
        }
        return Task.FromResult<IReadOnlyCollection<string>>(changed);
    }

    public Task<IReadOnlyCollection<string>> ReconcileAsync(CancellationToken ct)
    {
        var changed = new SortedSet<string>(StringComparer.Ordinal);
        ReconcileDir(layout.RepoRoot, layout.MirrorRoot, changed, ct);
        return Task.FromResult<IReadOnlyCollection<string>>(changed);
    }

    public string MirrorPathOf(string repoRelativePath) => map.MirrorPath(repoRelativePath);

    private void ReconcileDir(string srcDir, string dstDir, ISet<string> changed, CancellationToken ct)
    {
        ct.ThrowIfCancellationRequested();
        Directory.CreateDirectory(dstDir);
        var srcFiles = new HashSet<string>(StringComparer.Ordinal);
        foreach (var file in Directory.EnumerateFiles(srcDir))
        {
            var name = Path.GetFileName(file);
            if (name == ".git") continue;
            srcFiles.Add(name);
            if (CopyIfDifferent(file, Path.Combine(dstDir, name))) changed.Add(map.RepoRelative(file));
        }
        var srcDirs = new HashSet<string>(StringComparer.Ordinal);
        foreach (var dir in Directory.EnumerateDirectories(srcDir))
        {
            var name = Path.GetFileName(dir);
            if (FileFilter.IsExcludedDirName(name)) continue;
            srcDirs.Add(name);
            ReconcileDir(dir, Path.Combine(dstDir, name), changed, ct);
        }
        // Remove mirror entries that no longer exist in the repo (but never build output dirs).
        foreach (var file in Directory.EnumerateFiles(dstDir))
        {
            if (srcFiles.Contains(Path.GetFileName(file))) continue;
            File.Delete(file);
            changed.Add(map.RepoRelative(map.ToRepo(file)));
        }
        foreach (var dir in Directory.EnumerateDirectories(dstDir))
        {
            var name = Path.GetFileName(dir);
            if (srcDirs.Contains(name) || FileFilter.IsExcludedDirName(name)) continue;
            DeleteMirrorDir(dir, changed);
        }
    }

    private void DeleteMirrorDir(string mirrorDir, ISet<string> changed)
    {
        foreach (var file in Directory.EnumerateFiles(mirrorDir, "*", SearchOption.AllDirectories))
        {
            var rel = map.RepoRelative(map.ToRepo(file));
            if (!FileFilter.IsExcluded(rel)) changed.Add(rel);
        }
        Directory.Delete(mirrorDir, recursive: true);
    }

    /// <summary>Copies src over dst unless they already match. Returns true when content changed.</summary>
    private static bool CopyIfDifferent(string src, string dst)
    {
        var s = new FileInfo(src);
        var d = new FileInfo(dst);
        if (d.Exists && d.Length == s.Length)
        {
            if (d.LastWriteTimeUtc == s.LastWriteTimeUtc) return false;
            if (SameBytes(src, dst))
            {
                File.SetLastWriteTimeUtc(dst, s.LastWriteTimeUtc);
                return false;
            }
        }
        Directory.CreateDirectory(Path.GetDirectoryName(dst)!);
        File.Copy(src, dst, overwrite: true);
        File.SetLastWriteTimeUtc(dst, s.LastWriteTimeUtc);
        return true;
    }

    private static bool SameBytes(string a, string b)
    {
        using var fa = File.OpenRead(a);
        using var fb = File.OpenRead(b);
        Span<byte> ba = stackalloc byte[8192];
        Span<byte> bb = stackalloc byte[8192];
        while (true)
        {
            var na = fa.ReadAtLeast(ba, ba.Length, throwOnEndOfStream: false);
            var nb = fb.ReadAtLeast(bb, bb.Length, throwOnEndOfStream: false);
            if (na != nb || !ba[..na].SequenceEqual(bb[..nb])) return false;
            if (na == 0) return true;
        }
    }

    private async Task AddGitExcludeAsync(CancellationToken ct)
    {
        var gitDir = Path.Combine(layout.RepoRoot, ".git");
        if (!Directory.Exists(gitDir)) return; // worktree/.git file: skip rather than guess
        var info = Directory.CreateDirectory(Path.Combine(gitDir, "info")).FullName;
        var exclude = Path.Combine(info, "exclude");
        var lines = File.Exists(exclude) ? await File.ReadAllLinesAsync(exclude, ct) : [];
        if (lines.Any(l => l.Trim() is ".nocrunch/" or ".nocrunch" or "/.nocrunch/")) return;
        var prefix = lines.Length > 0 && !string.IsNullOrEmpty(lines[^1]) ? "\n" : "";
        await File.AppendAllTextAsync(exclude, prefix + ".nocrunch/\n", ct);
    }
}
