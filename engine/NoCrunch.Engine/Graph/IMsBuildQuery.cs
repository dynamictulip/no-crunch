namespace NoCrunch.Engine.Graph;

public interface IMsBuildQuery
{
    /// <summary>Absolute mirror paths of the projects in the solution.</summary>
    Task<IReadOnlyList<string>> ListSolutionProjectsAsync(string mirrorSolutionPath, CancellationToken ct);

    Task<ProjectInfo> QueryAsync(string mirrorProjectPath, CancellationToken ct);
}
