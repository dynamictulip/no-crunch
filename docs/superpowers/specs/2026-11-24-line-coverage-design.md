# Line coverage (design)

Date: 2026-11-24. Status: awaiting approval.

## Intent

Bring line coverage into the engine so the editor can show NCrunch-style dots on production code, and so a later project can rerun only the tests an edit affects. Today the plugin knows which tests passed or failed and which line a failure came from, but not which production lines any test runs. This design adds that knowledge, cheaply enough to work on a solution with thousands of tests.

The gutter markers shipped earlier (test method and failure-cause markers, one shared palette) are the first consumer. Selective rerun is the second, in a separate project that this design must not block.

## Goals

- After a test cycle, the engine knows for each production source line: whether any test ran it, and whether any failing test ran it.
- The plugin draws the line states from that knowledge, using the shapes and palette already in place: hit only by passing tests (green circle), hit by a failing test (red diamond), not hit by any test (new marker, see Open decisions), each with a stale (hollow) variant. The "cause of failure" cross already exists.
- Coverage collection stays cheap on large solutions. Target for a 3000-test, multi-assembly solution: the first full pass adds seconds to a minute or two of overhead, not minutes per hundred tests.
- The engine also keeps a coarser line-to-test-class map. It is not shown anywhere yet, but it is what selective rerun needs.
- Coverage never changes build outputs, test results, or what the user's own debug runs execute.

## Non-goals

- Per-test line coverage for every test (tooltips like "covered by these 3 tests"). It is too slow to collect up front; see Findings.
- Selective test rerun itself. Only the data it will need is kept.
- Branch coverage, coverage percentages, a coverage report UI, or file-level summaries.
- VSTest projects (the engine only runs MTP projects), and projects with no PDB.
- Coverage for editors that are not open. The plugin only paints open editors, as now.

## Anti-goals

- A dot that lies. No marker beats a wrong marker: a line is drawn "not covered" only when the engine has a successful coverage pass for every test project that references its assembly. If a pass failed or hasn't run, its lines show nothing.
- Coverage that slows the tool window. Results still stream while coverage is collected.
- Coverage that requires the user to change their projects.

## Findings that shape the design

All measured on an osx-arm64 Mac with `dotnet-coverage` 18.10.0, MSTest 4.5.3 and the engine's MTP client, in scratch copies. The Windows and Linux paths are expected to behave the same but are untested.

- **Dynamic instrumentation does not work on arm64** (`collect`/`connect`: "Profiler was not initialized"). **Static instrumentation does**: start a collector session, rewrite the production DLLs with `instrument --session-id`, run the test host normally, read the data with `snapshot`.
- **One MTP host can run many `runTests` calls**, one slice at a time, with a snapshot and reset between them (47 tests, and then 3000). Attribution per slice was clean: each snapshot held only that slice's lines.
- **A snapshot costs about 63 ms plus about 35 ms per instrumented assembly** (the tested assemblies had 1200 methods each). It is a full dump of every loaded instrumented module even right after a reset: 1 assembly = 1.2 MB and 100 ms; 4 = 4.9 MB and 195 ms; 8 = 9.8 MB and 270–350 ms. About 63 ms of that is the CLI starting up; the rest is the collector and does not shrink with a direct connection, which would also mean copying a private, undocumented protocol.
- **Snapshot after every test is too slow**: 3000 tests = 824 s of snapshots on top of 21 s of tests (about 14 minutes), and 27 GB of snapshot files. Cost scales with tests × instrumented code.
- **The MTP coverage extension** (`--coverage`) works on arm64 with about 2 s overhead but reports per run, not per test. MSTest.Sdk projects ship it; other frameworks need the package added.
- The engine builds the mirror with `dotnet build` and no configuration flag, so projects build as Debug and emit PDBs unless a project overrides that.

## What the dots actually need

Each dot state is about a *set of lines*, not about one test:

| State | Needs |
|---|---|
| Not covered | lines hit by no test |
| Hit only by passing tests | covered lines, minus lines hit by a failing test |
| Hit by failing tests | lines hit by at least one failing test |

So the engine needs only two sets per test project: lines hit by any test, and lines hit by any failing test. A full per-test map is not required for the dots.

## Approaches considered

1. **Snapshot after every test.** Exact per-test map. Rejected: about 14 minutes and 27 GB for 3000 tests, growing with the amount of production code.
2. **Own IL probes keyed by the current test** (rewrite assemblies with Mono.Cecil, record hits into a memory-mapped file the engine reads, using the PDB to map to lines). Exact per-test data at almost no snapshot cost. Rejected for now: the engine would own an IL rewriter that has to cope with async, iterators, generics and ref structs, and a bug breaks the user's test run. Worth revisiting if the per-test map turns out to matter.
3. **A once-over, then failing tests, with a class-level map (chosen).** One pass runs the tests class by class on a single host, snapshotting once per class. The union is "covered"; each class's snapshot is the class-level map. Failing tests are rerun alone with a snapshot to get "hit by failing". Cost is one snapshot per class plus one per failing test. For a solution with 30 classes in 8 assemblies that is about 8 s of snapshots instead of 14 minutes.
4. **Group testing / bisection** to recover per-test data from fewer snapshots. Rejected as complexity without need for the dots.

## Design

### Where it sits in the engine

A new coverage-aware way of running a test project, used in place of the plain run when coverage is on. It fits the existing seam: `EngineCore.RunProjectAsync` calls `ITestStep.RunAsync`, which streams `TestResultUpdate`s. The coverage run streams the same updates, so the tool window behaves as today.

For one test project, in one cycle:

1. **Copy the test project's build output folder** to a sibling of it named `<output>.nccov` (same depth as the original, so tests that find files relative to the assembly resolve the same way; the folder is git-ignored with the rest of `bin/`, and is removed when the run ends). Instrument the copies, never the originals. (The spike instrumented in place. That is unsafe outside a scratch copy: MSBuild's copy-local step keeps a newer instrumented DLL and would hand the instrumented one to the user's debug run and the next build.) Which DLLs: the non-test projects in the solution that this test project references, found through the project graph, plus their PDBs. Skip test assemblies and third-party packages.
2. **Start a collector session** (`collect --server-mode --background`, unique session id per project run, `cobertura` output) and instrument the copied DLLs against it.
3. **Launch the copied test host once**, discover, then for each test class: `runTests` for that class's UIDs, then `snapshot --reset`. Results stream as now. Convert the snapshot to a line set with `merge -f cobertura`.
4. **Failing tests.** For each test that failed in its class, run it again alone and snapshot. Its line set joins "hit by failing". A test that passes on the rerun is treated as flaky: its lines are not marked failing, and the run logs a warning. Rerun results never replace the reported result.
5. **Shut the session down** and delete the snapshot files as each is merged. Keep only the compact results.

Tests excluded by the user's exclusion rules do not run, so they contribute nothing; that is expected.

### What the engine keeps

A coverage book per test project, alongside `ResultBook` and persisted the same way (so dots survive a restart):

- file (repo path) → lines covered by any test → lines covered by any failing test
- file → line → set of test classes that cover it (the class-level map; stored now, used by selective rerun later)
- which assemblies were successfully covered, and when

Each project's pass replaces that project's earlier contribution. The solution view is the union across test projects. Paths in the coverage data are mirror paths; they are translated with `PathMap.ToRepo`, and files outside the repo (generated `obj/` sources) are dropped.

### Protocol

One additive notification from the engine to the plugin (no protocol version bump, per the protocol's rule): `coverageUpdated`, carrying per source file the covered line ranges, the failing-hit line ranges, and which assembly/project it belongs to. The plugin keeps a project-wide map and recomputes markers when it changes.

### Plugin

The marker computation (`EditorMarkers.compute`) takes the coverage map as an extra input and adds line markers for production files:

- circle, filled: hit only by passing tests
- diamond, filled: hit by at least one failing test
- the cause cross keeps precedence on its line, as now
- the not-covered marker (see Open decisions), drawn only where the anti-goal allows it
- every state has a hollow stale variant

A line is stale when its project has changed since the data was collected (the same rule that makes test results stale: the project is in the current or pending cycle). The existing toolbar toggle controls these markers too.

### Settings

Coverage is on by default only if `dotnet-coverage` is available (see Open decisions). A setting turns it off, for users who want results without the extra cost.

## Calls made while writing this up (please check)

These came out of a cold read of the first draft. Each is my call, not something agreed in conversation.

1. **Which assemblies are instrumented.** For a test project: every non-test project in its transitive project-reference closure (the project graph already computes this) whose DLL is in the copied output folder and has a PDB. Test projects and test helper projects are never instrumented, and neither are packages. A production assembly "belongs to" every test project whose closure contains it. The "not covered" rule needs a successful pass from all of those test projects.
2. **A pass is all or nothing.** Only a fully successful pass replaces a project's earlier coverage. A pass that fails, is cancelled, loses a snapshot or crashes the host contributes nothing, and the previous data stays but is shown stale until a pass succeeds. Half a pass is never merged.
3. **Edits and line numbers.** Dots are anchored to the document in the editor, so they move as you type, like the test markers. Coverage data records a hash of each source file's content from the mirror copy it was collected against. The plugin paints a file's dots only if the open document still hashes the same; otherwise it paints none for that file until the next pass, since shifted line numbers would put dots on the wrong lines. Staleness is per project, as for test results: a line is stale when the project that owns the file is in the current or a pending cycle.
4. **Failing-test reruns have no cap, but they never mislead.** Every failing test is rerun alone with a snapshot, failing-project first. Until a class's failures have all been rerun, lines hit by that class are drawn with no marker (neither "only passing" nor "not covered"), because we can't yet say whether a failing test hit them. Large failure counts make the pass slower, not wrong.
5. **How coverage fits the cycle.** With coverage on, the coverage run replaces the plain run for an MTP project with PDBs; results stream from it as before, and `ProjectRunFinished` fires when the tests finish. The engine goes idle once coverage for the cycle has been merged and `coverageUpdated` sent, so the status reads "Testing" a little longer than today. Test projects that aren't MTP, or whose references have no PDBs, get the plain run, with a log line saying coverage was skipped and why. Excluded tests behave as now.
6. **How availability is detected.** At engine start, run `dotnet-coverage --version` (found on `PATH` or in `~/.dotnet/tools`) and require at least 18.0, the family tested here. If it's missing or too old, coverage stays off, the engine reports that once, and the plugin shows a notification and a line in the settings page with the install command.

## Constraints

- The collector tool must be reachable from the engine's machine: see Open decisions.
- Instrumentation must work on arm64 macOS, which rules out dynamic instrumentation.
- The mirror is rebuilt on edits; coverage folders are rebuilt each cycle and must not be left in a state that confuses the next cycle (a crash mid-pass leaves a collector session running: the engine shuts down its sessions on cancel, error and exit).
- Existing behaviours stay: the test timeout (inactivity watchdog must be re-armed around snapshots, which can take a few hundred ms), parallel test projects (one session per project run), and cancellation mid-cycle.

## Risks

- **Not tested on a real solution.** The 3000-test numbers are from a synthetic solution of trivial tests. Real tests are slower, instrumentation slows them further, and a real solution has more production code per snapshot (cost 35 ms per ~1.2 MB assembly, so a large codebase is slower per snapshot).
- **Leaked background work.** A test that leaves a task running after it finishes can have its hits land in the next slice. Class-level slicing contains this to within one class's neighbours; per-test attribution would not have.
- **Tool coupling.** `dotnet-coverage`'s session commands and cobertura output are public and documented, but its collector pipe protocol is private and not used here.
- **Other frameworks.** Only MSTest was exercised. xUnit v3 and NUnit through MTP should work the same (the engine only needs `runTests` by UID) but are untested.
- **Frameworks that run a class's tests in parallel** are fine at class granularity; a framework that runs classes in parallel within one host is also fine because a slice contains one class at a time.

## Open decisions (yours to make)

1. **How the engine gets `dotnet-coverage`.** (a) Require the user to install it as a global tool; the plugin shows a message when it is missing and coverage stays off. Simplest, no licensing or download questions. (b) The engine installs it on first use into its own tools folder (needs network; adds a download the user didn't ask for). (c) Skip the CLI and use the MTP coverage extension instead: no extra tool, but it reports per run, so each class and each failing-test rerun becomes its own host launch with about 2.5 s of overhead instead of about 0.1–0.3 s, and non-MSTest projects need the package added to the mirror. **My pick: (a) for the first version.** Nothing in the design depends on the choice except the cost per slice and the install story.
2. **How "not covered" looks. Decided: a grey dash.** The user picked it from four mocked-up options (small ring, dash, filled square, hollow square). A short horizontal bar in neutral grey (`#7D828C` light, `#9DA0A8` dark); its shape matches no other marker, so shape alone separates it from the rest. Stale is the same dash at reduced opacity (the `Palette.faded` treatment), not a different shape.
3. **What the user sees while coverage is still being gathered.** After the first cycle, test-method markers appear as now; production-line dots arrive when the coverage pass finishes. I propose no placeholder (the dots just appear), but the status could say "collecting coverage" in the tool window.

## Left to the builder

- Exact folder layout and cleanup of coverage folders and sessions.
- How to copy the output folder cheaply (hard links or clones where the platform supports them).
- File format for persisting the coverage book.
- Naming of the protocol message and its fields.
- Whether to run the failing-test rerun inside the same host or a fresh one (same host is cheaper; a fresh host avoids state from earlier tests).

## Testing

- Unit tests for the pure parts: parsing cobertura into line sets, merging per-class snapshots into the "covered" and "failing" sets and the class-level map, path translation, per-project replace and union, the rule for drawing "not covered", and the plugin's marker computation with coverage input.
- An engine integration test against the sample solution: it should report the lines each sample test class covers, and the failing sample test's lines as failing.
- A manual check in Rider for rendering, the toggle, staleness after an edit, and colour-blind legibility.
- A timing check on a larger solution before turning coverage on by default.
