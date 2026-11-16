using Shop.Catalog;

namespace Shop.Catalog.Tests;

[TestClass]
public class ProductTests
{
    private static readonly Product Mug = new("KIT-0001", "Mug", 7.50m);

    [TestMethod]
    public void WithPrice_returns_copy_with_new_price() => Assert.AreEqual(9m, Mug.WithPrice(9m).Price);

    [TestMethod]
    public void WithPrice_keeps_the_original_unchanged()
    {
        _ = Mug.WithPrice(1m);
        Assert.AreEqual(7.50m, Mug.Price);
    }

    [TestMethod]
    public void WithPrice_allows_zero() => Assert.AreEqual(0m, Mug.WithPrice(0m).Price);

    [TestMethod]
    public void WithPrice_rejects_negative() => Assert.ThrowsExactly<ArgumentOutOfRangeException>(() => Mug.WithPrice(-1m));

    [TestMethod]
    public void Products_with_same_values_are_equal() => Assert.AreEqual(new Product("KIT-0001", "Mug", 7.50m), Mug);
}
