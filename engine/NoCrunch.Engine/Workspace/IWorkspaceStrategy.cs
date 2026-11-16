namespace NoCrunch.Engine.Workspace;

/// <summary>
/// Owns the isolated copy of the repo that builds and test runs use.
/// The MVP has one implementation (a shared mirror); per-project workspaces can be added later.
/// </summary>
public interface IWorkspaceStrategy
{
    Task InitializeAsync(CancellationToken ct);

    /// <summary>Applies the given repo paths (files or directories) to the workspace.
    /// Returns repo-relative paths whose content actually changed in the workspace.</summary>
    Task<IReadOnlyCollection<string>> SyncAsync(IReadOnlyCollection<string> changedRepoPaths, CancellationToken ct);

    /// <summary>Full comparison of repo and workspace (size + mtime, then content). Returns changed repo-relative paths.</summary>
    Task<IReadOnlyCollection<string>> ReconcileAsync(CancellationToken ct);

    string MirrorPathOf(string repoRelativePath);
}
