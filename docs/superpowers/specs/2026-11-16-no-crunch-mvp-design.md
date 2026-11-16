# No Crunch — MVP design

Date: 2026-11-16
Status: draft, awaiting approval

## Intent

NCrunch gives fast, continuous, background test feedback in .NET but does not run on macOS. Rider's built-in continuous testing is the obvious alternative, but in practice it rarely runs, is slow when it does, and blocks other work in the IDE.

No Crunch is a Rider plugin for macOS that continuously and quickly reruns the tests affected by your changes, in an isolated copy of the repo, without ever blocking the IDE. It is built as a full plugin because it will grow (coverage-based impact detection, NCrunch-style line markers).

## Goals

- Rerun affected tests automatically shortly after a file is saved.
- "Affected" covers both changed production code and changed test code.
- Build and run in isolation from the working tree (NCrunch-style), so Rider's own builds and `bin`/`obj` are never touched or locked.
- Never block or noticeably slow down Rider.
- A good GUI to see results and to exclude chosen tests (e.g. E2E tests) from automatic runs.
- Structure that can grow into coverage-based impact detection, per-project workspaces and line markers without a rewrite.

## Non-goals (MVP)

- Coverage-based (per-test) impact detection. MVP uses project-level impact.
- Building from unsaved editor buffers. MVP triggers on save.
- VSTest-based test projects. MVP supports Microsoft.Testing.Platform (MTP) only.
- Exclusion by category/trait.
- Per-project workspaces (NCrunch-style "declared files only" builds). MVP uses one shared mirror, behind an interface designed to accept per-project workspaces later.
- Path mapping for `[CallerFilePath]`-based tools (Verify, Shouldly `ShouldMatchApproved`). Known gap: their `.received` files land in the mirror.
- Line/coverage markers, performance metrics, distributed processing, Windows/Linux.

## Anti-goals

- Anything that makes Rider wait: no heavy work on Rider's UI thread or in Rider's backend process.
- Silently skipping tests that should have rerun. Coarse but correct beats precise but leaky.
- Writing into the user's working tree (apart from the `.nocrunch/` folder, the `.nocrunch.user` file and the local git exclude entry).
- Showing stale results as current: a new change cancels and restarts the in-flight cycle.

## Constraints and environment

- macOS, Rider 2026.2 (build 262.x; bundled JBR requires Java 25). No system Java: Gradle runs on Rider's bundled JBR (`~/Applications/Rider.app/Contents/jbr/Contents/Home`).
- .NET SDK 10 installed (plus 6–9). The engine targets .NET 10 and runs framework-dependent via `dotnet`.
- Test projects use MTP (MSTest, xUnit v3, NUnit or TUnit on MTP). Driven through MTP server mode (`--server`, JSON-RPC) using Microsoft's source-only client package `Microsoft.Testing.Platform.ServerMode.Client.Sources` (MTP 2.4+).

## Approaches considered

### Impact detection
1. **Project-level (chosen for MVP).** A change in project X reruns all non-excluded tests in X (if it is a test project) and in every test project that transitively depends on X. Simple, never misses.
2. **Coverage mapping (chosen for later).** Instrument mirror builds, record which methods each test executes, rerun only the tests that hit the changed code. Precise, biggest piece of work.
3. Static references via Roslyn. Rejected: misses interface/DI dispatch, so it silently skips tests.

### Isolation layout
- **Copy, not symlink (chosen).** Builds see a consistent snapshot, tests cannot write through into the repo, and future unsaved-buffer builds need real files anyway. Symlinks only win on first-time mirroring of huge repos.
- **Shared mirror (chosen for MVP)** vs **per-project workspaces** (NCrunch-style, chosen for later). Per-project workspaces catch poor build configuration (undeclared files, cross-project `bin` reach-ins, build-order reliance) but are the riskiest piece. Both sit behind `IWorkspaceStrategy`, so switching later only replaces the sync code. Switching is expected to surface a batch of failures; that is the point.

### Plugin structure
1. **Kotlin frontend plugin + separate C# engine process over JSON-RPC (chosen).** Heavy work can never block Rider; the engine is testable with plain `dotnet test`; the MTP client is C#; coverage instrumentation later lives in the engine.
2. Kotlin + ReSharper backend plugin. Rejected: heavy SDK setup pinned to the Rider version, slow dev loop, and heavy work in Rider's backend process is what makes the built-in tool block.
3. Kotlin only, shelling out to `dotnet`. Rejected: would need to reimplement the MTP JSON-RPC client in Kotlin.

## Design

### Components

```
Rider (Kotlin plugin)                         NoCrunch.Engine (.NET 10 process)
 ├─ Save listener (VFS/document events)  ──►   ├─ Protocol (JSON-RPC over stdio)
 ├─ Tool window (test tree, details)     ◄──   ├─ Solution model + project graph
 ├─ Status bar widget                          ├─ IWorkspaceStrategy (SharedMirror)
 ├─ Exclusions store (.nocrunch.user)          ├─ Scheduler (debounce, cancel, order)
 └─ Engine process manager                     ├─ Builder (dotnet build per project)
                                               └─ Test runner (MTP server-mode client)
```

The plugin starts one engine process per open solution and restarts it if it crashes. The engine is shipped inside the plugin zip.

### Mirror (shared, MVP)

- Location: `<git repo root>/.nocrunch/<SolutionName>/mirror/`, mirroring the repo layout. Repo root, not solution folder, so `Directory.Build.props`, `global.json`, `NuGet.config` above the `.sln` are included. One mirror per solution, so two solutions from the same repo open at once never share a mirror. If the solution is not in a git repo, the solution folder is the root and the git-exclude step is skipped. *(Builder-check call.)*
- **What counts as a change:** any change to files under the repo root, not just Rider saves. That includes branch switches, pulls, stashes, other editors and AI agents, generators, and Finder deletes. The plugin listens to all IntelliJ VFS events (which also pick up external changes on refresh and focus) and forwards them in batches. A burst of more than about 200 files, such as a branch switch, triggers a full reconcile: the engine compares the mirror with the repo by size and modification time and copies the differences, instead of applying each event one by one. *(Builder-check call: the "no stale results" anti-goal requires it.)*
- Initial sync copies everything except `bin`, `obj`, `.git`, `.idea`, `.vs`, `TestResults`, `node_modules`, `.nocrunch`, and **git-ignored directories**. These are asked from git with `git ls-files --others --ignored --directory` and refreshed whenever a `.gitignore` changes. Git-ignored *files* are still copied (tests may need e.g. `appsettings.Development.json`). *(Amended 2026-11-16 after first use: mirroring ignored caches such as `.gradle/` or IDE sandboxes copied hundreds of MB.)*
- Incremental sync on save: copy changed files; propagate deletes and renames.
- Safety:
  - On first run the plugin adds `.nocrunch/` to `.git/info/exclude` (local only; never edits `.gitignore`).
  - The plugin marks `.nocrunch/` excluded in Rider so it is not indexed or searched.
  - Empty stopper `Directory.Build.props`, `Directory.Build.targets` and `Directory.Packages.props` are written to `.nocrunch/` so props files that chain upward (`GetPathOfFileAbove`) cannot import the real repo's files.
  - SDK projects already skip dot-folders by default, so a project at the repo root will not compile the mirror.
  - `.dockerignore` is left to the user (documented in the README).

### Project graph

For each project in the solution, the engine queries MSBuild in the mirror (`dotnet msbuild <proj> -getItem:ProjectReference -getProperty:IsTestingPlatformApplication,IsTestProject,TargetPath,TargetFramework,TargetFrameworks`) and builds a dependency graph. It refreshes on changes to any `*.csproj`, `*.props`, `*.targets`, `global.json` or `NuGet.config`, and runs `dotnet restore` in the mirror when needed.

Test project = `IsTestingPlatformApplication == true`. Test projects not on MTP are shown in the tree as "unsupported (VSTest)" and skipped. The MVP targets MTP 2.x hosts. If the server-mode handshake with an older host fails, that project is shown as "unsupported MTP version" rather than failing the cycle. *(Builder-check call.)*

### What runs after a change

1. Changed files are mapped to the projects that own them. A file's owners are every project whose evaluated items include it (Compile, None, Content, EmbeddedResource, AdditionalFiles, read with `-getItem` during the project-graph query, so linked and shared files are covered), plus the deepest project whose directory contains it. A file with no owner (docs, scripts, `.github/`) is synced to the mirror but triggers no rebuild. Changes to repo-wide build files (`Directory.Build.*`, `Directory.Packages.props`, `global.json`, `NuGet.config`, `.editorconfig`) mark every project dirty. *(Builder-check call.)*
2. Dirty set = owning projects + all transitive dependents.
3. Each dirty project is built alone, in dependency order, with `dotnet build <proj> -p:BuildProjectReferences=false`. A failed project marks its dependents as "blocked by build failure" without stopping unrelated projects. The MSBuild/Roslyn build servers are kept warm.
4. Each dirty test project that built successfully is discovered (MTP server mode) and then has all of its non-excluded tests run by UID. Test projects run in parallel (default: half the CPU cores, configurable).
5. Ordering: within the cycle, test projects containing previously failing tests run first.
6. Debounce: about 300 ms after the last save. A save during a cycle cancels in-flight builds and test runs (MTP cancellation) and starts a new cycle with the union of dirty projects.

**Startup and restarts.** The mirror, its build outputs and the last test results (`.nocrunch/<SolutionName>/results.json`) survive Rider restarts. When the solution opens:
1. The tree shows the last results, marked stale.
2. The engine reconciles the mirror with the repo, which picks up anything that changed while Rider was closed.
3. It runs the normal cycle for the dirty projects, plus any test project that has no stored results.

The very first open does a full sync, build and run. MSBuild's incremental build keeps later rebuilds cheap. *(Builder-check call.)*

### Exclusions

- Saved in `<SolutionName>.nocrunch.user` next to the `.sln` (JSON). The user decides whether to commit or ignore it.
- Granularity: project, class, or single test, toggled with checkboxes in the tree. Excluding a parent excludes its children.
- Tests are identified by project (repo-relative path) + fully qualified type + method name, as reported by MTP node location properties. This is stable across rebuilds, unlike display names. Fallback when location is missing: the MTP UID.
- Excluded tests are never run automatically but can be run manually from the tool window.

### Tool window

```
No Crunch  [⏸ Pause] [▶ Run all] [Failures only] [⚙]   ● Building… 3 queued
──────────────────────────────────────────────────────────────────────
▾ ☑ Orders.Tests                         ✔ 41  ✖ 1
   ▾ ☑ OrderServiceTests
        ☑ ✔ Calculates_total              12ms
        ☑ ✖ Applies_discount              8ms   ← select: message + stack
▾ ☐ Orders.E2E.Tests        (excluded)    – 18
   ▸ ☐ CheckoutFlowTests
```

- Tree: project → class → test, with state icons (pass, fail, running, queued, stale, excluded, build failed).
- Details pane: failure message, stack trace with clickable file:line links. Paths in the mirror are mapped back to the repo before display.
- Double-click a test to open its source.
- Toolbar: pause/resume, run all, failures only filter, run selected (works on excluded tests too), open settings.
- Build failures show per project with the compiler output.
- Status bar widget: green / red (with failure count) / building / paused. Clicking it opens the tool window.

### Engine protocol

JSON-RPC 2.0 over the engine's stdin/stdout, `Content-Length` framing (LSP style). The engine uses StreamJsonRpc. The plugin uses a small hand-written client on the IntelliJ-bundled JSON library. Versioned (`initialize` returns a protocol version).

- Plugin → engine: `initialize(solutionPath, repoRoot, settings)`, `filesChanged(paths)`, `setExclusions(...)`, `runAll()`, `runTests(ids)`, `pause()`/`resume()`, `shutdown()`.
- Engine → plugin (notifications): `stateChanged`, `projectBuildStarted/Finished`, `testsDiscovered`, `testResults` (batched), `log`.
- New notification types must be additive, so later features (coverage, line markers) extend the protocol without breaking it.

### Settings (MVP)

Debounce delay, max parallel test projects, and the path to `dotnet` (default: Rider's configured .NET CLI, otherwise `PATH`). Stored in Rider project settings.

## Future: NCrunch-style line markers (and why this design allows them)

- **Data:** per-test line coverage collected in the engine. The plan is to instrument mirror build outputs (IL rewriting, e.g. Mono.Cecil) plus a small MTP in-process extension that marks test start/end, so hits are attributed to individual tests. The same data drives coverage-based impact detection, so it is one feature serving two purposes.
- **Display:** the Kotlin frontend draws gutter markers with range highlighters on the editor markup model (green = covered by passing tests only, red = covered by a failing test, grey = not covered). Rider editors live in the frontend, so no ReSharper backend is needed. Highlighters move with edits until the next run.
- **What the MVP must do to keep this open:**
  - Keep portable PDBs in mirror builds.
  - Treat mirror ↔ repo path mapping as one first-class function.
  - Keep the protocol additive.

## Repo layout

```
engine/NoCrunch.Engine/          C# engine (console app)
engine/NoCrunch.Engine.Tests/    engine tests (MTP)
plugin/                          Kotlin Rider plugin (IntelliJ Platform Gradle Plugin 2.x)
samples/SampleSolution/          small MTP solution used by integration tests and manual testing
docs/
```

## Testing

- Engine unit tests: mirror sync, project graph, dirty-set computation, scheduler cancellation, exclusion matching.
- Engine integration tests against `samples/SampleSolution` (a library, a test project that depends on it, and an "E2E" test project): edit a file in the sample, then assert the right projects rebuild and the right tests run.
- Plugin: light unit tests for the protocol client and exclusions file; manual verification with `runIde` against the sample solution.

## Left to the builder

- Exact JSON shape of `.nocrunch.user` and the protocol messages.
- Icons, colours and exact layout of the tool window.
- Handling multi-targeted test projects (suggested: use the first or highest target framework the installed runtime supports, and show which).
- Whether to keep one MTP server process per test project alive between cycles (only if `MultiRequestSupport` is reported and the test assembly has not changed) or start a fresh one each cycle. A fresh one is always correct after a rebuild.
- Logging and diagnostics.
