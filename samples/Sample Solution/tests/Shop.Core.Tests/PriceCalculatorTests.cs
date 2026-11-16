using Shop.Core;

namespace Shop.Core.Tests;

[TestClass]
public class PriceCalculatorTests
{
    [TestMethod]
    public void Total_multiplies() => Assert.AreEqual(10m, PriceCalculator.Total(2.5m, 4));

    [TestMethod]
    public void Total_zero_qty() => Assert.AreEqual(0m, PriceCalculator.Total(9.99m, 0));
}
