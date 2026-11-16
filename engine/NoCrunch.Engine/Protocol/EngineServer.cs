using System.Text.Json;
using System.Text.Json.Serialization;
using NoCrunch.Engine.Build;
using NoCrunch.Engine.Core;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Layout;
using NoCrunch.Engine.Testing;
using NoCrunch.Engine.Workspace;
using StreamJsonRpc;

namespace NoCrunch.Engine.Protocol;

public sealed record InitializeParams(string SolutionPath, string? DotnetPath, int MaxParallel, int DebounceMs);
public sealed record InitializeResult(int ProtocolVersion, string RepoRoot, string MirrorRoot);
public sealed record FilesChangedParams(string[] Paths);
public sealed record SetExclusionsParams(ExclusionRule[] Rules);
public sealed record RunTestsParams(string[] Keys);

public sealed record TestResultDto(
    string Key, string Project, string? Type, string? Method, string Uid, string? Variant, string DisplayName,
    TestState State, double? DurationMs, string? Message, string? StackTrace, string? FilePath, int? Line, RunKind RunKind)
{
    public static TestResultDto From(TestResultUpdate u) => new(u.Id.Key, u.Id.Project, u.Id.Type, u.Id.Method, u.Id.Uid, u.Id.Variant,
        u.DisplayName, u.State, u.DurationMs, u.Message, u.StackTrace, u.FilePath, u.Line, u.RunKind);
}

/// <summary>
/// JSON-RPC 2.0 (Content-Length framed) adapter between the plugin and <see cref="EngineCore"/>.
/// Protocol changes must be additive; bump <see cref="ProtocolVersion"/> only for breaking changes.
/// </summary>
public sealed class EngineServer : IEngineEvents, IAsyncDisposable
{
    public const int ProtocolVersion = 1;

    private readonly TaskCompletionSource _shutdown = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private JsonRpc? _rpc;
    private EngineCore? _core;

    public Task ShutdownRequested => _shutdown.Task;

    public static SystemTextJsonFormatter CreateFormatter() => new()
    {
        JsonSerializerOptions =
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            DefaultIgnoreCondition = JsonIgnoreCondition.Never,
            Converters = { new JsonStringEnumConverter(JsonNamingPolicy.CamelCase) },
        },
    };

    public void Attach(JsonRpc rpc) => _rpc = rpc;

    [JsonRpcMethod("initialize", UseSingleObjectParameterDeserialization = true)]
    public async Task<InitializeResult> InitializeAsync(InitializeParams p)
    {
        if (_core is not null) throw new InvalidOperationException("Already initialized.");
        var layout = RepoLayout.Resolve(p.SolutionPath);
        var map = new PathMap(layout.RepoRoot, layout.MirrorRoot);
        var dotnet = string.IsNullOrWhiteSpace(p.DotnetPath) ? "dotnet" : p.DotnetPath;
        var runner = new ProcessRunner();
        var query = new DotnetMsBuildQuery(runner, map, dotnet);
        _core = new EngineCore(
            new SharedMirrorWorkspace(layout, map),
            ct => ProjectGraph.LoadAsync(layout, query, ct),
            new ProjectBuilder(runner, map, dotnet),
            new MtpTestRunner(map),
            new ResultStore(Path.Combine(layout.StateDir, "results.json")),
            this, map, layout.SolutionPath);
        if (p.DebounceMs > 0) _core.Debounce = TimeSpan.FromMilliseconds(p.DebounceMs);
        if (p.MaxParallel > 0) _core.MaxParallelTestProjects = p.MaxParallel;
        await _core.StartAsync(CancellationToken.None);
        return new InitializeResult(ProtocolVersion, layout.RepoRoot, layout.MirrorRoot);
    }

    [JsonRpcMethod("filesChanged", UseSingleObjectParameterDeserialization = true)]
    public void FilesChanged(FilesChangedParams p) => Core.FilesChanged(p.Paths);

    [JsonRpcMethod("setExclusions", UseSingleObjectParameterDeserialization = true)]
    public void SetExclusions(SetExclusionsParams p) => Core.SetExclusions(new ExclusionSet(p.Rules));

    [JsonRpcMethod("runAll")]
    public void RunAll() => Core.RunAll();

    [JsonRpcMethod("runTests", UseSingleObjectParameterDeserialization = true)]
    public void RunTests(RunTestsParams p) => Core.RunTests(p.Keys);

    [JsonRpcMethod("pause")]
    public void Pause() => Core.Pause();

    [JsonRpcMethod("resume")]
    public void Resume() => Core.Resume();

    [JsonRpcMethod("shutdown")]
    public async Task ShutdownAsync()
    {
        if (_core is not null) await _core.DisposeAsync();
        _shutdown.TrySetResult();
    }

    private EngineCore Core => _core ?? throw new InvalidOperationException("Call initialize first.");

    // ---- notifications -----------------------------------------------------------------------------------

    Task IEngineEvents.StateChanged(EngineState state, int queuedProjects) => Notify("stateChanged", new { state, queued = queuedProjects });

    Task IEngineEvents.ProjectBuildStarted(string project) => Notify("projectBuildStarted", new { project });

    Task IEngineEvents.ProjectBuildFinished(BuildOutcome o) =>
        Notify("projectBuildFinished", new { project = o.Project, status = o.Status, output = o.Output, blockedBy = o.BlockedBy });

    Task IEngineEvents.TestsDiscovered(string project, IReadOnlyList<TestResultUpdate> tests) =>
        Notify("testsDiscovered", new { project, tests = tests.Select(TestResultDto.From).ToList() });

    Task IEngineEvents.TestResults(IReadOnlyList<TestResultUpdate> updates) =>
        Notify("testResults", new { updates = updates.Select(TestResultDto.From).ToList() });

    Task IEngineEvents.ProjectRunFinished(ProjectRunOutcome o) =>
        Notify("projectRunFinished", new { project = o.Project, status = o.Status, error = o.Error });

    Task IEngineEvents.Log(string level, string message)
    {
        Console.Error.WriteLine($"[{level}] {message}");
        return Notify("log", new { level, message });
    }

    private async Task Notify(string method, object args)
    {
        if (_rpc is null) return;
        try { await _rpc.NotifyWithParameterObjectAsync(method, args); }
        catch (Exception e) when (e is ConnectionLostException or ObjectDisposedException) { }
    }

    public async ValueTask DisposeAsync()
    {
        if (_core is not null) await _core.DisposeAsync();
    }
}
