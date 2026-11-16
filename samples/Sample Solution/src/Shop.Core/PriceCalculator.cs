namespace Shop.Core;

public static class PriceCalculator
{
    public static decimal Total(decimal unit, int qty) => unit * qty;
}
