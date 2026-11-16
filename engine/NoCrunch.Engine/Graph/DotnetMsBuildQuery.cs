using System.Text.Json;
using NoCrunch.Engine.Build;
using NoCrunch.Engine.Layout;
using NoCrunch.Engine.Workspace;

namespace NoCrunch.Engine.Graph;

/// <summary>Reads project data via `dotnet sln list` and `dotnet msbuild -getProperty/-getItem` (JSON output).</summary>
public sealed class DotnetMsBuildQuery(IProcessRunner runner, PathMap map, string dotnetPath) : IMsBuildQuery
{
    private static readonly string[] ItemTypes = ["ProjectReference", "Compile", "None", "Content", "EmbeddedResource", "AdditionalFiles"];
    private static readonly string[] Properties = ["IsTestingPlatformApplication", "IsTestProject", "TargetPath", "TargetFramework", "TargetFrameworks"];

    public async Task<IReadOnlyList<string>> ListSolutionProjectsAsync(string mirrorSolutionPath, CancellationToken ct)
    {
        var dir = Path.GetDirectoryName(mirrorSolutionPath)!;
        var result = await runner.RunAsync(dotnetPath, ["sln", mirrorSolutionPath, "list"], dir, ct);
        if (result.ExitCode != 0) throw new InvalidOperationException($"dotnet sln list failed: {result.StdErr}{result.StdOut}");
        return result.StdOut.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Where(l => l.EndsWith("proj", StringComparison.OrdinalIgnoreCase))
            .Select(l => Path.GetFullPath(Path.Combine(dir, l.Replace('\\', '/'))))
            .ToList();
    }

    public async Task<ProjectInfo> QueryAsync(string mirrorProjectPath, CancellationToken ct)
    {
        var json = await RunQueryAsync(mirrorProjectPath, null, ct);
        var tfms = Prop(json, "TargetFrameworks");
        if (string.IsNullOrEmpty(Prop(json, "TargetFramework")) && !string.IsNullOrEmpty(tfms))
            json = await RunQueryAsync(mirrorProjectPath, tfms.Split(';', StringSplitOptions.RemoveEmptyEntries)[0], ct);

        var repoRel = map.RepoRelative(map.ToRepo(mirrorProjectPath));
        var isMtp = IsTrue(Prop(json, "IsTestingPlatformApplication"));
        var isTest = isMtp || IsTrue(Prop(json, "IsTestProject"));
        var targetPath = Prop(json, "TargetPath");

        var refs = Items(json, "ProjectReference").Select(ToRepoRelative).OfType<string>().Distinct().ToList();
        var items = ItemTypes.Skip(1).SelectMany(t => Items(json, t)).Select(ToRepoRelative).OfType<string>()
            .Where(r => !FileFilter.IsExcluded(r)).Distinct().ToList();

        return new ProjectInfo(repoRel, Path.GetFileNameWithoutExtension(mirrorProjectPath), mirrorProjectPath,
            isTest, isMtp, string.IsNullOrEmpty(targetPath) ? null : targetPath, refs, items);
    }

    private async Task<JsonElement> RunQueryAsync(string project, string? tfm, CancellationToken ct)
    {
        var args = new List<string> { "msbuild", project, "-nologo" };
        args.AddRange(Properties.Select(p => "-getProperty:" + p));
        args.AddRange(ItemTypes.Select(i => "-getItem:" + i));
        if (tfm is not null) args.Add("-p:TargetFramework=" + tfm);
        var result = await runner.RunAsync(dotnetPath, args, Path.GetDirectoryName(project)!, ct);
        if (result.ExitCode != 0) throw new InvalidOperationException($"MSBuild query failed for {project}: {result.StdOut}{result.StdErr}");
        var start = result.StdOut.IndexOf('{');
        return JsonDocument.Parse(result.StdOut[start..]).RootElement.Clone();
    }

    /// <summary>Maps an absolute mirror path to repo-relative, or null if it lies outside the mirror (e.g. SDK files).</summary>
    private string? ToRepoRelative(string fullPath)
    {
        var mirrorRoot = map.MirrorRoot + Path.DirectorySeparatorChar;
        return fullPath.StartsWith(mirrorRoot, StringComparison.Ordinal) ? map.RepoRelative(map.ToRepo(fullPath)) : null;
    }

    private static string Prop(JsonElement json, string name) =>
        json.TryGetProperty("Properties", out var props) && props.TryGetProperty(name, out var v) ? v.GetString() ?? "" : "";

    private static IEnumerable<string> Items(JsonElement json, string type) =>
        json.TryGetProperty("Items", out var items) && items.TryGetProperty(type, out var arr)
            ? arr.EnumerateArray().Select(i => i.GetProperty("FullPath").GetString()!).Where(p => p is not null)
            : [];

    private static bool IsTrue(string value) => string.Equals(value, "true", StringComparison.OrdinalIgnoreCase);
}
