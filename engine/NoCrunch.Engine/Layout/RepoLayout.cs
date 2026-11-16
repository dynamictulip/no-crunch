namespace NoCrunch.Engine.Layout;

/// <summary>Where the repo, solution, and No Crunch state/mirror live.</summary>
public sealed record RepoLayout(
    string RepoRoot,
    string SolutionPath,
    string SolutionName,
    string NoCrunchDir,
    string StateDir,
    string MirrorRoot,
    bool IsGitRepo)
{
    public static RepoLayout Resolve(string solutionPath)
    {
        var sln = RealPath.Of(solutionPath);
        var slnDir = Path.GetDirectoryName(sln)!;
        var gitRoot = FindGitRoot(slnDir);
        var root = gitRoot ?? slnDir;
        var name = Path.GetFileNameWithoutExtension(sln);
        var noCrunch = Path.Combine(root, ".nocrunch");
        var state = Path.Combine(noCrunch, name);
        return new RepoLayout(root, sln, name, noCrunch, state, Path.Combine(state, "mirror"), gitRoot is not null);
    }

    private static string? FindGitRoot(string start)
    {
        for (var dir = new DirectoryInfo(start); dir is not null; dir = dir.Parent)
        {
            var git = Path.Combine(dir.FullName, ".git");
            if (Directory.Exists(git) || File.Exists(git)) return dir.FullName;
        }
        return null;
    }
}
