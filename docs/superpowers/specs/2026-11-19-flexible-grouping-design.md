# Flexible grouping for the No Crunch test tree

Date: 2026-11-19. Status: for approval.

## Why

Large solutions are often part-way through a reorganisation, such as a namespace move.

- Sometimes namespaces are the best way to classify the code under test, so you want to browse by namespace.
- Sometimes the namespaces are still wrong, and the folder structure says more, so you want to browse by folder.
- Tests for one class are sometimes split across several files as a `partial class`. Grouping by class should bring
  them back together.

No single fixed grouping serves all three, and people switch between them as they work. What's needed is
flexibility: choose which levels the tree groups by, and in what order.

Today's grouping also gets in the way:
- Namespaces are listed flat, so `Orders.Pricing` and `Orders.Pricing.Discounts` appear as siblings rather than
  nested.
- A group that contains some excluded tests looks the same as one with none, or (on the project row) can look
  wholly excluded.

## How it works today

`plugin/src/main/kotlin/com/nocrunch/rider/ui/TreeView.kt` turns the model (`ProjectNode` › `ClassNode` › `TestNode`,
built in `TestTreeModel`) into view rows. The toolbar's "View as" menu picks one of three fixed views (`ViewMode` in
`com.nocrunch.rider.view`):

| View | Shape |
|---|---|
| Classes (default) | Project › Class › Test. A namespace shared by all of a project's classes is trimmed from their labels (`sharedNamespace`). |
| Namespaces | Project › Namespace › Class › Test. Each namespace is one flat row, with the project name trimmed off (`byNamespace`). |
| Folders and files | Project › Folder › File › Test. Folders are flat (`Unit/Pricing` is one row); tests sit directly under their file, without a class row (`byFile`). |

- Projects are always the top level. Broken non-test projects are pinned in a "Build errors" group above the rest
  (`TreeView.splitBuildErrors`).
- View rows are `TestView`, `ClassView` and `GroupView(kind = NAMESPACE | FOLDER | FILE)`. Each works out its totals
  (`Totals`) and an `excluded` flag. A group is excluded only when every child is.
- Rows show an icon, the name, and grey stats on the right; `RowPresentation` (pure, unit-tested) decides what each
  row shows, and `TestTreeRenderer` draws it.
- Including or excluding a group writes exclusion rules for everything in it (`ViewNode.rules`).
- The tree is updated in place by `TreeSync`, matching rows by a key built from their path (`pathKey`).
- The choice of view is stored per solution in `NoCrunchSettings` (workspace file) as `viewMode`, alongside
  `sortMode`.

## Goals

1. **Choose and order the grouping levels:** from Project, Folder, File, Namespace and Class, any subset, in any order.
   The test is always the bottom level, and theory data rows stay nested under their test, as now.
2. **Nested namespaces and folders:** `Orders` › `Pricing` › `Discounts`, and `Unit` › `Pricing`. Single-child chains
   collapse into one row (`Orders.Pricing` when `Orders` has nothing else), as Rider's "compact middle packages"
   does.
3. **A partial class is one Class row,** whatever files its tests live in. This already holds wherever Class is a
   level, because tests are grouped by their class's full name.
4. **Quick switching:** the grouping popup lists the last few groupings you used, so switching between
   e.g. `Project › Namespace › Class` and `Project › Folder › Class` is one click.
5. **Partial exclusion is visible** on any group row (project, folder, file, namespace, class), with all three markers
   chosen:
   - a grey `· N excluded` after the row's counts;
   - a small crossed-out-eye badge on the corner of the row's result icon;
   - a half-ticked checkbox while choosing tests (the pencil toolbar toggle).
   Wholly excluded groups keep today's look: crossed-out-eye icon, grey name, `(excluded)`.

## Non-goals

- Grouping by anything other than code structure (outcome, duration, run kind, categories/traits). Sorting already
  covers status and duration.
- Changing how sorting, filtering (Show Failures Only, Hide Excluded Tests) or exclusion rules work. They apply to
  the new groupings as they do today.
- Changing the engine or the protocol. Everything needed (project, type, method, file path) is already in each
  `TestResultDto`.

## Anti-goals (this would be a failure even if it worked)

- Existing users' trees change unexpectedly on upgrade. Today's choice must carry over exactly.
- Switching grouping loses expansion and selection more than necessary, or makes the tree slow on large solutions
  (thousands of tests).
- The popup becomes a settings dialog. It should be a quick, light popup you use while working.

## Decisions made

### Levels

| Level | Groups by | Label | Nests? |
|---|---|---|---|
| Project | `ProjectNode` | project name | no |
| Folder | the test file's folder: relative to its project's folder when Project is above Folder, otherwise relative to the repository root (so same-named folders in different projects stay apart) | one folder segment per row (after collapsing) | yes, single-child chains collapsed |
| File | the test file | file name | no |
| Namespace | the class's namespace (`type` before the last `.`): when Project is directly above Namespace, relative to the project's name as today (`Shop.Tests.Pricing` in project `Shop.Tests` shows as `Pricing`; classes in `Shop.Tests` itself sit directly under the project); otherwise the full namespace | one namespace segment per row (after collapsing) | yes, single-child chains collapsed |
| Class | the class (nested classes nest under their outer class, see "Nested classes") | with a Namespace level above it: the bare class name. Otherwise today's label: the full name with the namespace shared by all the classes under the same parent trimmed (`TreeView.sharedNamespace`), so `Pricing.CalcTests`, or the full name when nothing is shared | no |

The Folder, Namespace and Class label rules are designer's calls made after review, chosen so converted views look
exactly as they do today.

- Any subset, any order. Examples:
  - `Project › Class` (today's default)
  - `Project › Namespace › Class`
  - `Project › Folder › Class` (the namespace-move escape hatch)
  - `Project › Folder › File` (today's files view)
  - `Namespace › Class` with Project unticked, giving one tree across all test projects
- A test that has no value for a level skips that level, rather than landing in an "unknown" group. Examples: no
  file location (Folder and File), no namespace or no class (uid-only tests).
- When nothing is ticked, the tree is a flat list of tests.

### Nested classes (decided)

A test class can be declared inside another class. The Class level nests the way Namespace does:
- An inner class's row sits under its outer class's row.
- The outer class's row holds the outer class's own tests and the inner class rows.

```
▾ {} Pricing
  ▾ C Outer
      Outer_test_one
    ▾ C Inner
        Inner_test_one
```

- The namespace is the part of the type name before the outermost class. A nested class must never be mistaken for
  a namespace (`Outer` above is a class, not a namespace).
- Test frameworks may report a nested class's name as `Ns.Outer+Inner` or `Ns.Outer.Inner`. The builder must check
  what each supported framework (MSTest, xUnit v3, NUnit, TUnit) actually reports through MTP, and handle each
  form, with a nested test class added to the sample solution to prove it.
  - If a framework's report can't tell `Ns.Outer.Inner` (nested) from `Ns.Outer.Inner` (namespace `Ns.Outer`),
    say so in the plan. One option is the class's declaring type from the test's location, if MTP provides it.
- Excluding an outer class's row follows "only what's shown": it excludes the outer class and every class nested
  under that row. The exclusion rules themselves treat each class as separate, so this writes one class rule per
  class under the row.
- Collapsing doesn't apply to classes. An outer class with no tests of its own still gets its own row, because it's
  a real class in the code.

### Project status without a top-level Project row (decided)

A test project's row today carries project-level states:
- build failed or blocked
- test host crashed
- unsupported (VSTest, or an unsupported MTP version)
- project-wide exclusion
- no tests at all, for example because it never built

When Project is not the top level (unticked, or below another level), these would otherwise have nowhere to show,
and a test project with no tests would vanish.

- The pinned "Build errors" group becomes **"Projects with problems"**. When Project isn't the top level, it lists
  every project whose build failed or was blocked, whose test host crashed, or whose test framework isn't supported.
  Each entry shows the same status text its project row shows today. Broken non-test projects are listed, as now.
- When Project is the top level, the group behaves as "Build errors" does today: broken non-test projects only.
  Test projects show their status on their own rows.
- A Project row lower in the tree (e.g. `Namespace › Project`) still shows its project's build and run status.

### Excluding a group (decided)

Ticking or unticking a group includes or excludes only what that row shows:
- A **Class** row that holds the whole class writes a class rule, as today, so tests added to the class later are
  covered.
- A **Project** row that holds the whole project writes a project rule, as today.
- A Class or Project row that holds only **part** of its class or project writes rules for just the tests under that
  row. The case: `Project › Folder › Class`, with a partial class spread across folders. A click never excludes tests
  that aren't under the clicked row.
- **Folder, File and Namespace** rows write rules for the classes or tests under them, as today.
- Whether a group shows as wholly excluded, partly excluded or neither is worked out from the tests under it, so it
  matches what the click affected.

### Choosing: the Group by popup

The toolbar's "View as" button opens a light popup (option A from the mockups):

```
Recent
  ✓ Project › Namespace › Class
    Project › Folder › Class
───────────────────────────────
Group by (top to bottom)
  ☑ Project      ↑↓
  ☑ Namespace    ↑↓
  ☑ Class        ↑↓
  ☐ Folder       ↑↓
  ☐ File         ↑↓
```

- **Recent:** the last 2–3 groupings actually used, named by their levels, current one ticked. Clicking one switches
  to it. There are no fixed presets.
- **Group by:** every level with a tick box and up/down arrows. Ticked levels come first, in order. The tree updates
  as you click, without an OK button.
- A grouping becomes "recently used" once you've stayed on it: not every intermediate state while clicking
  through the tick boxes.

### Settings

- The ordered list of levels and the recent list are stored per solution, in `NoCrunchSettings` (workspace file), like
  `viewMode` today.
- On first load, an existing `viewMode` is converted:
  - Classes → `Project › Class`
  - Namespaces → `Project › Namespace › Class`
  - Folders and files → `Project › Folder › File`

  After conversion, the tree looks as it did before, apart from namespaces and folders now nesting.
- The new default, with no setting saved, is `Project › Class`.

### Partial exclusion

- A group is **wholly excluded** when every test under it is excluded (today's rule), **partly excluded** when some
  but not all are, otherwise not excluded.
- The `· N excluded` count gives the number of excluded tests under the group. It counts tests, not theory data rows
  (the same unit as the other counts).
- The half-ticked checkbox appears only in choosing mode (pencil on), where checkboxes are shown. Clicking it
  includes everything under the group, matching how a tri-state checkbox normally behaves. (Builder: confirm against
  IntelliJ's `CheckedTreeNode` behaviour; if it naturally excludes instead, keep its behaviour and note it.)

## Approaches considered

1. **One grouping step driven by the ordered levels (chosen).** Replace `TreeView`'s three hard-wired views with one
   function that groups tests by the first level, then recurses inside each group with the remaining levels.
   - Folder and Namespace build nested groups and collapse single-child chains.
   - Each group works out its totals, result and exclusion state (none, partial, whole) once, when built, as group
     rows do today.
   - This gives every combination asked for with one code path, and it's pure and unit-testable.
2. **Keep the three views and add nested variants.** A longer fixed menu. Rejected: it gives none of the mixing they
   asked for (e.g. `Folder › Class`).
3. **Reuse Rider's own Unit Tests window grouping.** Rejected: it's internal to Rider's test runner and not a plugin
   API, and it would mean dropping No Crunch's own tested model.

## Constraints

- Pure logic (the grouping step, labels, collapsing, partial exclusion, the recent list, settings conversion) lives in
  plain classes with unit tests, per CONTRIBUTING.md. Swing code stays thin.
- `TreeSync` keeps rows stable by key. Group keys must stay stable across updates, and must be unique among siblings,
  e.g. level kind plus the group's full path (`ns:Orders.Pricing`), not just its label.
- Expansion and selection survive result updates as now. After switching grouping, the tree may reset expansion;
  projects start expanded, as on first load (`TreeSync.syncTree` expands newly appearing top-level rows).
- Exclusion actions go through the existing `ViewNode.rules` and rule shapes (`ExclusionRuleDto`). See "Excluding a
  group" for which rules a split Class or Project row writes. The exclusion rules themselves, and the shared cases in
  `testdata/exclusions.json`, don't change.
- The tree builds off the EDT in `NoCrunchResults` and syncs on the EDT. Grouping must stay cheap enough for
  thousands of tests at the existing 100 ms update rate.
- Settings must keep loading for existing users: stored `viewMode`, `sortMode` and `nameColouring` values must not
  change meaning.

## Left to the builder

- The exact data shapes: replacing `ViewMode` with a level list, generalising `GroupView` and `GroupKind`, and whether
  `ClassView` stays a separate type.
- Icons for the new group kinds (folder, file, namespace and class already have icons in `KindIcons`). How the badge
  is drawn on the result icon (a layered icon is the usual IntelliJ way).
- Popup building blocks (e.g. a `JBPopup` hosting a small custom panel), keyboard handling, and the exact Recent
  wording.
- How "stayed on it" is decided for the recent list. Closing the popup is a reasonable trigger.
- Test layout and names.
