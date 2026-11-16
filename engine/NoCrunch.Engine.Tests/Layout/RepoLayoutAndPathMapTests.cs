using NoCrunch.Engine.Layout;

namespace NoCrunch.Engine.Tests.Layout;

[TestClass]
public class RepoLayoutAndPathMapTests
{
    [TestMethod]
    public void Resolve_uses_git_root_and_per_solution_mirror()
    {
        var root = TempDir.Create();
        Directory.CreateDirectory(Path.Combine(root, ".git"));
        var slnDir = Directory.CreateDirectory(Path.Combine(root, "src", "app")).FullName;
        var sln = Path.Combine(slnDir, "Sample.slnx");
        File.WriteAllText(sln, "<Solution />");

        var layout = RepoLayout.Resolve(sln);

        Assert.AreEqual(root, layout.RepoRoot);
        Assert.IsTrue(layout.IsGitRepo);
        Assert.AreEqual("Sample", layout.SolutionName);
        Assert.AreEqual(Path.Combine(root, ".nocrunch"), layout.NoCrunchDir);
        Assert.AreEqual(Path.Combine(root, ".nocrunch", "Sample"), layout.StateDir);
        Assert.AreEqual(Path.Combine(root, ".nocrunch", "Sample", "mirror"), layout.MirrorRoot);
    }

    [TestMethod]
    public void Resolve_without_git_uses_solution_dir()
    {
        var root = TempDir.Create();
        var sln = Path.Combine(root, "Other.sln");
        File.WriteAllText(sln, "");

        var layout = RepoLayout.Resolve(sln);

        Assert.IsFalse(layout.IsGitRepo);
        Assert.AreEqual(root, layout.RepoRoot);
        Assert.AreEqual(Path.Combine(root, ".nocrunch", "Other", "mirror"), layout.MirrorRoot);
    }

    [TestMethod]
    public void ToRepo_inverts_ToMirror()
    {
        var map = new PathMap("/r/My Repo", "/r/My Repo/.nocrunch/S/mirror");
        var repoPath = "/r/My Repo/src/Café/a.cs";

        var mirror = map.ToMirror(repoPath);

        Assert.AreEqual("/r/My Repo/.nocrunch/S/mirror/src/Café/a.cs", mirror);
        Assert.AreEqual(repoPath, map.ToRepo(mirror));
        Assert.AreEqual("src/Café/a.cs", map.RepoRelative(repoPath));
    }

    [TestMethod]
    public void RewriteText_rewrites_stack_trace_paths()
    {
        var map = new PathMap("/r/My Repo", "/r/My Repo/.nocrunch/S/mirror");
        var trace = "   at Foo() in /r/My Repo/.nocrunch/S/mirror/src/Foo.cs:line 12\n   at Bar() in /r/My Repo/.nocrunch/S/mirror/src/Bar.cs:line 3";

        var rewritten = map.RewriteText(trace);

        Assert.AreEqual("   at Foo() in /r/My Repo/src/Foo.cs:line 12\n   at Bar() in /r/My Repo/src/Bar.cs:line 3", rewritten);
    }
}

internal static class TempDir
{
    public static string Create()
    {
        var dir = Path.Combine(Path.GetTempPath(), "nocrunch tests", Guid.NewGuid().ToString("N")[..8]);
        Directory.CreateDirectory(dir);
        // Resolve /var -> /private/var so path comparisons are stable on macOS.
        return new DirectoryInfo(dir).ResolveLinkTarget(true)?.FullName ?? Path.GetFullPath(dir);
    }
}
