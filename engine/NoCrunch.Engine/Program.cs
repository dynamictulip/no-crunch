using NoCrunch.Engine.Protocol;
using StreamJsonRpc;

// stdout carries the protocol; everything human-readable goes to stderr.
var stdout = Console.OpenStandardOutput();
var stdin = Console.OpenStandardInput();
Console.SetOut(Console.Error);

await using var server = new EngineServer();
using var rpc = new JsonRpc(new HeaderDelimitedMessageHandler(stdout, stdin, EngineServer.CreateFormatter()));
rpc.AddLocalRpcTarget(server, new JsonRpcTargetOptions { NotifyClientOfEvents = false });
server.Attach(rpc);
rpc.StartListening();

await Task.WhenAny(rpc.Completion, server.ShutdownRequested);
return 0;
