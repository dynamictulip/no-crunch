using NoCrunch.Engine.Build;
using NoCrunch.Engine.Coverage;
using NoCrunch.Engine.Tests.Layout;
using NoCrunch.Engine.Tests.TestSupport;

namespace NoCrunch.Engine.Tests.Coverage;

[TestClass]
public class DotnetCoverageToolTests
{
    private sealed class FakeRunner(string versionOutput) : IProcessRunner
    {
        public List<string> Files { get; } = [];
        public Task<ProcessResult> RunAsync(string file, IReadOnlyList<string> args, string workingDir, CancellationToken ct)
        {
            Files.Add(file);
            return Task.FromResult(new ProcessResult(0, versionOutput, ""));
        }
    }

    private static string Stub(string dir)
    {
        Directory.CreateDirectory(dir);
        var path = Path.Combine(dir, OperatingSystem.IsWindows() ? "dotnet-coverage.exe" : "dotnet-coverage");
        File.WriteAllText(path, "");
        return path;
    }

    [TestMethod]
    public void ParseVersion_reads_the_version_output()
    {
        var v = DotnetCoverageTool.ParseVersion("18.10.0+f4cc39224845ffa74bf246c9da2399d50e5d6342\n");
        Assert.AreEqual(18, v!.Major);
        Assert.AreEqual(10, v.Minor);
    }

    [TestMethod]
    public void ParseVersion_reads_the_banner()
    {
        var v = DotnetCoverageTool.ParseVersion("dotnet-coverage v18.10.0.0 [osx-arm64 - .NET 8.0.8]");
        Assert.AreEqual(18, v!.Major);
        Assert.AreEqual(10, v.Minor);
    }

    [TestMethod]
    public void ParseVersion_returns_null_for_garbage()
    {
        Assert.IsNull(DotnetCoverageTool.ParseVersion("command not found"));
        Assert.IsNull(DotnetCoverageTool.ParseVersion(""));
    }

    [TestMethod]
    public async Task Detect_returns_null_when_not_found()
    {
        var empty = TempDir.Create();

        var info = await DotnetCoverageTool.DetectAsync(new FakeRunner("18.10.0"), empty, empty, CancellationToken.None);

        Assert.IsNull(info);
    }

    [TestMethod]
    public async Task Detect_returns_null_below_18()
    {
        var dir = TempDir.Create();
        Stub(dir);

        var info = await DotnetCoverageTool.DetectAsync(new FakeRunner("17.9.0+abc"), TempDir.Create(), dir, CancellationToken.None);

        Assert.IsNull(info);
    }

    [TestMethod]
    public async Task Detect_prefers_PATH_then_the_dotnet_tools_dir()
    {
        var onPath = Stub(TempDir.Create());
        var home = TempDir.Create();
        Stub(Path.Combine(home, ".dotnet", "tools"));
        var runner = new FakeRunner("18.10.0+abc");

        var info = await DotnetCoverageTool.DetectAsync(runner, home, Path.GetDirectoryName(onPath), CancellationToken.None);

        Assert.AreEqual(onPath, info!.Path);
        Assert.AreEqual(18, info.Version.Major);

        var fromTools = await DotnetCoverageTool.DetectAsync(runner, home, TempDir.Create(), CancellationToken.None);
        StringAssert.Contains(fromTools!.Path, Path.Combine(".dotnet", "tools"));
    }

    [TestMethod]
    [Timeout(300_000)]
    public async Task Session_instruments_snapshots_and_cleans_up()
    {
        var runner = new ProcessRunner();
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (await DotnetCoverageTool.DetectAsync(runner, home, Environment.GetEnvironmentVariable("PATH"), CancellationToken.None) is not { } info)
        {
            Assert.Inconclusive("dotnet-coverage is not installed");
            return;
        }
        var sample = await MirroredSample.CreateAsync();
        await sample.BuildAllAsync();
        var catalog = sample.Graph.Projects.Single(p => p.Name == "Shop.Catalog");
        var outputDir = Path.Combine(Path.GetDirectoryName(catalog.MirrorProjectPath)!, "bin", "Debug", "net10.0");
        var work = TempDir.Create();
        var dll = Path.Combine(work, "Shop.Catalog.dll");
        File.Copy(Path.Combine(outputDir, "Shop.Catalog.dll"), dll);
        File.Copy(Path.Combine(outputDir, "Shop.Catalog.pdb"), Path.ChangeExtension(dll, ".pdb"));
        var before = File.ReadAllBytes(dll);

        int serverId;
        await using (var session = (CoverageSession)await new DotnetCoverageTool(info, runner).StartSessionAsync(Path.Combine(work, "session"), CancellationToken.None))
        {
            serverId = session.ServerProcessId;
            await session.InstrumentAsync(dll, CancellationToken.None);
            var xml = await session.SnapshotCoberturaAsync(CancellationToken.None);
            Assert.AreEqual(0, CoberturaParser.Parse(xml).Count);   // nothing ran yet
        }

        Assert.IsFalse(File.ReadAllBytes(dll).SequenceEqual(before), "the DLL in the copy was instrumented");
        Assert.IsFalse(Directory.Exists(Path.Combine(work, "session")), "work dir is removed");
        Assert.IsTrue(IsGone(serverId), "the collector process is gone");
    }

    private static bool IsGone(int pid)
    {
        for (var i = 0; i < 50; i++)
        {
            try { System.Diagnostics.Process.GetProcessById(pid); }
            catch (ArgumentException) { return true; }
            Thread.Sleep(100);
        }
        return false;
    }
}
