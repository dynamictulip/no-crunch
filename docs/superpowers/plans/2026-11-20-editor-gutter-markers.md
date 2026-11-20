# Editor Gutter Markers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show test status as colour-blind-friendly gutter markers in the editor (test methods, plus the line a failure came from), behind a tool window toolbar toggle, and share one green/red palette across the whole plugin UI.

**Architecture:** A pure function turns the latest test results into per-file line markers. A project service paints them as range highlighters with a gutter icon on every open editor, repainting when results change, an editor opens, or the toggle flips. Colours come from one `Palette` object also used by the progress bar and tree text.

**Tech Stack:** Kotlin, IntelliJ Platform (Rider 2026.2+), JUnit 5, Gradle (`plugin/`).

**Spec:** `docs/superpowers/specs/2026-11-20-editor-gutter-markers-design.md`

## Global Constraints

- Plugin code lives under `plugin/src/main/kotlin/com/nocrunch/rider/`; tests under `plugin/src/test/kotlin/com/nocrunch/rider/` (JUnit 5, `org.junit.jupiter.api`).
- Run tests with `cd plugin && JAVA_HOME=~/Applications/Rider.app/Contents/jbr/Contents/Home ./gradlew test --tests '<class>'`.
- Palette: pass green `#009E73`, fail and cause red `#D55E00`. Only these two hues. Shape carries meaning: passing = circle, failing = diamond, cause = bold cross. Stale = same shape drawn hollow.
- There is no stale cause marker: a stale cause line shows as failing-stale (hollow diamond).
- One marker per line. Precedence: cause, failing, failing-stale, passing, passing-stale.
- Tests that haven't run, are skipped, or are `EXCLUDED` show no marker. No engine or wire-protocol changes.
- Toggle name "Show Editor Markers", on by default, persisted per project in `NoCrunchSettingsState`.
- Markers are painted in code (no SVG resources) and must scale with `JBUI.scale`.
- Sentence case in UI text; no "successfully", no exclamation marks.

## Review Focus

- A theory with some failing and some passing variants on the same line shows one failing marker, not a passing one.
- A failing test whose stack trace has only framework frames (files outside the repo, or no file info) gets its test-method marker but no cause marker.
- A stack frame pointing into `.nocrunch/` (mirror) or at a file that no longer exists is ignored for the cause marker.
- A cause line that is also a test method line (assertion on the declaration line) shows one marker with cause winning.
- A marker whose line is past the end of the file after an external edit is skipped instead of throwing; toggling off removes every highlighter this feature added and nothing else.

---

### Task 1: Shared palette

**Files:**
- Create: `plugin/src/main/kotlin/com/nocrunch/rider/ui/Palette.kt`
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/ui/ProgressBar.kt` (the `colour` function, PASSING and FAILING)
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/ui/TestTreeRenderer.kt` (companion: `FAILED`, `FAILED_BOLD`, `PASSED_BOLD`, `STALE_RED`, `STALE_FAILED`, `STALE_FAILED_BOLD`)
- Test: `plugin/src/test/kotlin/com/nocrunch/rider/ui/PaletteTest.kt`

**Interfaces:**
- Produces:
  - `object Palette` with `val PASS_LIGHT: Color`, `val FAIL_LIGHT: Color`, `val PASS: JBColor`, `val FAIL: JBColor`, and `fun faded(c: Color): Color`.
  - `PASS_LIGHT` is `Color(0x009E73)`, `FAIL_LIGHT` is `Color(0xD55E00)`. Dark variants are slightly lighter versions of the same hues (use `Color(0x2DBE96)` and `Color(0xF07A2A)`).
  - `faded(c)` returns `c` with alpha 0.55 (use `ColorUtil.withAlpha`), used for stale text.

- [ ] **Step 1: Write the failing test** `PaletteTest`: `pass_and_fail_light_values_are_the_spec_hexes` (assert `PASS_LIGHT.rgb and 0xFFFFFF == 0x009E73`, `FAIL_LIGHT` likewise `0xD55E00`), `faded_keeps_the_hue_and_lowers_alpha` (`faded(PASS_LIGHT)` has the same red, green, blue and `alpha == (0.55 * 255).toInt()` give or take 1).
- [ ] **Step 2: Run it** with `--tests 'com.nocrunch.rider.ui.PaletteTest'`. Expected: FAIL (unresolved reference `Palette`).
- [ ] **Step 3: Implement `Palette`** as above. Then switch `ProgressBar.colour` so `PASSING -> Palette.PASS` and `FAILING -> Palette.FAIL` (leave RUNNING and NEUTRAL). In `TestTreeRenderer`'s companion, build `FAILED`/`FAILED_BOLD` from `Palette.FAIL`, `PASSED_BOLD` from `Palette.PASS`, and replace the hard-coded `STALE_RED` with `Palette.faded(Palette.FAIL)`, so stale failures are the palette red faded.
- [ ] **Step 4: Run the full plugin suite** with `./gradlew test`. Expected: PASS (no existing test asserts these colours; if one does, update it to the palette).
- [ ] **Step 5: Commit** `feat(plugin): one green/red palette across the progress bar and test tree`.

---

### Task 2: Marker types, marker icon, and the painter skeleton (go/no-go in Rider)

This task also settles the spec's top risk: whether gutter icons render in Rider's C# editor.

**Files:**
- Create: `plugin/src/main/kotlin/com/nocrunch/rider/ui/EditorMarkers.kt` (types only for now)
- Create: `plugin/src/main/kotlin/com/nocrunch/rider/ui/MarkerIcon.kt`
- Create: `plugin/src/main/kotlin/com/nocrunch/rider/ui/EditorMarkerPainter.kt`
- Test: `plugin/src/test/kotlin/com/nocrunch/rider/ui/MarkerIconTest.kt`

**Interfaces:**
- Produces in `EditorMarkers.kt`:
  - `enum class MarkerKind { CAUSE, FAIL, FAIL_STALE, PASS, PASS_STALE }` (declaration order is precedence, best first).
  - `data class LineMarker(val line: Int, val kind: MarkerKind, val tooltip: String, val method: String? = null)`: `line` is 0-based; `method` is the test method name when the marker is on a test, used to land on the method's own line rather than its attribute line.
- Produces in `MarkerIcon.kt`: `class MarkerIcon(val kind: MarkerKind) : Icon` of 14x14 scaled units: PASS filled circle; PASS_STALE hollow circle (1.5 stroke); FAIL filled diamond; FAIL_STALE hollow diamond; CAUSE bold cross (3 stroke, round caps). PASS kinds use `Palette.PASS`; every other kind uses `Palette.FAIL`.
- Produces in `EditorMarkerPainter.kt`: `@Service(Service.Level.PROJECT) class EditorMarkerPainter(project: Project, scope: CoroutineScope) : Disposable` with `fun refresh()` (repaints every open editor from the current markers) and `companion fun getInstance(project: Project)`. It attaches to editors through `EditorFactory.getInstance().addEditorFactoryListener(..., this)` and also handles editors already open. Each editor's highlighters are tracked in a `Map<Editor, List<RangeHighlighter>>` so only our own are removed. Highlighter: `editor.markupModel.addLineHighlighter(null, line, HighlighterLayer.ADDITIONAL_SYNTAX)` with a `GutterIconRenderer` (icon `MarkerIcon(kind)`, tooltip, `LEFT` alignment, `equals`/`hashCode` on kind and tooltip). Starts from `NoCrunchStartup` (call `EditorMarkerPainter.getInstance(project)`).

- [ ] **Step 1: Write the failing test** `MarkerIconTest`: `icons_are_square_and_scale` (`iconWidth == iconHeight` for each kind), `precedence_follows_declaration_order` (`MarkerKind.entries` is `[CAUSE, FAIL, FAIL_STALE, PASS, PASS_STALE]`).
- [ ] **Step 2: Run it.** Expected: FAIL (unresolved references).
- [ ] **Step 3: Implement** the three files. For `refresh()`, take the markers from a private `markers(): Map<String, List<LineMarker>>` that for now returns a temporary probe: `mapOf(<each open editor's file path> to listOf(LineMarker(0, MarkerKind.PASS, "probe")))`. Mark the probe with `// PROBE: removed in Task 5`.
- [ ] **Step 4: Run `./gradlew test`.** Expected: PASS.
- [ ] **Step 5: Go/no-go in Rider.** Build and run in Rider (`cd plugin && JAVA_HOME=~/Applications/Rider.app/Contents/jbr/Contents/Home ./gradlew runIde`; if `runIde` isn't usable, `buildPlugin` and install the zip from disk). Open a solution, open a `.cs` file. Expected: a green circle in the gutter on line 1. If no icon appears in the C# editor, **stop** and report back with what you saw; the design needs rethinking (spec Risks). If it appears, also glance at light and dark themes.
- [ ] **Step 6: Commit** `feat(plugin): gutter marker icon and editor painter skeleton`.

---

### Task 3: Test-method markers from results

**Files:**
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/ui/EditorMarkers.kt`
- Test: `plugin/src/test/kotlin/com/nocrunch/rider/ui/EditorMarkersTest.kt`

**Interfaces:**
- Consumes: `TestResultDto` fields `filePath`, `line` (1-based), `method`, `displayName`, `state`, `lastOutcome`, `message`, `stackTrace`; `TestState.isOutcome`; `TestTreeModel.worst(outcomes)`.
- Produces: `object EditorMarkers { fun compute(results: Collection<TestResultDto>, isRepoFile: (String) -> Boolean): Map<String, List<LineMarker>> }`. Keyed by `filePath`; each list has at most one marker per line, sorted by line.

Rules for this task (cause markers come in Task 4):
- A result with null `filePath` or `line` is ignored.
- Shown outcome = `state` if `state.isOutcome`, else `lastOutcome`; `state == EXCLUDED` is ignored; SKIPPED or null outcomes are ignored.
- Stale = `!state.isOutcome` (queued, running, stale, discovered with a last outcome).
- Results on the same file and line (theory variants) collapse: outcome is `TestTreeModel.worst`, and the marker is stale if any result contributing that worst outcome is stale.
- Kind: FAILED → `FAIL`/`FAIL_STALE`; PASSED → `PASS`/`PASS_STALE`. Marker `line = line - 1`, `method = method?.substringBefore('(')`.
- Tooltip: `"Failing: <name>"`, `"Failing, stale: <name>"`, `"Passing: <name>"`, `"Passing, stale: <name>"` where `<name>` is `method?.substringBefore('(') ?: displayName`.

- [ ] **Step 1: Write the failing tests** in `EditorMarkersTest`, using a small private `result(...)` builder that fills `TestResultDto`'s required fields (key, project, type, method, uid, variant, displayName, state, durationMs, message, stackTrace, filePath, line): `passing_test_gets_a_pass_marker_on_its_line` (line 12 → marker line 11, kind `PASS`, tooltip `"Passing: Total_works"`), `failed_test_gets_fail`, `stale_result_shows_last_outcome_hollow` (`state=STALE, lastOutcome=PASSED` → `PASS_STALE`; `lastOutcome=FAILED` → `FAIL_STALE`), `never_run_skipped_and_excluded_get_nothing` (`DISCOVERED` with no lastOutcome, `SKIPPED`, `EXCLUDED` → empty map), `theory_with_a_failing_variant_shows_one_fail_marker` (three results on the same line, one `FAILED`, two `PASSED` → one `FAIL`; Review Focus 1), `results_without_a_location_are_ignored`.
- [ ] **Step 2: Run** `--tests 'com.nocrunch.rider.ui.EditorMarkersTest'`. Expected: FAIL (`compute` not defined).
- [ ] **Step 3: Implement `EditorMarkers.compute`** as specified; `isRepoFile` is unused until Task 4.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(plugin): compute test-method markers from results`.

---

### Task 4: Cause-of-failure markers from stack traces

**Files:**
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/ui/EditorMarkers.kt`
- Test: `plugin/src/test/kotlin/com/nocrunch/rider/ui/EditorMarkersTest.kt`

**Interfaces:**
- Consumes: Task 3's `compute`.
- Produces: `fun EditorMarkers.topRepoFrame(stackTrace: String?, isRepoFile: (String) -> Boolean): Pair<String, Int>?` returning the first stack frame's `(path, 1-based line)` whose path satisfies `isRepoFile`, parsing frames of the form `in /abs/path/File.cs:line 12` (the same pattern `DetailsPane`'s `DotnetLocationFilter` uses: `in (/[^\n]+?):line (\d+)`). Null when none.

Rules:
- For every result whose shown outcome is FAILED (current or stale), take `topRepoFrame(dto.stackTrace, isRepoFile)`. If present, add a marker at that file and `line - 1`: kind `CAUSE` when the result is current, `FAIL_STALE` when stale. Tooltip: `"Cause of failure: " + (first line of message ?: name)`; stale uses `"Failing, stale: <name>"`. `method = null`.
- Merging onto an existing marker on the same file and line keeps the better kind by `MarkerKind` declaration order (cause beats failing beats failing-stale beats passing beats passing-stale), and keeps that marker's tooltip and method.

- [ ] **Step 1: Write the failing tests:** `top_repo_frame_skips_framework_frames` (trace with an `in /usr/share/dotnet/…/Assert.cs:line 5` frame first, then `in /repo/src/A.cs:line 40` → `("/repo/src/A.cs", 40)`), `top_repo_frame_ignores_frames_outside_the_repo_and_mirror_and_missing_files` (predicate rejects them → null; Review Focus 3), `failing_test_gets_a_cause_marker_in_production_code` (cause line in `/repo/src/Order.cs` plus the test's own `FAIL` marker in its file), `framework_only_trace_gives_no_cause_marker` (Review Focus 2), `stale_failure_cause_shows_as_fail_stale`, `cause_on_the_test_method_line_wins_over_fail` (Review Focus 4: one marker, kind `CAUSE`), `cause_beats_a_passing_marker_on_the_same_line`.
- [ ] **Step 2: Run the tests.** Expected: FAIL.
- [ ] **Step 3: Implement `topRepoFrame` and the cause step in `compute`** (add the markers after the test-method markers, merge per the rule).
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(plugin): cause-of-failure markers from stack traces`.

---

### Task 5: Paint real markers

**Files:**
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/ui/EditorMarkerPainter.kt`
- Test: `plugin/src/test/kotlin/com/nocrunch/rider/ui/MarkerLinesTest.kt`

**Interfaces:**
- Consumes: `EditorMarkers.compute`, `NoCrunchResults.getInstance(project).snapshots` (`StateFlow<ResultsSnapshot>`, `snapshot.tests: Map<String, TestResultDto>`), `NoCrunchService.getInstance(project).repoRoot: String?`, `TestLocator.caretOffset(text, line0, methodName): Int`.
- Produces: `fun landingLine(text: CharSequence, marker: LineMarker): Int?` (top-level in `EditorMarkerPainter.kt`) returning the 0-based line to paint: for a marker with a `method`, the line containing `TestLocator.caretOffset(text, marker.line, method)`; otherwise `marker.line`; null when the result is past the last line of `text` (Review Focus 5).

- [ ] **Step 1: Write the failing tests** in `MarkerLinesTest`: `test_marker_lands_on_the_method_line_not_the_attribute` (text `"[TestMethod]\npublic void Total_works()\n{\n}"`, marker line 0, method `Total_works` → 1), `cause_marker_keeps_its_line`, `line_past_the_end_is_skipped` (marker line 99 on a 3-line text → null).
- [ ] **Step 2: Run** `--tests 'com.nocrunch.rider.ui.MarkerLinesTest'`. Expected: FAIL.
- [ ] **Step 3: Implement `landingLine` and replace the Task 2 probe.** The painter collects `NoCrunchResults.snapshots` on the service scope, calls `EditorMarkers.compute(snapshot.tests.values, isRepoFile)` where `isRepoFile = { p -> repoRoot != null && p.startsWith("$repoRoot/") && !p.startsWith("$repoRoot/.nocrunch/") && File(p).isFile }`, and on the EDT repaints every open editor: remove only the highlighters it previously added for that editor, then for each marker for that editor's `virtualFile.path` add a highlighter at `landingLine(document.immutableCharSequence, marker)` (skip nulls). Collapse markers that land on the same line using `MarkerKind` order. Remove an editor's entry when the editor is released. Delete the `// PROBE` code.
- [ ] **Step 4: Run `./gradlew test`.** Expected: PASS.
- [ ] **Step 5: Verify in Rider** (as in Task 2 Step 5): open a solution with tests; expect circles on passing test methods, diamonds on failing ones, a cross on the throwing line (check one in production code), hollow versions after you edit a covered file and before the rerun finishes. Confirm stack frames point at repo files (not `.nocrunch/`); if they point into the mirror, map them back with the engine's repo root before the `isRepoFile` check. Run the colour-blind check by eye or with a simulator.
- [ ] **Step 6: Commit** `feat(plugin): paint test and failure markers in the editor`.

---

### Task 6: Toggle

**Files:**
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/settings/NoCrunchSettings.kt` (add `var showEditorMarkers by property(true)` to `NoCrunchSettingsState`)
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/ui/NoCrunchActions.kt` (new `ToggleAction` after the Colour Test Names group in `toolbarActions()`)
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/ui/EditorMarkerPainter.kt` (read the setting; when off, remove all its highlighters and add none)
- Modify: `README.md` (one bullet describing the editor markers and the toggle, and what the shapes mean)
- Test: `plugin/src/test/kotlin/com/nocrunch/rider/settings/NoCrunchSettingsStateTest.kt`

**Interfaces:**
- Consumes: `NoCrunchSettings.getInstance(project).state.showEditorMarkers`, `EditorMarkerPainter.getInstance(project).refresh()`.
- Produces: toolbar `ToggleAction` titled "Show Editor Markers", description "Show test status markers in the editor gutter", icon `AllIcons.Actions.Show`, `getActionUpdateThread() = EDT`; `setSelected` stores the value then calls `refresh()`. It follows the same pause-greying rule as the other display actions.

- [ ] **Step 1: Write the failing test:** `editor_markers_are_on_by_default` (`assertTrue(NoCrunchSettingsState().showEditorMarkers)`).
- [ ] **Step 2: Run** `--tests 'com.nocrunch.rider.settings.NoCrunchSettingsStateTest'`. Expected: FAIL (unresolved `showEditorMarkers`).
- [ ] **Step 3: Implement** the setting, the action, and the painter check. When the setting is off, `refresh()` clears every tracked highlighter (Review Focus 5); when turned back on it repaints from the latest snapshot.
- [ ] **Step 4: Run `./gradlew test`.** Expected: PASS.
- [ ] **Step 5: Verify in Rider:** toggle off removes every marker from open editors immediately and does not touch other gutter icons (breakpoints, VCS bars); toggle on brings them back; the choice survives restarting Rider.
- [ ] **Step 6: Commit** `feat(plugin): toolbar toggle for editor markers`.
