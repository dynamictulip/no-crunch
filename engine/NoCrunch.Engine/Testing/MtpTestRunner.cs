using System.Threading.Channels;
using Microsoft.Testing.Platform.ServerMode.Client;
using NoCrunch.Engine.Graph;
using NoCrunch.Engine.Layout;

namespace NoCrunch.Engine.Testing;

/// <summary>Discovers and runs one MTP test application via server mode (JSON-RPC).</summary>
public sealed class MtpTestRunner(PathMap map) : NoCrunch.Engine.Core.ITestStep
{
    private const string ProtocolErrorExitCodeHint = "12";

    public async Task<ProjectRunOutcome> RunAsync(
        ProjectInfo project,
        Func<TestId, bool> shouldRun,
        Func<IReadOnlyList<TestResultUpdate>, Task> onUpdates,
        CancellationToken ct)
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
            var discovered = await DiscoverAsync(project, ct);
            var toRun = new List<string>();
            var initial = new List<TestResultUpdate>();
            foreach (var t in discovered)
            {
                var included = shouldRun(t.Id);
                if (included) toRun.Add(t.Id.Uid);
                initial.Add(t with { State = included ? TestState.Queued : TestState.Excluded });
            }
            updates.Writer.TryWrite(initial);

            if (toRun.Count > 0) await ExecuteAsync(project, toRun, updates.Writer, ct);
            return new ProjectRunOutcome(name, ProjectRunStatus.Completed, null);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            return new ProjectRunOutcome(name, ProjectRunStatus.Cancelled, null);
        }
        catch (MtpServerClientException ex) when (ex is not MtpServerConnectionClosedException
                                                  && (ex.Message.Contains("protocol", StringComparison.OrdinalIgnoreCase)
                                                      || ex.Message.Contains(ProtocolErrorExitCodeHint, StringComparison.Ordinal)))
        {
            return new ProjectRunOutcome(name, ProjectRunStatus.UnsupportedMtpVersion, ex.Message);
        }
        catch (Exception ex)
        {
            return new ProjectRunOutcome(name, ProjectRunStatus.Crashed, ex.Message);
        }
        finally
        {
            updates.Writer.TryComplete();
            await pump;
        }
    }

    private async Task<List<TestResultUpdate>> DiscoverAsync(ProjectInfo project, CancellationToken ct)
    {
        var found = new Dictionary<string, TestResultUpdate>(StringComparer.Ordinal);
        await WithClientAsync(project, async client =>
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

    private async Task ExecuteAsync(ProjectInfo project, IReadOnlyCollection<string> uids,
        ChannelWriter<IReadOnlyList<TestResultUpdate>> writer, CancellationToken ct)
    {
        await WithClientAsync(project, async client =>
        {
            client.TestNodesUpdated += (_, e) =>
            {
                var batch = new List<TestResultUpdate>();
                foreach (var change in e.Changes)
                {
                    if (!IsTest(change) || change.Uid is null) continue;
                    if (change.Node.TryGetValue("retry.is-superseded", out var superseded) && superseded is true) continue;
                    var state = change.ExecutionState switch
                    {
                        "discovered" or null => (TestState?)null,
                        "in-progress" => TestState.Running,
                        "passed" => TestState.Passed,
                        "skipped" => TestState.Skipped,
                        _ => TestState.Failed,
                    };
                    if (state is { } s) batch.Add(ToUpdate(project, change, s));
                }
                if (batch.Count > 0) writer.TryWrite(batch);
            };
            await client.RunTestsAsync(uids, ct);
        }, ct);
    }

    private static async Task WithClientAsync(ProjectInfo project, Func<IMtpServerClient, Task> body, CancellationToken ct)
    {
        var options = new MtpServerClientOptions { ClientName = "NoCrunch", IsStateful = false };
        options.EnvironmentVariables["DOTNET_CLI_TELEMETRY_OPTOUT"] = "1";
        using IMtpServerClient client = await MtpServerClient.LaunchAsync(project.TargetPath!, options, ct);
        try
        {
            var caps = await client.InitializeAsync(ct);
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
