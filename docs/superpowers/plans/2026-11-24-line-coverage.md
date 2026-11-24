# Line Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The engine collects line coverage with one pass per test project (a snapshot per test class, plus an alone-rerun per failing test), and the plugin draws production-line dots from it.

**Architecture:** A new `Coverage/` area in the engine copies each test project's build output, instruments the production DLLs in the copy against a `dotnet-coverage` collector session, and has `MtpTestRunner` run tests class by class on one host, snapshotting between classes. Pure code (`CoverageBook`) merges the passes into per-file line states; `EngineCore` sends them as an additive `coverageUpdated` notification. The plugin keeps the latest map and `EditorMarkerPainter` draws it, only on files whose content still matches.

**Tech Stack:** C# / .NET 10 engine (MSTest, `Microsoft.Testing.Platform.ServerMode.Client`, StreamJsonRpc), `dotnet-coverage` CLI 18.x, Kotlin / IntelliJ Platform plugin (JUnit 5).

**Spec:** `docs/superpowers/specs/2026-11-24-line-coverage-design.md`. Read it first; the plan only records decisions the spec leaves to the builder.

## Global Constraints

- `dotnet-coverage` 18.0 or newer; found on `PATH` or in `~/.dotnet/tools`. If missing or older, coverage stays off and tests still run exactly as today.
- Static instrumentation only (`instrument --session-id`); dynamic instrumentation does not work on arm64 and must not be used. Must work on osx-arm64.
- Coverage never touches the mirror's build outputs: instrument a **copy** of the test project's output folder, under `<RepoLayout.StateDir>/coverage/`.
- `coverageUpdated` and `coverageStatus` are additive protocol notifications: `ProtocolVersion` stays 1.
- Paths in coverage data are mirror paths: translate with `PathMap.ToRepo`; drop files outside the repo and generated sources under `obj/` or `.nocrunch/`.
- A coverage failure never fails the test run or changes a reported test result. A pass is all or nothing: a failed or cancelled pass contributes nothing and leaves the previous data in place, shown stale.
- "No marker beats a wrong marker": a line is "not covered" only when every test project whose reference closure contains the file's production project has a successful pass; lines hit by a class with an unrefined failure get no marker.
- Content hash of a source file: SHA-256, lower-case hex, of the text read as UTF-8 (BOM dropped) with `\r\n` replaced by `\n`.
- Coverage markers use the existing shapes and `Palette`; every state has a hollow stale variant; the existing toolbar toggle controls them.
- Engine paths follow existing style: `engine/NoCrunch.Engine/...` with tests in `engine/NoCrunch.Engine.Tests/...` (MSTest); plugin tests are JUnit 5 under `plugin/src/test/kotlin/com/nocrunch/rider/`.
- Engine tests: `dotnet test engine/NoCrunch.Engine.Tests`. Plugin tests: `./gradlew test` from `plugin/` with `JAVA_HOME=~/Applications/Rider.app/Contents/jbr/Contents/Home`.

## Review Focus

Each line has its test in the owning task.

1. A source file edited after coverage was collected (hash mismatch, or CRLF versus LF) must show no dots, not shifted ones. Tasks 1 and 12.
2. `dotnet-coverage` missing, too old, or a project with no PDBs or not MTP: plain run, no crash, and no "not covered" dots for assemblies those projects reference. Tasks 2, 4, 6, 8.
3. A cycle cancelled mid-coverage must leave no partial merge and no orphaned collector process. Tasks 5, 7.
4. Every queued test runs exactly once when split into class slices, including tests with no class (`Id.Type` null), theory rows sharing a UID, and tests in several classes. Task 6.
5. A failing test that passes on its alone-rerun (flaky) must not turn lines red, and its rerun must not change the reported result. Tasks 5, 6.

---

### Task 1: Parse cobertura and hash file content

**Files:**
- Create: `engine/NoCrunch.Engine/Coverage/CoberturaParser.cs`, `engine/NoCrunch.Engine/Coverage/ContentHash.cs`
- Test: `engine/NoCrunch.Engine.Tests/Coverage/CoberturaParserTests.cs`, `engine/NoCrunch.Engine.Tests/Coverage/ContentHashTests.cs`

**Interfaces:**
- Produces:
  - `public sealed record LineSet(IReadOnlySet<int> Executable, IReadOnlySet<int> Hit)` (1-based lines; `Hit` is a subset of `Executable`).
  - `internal static class CoberturaParser { public static IReadOnlyDictionary<string, LineSet> Parse(string coberturaXml); }` keyed by absolute file path as the report names it.
  - `internal static class ContentHash { public static string Of(string text); public static string OfFile(string path); }`

- [ ] **Step 1: Write failing tests.** `CoberturaParserTests`: `Hit_lines_are_those_with_hits_above_zero` (two classes in one file merge their lines; a line seen with hits 0 in one class and 3 in another is hit), `Relative_filenames_resolve_against_the_first_source`, `Empty_report_gives_empty_map`. `ContentHashTests`: `Of_a_b_newline_is_the_known_digest` (`"a\nb\n"` gives `911169ddaaf146aff539f58c26c489af3b892dff0fe283c1c264c65ae5aa59a2`), `Crlf_and_lf_hash_the_same` (`"a\r\nb\n"`), `OfFile_ignores_a_utf8_bom`.
- [ ] **Step 2: Run to verify failure.** `dotnet test engine/NoCrunch.Engine.Tests --filter "FullyQualifiedName~Coverage"`. Expected: compile failure, types missing.
- [ ] **Step 3: Implement** both types. Parse with `System.Xml.Linq` over `//class`, reading each `class`'s `filename` and its `lines/line` `number` and `hits`.
- [ ] **Step 4: Run to verify they pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(engine): parse cobertura reports and hash file content`.

---

### Task 2: CoverageBook, the merge rules

**Files:**
- Create: `engine/NoCrunch.Engine/Coverage/CoverageBook.cs`
- Test: `engine/NoCrunch.Engine.Tests/Coverage/CoverageBookTests.cs`

**Interfaces:**
- Consumes: `LineSet` (Task 1).
- Produces (all in `NoCrunch.Engine.Coverage`):
  ```csharp
  public sealed record ClassCoverage(string TestClass, IReadOnlyDictionary<string, LineSet> Files); // keys repo-relative
  public sealed record TestProjectPass(
      string TestProject,
      IReadOnlyList<ClassCoverage> Classes,
      IReadOnlyDictionary<string, IReadOnlySet<int>> FailingHit,   // repo-relative file -> lines hit by refined failing tests
      IReadOnlySet<string> UnrefinedFailingClasses,
      IReadOnlyDictionary<string, string> FileHashes,             // repo-relative file -> ContentHash
      IReadOnlySet<string> Assemblies,                            // repo-relative production project paths instrumented
      bool Stale = false);
  public sealed record FileMarkup(string Path, string Hash, bool Stale,
      IReadOnlyList<int> NotCovered, IReadOnlyList<int> Passing, IReadOnlyList<int> Failing); // Path repo-relative here; sorted 1-based lines
  public sealed class CoverageBook
  {
      public void Replace(TestProjectPass pass);                                   // replaces that test project's pass
      public bool MarkStale(IReadOnlySet<string> staleTestProjects);               // true if any pass changed
      public IReadOnlyList<FileMarkup> Markup(Func<string, IReadOnlySet<string>> ownersOf,
                                              Func<string, IReadOnlySet<string>> testProjectsCovering);
      public IReadOnlyList<TestProjectPass> All();
      public void Restore(IEnumerable<TestProjectPass> passes);                    // restored passes are marked Stale
  }
  ```
  `ownersOf(file)` = production projects owning the file (`ProjectGraph.OwnersOf`); `testProjectsCovering(prodProject)` = every test project, MTP or not, whose reference closure contains it.

- [ ] **Step 1: Write failing tests** (one per rule, small hand-built passes; no engine graph needed):
  - `Line_hit_by_passing_class_is_passing`; `Line_in_failing_hit_is_failing_even_if_also_hit_by_passing`.
  - `Executable_line_hit_by_nothing_is_not_covered_when_all_covering_test_projects_have_a_pass`.
  - `Unhit_line_gets_no_marker_while_a_covering_test_project_has_no_pass` (second test project, never passed, covers the assembly).
  - `Unhit_line_gets_no_marker_when_the_assembly_was_not_instrumented_in_a_pass` (pass.Assemblies lacks the owner).
  - `Lines_hit_by_a_class_with_an_unrefined_failure_get_no_marker_unless_in_failing_hit`.
  - `Replace_swaps_one_test_projects_contribution_and_keeps_the_others`.
  - `Markup_is_stale_when_any_contributing_pass_is_stale`; `MarkStale_returns_false_when_nothing_changes`; `Replace_clears_stale`.
  - `File_is_omitted_when_contributing_passes_disagree_on_its_hash`.
  - `Restore_marks_passes_stale`.
- [ ] **Step 2: Run to verify failure.** `dotnet test engine/NoCrunch.Engine.Tests --filter "FullyQualifiedName~CoverageBook"`. Expected: compile failure.
- [ ] **Step 3: Implement** `CoverageBook`, guarded by a lock. Per file: `passing` = hit by any class; `failing` = union of `FailingHit`; unknown = lines hit by a class in a pass's `UnrefinedFailingClasses` that are not in `failing`; precedence failing, then unknown (no marker), then passing, then not-covered (executable lines hit by nothing, drawn only under the Global Constraints rule).
- [ ] **Step 4: Run to verify they pass.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(engine): CoverageBook merges test project passes into line states`.

---

### Task 3: Persist the coverage book

**Files:**
- Create: `engine/NoCrunch.Engine/Coverage/CoverageStore.cs`
- Test: `engine/NoCrunch.Engine.Tests/Coverage/CoverageStoreTests.cs`

**Interfaces:**
- Consumes: `TestProjectPass` (Task 2).
- Produces: `public sealed class CoverageStore(string path) { public IReadOnlyList<TestProjectPass> Load(); public void Save(IEnumerable<TestProjectPass> passes); }`. Same atomic write (`.tmp` then move) and tolerant load as `ResultStore` (`engine/NoCrunch.Engine/Core/ResultStore.cs`).

- [ ] **Step 1: Write failing tests:** `Round_trips_a_pass` (every field, including sets and dictionaries), `Missing_file_loads_empty`, `Corrupt_file_loads_empty`.
- [ ] **Step 2: Run to verify failure.** `--filter "FullyQualifiedName~CoverageStore"`. Expected: compile failure.
- [ ] **Step 3: Implement** with `System.Text.Json`; store sets as arrays, via a small DTO record if the records don't round-trip directly.
- [ ] **Step 4: Run to verify they pass.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(engine): persist coverage passes`.

---

### Task 4: Protocol, events and options

**Files:**
- Modify: `engine/NoCrunch.Engine/Core/IEngineEvents.cs`, `engine/NoCrunch.Engine/Protocol/EngineServer.cs` (`InitializeParams`, notifications)
- Test: `engine/NoCrunch.Engine.Tests/Protocol/EngineServerTests.cs`

**Interfaces:**
- Consumes: `FileMarkup` (Task 2).
- Produces:
  - `public sealed record CoverageAvailability(bool Available, string? Message)` in `NoCrunch.Engine.Core`.
  - On `IEngineEvents`, as default interface methods returning `Task.CompletedTask` (so existing fakes keep compiling): `Task CoverageUpdated(IReadOnlyList<FileMarkup> files)` (**complete** current set, replaces the plugin's map; `Path` is the absolute repo path here) and `Task CoverageStatus(CoverageAvailability availability)`.
  - `InitializeParams` gains a trailing `bool? Coverage = null` (absent means on).
  - Notifications: `coverageUpdated { files: [{ path, hash, stale, notCovered, passing, failing }] }`, `coverageStatus { available, message }`.

- [ ] **Step 1: Write failing tests** following `EngineServerTests`' existing style: `CoverageUpdated_is_sent_as_a_coverageUpdated_notification` (camelCase fields and 1-based line arrays), `CoverageStatus_is_sent_as_a_coverageStatus_notification`, `Initialize_accepts_a_missing_coverage_option`.
- [ ] **Step 2: Run to verify failure.** `--filter "FullyQualifiedName~EngineServer"`. Expected: the new tests fail.
- [ ] **Step 3: Implement** the interface additions and the two `Notify` calls in `EngineServer`.
- [ ] **Step 4: Run the whole engine suite.** `dotnet test engine/NoCrunch.Engine.Tests`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(engine): coverageUpdated and coverageStatus notifications`.

---

### Task 5: The dotnet-coverage tool and session

**Files:**
- Create: `engine/NoCrunch.Engine/Coverage/DotnetCoverageTool.cs`, `engine/NoCrunch.Engine/Coverage/ICoverageSession.cs`
- Test: `engine/NoCrunch.Engine.Tests/Coverage/DotnetCoverageToolTests.cs`

**Interfaces:**
- Consumes: `IProcessRunner` (`engine/NoCrunch.Engine/Build/IProcessRunner.cs`).
- Produces:
  ```csharp
  internal interface ICoverageSession : IAsyncDisposable
  {
      Task InstrumentAsync(string dllPath, CancellationToken ct);
      /// snapshot --reset to a temp file, merge it to cobertura, delete the temp file, return the XML text.
      Task<string> SnapshotCoberturaAsync(CancellationToken ct);
  }
  internal interface ICoverageTool { Task<ICoverageSession> StartSessionAsync(string workDir, CancellationToken ct); }
  internal sealed record ToolInfo(string Path, Version Version);
  internal sealed class DotnetCoverageTool(ToolInfo info, IProcessRunner runner) : ICoverageTool
  {
      public static Task<ToolInfo?> DetectAsync(IProcessRunner runner, string? homeDir, string? pathVariable, CancellationToken ct);
      public static Version? ParseVersion(string versionOutput);   // "dotnet-coverage v18.10.0.0 [osx-arm64 - .NET 8.0.8]" -> 18.10.0.0
  }
  ```
  `DetectAsync` returns null when no executable is found or the version is below 18.0.

- [ ] **Step 1: Write failing tests:** `ParseVersion_reads_the_banner`, `ParseVersion_returns_null_for_garbage`, `Detect_returns_null_when_not_found`, `Detect_returns_null_below_18` (fake `IProcessRunner`), `Detect_prefers_PATH_then_dotnet_tools_dir` (temp directories holding a stub file).
- [ ] **Step 2: Run to verify failure.** `--filter "FullyQualifiedName~DotnetCoverageTool"`. Expected: compile failure.
- [ ] **Step 3: Implement** `ParseVersion` and `DetectAsync`.
- [ ] **Step 4: Verify the collector can run as an engine-owned foreground child.** Manually, in a scratch dir: `dotnet-coverage collect --server-mode --session-id T1 -f cobertura -o out.xml` (no `--background`); from another shell `dotnet-coverage instrument --session-id T1 <a dll>`, `dotnet-coverage snapshot T1 --reset -o a.coverage`, `dotnet-coverage merge -f cobertura -o a.xml a.coverage`, `dotnet-coverage shutdown T1`. Expected: all succeed and the server exits on `shutdown`. If the foreground server does not accept commands, or does not print anything saying it is ready, fall back to `--background` and run it with all three stdio streams redirected to files (a previous attempt that piped `--background` output hung because the background child kept the pipe open). Record which form works in a comment on `StartSessionAsync`.
- [ ] **Step 5: Implement** `DotnetCoverageTool.StartSessionAsync` and the session class. The session starts the collector as a child process the engine owns (killed on dispose and on cancellation), waits until `snapshot` of an empty session succeeds before returning, uses a unique session id, and deletes its work directory on dispose. `SnapshotCoberturaAsync` is serialised by a lock.
- [ ] **Step 6: Write the real-tool test** `Session_instruments_snapshots_and_cleans_up` against the sample's `Shop.Catalog.dll` (build it via `MirroredSample.BuildAllAsync`, copy to a temp dir): after `InstrumentAsync` and running nothing, `SnapshotCoberturaAsync` returns parseable XML; after `DisposeAsync` no collector process remains (`Process.GetProcessesByName`-style check on the child's id). Start it with `if (await DetectAsync(...) is null) Assert.Inconclusive("dotnet-coverage not installed")`.
- [ ] **Step 7: Run.** `--filter "FullyQualifiedName~DotnetCoverageTool"`. Expected: PASS, or the real-tool test Inconclusive if the tool is missing.
- [ ] **Step 8: Commit** `feat(engine): dotnet-coverage tool detection and collector session`.

---

### Task 6: Instrumented copy of a test project's output

**Files:**
- Create: `engine/NoCrunch.Engine/Coverage/CoverageTargets.cs`
- Test: `engine/NoCrunch.Engine.Tests/Coverage/CoverageTargetsTests.cs`

**Interfaces:**
- Consumes: `ProjectInfo` (`engine/NoCrunch.Engine/Graph/ProjectInfo.cs`: `TargetPath`, `ProjectReferences`, `IsTestProject`), `ProjectGraph.WithReferences/Find`.
- Produces:
  ```csharp
  internal sealed record CoverageTargetSet(IReadOnlyList<ProjectInfo> Production, IReadOnlyList<string> DllNames);
  internal static class CoverageTargets
  {
      /// Non-test projects in the test project's transitive reference closure whose <AssemblyName>.dll and .pdb are in outputDir.
      public static CoverageTargetSet Select(ProjectInfo testProject, ProjectGraph graph, string outputDir);
      /// Copies outputDir into destDir (recursively, replacing it), preferring hard links or clones where cheap; returns the copied test application path.
      public static string CopyOutput(string outputDir, string destDir, string testAppPath);
  }
  ```
  `DllNames` come from the project's `Name` plus `.dll` unless the output folder shows a different assembly name via a matching `.pdb` (the builder may read the assembly name from the project file items if `Name` differs).

- [ ] **Step 1: Write failing tests:** `Select_includes_transitive_production_references`, `Select_excludes_test_and_helper_test_projects`, `Select_skips_projects_whose_dll_or_pdb_is_missing` (and the result says the pass cannot be complete: see Task 7's use), `CopyOutput_copies_all_files_and_leaves_the_source_untouched` (modify the copy, assert the original bytes are unchanged), `CopyOutput_replaces_an_existing_destination`. Use `MirroredSample` for the graph.
- [ ] **Step 2: Run to verify failure.** `--filter "FullyQualifiedName~CoverageTargets"`. Expected: compile failure.
- [ ] **Step 3: Implement.** Prefer a plain recursive copy first; add hard links or clones only if measured copy time on the sample warrants it.
- [ ] **Step 4: Run to verify they pass.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(engine): choose and copy the assemblies to instrument`.

---

### Task 7: The coverage run (one test project, one pass)

**Files:**
- Create: `engine/NoCrunch.Engine/Coverage/ICoverageRun.cs`, `engine/NoCrunch.Engine/Coverage/CoverageRun.cs`, `engine/NoCrunch.Engine/Coverage/CoverageRunFactory.cs`
- Test: `engine/NoCrunch.Engine.Tests/Coverage/CoverageRunTests.cs`

**Interfaces:**
- Consumes: `ICoverageTool`/`ICoverageSession` (Task 5), `CoverageTargets` (Task 6), `CoberturaParser`, `ContentHash` (Task 1), `TestProjectPass`/`ClassCoverage` (Task 2), `PathMap`, `RepoLayout.StateDir`, `TestResultUpdate`/`TestState`.
- Produces:
  ```csharp
  public delegate Task<TestState> RunAlone(TestResultUpdate test, CancellationToken ct);
  public interface ICoverageRun : IAsyncDisposable
  {
      string TargetPath { get; }                    // the instrumented copy of the test application to launch
      Task ClassFinishedAsync(string testClass, IReadOnlyList<TestResultUpdate> results, CancellationToken ct);
      Task FinishedAsync(RunAlone runAlone, CancellationToken ct);
      TestProjectPass? Pass { get; }                // null unless every step succeeded
  }
  public interface ICoverageRunFactory
  {
      Task<CoverageAvailability> CheckAsync(CancellationToken ct);
      /// null when coverage is off or unavailable, or the project is not eligible (not MTP, no TargetPath, nothing to instrument); logs why.
      Task<ICoverageRun?> StartAsync(ProjectInfo project, ProjectGraph graph, CancellationToken ct);
  }
  ```
  `CoverageRunFactory(ICoverageTool? tool, PathMap map, RepoLayout layout, Func<string, string, Task> log)`; `CoverageRun`'s constructor is internal and takes an `ICoverageSession`, `PathMap`, the target set and a log callback so tests can fake the session.

- [ ] **Step 1: Write failing tests with a fake `ICoverageSession`** (it returns a canned cobertura XML per snapshot, in order):
  - `ClassFinished_records_a_ClassCoverage_with_repo_relative_paths` (mirror paths in the XML become repo-relative; a file under `obj/` is dropped).
  - `Failing_test_is_rerun_alone_and_its_lines_become_failing_hit`.
  - `Failing_test_that_passes_alone_is_flaky_it_adds_no_failing_lines_and_logs_a_warning`.
  - `Failing_test_whose_rerun_throws_marks_its_class_unrefined_but_the_pass_still_succeeds`.
  - `Snapshot_failure_in_a_class_leaves_Pass_null`.
  - `Cancellation_leaves_Pass_null_and_disposes_the_session`.
  - `FileHashes_come_from_the_mirror_copy_of_each_covered_file` (temp file with CRLF content).
  - `Pass_Assemblies_lists_the_instrumented_production_projects`.
  Plus `CoverageRunFactoryTests`: `StartAsync_returns_null_and_logs_when_the_tool_is_missing`, `StartAsync_returns_null_for_a_non_MTP_project`, `CheckAsync_reports_unavailable_with_the_install_command_in_the_message` (message contains `dotnet tool install --global dotnet-coverage`).
- [ ] **Step 2: Run to verify failure.** `--filter "FullyQualifiedName~CoverageRun"`. Expected: compile failure.
- [ ] **Step 3: Implement.** `StartAsync` copies the output (`CoverageTargets.CopyOutput` to `<StateDir>/coverage/<project name>/`), starts a session, instruments each target DLL in the copy, and exposes the copy's test application as `TargetPath`. Any exception inside `ClassFinishedAsync`/`FinishedAsync` is caught, logged and recorded so `Pass` stays null; those methods never throw to the caller, so a coverage failure cannot fail the test run. `FinishedAsync` reruns each failed test collected from `ClassFinishedAsync` through `runAlone`, in order, taking a snapshot after each.
- [ ] **Step 4: Run to verify they pass.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(engine): CoverageRun collects one project's coverage pass`.

---

### Task 8: Run tests class by class on one host

**Files:**
- Modify: `engine/NoCrunch.Engine/Core/IEngineEvents.cs` (`ITestStep.RunAsync`), `engine/NoCrunch.Engine/Testing/MtpTestRunner.cs` (`ExecuteAsync`, `WithClientAsync`)
- Test: `engine/NoCrunch.Engine.Tests/Testing/MtpTestRunnerCoverageTests.cs`; update the `ITestStep` fakes in `engine/NoCrunch.Engine.Tests/Core/EngineCoreTests.cs`

**Interfaces:**
- Consumes: `ICoverageRun`, `RunAlone` (Task 7).
- Produces: `ITestStep.RunAsync(ProjectInfo project, Func<TestId, bool> shouldRun, Func<IReadOnlyList<TestResultUpdate>, Task> onUpdates, CancellationToken ct, ICoverageRun? coverage = null)`. With `coverage == null` behaviour is exactly today's.

- [ ] **Step 1: Write failing tests** (integration, on `MirroredSample` with a recording fake `ICoverageRun` whose `TargetPath` is the project's own `TargetPath`):
  - `Each_class_runs_as_its_own_slice_and_ClassFinished_gets_that_classes_results`.
  - `Every_queued_test_runs_exactly_once` (Review Focus 4: a test with no class, theory rows sharing a UID, and the sample's xUnit project `Shop.Theory.Tests`).
  - `Finished_gets_a_RunAlone_that_reruns_one_test_and_returns_its_state`.
  - `A_rerun_does_not_change_the_reported_result` (the final state streamed to `onUpdates` for a test that fails then passes alone is still Failed; Review Focus 5).
  - `Target_path_override_is_the_one_launched` (launch a copy; assert the host's own path is the copy's).
  - `Test_timeout_still_fires_with_coverage` (reuse the existing hanging-test fixture from `MtpTestRunnerTimeoutTests`; the watchdog is re-armed around each coverage call, so a slow snapshot alone does not trip it).
  - `Null_coverage_behaves_as_before` (the existing `MtpTestRunner*` tests keep passing unchanged).
- [ ] **Step 2: Run to verify failure.** `--filter "FullyQualifiedName~MtpTestRunnerCoverage"`. Expected: compile failure.
- [ ] **Step 3: Implement.** In `ExecuteAsync`, when `coverage` is given: group `queued` by `Id.Type` (tests with a null type form their own slice), for each slice `RunTestsAsync` on its distinct UIDs, then `await coverage.ClassFinishedAsync(...)` with that slice's latest results, re-arming the watchdog before and after. After all slices, call `coverage.FinishedAsync` with a `RunAlone` that runs one test by UID on the same host while the update handler diverts that UID's updates into a side dictionary (not `writer`), and returns the state recorded there. Launch the host from `coverage.TargetPath`; discovery keeps using the original target.
- [ ] **Step 4: Run the engine suite.** `dotnet test engine/NoCrunch.Engine.Tests`. Expected: PASS, including every existing runner and core test.
- [ ] **Step 5: Commit** `feat(engine): run tests class by class on one host when collecting coverage`.

---

### Task 9: Wire coverage into EngineCore and the server

**Files:**
- Modify: `engine/NoCrunch.Engine/Core/EngineCore.cs` (constructor, `StartAsync`, `RunCycleAsync`, `RunProjectAsync`), `engine/NoCrunch.Engine/Protocol/EngineServer.cs` (`CreateCore`)
- Test: `engine/NoCrunch.Engine.Tests/Core/EngineCoreTests.cs`

**Interfaces:**
- Consumes: `ICoverageRunFactory` (Task 7), `CoverageBook` and `CoverageStore` (Tasks 2 and 3), `IEngineEvents.CoverageUpdated/CoverageStatus` (Task 4).
- Produces: `EngineCore` gains optional constructor parameters `ICoverageRunFactory? coverage = null, CoverageStore? coverageStore = null` (appended, both default null so existing tests compile).

- [ ] **Step 1: Write failing tests** with fakes (a fake `ICoverageRunFactory` returning a scripted `ICoverageRun`; the existing fake `ITestStep`):
  - `Start_reports_coverage_availability_once`.
  - `A_successful_pass_is_merged_and_sent_as_complete_markup_with_absolute_repo_paths`.
  - `A_failed_or_cancelled_pass_changes_nothing_and_keeps_previous_markup`.
  - `Planning_a_cycle_marks_the_stale_test_projects_coverage_stale_and_sends_it` (the `plan.Stale` set).
  - `A_later_pass_replaces_stale_with_fresh`.
  - `Coverage_run_is_disposed_even_when_the_cycle_is_cancelled` (Review Focus 3).
  - `Persisted_coverage_is_restored_stale_and_sent_on_start`.
  - `Coverage_off_runs_the_plain_path` (null factory).
  - `Not_covered_is_withheld_for_assemblies_referenced_by_a_test_project_without_a_pass` (the `testProjectsCovering` delegate counts non-MTP and wholly excluded test projects).
- [ ] **Step 2: Run to verify failure.** `--filter "FullyQualifiedName~EngineCore"`. Expected: compile failure or the new tests fail.
- [ ] **Step 3: Implement.** In `RunProjectAsync`: `var run = coverage is null ? null : await coverage.StartAsync(project, graph, ct)`, pass it to `tests.RunAsync`, then `await using` it; on a `Completed` outcome with `run.Pass` non-null, `Replace` and send the full markup (`Markup` with `ownersOf = graph.OwnersOf`, `testProjectsCovering = p => graph.WithDependents([p]).Where(t => graph.Find(t)?.IsTestProject == true).ToHashSet(...)`), mapping `Path` through `map.RepoPath`. After `_results.MarkStale(plan.Stale)` also call `MarkStale` and send markup if it returned true. `Save` persists the book through `coverageStore`; `StartAsync` restores it. In `EngineServer.CreateCore`, build the factory only when `p.Coverage != false`, detecting the tool with `DotnetCoverageTool.DetectAsync`, with the log callback going to `events.Log`.
- [ ] **Step 4: Run the engine suite.** `dotnet test engine/NoCrunch.Engine.Tests`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(engine): collect coverage per cycle and publish line states`.

---

### Task 10: End-to-end engine test on the sample solution

**Files:**
- Modify: `engine/NoCrunch.Engine.Tests/TestSupport/MirroredSample.cs` (`CreateAsync(Action<string>? mutateRepo = null)`, applied to the temp repo before the mirror is initialised)
- Test: `engine/NoCrunch.Engine.Tests/Coverage/CoverageEndToEndTests.cs`

**Interfaces:**
- Consumes: everything above. Each test starts with `if (await DotnetCoverageTool.DetectAsync(...) is null) Assert.Inconclusive("dotnet-coverage not installed")`.

- [ ] **Step 1: Write the tests.**
  - `Catalog_tests_cover_sku_parser_as_passing_and_the_broken_add_as_failing`: use `mutateRepo` to change `Inventory.Add` in the temp copy to `existing.Quantity + quantity + 2` (the same break used by hand earlier; committed sample code has no failing test). Run `MtpTestRunner` with a real `CoverageRunFactory` over `Shop.Catalog.Tests`, feed the pass into `CoverageBook`, and assert: lines of `src/Shop.Catalog/SkuParser.cs` are `Passing`; the changed line in `Inventory.cs` is `Failing`; a line the tests never reach in the same assemblies is `NotCovered` (find one in the sample; if none exists add a small uncalled method to the sample's production project in the same commit and say so in the commit message).
  - `Xunit_project_gets_coverage_too` on `Shop.Theory.Tests`.
  - `Original_build_output_is_untouched`: hash `Shop.Catalog.dll` in the mirror's build output before and after; equal.
  - `No_collector_process_remains_after_the_run`.
  - `Cobertura_filenames_map_back_into_the_repo` (every marked path exists under the repo root and none is under `.nocrunch` or `obj`). This is where the real report's `filename` format is first checked: if it differs from what Task 1 assumed, fix `CoberturaParser` here with a new unit test in the same commit.
- [ ] **Step 2: Run.** `dotnet test engine/NoCrunch.Engine.Tests --filter "FullyQualifiedName~CoverageEndToEnd"`. Expected: PASS, or Inconclusive without the tool. Fix anything real it finds in the earlier tasks' code, test-first.
- [ ] **Step 3: Commit** `test(engine): end-to-end coverage on the sample solution`.

---

### Task 11: Plugin protocol, state, settings and content hash

**Files:**
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/engine/EngineMessages.kt`, `plugin/src/main/kotlin/com/nocrunch/rider/NoCrunchService.kt` (notifications, `InitializeParams`), `plugin/src/main/kotlin/com/nocrunch/rider/ui/NoCrunchResults.kt` (`ResultsSnapshot`), `plugin/src/main/kotlin/com/nocrunch/rider/settings/NoCrunchSettings.kt`, `NoCrunchConfigurable.kt`
- Create: `plugin/src/main/kotlin/com/nocrunch/rider/ui/ContentHash.kt`
- Test: `plugin/src/test/kotlin/com/nocrunch/rider/ui/ContentHashTest.kt`, plus the existing settings-state test

**Interfaces:**
- Produces:
  - `data class CoverageFileDto(val path: String, val hash: String, val stale: Boolean, val notCovered: List<Int>, val passing: List<Int>, val failing: List<Int>)` (1-based lines), `data class CoverageUpdatedDto(val files: List<CoverageFileDto>)`, `data class CoverageStatusDto(val available: Boolean, val message: String?)`.
  - `InitializeParams` gains `val coverage: Boolean? = null`.
  - `NoCrunchSettingsState.enableCoverage` (default true, "applies after restart" in its settings label).
  - `ResultsSnapshot.coverage: Map<String, CoverageFileDto> = emptyMap()` (keyed by absolute path), fed by a `coverage` state flow on `NoCrunchService` that **replaces** its map on every `coverageUpdated`.
  - `object ContentHash { fun of(text: CharSequence): String }`: the same definition as the engine's.

- [ ] **Step 1: Write failing tests:** `ContentHashTest.of_a_b_newline_is_the_known_digest` (`"a\nb\n"` gives `911169ddaaf146aff539f58c26c489af3b892dff0fe283c1c264c65ae5aa59a2`, the same value as Task 1) and `crlf_hashes_like_lf`; a settings test `enable_coverage_defaults_on`.
- [ ] **Step 2: Run to verify failure.** `cd plugin && ./gradlew test --tests '*ContentHashTest' --tests '*NoCrunchSettingsStateTest'` (with `JAVA_HOME` set, outside the sandbox). Expected: compile failure.
- [ ] **Step 3: Implement** the DTOs, the service handling of `coverageUpdated` and `coverageStatus`, the new `InitializeParams` field (sent from the setting), the snapshot field, and the settings checkbox.
- [ ] **Step 4: Run the plugin suite.** `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(plugin): receive coverage from the engine`.

---

### Task 12: Choose the "not covered" marker (your call: stop here)

**Files:** none until you pick.

- [ ] **Step 1: Make the mockups.** Use the visual companion or an HTML widget to show three or four candidate "not covered" markers beside the existing circle, diamond and cross, in both themes and with a colour-blind simulation (deuteranopia and protanopia): a small grey ring, a grey dash, a faint filled square, and any one the options suggest. Include the stale (hollow or faded) variant of each.
- [ ] **Step 2: Ask the user to pick.** Do not choose for them. Record the pick, and the stale variant, in the spec's Open decisions and commit that edit.

---

### Task 13: Draw coverage markers in the editor

**Files:**
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/ui/EditorMarkers.kt` (`MarkerKind`), `plugin/src/main/kotlin/com/nocrunch/rider/ui/MarkerIcon.kt`, `plugin/src/main/kotlin/com/nocrunch/rider/ui/EditorMarkerPainter.kt`
- Test: `plugin/src/test/kotlin/com/nocrunch/rider/ui/EditorMarkersTest.kt`, `MarkerIconTest.kt`, `MarkerLinesTest.kt`

**Interfaces:**
- Consumes: `CoverageFileDto`, `ContentHash` (Task 11); the picked shape from Task 12.
- Produces:
  - `MarkerKind` gains `NOT_COVERED` and `NOT_COVERED_STALE` at the **end** of the enum (lowest precedence; `layout` keeps the lowest ordinal on a shared line).
  - `EditorMarkers.coverageMarkers(file: CoverageFileDto): List<LineMarker>`: `passing` becomes `PASS` or `PASS_STALE`, `failing` becomes `FAIL` or `FAIL_STALE`, `notCovered` becomes `NOT_COVERED` or `NOT_COVERED_STALE` (hollow or faded per Task 12); lines are converted to 0-based; `method = null`; tooltips "Hit only by passing tests", "Hit by failing tests", "Not covered by any test", with ", stale" appended when stale.

- [ ] **Step 1: Write failing tests:** `coverage_markers_map_states_to_kinds_and_zero_based_lines`, `stale_file_gives_stale_kinds`, `coverage_marker_loses_to_a_cause_or_test_marker_on_the_same_line` (via `layout`), `precedence_follows_declaration_order` updated in `MarkerIconTest` to include the two new kinds, `marker_icons_are_square` still holds for them. In a new painter-level pure function test (extract the choice into `fun coverageFor(documentText: CharSequence, file: CoverageFileDto?): CoverageFileDto?`): `file_is_used_only_when_its_hash_matches_the_document` and `crlf_file_matches_an_lf_document` (Review Focus 1).
- [ ] **Step 2: Run to verify failure.** `./gradlew test --tests '*EditorMarkersTest' --tests '*MarkerIconTest' --tests '*MarkerLinesTest'`. Expected: compile failure.
- [ ] **Step 3: Implement** the enum values, icon drawing for the Task 12 shape, `coverageMarkers`, `coverageFor`, and the painter change: when laying out an editor, take `coverage[path]`, pass it through `coverageFor(document text, ...)`, and append its markers to the editor's markers before `layout`. The existing change-detection and toggle apply unchanged.
- [ ] **Step 4: Run the plugin suite.** `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(plugin): coverage dots on production lines`.

---

### Task 14: Surface the missing tool, document, and verify in Rider

**Files:**
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/NoCrunchService.kt` (react to `coverageStatus`), `plugin/src/main/resources/META-INF/plugin.xml` if a notification group must be registered, `README.md`
- Test: `plugin/src/test/kotlin/com/nocrunch/rider/...` for the pure message text

**Interfaces:**
- Consumes: `CoverageStatusDto` (Task 11).

- [ ] **Step 1: Write a failing test** for the pure part: `unavailable_status_message_names_the_install_command` (the notification text contains `dotnet tool install --global dotnet-coverage`), and `available_status_shows_nothing`.
- [ ] **Step 2: Run to verify failure.** Expected: compile failure.
- [ ] **Step 3: Implement.** When `coverageStatus` arrives with `available = false`, show one IDE notification per project session (follow how the plugin already raises notifications; register the group in `plugin.xml` if none exists) and a line on the settings page. Add a README "Line coverage" section: what the dots mean, the install command, the setting, and that it needs a restart to change.
- [ ] **Step 4: Run all suites.** `dotnet test engine/NoCrunch.Engine.Tests` and `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(plugin): tell the user when coverage needs dotnet-coverage`.
- [ ] **Step 6: Manual check in Rider** (`./gradlew runIde -PopenSolution="<path>/samples/Sample Solution/Sample.slnx"`, with the sample's `Add` edited to fail): production lines show circles, the broken line shows a diamond with a cross where the failure surfaces, an uncalled method shows the not-covered marker; dots go hollow on an edit and refresh after the cycle; the toolbar toggle hides them; a file edited by hand while the engine is paused shows no dots; with `dotnet-coverage` hidden from `PATH` the notification appears and tests still run. Confirm the colours and shapes with the user.
- [ ] **Step 7: Timing on a real solution.** Ask the user to run it on their 3000-test solution and report the first full pass time against a plain run. Do not turn coverage on by default for large solutions until that number is known.
