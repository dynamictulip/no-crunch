using Shop.Core;

namespace Shop.NUnit.Tests;

[TestFixture]
public class PriceCalculatorTests
{
    [Test]
    public void Total_multiplies() => Assert.That(PriceCalculator.Total(2.5m, 4), Is.EqualTo(10m));

    [Test]
    public void Total_zero_qty() => Assert.That(PriceCalculator.Total(9.99m, 0), Is.EqualTo(0m));

    [TestCase(1, 3)]
    [TestCase(2, 6)]
    [TestCase(10, 30)]
    public void Total_scales_with_quantity(int qty, int expected) => Assert.That(PriceCalculator.Total(3m, qty), Is.EqualTo((decimal)expected));
}
