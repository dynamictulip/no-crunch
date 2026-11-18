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
    public async Task Gitignore_change_leaves_newly_ignored_directories_in_mirror()
    {
        // The mirror can't tell sources copied before the directory was ignored from build output written there
        // since (artifacts/ with UseArtifactsOutput), so an ignored directory's mirror copy is never touched.
        var (root, layout, ws) = SetupRealGitRepo("");
        await ws.InitializeAsync(CancellationToken.None);
        Assert.IsTrue(Directory.Exists(Path.Combine(layout.MirrorRoot, "artifacts")));

        Write(root, ".gitignore", "artifacts/\n");
        var changed = await ws.SyncAsync([Path.Combine(root, ".gitignore")], CancellationToken.None);

        Assert.IsTrue(File.Exists(Path.Combine(layout.MirrorRoot, "artifacts/My Pkg/out.nupkg")));
        CollectionAssert.AreEqual(new[] { ".gitignore" }, changed.ToArray());
    }

    [TestMethod]
    public async Task Reconcile_leaves_build_output_in_gitignored_directories_alone()
    {
        var (_, layout, ws) = SetupRealGitRepo("artifacts/\n");
        await ws.InitializeAsync(CancellationToken.None);
        WriteMirror(layout, "artifacts/bin/App/debug/App.dll", "built in the mirror");

        var changed = await ws.ReconcileAsync(CancellationToken.None);

        Assert.AreEqual(0, changed.Count, string.Join(", ", changed));
        Assert.IsTrue(File.Exists(Path.Combine(layout.MirrorRoot, "artifacts/bin/App/debug/App.dll")));
    }

    [TestMethod]
    public async Task Reconcile_leaves_gitignored_directories_that_only_the_mirror_has_alone()
    {
        // The user never built outside No Crunch, so the repo has no artifacts/ at all; the mirror's is still output.
        var (root, layout, ws) = SetupRealGitRepo("artifacts/\n**/generated/\n");
        Directory.Delete(Path.Combine(root, "artifacts"), recursive: true);
        await ws.InitializeAsync(CancellationToken.None);
        WriteMirror(layout, "artifacts/bin/App/debug/App.dll", "built in the mirror");
        WriteMirror(layout, "src/App/generated/Gen.cs", "generated in the mirror");

        var changed = await ws.ReconcileAsync(CancellationToken.None);

        Assert.AreEqual(0, changed.Count, string.Join(", ", changed));
        Assert.IsTrue(File.Exists(Path.Combine(layout.MirrorRoot, "artifacts/bin/App/debug/App.dll")));
        Assert.IsTrue(File.Exists(Path.Combine(layout.MirrorRoot, "src/App/generated/Gen.cs")));
    }

    [TestMethod]
    public async Task Reconcile_removes_directories_deleted_from_the_repo()
    {
        var (root, layout, ws) = SetupRealGitRepo("artifacts/\n");
        await ws.InitializeAsync(CancellationToken.None);
        Directory.Delete(Path.Combine(root, "plugin"), recursive: true);

        var changed = await ws.ReconcileAsync(CancellationToken.None);

        CollectionAssert.AreEqual(new[] { "plugin/.gradle/caches/huge.bin" }, changed.ToArray());
        Assert.IsFalse(Directory.Exists(Path.Combine(layout.MirrorRoot, "plugin")));
    }

    [TestMethod]
    public async Task Sync_of_a_deleted_gitignored_directory_leaves_the_mirror_copy_alone()
    {
        var (root, layout, ws) = SetupRealGitRepo("artifacts/\n");
        await ws.InitializeAsync(CancellationToken.None);
        WriteMirror(layout, "artifacts/bin/App.dll", "built in the mirror");
        Directory.Delete(Path.Combine(root, "artifacts"), recursive: true);
        await ws.ReconcileAsync(CancellationToken.None); // forget the ignored list that still had artifacts/

        var changed = await ws.SyncAsync([Path.Combine(root, "artifacts")], CancellationToken.None);

        Assert.AreEqual(0, changed.Count, string.Join(", ", changed));
        Assert.IsTrue(File.Exists(Path.Combine(layout.MirrorRoot, "artifacts/bin/App.dll")));
    }

    [TestMethod]
    public async Task Reconcile_treats_a_file_that_vanishes_before_it_is_copied_as_deleted()
    {
        // A dangling symlink is listed like a file but can't be opened, which is exactly what a file deleted or
        // renamed between the directory listing and the copy looks like (e.g. Rider's safe-write temp files).
        var (root, layout, ws) = Setup();
        await ws.InitializeAsync(CancellationToken.None);
        WriteMirror(layout, "src/App/Gone.cs", "copied earlier");
        File.CreateSymbolicLink(Path.Combine(root, "src/App/Gone.cs"), Path.Combine(root, "src/App/nowhere.cs"));

        var changed = await ws.ReconcileAsync(CancellationToken.None);

        CollectionAssert.AreEqual(new[] { "src/App/Gone.cs" }, changed.ToArray());
        Assert.IsFalse(File.Exists(Path.Combine(layout.MirrorRoot, "src/App/Gone.cs")));
    }

    [TestMethod]
    public async Task Reconcile_reports_files_it_cannot_read()
    {
        if (OperatingSystem.IsWindows()) Assert.Inconclusive("Unix file modes");
        var (root, _, ws) = Setup();
        await ws.InitializeAsync(CancellationToken.None);
        var secret = Path.Combine(root, "src/App/Secret.cs");
        File.WriteAllText(secret, "class S {}");
        File.SetUnixFileMode(secret, UnixFileMode.None);
        try
        {
            await Assert.ThrowsExactlyAsync<UnauthorizedAccessException>(() => ws.ReconcileAsync(CancellationToken.None));
        }
        finally
        {
            File.SetUnixFileMode(secret, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        }
    }

    [TestMethod]
    public async Task Reconcile_skips_symlinked_directories_that_loop()
    {
        var (root, layout, ws) = Setup();
        Directory.CreateSymbolicLink(Path.Combine(root, "src/Root"), root);
        Directory.CreateSymbolicLink(Path.Combine(root, "src/App/Parent"), Path.Combine(root, "src"));

        await ws.InitializeAsync(CancellationToken.None);

        Assert.IsTrue(File.Exists(Path.Combine(layout.MirrorRoot, "src/App/Program.cs")));
        Assert.IsFalse(Directory.Exists(Path.Combine(layout.MirrorRoot, "src/Root")));
        Assert.IsFalse(Directory.Exists(Path.Combine(layout.MirrorRoot, "src/App/Parent")));
    }

    [TestMethod]
    public async Task Reconcile_copies_symlinked_directories_that_dont_loop_as_real_directories()
    {
        var (root, layout, ws) = Setup();
        var shared = TempDir.Create();
        Write(shared, "Shared.cs", "class Shared {}");
        Directory.CreateSymbolicLink(Path.Combine(root, "src/Shared"), shared);
        Directory.CreateSymbolicLink(Path.Combine(root, "src/AppAgain"), Path.Combine(root, "src/App"));

        await ws.InitializeAsync(CancellationToken.None);

        var mirrored = new DirectoryInfo(Path.Combine(layout.MirrorRoot, "src/Shared"));
        Assert.IsNull(mirrored.LinkTarget, "a link would let mirror builds write outside the mirror");
        Assert.AreEqual("class Shared {}", File.ReadAllText(Path.Combine(layout.MirrorRoot, "src/Shared/Shared.cs")));
        Assert.IsTrue(File.Exists(Path.Combine(layout.MirrorRoot, "src/AppAgain/Program.cs")));
    }

    private static void WriteMirror(RepoLayout layout, string rel, string content) => Write(layout.MirrorRoot, rel, content);

    [TestMethod]
    public async Task Tracks_only_files_that_would_be_mirrored()
    {
        var (root, _, ws) = SetupRealGitRepo("artifacts/\n");
        await ws.InitializeAsync(CancellationToken.None);

        Assert.IsTrue(ws.Tracks(Path.Combine(root, "src/App/Program.cs")));
        Assert.IsTrue(ws.Tracks(Path.Combine(root, "src/App/appsettings.Development.json")));
        Assert.IsFalse(ws.Tracks(Path.Combine(root, ".git/index")));
        Assert.IsFalse(ws.Tracks(Path.Combine(root, "src/App/bin/Debug/App.dll")));
        Assert.IsFalse(ws.Tracks(Path.Combine(root, "artifacts/My Pkg/out.nupkg")), "git-ignored directory");
        Assert.IsFalse(ws.Tracks(Path.Combine(root, "Sample.nocrunch.user")), "No Crunch's own exclusions file");
        Assert.IsFalse(ws.Tracks("/somewhere/else/File.cs"));
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
