# Editor gutter markers (design)

Date: 2026-11-20. Status: awaiting approval.

## Intent

NCrunch-style markers in the editor gutter, so you see test status without opening the No Crunch tool window. This is the first step towards full NCrunch dots. Line-level coverage doesn't exist yet (see Non-goals), so this version marks only what the plugin already knows: test methods and the line a failure came from.

## Goals

- A gutter marker on each test method showing its latest outcome: passing, failing, and whether that result is stale.
- A marker on the line a failing test threw from, in test or production code (the cause of failure).
- Colour-blind friendly: shape carries the meaning, colour only reinforces it.
- A tool window toolbar toggle turns the markers on and off. The choice persists per project and applies to open editors immediately.

## Non-goals

- Line coverage, "hit only by passing tests", "hit by failing tests" on arbitrary production lines, and the "not covered" marker. These need per-test coverage in the engine, which is a separate project.
- Clicking a marker to run, debug, exclude or navigate. Tooltip only for now.
- Any change to the engine or the wire protocol.

## Anti-goals

- Markers that mislead. No marker beats a wrong marker, so tests that haven't run, are skipped or are excluded get none.
- Colour as the only signal.
- Two palettes: the editor markers and the tool window must not use different greens and reds.

## Marker set (chosen from the mockups: style A, pass green)

| State | Shape | Colour |
|---|---|---|
| Passing | filled circle | green `#009E73` |
| Passing, stale | hollow circle | green |
| Failing | filled diamond | the stock failed-test icon red |
| Failing, stale | hollow diamond | red |
| Cause of failure | bold cross | red |

Only two hues, green and red. Failing and cause share the red, and shape tells them apart. The stale variant is always the same shape drawn hollow. There is no stale cause marker: a stale cause line shows as failing, stale until the rerun. The "not covered" state is not drawn in this version.

These are an Okabe-Ito bluish green and the stock failed-test icon's red, read from the icon at runtime so it follows the theme (a vermillion and a crimson were tried first), chosen over a traditional green and red because they stay separable under the common types of colour blindness. They have similar lightness, so shape is what keeps them apart for red-green colour blindness; colour reinforces it.

## One palette across the app

The markers and the tool window share one set of colours, defined once as a small palette object with light and dark variants (`JBColor`), so they can't drift:

- **Progress bar:** passing and failing use the palette green and red, replacing `#3F9E4D` and `#D64545`. Running (blue) and neutral (grey) are unchanged.
- **Tree text:** passed and failed names use the palette green and red. The muted red for stale failures becomes a faded version of the palette red, and passed-but-stale gets a faded green the same way, if the tree currently fades it.
- **Tree icons:** Rider's stock tick and cross icons stay as they are. Replacing them with the marker shapes is out of scope here.

## Behaviour

- **Test method markers.** Each test result carries `filePath` and `line`. Markers come from `shownOutcome` (the current outcome, or the last one while stale, queued or running) and `freshness` (non-null means stale), the same fields the tool window uses. A method with several results (theory variants) shows the worst outcome, using the existing worst-outcome ordering. Tests with no outcome yet, skipped, or excluded show nothing.
- **Cause markers.** For each failing result, take the failure's stack trace and find the first frame whose file is inside the repo and exists on disk (this skips framework frames). That `file:line` gets the cross, or the failing-stale diamond when the result is stale. The tool window already links stack frames by path, and the engine rewrites mirror paths to repo paths, so frames should already point at real files; the plan must verify this on a real failure.
- **One marker per line.** Precedence: cause, then failing, then passing. Within a state, a current result beats a stale one.
- **Live updates.** Markers repaint when results change, when an editor opens, and when the toggle flips. Markers are anchored to the document, so they move with the line as you type and don't drift.
- **Tooltip.** Shows the state and the test name or failure message, for example "Failing: Total_applies_coupon" or "Cause of failure: NullReferenceException".

## Constraints

- Rider only: the C# editor is owned by Rider's .NET backend, so markers are painted from the Kotlin frontend onto the editor's markup (range highlighters with a gutter icon) rather than through language-specific line-marker providers.
- The plugin must stay cheap on large solutions: only open editors are painted, and work is batched when results arrive.

## Approaches considered

1. **Frontend markup highlighters per open editor (chosen).** Works for any file regardless of language support and needs no PSI. Costs a small listener that attaches to editors as they open.
2. **A line-marker provider.** It is the usual IDE pattern, but it relies on the language's PSI being available in the frontend, which it isn't reliably for C# in Rider.
3. **Backend (ReSharper) highlighting.** It would give richer integration but needs a .NET backend component alongside the Kotlin plugin, which is far too much for this change.

## Decisions made by me (please check)

- The toggle is on by default.
- The toolbar button is named "Show Editor Markers" with a neutral icon, and sits next to the other display actions.
- Only the top in-repo stack frame gets the cause marker, not every frame.
- Markers are painted in code, not from SVG resources, so they scale with the display. Dark-theme variants of the palette are slightly lighter versions of the same hues; I'll pick them when building.
- The tool window's stale failure red and any stale-passing fade are derived from the palette (faded), not separate colours.

## Left to the builder

- Exact event wiring for result updates and editor open/close.
- Where the marker computation lives. Keep it as a pure function from results to line markers so it can be unit tested without the IDE.
- Tooltip wording.

## Risks

- **Rendering in Rider's C# editor.** Confirm early that range highlighters with gutter icons render there. If they don't, stop and rethink before building the rest.
- **Stack trace paths.** If frames point into the mirror, they must be mapped back to repo files.

## Testing

Unit tests for the pure marker computation (precedence, stale handling, theories, stack frame selection). Manual check in Rider for rendering, the toggle, and colour-blind legibility.
