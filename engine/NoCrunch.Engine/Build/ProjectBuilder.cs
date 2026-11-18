using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Layout;

namespace NoCrunch.Engine.Build;

public enum BuildStatus { Succeeded, Failed, Blocked, Cancelled }

/// <summary>Result of building one project. <see cref="Output"/> has mirror paths rewritten to repo paths.</summary>
public sealed record BuildOutcome(string Project, BuildStatus Status, string Output, string? BlockedBy);

/// <summary>Builds projects one at a time in the mirror, never building project references transitively.</summary>
public sealed class ProjectBuilder(IProcessRunner runner, PathMap map, string dotnetPath) : NoCrunch.Engine.Core.IBuildStep
{
    public async Task RestoreAsync(string mirrorSolutionPath, CancellationToken ct)
    {
        var result = await runner.RunAsync(dotnetPath, ["restore", mirrorSolutionPath, "-nologo", "-v:q"],
            Path.GetDirectoryName(mirrorSolutionPath)!, ct);
        if (result.ExitCode != 0)
            throw new InvalidOperationException("Restore failed:\n" + map.RewriteText(result.StdOut + result.StdErr));
    }

    public async Task<IReadOnlyDictionary<string, BuildOutcome>> BuildAsync(
        IReadOnlyList<ProjectInfo> inOrder,
        Func<ProjectInfo, Task>? onStarted,
        Func<BuildOutcome, Task>? onFinished,
        CancellationToken ct)
    {
        var outcomes = new Dictionary<string, BuildOutcome>(StringComparer.Ordinal);
        foreach (var project in inOrder)
        {
            var outcome = await BuildOneAsync(project, outcomes, onStarted, ct);
            outcomes[project.RepoRelativePath] = outcome;
            if (onFinished is not null) await onFinished(outcome);
        }
        return outcomes;
    }

    private async Task<BuildOutcome> BuildOneAsync(ProjectInfo project, Dictionary<string, BuildOutcome> soFar,
        Func<ProjectInfo, Task>? onStarted, CancellationToken ct)
    {
        var path = project.RepoRelativePath;
        if (ct.IsCancellationRequested) return new BuildOutcome(path, BuildStatus.Cancelled, "", null);

        var blocker = project.ProjectReferences.FirstOrDefault(r =>
            soFar.TryGetValue(r, out var o) && o.Status is BuildStatus.Failed or BuildStatus.Blocked or BuildStatus.Cancelled);
        if (blocker is not null)
        {
            var status = soFar[blocker].Status == BuildStatus.Cancelled ? BuildStatus.Cancelled : BuildStatus.Blocked;
            return new BuildOutcome(path, status, "", status == BuildStatus.Blocked ? blocker : null);
        }

        if (onStarted is not null) await onStarted(project);
        try
        {
            var result = await runner.RunAsync(dotnetPath,
                ["build", project.MirrorProjectPath, "--no-restore", "-p:BuildProjectReferences=false", "-nologo", "-v:q", "-clp:NoSummary"],
                Path.GetDirectoryName(project.MirrorProjectPath)!, ct);
            var output = map.RewriteText((result.StdOut + result.StdErr).Trim());
            return new BuildOutcome(path, result.ExitCode == 0 ? BuildStatus.Succeeded : BuildStatus.Failed, output, null);
        }
        catch (OperationCanceledException)
        {
            return new BuildOutcome(path, BuildStatus.Cancelled, "", null);
        }
    }
}
