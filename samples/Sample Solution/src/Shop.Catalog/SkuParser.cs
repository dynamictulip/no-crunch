namespace Shop.Catalog;

/// <summary>SKUs look like "ABC-1234": a 3-letter category, a dash, and a 4-digit number.</summary>
public static class SkuParser
{
    public static bool TryParse(string? sku, out string category, out int number)
    {
        category = "";
        number = 0;
        if (sku is null || sku.Length != 8 || sku[3] != '-') return false;
        var cat = sku[..3];
        if (!cat.All(char.IsAsciiLetterUpper)) return false;
        if (!int.TryParse(sku[4..], System.Globalization.NumberStyles.None, null, out number)) return false;
        category = cat;
        return true;
    }
}
