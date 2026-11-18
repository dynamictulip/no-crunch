using NoCrunch.Engine.Workspace;

namespace NoCrunch.Engine.Tests.Workspace;

[TestClass]
public class FileRetryTests
{
    private static readonly TimeSpan[] NoWait = [TimeSpan.Zero, TimeSpan.Zero, TimeSpan.Zero];

    [TestMethod]
    public void Retries_a_locked_file_until_it_is_free()
    {
        var calls = 0;
        var result = FileRetry.Run(() => ++calls < 3 ? throw new IOException("in use") : 42, NoWait);
        Assert.AreEqual(42, result);
        Assert.AreEqual(3, calls);
    }

    [TestMethod]
    public void Gives_up_and_rethrows_when_the_lock_lasts()
    {
        var calls = 0;
        Assert.ThrowsExactly<IOException>(() => FileRetry.Run<int>(() => { calls++; throw new IOException("in use"); }, NoWait));
        Assert.AreEqual(NoWait.Length + 1, calls);
    }

    [TestMethod]
    public void Does_not_retry_a_missing_file()
    {
        var calls = 0;
        Assert.ThrowsExactly<FileNotFoundException>(() => FileRetry.Run<int>(() => { calls++; throw new FileNotFoundException(); }, NoWait));
        Assert.AreEqual(1, calls);
    }
}
