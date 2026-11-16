namespace NoCrunch.Engine.Tests;

[TestClass]
public class SmokeTests
{
    [TestMethod]
    public void Sample_solution_copy_contains_solution_file()
    {
        var root = TestSupport.SampleSolution.CopyToTemp();
        Assert.IsTrue(File.Exists(Path.Combine(root, "Sample.slnx")));
        Assert.IsTrue(Directory.Exists(Path.Combine(root, ".git")));
        StringAssert.Contains(root, " ");
    }
}
