namespace Shop.Other.Tests;

/// <summary>Tests of varying lengths.</summary>
[TestClass]
public class TimingTests
{
    [TestMethod]
    public void Instant() => Assert.AreEqual(4, 2 + 2);

    [TestMethod]
    [DataRow(25)]
    [DataRow(75)]
    [DataRow(150)]
    [DataRow(300)]
    [DataRow(600)]
    public async Task Waits(int ms)
    {
        await Task.Delay(ms);
        Assert.IsTrue(ms > 0);
    }

    [TestMethod]
    public void Busy_cpu_work()
    {
        var primes = Enumerable.Range(2, 60_000).Count(n => Enumerable.Range(2, (int)Math.Sqrt(n) - 1).All(d => n % d != 0));
        Assert.AreEqual(6057, primes);
    }

    [TestMethod]
    public void String_building() => Assert.AreEqual(10_000, string.Concat(Enumerable.Repeat("x", 10_000)).Length);
}
