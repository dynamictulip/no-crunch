using System.Diagnostics;
using NoCrunch.Engine.Tests.Layout;
using NoCrunch.Engine.Workspace;

namespace NoCrunch.Engine.Tests.Workspace;

[TestClass]
public class GitIgnoredDirectoriesTests
{
    /// <summary>A stand-in for git: a shell script, so the test controls what it prints and how long it takes.</summary>
    private static string FakeGit(string script)
    {
        if (OperatingSystem.IsWindows()) Assert.Inconclusive("shell script stand-in for git");
        var path = Path.Combine(TempDir.Create(), "git");
        File.WriteAllText(path, "#!/bin/sh\n" + script + "\n");
        File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        return path;
    }

    [TestMethod]
    public void Query_reads_stderr_so_a_chatty_git_cannot_block()
    {
        // Far more than a pipe buffer (64 KB on macOS/Linux) on stderr before the real answer on stdout.
        var git = FakeGit("head -c 1000000 /dev/zero | tr '\\0' 'x' >&2\nprintf 'artifacts/\\0'");

        var ignored = GitIgnoredDirectories.Query(TempDir.Create(), git, TimeSpan.FromSeconds(30));

        Assert.IsTrue(ignored.Contains("artifacts/bin"));
    }

    [TestMethod]
    public void Query_gives_up_on_a_git_that_hangs()
    {
        var git = FakeGit("sleep 30");
        var sw = Stopwatch.StartNew();

        Assert.ThrowsExactly<TimeoutException>(() => GitIgnoredDirectories.Query(TempDir.Create(), git, TimeSpan.FromMilliseconds(300)));

        Assert.IsTrue(sw.Elapsed < TimeSpan.FromSeconds(10), sw.Elapsed.ToString());
    }

    [TestMethod]
    public void IgnoredAmong_asks_git_about_directories_that_dont_exist()
    {
        var root = TempDir.Create();
        NoCrunch.Engine.Tests.TestSupport.SampleSolution.Git(root, "init", "-q");
        File.WriteAllText(Path.Combine(root, ".gitignore"), "artifacts/\n**/generated/\n*.log\n");
        var ignored = GitIgnoredDirectories.Query(root);

        var result = ignored.IgnoredAmong(["artifacts", "src/App/generated", "src/App/Other", "build.log"]);

        CollectionAssert.AreEquivalent(new[] { "artifacts", "src/App/generated", "build.log" }, result.ToArray());
    }
}
