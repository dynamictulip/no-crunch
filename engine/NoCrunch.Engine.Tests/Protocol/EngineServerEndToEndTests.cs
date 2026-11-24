using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Nodes;
using NoCrunch.Engine.Core;
using NoCrunch.Engine.Protocol;
using NoCrunch.Engine.Tests.TestSupport;
using StreamJsonRpc;

namespace NoCrunch.Engine.Tests.Protocol;

[TestClass]
public class EngineServerEndToEndTests
{
    private sealed class Client
    {
        public ConcurrentQueue<(string method, JsonObject args)> Notes { get; } = new();
        public SemaphoreSlim Idle { get; } = new(0);

        [JsonRpcMethod("stateChanged", UseSingleObjectParameterDeserialization = true)]
        public void StateChanged(JsonObject args) { Add("stateChanged", args); if ((string?)args["state"] == "idle") Idle.Release(); }
        [JsonRpcMethod("projectBuildStarted", UseSingleObjectParameterDeserialization = true)]
        public void BuildStarted(JsonObject args) => Add("projectBuildStarted", args);
        [JsonRpcMethod("projectBuildFinished", UseSingleObjectParameterDeserialization = true)]
        public void BuildFinished(JsonObject args) => Add("projectBuildFinished", args);
        [JsonRpcMethod("testsDiscovered", UseSingleObjectParameterDeserialization = true)]
        public void Discovered(JsonObject args) => Add("testsDiscovered", args);
        [JsonRpcMethod("testResults", UseSingleObjectParameterDeserialization = true)]
        public void Results(JsonObject args) => Add("testResults", args);
        [JsonRpcMethod("projectRunFinished", UseSingleObjectParameterDeserialization = true)]
        public void RunFinished(JsonObject args) => Add("projectRunFinished", args);
        [JsonRpcMethod("log", UseSingleObjectParameterDeserialization = true)]
        public void Log(JsonObject args) => Add("log", args);

        private void Add(string m, JsonObject a) => Notes.Enqueue((m, a));

        public void Clear() { while (Notes.TryDequeue(out _)) { } }

        public async Task WaitIdle()
        {
            if (!await Idle.WaitAsync(TimeSpan.FromMinutes(3)))
                Assert.Fail("No idle. Notes: " + string.Join("\n", Notes.Select(n => n.method + " " + n.args.ToJsonString())));
        }

        public IEnumerable<JsonObject> Updates() => Notes
            .Where(n => n.method is "testResults" or "testsDiscovered")
            .SelectMany(n => (n.args[n.method == "testResults" ? "updates" : "tests"] as JsonArray)!.Select(x => x!.AsObject()));
    }

    [TestMethod]
    [Timeout(600_000)]
    public async Task Edit_core_file_reruns_core_tests_only()
    {
        var repo = SampleSolution.CopyToTemp();
        var psi = new ProcessStartInfo("dotnet")
        {
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false,
        };
        psi.ArgumentList.Add(typeof(EngineCore).Assembly.Location);
        using var engine = Process.Start(psi)!;
        var stderr = new System.Text.StringBuilder();
        engine.ErrorDataReceived += (_, e) => { lock (stderr) stderr.AppendLine(e.Data); };
        engine.BeginErrorReadLine();

        var client = new Client();
        var handler = new HeaderDelimitedMessageHandler(engine.StandardInput.BaseStream, engine.StandardOutput.BaseStream, EngineServer.CreateFormatter());
        using var rpc = new JsonRpc(handler, client);
        rpc.StartListening();
        try
        {
            var init = await rpc.InvokeWithParameterObjectAsync<JsonObject>("initialize",
                new { solutionPath = Path.Combine(repo, "Sample.slnx"), dotnetPath = "dotnet", maxParallel = 2, debounceMs = 200 });
            Assert.AreEqual(1, (int)init["protocolVersion"]!);
            await client.WaitIdle();
            Assert.IsTrue(client.Updates().Any(u => (string?)u["state"] == "passed"), "initial run passes");

            await rpc.NotifyWithParameterObjectAsync("setExclusions", new
            {
                rules = new[] { new { project = "tests/Shop.E2E.Tests/Shop.E2E.Tests.csproj", type = (string?)null, method = (string?)null, uid = (string?)null } },
            });
            await Task.Delay(300);
            client.Clear();

            var calc = Path.Combine(repo, "src/Shop.Core/PriceCalculator.cs");
            File.WriteAllText(calc, File.ReadAllText(calc).Replace("unit * qty", "unit + qty"));
            await rpc.NotifyWithParameterObjectAsync("filesChanged", new { paths = new[] { calc } });
            await client.WaitIdle();

            var built = client.Notes.Where(n => n.method == "projectBuildFinished").Select(n => (string)n.args["project"]!).ToHashSet();
            // Shop.E2E.Tests depends on Shop.Core too, but it's excluded as a whole, so it isn't built.
            CollectionAssert.AreEquivalent(new[]
            {
                "src/Shop.Core/Shop.Core.csproj", "tests/Shop.Core.Tests/Shop.Core.Tests.csproj",
                "tests/Shop.Xunit.Tests/Shop.Xunit.Tests.csproj", "tests/Shop.NUnit.Tests/Shop.NUnit.Tests.csproj",
            }, built.ToArray());

            var finals = client.Updates().Where(u => (string?)u["state"] is "passed" or "failed").ToList();
            Assert.IsTrue(finals.Count > 0);
            // Every test project that depends on Shop.Core reruns: MSTest, xUnit and NUnit.
            var coreDependents = new[] { "Shop.Core.Tests", "Shop.Xunit.Tests", "Shop.NUnit.Tests" }.Select(n => $"tests/{n}/{n}.csproj").ToHashSet();
            Assert.IsTrue(finals.All(u => coreDependents.Contains((string)u["project"]!)),
                string.Join("\n", finals.Select(f => f.ToJsonString())));
            foreach (var project in coreDependents)
            {
                var matching = finals.Where(u => (string?)u["project"] == project && ((string?)u["method"] ?? (string?)u["displayName"]) == "Total_multiplies").ToList();
                Assert.AreEqual(1, matching.Count, project + " in: " + string.Join(" | ", finals.Select(f => $"{(string?)f["project"]}:{(string?)f["method"]}:{(string?)f["state"]}")));
                var failed = matching.Single();
                Assert.AreEqual("failed", (string?)failed["state"], project);
                Assert.IsFalse(((string?)failed["stackTrace"] ?? "").Contains(".nocrunch"), project);
                // NUnit's MTP nodes carry no source location, so its tests have no method or file path to check.
                if (!project.Contains("NUnit")) StringAssert.StartsWith((string?)failed["filePath"], NoCrunch.Engine.Layout.RealPath.Of(repo), project);
            }

            await rpc.InvokeAsync("shutdown");
        }
        finally
        {
            if (!engine.WaitForExit(10_000)) engine.Kill(true);
            Console.WriteLine(stderr.ToString());
        }
    }
}
