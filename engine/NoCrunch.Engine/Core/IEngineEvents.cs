using NoCrunch.Engine.Build;
using NoCrunch.Engine.Coverage;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Testing;

namespace NoCrunch.Engine.Core;

public enum EngineState { Idle, Syncing, Building, Testing, Paused }

/// <summary>Whether line coverage can be collected; <see cref="Message"/> says why not (and how to fix it) when it can't.</summary>
public sealed record CoverageAvailability(bool Available, string? Message);

/// <summary>Everything the engine reports outward (to the plugin, via the protocol).</summary>
public interface IEngineEvents
{
    Task StateChanged(EngineState state, int queuedProjects);
    Task ProjectBuildStarted(string project);
    Task ProjectBuildFinished(BuildOutcome outcome);
    /// <summary>The full current test list for a project (replaces any earlier list).</summary>
    Task TestsDiscovered(string project, IReadOnlyList<TestResultUpdate> tests);
    Task TestResults(IReadOnlyList<TestResultUpdate> updates);
    Task ProjectRunFinished(ProjectRunOutcome outcome);
    Task Log(string level, string message);
    /// <summary>The complete current line states (replaces any earlier set). <see cref="FileMarkup.Path"/> is the absolute repo path.</summary>
    Task CoverageUpdated(IReadOnlyList<FileMarkup> files) => Task.CompletedTask;
    Task CoverageStatus(CoverageAvailability availability) => Task.CompletedTask;
}

public interface IBuildStep
{
    Task RestoreAsync(string mirrorSolutionPath, CancellationToken ct);

    Task<IReadOnlyDictionary<string, BuildOutcome>> BuildAsync(IReadOnlyList<ProjectInfo> inOrder,
        Func<ProjectInfo, Task>? onStarted, Func<BuildOutcome, Task>? onFinished, CancellationToken ct);
}

public interface ITestStep
{
    /// <summary>
    /// Runs one test project. The first <paramref name="onUpdates"/> batch must be the complete discovered list
    /// (Queued or Excluded); later batches are state changes. With <paramref name="coverage"/> the tests run class by
    /// class on one host started from the coverage run's instrumented copy.
    /// </summary>
    Task<ProjectRunOutcome> RunAsync(ProjectInfo project, Func<TestId, bool> shouldRun,
        Func<IReadOnlyList<TestResultUpdate>, Task> onUpdates, CancellationToken ct, ICoverageRun? coverage = null);
}
