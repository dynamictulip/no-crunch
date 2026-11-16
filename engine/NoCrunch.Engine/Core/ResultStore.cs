using System.Text.Json;
using System.Text.Json.Serialization;
using NoCrunch.Engine.Testing;

namespace NoCrunch.Engine.Core;

/// <summary>Persists the latest result per test so results survive restarts.</summary>
public sealed class ResultStore(string path)
{
    private static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = false,
        Converters = { new JsonStringEnumConverter() },
    };

    public IReadOnlyDictionary<string, TestResultUpdate> Load()
    {
        try
        {
            if (!File.Exists(path)) return new Dictionary<string, TestResultUpdate>();
            var list = JsonSerializer.Deserialize<List<TestResultUpdate>>(File.ReadAllText(path), Json) ?? [];
            return list.GroupBy(r => r.Id.Key).ToDictionary(g => g.Key, g => g.Last());
        }
        catch (Exception e) when (e is JsonException or IOException)
        {
            return new Dictionary<string, TestResultUpdate>();
        }
    }

    public void Save(IEnumerable<TestResultUpdate> latest)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(latest.ToList(), Json));
        File.Move(tmp, path, overwrite: true);
    }
}
