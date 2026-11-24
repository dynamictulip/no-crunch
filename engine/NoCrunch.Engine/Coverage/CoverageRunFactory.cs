using NoCrunch.Engine.Build;
using NoCrunch.Engine.Core;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Layout;

namespace NoCrunch.Engine.Coverage;

/// <summary>Starts a <see cref="CoverageRun"/> per test project run: copies its output, instruments the copy, opens a collector session.</summary>
internal sealed class CoverageRunFactory(ICoverageTool? tool, CoverageAvailability availability, PathMap map, RepoLayout layout,
    Func<string, string, Task> log) : ICoverageRunFactory
{
    public const string InstallCommand = "dotnet tool install --global dotnet-coverage";

    public static async Task<CoverageRunFactory> CreateAsync(IProcessRunner runner, string? homeDir, string? pathVariable,
        PathMap map, RepoLayout layout, Func<string, string, Task> log, CancellationToken ct)
    {
        var info = await DotnetCoverageTool.DetectAsync(runner, homeDir, pathVariable, ct);
        return info is null
            ? new CoverageRunFactory(null, new CoverageAvailability(false,
                $"Line coverage is off: dotnet-coverage 18.0 or newer was not found. Install it with: {InstallCommand}"), map, layout, log)
            : new CoverageRunFactory(new DotnetCoverageTool(info, runner), new CoverageAvailability(true, null), map, layout, log);
    }

    public Task<CoverageAvailability> CheckAsync(CancellationToken ct) => Task.FromResult(availability);

    public async Task<ICoverageRun?> StartAsync(ProjectInfo project, ProjectGraph graph, CancellationToken ct)
    {
        if (tool is null)
        {
            await log("info", $"Coverage skipped for {project.RepoRelativePath}: {availability.Message}");
            return null;
        }
        if (!project.IsMtp || project.TargetPath is null || !File.Exists(project.TargetPath))
        {
            await log("info", $"Coverage skipped for {project.RepoRelativePath}: not a Microsoft.Testing.Platform test application with a build output.");
            return null;
        }
        var outputDir = Path.GetDirectoryName(project.TargetPath)!;
        var targets = CoverageTargets.Select(project, graph, outputDir);
        if (targets.Production.Count == 0)
        {
            await log("info", $"Coverage skipped for {project.RepoRelativePath}: no production assemblies with a PDB to instrument.");
            return null;
        }
        if (targets.Missing.Count > 0)
            await log("info", $"Coverage for {project.RepoRelativePath} leaves out {string.Join(", ", targets.Missing.Select(p => p.Name))} (no DLL or PDB in the output).");

        var dir = Path.Combine(layout.StateDir, "coverage", project.RepoRelativePath.Replace('/', '_'));
        // Next to the original output (same depth), so tests that find files relative to the assembly resolve the same way.
        var copyDir = Path.TrimEndingDirectorySeparator(outputDir) + ".nccov";
        ICoverageSession? session = null;
        var started = false;
        try
        {
            var app = CoverageTargets.CopyOutput(outputDir, copyDir, project.TargetPath);
            session = await tool.StartSessionAsync(Path.Combine(dir, "session"), ct);
            foreach (var dll in targets.DllNames) await session.InstrumentAsync(Path.Combine(copyDir, dll), ct);
            started = true;
            return new CoverageRun(session, map, project.RepoRelativePath, app,
                targets.Production.Select(p => p.RepoRelativePath).ToList(), log, copyDir);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            await log("warning", $"Coverage skipped for {project.RepoRelativePath}: {ex.Message}");
            return null;
        }
        finally
        {
            // A failed or cancelled start leaves nothing behind: no collector process and no copied output.
            if (!started)
            {
                if (session is not null) await session.DisposeAsync();
                try { if (Directory.Exists(copyDir)) Directory.Delete(copyDir, recursive: true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
                try { if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            }
        }
    }
}
