namespace Shop.Catalog;

public sealed class Inventory
{
    private readonly Dictionary<string, (Product Product, int Quantity)> _items = new(StringComparer.OrdinalIgnoreCase);

    public int DistinctProducts => _items.Count;

    public void Add(Product product, int quantity)
    {
        if (quantity <= 0) throw new ArgumentOutOfRangeException(nameof(quantity));
        _items[product.Sku] = _items.TryGetValue(product.Sku, out var existing)
            ? (product, existing.Quantity + quantity)
            : (product, quantity);
    }

    public void Remove(string sku, int quantity)
    {
        if (!_items.TryGetValue(sku, out var existing)) throw new KeyNotFoundException(sku);
        if (quantity > existing.Quantity) throw new InvalidOperationException($"Only {existing.Quantity} of {sku} in stock");
        var left = existing.Quantity - quantity;
        if (left == 0) _items.Remove(sku);
        else _items[sku] = (existing.Product, left);
    }

    public int QuantityOf(string sku) => _items.TryGetValue(sku, out var e) ? e.Quantity : 0;

    public bool InStock(string sku) => QuantityOf(sku) > 0;

    public decimal TotalValue() => _items.Values.Sum(e => e.Product.Price * e.Quantity);
}
