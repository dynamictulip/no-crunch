namespace NoCrunch.Engine.Graph;

/// <summary>
/// A project in the solution. Paths are repo-relative with '/' separators, except
/// <see cref="MirrorProjectPath"/> and <see cref="TargetPath"/> which are absolute mirror paths.
/// </summary>
public sealed record ProjectInfo(
    string RepoRelativePath,
    string Name,
    string MirrorProjectPath,
    bool IsTestProject,
    bool IsMtp,
    string? TargetPath,
    IReadOnlyList<string> ProjectReferences,
    IReadOnlyList<string> ItemFiles)
{
    public string Directory => RepoRelativePath.Contains('/') ? RepoRelativePath[..RepoRelativePath.LastIndexOf('/')] : "";
}
