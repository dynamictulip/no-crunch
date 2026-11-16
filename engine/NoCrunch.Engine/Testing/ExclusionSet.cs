namespace NoCrunch.Engine.Testing;

/// <summary>
/// One exclusion. Project only = whole project; + Type = whole class; + Type + Method = one test (all its data rows);
/// Project + Uid = one test by UID (for frameworks that report no location).
/// </summary>
public sealed record ExclusionRule(string Project, string? Type, string? Method, string? Uid);

public sealed class ExclusionSet(IEnumerable<ExclusionRule> rules)
{
    private readonly ExclusionRule[] _rules = rules.ToArray();

    public static ExclusionSet Empty { get; } = new([]);

    public IReadOnlyList<ExclusionRule> Rules => _rules;

    public bool IsExcluded(TestId id) => _rules.Any(r => Matches(r, id));

    private static bool Matches(ExclusionRule rule, TestId id)
    {
        if (!string.Equals(rule.Project, id.Project, StringComparison.Ordinal)) return false;
        if (rule.Uid is not null) return string.Equals(rule.Uid, id.Uid, StringComparison.Ordinal);
        if (rule.Type is null) return true;
        if (!string.Equals(rule.Type, id.Type, StringComparison.Ordinal)) return false;
        return rule.Method is null || string.Equals(rule.Method, id.Method, StringComparison.Ordinal);
    }
}
