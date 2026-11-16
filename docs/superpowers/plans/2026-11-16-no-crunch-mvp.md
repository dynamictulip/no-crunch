# No Crunch MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Rider plugin for macOS that reruns tests affected by file changes, at project level, in an isolated mirror of the repo, with a tool window to view results and exclude tests.

**Architecture:** A Kotlin Rider plugin (UI, file-change forwarding, exclusions file) talks JSON-RPC over stdio to a separate .NET 10 engine process. The engine mirrors the repo into `.nocrunch/<Sln>/mirror`, builds dirty projects one by one, and runs MTP test apps in server mode.

**Tech Stack:**
- Engine:
  - C# / .NET 10
  - StreamJsonRpc
  - `Microsoft.Testing.Platform.ServerMode.Client.Sources` (2.5.x)
  - MSTest on MTP for the engine's own tests
- Plugin:
  - Kotlin
  - IntelliJ Platform Gradle Plugin 2.x, targeting Rider 2026.2 (build 262)
  - Gson (bundled with the platform)
  - JUnit 5

**Spec:** `docs/superpowers/specs/2026-11-16-no-crunch-mvp-design.md`

## Global Constraints

**Engine and runtime**
- The engine targets `net10.0` and runs framework-dependent through `dotnet`.
- Supported test projects: `IsTestingPlatformApplication == true`, MTP 2.x hosts only.
- Other test projects show as `unsupported (VSTest)`.
- If the MTP handshake fails, the project shows as `unsupported MTP version`.

**Mirror location and contents**
- Mirror path: `<repoRoot>/.nocrunch/<SolutionName>/mirror/`.
- `repoRoot` is the git root. Without git, it is the solution folder.
- Directories never copied: `bin`, `obj`, `.git`, `.idea`, `.vs`, `TestResults`, `node_modules`, `.nocrunch`.
- Git-ignored files are still copied.

**Mirror safety files**
- Write empty stopper files `Directory.Build.props`, `Directory.Build.targets` and `Directory.Packages.props` into `<repoRoot>/.nocrunch/`.
- Add `.nocrunch/` to `.git/info/exclude`, once only. Never edit `.gitignore`.

**Change handling**
- Build each dirty project with `dotnet build <proj> -p:BuildProjectReferences=false`, in dependency order.
- Wait 300 ms after the last change (debounce).
- A new change cancels the cycle in flight. The next cycle covers the union of dirty projects.
- Run at most `max(1, cores/2)` test projects in parallel.
- A batch of more than 200 changed files triggers a full reconcile, comparing size and mtime.
- Global build files mark every project dirty: `Directory.Build.*`, `Directory.Packages.props`, `global.json`, `NuGet.config`, `.editorconfig`.

**Persisted files**
- Exclusions file: `<SolutionDir>/<SolutionName>.nocrunch.user`, JSON.
- Results file: `.nocrunch/<SolutionName>/results.json`.

**Test identity and ordering**
- A test's identity is the project's repo-relative path, plus the fully qualified type, plus the method.
- If location is missing, fall back to the MTP UID.
- Test projects with previously failing tests run first.

**Protocol and paths**
- Protocol: JSON-RPC 2.0, `Content-Length` framing. `initialize` returns `protocolVersion: 1`. Changes are additive only.
- Mirror paths are rewritten to repo paths before anything is sent to the plugin, including stack traces and file locations.
- Keep portable PDBs. Never set `DebugType=none`.

**Sandbox network access**
- Network commands need `allowed_domains`.
- NuGet: `api.nuget.org`, `*.nuget.org`.
- Gradle: `services.gradle.org`, `downloads.gradle.org`, `plugins.gradle.org`, `repo.maven.apache.org`, `cache-redirector.jetbrains.com`, `*.jetbrains.com`, `*.intellij.net`.
- Java for Gradle: `JAVA_HOME=~/Applications/Rider.app/Contents/jbr/Contents/Home`.

## Review Focus

1. **Branch switch:** a burst of hundreds of changed files, including deletes. Expected: one reconcile, no per-file storm, and the mirror matches the repo afterwards. The test lives in Task 2: `Reconcile_after_branch_switch_matches_repo`.
2. **Save during a build:** a save that arrives mid-build. Expected: the in-flight build is cancelled, no results from the cancelled cycle are shown as current, and the next cycle includes both changes. The test lives in Task 7: `Change_during_cycle_cancels_and_unions`.
3. **Compile error in a shared library:** expected: dependents show "blocked by build failure", unrelated test projects still run, and the compiler output is visible. The test lives in Task 4: `Failed_dependency_blocks_dependents_only`.
4. **Paths containing spaces or non-ASCII characters** (common on macOS). Expected: mirror, build and test run all work. The tests live in Task 2 (`Sync_handles_spaces_and_unicode`) and Task 4, where the sample solution path contains a space.
5. **Test projects without stable location data:** some frameworks report no type or method in node properties. Expected: the test can still be excluded and remains excluded after a rebuild, using its UID. The test lives in Task 6: `Uid_fallback_matches_when_location_missing`.

---

## File structure

```
.gitignore                              bin/ obj/ .gradle/ build/ .nocrunch/ *.user (except samples) .idea/
global.json                             sdk 10.0.101, rollForward latestFeature
NoCrunch.slnx                           engine + engine tests
engine/NoCrunch.Engine/
  NoCrunch.Engine.csproj                Exe, net10.0, InternalsVisibleTo tests
  Program.cs                            stdio JSON-RPC host entry
  Layout/RepoLayout.cs                  repo root / mirror / sln paths
  Layout/PathMap.cs                     repo <-> mirror path + text rewriting
  Workspace/IWorkspaceStrategy.cs
  Workspace/SharedMirrorWorkspace.cs    initial copy, incremental sync, reconcile, stoppers, git exclude
  Workspace/FileFilter.cs               excluded dir rules
  Graph/ProjectInfo.cs
  Graph/IMsBuildQuery.cs, DotnetMsBuildQuery.cs
  Graph/ProjectGraph.cs                 ownership, dirty set, topo order
  Build/IProcessRunner.cs, ProcessRunner.cs
  Build/ProjectBuilder.cs
  Testing/TestModels.cs                 TestId, TestState, TestResultUpdate, ProjectRunOutcome
  Testing/ExclusionSet.cs
  Testing/MtpTestRunner.cs
  Core/IEngineEvents.cs
  Core/ResultStore.cs
  Core/EngineCore.cs                    debounce, cycles, cancel, ordering, pause
  Protocol/EngineServer.cs              StreamJsonRpc target + notifier
engine/NoCrunch.Engine.Tests/           MSTest (MTP) tests, one file per unit
samples/Sample Solution/                note the space (Review Focus 4)
  Sample.slnx
  src/Shop.Core/                        class lib: PriceCalculator
  tests/Shop.Core.Tests/                MSTest MTP, depends on Shop.Core, 2 passing tests
  tests/Shop.E2E.Tests/                 MSTest MTP, 1 test (to be excluded)
  tests/Shop.Other.Tests/               MSTest MTP, no dependency on Shop.Core
plugin/
  settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew + wrapper
  src/main/resources/META-INF/plugin.xml
  src/main/kotlin/com/nocrunch/rider/
    engine/EngineClient.kt              Content-Length JSON-RPC client
    engine/EngineProcess.kt             launch, restart on crash
    engine/EngineMessages.kt            data classes mirroring protocol
    NoCrunchService.kt                  per-project service; owns engine + state
    files/FileChangeForwarder.kt        BulkFileListener -> filesChanged
    files/MirrorFolderExcluder.kt       mark .nocrunch excluded
    exclusions/ExclusionStore.kt        read/write .nocrunch.user
    ui/TestTreeModel.kt                 pure model: nodes, counts, filter
    ui/NoCrunchToolWindowFactory.kt, NoCrunchPanel.kt, DetailsPane.kt
    ui/NoCrunchStatusBarWidgetFactory.kt
    settings/NoCrunchSettings.kt, NoCrunchConfigurable.kt
  src/test/kotlin/...                   EngineClientTest, ExclusionStoreTest, TestTreeModelTest
```

---

### Task 1: Scaffold repo, engine projects and sample solution

**Files:**
- Create: `.gitignore`, `global.json`, `NoCrunch.slnx`, `engine/NoCrunch.Engine/NoCrunch.Engine.csproj`, `engine/NoCrunch.Engine/Program.cs` (temporary `return 0;`), `engine/NoCrunch.Engine.Tests/NoCrunch.Engine.Tests.csproj`, `engine/NoCrunch.Engine.Tests/SmokeTests.cs`
- Create: the sample solution as in the file structure.
  - `PriceCalculator.Total(decimal unit, int qty) => unit * qty`.
  - `Shop.Core.Tests`: `Total_multiplies` and `Total_zero_qty`.
  - `Shop.E2E.Tests`: `Checkout_flow`, which passes.
  - `Shop.Other.Tests`: `Always_passes`.
- Create: `engine/NoCrunch.Engine.Tests/TestSupport/SampleSolution.cs`.

**Interfaces:**
- Produces: `SampleSolution.CopyToTemp() -> string` (path of a temp git-initialised copy of `samples/Sample Solution`, whose own path contains a space). Every integration test uses it.

- [ ] **Step 1: Create the files.** Test projects use `<UseMicrosoftTestingPlatformRunner>`. More precisely, MSTest.Sdk or `MSTest` with `EnableMSTestRunner=true` and `OutputType=Exe`, so that `IsTestingPlatformApplication` evaluates to true. Add `global.json` with `"test": { "runner": "Microsoft.Testing.Platform" }` for both the repo and the sample.
- [ ] **Step 2: Build the sample and run its tests:** `dotnet test --solution "samples/Sample Solution/Sample.slnx"`. Expected: 5 tests pass.
- [ ] **Step 3: Check the property:** `dotnet msbuild "samples/Sample Solution/tests/Shop.Core.Tests/Shop.Core.Tests.csproj" -getProperty:IsTestingPlatformApplication`. Expected: `true`.
- [ ] **Step 4: Build the engine and run its tests:** `dotnet test --solution NoCrunch.slnx`. Expected: 1 smoke test passes.
- [ ] **Step 5: Commit:** `chore: scaffold engine and sample solution`.

### Task 2: Repo layout, path map and shared-mirror workspace

**Files:**
- Create: `Layout/RepoLayout.cs`, `Layout/PathMap.cs`, `Workspace/FileFilter.cs`, `Workspace/IWorkspaceStrategy.cs`, `Workspace/SharedMirrorWorkspace.cs`
- Test: `RepoLayoutTests.cs`, `PathMapTests.cs`, `SharedMirrorWorkspaceTests.cs`

**Interfaces:**
- Produces:
  - `record RepoLayout(string RepoRoot, string SolutionPath, string SolutionName, string NoCrunchDir, string StateDir, string MirrorRoot, bool IsGitRepo)` with `static RepoLayout Resolve(string solutionPath)`. `StateDir` = `.nocrunch/<SolutionName>`.
  - `sealed class PathMap(string repoRoot, string mirrorRoot)`:
    - `string ToMirror(string repoPath)`
    - `string ToRepo(string mirrorPath)`
    - `string RewriteText(string text)`, which replaces every occurrence of the mirror root with the repo root
    - `string RepoRelative(string repoPath)`, using `/` separators
  - `static class FileFilter { bool IsExcluded(string repoRelativePath) }`
  - `interface IWorkspaceStrategy`:
    - `Task InitializeAsync(CancellationToken ct)`
    - `Task<IReadOnlyCollection<string>> SyncAsync(IReadOnlyCollection<string> changedRepoPaths, CancellationToken ct)`, which returns the repo-relative paths that actually changed in the mirror
    - `Task<IReadOnlyCollection<string>> ReconcileAsync(CancellationToken ct)`
    - `string MirrorPathOf(string repoRelativePath)`
  - `SharedMirrorWorkspace(RepoLayout layout, PathMap map) : IWorkspaceStrategy`

- [ ] **Step 1: Write failing tests:**
  - `Resolve_uses_git_root_and_per_solution_mirror`: MirrorRoot = `<root>/.nocrunch/Sample/mirror`.
  - `Resolve_without_git_uses_solution_dir`: `IsGitRepo == false`.
  - `ToRepo_inverts_ToMirror`.
  - `RewriteText_rewrites_stack_trace_paths`.
  - `Initialize_copies_sources_but_not_bin_obj_git`.
  - `Initialize_copies_gitignored_files`.
  - `Initialize_writes_stopper_props_and_git_exclude_once`: run Initialize twice and check `.git/info/exclude` contains `.nocrunch/` exactly once.
  - `Sync_copies_changed_and_deletes_removed`.
  - `Sync_ignores_unchanged_content`: returns empty when bytes are the same.
  - `Sync_handles_spaces_and_unicode`, using the file `src/Café Dir/naïve file.cs`.
  - `Reconcile_after_branch_switch_matches_repo`: modify 50 files, delete 10 and add 10 in the repo, then call `ReconcileAsync`. Assert the returned set has 70 entries and the mirror tree equals the repo tree (minus excluded dirs).
- [ ] **Step 2: Run the tests.** Expected: FAIL (types missing).
- [ ] **Step 3: Implement.**
  - Copy with `File.Copy(overwrite: true)` and keep the source mtime.
  - Reconcile compares (size, mtime) both ways.
  - The git root is found by walking up for `.git`.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit:** `feat(engine): shared mirror workspace`.

### Task 3: Project graph (MSBuild query, ownership, dirty set, order)

**Files:**
- Create: `Graph/ProjectInfo.cs`, `Graph/IMsBuildQuery.cs`, `Graph/DotnetMsBuildQuery.cs`, `Graph/ProjectGraph.cs`, `Build/IProcessRunner.cs`, `Build/ProcessRunner.cs`
- Test: `ProjectGraphTests.cs` (with a fake `IMsBuildQuery`), `DotnetMsBuildQueryTests.cs` (integration, against the sample)

**Interfaces:**
- Produces:
  - `record ProcessResult(int ExitCode, string StdOut, string StdErr)`.
  - `interface IProcessRunner { Task<ProcessResult> RunAsync(string file, IReadOnlyList<string> args, string workingDir, CancellationToken ct) }`. Cancellation kills the process tree.
  - `record ProjectInfo(string RepoRelativePath, string Name, string MirrorProjectPath, bool IsTestProject, bool IsMtp, string? TargetPath, IReadOnlyList<string> ProjectReferences, IReadOnlyList<string> ItemFiles)`. All paths are repo-relative with `/`, except `MirrorProjectPath` and `TargetPath`, which are absolute mirror paths.
  - `interface IMsBuildQuery`:
    - `Task<IReadOnlyList<string>> ListSolutionProjectsAsync(string mirrorSolutionPath, CancellationToken ct)`
    - `Task<ProjectInfo> QueryAsync(string mirrorProjectPath, CancellationToken ct)`
  - `DotnetMsBuildQuery(IProcessRunner runner, PathMap map, string dotnetPath)`:
    - Uses `dotnet sln <sln> list`.
    - Then calls `dotnet msbuild <proj> -getProperty:IsTestingPlatformApplication -getProperty:IsTestProject -getProperty:TargetPath -getProperty:TargetFramework -getProperty:TargetFrameworks -getItem:ProjectReference -getItem:Compile -getItem:None -getItem:Content -getItem:EmbeddedResource -getItem:AdditionalFiles`, which prints JSON.
    - Multi-targeted projects: query again with `-p:TargetFramework=<first TFM>`.
  - `sealed class ProjectGraph`:
    - `static Task<ProjectGraph> LoadAsync(RepoLayout layout, IMsBuildQuery query, CancellationToken ct)`
    - `IReadOnlyCollection<ProjectInfo> Projects`
    - `IReadOnlySet<string> OwnersOf(string repoRelativePath)`
    - `IReadOnlySet<string> DirtyProjects(IEnumerable<string> changedRepoRelativePaths)`
    - `IReadOnlyList<ProjectInfo> InBuildOrder(IEnumerable<string> projectPaths)`
    - `static bool IsGlobalBuildFile(string repoRelativePath)`
    - `static bool IsGraphFile(string repoRelativePath)`: `*.csproj`, `*.props`, `*.targets`, `global.json`, `NuGet.config`, `*.sln`, `*.slnx`.

- [ ] **Step 1: Write failing tests** (fake query; graph Core ← Core.Tests, Other.Tests independent):
  - `OwnersOf_includes_deepest_project_dir`.
  - `OwnersOf_includes_projects_linking_the_file`: a `../Shared/X.cs` item in two projects gives both.
  - `OwnersOf_unowned_file_is_empty`: `docs/readme.md`.
  - `DirtyProjects_adds_transitive_dependents`: a change in Core gives {Core, Core.Tests}.
  - `DirtyProjects_test_file_only_its_project`.
  - `DirtyProjects_global_build_file_marks_all`: `Directory.Build.props` and `.editorconfig`.
  - `InBuildOrder_dependencies_first`.
  - Integration, `Query_reads_sample_projects`: Core.Tests has `IsMtp == true` and a ProjectReference to `src/Shop.Core/Shop.Core.csproj`, and `ItemFiles` contains its test `.cs` file.
- [ ] **Step 2: Run the tests.** Expected: FAIL.
- [ ] **Step 3: Implement.** Owners: the union of item-file membership and the deepest containing project dir. Dirty: a reverse-dependency BFS. Order: a Kahn topological sort.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit:** `feat(engine): project graph and dirty-set computation`.

### Task 4: Per-project builder

**Files:**
- Create: `Build/ProjectBuilder.cs`
- Test: `ProjectBuilderTests.cs` (fake runner), `ProjectBuilderIntegrationTests.cs` (sample in a temp dir with a space in its path)

**Interfaces:**
- Consumes: `ProjectInfo`, `IProcessRunner`, `PathMap`
- Produces:
  - `enum BuildStatus { Succeeded, Failed, Blocked, Cancelled }`
  - `record BuildOutcome(string Project, BuildStatus Status, string Output, string? BlockedBy)`, where `Output` has been rewritten with `PathMap.RewriteText`.
  - `ProjectBuilder(IProcessRunner runner, PathMap map, string dotnetPath)`:
    - `Task<IReadOnlyDictionary<string, BuildOutcome>> BuildAsync(IReadOnlyList<ProjectInfo> inOrder, Func<ProjectInfo, Task>? onStarted, Func<BuildOutcome, Task>? onFinished, CancellationToken ct)`
    - Runs `build <mirrorProj> -p:BuildProjectReferences=false -nologo -v:q -clp:NoSummary`.
    - Runs `dotnet restore <mirrorSln>` first when `restoreNeeded` is set, through `Task RestoreAsync(string mirrorSolutionPath, CancellationToken ct)`.

- [ ] **Step 1: Write failing tests:**
  - `Failed_dependency_blocks_dependents_only`: Core fails, so Core.Tests is Blocked (BlockedBy=Core) and Other.Tests is Succeeded.
  - `Cancellation_marks_remaining_cancelled`.
  - `Output_paths_rewritten_to_repo`.
  - Integration, `Builds_sample_in_mirror_without_touching_repo_bin`: after the build, `<mirror>/tests/Shop.Core.Tests/bin` exists and `<repo>/tests/Shop.Core.Tests/bin` does not.
- [ ] **Step 2: Run the tests.** Expected: FAIL.
- [ ] **Step 3: Implement.** Builds run sequentially in the given order. A project whose reference failed or was blocked is not built and gets marked Blocked.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit:** `feat(engine): per-project builder`.

### Task 5: MTP test runner

**Files:**
- Modify: `NoCrunch.Engine.csproj`. Add `Microsoft.Testing.Platform.ServerMode.Client.Sources` 2.5.x with `PrivateAssets=all`, and set `LangVersion` to latest.
- Create: `Testing/TestModels.cs`, `Testing/MtpTestRunner.cs`
- Test: `MtpTestRunnerIntegrationTests.cs` (sample, built in a mirror via Task 4)

**Interfaces:**
- Produces:
  - `record TestId(string Project, string? Type, string? Method, string Uid)`.
    - `Project` is repo-relative.
    - Equality and key use `(Project, Type, Method)` when Type and Method are both present, and otherwise `(Project, Uid)`.
    - `string Key` is a property.
  - `enum TestState { Discovered, Queued, Running, Passed, Failed, Skipped, Excluded, Stale }`
  - `record TestResultUpdate(TestId Id, string DisplayName, TestState State, double? DurationMs, string? Message, string? StackTrace, string? FilePath, int? Line)`. `FilePath` and `StackTrace` are repo paths.
  - `enum ProjectRunStatus { Completed, Cancelled, Crashed, UnsupportedMtpVersion }`
  - `record ProjectRunOutcome(string Project, ProjectRunStatus Status, string? Error)`
  - `MtpTestRunner(PathMap map)` with `Task<ProjectRunOutcome> RunAsync(ProjectInfo project, Func<TestId, bool> shouldRun, Func<IReadOnlyList<TestResultUpdate>, Task> onUpdates, CancellationToken ct)`.
    - Discovery uses one process and the run uses a fresh one, following the docs sample.
    - Type and method come from the node keys `location.type` and `location.method`.
    - File and line come from `location.file` and `location.line-start`.
    - Final states map as follows: `passed` → Passed, `skipped` → Skipped, and anything else that is not `discovered` or `in-progress` → Failed.
    - Results whose `retry.is-superseded` is true are ignored.
    - Excluded tests (shouldRun false) are reported once with `TestState.Excluded`.
    - A handshake failure or exit code 12 gives `UnsupportedMtpVersion`.

- [ ] **Step 1: Write failing tests:**
  - `Discovers_and_runs_sample_core_tests`: 2 Passed updates, each with Type `Shop.Core.Tests.PriceCalculatorTests` and a FilePath under the repo (not the mirror).
  - `ShouldRun_false_reports_excluded_and_does_not_run`.
  - `Failing_test_reports_message_and_repo_stack`: temporarily edit the mirror copy so a test fails.
  - `Cancellation_returns_Cancelled`.
- [ ] **Step 2: Run the tests.** Expected: FAIL.
- [ ] **Step 3: Implement** using `MtpServerClient.LaunchAsync(project.TargetPath)`, `InitializeAsync`, `DiscoverTestsAsync`, `RunTestsAsync(uids)`, `ExitAsync` and `ShutdownAsync`. Set `MtpServerClientOptions.IsStateful = false`. Batch updates per `TestNodesUpdated` event, and never block the handler.
- [ ] **Step 4: Run the tests.** Expected: PASS. If the location key names differ, log a discovered node's `Node` dictionary, fix the mapping, and note the real key names in a code comment.
- [ ] **Step 5: Commit:** `feat(engine): MTP server-mode test runner`.

### Task 6: Exclusion matching

**Files:**
- Create: `Testing/ExclusionSet.cs`
- Test: `ExclusionSetTests.cs`

**Interfaces:**
- Produces:
  - `record ExclusionRule(string Project, string? Type, string? Method, string? Uid)`
  - `sealed class ExclusionSet(IEnumerable<ExclusionRule> rules)` with `bool IsExcluded(TestId id)` and `static ExclusionSet Empty`.
  - Matching rules:
    - A rule with only `Project` excludes the whole project.
    - `Project` + `Type` excludes the whole class.
    - `Project` + `Type` + `Method` excludes one test.
    - `Project` + `Uid` excludes one test by UID.

- [ ] **Step 1: Write failing tests:**
  - `Project_rule_excludes_all_in_project`
  - `Type_rule_excludes_class_only`
  - `Method_rule_excludes_single_test`
  - `Uid_fallback_matches_when_location_missing`
  - `Rule_survives_new_uid_when_location_present`: same type and method but a different UID, still excluded.
- [ ] **Step 2: Run the tests.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit:** `feat(engine): exclusion matching`.

### Task 7: Engine core: cycles, debounce, cancel, ordering, persistence

**Files:**
- Create: `Core/IEngineEvents.cs`, `Core/ResultStore.cs`, `Core/EngineCore.cs`
- Test: `EngineCoreTests.cs` (fakes for workspace, graph loader, builder and runner, plus a controllable clock), `ResultStoreTests.cs`

**Interfaces:**
- Consumes: everything from Tasks 2–6. Builder and runner are reached through these small interfaces, for testability: `IBuildStep { Task<IReadOnlyDictionary<string, BuildOutcome>> BuildAsync(...) }` and `ITestStep { Task<ProjectRunOutcome> RunAsync(...) }`, with the same signatures as `ProjectBuilder.BuildAsync` and `MtpTestRunner.RunAsync`. Those classes implement them.
- Produces:
  - `enum EngineState { Idle, Syncing, Building, Testing, Paused }`
  - `interface IEngineEvents`:
    - `Task StateChanged(EngineState state, int queuedProjects)`
    - `Task ProjectBuildStarted(string project)`
    - `Task ProjectBuildFinished(BuildOutcome outcome)`
    - `Task TestsDiscovered(string project, IReadOnlyList<TestResultUpdate> tests)`
    - `Task TestResults(IReadOnlyList<TestResultUpdate> updates)`
    - `Task ProjectRunFinished(ProjectRunOutcome outcome)`
    - `Task Log(string level, string message)`
  - `sealed class ResultStore(string path)`:
    - `IReadOnlyDictionary<string, TestResultUpdate> Load()`, where the key is `TestId.Key`
    - `void Save(IEnumerable<TestResultUpdate> latest)`
  - `sealed class EngineCore(...)`:
    - `Task StartAsync(CancellationToken ct)`:
      1. Emit the stored results as `Stale`.
      2. Initialize and reconcile the workspace.
      3. Load the graph.
      4. Run a cycle for the dirty projects plus test projects that have no stored results.
    - `void FilesChanged(IReadOnlyCollection<string> repoPaths)`
    - `void SetExclusions(ExclusionSet set)`
    - `void RunAll()`
    - `void RunTests(IReadOnlyCollection<string> testKeys)`, which ignores exclusions
    - `void Pause()`
    - `void Resume()`
    - `TimeSpan Debounce`, default 300 ms
    - `int MaxParallelTestProjects`

- [ ] **Step 1: Write failing tests:**
  - `Debounces_bursts_into_one_cycle`
  - `Change_during_cycle_cancels_and_unions`: block the fake builder, send change B, and assert that the first cycle's token is cancelled, its results are not emitted as final, and the second cycle builds A∪B.
  - `Burst_over_200_files_triggers_reconcile_not_sync`
  - `Graph_file_change_reloads_graph_and_restores`
  - `Previously_failing_projects_run_first`
  - `Excluded_tests_not_run_but_RunTests_runs_them`
  - `Paused_queues_changes_and_Resume_runs_them`
  - `Startup_emits_stale_then_runs_dirty_and_unknown_projects`
  - `Non_mtp_test_project_reported_unsupported`
  - `ResultStore_roundtrips`
- [ ] **Step 2: Run the tests.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - A single background loop consumes a channel of requests.
  - The dirty set accumulates.
  - Each cycle gets a `CancellationTokenSource` that is cancelled on a new change.
  - Test projects run through `Parallel.ForEachAsync` with `MaxParallelTestProjects`.
  - Save results at the end of each completed cycle.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit:** `feat(engine): scheduling core`.

### Task 8: JSON-RPC protocol host and end-to-end engine test

**Files:**
- Modify: `Program.cs`, `NoCrunch.Engine.csproj` (add StreamJsonRpc)
- Create: `Protocol/EngineServer.cs`
- Test: `EngineServerEndToEndTests.cs`

**Interfaces:**
- Produces the wire protocol, camelCase, with `HeaderDelimitedMessageHandler` and `SystemTextJsonFormatter`:
  - Requests:
    - `initialize({solutionPath, dotnetPath, maxParallel, debounceMs}) -> {protocolVersion: 1, repoRoot, mirrorRoot}`
    - `filesChanged({paths: string[]})`
    - `setExclusions({rules: ExclusionRule[]})`
    - `runAll()`
    - `runTests({keys: string[]})`
    - `pause()`
    - `resume()`
    - `shutdown()`
  - Notifications:
    - `stateChanged {state, queued}`
    - `projectBuildStarted {project}`
    - `projectBuildFinished {project, status, output, blockedBy}`
    - `testsDiscovered {project, tests}`
    - `testResults {updates}`
    - `projectRunFinished {project, status, error}`
    - `log {level, message}`
  - Wire field names for `TestResultUpdate`: `key, project, type, method, uid, displayName, state, durationMs, message, stackTrace, filePath, line`.
- `Program.Main`: serve on stdin/stdout. All logs go to stderr, never stdout.

- [ ] **Step 1: Write failing test** `Edit_core_file_reruns_core_tests_only`:
  1. Launch the engine via `dotnet run --project engine/NoCrunch.Engine` with piped stdio against a temp copy of the sample.
  2. Call `initialize` and wait until `stateChanged Idle` arrives after the initial cycle.
  3. Send `setExclusions` for `tests/Shop.E2E.Tests/Shop.E2E.Tests.csproj`.
  4. Edit `PriceCalculator.cs` so the total is wrong, then send `filesChanged`.
  5. Assert that `projectBuildFinished` arrives for Shop.Core and Shop.Core.Tests only, that `testResults` contains Failed results for Core tests whose `stackTrace` contains no `.nocrunch`, and that there are no results for Other.Tests or E2E.
- [ ] **Step 2: Run the test.** Expected: FAIL.
- [ ] **Step 3: Implement** `EngineServer`, which adapts RPC calls to `EngineCore` and implements `IEngineEvents` by sending notifications.
- [ ] **Step 4: Run all engine tests:** `dotnet test --solution NoCrunch.slnx`. Expected: all pass.
- [ ] **Step 5: Commit:** `feat(engine): JSON-RPC host`.

### Task 9: Plugin scaffold, engine bundling and protocol client

**Files:**
- Create: `plugin/settings.gradle.kts`, `plugin/build.gradle.kts`, `plugin/gradle.properties`, the Gradle wrapper (Gradle 9.x), `plugin.xml`, `engine/EngineMessages.kt`, `engine/EngineClient.kt`, `engine/EngineProcess.kt`
- Test: `EngineClientTest.kt`

**Interfaces:**
- Produces:
  - `class EngineClient(input: InputStream, output: OutputStream)`:
    - `fun <T> request(method: String, params: Any?, type: Class<T>): CompletableFuture<T>`
    - `fun notify(method: String, params: Any?)`
    - `fun onNotification(handler: (method: String, params: JsonObject) -> Unit)`
    - `fun close()`
  - Data classes in `EngineMessages.kt` matching the Task 8 wire names: `TestResultDto`, `BuildFinishedDto`, `ExclusionRuleDto`, `InitializeParams`, `InitializeResult`.
  - `class EngineProcess(dotnet: String, engineDll: Path, onCrash: () -> Unit)` with `fun start(): EngineClient` and `fun stop()`.
  - The engine is published by a Gradle task, `publishEngine`, which runs `dotnet publish engine/NoCrunch.Engine -c Release -o build/engine`. That task is wired into `prepareSandbox`/`buildPlugin` so the output lands at `<plugin>/engine/NoCrunch.Engine.dll`. It's located at runtime via `PluginManagerCore.getPlugin(PluginId("com.nocrunch.rider")).pluginPath`.
- Gradle: `intellijPlatform { rider("2026.2") }` (`useInstaller = false` if required by the Rider artifact), with `sinceBuild = "262"`. Use `JAVA_HOME` = the Rider JBR.

- [ ] **Step 1: Write failing tests:**
  - `writes_content_length_framed_request`
  - `parses_split_and_coalesced_frames`: two messages in one chunk, and one message across three chunks.
  - `routes_notifications_and_completes_responses`
  - `request_fails_when_stream_closes`
- [ ] **Step 2: Run** `./gradlew test` from `plugin/`. Expected: FAIL.
- [ ] **Step 3: Implement.** The client reads on a dedicated thread. Responses complete their futures, notifications go to the handler, and the client never touches the EDT.
- [ ] **Step 4: Run** `./gradlew test buildPlugin`. Expected: tests pass, and the plugin zip contains `engine/NoCrunch.Engine.dll`.
- [ ] **Step 5: Commit:** `feat(plugin): scaffold and engine protocol client`.

### Task 10: Project service, file forwarding, mirror exclusion, exclusions file, settings

**Files:**
- Create: `NoCrunchService.kt`, `files/FileChangeForwarder.kt`, `files/MirrorFolderExcluder.kt`, `exclusions/ExclusionStore.kt`, `settings/NoCrunchSettings.kt`, `settings/NoCrunchConfigurable.kt`. Register them in `plugin.xml`.
- Test: `ExclusionStoreTest.kt`

**Interfaces:**
- Produces:
  - `@Service(Service.Level.PROJECT) class NoCrunchService(project: Project, cs: CoroutineScope)`:
    - `val state: StateFlow<EngineUiState>`
    - `val tests: StateFlow<Map<String, TestResultDto>>`
    - `val builds: StateFlow<Map<String, BuildFinishedDto>>`
    - `fun runAll()`
    - `fun runTests(keys: List<String>)`
    - `fun pause()`
    - `fun resume()`
    - `fun setExcluded(rule: ExclusionRuleDto, excluded: Boolean)`
    - Starts when the solution loads, via a `ProjectActivity`. Restarts the engine on crash, at most 3 times a minute, then shows an error state.
  - `class ExclusionStore(file: Path)`:
    - `fun load(): List<ExclusionRuleDto>` (empty if the file is missing or corrupt, with a logged warning)
    - `fun save(rules: List<ExclusionRuleDto>)`
    - File JSON: `{"version":1,"excluded":[{"project":"...","type":null,"method":null,"uid":null}]}`
  - `FileChangeForwarder` is a `BulkFileListener`. It forwards the paths of all VFS create, delete, move, content-change and rename events under the repo root, excluding `.nocrunch/`, batched every 100 ms.
  - `MirrorFolderExcluder` marks `.nocrunch` excluded in Rider's index. Use Rider's `SolutionExplorer` exclude API, or fall back to `ProjectFileIndex`/`WorkspaceModel` exclusion. Verify manually in Task 12.
  - `NoCrunchSettings`, a `PersistentStateComponent` at project level, holds `debounceMs = 300`, `maxParallel = 0` (0 = cores/2) and `dotnetPath = ""`. An empty path resolves to `PATH`, then `/usr/local/share/dotnet/dotnet`.

- [ ] **Step 1: Write failing tests:**
  - `missing_file_loads_empty`
  - `roundtrip_preserves_rules`
  - `corrupt_file_loads_empty`
  - `file_named_after_solution`: `<SolutionDir>/<Name>.nocrunch.user`
- [ ] **Step 2: Run** `./gradlew test`. Expected: FAIL.
- [ ] **Step 3: Implement** all the files above.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(plugin): engine service, file forwarding, exclusions store`.

### Task 11: Tool window, details pane, status bar

**Files:**
- Create: `ui/TestTreeModel.kt`, `ui/NoCrunchToolWindowFactory.kt`, `ui/NoCrunchPanel.kt`, `ui/DetailsPane.kt`, `ui/NoCrunchStatusBarWidgetFactory.kt`. Register them in `plugin.xml`.
- Test: `TestTreeModelTest.kt`

**Interfaces:**
- Produces:
  - `TestTreeModel`, a pure function of (tests, builds, exclusions):
    - `fun build(tests: Collection<TestResultDto>, builds: Map<String, BuildFinishedDto>, rules: List<ExclusionRuleDto>, failuresOnly: Boolean): List<ProjectNode>`
  - The node types, each with `excluded: Boolean` and `counts: Counts(passed, failed, other)`:
    - `ProjectNode(path, name, buildStatus, children: List<ClassNode>)`
    - `ClassNode(type, children: List<TestNode>)`
    - `TestNode(dto)`
- UI behaviour:
  - Tree layout: project → class → test, each with a checkbox (`CheckboxTree`). Toggling a checkbox calls `NoCrunchService.setExcluded`.
  - Toolbar: Pause/Resume, Run all, Failures only, Run selected (sends `runTests` with selected keys, including excluded tests), Settings.
  - Details pane: message plus stack trace through `ConsoleView` with file hyperlinks. Selecting a project with a failed build shows its build output.
  - Double-click opens the file with `OpenFileDescriptor(filePath, line-1)`.
  - Status bar widget text: `No Crunch: ✔`, `✖ N`, `building…` or `paused`. Clicking it activates the tool window.
  - All UI updates are collected from the `StateFlow`s on `Dispatchers.EDT`.

- [ ] **Step 1: Write failing tests:**
  - `groups_by_project_then_class`
  - `parent_exclusion_marks_children_excluded`
  - `counts_roll_up`
  - `failures_only_keeps_failed_and_ancestors`
  - `build_failed_project_has_status`
  - `uid_only_tests_grouped_under_unknown_class`
- [ ] **Step 2: Run** `./gradlew test`. Expected: FAIL.
- [ ] **Step 3: Implement** the model and the Swing UI.
- [ ] **Step 4: Run** `./gradlew test buildPlugin`. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(plugin): tool window and status bar`.

### Task 12: Manual verification in Rider and README

**Files:**
- Create: `README.md`, covering install from zip, requirements (.NET 10 SDK, MTP test projects), the `.dockerignore` note, known gaps (Verify/`ShouldMatchApproved`, VSTest, unsaved buffers) and the dev workflow.

- [ ] **Step 1: Run** `./gradlew runIde` and open `samples/Sample Solution/Sample.slnx`. Expected:
  - The tool window shows 3 test projects and the tests go green.
  - `.nocrunch/` appears in neither Go to File nor `git status`.
- [ ] **Step 2: Untick Shop.E2E.Tests.** Expected: `Sample.nocrunch.user` is written, and the project shows as excluded.
- [ ] **Step 3: Break `PriceCalculator.Total` and save.** Expected:
  - Within a few seconds, only the Core tests go red. The details pane shows a stack with repo paths, and double-click opens the test.
  - Rider stays responsive while this happens.
- [ ] **Step 4: Introduce a compile error in Shop.Core.** Expected: Core.Tests shows "blocked by build failure", and Other.Tests is unaffected.
- [ ] **Step 5: Run `git switch -c tmp && git switch -`, or touch 300 files.** Expected: one reconcile, and the results stay correct.
- [ ] **Step 6: Restart the IDE.** Expected: stale results show immediately, then refresh.
- [ ] **Step 7: Fix any issues found** (each with a regression test where feasible), then commit: `docs: README; fix: issues from manual verification`.
