namespace Shop.Core.Tests;

/// <summary>A test class with a test class nested inside it, to see how each framework names the inner class.</summary>
[TestClass]
public class Outer
{
    [TestMethod]
    public void Outer_test_one() { }

    [TestClass]
    public class Inner
    {
        [TestMethod]
        public void Inner_test_one() { }
    }
}
