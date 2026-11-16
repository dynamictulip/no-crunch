using NoCrunch.Engine.Testing;

namespace NoCrunch.Engine.Tests.Testing;

[TestClass]
public class ExclusionSetTests
{
    private const string E2E = "tests/E2E/E2E.csproj";
    private const string Unit = "tests/Unit/Unit.csproj";

    private static TestId T(string project, string type, string method, string uid = "u1") => new(project, type, method, uid);

    [TestMethod]
    public void Project_rule_excludes_all_in_project()
    {
        var set = new ExclusionSet([new ExclusionRule(E2E, null, null, null)]);
        Assert.IsTrue(set.IsExcluded(T(E2E, "A.Flow", "Checkout")));
        Assert.IsTrue(set.IsExcluded(new TestId(E2E, null, null, "x")));
        Assert.IsFalse(set.IsExcluded(T(Unit, "A.Flow", "Checkout")));
    }

    [TestMethod]
    public void Type_rule_excludes_class_only()
    {
        var set = new ExclusionSet([new ExclusionRule(Unit, "A.Slow", null, null)]);
        Assert.IsTrue(set.IsExcluded(T(Unit, "A.Slow", "One")));
        Assert.IsFalse(set.IsExcluded(T(Unit, "A.Fast", "One")));
        Assert.IsFalse(set.IsExcluded(T(Unit, "A.SlowTests", "One")));
    }

    [TestMethod]
    public void Method_rule_excludes_single_test()
    {
        var set = new ExclusionSet([new ExclusionRule(Unit, "A.C", "One", null)]);
        Assert.IsTrue(set.IsExcluded(T(Unit, "A.C", "One")));
        Assert.IsTrue(set.IsExcluded(new TestId(Unit, "A.C", "One", "u9", Variant: "One (1,2)")));
        Assert.IsFalse(set.IsExcluded(T(Unit, "A.C", "Two")));
    }

    [TestMethod]
    public void Uid_fallback_matches_when_location_missing()
    {
        var set = new ExclusionSet([new ExclusionRule(Unit, null, null, "uid-42")]);
        Assert.IsTrue(set.IsExcluded(new TestId(Unit, null, null, "uid-42")));
        Assert.IsFalse(set.IsExcluded(new TestId(Unit, null, null, "uid-43")));
        Assert.IsFalse(set.IsExcluded(new TestId(E2E, null, null, "uid-42")));
    }

    [TestMethod]
    public void Rule_survives_new_uid_when_location_present()
    {
        var set = new ExclusionSet([new ExclusionRule(Unit, "A.C", "One", null)]);
        Assert.IsTrue(set.IsExcluded(T(Unit, "A.C", "One", uid: "after-rebuild")));
    }

    [TestMethod]
    public void Empty_excludes_nothing() => Assert.IsFalse(ExclusionSet.Empty.IsExcluded(T(Unit, "A", "B")));
}
