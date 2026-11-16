using Shop.Core;

namespace Shop.E2E.Tests;

[TestClass]
public class CheckoutFlowTests
{
    [TestMethod]
    public void Checkout_flow() => Assert.AreEqual(3m, PriceCalculator.Total(1m, 3));
}
