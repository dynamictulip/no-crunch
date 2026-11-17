namespace NoCrunch.Engine.Testing;

/// <summary>
/// One exclusion. Project only = whole project; + Type = whole class; + Type + Method = one test (all its data rows);
/// Project + Uid = one test by UID (for frameworks that report no location).
/// <see cref="Include"/> turns it into an exception: the tests it matches run even though a broader rule excludes them.
/// </summary>
public sealed record ExclusionRule(string Project, string? Type, string? Method, string? Uid, bool Include = false)
{
    /// <summary>0 = project, 1 = class, 2 = single test. The most specific matching rule decides.</summary>
    public int Specificity => Uid is not null || Method is not null ? 2 : Type is not null ? 1 : 0;
}

public sealed class ExclusionSet(IEnumerable<ExclusionRule> rules)
{
    private readonly ExclusionRule[] _rules = rules.ToArray();

    public static ExclusionSet Empty { get; } = new([]);

    public IReadOnlyList<ExclusionRule> Rules => _rules;

    /// <summary>The most specific matching rule decides; if two equally specific rules disagree, exclusion wins.</summary>
    public bool IsExcluded(TestId id)
    {
        ExclusionRule? best = null;
        foreach (var r in _rules)
        {
            if (!Matches(r, id)) continue;
            if (best is null || r.Specificity > best.Specificity || (r.Specificity == best.Specificity && !r.Include)) best = r;
        }
        return best is { Include: false };
    }

    private static bool Matches(ExclusionRule rule, TestId id)
    {
        if (!string.Equals(rule.Project, id.Project, StringComparison.Ordinal)) return false;
        if (rule.Uid is not null) return string.Equals(rule.Uid, id.Uid, StringComparison.Ordinal);
        if (rule.Type is null) return true;
        if (!string.Equals(rule.Type, id.Type, StringComparison.Ordinal)) return false;
        return rule.Method is null || string.Equals(rule.Method, id.Method, StringComparison.Ordinal);
    }
}
