using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;
using NoCrunch.Engine.Build;

namespace NoCrunch.Engine.Coverage;

internal sealed record ToolInfo(string Path, Version Version);

/// <summary>The <c>dotnet-coverage</c> global tool (18.0 or newer), used for static instrumentation and snapshots.</summary>
internal sealed partial class DotnetCoverageTool(ToolInfo info, IProcessRunner runner) : ICoverageTool
{
    private static readonly Version MinimumVersion = new(18, 0);

    [GeneratedRegex(@"(\d+)\.(\d+)(?:\.(\d+))?(?:\.(\d+))?")]
    private static partial Regex VersionPattern();

    /// <summary>Reads "18.10.0+hash" (what <c>--version</c> prints) or the banner "dotnet-coverage v18.10.0.0 [...]".</summary>
    public static Version? ParseVersion(string versionOutput)
    {
        var m = VersionPattern().Match(versionOutput);
        if (!m.Success) return null;
        var parts = m.Groups.Cast<Group>().Skip(1).Where(g => g.Success).Select(g => int.Parse(g.Value)).ToArray();
        return parts.Length switch { 2 => new Version(parts[0], parts[1]), 3 => new Version(parts[0], parts[1], parts[2]), _ => new Version(parts[0], parts[1], parts[2], parts[3]) };
    }

    /// <summary>Looks on <paramref name="pathVariable"/> and then in <c>~/.dotnet/tools</c>; null if absent or older than 18.0.</summary>
    public static async Task<ToolInfo?> DetectAsync(IProcessRunner runner, string? homeDir, string? pathVariable, CancellationToken ct)
    {
        var name = OperatingSystem.IsWindows() ? "dotnet-coverage.exe" : "dotnet-coverage";
        var dirs = (pathVariable ?? "").Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries).ToList();
        if (!string.IsNullOrEmpty(homeDir)) dirs.Add(Path.Combine(homeDir, ".dotnet", "tools"));
        foreach (var file in dirs.Select(d => Path.Combine(d, name)).Where(File.Exists))
        {
            try
            {
                var result = await runner.RunAsync(file, ["--version"], Path.GetDirectoryName(file)!, ct);
                if (result.ExitCode == 0 && ParseVersion(result.StdOut) is { } v)
                    return v >= MinimumVersion ? new ToolInfo(file, v) : null;
            }
            catch (Exception e) when (e is System.ComponentModel.Win32Exception or IOException) { }
        }
        return null;
    }

    public async Task<ICoverageSession> StartSessionAsync(string workDir, CancellationToken ct)
    {
        Directory.CreateDirectory(workDir);
        var session = new CoverageSession(info.Path, runner, workDir);
        try
        {
            await session.StartAsync(ct);
            return session;
        }
        catch
        {
            await session.DisposeAsync();
            throw;
        }
    }
}

/// <summary>
/// A collector the engine owns as a child process (<c>collect --server-mode</c> in the foreground), so cancelling or
/// disposing kills it and nothing is left behind. Commands to it are separate runs of the same tool.
/// </summary>
internal sealed class CoverageSession : ICoverageSession
{
    private readonly string _tool;
    private readonly IProcessRunner _runner;
    private readonly string _workDir;
    private readonly string _id = "nc" + Guid.NewGuid().ToString("N")[..10];
    private readonly SemaphoreSlim _snapshotLock = new(1, 1);
    private readonly StringBuilder _serverOutput = new();
    private Process? _server;
    private int _snapshots;

    public CoverageSession(string tool, IProcessRunner runner, string workDir)
    {
        _tool = tool;
        _runner = runner;
        _workDir = workDir;
    }

    public int ServerProcessId => _server?.Id ?? -1;

    public async Task StartAsync(CancellationToken ct)
    {
        var psi = new ProcessStartInfo(_tool)
        {
            WorkingDirectory = _workDir,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
            UseShellExecute = false,
        };
        foreach (var a in new[] { "collect", "--server-mode", "--session-id", _id, "-f", "cobertura", "-o", Path.Combine(_workDir, "final.xml") })
            psi.ArgumentList.Add(a);
        foreach (var key in psi.Environment.Keys.Where(ProcessRunner.IsInheritedMsBuildVariable).ToList()) psi.Environment.Remove(key);
        psi.Environment["DOTNET_CLI_TELEMETRY_OPTOUT"] = "1";
        _server = Process.Start(psi) ?? throw new InvalidOperationException("Could not start dotnet-coverage.");
        _server.StandardInput.Close();
        _server.OutputDataReceived += (_, e) => { if (e.Data is not null) lock (_serverOutput) _serverOutput.AppendLine(e.Data); };
        _server.ErrorDataReceived += (_, e) => { if (e.Data is not null) lock (_serverOutput) _serverOutput.AppendLine(e.Data); };
        _server.BeginOutputReadLine();
        _server.BeginErrorReadLine();

        // Ready once a snapshot of the empty session succeeds.
        for (var attempt = 0; attempt < 100; attempt++)
        {
            ct.ThrowIfCancellationRequested();
            if (_server.HasExited) throw new InvalidOperationException("dotnet-coverage exited while starting: " + ServerOutput());
            var probe = await _runner.RunAsync(_tool, ["snapshot", _id, "--reset", "-o", Path.Combine(_workDir, "ready.coverage")], _workDir, ct);
            if (probe.ExitCode == 0) return;
            await Task.Delay(100, ct);
        }
        throw new InvalidOperationException("dotnet-coverage did not become ready: " + ServerOutput());
    }

    public async Task InstrumentAsync(string dllPath, CancellationToken ct)
    {
        var r = await _runner.RunAsync(_tool, ["instrument", "--session-id", _id, dllPath], _workDir, ct);
        if (r.ExitCode != 0) throw new InvalidOperationException($"Could not instrument {dllPath}: {r.StdOut} {r.StdErr}".Trim());
    }

    public async Task<string> SnapshotCoberturaAsync(CancellationToken ct)
    {
        await _snapshotLock.WaitAsync(ct);
        try
        {
            var n = ++_snapshots;
            var coverage = Path.Combine(_workDir, $"snap{n}.coverage");
            var xml = Path.Combine(_workDir, $"snap{n}.xml");
            try
            {
                var snap = await _runner.RunAsync(_tool, ["snapshot", _id, "--reset", "-o", coverage], _workDir, ct);
                if (snap.ExitCode != 0) throw new InvalidOperationException("Snapshot failed: " + snap.StdOut + snap.StdErr);
                var merge = await _runner.RunAsync(_tool, ["merge", "-f", "cobertura", "-o", xml, coverage], _workDir, ct);
                if (merge.ExitCode != 0) throw new InvalidOperationException("Merge failed: " + merge.StdOut + merge.StdErr);
                return await File.ReadAllTextAsync(xml, Encoding.UTF8, ct);
            }
            finally
            {
                TryDelete(coverage);
                TryDelete(xml);
            }
        }
        finally
        {
            _snapshotLock.Release();
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (_server is { } server)
        {
            try
            {
                if (!server.HasExited)
                {
                    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
                    try { await _runner.RunAsync(_tool, ["shutdown", _id], _workDir, timeout.Token); } catch (OperationCanceledException) { }
                    try { await server.WaitForExitAsync(timeout.Token); } catch (OperationCanceledException) { }
                }
                if (!server.HasExited) server.Kill(entireProcessTree: true);
            }
            catch (InvalidOperationException) { }
            server.Dispose();
        }
        try { if (Directory.Exists(_workDir)) Directory.Delete(_workDir, recursive: true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }

    private string ServerOutput() { lock (_serverOutput) return _serverOutput.ToString().Trim(); }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }
}
