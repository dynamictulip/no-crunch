namespace Shop.Catalog;

public sealed record Product(string Sku, string Name, decimal Price)
{
    public Product WithPrice(decimal price) =>
        price < 0 ? throw new ArgumentOutOfRangeException(nameof(price)) : this with { Price = price };
}
