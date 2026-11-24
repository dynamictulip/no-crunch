using NoCrunch.Engine.Core;
using NoCrunch.Engine.Coverage;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Layout;
using NoCrunch.Engine.Testing;
using NoCrunch.Engine.Tests.Layout;

namespace NoCrunch.Engine.Tests.Coverage;

[TestClass]
public class CoverageRunTests
{
    private sealed class FakeSession(params object[] snapshots) : ICoverageSession
    {
        private readonly Queue<object> _snapshots = new(snapshots);
        public int Snapshots { get; private set; }
        public bool Disposed { get; private set; }
        public Task InstrumentAsync(string dllPath, CancellationToken ct) => Task.CompletedTask;
        public Task<string> SnapshotCoberturaAsync(CancellationToken ct)
        {
            Snapshots++;
            var next = _snapshots.Count > 0 ? _snapshots.Dequeue() : Report();
            return next is Exception e ? Task.FromException<string>(e) : Task.FromResult((string)next);
        }
        public ValueTask DisposeAsync() { Disposed = true; return ValueTask.CompletedTask; }
    }

    private sealed class Env
    {
        public string Root { get; } = TempDir.Create();
        public PathMap Map { get; }
        public List<(string level, string message)> Log { get; } = [];
        public Env() => Map = new PathMap(Root, Path.Combine(Root, ".nocrunch", "S", "mirror"));
        public string MirrorFile(string rel, string text = "line1\r\nline2\r\n")
        {
            var path = Map.MirrorPath(rel);
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllText(path, text);
            return path;
        }
        public Task LogAsync(string level, string message) { lock (Log) Log.Add((level, message)); return Task.CompletedTask; }
        public CoverageRun Run(FakeSession session, params string[] assemblies) =>
            new(session, Map, "tests/T/T.csproj", "/copy/T.dll", assemblies.Length == 0 ? ["src/Prod/Prod.csproj"] : assemblies, LogAsync, null);
    }

    private static string Report(params (string file, int[] hit, int[] executable)[] files) =>
        "<coverage><sources/><packages><package><classes>" + string.Concat(files.Select(f =>
            $"<class filename=\"{f.file}\"><lines>{string.Concat(f.executable.Select(l => $"<line number=\"{l}\" hits=\"{(f.hit.Contains(l) ? 1 : 0)}\"/>"))}</lines></class>")) +
        "</classes></package></packages></coverage>";

    private static TestResultUpdate Result(string method, TestState state) =>
        new(new TestId("tests/T/T.csproj", "Ns.C", method, method), method, state, 1, null, null, null, null);

    private static readonly RunAlone Unused = (_, _) => throw new InvalidOperationException("no rerun expected");

    [TestMethod]
    public async Task ClassFinished_records_a_ClassCoverage_with_repo_relative_paths()
    {
        var env = new Env();
        var a = env.MirrorFile("src/Prod/A.cs");
        var gen = env.MirrorFile("src/Prod/obj/Debug/Gen.g.cs");
        var snapshot = Report((a, [1], [1, 2]), (gen, [1], [1]), ("/elsewhere/Framework.cs", [1], [1]));
        var run = env.Run(new FakeSession(snapshot));

        await run.ClassFinishedAsync("Ns.C", [Result("M", TestState.Passed)], CancellationToken.None);
        await run.FinishedAsync(Unused, CancellationToken.None);

        var cls = run.Pass!.Classes.Single();
        Assert.AreEqual("Ns.C", cls.TestClass);
        CollectionAssert.AreEqual(new[] { "src/Prod/A.cs" }, cls.Files.Keys.ToArray());
        CollectionAssert.AreEquivalent(new[] { 1 }, cls.Files["src/Prod/A.cs"].ToArray());
        CollectionAssert.AreEquivalent(new[] { 1, 2 }, run.Pass.Executable["src/Prod/A.cs"].ToArray(), "executable lines are kept once per pass, not per class");
    }

    [TestMethod]
    public async Task Executable_lines_are_stored_once_however_many_classes_report_them()
    {
        var env = new Env();
        var a = env.MirrorFile("src/Prod/A.cs");
        var run = env.Run(new FakeSession(Report((a, [1], [1, 2, 3])), Report((a, [2], [1, 2, 3])), Report((a, [3], [1, 2, 3]))));

        foreach (var cls in new[] { "C1", "C2", "C3" }) await run.ClassFinishedAsync(cls, [Result("M", TestState.Passed)], CancellationToken.None);
        await run.FinishedAsync(Unused, CancellationToken.None);

        CollectionAssert.AreEquivalent(new[] { 1, 2, 3 }, run.Pass!.Executable["src/Prod/A.cs"].ToArray());
        Assert.AreEqual(3, run.Pass.Classes.Count);
        Assert.IsTrue(run.Pass.Classes.All(c => c.Files["src/Prod/A.cs"].Count == 1), "a class keeps only the lines it hit");
    }

    [TestMethod]
    public async Task Failing_theory_rows_sharing_a_uid_are_rerun_once()
    {
        var env = new Env();
        var a = env.MirrorFile("src/Prod/A.cs");
        var run = env.Run(new FakeSession(Report((a, [1], [1, 2]))));
        var rows = Enumerable.Range(1, 3).Select(i => new TestResultUpdate(new TestId("tests/T/T.csproj", "Ns.C", "Theory", "same-uid", $"Theory({i})"),
            $"Theory({i})", TestState.Failed, 1, null, null, null, null)).ToList();
        var reruns = 0;

        await run.ClassFinishedAsync("Ns.C", rows, CancellationToken.None);
        await run.FinishedAsync((_, _) => { reruns++; return Task.FromResult(TestState.Failed); }, CancellationToken.None);

        Assert.AreEqual(1, reruns);
    }

    [TestMethod]
    public async Task A_timeout_cancellation_during_a_rerun_is_not_swallowed()
    {
        var env = new Env();
        var a = env.MirrorFile("src/Prod/A.cs");
        var run = env.Run(new FakeSession(Report((a, [1], [1, 2]))));

        await run.ClassFinishedAsync("Ns.C", [Result("Bad", TestState.Failed)], CancellationToken.None);
        // The caller's own token is not cancelled: the cancellation came from the runner's watchdog.
        await Assert.ThrowsExactlyAsync<OperationCanceledException>(() =>
            run.FinishedAsync((_, _) => throw new OperationCanceledException("watchdog"), CancellationToken.None));

        Assert.IsNull(run.Pass);
    }

    [TestMethod]
    public async Task Failing_test_is_rerun_alone_and_its_lines_become_failing_hit()
    {
        var env = new Env();
        var a = env.MirrorFile("src/Prod/A.cs");
        var session = new FakeSession(Report((a, [1, 2], [1, 2, 3])), Report((a, [2], [1, 2, 3])));
        var run = env.Run(session);
        var rerun = new List<string>();

        await run.ClassFinishedAsync("Ns.C", [Result("Ok", TestState.Passed), Result("Bad", TestState.Failed)], CancellationToken.None);
        await run.FinishedAsync((t, _) => { rerun.Add(t.Id.Method!); return Task.FromResult(TestState.Failed); }, CancellationToken.None);

        CollectionAssert.AreEqual(new[] { "Bad" }, rerun);
        CollectionAssert.AreEquivalent(new[] { 2 }, run.Pass!.FailingHit["src/Prod/A.cs"].ToArray());
        Assert.AreEqual(0, run.Pass.UnrefinedFailingClasses.Count);
    }

    [TestMethod]
    public async Task Failing_test_that_passes_alone_is_flaky_it_adds_no_failing_lines_and_logs_a_warning()
    {
        var env = new Env();
        var a = env.MirrorFile("src/Prod/A.cs");
        var run = env.Run(new FakeSession(Report((a, [1, 2], [1, 2])), Report((a, [2], [1, 2]))));

        await run.ClassFinishedAsync("Ns.C", [Result("Bad", TestState.Failed)], CancellationToken.None);
        await run.FinishedAsync((_, _) => Task.FromResult(TestState.Passed), CancellationToken.None);

        Assert.AreEqual(0, run.Pass!.FailingHit.Count);
        Assert.IsTrue(env.Log.Any(l => l.level == "warning" && l.message.Contains("flaky", StringComparison.OrdinalIgnoreCase)), string.Join("|", env.Log));
    }

    [TestMethod]
    public async Task Failing_test_whose_rerun_throws_marks_its_class_unrefined_but_the_pass_still_succeeds()
    {
        var env = new Env();
        var a = env.MirrorFile("src/Prod/A.cs");
        var run = env.Run(new FakeSession(Report((a, [1], [1, 2]))));

        await run.ClassFinishedAsync("Ns.C", [Result("Bad", TestState.Failed)], CancellationToken.None);
        await run.FinishedAsync((_, _) => throw new InvalidOperationException("host died"), CancellationToken.None);

        Assert.IsNotNull(run.Pass);
        CollectionAssert.AreEqual(new[] { "Ns.C" }, run.Pass.UnrefinedFailingClasses.ToArray());
    }

    [TestMethod]
    public async Task Snapshot_failure_in_a_class_leaves_Pass_null()
    {
        var env = new Env();
        var run = env.Run(new FakeSession(new InvalidOperationException("snapshot failed")));

        await run.ClassFinishedAsync("Ns.C", [Result("M", TestState.Passed)], CancellationToken.None);   // must not throw
        await run.FinishedAsync(Unused, CancellationToken.None);

        Assert.IsNull(run.Pass);
        Assert.IsTrue(env.Log.Any(l => l.level == "warning"));
    }

    [TestMethod]
    public async Task Cancellation_leaves_Pass_null_and_disposes_the_session()
    {
        var env = new Env();
        var a = env.MirrorFile("src/Prod/A.cs");
        var session = new FakeSession(Report((a, [1], [1])));
        var run = env.Run(session);
        using var cts = new CancellationTokenSource();

        await run.ClassFinishedAsync("Ns.C", [Result("Bad", TestState.Failed)], cts.Token);
        await cts.CancelAsync();
        await Assert.ThrowsExactlyAsync<OperationCanceledException>(() =>
            run.FinishedAsync((_, ct) => { ct.ThrowIfCancellationRequested(); return Task.FromResult(TestState.Failed); }, cts.Token));
        await run.DisposeAsync();

        Assert.IsNull(run.Pass);
        Assert.IsTrue(session.Disposed);
    }

    [TestMethod]
    public async Task FileHashes_come_from_the_mirror_copy_of_each_covered_file()
    {
        var env = new Env();
        var a = env.MirrorFile("src/Prod/A.cs", "line1\r\nline2\r\n");
        var run = env.Run(new FakeSession(Report((a, [1], [1, 2]))));

        await run.ClassFinishedAsync("Ns.C", [Result("M", TestState.Passed)], CancellationToken.None);
        await run.FinishedAsync(Unused, CancellationToken.None);

        Assert.AreEqual(ContentHash.Of("line1\nline2\n"), run.Pass!.FileHashes["src/Prod/A.cs"]);
    }

    [TestMethod]
    public async Task Pass_Assemblies_lists_the_instrumented_production_projects()
    {
        var env = new Env();
        var run = env.Run(new FakeSession(), "src/A/A.csproj", "src/B/B.csproj");

        await run.ClassFinishedAsync("Ns.C", [Result("M", TestState.Passed)], CancellationToken.None);
        await run.FinishedAsync(Unused, CancellationToken.None);

        CollectionAssert.AreEquivalent(new[] { "src/A/A.csproj", "src/B/B.csproj" }, run.Pass!.Assemblies.ToArray());
        Assert.AreEqual("tests/T/T.csproj", run.Pass.TestProject);
    }
}

[TestClass]
public class CoverageRunFactoryTests
{
    private static ProjectInfo Project(bool mtp, string? target) =>
        new("tests/T/T.csproj", "T", "/m/tests/T/T.csproj", true, mtp, target, [], []);

    private static async Task<ProjectGraph> EmptyGraph()
    {
        var root = TempDir.Create();
        File.WriteAllText(Path.Combine(root, "S.slnx"), "");
        return await ProjectGraph.LoadAsync(RepoLayout.Resolve(Path.Combine(root, "S.slnx")),
            new NoCrunch.Engine.Tests.Graph.ProjectGraphTests.FakeQuery(), CancellationToken.None);
    }

    private static (CoverageRunFactory factory, List<string> log) Factory(ICoverageTool? tool)
    {
        var root = TempDir.Create();
        var layout = RepoLayout.Resolve(Path.Combine(root, "S.slnx"));
        var log = new List<string>();
        var availability = tool is null ? new CoverageAvailability(false, "off") : new CoverageAvailability(true, null);
        return (new CoverageRunFactory(tool, availability, new PathMap(layout.RepoRoot, layout.MirrorRoot), layout,
            (level, message) => { log.Add(level + ": " + message); return Task.CompletedTask; }), log);
    }

    [TestMethod]
    public async Task StartAsync_returns_null_and_logs_when_the_tool_is_missing()
    {
        var (factory, log) = Factory(null);

        Assert.IsNull(await factory.StartAsync(Project(true, "/m/T.dll"), await EmptyGraph(), CancellationToken.None));
        Assert.AreEqual(1, log.Count);
    }

    [TestMethod]
    public async Task StartAsync_returns_null_for_a_non_MTP_project()
    {
        var tool = new Moq_Tool();
        var (factory, log) = Factory(tool);

        Assert.IsNull(await factory.StartAsync(Project(false, "/m/T.dll"), await EmptyGraph(), CancellationToken.None));
        Assert.IsFalse(tool.Started);
        StringAssert.Contains(log.Single(), "not a Microsoft.Testing.Platform");
    }

    private sealed class Moq_Tool : ICoverageTool
    {
        public bool Started { get; private set; }
        public Task<ICoverageSession> StartSessionAsync(string workDir, CancellationToken ct) { Started = true; throw new NotSupportedException(); }
    }

    [TestMethod]
    public async Task CheckAsync_reports_unavailable_with_the_install_command_in_the_message()
    {
        var empty = TempDir.Create();
        var layout = RepoLayout.Resolve(Path.Combine(empty, "S.slnx"));

        var factory = await CoverageRunFactory.CreateAsync(new NoCrunch.Engine.Build.ProcessRunner(), empty, empty,
            new PathMap(layout.RepoRoot, layout.MirrorRoot), layout, (_, _) => Task.CompletedTask, CancellationToken.None);
        var availability = await factory.CheckAsync(CancellationToken.None);

        Assert.IsFalse(availability.Available);
        StringAssert.Contains(availability.Message, "dotnet tool install --global dotnet-coverage");
    }

    private sealed class FakeSessionForFactory(Exception? failInstrument = null) : ICoverageSession
    {
        public bool Disposed { get; private set; }
        public Task InstrumentAsync(string dllPath, CancellationToken ct) => failInstrument is null ? Task.CompletedTask : Task.FromException(failInstrument);
        public Task<string> SnapshotCoberturaAsync(CancellationToken ct) => Task.FromResult("<coverage/>");
        public ValueTask DisposeAsync() { Disposed = true; return ValueTask.CompletedTask; }
    }

    private sealed class FakeToolForFactory(ICoverageSession session) : ICoverageTool
    {
        public Task<ICoverageSession> StartSessionAsync(string workDir, CancellationToken ct) => Task.FromResult(session);
    }

    /// <summary>A test project with one production reference, its output folder on disk, and a factory over a fake tool.</summary>
    private static async Task<(CoverageRunFactory factory, ProjectInfo project, ProjectGraph graph, string outputDir)> Scenario(ICoverageSession session)
    {
        var output = Path.Combine(TempDir.Create(), "bin", "Debug", "net10.0");
        Directory.CreateDirectory(output);
        foreach (var f in new[] { "T.dll", "A.dll", "A.pdb" }) File.WriteAllText(Path.Combine(output, f), f);
        var test = new ProjectInfo("tests/T/T.csproj", "T", "/m/tests/T/T.csproj", true, true, Path.Combine(output, "T.dll"), ["src/A/A.csproj"], []);
        var prod = new ProjectInfo("src/A/A.csproj", "A", "/m/src/A/A.csproj", false, false, Path.Combine(output, "A.dll"), [], []);
        var root = TempDir.Create();
        File.WriteAllText(Path.Combine(root, "S.slnx"), "");
        var layout = RepoLayout.Resolve(Path.Combine(root, "S.slnx"));
        var graph = await ProjectGraph.LoadAsync(layout, new NoCrunch.Engine.Tests.Graph.ProjectGraphTests.FakeQuery(test, prod), CancellationToken.None);
        var factory = new CoverageRunFactory(new FakeToolForFactory(session), new CoverageAvailability(true, null),
            new PathMap(layout.RepoRoot, layout.MirrorRoot), layout, (_, _) => Task.CompletedTask);
        return (factory, test, graph, output);
    }

    [TestMethod]
    public async Task StartAsync_cancelled_while_instrumenting_disposes_the_session_removes_the_copy_and_rethrows()
    {
        var session = new FakeSessionForFactory(new OperationCanceledException("cancelled"));
        var (factory, project, graph, output) = await Scenario(session);

        await Assert.ThrowsExactlyAsync<OperationCanceledException>(() => factory.StartAsync(project, graph, CancellationToken.None));

        Assert.IsTrue(session.Disposed, "the collector session was stopped");
        Assert.IsFalse(Directory.Exists(output + ".nccov"), "the copied output was removed");
    }

    [TestMethod]
    public async Task StartAsync_failing_to_instrument_disposes_the_session_removes_the_copy_and_skips_coverage()
    {
        var session = new FakeSessionForFactory(new InvalidOperationException("cannot instrument"));
        var (factory, project, graph, output) = await Scenario(session);

        Assert.IsNull(await factory.StartAsync(project, graph, CancellationToken.None));

        Assert.IsTrue(session.Disposed);
        Assert.IsFalse(Directory.Exists(output + ".nccov"));
    }

    [TestMethod]
    public async Task StartAsync_puts_the_copy_next_to_the_original_output_so_relative_paths_from_the_assembly_still_resolve()
    {
        var (factory, project, graph, output) = await Scenario(new FakeSessionForFactory());

        await using var run = await factory.StartAsync(project, graph, CancellationToken.None);

        Assert.AreEqual(Path.GetDirectoryName(output), Path.GetDirectoryName(Path.GetDirectoryName(run!.TargetPath)), "same depth as the original bin/Debug/net10.0");
        Assert.AreEqual(Path.Combine(output + ".nccov", "T.dll"), run.TargetPath);
        await run.DisposeAsync();
        Assert.IsFalse(Directory.Exists(output + ".nccov"));
    }
}
