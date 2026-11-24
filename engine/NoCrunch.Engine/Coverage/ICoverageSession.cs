namespace NoCrunch.Engine.Coverage;

/// <summary>One collector session: instrument assemblies against it, then read what ran since the last snapshot.</summary>
internal interface ICoverageSession : IAsyncDisposable
{
    /// <summary>Instruments the DLL in place (always a copy, never a build output) against this session.</summary>
    Task InstrumentAsync(string dllPath, CancellationToken ct);

    /// <summary>Takes a snapshot and resets the collector, merges it to cobertura and returns the XML text (the temp files are deleted).</summary>
    Task<string> SnapshotCoberturaAsync(CancellationToken ct);
}

internal interface ICoverageTool
{
    /// <param name="workDir">A directory the session may create and delete (snapshot files, the collector's final report).</param>
    Task<ICoverageSession> StartSessionAsync(string workDir, CancellationToken ct);
}
