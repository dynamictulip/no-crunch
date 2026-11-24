using System.Text;
using NoCrunch.Engine.Coverage;
using NoCrunch.Engine.Tests.Layout;

namespace NoCrunch.Engine.Tests.Coverage;

[TestClass]
public class ContentHashTests
{
    private const string AbNewline = "911169ddaaf146aff539f58c26c489af3b892dff0fe283c1c264c65ae5aa59a2";

    [TestMethod]
    public void Of_a_b_newline_is_the_known_digest() => Assert.AreEqual(AbNewline, ContentHash.Of("a\nb\n"));

    [TestMethod]
    public void Crlf_and_lf_hash_the_same() => Assert.AreEqual(AbNewline, ContentHash.Of("a\r\nb\n"));

    [TestMethod]
    public void OfFile_ignores_a_utf8_bom()
    {
        var path = Path.Combine(TempDir.Create(), "bom.cs");
        File.WriteAllBytes(path, new UTF8Encoding(true).GetPreamble().Concat(Encoding.UTF8.GetBytes("a\r\nb\n")).ToArray());

        Assert.AreEqual(AbNewline, ContentHash.OfFile(path));
    }
}
