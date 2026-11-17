namespace NoCrunch.Engine.Workspace;

/// <summary>
/// Owns the isolated copy of the repo that builds and test runs use.
/// The MVP has one implementation (a shared mirror); per-project workspaces can be added later.
/// </summary>
public interface IWorkspaceStrategy
{
    Task InitializeAsync(CancellationToken ct);

    /// <summary>Applies the given repo paths (files or directories) to the workspace.
    /// Returns repo-relative paths whose content actually changed in the workspace. Each change is also added to
    /// <paramref name="progress"/> as soon as it is made, so a caller still knows what changed if this throws or is cancelled.</summary>
    Task<IReadOnlyCollection<string>> SyncAsync(IReadOnlyCollection<string> changedRepoPaths, CancellationToken ct, ISet<string>? progress = null);

    /// <summary>Full comparison of repo and workspace (size + mtime, then content). Returns changed repo-relative paths;
    /// reports each change to <paramref name="progress"/> as it is made.</summary>
    Task<IReadOnlyCollection<string>> ReconcileAsync(CancellationToken ct, ISet<string>? progress = null);

    string MirrorPathOf(string repoRelativePath);

    /// <summary>True if a change to this repo path matters: it's inside the repo and would be mirrored
    /// (not .git/.idea/bin/obj, not a git-ignored directory, not No Crunch's own files).</summary>
    bool Tracks(string repoPath);
}
