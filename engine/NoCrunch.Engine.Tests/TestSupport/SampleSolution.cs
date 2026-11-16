using System.Diagnostics;

namespace NoCrunch.Engine.Tests.TestSupport;

internal static class SampleSolution
{
    public static string SourceDir
    {
        get
        {
            var dir = new DirectoryInfo(AppContext.BaseDirectory);
            while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "NoCrunch.slnx")))
                dir = dir.Parent;
            if (dir is null) throw new InvalidOperationException("Repo root not found");
            return Path.Combine(dir.FullName, "samples", "Sample Solution");
        }
    }

    /// <summary>Copies the sample to a fresh temp dir (path contains a space) and git-inits it.</summary>
    public static string CopyToTemp()
    {
        var target = Path.Combine(Path.GetTempPath(), "nocrunch tests", Guid.NewGuid().ToString("N")[..8], "Sample Repo");
        CopyDir(SourceDir, target);
        Git(target, "init", "-q");
        return target;
    }

    private static void CopyDir(string from, string to)
    {
        Directory.CreateDirectory(to);
        foreach (var file in Directory.EnumerateFiles(from))
            File.Copy(file, Path.Combine(to, Path.GetFileName(file)));
        foreach (var dir in Directory.EnumerateDirectories(from))
        {
            var name = Path.GetFileName(dir);
            if (name is "bin" or "obj" or ".nocrunch" or "TestResults") continue;
            CopyDir(dir, Path.Combine(to, name));
        }
    }

    public static void Git(string cwd, params string[] args)
    {
        var psi = new ProcessStartInfo("git") { WorkingDirectory = cwd, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var a in args) psi.ArgumentList.Add(a);
        using var p = Process.Start(psi)!;
        p.WaitForExit();
        if (p.ExitCode != 0) throw new InvalidOperationException($"git {string.Join(' ', args)} failed: {p.StandardError.ReadToEnd()}");
    }
}
