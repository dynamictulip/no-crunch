using NoCrunch.Engine.Testing;

namespace NoCrunch.Engine.Tests.Testing;

[TestClass]
public class MtpTestRunnerKeyTests
{
    private const string P = "tests/T/T.csproj";

    private static TestResultUpdate Row(string uid, string? variant, string method = "M") =>
        new(new TestId(P, "T.C", method, uid, variant), variant ?? method, TestState.Discovered, null, null, null, null, null);

    [TestMethod]
    public void Rows_whose_keys_clash_get_their_uid_added_to_the_key()
    {
        var (tests, renamed) = MtpTestRunner.KeepKeysApart([Row("u1", "M(\"abc···\")"), Row("u2", "M(\"abc···\")")]);

        CollectionAssert.AreEqual(new[] { $"{P}|T.C|M|M(\"abc···\") [u1]", $"{P}|T.C|M|M(\"abc···\") [u2]" },
            tests.Select(t => t.Id.Key).ToArray());
        Assert.AreEqual("M(\"abc···\")", tests[0].DisplayName, "what the user sees doesn't change");
        Assert.AreEqual($"{P}|T.C|M|M(\"abc···\") [u2]", renamed["u2"].Id.Key, "results during the run use the same key");
        Assert.AreEqual($"{P}|T.C|M|M(\"abc···\")", renamed["u2"].OriginalKey);
    }

    [TestMethod]
    public void Keys_that_dont_clash_are_left_alone()
    {
        var (tests, renamed) = MtpTestRunner.KeepKeysApart([Row("u1", "M(1)"), Row("u2", "M(2)"), Row("u3", null, "N")]);

        CollectionAssert.AreEqual(new[] { $"{P}|T.C|M|M(1)", $"{P}|T.C|M|M(2)", $"{P}|T.C|N" }, tests.Select(t => t.Id.Key).ToArray());
        Assert.AreEqual(0, renamed.Count);
    }

    [TestMethod]
    public void Tests_with_no_variant_that_clash_are_kept_apart_too()
    {
        // e.g. overloads of one method name that a framework reports without arguments
        var (tests, _) = MtpTestRunner.KeepKeysApart([Row("u1", null), Row("u2", null)]);

        Assert.AreEqual(2, tests.Select(t => t.Id.Key).Distinct().Count());
    }
}
