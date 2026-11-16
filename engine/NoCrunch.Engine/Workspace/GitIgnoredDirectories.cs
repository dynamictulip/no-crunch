using System.Diagnostics;

namespace NoCrunch.Engine.Workspace;

/// <summary>
/// Directories git ignores (build caches, artifacts, tool sandboxes). The mirror skips these directories,
/// while git-ignored *files* (e.g. appsettings.Development.json) are still copied because tests often need them.
/// </summary>
public sealed class GitIgnoredDirectories
{
    private readonly HashSet<string> _dirs;

    private GitIgnoredDirectories(HashSet<string> dirs) => _dirs = dirs;

    public static GitIgnoredDirectories None { get; } = new([]);

    public int Count => _dirs.Count;

    /// <summary>Asks git (`ls-files --others --ignored --directory`). Not a git repo or git missing → nothing ignored.</summary>
    public static GitIgnoredDirectories Query(string repoRoot)
    {
        try
        {
            var psi = new ProcessStartInfo("git")
            {
                WorkingDirectory = repoRoot,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
            };
            foreach (var a in new[] { "-c", "core.quotepath=off", "ls-files", "--others", "--ignored", "--exclude-standard", "--directory", "-z" })
                psi.ArgumentList.Add(a);
            using var p = Process.Start(psi)!;
            var output = p.StandardOutput.ReadToEnd();
            p.WaitForExit();
            if (p.ExitCode != 0) return None;
            var dirs = output.Split('\0', StringSplitOptions.RemoveEmptyEntries)
                .Where(e => e.EndsWith('/'))
                .Select(e => e.TrimEnd('/'))
                .ToHashSet(StringComparer.Ordinal);
            return new GitIgnoredDirectories(dirs);
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            return None;
        }
    }

    /// <summary>True if the repo-relative path is an ignored directory or lies inside one.</summary>
    public bool Contains(string repoRelativePath)
    {
        if (_dirs.Count == 0) return false;
        var path = repoRelativePath.TrimEnd('/');
        for (var i = path.Length; i > 0; i = path.LastIndexOf('/', i - 1))
        {
            if (_dirs.Contains(path[..i])) return true;
            if (i == 0) break;
        }
        return false;
    }
}
