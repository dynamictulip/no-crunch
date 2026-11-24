# No Crunch

Lightweight, NCrunch-style continuous testing for .NET in **JetBrains Rider on macOS**.

Save a file and No Crunch rebuilds only the projects your change affects and reruns their tests. All of this happens in an isolated mirror of your repo, so it never touches Rider's own `bin`/`obj` and never blocks the IDE.

- **Project-level impact detection.** A change reruns the tests in the owning project and every test project that depends on it. Changes to repo-wide build files rerun everything.
- **Isolated builds.** The repo is mirrored into `.nocrunch/<Solution>/mirror/` and each project is built on its own with `BuildProjectReferences=false`, so one broken project doesn't stop unrelated ones.
- **What gets mirrored.** Everything except `bin`, `obj`, `.git`, `.idea`, `.vs`, `TestResults`, `node_modules` and **git-ignored directories** (caches, artifacts). Git-ignored *files*, such as `appsettings.Development.json`, are still copied because tests often need them.
- **Microsoft.Testing.Platform (MTP).** Test apps are driven in MTP server mode (JSON-RPC), so you get per-test results, locations and stack traces.
- **Exclusions.** Untick projects, classes or single tests (e.g. E2E suites) in the tool window. They're saved to `<Solution>.nocrunch.user` next to the `.sln`/`.slnx`. Commit it or ignore it, whichever suits you.
- **Group by.** The toolbar's Group By button opens a popup to tick and reorder the levels the tree nests by: Project, Folder, File, Namespace and Class (e.g. Project › Namespace › Class), or none for a flat list of tests. Nested classes sit inside their outer class. The last few groupings you used are listed under Recent for one-click switching. A group with only some of its tests excluded shows a partial checkbox and an excluded count.
- **Editor markers.** The editor gutter shows each test's status: a circle for passing, a diamond for failing, and a cross on the line a failure was thrown from (in test or production code). A hollow marker means the result is stale. Shape carries the meaning, so it works without telling green from red. The eye button in the toolbar turns the markers on and off.
- **Line coverage.** Production lines get dots too, from one coverage pass per test project: a circle where only passing tests ran the line, a diamond where a failing test did, and a dash where no test did. Hollow or faded means the data is from before your latest edits; the dots stay, faded, until the next pass replaces them, so after an edit a dot may sit on a line that has since moved. It needs the `dotnet-coverage` tool, 18.0 or newer (`dotnet tool install --global dotnet-coverage`); without it, coverage stays off and tests run as before, and No Crunch tells you once. It runs your tests class by class on one test host and takes a snapshot after each; each failing test is then rerun alone to see which lines it hit. Coverage runs on a copy of each test project's build output, so your own builds and debug runs are never instrumented. Turn it off with the Collect line coverage setting (applies after the engine restarts). The toolbar's marker button hides these dots too.
- **Survives restarts.** Last results show as stale on startup. The engine then catches up on anything that changed while Rider was closed.

## Requirements

- Rider 2026.2+ on macOS
- .NET 10 SDK (the engine runs on .NET 10). Test projects can target any framework the SDK can build.
- Test projects on **Microsoft.Testing.Platform 2.x**, e.g. `MSTest.Sdk`, xUnit v3, NUnit or TUnit with the MTP runner. VSTest-only projects show as *unsupported (VSTest)*.

## Install

```bash
cd plugin
JAVA_HOME=~/Applications/Rider.app/Contents/jbr/Contents/Home ./gradlew buildPlugin
```

Then in Rider: **Settings → Plugins → ⚙ → Install Plugin from Disk…** and pick `plugin/build/distributions/no-crunch-rider-<version>.zip`.

Open a solution and the **No Crunch** tool window (bottom) and status bar widget appear. Settings are under **Settings → Tools → No Crunch**: debounce, parallelism and the `dotnet` path.

## What it changes on your machine

- Creates `<repo root>/.nocrunch/` (mirror + state) and adds `.nocrunch/` to `.git/info/exclude`. It never edits `.gitignore`.
- Writes empty `Directory.Build.props/.targets` and `Directory.Packages.props` into `.nocrunch/`. These stop MSBuild import chains escaping the mirror into your real repo.
- Adds `.nocrunch` to Rider's *Ignored files and folders*, so the mirror isn't indexed or searched.
- If you build Docker images from the repo root, add `.nocrunch` to your `.dockerignore`.

## Known gaps (MVP)

- **Runs on save, not as you type.** Rider's idle autosave makes this close.
- **Project-level impact only.** Coverage-based selection of individual tests is planned.
- **Snapshot and approval tests** (Verify, Shouldly `ShouldMatchApproved`) write `.received` files inside the mirror. Shouldly's normal assertion messages work fine.
- **VSTest test projects** aren't run.

## Development

```
engine/NoCrunch.Engine/        C# engine (stdio JSON-RPC server)
engine/NoCrunch.Engine.Tests/  engine tests (MSTest on MTP)
plugin/                        Kotlin Rider plugin (bundles the engine)
samples/Sample Solution/       sample MTP solution used by tests
docs/superpowers/              design spec and implementation plan
```

```bash
dotnet test --solution NoCrunch.slnx
```

```bash
cd plugin && JAVA_HOME=~/Applications/Rider.app/Contents/jbr/Contents/Home ./gradlew test buildPlugin
```

```bash
cd plugin && ./gradlew runIde -PopenSolution="/path/to/Some.slnx"
```

While that sandbox Rider is open, apply plugin or engine changes without restarting it:

```bash
cd plugin && JAVA_HOME=~/Applications/Rider.app/Contents/jbr/Contents/Home ./gradlew prepareSandbox_runIde
```

The sandbox spots the new build, unloads the old plugin and loads the new one within a few seconds, including a fresh engine. A "still referenced" warning in the sandbox log is a small memory leak per reload; restart the sandbox now and then if it gets sluggish. Changes to `plugin.xml` (new extensions) may still need a restart.

The plugin builds against the Rider installed at `~/Applications/Rider.app`. Set `RIDER_PATH` to use a different install.

The engine can be driven without Rider. Run `dotnet engine/NoCrunch.Engine/bin/Debug/net10.0/NoCrunch.Engine.dll` and speak JSON-RPC 2.0 (`Content-Length` framing) on stdio. Start with `initialize {solutionPath}`. See `engine/NoCrunch.Engine/Protocol/EngineServer.cs` for the protocol.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

[MIT](LICENSE)
