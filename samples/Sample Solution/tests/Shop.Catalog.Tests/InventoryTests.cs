using Shop.Catalog;

namespace Shop.Catalog.Tests;

[TestClass]
public class InventoryTests
{
    private static readonly Product Mug = new("KIT-0001", "Mug", 7.50m);
    private static readonly Product Pan = new("KIT-0002", "Pan", 24.00m);

    [TestMethod]
    public void New_inventory_is_empty() => Assert.AreEqual(0, new Inventory().DistinctProducts);

    [TestMethod]
    public void Add_increases_quantity()
    {
        var inv = new Inventory();
        inv.Add(Mug, 3);
        inv.Add(Mug, 2);
        Assert.AreEqual(5, inv.QuantityOf("KIT-0001"));
    }

    [TestMethod]
    public void Sku_lookup_ignores_case()
    {
        var inv = new Inventory();
        inv.Add(Mug, 1);
        Assert.IsTrue(inv.InStock("kit-0001"));
    }

    [TestMethod]
    public void Add_rejects_zero_quantity() => Assert.ThrowsExactly<ArgumentOutOfRangeException>(() => new Inventory().Add(Mug, 0));

    [TestMethod]
    public void Remove_decreases_quantity()
    {
        var inv = new Inventory();
        inv.Add(Pan, 4);
        inv.Remove("KIT-0002", 1);
        Assert.AreEqual(3, inv.QuantityOf("KIT-0002"));
    }

    [TestMethod]
    public void Removing_everything_drops_the_product()
    {
        var inv = new Inventory();
        inv.Add(Pan, 2);
        inv.Remove("KIT-0002", 2);
        Assert.AreEqual(0, inv.DistinctProducts);
    }

    [TestMethod]
    public void Remove_more_than_stock_throws()
    {
        var inv = new Inventory();
        inv.Add(Pan, 1);
        Assert.ThrowsExactly<InvalidOperationException>(() => inv.Remove("KIT-0002", 2));
    }

    [TestMethod]
    public void Remove_unknown_sku_throws() => Assert.ThrowsExactly<KeyNotFoundException>(() => new Inventory().Remove("NOP-0000", 1));

    [TestMethod]
    public void TotalValue_sums_price_times_quantity()
    {
        var inv = new Inventory();
        inv.Add(Mug, 2);
        inv.Add(Pan, 1);
        Assert.AreEqual(39.00m, inv.TotalValue());
    }

    [TestMethod]
    public async Task Large_inventory_totals_correctly()
    {
        var inv = new Inventory();
        for (var i = 0; i < 5_000; i++) inv.Add(new Product($"BLK-{i % 10_000:0000}", "Bulk", 1m), 1);
        await Task.Delay(150);
        Assert.AreEqual(5_000m, inv.TotalValue());
    }
}
