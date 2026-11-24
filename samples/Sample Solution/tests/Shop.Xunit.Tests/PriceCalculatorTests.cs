using Xunit;
using Shop.Core;

namespace Shop.Xunit.Tests;

public class PriceCalculatorTests
{
    [Fact]
    public void Total_multiplies() => Assert.Equal(10m, PriceCalculator.Total(2.5m, 4));

    [Fact]
    public void Total_zero_qty() => Assert.Equal(0m, PriceCalculator.Total(9.99m, 0));

    [Fact]
    public void Total_scales_with_quantity()
    {
        var one = PriceCalculator.Total(3m, 1);
        var three = PriceCalculator.Total(3m, 3);

        Assert.Equal(one * 3, three);
    }
}
