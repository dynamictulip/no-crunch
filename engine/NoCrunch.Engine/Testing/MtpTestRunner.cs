using System.Threading.Channels;
using Microsoft.Testing.Platform.ServerMode.Client;
using Microsoft.Testing.Platform.ServerMode.Client.Protocol.ServerMode;
using NoCrunch.Engine.Coverage;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Layout;

namespace NoCrunch.Engine.Testing;

/// <summary>Raised when a test application's server mode speaks a protocol version we can't use.</summary>
internal sealed class UnsupportedMtpVersionException(string message, Exception inner) : Exception(message, inner);

/// <summary>
/// Discovers and runs one MTP test application via server mode (JSON-RPC).
/// <paramref name="testTimeout"/>: when a run reports nothing (no test result or progress) for this long, its test host
/// is stopped and the tests it hadn't finished fail. Null = no timeout.
/// </summary>
public sealed class MtpTestRunner(PathMap map, TimeSpan? testTimeout = null) : NoCrunch.Engine.Core.ITestStep
{
    /// <summary>MTP's exit code for "incompatible protocol version".</summary>
    private const int IncompatibleProtocolVersionExitCode = 12;

    /// <summary>Longest a single coverage call (a snapshot after a class) may take before it is abandoned.</summary>
    private static readonly TimeSpan CoverageCallTimeout = TimeSpan.FromMinutes(5);

    /// <summary>How long to wait for a test host that hung up during the handshake to exit, so its exit code can be read.</summary>
    private static readonly TimeSpan HandshakeExitWait = TimeSpan.FromSeconds(2);

    public async Task<ProjectRunOutcome> RunAsync(
        ProjectInfo project,
        Func<TestId, bool> shouldRun,
        Func<IReadOnlyList<TestResultUpdate>, Task> onUpdates,
        CancellationToken ct,
        ICoverageRun? coverage = null)
    {
        var name = project.RepoRelativePath;
        if (ct.IsCancellationRequested) return new ProjectRunOutcome(name, ProjectRunStatus.Cancelled, null);
        if (!project.IsMtp || project.TargetPath is null || !File.Exists(project.TargetPath))
            return new ProjectRunOutcome(name, ProjectRunStatus.Crashed, $"No MTP test application at '{project.TargetPath}'.");

        var updates = Channel.CreateUnbounded<IReadOnlyList<TestResultUpdate>>(new UnboundedChannelOptions { SingleReader = true });
        var pump = Task.Run(async () =>
        {
            await foreach (var batch in updates.Reader.ReadAllAsync(CancellationToken.None))
                await onUpdates(batch);
        }, CancellationToken.None);

        try
        {
            var (discovered, renamed) = KeepKeysApart(await DiscoverAsync(project, ct));
            var queued = new List<TestResultUpdate>();
            var initial = new List<TestResultUpdate>();
            foreach (var t in discovered)
            {
                var u = t with { State = shouldRun(t.Id) ? TestState.Queued : TestState.Excluded };
                if (u.State == TestState.Queued) queued.Add(u);
                initial.Add(u);
            }
            updates.Writer.TryWrite(initial);

            var timedOut = queued.Count > 0 ? await ExecuteAsync(project, queued, renamed, updates.Writer, coverage, ct) : null;
            // A timed-out run still ends normally: every test it was asked to run now has a result (the unfinished
            // ones failed), and the error says why the test host was stopped.
            return new ProjectRunOutcome(name, ProjectRunStatus.Completed, timedOut);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            return new ProjectRunOutcome(name, ProjectRunStatus.Cancelled, null);
        }
        catch (Exception ex)
        {
            return ToOutcome(name, ex);
        }
        finally
        {
            updates.Writer.TryComplete();
            await pump;
        }
    }

    /// <summary>A discovered test whose key clashed with another's: its original key and the one it uses instead.</summary>
    internal sealed record Renamed(string OriginalKey, TestId Id);

    /// <summary>
    /// A data row's key comes from its display name, which isn't always unique: xUnit, for one, cuts argument values to
    /// 50 characters plus "···", so rows whose arguments share a long prefix look alike. Tests whose keys clash get
    /// their UID added to the key so each stays its own test; every other key is left as it was, so saved results and
    /// exclusions keep matching. Returns the tests and, by UID, the renames to apply to results reported during the run.
    /// </summary>
    internal static (List<TestResultUpdate> Tests, IReadOnlyDictionary<string, Renamed> Renamed) KeepKeysApart(IReadOnlyList<TestResultUpdate> discovered)
    {
        var clashing = discovered.GroupBy(t => t.Id.Key, StringComparer.Ordinal).Where(g => g.Count() > 1)
            .Select(g => g.Key).ToHashSet(StringComparer.Ordinal);
        var renamed = new Dictionary<string, Renamed>(StringComparer.Ordinal);
        var tests = discovered.Select(t =>
        {
            if (!clashing.Contains(t.Id.Key)) return t;
            var id = t.Id with { Variant = t.Id.Variant is { } v ? $"{v} [{t.Id.Uid}]" : $"[{t.Id.Uid}]" };
            renamed[t.Id.Uid] = new Renamed(t.Id.Key, id);
            return t with { Id = id };
        }).ToList();
        return (tests, renamed);
    }

    /// <summary>The outcome of a run that threw <paramref name="ex"/> (other than by being cancelled).</summary>
    internal static ProjectRunOutcome ToOutcome(string project, Exception ex) => ex is UnsupportedMtpVersionException
        ? new ProjectRunOutcome(project, ProjectRunStatus.UnsupportedMtpVersion, ex.Message)
        : new ProjectRunOutcome(project, ProjectRunStatus.Crashed, ex.Message);

    /// <summary>
    /// Whether a failed initialize handshake means the test application's MTP doesn't speak our protocol version:
    /// the server refused it with its dedicated error code, the client refused the version the server picked (the only
    /// plain <see cref="MtpServerClientException"/>s the handshake throws are about the version or a malformed reply),
    /// or the server hung up and exited with MTP's incompatible-protocol exit code.
    /// </summary>
    internal static bool IsProtocolRejection(MtpServerClientException ex, int? serverExitCode) => ex switch
    {
        MtpServerErrorException e => e.ErrorCode == ErrorCodes.ProtocolVersionNotSupported,
        MtpServerConnectionClosedException => serverExitCode == IncompatibleProtocolVersionExitCode,
        _ => ex.GetType() == typeof(MtpServerClientException),
    };

    private async Task<List<TestResultUpdate>> DiscoverAsync(ProjectInfo project, CancellationToken ct)
    {
        var found = new Dictionary<string, TestResultUpdate>(StringComparer.Ordinal);
        await WithClientAsync(project.TargetPath!, async client =>
        {
            client.TestNodesUpdated += (_, e) =>
            {
                foreach (var change in e.Changes)
                    if (IsTest(change) && change.ExecutionState == "discovered" && change.Uid is { } uid)
                        lock (found) found[uid] = ToUpdate(project, change, TestState.Discovered);
            };
            await client.DiscoverTestsAsync(ct);
        }, ct);
        return found.Values.ToList();
    }

    /// <summary>
    /// Runs <paramref name="queued"/>. Returns null when the run finished, or why it was stopped when it reported nothing
    /// for the test timeout: then its test host has been stopped and its unfinished tests reported as failed.
    /// </summary>
    private async Task<string?> ExecuteAsync(ProjectInfo project, IReadOnlyList<TestResultUpdate> queued,
        IReadOnlyDictionary<string, Renamed> renamed, ChannelWriter<IReadOnlyList<TestResultUpdate>> writer,
        ICoverageRun? coverage, CancellationToken ct)
    {
        var gate = new object();
        var stopped = false;
        // Keys are unique after KeepKeysApart, but a clash must never crash the run: the later test wins.
        var latest = new Dictionary<string, TestResultUpdate>(StringComparer.Ordinal);
        foreach (var q in queued) latest[q.Id.Key] = q;
        var reportedUids = new HashSet<string>(StringComparer.Ordinal);
        // While a failing test is rerun alone for coverage, its updates are kept here instead of being reported.
        string? aloneUid = null;
        var aloneStates = new Dictionary<string, TestState>(StringComparer.Ordinal);
        using var watchdog = CancellationTokenSource.CreateLinkedTokenSource(ct);
        // (Re)starts the inactivity timer. Only call under gate while not stopped (the source is disposed after that).
        void Arm()
        {
            if (testTimeout is { } t) watchdog.CancelAfter(t);
        }

        try
        {
            await WithClientAsync(coverage?.TargetPath ?? project.TargetPath!, async client =>
            {
                client.TestNodesUpdated += (_, e) =>
                {
                    lock (gate)
                    {
                        if (stopped) return;
                        Arm();
                        var batch = new List<TestResultUpdate>();
                        foreach (var change in e.Changes)
                        {
                            if (!IsTest(change) || change.Uid is null) continue;
                            if (change.Node.TryGetValue("retry.is-superseded", out var superseded) && superseded is true) continue;
                            if (aloneUid is not null && change.Uid == aloneUid)
                            {
                                // A rerun for coverage: remember the outcome (a failing row makes the test failed), report nothing.
                                var rerun = change.ExecutionState switch { "passed" => TestState.Passed, "skipped" => TestState.Skipped, "discovered" or "in-progress" or null => (TestState?)null, _ => TestState.Failed };
                                if (rerun is { } rerunState && (!aloneStates.TryGetValue(aloneUid, out var seen) || seen != TestState.Failed)) aloneStates[aloneUid] = rerunState;
                                continue;
                            }
                            var state = change.ExecutionState switch
                            {
                                "discovered" or null => (TestState?)null,
                                "in-progress" => TestState.Running,
                                "passed" => TestState.Passed,
                                "skipped" => TestState.Skipped,
                                _ => TestState.Failed,
                            };
                            if (state is not { } s) continue;
                            var u = ToUpdate(project, change, s);
                            // A test renamed at discovery reports under its new key. (Rows of a theory share its UID
                            // but have their own display names, so they don't match the theory's original key.)
                            if (renamed.TryGetValue(u.Id.Uid, out var r) && u.Id.Key == r.OriginalKey) u = u with { Id = r.Id };
                            latest[u.Id.Key] = u;
                            reportedUids.Add(u.Id.Uid);
                            batch.Add(u);
                        }
                        if (batch.Count > 0) writer.TryWrite(batch);
                    }
                };
                lock (gate) Arm();
                if (coverage is null)
                {
                    await client.RunTestsAsync(queued.Select(q => q.Id.Uid).Distinct(StringComparer.Ordinal).ToList(), watchdog.Token);
                    return;
                }

                // With coverage: one class at a time on this host, a snapshot after each, then failing tests alone.
                foreach (var slice in queued.GroupBy(q => q.Id.Type ?? "").ToList())
                {
                    await client.RunTestsAsync(slice.Select(q => q.Id.Uid).Distinct(StringComparer.Ordinal).ToList(), watchdog.Token);
                    List<TestResultUpdate> results;
                    lock (gate) results = latest.Values.Where(r => (r.Id.Type ?? "") == slice.Key && r.IsOutcome).ToList();
                    await CoverageCallAsync(token => coverage.ClassFinishedAsync(slice.Key.Length == 0 ? "(no class)" : slice.Key, results, token));
                }
                lock (gate) Arm();
                await coverage.FinishedAsync(async (test, _) =>
                {
                    lock (gate) { aloneUid = test.Id.Uid; aloneStates.Remove(test.Id.Uid); }
                    try { await client.RunTestsAsync([test.Id.Uid], watchdog.Token); }
                    finally { lock (gate) aloneUid = null; }
                    lock (gate)
                    {
                        Arm();
                        return aloneStates.TryGetValue(test.Id.Uid, out var state) ? state : throw new InvalidOperationException("The rerun reported no result.");
                    }
                }, ct);
                lock (gate) Arm();

                // The test-timeout watchdog is paused while the coverage run works (a snapshot reports nothing), but a
                // hung coverage call is still cut off after CoverageCallTimeout, or at once if the run is cancelled.
                async Task CoverageCallAsync(Func<CancellationToken, Task> call)
                {
                    lock (gate) watchdog.CancelAfter(Timeout.InfiniteTimeSpan);
                    try
                    {
                        using var cap = CancellationTokenSource.CreateLinkedTokenSource(ct);
                        cap.CancelAfter(CoverageCallTimeout);
                        await call(cap.Token);
                    }
                    finally
                    {
                        lock (gate) Arm();
                    }
                }
            }, ct);
            return null;
        }
        catch (OperationCanceledException) when (watchdog.IsCancellationRequested && !ct.IsCancellationRequested)
        {
            // WithClientAsync has already shut the test host down (killing its process tree).
            var seconds = $"{testTimeout!.Value.TotalSeconds:0.###} s";
            lock (gate)
            {
                stopped = true;
                var failed = latest.Values
                    .Where(r => r.State == TestState.Running
                                // Rows of a theory report under the theory's UID; the theory itself is settled from its rows.
                                || (r.State == TestState.Queued && !reportedUids.Contains(r.Id.Uid)))
                    .Select(r => r with
                    {
                        State = TestState.Failed, DurationMs = null, StackTrace = null,
                        Message = r.State == TestState.Running
                            ? $"Timed out: no result for {seconds}"
                            : $"Not run: the test run was stopped after {seconds} with no result",
                    })
                    .ToList();
                if (failed.Count > 0) writer.TryWrite(failed);
            }
            return $"Timed out: the test run reported nothing for {seconds}, so its test host was stopped.";
        }
        finally
        {
            lock (gate) stopped = true;
        }
    }

    private static async Task WithClientAsync(string targetPath, Func<IMtpServerClient, Task> body, CancellationToken ct)
    {
        var options = new MtpServerClientOptions { ClientName = "NoCrunch", IsStateful = false };
        options.EnvironmentVariables["DOTNET_CLI_TELEMETRY_OPTOUT"] = "1";
        using IMtpServerClient client = await MtpServerClient.LaunchAsync(targetPath, options, ct);
        try
        {
            var caps = await InitializeAsync(client, ct);
            if (caps.MultiConnectionProvider)
                throw new NotSupportedException("Test application opened a passive controller connection; not supported.");
            await body(client);
            await client.ExitAsync(ct);
        }
        finally
        {
            await client.ShutdownAsync();
        }
    }

    private static async Task<MtpServerCapabilities> InitializeAsync(IMtpServerClient client, CancellationToken ct)
    {
        try
        {
            return await client.InitializeAsync(ct);
        }
        catch (MtpServerClientException ex) when (!ct.IsCancellationRequested)
        {
            int? exitCode = null;
            if (ex is MtpServerConnectionClosedException)
            {
                // The host may still be exiting; give it a moment so its exit code can be read.
                var until = DateTime.UtcNow + HandshakeExitWait;
                while ((exitCode = client.ServerExitCode) is null && DateTime.UtcNow < until)
                    await Task.Delay(50, CancellationToken.None);
            }
            if (IsProtocolRejection(ex, exitCode)) throw new UnsupportedMtpVersionException(ex.Message, ex);
            throw;
        }
    }

    private static bool IsTest(MtpTestNodeUpdate change) => change.NodeType != "group";

    private TestResultUpdate ToUpdate(ProjectInfo project, MtpTestNodeUpdate change, TestState state)
    {
        var type = GetString(change, "location.type");
        var method = GetString(change, "location.method");
        var display = change.DisplayName ?? method ?? change.Uid!;
        var variant = method is not null && display != method ? display : null;
        var file = change.FilePath is { } f ? SafeToRepo(f) : null;
        var message = change.ErrorMessage is { } m ? map.RewriteText(m) : null;
        var stack = change.ErrorStackTrace is { } st ? map.RewriteText(st) : null;
        return new TestResultUpdate(
            new TestId(project.RepoRelativePath, type, method, change.Uid!, variant),
            display, state, change.DurationInMilliseconds, message, stack, file, change.LineStart);
    }

    private string SafeToRepo(string path)
    {
        try { return map.ToRepo(path); }
        catch (ArgumentException) { return map.RewriteText(path); }
    }

    private static string? GetString(MtpTestNodeUpdate change, string key) =>
        change.Node.TryGetValue(key, out var v) ? v?.ToString() : null;
}
