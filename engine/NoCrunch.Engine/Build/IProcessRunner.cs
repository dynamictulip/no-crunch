namespace NoCrunch.Engine.Build;

public sealed record ProcessResult(int ExitCode, string StdOut, string StdErr);

public interface IProcessRunner
{
    /// <summary>Runs a process to completion. Cancellation kills the whole process tree.</summary>
    Task<ProcessResult> RunAsync(string file, IReadOnlyList<string> args, string workingDir, CancellationToken ct);
}
