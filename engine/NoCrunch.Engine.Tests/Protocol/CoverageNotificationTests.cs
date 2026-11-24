using System.Text.Json;
using System.Text.Json.Nodes;
using Nerdbank.Streams;
using NoCrunch.Engine.Core;
using NoCrunch.Engine.Coverage;
using NoCrunch.Engine.Protocol;
using StreamJsonRpc;

namespace NoCrunch.Engine.Tests.Protocol;

[TestClass]
public class CoverageNotificationTests
{
    private sealed class Client
    {
        public TaskCompletionSource<JsonObject> Updated { get; } = new();
        public TaskCompletionSource<JsonObject> Status { get; } = new();

        [JsonRpcMethod("coverageUpdated", UseSingleObjectParameterDeserialization = true)]
        public void CoverageUpdated(JsonObject args) => Updated.TrySetResult(args);

        [JsonRpcMethod("coverageStatus", UseSingleObjectParameterDeserialization = true)]
        public void CoverageStatus(JsonObject args) => Status.TrySetResult(args);
    }

    private static (IEngineEvents server, Client client, IDisposable cleanup) Connect()
    {
        var (serverStream, clientStream) = FullDuplexStream.CreatePair();
        var server = new EngineServer();
        var serverRpc = new JsonRpc(new HeaderDelimitedMessageHandler(serverStream, EngineServer.CreateFormatter()), server);
        server.Attach(serverRpc);
        serverRpc.StartListening();
        var client = new Client();
        var clientRpc = new JsonRpc(new HeaderDelimitedMessageHandler(clientStream, EngineServer.CreateFormatter()), client);
        clientRpc.StartListening();
        return (server, client, new Disposables(serverRpc, clientRpc));
    }

    private sealed class Disposables(params IDisposable[] items) : IDisposable
    {
        public void Dispose() { foreach (var i in items) i.Dispose(); }
    }

    [TestMethod]
    [Timeout(30_000)]
    public async Task CoverageUpdated_is_sent_as_a_coverageUpdated_notification()
    {
        var (server, client, cleanup) = Connect();
        using var _ = cleanup;

        await server.CoverageUpdated([new FileMarkup("/repo/src/A.cs", "h1", true, [4], [1, 2], [3])]);
        var args = await client.Updated.Task;

        var file = args["files"]!.AsArray().Single()!.AsObject();
        Assert.AreEqual("/repo/src/A.cs", (string?)file["path"]);
        Assert.AreEqual("h1", (string?)file["hash"]);
        Assert.AreEqual(true, (bool?)file["stale"]);
        CollectionAssert.AreEqual(new[] { 4 }, file["notCovered"]!.AsArray().Select(n => (int)n!).ToArray());
        CollectionAssert.AreEqual(new[] { 1, 2 }, file["passing"]!.AsArray().Select(n => (int)n!).ToArray());
        CollectionAssert.AreEqual(new[] { 3 }, file["failing"]!.AsArray().Select(n => (int)n!).ToArray());
    }

    [TestMethod]
    [Timeout(30_000)]
    public async Task CoverageStatus_is_sent_as_a_coverageStatus_notification()
    {
        var (server, client, cleanup) = Connect();
        using var _ = cleanup;

        await server.CoverageStatus(new CoverageAvailability(false, "install dotnet-coverage"));
        var args = await client.Status.Task;

        Assert.AreEqual(false, (bool?)args["available"]);
        Assert.AreEqual("install dotnet-coverage", (string?)args["message"]);
    }

    [TestMethod]
    public void Initialize_accepts_a_missing_coverage_option()
    {
        var options = new JsonSerializerOptions(JsonSerializerDefaults.Web);

        var without = JsonSerializer.Deserialize<InitializeParams>("""{"solutionPath":"/s","dotnetPath":null,"maxParallel":0,"debounceMs":0}""", options)!;
        var off = JsonSerializer.Deserialize<InitializeParams>("""{"solutionPath":"/s","dotnetPath":null,"maxParallel":0,"debounceMs":0,"coverage":false}""", options)!;

        Assert.IsNull(without.Coverage);
        Assert.AreEqual(false, off.Coverage);
    }
}
