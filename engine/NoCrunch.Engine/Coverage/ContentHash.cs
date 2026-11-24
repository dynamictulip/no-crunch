using System.Security.Cryptography;
using System.Text;

namespace NoCrunch.Engine.Coverage;

/// <summary>
/// Hash of a source file's text, so the plugin can tell whether an open document still matches the file coverage was
/// collected against: SHA-256 (lower-case hex) of the text with CRLF turned into LF. The plugin computes the same value.
/// </summary>
internal static class ContentHash
{
    public static string Of(string text) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(text.Replace("\r\n", "\n", StringComparison.Ordinal))));

    /// <summary>Reads the file as UTF-8 (a byte-order mark is dropped) and hashes its text.</summary>
    public static string OfFile(string path) => Of(File.ReadAllText(path, Encoding.UTF8));
}
