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

/// <param name="TestTimeoutSeconds">
/// Stop a test run that reports nothing for this many seconds and fail its unfinished tests. Absent = 30; 0 = no timeout.
/// </param>
public sealed record InitializeParams(string SolutionPath, string? DotnetPath, int MaxParallel, int DebounceMs, int? TestTimeoutSeconds = null);
public sealed record InitializeResult(int ProtocolVersion, string RepoRoot, string MirrorRoot);
public sealed record FilesChangedParams(string[] Paths);
public sealed record SetExclusionsParams(ExclusionRule[] Rules);
public sealed record RunTestsParams(string[] Keys);

public sealed record TestResultDto(
    string Key, string Project, string? Type, string? Method, string Uid, string? Variant, string DisplayName,
    TestState State, double? DurationMs, string? Message, string? StackTrace, string? FilePath, int? Line, RunKind RunKind,
    TestState? LastOutcome, string? TheoryKey)
{
    public static TestResultDto From(TestResultUpdate u) => new(u.Id.Key, u.Id.Project, u.Id.Type, u.Id.Method, u.Id.Uid, u.Id.Variant,
        u.DisplayName, u.State, u.DurationMs, u.Message, u.StackTrace, u.FilePath, u.Line, u.RunKind, u.LastOutcome, u.TheoryKey);
}

/// <summary>
/// JSON-RPC 2.0 (Content-Length framed) adapter between the plugin and <see cref="EngineCore"/>.
/// Protocol changes must be additive; bump <see cref="ProtocolVersion"/> only for breaking changes.
/// </summary>
public sealed class EngineServer : IEngineEvents, IAsyncDisposable
{
    public const int ProtocolVersion = 1;
    public static readonly TimeSpan DefaultTestTimeout = TimeSpan.FromSeconds(30);

    private readonly TaskCompletionSource _shutdown = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly Func<InitializeParams, IEngineEvents, (EngineCore Core, InitializeResult Result)> _createCore;
    /// <summary>Held while initializing (and shutting down), so only one core is ever started.</summary>
    private readonly SemaphoreSlim _initLock = new(1, 1);
    private JsonRpc? _rpc;
    private volatile EngineCore? _core;

    public EngineServer() : this(CreateCore) { }

    /// <param name="createCore">Builds the (not yet started) core for an <c>initialize</c> call; tests swap in fakes.</param>
    internal EngineServer(Func<InitializeParams, IEngineEvents, (EngineCore Core, InitializeResult Result)> createCore) =>
        _createCore = createCore;

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
        await _initLock.WaitAsync();
        try
        {
            if (_core is not null) throw new InvalidOperationException("Already initialized.");
            var (core, result) = _createCore(p, this);
            try
            {
                await core.StartAsync(CancellationToken.None);
            }
            catch
            {
                // Leave nothing half-started behind, so initialize can be called again.
                await core.DisposeAsync();
                throw;
            }
            _core = core;
            return result;
        }
        finally
        {
            _initLock.Release();
        }
    }

    /// <summary>The test timeout the plugin asked for: absent = <see cref="DefaultTestTimeout"/>, 0 (or less) = none.</summary>
    internal static TimeSpan? TestTimeout(InitializeParams p) => p.TestTimeoutSeconds switch
    {
        null => DefaultTestTimeout,
        <= 0 => null,
        { } s => TimeSpan.FromSeconds(s),
    };

    private static (EngineCore, InitializeResult) CreateCore(InitializeParams p, IEngineEvents events)
    {
        var layout = RepoLayout.Resolve(p.SolutionPath);
        var map = new PathMap(layout.RepoRoot, layout.MirrorRoot);
        var dotnet = string.IsNullOrWhiteSpace(p.DotnetPath) ? "dotnet" : p.DotnetPath;
        var runner = new ProcessRunner();
        var query = new DotnetMsBuildQuery(runner, map, dotnet);
        var core = new EngineCore(
            new SharedMirrorWorkspace(layout, map),
            ct => ProjectGraph.LoadAsync(layout, query, ct),
            new ProjectBuilder(runner, map, dotnet),
            new MtpTestRunner(map, TestTimeout(p)),
            new ResultStore(Path.Combine(layout.StateDir, "results.json")),
            events, map, layout.SolutionPath);
        if (p.DebounceMs > 0) core.Debounce = TimeSpan.FromMilliseconds(p.DebounceMs);
        if (p.MaxParallel > 0) core.MaxParallelTestProjects = p.MaxParallel;
        return (core, new InitializeResult(ProtocolVersion, layout.RepoRoot, layout.MirrorRoot));
    }

    // Notifications before initialize has finished are ignored (with a warning): the core starts with a full
    // reconcile and a run of everything, so nothing they asked for is lost by dropping them.

    [JsonRpcMethod("filesChanged", UseSingleObjectParameterDeserialization = true)]
    public void FilesChanged(FilesChangedParams p) => WithCore("filesChanged", c => c.FilesChanged(p.Paths));

    [JsonRpcMethod("setExclusions", UseSingleObjectParameterDeserialization = true)]
    public void SetExclusions(SetExclusionsParams p) => WithCore("setExclusions", c => c.SetExclusions(new ExclusionSet(p.Rules)));

    [JsonRpcMethod("runAll")]
    public void RunAll() => WithCore("runAll", c => c.RunAll());

    [JsonRpcMethod("runTests", UseSingleObjectParameterDeserialization = true)]
    public void RunTests(RunTestsParams p) => WithCore("runTests", c => c.RunTests(p.Keys));

    [JsonRpcMethod("pause")]
    public void Pause() => WithCore("pause", c => c.Pause());

    [JsonRpcMethod("resume")]
    public void Resume() => WithCore("resume", c => c.Resume());

    [JsonRpcMethod("shutdown")]
    public async Task ShutdownAsync()
    {
        await DisposeCoreAsync();
        _shutdown.TrySetResult();
    }

    private void WithCore(string method, Action<EngineCore> action)
    {
        if (_core is { } core) action(core);
        else _ = ((IEngineEvents)this).Log("warning", $"Ignored '{method}': the engine is not initialized yet.");
    }

    private async Task DisposeCoreAsync()
    {
        // Wait for an initialize in progress, so its core is disposed too rather than started after shutdown.
        await _initLock.WaitAsync();
        try
        {
            if (_core is { } core) await core.DisposeAsync();
        }
        finally
        {
            _initLock.Release();
        }
    }

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

    public async ValueTask DisposeAsync() => await DisposeCoreAsync();
}
