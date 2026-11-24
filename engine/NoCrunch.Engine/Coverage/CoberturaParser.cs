using System.Xml.Linq;

namespace NoCrunch.Engine.Coverage;

/// <summary>The executable lines of a source file and the ones that were hit (1-based; <see cref="Hit"/> is a subset).</summary>
public sealed record LineSet(IReadOnlySet<int> Executable, IReadOnlySet<int> Hit);

/// <summary>Reads a cobertura report (as written by <c>dotnet-coverage merge -f cobertura</c>) into line sets per file.</summary>
internal static class CoberturaParser
{
    /// <summary>Keyed by absolute file path as the report names it; relative names resolve against the first source.</summary>
    public static IReadOnlyDictionary<string, LineSet> Parse(string coberturaXml)
    {
        var result = new Dictionary<string, (HashSet<int> Executable, HashSet<int> Hit)>(StringComparer.Ordinal);
        if (string.IsNullOrWhiteSpace(coberturaXml)) return new Dictionary<string, LineSet>();
        var doc = XDocument.Parse(coberturaXml);
        var source = doc.Descendants("source").Select(s => s.Value).FirstOrDefault(s => !string.IsNullOrWhiteSpace(s));
        foreach (var cls in doc.Descendants("class"))
        {
            var file = (string?)cls.Attribute("filename");
            if (string.IsNullOrEmpty(file)) continue;
            if (!Path.IsPathRooted(file) && source is not null) file = Path.Combine(source, file);
            if (!result.TryGetValue(file, out var sets)) result[file] = sets = ([], []);
            foreach (var line in cls.Descendants("line"))
            {
                if (!int.TryParse((string?)line.Attribute("number"), out var number)) continue;
                sets.Executable.Add(number);
                if (int.TryParse((string?)line.Attribute("hits"), out var hits) && hits > 0) sets.Hit.Add(number);
            }
        }
        return result.ToDictionary(kv => kv.Key, kv => new LineSet(kv.Value.Executable, kv.Value.Hit), StringComparer.Ordinal);
    }
}
