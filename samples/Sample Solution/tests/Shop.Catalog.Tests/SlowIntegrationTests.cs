using Shop.Catalog;

namespace Shop.Catalog.Tests;

/// <summary>Deliberately slow tests (simulated I/O) to show longer durations in No Crunch.</summary>
[TestClass]
public class SlowIntegrationTests
{
    private static async Task<Inventory> LoadWarehouseAsync(int delayMs)
    {
        await Task.Delay(delayMs);
        var inv = new Inventory();
        inv.Add(new Product("WHS-0001", "Crate", 12m), 10);
        return inv;
    }

    [TestMethod]
    public async Task Loads_warehouse_snapshot() => Assert.AreEqual(10, (await LoadWarehouseAsync(250)).QuantityOf("WHS-0001"));

    [TestMethod]
    public async Task Syncs_stock_from_supplier_feed() => Assert.IsTrue((await LoadWarehouseAsync(500)).InStock("WHS-0001"));

    [TestMethod]
    public async Task Reconciles_nightly_stock_take() => Assert.AreEqual(120m, (await LoadWarehouseAsync(800)).TotalValue());

    [TestMethod]
    public async Task Rebuilds_search_index() => Assert.AreEqual(1, (await LoadWarehouseAsync(1_200)).DistinctProducts);

    [TestMethod]
    public async Task Exports_full_catalogue() => Assert.IsNotNull(await LoadWarehouseAsync(1_500));
}
