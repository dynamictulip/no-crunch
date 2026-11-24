using NoCrunch.Engine.Graph;

namespace NoCrunch.Engine.Coverage;

/// <param name="Production">Production projects whose DLL and PDB are in the output folder: they get instrumented.</param>
/// <param name="DllNames">File names of those DLLs, in the same order.</param>
/// <param name="Missing">Production projects in the closure with no usable DLL and PDB there: left out, so a pass over this test project is incomplete for them.</param>
internal sealed record CoverageTargetSet(IReadOnlyList<ProjectInfo> Production, IReadOnlyList<string> DllNames, IReadOnlyList<ProjectInfo> Missing);

/// <summary>Works out what to instrument for a test project, and makes the copy of its build output that gets instrumented.</summary>
internal static class CoverageTargets
{
    /// <summary>Non-test projects in the test project's transitive reference closure, by repo path.</summary>
    public static CoverageTargetSet Select(ProjectInfo testProject, ProjectGraph graph, string outputDir)
    {
        var production = new List<ProjectInfo>();
        var dlls = new List<string>();
        var missing = new List<ProjectInfo>();
        var candidates = graph.WithReferences([testProject.RepoRelativePath])
            .Where(p => p != testProject.RepoRelativePath)
            .Select(graph.Find).OfType<ProjectInfo>()
            .Where(p => !p.IsTestProject)
            .OrderBy(p => p.RepoRelativePath, StringComparer.Ordinal);
        foreach (var project in candidates)
        {
            var dll = project.TargetPath is null ? null : Path.GetFileName(project.TargetPath);
            if (dll is not null && File.Exists(Path.Combine(outputDir, dll)) && File.Exists(Path.Combine(outputDir, Path.ChangeExtension(dll, ".pdb"))))
            {
                production.Add(project);
                dlls.Add(dll);
            }
            else missing.Add(project);
        }
        return new CoverageTargetSet(production, dlls, missing);
    }

    /// <summary>Replaces <paramref name="destDir"/> with a copy of <paramref name="outputDir"/>; returns where the test application ended up.</summary>
    public static string CopyOutput(string outputDir, string destDir, string testAppPath)
    {
        if (Directory.Exists(destDir)) Directory.Delete(destDir, recursive: true);
        CopyDirectory(outputDir, destDir);
        return Path.Combine(destDir, Path.GetRelativePath(outputDir, testAppPath));
    }

    private static void CopyDirectory(string from, string to)
    {
        Directory.CreateDirectory(to);
        foreach (var file in Directory.EnumerateFiles(from)) File.Copy(file, Path.Combine(to, Path.GetFileName(file)), overwrite: true);
        foreach (var dir in Directory.EnumerateDirectories(from)) CopyDirectory(dir, Path.Combine(to, Path.GetFileName(dir)));
    }
}
