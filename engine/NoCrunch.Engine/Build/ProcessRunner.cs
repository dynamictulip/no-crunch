using System.Diagnostics;
using System.Text;

namespace NoCrunch.Engine.Build;

public sealed class ProcessRunner(IReadOnlyDictionary<string, string>? environment = null) : IProcessRunner
{
    public async Task<ProcessResult> RunAsync(string file, IReadOnlyList<string> args, string workingDir, CancellationToken ct)
    {
        var psi = new ProcessStartInfo(file)
        {
            WorkingDirectory = workingDir,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        // A parent dotnet/MSBuild process (Rider, `dotnet test`, `dotnet run`) leaks MSBuild variables that
        // make child builds resolve the wrong SDK/targets. Child builds must start clean.
        foreach (var key in psi.Environment.Keys.Where(IsInheritedMsBuildVariable).ToList())
            psi.Environment.Remove(key);
        psi.Environment["DOTNET_CLI_TELEMETRY_OPTOUT"] = "1";
        psi.Environment["DOTNET_NOLOGO"] = "1";
        if (environment is not null)
            foreach (var (k, v) in environment) psi.Environment[k] = v;

        using var process = new Process { StartInfo = psi };
        var stdout = new StringBuilder();
        var stderr = new StringBuilder();
        process.OutputDataReceived += (_, e) => { if (e.Data is not null) lock (stdout) stdout.AppendLine(e.Data); };
        process.ErrorDataReceived += (_, e) => { if (e.Data is not null) lock (stderr) stderr.AppendLine(e.Data); };
        process.Start();
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        try
        {
            await process.WaitForExitAsync(ct);
        }
        catch (OperationCanceledException)
        {
            try { process.Kill(entireProcessTree: true); } catch (InvalidOperationException) { }
            throw;
        }
        process.WaitForExit(); // flush async readers
        return new ProcessResult(process.ExitCode, stdout.ToString(), stderr.ToString());
    }

    internal static bool IsInheritedMsBuildVariable(string name) =>
        name.StartsWith("MSBuild", StringComparison.OrdinalIgnoreCase)
        || name.StartsWith("MSBUILD", StringComparison.Ordinal)
        || name is "DOTNET_HOST_PATH" or "DOTNET_LAUNCH_PROFILE" or "TESTINGPLATFORM_DOTNETTEST_EXECUTIONID"
        || name.StartsWith("TESTINGPLATFORM_", StringComparison.Ordinal);
}
