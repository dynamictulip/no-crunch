using NoCrunch.Engine.Core;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Testing;

namespace NoCrunch.Engine.Coverage;

/// <summary>Runs one test again on the host already running and returns its final state.</summary>
public delegate Task<TestState> RunAlone(TestResultUpdate test, CancellationToken ct);

/// <summary>
/// Coverage collection for one run of one test project. None of its methods throw for a coverage problem: a failed
/// step is logged and leaves <see cref="Pass"/> null, so coverage can never fail the test run.
/// </summary>
public interface ICoverageRun : IAsyncDisposable
{
    /// <summary>The instrumented copy of the test application to launch.</summary>
    string TargetPath { get; }

    /// <summary>Called after each test class has run, with that class's results; takes a snapshot.</summary>
    Task ClassFinishedAsync(string testClass, IReadOnlyList<TestResultUpdate> results, CancellationToken ct);

    /// <summary>Called once every class has run: reruns each failing test alone through <paramref name="runAlone"/>.</summary>
    Task FinishedAsync(RunAlone runAlone, CancellationToken ct);

    /// <summary>The completed pass, or null if the run was cancelled, a step failed, or it has not finished.</summary>
    TestProjectPass? Pass { get; }
}

public interface ICoverageRunFactory
{
    Task<CoverageAvailability> CheckAsync(CancellationToken ct);

    /// <summary>
    /// Null when coverage is off or unavailable or the project can't be covered (not MTP, no target, nothing to
    /// instrument); the reason is logged.
    /// </summary>
    Task<ICoverageRun?> StartAsync(ProjectInfo project, ProjectGraph graph, CancellationToken ct);
}
