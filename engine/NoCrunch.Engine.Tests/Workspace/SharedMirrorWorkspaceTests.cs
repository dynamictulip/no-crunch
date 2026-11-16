using NoCrunch.Engine.Layout;
using NoCrunch.Engine.Tests.Layout;
using NoCrunch.Engine.Workspace;

namespace NoCrunch.Engine.Tests.Workspace;

[TestClass]
public class SharedMirrorWorkspaceTests
{
    private static (string root, RepoLayout layout, SharedMirrorWorkspace ws) Setup()
    {
        var root = TempDir.Create();
        Directory.CreateDirectory(Path.Combine(root, ".git", "info"));
        Write(root, "Sample.slnx", "<Solution />");
        Write(root, "src/App/App.csproj", "<Project />");
        Write(root, "src/App/Program.cs", "class P {}");
        Write(root, "src/App/bin/Debug/App.dll", "binary");
        Write(root, "src/App/obj/project.assets.json", "{}");
        Write(root, ".git/HEAD", "ref");
        Write(root, "src/App/appsettings.Development.json", "{}");
        Write(root, ".gitignore", "appsettings.Development.json");
        var layout = RepoLayout.Resolve(Path.Combine(root, "Sample.slnx"));
        var ws = new SharedMirrorWorkspace(layout, new PathMap(layout.RepoRoot, layout.MirrorRoot));
        return (root, layout, ws);
    }

    private static void Write(string root, string rel, string content)
    {
        var path = Path.Combine(root, rel);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, content);
    }

    [TestMethod]
    public async Task Initialize_copies_sources_but_not_bin_obj_git()
    {
        var (_, layout, ws) = Setup();
        await ws.InitializeAsync(CancellationToken.None);

        Assert.IsTrue(File.Exists(Path.Combine(layout.MirrorRoot, "src/App/Program.cs")));
        Assert.IsTrue(File.Exists(Path.Combine(layout.MirrorRoot, "Sample.slnx")));
        Assert.IsFalse(Directory.Exists(Path.Combine(layout.MirrorRoot, "src/App/bin")));
        Assert.IsFalse(Directory.Exists(Path.Combine(layout.MirrorRoot, "src/App/obj")));
        Assert.IsFalse(Directory.Exists(Path.Combine(layout.MirrorRoot, ".git")));
        Assert.IsFalse(Directory.Exists(Path.Combine(layout.MirrorRoot, ".nocrunch")));
    }

    [TestMethod]
    public async Task Initialize_copies_gitignored_files()
    {
        var (_, layout, ws) = Setup();
        await ws.InitializeAsync(CancellationToken.None);
        Assert.IsTrue(File.Exists(Path.Combine(layout.MirrorRoot, "src/App/appsettings.Development.json")));
    }

    [TestMethod]
    public async Task Initialize_writes_stopper_props_and_git_exclude_once()
    {
        var (root, layout, ws) = Setup();
        await ws.InitializeAsync(CancellationToken.None);
        await ws.InitializeAsync(CancellationToken.None);

        foreach (var name in new[] { "Directory.Build.props", "Directory.Build.targets", "Directory.Packages.props" })
            Assert.IsTrue(File.Exists(Path.Combine(layout.NoCrunchDir, name)), name);
        var exclude = File.ReadAllLines(Path.Combine(root, ".git/info/exclude"));
        Assert.AreEqual(1, exclude.Count(l => l.Trim() == ".nocrunch/"));
    }

    [TestMethod]
    public async Task Sync_copies_changed_and_deletes_removed()
    {
        var (root, layout, ws) = Setup();
        await ws.InitializeAsync(CancellationToken.None);
        Write(root, "src/App/Program.cs", "class P { int x; }");
        Write(root, "src/App/New.cs", "class N {}");
        File.Delete(Path.Combine(root, "src/App/appsettings.Development.json"));

        var changed = await ws.SyncAsync(
            [Path.Combine(root, "src/App/Program.cs"), Path.Combine(root, "src/App/New.cs"), Path.Combine(root, "src/App/appsettings.Development.json")],
            CancellationToken.None);

        CollectionAssert.AreEquivalent(new[] { "src/App/Program.cs", "src/App/New.cs", "src/App/appsettings.Development.json" }, changed.ToArray());
        Assert.AreEqual("class P { int x; }", File.ReadAllText(Path.Combine(layout.MirrorRoot, "src/App/Program.cs")));
        Assert.IsTrue(File.Exists(Path.Combine(layout.MirrorRoot, "src/App/New.cs")));
        Assert.IsFalse(File.Exists(Path.Combine(layout.MirrorRoot, "src/App/appsettings.Development.json")));
    }

    [TestMethod]
    public async Task Sync_ignores_unchanged_content_and_excluded_paths()
    {
        var (root, _, ws) = Setup();
        await ws.InitializeAsync(CancellationToken.None);
        Write(root, "src/App/bin/Debug/App.dll", "rebuilt");

        var changed = await ws.SyncAsync(
            [Path.Combine(root, "src/App/Program.cs"), Path.Combine(root, "src/App/bin/Debug/App.dll")],
            CancellationToken.None);

        Assert.AreEqual(0, changed.Count);
    }

    [TestMethod]
    public async Task Sync_handles_spaces_and_unicode()
    {
        var (root, layout, ws) = Setup();
        await ws.InitializeAsync(CancellationToken.None);
        Write(root, "src/Café Dir/naïve file.cs", "class C {}");

        var changed = await ws.SyncAsync([Path.Combine(root, "src/Café Dir/naïve file.cs")], CancellationToken.None);

        CollectionAssert.AreEqual(new[] { "src/Café Dir/naïve file.cs" }, changed.ToArray());
        Assert.IsTrue(File.Exists(Path.Combine(layout.MirrorRoot, "src/Café Dir/naïve file.cs")));
    }

    [TestMethod]
    public async Task Sync_accepts_paths_through_a_symlinked_root()
    {
        var (root, layout, ws) = Setup();
        await ws.InitializeAsync(CancellationToken.None);
        var link = Path.Combine(Path.GetTempPath(), "nocrunch tests", "link-" + Guid.NewGuid().ToString("N")[..8]);
        Directory.CreateSymbolicLink(link, root);
        Write(root, "src/App/Program.cs", "class P { int viaLink; }");

        var changed = await ws.SyncAsync([Path.Combine(link, "src/App/Program.cs")], CancellationToken.None);

        CollectionAssert.AreEqual(new[] { "src/App/Program.cs" }, changed.ToArray());
    }

    [TestMethod]
    public async Task Reconcile_case_only_rename_keeps_file_and_dir()
    {
        var (root, layout, ws) = Setup();
        await ws.InitializeAsync(CancellationToken.None);
        File.Move(Path.Combine(root, "src/App/Program.cs"), Path.Combine(root, "src/App/program.cs"));
        Directory.Move(Path.Combine(root, "src/App"), Path.Combine(root, "src/tmp-rename"));
        Directory.Move(Path.Combine(root, "src/tmp-rename"), Path.Combine(root, "src/app"));

        await ws.ReconcileAsync(CancellationToken.None);

        Assert.IsTrue(File.Exists(Path.Combine(layout.MirrorRoot, "src/app/program.cs")), "file must survive a case-only rename");
        Assert.IsTrue(File.Exists(Path.Combine(layout.MirrorRoot, "src/app/App.csproj")), "dir must survive a case-only rename");
    }

    private static (string root, RepoLayout layout, SharedMirrorWorkspace ws) SetupRealGitRepo(string gitignore)
    {
        var root = TempDir.Create();
        NoCrunch.Engine.Tests.TestSupport.SampleSolution.Git(root, "init", "-q");
        Write(root, "Sample.slnx", "<Solution />");
        Write(root, "src/App/App.csproj", "<Project />");
        Write(root, "src/App/appsettings.Development.json", "{}");
        Write(root, "plugin/.gradle/caches/huge.bin", "cache");
        Write(root, "artifacts/My Pkg/out.nupkg", "pkg");
        Write(root, ".gitignore", gitignore);
        var layout = RepoLayout.Resolve(Path.Combine(root, "Sample.slnx"));
        return (root, layout, new SharedMirrorWorkspace(layout, new PathMap(layout.RepoRoot, layout.MirrorRoot)));
    }

    [TestMethod]
    public async Task Initialize_skips_gitignored_directories_but_copies_ignored_files()
    {
        var (_, layout, ws) = SetupRealGitRepo("appsettings.Development.json\n.gradle/\nartifacts/\n");

        await ws.InitializeAsync(CancellationToken.None);

        Assert.IsTrue(File.Exists(Path.Combine(layout.MirrorRoot, "src/App/appsettings.Development.json")), "ignored files are still copied");
        Assert.IsFalse(Directory.Exists(Path.Combine(layout.MirrorRoot, "plugin/.gradle")), "ignored directories are skipped");
        Assert.IsFalse(Directory.Exists(Path.Combine(layout.MirrorRoot, "artifacts")), "ignored directories are skipped");
        Assert.IsTrue(File.Exists(Path.Combine(layout.MirrorRoot, "src/App/App.csproj")));
    }

    [TestMethod]
    public async Task Sync_ignores_changes_inside_gitignored_directories()
    {
        var (root, _, ws) = SetupRealGitRepo("artifacts/\n");
        await ws.InitializeAsync(CancellationToken.None);
        Write(root, "artifacts/My Pkg/out2.nupkg", "pkg2");

        var changed = await ws.SyncAsync([Path.Combine(root, "artifacts/My Pkg/out2.nupkg")], CancellationToken.None);

        Assert.AreEqual(0, changed.Count);
    }

    [TestMethod]
    public async Task Gitignore_change_removes_newly_ignored_directories_from_mirror()
    {
        var (root, layout, ws) = SetupRealGitRepo("");
        await ws.InitializeAsync(CancellationToken.None);
        Assert.IsTrue(Directory.Exists(Path.Combine(layout.MirrorRoot, "artifacts")));

        Write(root, ".gitignore", "artifacts/\n");
        await ws.SyncAsync([Path.Combine(root, ".gitignore")], CancellationToken.None);

        Assert.IsFalse(Directory.Exists(Path.Combine(layout.MirrorRoot, "artifacts")));
    }

    [TestMethod]
    public async Task Reconcile_after_branch_switch_matches_repo()
    {
        var (root, layout, ws) = Setup();
        for (var i = 0; i < 60; i++) Write(root, $"src/Lib/F{i}.cs", $"class F{i} {{}}");
        await ws.InitializeAsync(CancellationToken.None);

        for (var i = 0; i < 50; i++) Write(root, $"src/Lib/F{i}.cs", $"class F{i} {{ int changed; }}");
        for (var i = 50; i < 60; i++) File.Delete(Path.Combine(root, $"src/Lib/F{i}.cs"));
        for (var i = 0; i < 10; i++) Write(root, $"src/Lib/Added{i}.cs", "class A {}");

        var changed = await ws.ReconcileAsync(CancellationToken.None);

        Assert.AreEqual(70, changed.Count);
        CollectionAssert.AreEquivalent(Tree(root), Tree(layout.MirrorRoot));
    }

    private static string[] Tree(string dir) =>
        Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories)
            .Select(f => Path.GetRelativePath(dir, f).Replace('\\', '/'))
            .Where(r => !FileFilter.IsExcluded(r))
            .Select(r => r + "=" + File.ReadAllText(Path.Combine(dir, r)))
            .ToArray();
}
