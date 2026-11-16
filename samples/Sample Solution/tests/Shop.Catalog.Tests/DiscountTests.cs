using Shop.Catalog;

namespace Shop.Catalog.Tests;

[TestClass]
public class DiscountTests
{
    [TestMethod]
    [DataRow(100.00, 0, 100.00)]
    [DataRow(100.00, 10, 90.00)]
    [DataRow(100.00, 100, 0.00)]
    [DataRow(19.99, 15, 16.99)]
    [DataRow(0.05, 50, 0.03)]
    [DataRow(250.00, 33, 167.50)]
    public void Apply_takes_percentage_off(double price, int percent, double expected) =>
        Assert.AreEqual((decimal)expected, Discounts.Apply((decimal)price, percent));

    [TestMethod]
    [DataRow(-1)]
    [DataRow(101)]
    public void Apply_rejects_out_of_range_percent(int percent) =>
        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() => Discounts.Apply(10m, percent));

    [TestMethod]
    [DataRow(1, 0)]
    [DataRow(9, 0)]
    [DataRow(10, 5)]
    [DataRow(49, 5)]
    [DataRow(50, 10)]
    [DataRow(99, 10)]
    [DataRow(100, 15)]
    [DataRow(10_000, 15)]
    public void BulkPercent_steps_with_quantity(int quantity, int expected) =>
        Assert.AreEqual(expected, Discounts.BulkPercent(quantity));

    [TestMethod]
    public async Task Price_recalculation_for_whole_catalogue()
    {
        await Task.Delay(400);
        var total = Enumerable.Range(1, 1_000).Sum(i => Discounts.Apply(i, Discounts.BulkPercent(i)));
        Assert.IsTrue(total > 0);
    }
}
