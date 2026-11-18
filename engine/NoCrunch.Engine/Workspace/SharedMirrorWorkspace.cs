using NoCrunch.Engine.Layout;

namespace NoCrunch.Engine.Workspace;

/// <summary>One copy of the whole repo at .nocrunch/&lt;Solution&gt;/mirror.</summary>
public sealed class SharedMirrorWorkspace(RepoLayout layout, PathMap map) : IWorkspaceStrategy
{
    private const string StopperContent = "<Project>\n  <!-- No Crunch: stops MSBuild import chains escaping the mirror into the real repo. -->\n</Project>\n";
    private static readonly string[] StopperFiles = ["Directory.Build.props", "Directory.Build.targets", "Directory.Packages.props"];

    private GitIgnoredDirectories _ignored = GitIgnoredDirectories.None;

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

    public Task<IReadOnlyCollection<string>> SyncAsync(IReadOnlyCollection<string> changedRepoPaths, CancellationToken ct, ISet<string>? progress = null)
    {
        var changed = new ProgressSet(progress);
        foreach (var repoPath in changedRepoPaths)
        {
            ct.ThrowIfCancellationRequested();
            string rel;
            try { rel = map.RepoRelative(RealPath.Of(repoPath)); }
            catch (ArgumentException) { continue; }
            if (rel.StartsWith("..", StringComparison.Ordinal) || rel == "." || FileFilter.IsExcluded(rel) || _ignored.Contains(rel)) continue;
            if (Path.GetFileName(rel) == ".gitignore")
            {
                // Ignore rules changed: newly un-ignored directories arrive. Newly ignored ones stay in the mirror
                // (see ReconcileDir) because by now their mirror copy may hold build output.
                RefreshIgnored();
                ReconcileDir(layout.RepoRoot, layout.MirrorRoot, changed, ct);
            }

            var src = map.RepoPath(rel);
            var dst = map.MirrorPath(rel);
            if (Directory.Exists(src)) ReconcileDir(src, dst, changed, ct);
            else if (File.Exists(src)) SyncFile(src, dst, rel, changed);
            else if (Directory.Exists(dst)) { if (_ignored.IgnoredAmong([rel]).Count == 0) DeleteMirrorDir(dst, changed); }
            else DeleteMirrorFile(dst, rel, changed);
        }
        return Task.FromResult<IReadOnlyCollection<string>>(changed.Items);
    }

    public Task<IReadOnlyCollection<string>> ReconcileAsync(CancellationToken ct, ISet<string>? progress = null)
    {
        var changed = new ProgressSet(progress);
        RefreshIgnored();
        ReconcileDir(layout.RepoRoot, layout.MirrorRoot, changed, ct);
        return Task.FromResult<IReadOnlyCollection<string>>(changed.Items);
    }

    public string MirrorPathOf(string repoRelativePath) => map.MirrorPath(repoRelativePath);

    public bool Tracks(string repoPath)
    {
        string rel;
        try { rel = map.RepoRelative(RealPath.Of(repoPath)); }
        catch (ArgumentException) { return false; }
        return !rel.StartsWith("..", StringComparison.Ordinal) && rel != "."
               && !FileFilter.IsExcluded(rel) && !_ignored.Contains(rel)
               && !rel.EndsWith(".nocrunch.user", StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>macOS (APFS default) and Windows file systems are case-insensitive; a case-only rename is the same entry.</summary>
    private static readonly StringComparer NameComparer =
        OperatingSystem.IsMacOS() || OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal;

    private static readonly StringComparison PathComparison =
        NameComparer == StringComparer.Ordinal ? StringComparison.Ordinal : StringComparison.OrdinalIgnoreCase;

    private void ReconcileDir(string srcDir, string dstDir, ProgressSet changed, CancellationToken ct) =>
        ReconcileDir(srcDir, dstDir, changed, ct, [RealPath.Of(srcDir)]);

    /// <param name="followed">Real paths of srcDir and the directories above it in this walk, to spot symlink loops.</param>
    private void ReconcileDir(string srcDir, string dstDir, ProgressSet changed, CancellationToken ct, List<string> followed)
    {
        ct.ThrowIfCancellationRequested();
        List<string> files, dirs;
        try
        {
            files = Directory.EnumerateFiles(srcDir).ToList();
            dirs = Directory.EnumerateDirectories(srcDir).ToList();
        }
        catch (DirectoryNotFoundException)
        {
            // Deleted or renamed after its parent listed it: that's a deletion, so the mirror copy goes too.
            if (Directory.Exists(dstDir)) DeleteMirrorDir(dstDir, changed);
            return;
        }
        Directory.CreateDirectory(dstDir);
        MatchCasing(files.Concat(dirs).Select(Path.GetFileName).OfType<string>(), dstDir);
        var srcFiles = new HashSet<string>(NameComparer);
        foreach (var file in files)
        {
            var name = Path.GetFileName(file);
            if (name == ".git") continue;
            // A file that vanished between the listing and the copy isn't added, so the pass below removes its mirror copy.
            if (SyncFile(file, Path.Combine(dstDir, name), map.RepoRelative(file), changed)) srcFiles.Add(name);
        }
        var srcDirs = new HashSet<string>(NameComparer);
        foreach (var dir in dirs)
        {
            var name = Path.GetFileName(dir);
            if (FileFilter.IsExcludedDirName(name) || _ignored.Contains(map.RepoRelative(dir))) continue;
            // Symlinked directories are copied as real directories: a link in the mirror would let builds there
            // read and write the user's tree. One that points at itself or a directory above it (in this walk) would
            // recurse until the path is too long, so it's skipped, as MSBuild globs would skip it too.
            var real = new DirectoryInfo(dir).LinkTarget is null ? Path.Combine(followed[^1], name) : RealPath.Of(dir);
            if (followed.Any(f => IsSameOrUnder(f, real))) continue;
            srcDirs.Add(name);
            followed.Add(real);
            ReconcileDir(dir, Path.Combine(dstDir, name), changed, ct, followed);
            followed.RemoveAt(followed.Count - 1);
        }
        // Remove mirror entries the repo no longer has. Files: every repo file is mirrored, git-ignored ones included,
        // so a mirror file without one was deleted in the repo.
        foreach (var file in Directory.EnumerateFiles(dstDir))
        {
            var name = Path.GetFileName(file);
            if (srcFiles.Contains(name) || name == ".git") continue;
            DeleteMirrorFile(file, map.RepoRelative(map.ToRepo(file)), changed);
        }
        // Directories: only those the repo would mirror. Excluded and git-ignored ones (bin, obj, artifacts/) are never
        // copied, so whatever the mirror has there is its own build output, whether or not the repo has the directory.
        var gone = new List<(string Path, string Rel)>();
        foreach (var dir in Directory.EnumerateDirectories(dstDir))
        {
            var name = Path.GetFileName(dir);
            if (srcDirs.Contains(name) || FileFilter.IsExcludedDirName(name)) continue;
            var rel = map.RepoRelative(map.ToRepo(dir));
            if (!_ignored.Contains(rel)) gone.Add((dir, rel));
        }
        // The ignored list only names directories that exist in the repo, so ask git about the rest.
        var ignored = _ignored.IgnoredAmong(gone.Select(g => g.Rel).ToList());
        foreach (var (dir, rel) in gone)
            if (!ignored.Contains(rel)) DeleteMirrorDir(dir, changed);
    }

    private static bool IsSameOrUnder(string path, string dir) =>
        path.Equals(dir, PathComparison) || path.StartsWith(Path.TrimEndingDirectorySeparator(dir) + Path.DirectorySeparatorChar, PathComparison);

    /// <summary>Copies one repo file into the mirror. Returns false if the repo file vanished before it could be
    /// read, in which case its mirror copy is removed as for any deletion.</summary>
    private bool SyncFile(string src, string dst, string rel, ProgressSet changed)
    {
        switch (CopyIfDifferent(src, dst))
        {
            case CopyResult.Copied:
                changed.Add(rel);
                return true;
            case CopyResult.SourceGone:
                DeleteMirrorFile(dst, rel, changed);
                return false;
            default:
                return true;
        }
    }

    /// <summary>Renames mirror entries whose names differ from the repo only by case, so the mirror keeps the repo's casing.</summary>
    private static void MatchCasing(IEnumerable<string> srcNames, string dstDir)
    {
        if (NameComparer == StringComparer.Ordinal) return;
        var dstNames = Directory.EnumerateFileSystemEntries(dstDir).Select(Path.GetFileName).OfType<string>()
            .ToDictionary(n => n, n => n, StringComparer.OrdinalIgnoreCase);
        foreach (var name in srcNames)
        {
            if (!dstNames.TryGetValue(name, out var existing) || existing == name) continue;
            var from = Path.Combine(dstDir, existing);
            var temp = Path.Combine(dstDir, existing + ".nocrunch-rename");
            if (Directory.Exists(from)) { Directory.Move(from, temp); Directory.Move(temp, Path.Combine(dstDir, name)); }
            else { File.Move(from, temp); File.Move(temp, Path.Combine(dstDir, name)); }
        }
    }

    private void RefreshIgnored() => _ignored = layout.IsGitRepo ? GitIgnoredDirectories.Query(layout.RepoRoot) : GitIgnoredDirectories.None;

    private static void DeleteMirrorFile(string mirrorFile, string rel, ProgressSet changed)
    {
        if (!File.Exists(mirrorFile)) return;
        try { FileRetry.Run(() => File.Delete(mirrorFile)); }
        catch (DirectoryNotFoundException) { return; } // its directory went first
        changed.Add(rel);
    }

    private void DeleteMirrorDir(string mirrorDir, ProgressSet changed)
    {
        List<string> files;
        try { files = Directory.EnumerateFiles(mirrorDir, "*", SearchOption.AllDirectories).ToList(); }
        catch (DirectoryNotFoundException) { return; }
        foreach (var file in files)
        {
            var rel = map.RepoRelative(map.ToRepo(file));
            if (!FileFilter.IsExcluded(rel)) changed.Add(rel);
        }
        try { FileRetry.Run(() => Directory.Delete(mirrorDir, recursive: true)); }
        catch (DirectoryNotFoundException) { }
    }

    private enum CopyResult { Unchanged, Copied, SourceGone }

    /// <summary>Copies src over dst unless they already match. A file can be deleted or renamed at any moment
    /// (editors' safe-write temp files are created, renamed and deleted within milliseconds), so a source that
    /// disappears part-way is reported as gone rather than failing the cycle. Other errors propagate.</summary>
    private static CopyResult CopyIfDifferent(string src, string dst)
    {
        try
        {
            return FileRetry.Run(() =>
            {
                var s = new FileInfo(src);
                var d = new FileInfo(dst);
                if (d.Exists && d.Length == s.Length)
                {
                    if (d.LastWriteTimeUtc == s.LastWriteTimeUtc) return CopyResult.Unchanged;
                    if (SameBytes(src, dst))
                    {
                        File.SetLastWriteTimeUtc(dst, s.LastWriteTimeUtc);
                        return CopyResult.Unchanged;
                    }
                }
                Directory.CreateDirectory(Path.GetDirectoryName(dst)!);
                // The time is read before copying: if the file changes mid-copy, its newer time differs from the
                // mirror's and the next sync copies it again.
                var time = s.LastWriteTimeUtc;
                File.Copy(src, dst, overwrite: true);
                File.SetLastWriteTimeUtc(dst, time);
                return CopyResult.Copied;
            });
        }
        catch (Exception e) when (e is FileNotFoundException or DirectoryNotFoundException)
        {
            return CopyResult.SourceGone;
        }
        // On Windows a file being deleted can't be opened ("access denied" / "in use") until the last handle closes.
        catch (Exception e) when (e is IOException or UnauthorizedAccessException && !File.Exists(src))
        {
            return CopyResult.SourceGone;
        }
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

    /// <summary>Collects changes and mirrors each one into the caller's progress sink immediately.</summary>
    private sealed class ProgressSet(ISet<string>? progress) : HashSet<string>(StringComparer.Ordinal)
    {
        public IReadOnlyCollection<string> Items => this.Order(StringComparer.Ordinal).ToList();

        public new bool Add(string item)
        {
            progress?.Add(item);
            return base.Add(item);
        }
    }
}
