using System.Text.Json;

namespace NoCrunch.Engine.Coverage;

/// <summary>Persists the coverage passes so line dots survive a restart. Same atomic write and tolerant load as the result store.</summary>
public sealed class CoverageStore(string path)
{
    private sealed record LineSetDto(int[] Executable, int[] Hit);
    private sealed record ClassDto(string TestClass, Dictionary<string, LineSetDto> Files);
    private sealed record PassDto(string TestProject, List<ClassDto> Classes, Dictionary<string, int[]> FailingHit,
        string[] UnrefinedFailingClasses, Dictionary<string, string> FileHashes, string[] Assemblies, bool Stale);

    public IReadOnlyList<TestProjectPass> Load()
    {
        try
        {
            if (!File.Exists(path)) return [];
            var dtos = JsonSerializer.Deserialize<List<PassDto>>(File.ReadAllText(path)) ?? [];
            return dtos.Select(FromDto).ToList();
        }
        catch (Exception e) when (e is JsonException or IOException or NullReferenceException or ArgumentException)
        {
            return [];
        }
    }

    public void Save(IEnumerable<TestProjectPass> passes)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(passes.Select(ToDto).ToList()));
        File.Move(tmp, path, overwrite: true);
    }

    private static PassDto ToDto(TestProjectPass p) => new(
        p.TestProject,
        p.Classes.Select(c => new ClassDto(c.TestClass,
            c.Files.ToDictionary(f => f.Key, f => new LineSetDto(f.Value.Executable.Order().ToArray(), f.Value.Hit.Order().ToArray())))).ToList(),
        p.FailingHit.ToDictionary(f => f.Key, f => f.Value.Order().ToArray()),
        p.UnrefinedFailingClasses.Order().ToArray(),
        p.FileHashes.ToDictionary(f => f.Key, f => f.Value),
        p.Assemblies.Order().ToArray(),
        p.Stale);

    private static TestProjectPass FromDto(PassDto d) => new(
        d.TestProject,
        d.Classes.Select(c => new ClassCoverage(c.TestClass,
            c.Files.ToDictionary(f => f.Key, f => new LineSet(f.Value.Executable.ToHashSet(), f.Value.Hit.ToHashSet())))).ToList(),
        d.FailingHit.ToDictionary(f => f.Key, f => (IReadOnlySet<int>)f.Value.ToHashSet()),
        d.UnrefinedFailingClasses.ToHashSet(),
        d.FileHashes,
        d.Assemblies.ToHashSet(),
        d.Stale);
}
