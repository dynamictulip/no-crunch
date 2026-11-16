namespace Shop.Catalog;

public static class Discounts
{
    public static decimal Apply(decimal price, int percent)
    {
        if (percent is < 0 or > 100) throw new ArgumentOutOfRangeException(nameof(percent));
        return Math.Round(price * (100 - percent) / 100m, 2, MidpointRounding.AwayFromZero);
    }

    public static int BulkPercent(int quantity) => quantity switch
    {
        >= 100 => 15,
        >= 50 => 10,
        >= 10 => 5,
        _ => 0,
    };
}
