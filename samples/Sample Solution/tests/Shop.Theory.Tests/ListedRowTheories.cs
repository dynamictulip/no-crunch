using Shop.Catalog;
using Xunit;

namespace Shop.Theory.Tests;

/// <summary>Theories whose data xUnit can serialise: each row is listed as its own test before anything runs.</summary>
public class ListedRowTheories
{
    [Theory]
    [InlineData(1, 0)]
    [InlineData(10, 5)]
    [InlineData(50, 10)]
    [InlineData(100, 15)]
    public void Bulk_percent_steps_up_with_quantity(int quantity, int expected) =>
        Assert.Equal(expected, Discounts.BulkPercent(quantity));

    public static TheoryData<string, bool> Skus() => new()
    {
        { "ABC-1234", true },
        { "abc-1234", false },
        { "ABC1234", false },
        { "ABC-12X4", false },
    };

    [Theory]
    [MemberData(nameof(Skus))]
    public void Sku_parser_accepts_only_well_formed_skus(string sku, bool valid) =>
        Assert.Equal(valid, SkuParser.TryParse(sku, out _, out _));

    // xUnit cuts argument values in display names to 50 characters plus "···", so these two rows get the same
    // display name. No Crunch must still keep them apart.
    [Theory]
    [InlineData("catalogue.imports.supplier_feeds.warehouse_north_0001_en")]
    [InlineData("catalogue.imports.supplier_feeds.warehouse_north_0002_en")]
    public void Rows_whose_long_arguments_look_the_same(string feed) =>
        Assert.StartsWith("catalogue.imports.", feed);
}
