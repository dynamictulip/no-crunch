namespace NoCrunch.Engine.Testing;

/// <summary>
/// Stable identity of a test. <see cref="Project"/> is repo-relative.
/// Identity is (Project, Type, Method, Variant) when the framework reports a location; otherwise (Project, Uid).
/// <see cref="Variant"/> distinguishes data-driven rows that share a method (it is the display name when that differs from the method).
/// </summary>
public sealed record TestId(string Project, string? Type, string? Method, string Uid, string? Variant = null)
{
    public bool HasLocation => Type is not null && Method is not null;

    public string Key => HasLocation
        ? Variant is null ? $"{Project}|{Type}|{Method}" : $"{Project}|{Type}|{Method}|{Variant}"
        : $"{Project}|uid:{Uid}";

    public bool Equals(TestId? other) => other is not null && Key == other.Key;

    public override int GetHashCode() => Key.GetHashCode(StringComparison.Ordinal);
}

public enum TestState { Discovered, Queued, Running, Passed, Failed, Skipped, Excluded, Stale }

/// <summary>Whether a result came from an automatic (continuous) run or a run the user asked for.</summary>
public enum RunKind { Auto, Manual }

/// <summary>A test's latest state. <see cref="FilePath"/> and <see cref="StackTrace"/> use repo paths, never mirror paths.</summary>
public sealed record TestResultUpdate(
    TestId Id,
    string DisplayName,
    TestState State,
    double? DurationMs,
    string? Message,
    string? StackTrace,
    string? FilePath,
    int? Line,
    RunKind RunKind = RunKind.Auto);

public enum ProjectRunStatus { Completed, Cancelled, Crashed, UnsupportedMtpVersion, UnsupportedNotMtp }

public sealed record ProjectRunOutcome(string Project, ProjectRunStatus Status, string? Error);
