namespace NoCrunch.Engine.Workspace;

/// <summary>
/// Retries file operations that fail because another process briefly has the file open (on Windows an editor's
/// save, an antivirus scan or the indexer holds files without sharing). Waits under a second in all; a lock that
/// lasts longer is a real problem and is rethrown so the cycle reports it instead of silently skipping the file.
/// </summary>
internal static class FileRetry
{
    private static readonly TimeSpan[] DefaultDelays =
        [TimeSpan.FromMilliseconds(50), TimeSpan.FromMilliseconds(100), TimeSpan.FromMilliseconds(200), TimeSpan.FromMilliseconds(400)];

    public static T Run<T>(Func<T> op) => Run(op, DefaultDelays);

    public static void Run(Action op) => Run(() => { op(); return 0; }, DefaultDelays);

    public static T Run<T>(Func<T> op, TimeSpan[] delays)
    {
        for (var attempt = 0; ; attempt++)
        {
            try
            {
                return op();
            }
            // A missing file or an over-long path won't fix itself by waiting; callers decide what those mean.
            catch (IOException e) when (attempt < delays.Length && e is not (FileNotFoundException or DirectoryNotFoundException or PathTooLongException))
            {
                Thread.Sleep(delays[attempt]);
            }
        }
    }
}
