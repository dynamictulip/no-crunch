using Xunit;

namespace Shop.Theory.Tests;

/// <summary>A test class with a test class nested inside it, to see how each framework names the inner class.</summary>
public class Outer
{
    [Fact]
    public void Outer_test_one() { }

    public class Inner
    {
        [Fact]
        public void Inner_test_one() { }
    }
}
