using NoCrunch.Engine.Build;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Testing;

namespace NoCrunch.Engine.Core;

public enum EngineState { Idle, Syncing, Building, Testing, Paused }

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
    /// (Queued or Excluded); later batches are state changes.
    /// </summary>
    Task<ProjectRunOutcome> RunAsync(ProjectInfo project, Func<TestId, bool> shouldRun,
        Func<IReadOnlyList<TestResultUpdate>, Task> onUpdates, CancellationToken ct);
}
