using System.Diagnostics;
using System.Text;

namespace NoCrunch.Engine.Workspace;

/// <summary>
/// Directories git ignores (build caches, artifacts, tool sandboxes). The mirror skips these directories,
/// while git-ignored *files* (e.g. appsettings.Development.json) are still copied because tests often need them.
/// </summary>
public sealed class GitIgnoredDirectories
{
    /// <summary>`ls-files` on a large repo takes seconds; a git that takes this long is stuck (e.g. waiting on a lock).</summary>
    private static readonly TimeSpan DefaultTimeout = TimeSpan.FromSeconds(60);

    private readonly HashSet<string> _dirs;
    private readonly string? _repoRoot;
    private readonly string _git;
    private readonly TimeSpan _timeout;

    private GitIgnoredDirectories(HashSet<string> dirs, string? repoRoot, string git, TimeSpan timeout)
    {
        _dirs = dirs;
        _repoRoot = repoRoot;
        _git = git;
        _timeout = timeout;
    }

    public static GitIgnoredDirectories None { get; } = new([], null, "git", DefaultTimeout);

    public int Count => _dirs.Count;

    /// <summary>Asks git (`ls-files --others --ignored --directory`). Not a git repo or git missing → nothing ignored.
    /// Throws <see cref="TimeoutException"/> if git doesn't answer in time.</summary>
    public static GitIgnoredDirectories Query(string repoRoot) => Query(repoRoot, "git", DefaultTimeout);

    internal static GitIgnoredDirectories Query(string repoRoot, string git, TimeSpan timeout)
    {
        var result = Run(git, repoRoot, ["-c", "core.quotepath=off", "ls-files", "--others", "--ignored", "--exclude-standard", "--directory", "-z"], null, timeout);
        if (result is not { ExitCode: 0 } r) return None;
        var dirs = r.Stdout.Split('\0', StringSplitOptions.RemoveEmptyEntries)
            .Where(e => e.EndsWith('/'))
            .Select(e => e.TrimEnd('/'))
            .ToHashSet(StringComparer.Ordinal);
        return new GitIgnoredDirectories(dirs, repoRoot, git, timeout);
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

    /// <summary>
    /// Which of these repo-relative directories git would ignore, whether or not they exist in the repo
    /// (`check-ignore`, which applies the rules to the path rather than listing what's on disk). The mirror uses
    /// this for directories only it has, such as artifacts/ when the user has never built outside No Crunch.
    /// </summary>
    public IReadOnlySet<string> IgnoredAmong(IReadOnlyCollection<string> repoRelativeDirs)
    {
        var none = new HashSet<string>(StringComparer.Ordinal);
        if (_repoRoot is null || repoRelativeDirs.Count == 0) return none;
        // The trailing slash makes git treat each path as a directory, so directory-only rules ("artifacts/") apply.
        var input = string.Concat(repoRelativeDirs.Select(d => d.TrimEnd('/') + "/\0"));
        var result = Run(_git, _repoRoot, ["-c", "core.quotepath=off", "check-ignore", "--stdin", "-z"], input, _timeout);
        // Exit 1 means nothing matched. Anything else (e.g. a path beyond a symlink) leaves the answer unknown, and
        // "not ignored" keeps the mirror matching the repo, as it did before this check existed.
        if (result is not { ExitCode: 0 } r) return none;
        return r.Stdout.Split('\0', StringSplitOptions.RemoveEmptyEntries).Select(e => e.TrimEnd('/')).ToHashSet(StringComparer.Ordinal);
    }

    private sealed record GitResult(int ExitCode, string Stdout, string Stderr);

    /// <summary>Runs git; null if it can't be started (not installed). Throws <see cref="TimeoutException"/> if it hangs.</summary>
    private static GitResult? Run(string git, string workingDirectory, IEnumerable<string> args, string? stdin, TimeSpan timeout)
    {
        var utf8 = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false);
        var psi = new ProcessStartInfo(git)
        {
            WorkingDirectory = workingDirectory,
            RedirectStandardInput = stdin is not null,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardInputEncoding = stdin is not null ? utf8 : null,
            StandardOutputEncoding = utf8,
            StandardErrorEncoding = utf8,
            UseShellExecute = false,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        Process p;
        try { p = Process.Start(psi)!; }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException) { return null; }
        using (p)
        {
            // Drain both pipes while git runs: a pipe nobody reads fills up (64 KB) and git blocks writing to it forever.
            var stdout = p.StandardOutput.ReadToEndAsync();
            var stderr = p.StandardError.ReadToEndAsync();
            // Write stdin on the side too, so a git that never reads it can't hang us past the timeout.
            var input = stdin is null ? Task.CompletedTask : Task.Run(async () =>
            {
                try
                {
                    await p.StandardInput.WriteAsync(stdin);
                    p.StandardInput.Close();
                }
                catch (IOException) { } // git exited without reading it all; its exit code says what happened
            });
            if (!p.WaitForExit(timeout))
            {
                try { p.Kill(entireProcessTree: true); }
                catch (InvalidOperationException) { } // exited just now
                throw new TimeoutException($"git {string.Join(' ', args)} didn't finish within {timeout.TotalSeconds:0.#}s in {workingDirectory}");
            }
            p.WaitForExit(); // flushes the async readers
            Task.WaitAll(stdout, stderr, input);
            return new GitResult(p.ExitCode, stdout.Result, stderr.Result);
        }
    }
}
