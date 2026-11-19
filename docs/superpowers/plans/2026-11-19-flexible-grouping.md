# Flexible Grouping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the No Crunch test tree be grouped by any ordered choice of Project, Folder, File, Namespace and Class, with nested namespaces, folders and classes, a "Group by" popup with recently used groupings, and visible partial exclusion.

**Architecture:** One pure grouping step (`GroupingTree`) replaces `TreeView`'s three hard-wired views. It groups the *unfiltered* model level by level, then drops tests the view filters hide, so "does this row hold the whole class or project" is decided by the grouping, never by a filter. Every group row is a `GroupView` with a kind (PROJECT, FOLDER, FILE, NAMESPACE, CLASS). The panel turns the result into `TreeSpec`s for `TreeSync` as it does today.

**Tech Stack:** Kotlin, IntelliJ Platform (Rider 2026.2), JUnit 5; the .NET engine and sample solution (MSTest, xUnit v3 on MTP) only for the nested-class check.

**Spec:** `docs/superpowers/specs/2026-11-19-flexible-grouping-design.md`

## Global Constraints

- Levels: Project, Folder, File, Namespace, Class; any subset, any order; the test is always the bottom level; theory data rows stay nested under their test.
- Persisted names must not change meaning: existing `viewMode`, `sortMode`, `nameColouring` values keep loading. Conversion: `CLASSES` → `Project › Class`, `NAMESPACES` → `Project › Namespace › Class`, `FILES` → `Project › Folder › File`. No saved setting → `Project › Class`.
- Converted views must look exactly as today apart from namespaces and folders now nesting.
- A test with no value for a level skips that level (no "unknown" group).
- Single-child chains collapse for Folder and Namespace only, never for Class.
- Recent list: the last 3 groupings used, named by their levels joined with ` › `, current one ticked; a grouping is remembered when the popup closes.
- Partial exclusion: grey `· N excluded` after the counts (N = excluded tests under the row, counted like the other counts: theory data rows don't count separately), a crossed-out-eye badge on the result icon, and a half-ticked checkbox in choosing mode. Clicking a half-ticked checkbox includes everything under the row.
- Excluding a row writes rules for only what it shows: whole class → class rule; whole project → project rule; part of either → rules for the tests under the row; an outer class row also covers the classes nested under it (one class rule each).
- When Project isn't the top level, the pinned group is titled "Projects with problems" and lists broken non-test projects plus test projects whose build failed or was blocked, whose test host crashed, or whose framework is unsupported; otherwise it is "Build errors" with broken non-test projects only.
- Speed: grouping runs off the EDT for every result batch (about every 100 ms while results stream), so it must stay linear in the number of tests per level for solutions with thousands of tests; no per-row re-scans of the whole model.
- No engine or protocol changes. Pure logic in plain classes with unit tests (CONTRIBUTING.md). Conventional commits. Tests: `cd plugin && JAVA_HOME=~/Applications/Rider.app/Contents/jbr/Contents/Home ./gradlew test --offline`; engine: `dotnet test --solution NoCrunch.slnx` from the repo root (both need the Bash sandbox disabled; commits are GPG-signed and need it too).

## Review Focus

1. **Same class name in two projects with Project unticked** (`Namespace › Class`): two separate Class rows, never merged, because a class's identity includes its project. → `GroupingTreeTest.same_class_in_two_projects_stays_two_rows` (Task 4).
2. **Tests without a file under `Project › Folder › File`:** they now sit directly under the project instead of in today's "(no file)" group; nothing is dropped. → `GroupingTreeTest.tests_without_a_file_skip_folder_and_file` (Task 4).
3. **Show Failures Only on a split class:** a class split across folders, with the filter hiding one folder's tests, still counts as split, so excluding the visible Class row excludes only its tests. → `GroupingTreeTest.filters_dont_make_a_split_class_look_whole` (Task 4).
4. **Uid-only tests and classes in the global namespace:** `(no class)` tests skip Namespace and Class; a class with no namespace skips Namespace but still gets its Class row. → `GroupingTreeTest.uid_only_and_global_namespace_tests` (Task 4).
5. **Switching grouping while rows are selected or expanded:** no exception; rows whose key still exists stay selected; projects start expanded. → manual check in Task 8's sandbox step, plus `GroupingTreeTest.keys_are_unique_among_siblings` (Task 4).

---

### Task 1: Grouping value, settings and conversion

**Files:**
- Create: `plugin/src/main/kotlin/com/nocrunch/rider/view/Grouping.kt`
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/settings/NoCrunchSettings.kt` (add two state fields)
- Test: `plugin/src/test/kotlin/com/nocrunch/rider/view/GroupingTest.kt`

**Interfaces:**
- Produces:
  - `enum class GroupLevel(val title: String) { PROJECT("Project"), FOLDER("Folder"), FILE("File"), NAMESPACE("Namespace"), CLASS("Class") }` (persisted by name; never rename constants)
  - `data class Grouping(val levels: List<GroupLevel>)` with `val title: String` (levels' titles joined by `" › "`, `"Tests only"` when empty), `fun toSetting(): String` (names joined by `,`), `fun has(level: GroupLevel): Boolean`, `fun above(level: GroupLevel, other: GroupLevel): Boolean` (true when `level` comes before `other`; false if either is missing)
  - `companion object`: `val DEFAULT = Grouping(listOf(PROJECT, CLASS))`; `fun fromSetting(saved: String?, legacyViewMode: String?): Grouping` (blank `saved` → convert `legacyViewMode` via the Global Constraints table; unknown names skipped; duplicates dropped); `fun fromViewMode(mode: ViewMode): Grouping`
  - `object RecentGroupings { const val MAX = 3; fun remember(recent: List<Grouping>, used: Grouping): List<Grouping>; fun fromSetting(saved: String?): List<Grouping>; fun toSetting(recent: List<Grouping>): String }` (most recent first, no duplicates, at most `MAX`; entries joined by `;`)
  - `NoCrunchSettingsState`: `var grouping by string("")`, `var recentGroupings by string("")` (keep `viewMode` for conversion)

- [ ] **Step 1: Write the failing tests** in `GroupingTest`:
  - `converts_each_old_view`: `fromSetting("", "CLASSES")` → `[PROJECT, CLASS]`; `"NAMESPACES"` → `[PROJECT, NAMESPACE, CLASS]`; `"FILES"` → `[PROJECT, FOLDER, FILE]`; `fromSetting(null, null)` → `DEFAULT`.
  - `saved_grouping_wins_over_old_view`: `fromSetting("FOLDER,CLASS", "NAMESPACES").levels == [FOLDER, CLASS]`.
  - `round_trips_and_ignores_junk`: `Grouping(listOf(NAMESPACE, CLASS)).toSetting() == "NAMESPACE,CLASS"`; `fromSetting("CLASS,BOGUS,CLASS", null).levels == [CLASS]`.
  - `nothing_ticked_is_saved_as_a_dash`: `Grouping(emptyList()).toSetting() == "-"` and `fromSetting("-", "NAMESPACES").levels == []` (a blank setting means "never saved", so "nothing ticked" needs its own value).
  - `title_names_the_levels`: `Grouping(listOf(PROJECT, FOLDER, CLASS)).title == "Project › Folder › Class"`; `Grouping(emptyList()).title == "Tests only"`.
  - `recent_keeps_three_newest_without_duplicates`: remembering A, B, C, A, D gives `[D, A, C]`.
- [ ] **Step 2: Run** `./gradlew test --offline --tests '*GroupingTest*'` → fails to compile (types missing).
- [ ] **Step 3: Implement `Grouping.kt`** with the interfaces above, and add the two state fields to `NoCrunchSettingsState` with a doc comment each. Re-document `ViewMode` in `view/ViewOptions.kt` as kept only so old settings convert (nothing else may read it after Task 6).
- [ ] **Step 4: Run** the same command → PASS.
- [ ] **Step 5: Commit** `feat(plugin): grouping levels and recently used groupings in settings`.

### Task 2: Nested class names

**Files:**
- Modify: `samples/Sample Solution/tests/Shop.Core.Tests/` (add `NestedTests.cs`, MSTest) and `samples/Sample Solution/tests/Shop.Theory.Tests/` (add `NestedTests.cs`, xUnit)
- Create: `plugin/src/main/kotlin/com/nocrunch/rider/ui/TypeName.kt`
- Test: `plugin/src/test/kotlin/com/nocrunch/rider/ui/TypeNameTest.kt`; `engine/NoCrunch.Engine.Tests/Testing/MtpTestRunnerIntegrationTests.cs` (one test)

**Interfaces:**
- Produces: `data class TypeName(val namespace: List<String>, val classes: List<String>)` with `companion object { fun parse(type: String, knownTypes: Set<String>): TypeName }`. `classes` is outermost first (`["Outer", "Inner"]`); `namespace` may be empty. `UNKNOWN_CLASS` is never parsed (callers skip it).

- [ ] **Step 1: Add nested test classes to the sample** (both must pass): in each project, `public class Outer { [test] public void Outer_test_one() {} public class Inner { [test] public void Inner_test_one() {} } }` in each framework's attributes. Commit them (`test(sample): nested test classes`) before Step 2: the engine's tests copy the sample from `git archive HEAD`.
- [ ] **Step 2: Write an engine integration test** `Nested_classes_report_their_type_name` that runs `Shop.Core.Tests` and `Shop.Theory.Tests` and asserts on the `Id.Type` reported for `Inner_test_one`. First run it printing the two values, then pin what MTP actually reports (expected `Shop.Core.Tests.Outer+Inner` or `...Outer.Inner`) as the assertion, so a framework change is caught. Note the finding for NUnit and TUnit in `TypeName`'s doc comment as "not checked (no sample project)" unless you can check them locally without committing a project.
- [ ] **Step 3: Write the failing tests** in `TypeNameTest`:
  - `plus_marks_nesting`: `parse("Shop.Tests.Outer+Inner", emptySet())` → namespace `[Shop, Tests]`, classes `[Outer, Inner]`.
  - `dot_nesting_is_recognised_when_the_outer_class_is_known`: `parse("Shop.Tests.Outer.Inner", setOf("Shop.Tests.Outer"))` → classes `[Outer, Inner]`.
  - `dot_without_a_known_outer_class_is_a_namespace`: `parse("Shop.Tests.Outer.Inner", emptySet())` → namespace `[Shop, Tests, Outer]`, classes `[Inner]`.
  - `global_namespace`: `parse("Plain", emptySet())` → namespace `[]`, classes `[Plain]`.
- [ ] **Step 4: Implement `TypeName.parse`**: split on `+` first; otherwise treat the longest prefix that is in `knownTypes` (or is the `+`-free outer of a known type) as the outer class. `knownTypes` is every test class type in the same project. Doc comment records Step 2's finding.
- [ ] **Step 5: Run** plugin `--tests '*TypeNameTest*'` and engine `--filter "FullyQualifiedName~Nested_classes"` → PASS.
- [ ] **Step 6: Commit** `feat(plugin): recognise nested test classes`.

### Task 3: View rows for any grouping

**Files:**
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/ui/TreeView.kt` (row types; delete `ClassView`, `TreeView.of`, `byNamespace`, `byFile` once Task 4 replaces them)
- Test: `plugin/src/test/kotlin/com/nocrunch/rider/ui/GroupViewTest.kt`

**Interfaces:**
- Consumes: `TestNode`, `ProjectNode`, `Totals`, `TestExclusions.ruleFor` (existing).
- Produces:
  - `enum class GroupKind { PROJECT, FOLDER, FILE, NAMESPACE, CLASS }`
  - `data class GroupView(val key: String, override val label: String, val kind: GroupKind, val children: List<ViewNode>, override val parentExcluded: Boolean, val project: ProjectNode? = null, val wholeRule: ExclusionRuleDto? = null) : ViewNode`
    - `leaves` = all tests under it; `totals = Totals.of(leaves)`.
    - `excluded` = `leaves.isNotEmpty() && leaves.all { it.excluded }`; `partlyExcluded: Boolean` = some but not all; `excludedCount: Int` = `leaves.count { it.excluded }`.
    - `rules` = `listOfNotNull(wholeRule)` + rules of children that `wholeRule` doesn't cover (with a `wholeRule`, its own `TestView` children are covered; nested CLASS children still add theirs; without one, every child's rules), distinct.
    - `project` is set for PROJECT rows only (status for the renderer); `wholeRule` is the class or project rule when the row holds the whole class or project, else null.
  - `TestView` unchanged. `ViewNode` keeps its members.

- [ ] **Step 1: Write the failing tests** in `GroupViewTest` (build `TestNode`s directly):
  - `whole_class_row_writes_its_class_rule`: a CLASS `GroupView` with `wholeRule = ExclusionRuleDto(p, "Ns.C")` and two test children → `rules == [ExclusionRuleDto(p, "Ns.C")]`.
  - `partial_class_row_writes_its_tests`: same without `wholeRule` → the two tests' `ruleFor` rules.
  - `outer_class_covers_nested_classes`: outer whole (rule `Ns.Outer`) with one test and a nested whole CLASS child (rule `Ns.Outer+Inner`) → both class rules, nothing else.
  - `exclusion_state`: none / some / all excluded leaves → `excluded`, `partlyExcluded`, `excludedCount` = (false,false,0) / (false,true,1) / (true,false,2).
- [ ] **Step 2: Run** `--tests '*GroupViewTest*'` → compile failure.
- [ ] **Step 3: Implement** the new `GroupKind` and `GroupView` in `TreeView.kt` (leave old code compiling until Task 4).
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `refactor(plugin): one group row type for every grouping level`.

### Task 4: The grouping step

**Files:**
- Create: `plugin/src/main/kotlin/com/nocrunch/rider/ui/GroupingTree.kt`
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/ui/TestTreeModel.kt` (extract `keeps`), `TreeView.kt` (make the sorting reusable; remove old views)
- Test: `plugin/src/test/kotlin/com/nocrunch/rider/ui/GroupingTreeTest.kt`; rewrite `TreeViewTest.kt` cases onto `GroupingTree`

**Interfaces:**
- Consumes: `Grouping`, `GroupLevel` (Task 1); `TypeName.parse` (Task 2); `GroupView`, `GroupKind` (Task 3); `TreeView.sharedNamespace`, `TreeView.sortTests`, sorting comparators.
- Produces:
  - `TestTreeModel.keeps(test: TestNode, failuresOnly: Boolean, hideExcluded: Boolean): Boolean` (the per-test rule `filter` uses today; `filter` calls it)
  - `data class ProblemsGroup(val title: String, val count: Int)` (replaces `BuildErrorsGroup`)
  - `data class GroupedTree(val problems: List<ProjectNode>, val problemsTitle: String, val rows: List<ViewNode>)`
  - `object GroupingTree { fun build(projects: List<ProjectNode>, grouping: Grouping, sort: SortMode, failuresOnly: Boolean = false, hideExcluded: Boolean = false): GroupedTree }` where `projects` is the **unfiltered** model.

Rules the tests pin (see spec "Decisions made"):
- Group key = `"<kind>:<identity>"`: `project:<path>`, `folder:<path>` (path as used for the label's parent chain), `file:<path>`, `ns:<full namespace>`, `class:<project>|<type>`. Unique among siblings.
- Folder: relative to the project folder when PROJECT is above FOLDER, else repository-relative (`<project dir>/<relative path>`, from the existing `relativeTo` logic). Namespace: when PROJECT is *directly* above NAMESPACE, relative to the project name exactly as `byNamespace` does; otherwise full.
- Collapse a FOLDER or NAMESPACE group whose only child is a group of the same kind and that has no tests directly in it: label joined with `/` or `.`.
- CLASS rows nest by `TypeName.classes`; label = bare class name when NAMESPACE is above CLASS, else the full name with `TreeView.sharedNamespace` of the sibling classes trimmed (today's Classes view); nested classes under an outer row use the bare name.
- `wholeRule`: CLASS rows get `ExclusionRuleDto(project, type)` when every test of that class is under this row in the *unfiltered* grouping; PROJECT rows get `ProjectNode.rule` when every test of the project is under the row. Uid-only `(no class)` tests never get a class rule.
- Filters are applied after grouping with `TestTreeModel.keeps`; empty groups are dropped; broken-build projects are kept as `TestTreeModel.filter` keeps them today.
- `problems`/`problemsTitle`: per the Global Constraints; problem projects are excluded from `rows` only when they have no tests.
- Sorting: today's `sortViews` behaviour at every level (name order keeps folders first).

- [ ] **Step 1: Write the failing tests** in `GroupingTreeTest`, each a small fixture and exact expected labels/keys:
  - `classes_grouping_matches_todays_classes_view`, `namespaces_grouping_matches_todays_view_but_nested`, `files_grouping_matches_todays_view_but_nested`: port the fixtures from `TreeViewTest` and assert the same labels, except nesting (e.g. `Orders` › `Pricing` › `Discounts`).
  - `single_child_chains_collapse`: namespaces `A.B.C1`, `A.B.C2` (no tests in `A` or `A.B`) under `Namespace › Class` → one row `A.B` with children `C1`, `C2`.
  - `nested_classes_nest_under_their_outer_class` and `outer_class_without_own_tests_still_has_a_row`.
  - `split_class_rows_have_no_class_rule`: `Project › Folder › Class` with class `Ns.C` in two folders → both CLASS rows have `wholeRule == null`; a class in one folder has its class rule.
  - `project_below_namespace_holds_part_of_the_project`: `Namespace › Project` with two namespaces → PROJECT rows have `wholeRule == null`.
  - `problems_group_when_project_is_not_top`: crashed test project, broken non-test project → title `"Projects with problems"`, both listed; with `Project › Class` → title `"Build errors"`, non-test project only.
  - Review Focus tests 1–5 named above (`same_class_in_two_projects_stays_two_rows`, `tests_without_a_file_skip_folder_and_file`, `filters_dont_make_a_split_class_look_whole`, `uid_only_and_global_namespace_tests`, `keys_are_unique_among_siblings`).
  - `nothing_ticked_is_a_flat_list_of_tests`.
- [ ] **Step 2: Run** `--tests '*GroupingTreeTest*'` → compile failure.
- [ ] **Step 3: Implement `GroupingTree.build`**: recursive `group(tests, remainingLevels, context)` over `TestNode`s carrying their `ProjectNode`, then split classes and `wholeRule`, then filter, collapse and sort. Remove `TreeView.of`, `byNamespace`, `byFile`, `ClassView`, `splitBuildErrors` and `BuildErrorsGroup`; port or delete `TreeViewTest`/`BuildErrorsTest` cases accordingly.
- [ ] **Step 4: Run** the whole plugin suite → PASS (callers still compile only after Task 6; if needed, do Tasks 4–6 before running the full suite, but `GroupingTreeTest` must pass here).
- [ ] **Step 5: Commit** `feat(plugin): group the test tree by any ordered levels`.

### Task 5: Partial exclusion on rows

**Files:**
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/ui/RowPresentation.kt`, `TestTreeRenderer.kt`
- Test: `plugin/src/test/kotlin/com/nocrunch/rider/ui/RowPresentationTest.kt`

**Interfaces:**
- Consumes: `GroupView.partlyExcluded`, `excludedCount`, `project`, `ProblemsGroup` (Tasks 3–4).
- Produces: `RowIcon.partlyExcluded: Boolean = false` (the renderer layers a small excluded icon on the result icon's corner); `enum class CheckState { CHECKED, UNCHECKED, PARTIAL }` and `RowPresentation.checkState(o: Any?): CheckState?` (null when there's no checkbox).

- [ ] **Step 1: Write the failing tests**:
  - `partly_excluded_group_shows_count_and_badge`: a GroupView with 12 of 73 tests excluded → stats end with `Segment("  · 12 excluded", StatStyle.GREY_ITALIC)` and `icon.partlyExcluded == true`; the icon's status is the group's result, not EXCLUDED.
  - `wholly_excluded_group_looks_as_today`: no `· N excluded`, `(excluded)` note, EXCLUDED icon, no badge.
  - `project_group_shows_project_status`: a PROJECT GroupView whose `project` crashed → `test host crashed`, as `RowPresentation.project` shows today.
  - `problems_group_row`: `ProblemsGroup("Projects with problems", 2)` → name `"Projects with problems"`, `ERROR` icon, count `2`.
  - `check_state`: none/some/all excluded → CHECKED/PARTIAL/UNCHECKED.
- [ ] **Step 2: Run** `--tests '*RowPresentationTest*'` → fail.
- [ ] **Step 3: Implement**: PROJECT GroupViews reuse today's project status logic (refactor `project(o: ProjectNode)` to take the status from `GroupView.project`); the renderer draws `partlyExcluded` with a `LayeredIcon` (`AllIcons.RunConfigurations.TestIgnored`-style excluded icon, scaled small, bottom-right) and sets its `ThreeStateCheckBox` to `DONT_CARE` for PARTIAL.
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat(plugin): show partly excluded groups`.

### Task 6: Panel, selection and details wiring

**Files:**
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/ui/NoCrunchPanel.kt` (`specs`, `spec`, `render`), `TreeSelection.kt`, `DetailsText.kt`, `NoCrunchResults.kt` only if needed
- Test: `TreeSelectionTest.kt`, `DetailsTextTest.kt`

**Interfaces:**
- Consumes: `GroupingTree.build`, `GroupedTree`, `GroupView`, `RowPresentation.checkState`, `Grouping.fromSetting(settings.state.grouping, settings.state.viewMode)`.
- Produces: nothing new for later tasks besides a `currentGrouping` the panel reads.

- [ ] **Step 1: Write the failing tests**: `TreeSelectionTest.group_rows_run_and_exclude_what_they_show` (a split CLASS GroupView → `checkboxRules` = its tests' rules; PROJECT GroupView with `wholeRule` → `[project rule]`; `runKeys` of a GroupView = its leaves' run keys); `DetailsTextTest.project_group_details` (a PROJECT GroupView shows what a `ProjectNode` row shows today).
- [ ] **Step 2: Run** → fail.
- [ ] **Step 3: Implement**: `specs()` builds from `GroupingTree.build(s.projects, grouping, sortMode, failuresOnly, hideExcluded)` (no more `TestTreeModel.filter` call here); `HiddenBy.of(shownEmpty = rows.isEmpty() && problems.isEmpty())`; `TreeSpec(key = GroupView.key, checked = RowPresentation.checkState(v) == CHECKED, ...)`; the pinned group uses `ProblemsGroup` with key `problems`. `TreeSelection` and `DetailsText` handle `GroupView` of every kind (PROJECT rows behave as `ProjectNode` rows do).
- [ ] **Step 4: Run** the whole plugin suite and `./gradlew buildPlugin --offline` → PASS.
- [ ] **Step 5: Commit** `feat(plugin): the tool window shows the chosen grouping`.

### Task 7: Group by popup

**Files:**
- Create: `plugin/src/main/kotlin/com/nocrunch/rider/ui/GroupByModel.kt` (pure), `plugin/src/main/kotlin/com/nocrunch/rider/ui/GroupByPopup.kt` (Swing)
- Modify: `plugin/src/main/kotlin/com/nocrunch/rider/ui/NoCrunchActions.kt`
- Test: `plugin/src/test/kotlin/com/nocrunch/rider/ui/GroupByModelTest.kt`

**Interfaces:**
- Consumes: `Grouping`, `GroupLevel`, `RecentGroupings` (Task 1); `ActionHost.redraw()`.
- Produces: `class GroupByModel(initial: Grouping) { val rows: List<Pair<GroupLevel, Boolean>>; val grouping: Grouping; fun toggle(level: GroupLevel); fun moveUp(level: GroupLevel); fun moveDown(level: GroupLevel) }`. Rows list ticked levels first in grouping order, then unticked ones in `GroupLevel` order; `grouping` = the ticked ones in row order.

- [ ] **Step 1: Write the failing tests**: `ticked_levels_come_first_in_order`, `toggle_adds_at_the_end_of_the_ticked_levels` (ticking FOLDER on `Project › Class` → `Project › Class › Folder`), `move_up_and_down_reorder_ticked_levels` (FOLDER up twice → `Folder › Project › Class`), `moving_past_the_ends_does_nothing`, `unticking_everything_gives_tests_only`.
- [ ] **Step 2: Run** → fail. **Step 3: Implement `GroupByModel`.** **Step 4: Run** → PASS.
- [ ] **Step 5: Implement `GroupByPopup`**: a `JBPopupFactory.createComponentPopupBuilder` popup under the toolbar button with a "Recent" list (each `Grouping.title`, current one ticked, click applies and closes) and the level rows (checkbox, label, ↑/↓ icon buttons). Every change saves `settings.state.grouping` and calls `host.redraw()`; on close, `settings.state.recentGroupings = RecentGroupings.toSetting(RecentGroupings.remember(...))`. Replace the toolbar's "View As" group: a `Group By` button (`AllIcons.Actions.GroupBy`) opens the popup; the "Colour Test Names" choices move into their own dropdown (`AllIcons.Actions.Colors`), unchanged.
- [ ] **Step 6: Update `README.md`**: replace the "View as" wording with Group by (levels, recent groupings, nesting) and mention partly excluded groups.
- [ ] **Step 7: Run** the plugin suite and `buildPlugin` → PASS; `grep -rn "viewMode" plugin/src/main` shows only `Grouping.fromSetting` and the settings field.
- [ ] **Step 8: Commit** `feat(plugin): Group by popup with recently used groupings`.

### Task 8: End-to-end check

- [ ] **Step 1: Run both suites**: engine `dotnet test --solution NoCrunch.slnx` and plugin `./gradlew test buildPlugin --offline` → all pass.
- [ ] **Step 2: Hot-reload the sandbox** (`./gradlew prepareSandbox_runIde`) and check by hand with the sample solution:
  - each recent grouping and level combination from the spec's examples;
  - nested namespaces, folders and the nested sample classes;
  - partial exclusion count, badge and half-ticked checkbox; clicking the half tick includes everything;
  - switching grouping with rows selected (Review Focus 5);
  - an old `viewMode` setting converts (set it in the workspace file before opening).
- [ ] **Step 3: Note** anything not checked in the hand-off.
