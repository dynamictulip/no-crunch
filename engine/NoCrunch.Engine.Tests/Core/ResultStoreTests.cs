using NoCrunch.Engine.Core;
using NoCrunch.Engine.Testing;
using NoCrunch.Engine.Tests.Layout;

namespace NoCrunch.Engine.Tests.Core;

[TestClass]
public class ResultStoreTests
{
    [TestMethod]
    public void ResultStore_roundtrips()
    {
        var path = Path.Combine(TempDir.Create(), "sub", "results.json");
        var store = new ResultStore(path);
        var r = new TestResultUpdate(new TestId("p.csproj", "T", "M", "u", "M(1)"), "M(1)", TestState.Failed, 12.5, "boom", "at x", "/r/a.cs", 7);

        store.Save([r]);
        var loaded = new ResultStore(path).Load();

        Assert.AreEqual(r, loaded[r.Id.Key]);
        Assert.AreEqual("M(1)", loaded[r.Id.Key].Id.Variant);
    }

    [TestMethod]
    public void Missing_or_corrupt_file_loads_empty()
    {
        var dir = TempDir.Create();
        Assert.AreEqual(0, new ResultStore(Path.Combine(dir, "none.json")).Load().Count);
        File.WriteAllText(Path.Combine(dir, "bad.json"), "{not json");
        Assert.AreEqual(0, new ResultStore(Path.Combine(dir, "bad.json")).Load().Count);
    }
}
