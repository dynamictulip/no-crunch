using Shop.Catalog;
using Xunit;

namespace Shop.Theory.Tests;

/// <summary>
/// Theories whose data xUnit can't serialise (a <see cref="Product"/>): discovery reports each theory once,
/// and the rows only appear when it runs. No Crunch nests those rows under the theory.
/// </summary>
public class RunTimeRowTheories
{
    public static TheoryData<string, Product> Products() => new()
    {
        { "cheap", new Product("ABC-0001", "Pencil", 0.5m) },
        { "mid", new Product("ABC-0002", "Notebook", 4.99m) },
        { "dear", new Product("ABC-0003", "Fountain pen", 120m) },
    };

    [Theory]
    [MemberData(nameof(Products))]
    public void Ten_percent_off_never_costs_more(string scenario, Product product) =>
        Assert.True(Discounts.Apply(product.Price, 10) <= product.Price, scenario);

    [Theory]
    [MemberData(nameof(Products))]
    public void Stock_value_is_price_times_quantity(string scenario, Product product)
    {
        var inventory = new Inventory();
        inventory.Add(product, 3);
        Assert.Equal(product.Price * 3, inventory.TotalValue());
        Thread.Sleep(scenario == "dear" ? 150 : 20);
    }
}
