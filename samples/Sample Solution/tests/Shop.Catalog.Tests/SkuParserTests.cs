using Shop.Catalog;

namespace Shop.Catalog.Tests;

[TestClass]
public class SkuParserTests
{
    [TestMethod]
    [DataRow("KIT-0001", "KIT", 1)]
    [DataRow("ABC-1234", "ABC", 1234)]
    [DataRow("ZZZ-9999", "ZZZ", 9999)]
    public void Parses_valid_skus(string sku, string category, int number)
    {
        Assert.IsTrue(SkuParser.TryParse(sku, out var c, out var n));
        Assert.AreEqual(category, c);
        Assert.AreEqual(number, n);
    }

    [TestMethod]
    [DataRow(null)]
    [DataRow("")]
    [DataRow("KIT0001")]
    [DataRow("kit-0001")]
    [DataRow("KI-00001")]
    [DataRow("KIT-00A1")]
    [DataRow("KIT--001")]
    public void Rejects_invalid_skus(string? sku) => Assert.IsFalse(SkuParser.TryParse(sku, out _, out _));
}
