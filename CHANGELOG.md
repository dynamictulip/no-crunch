# Changelog

All notable changes to No Crunch are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Every pull request adds a line under **Unreleased** (dependency updates from Renovate are listed in the release notes
instead). Publishing a GitHub Release moves those lines under the release's version for you.

## [Unreleased]

First release: NCrunch-style continuous testing for .NET in JetBrains Rider on macOS.

### Added
- Continuous testing: save a file and No Crunch rebuilds only the projects the change affects, then reruns their tests
  and those of every test project that depends on them. Changes to repo-wide build files rerun everything.
- Isolated builds in a mirror of the repo (`.nocrunch/`), so Rider's own `bin`/`obj` are never touched and one broken
  project doesn't stop unrelated ones.
- Microsoft.Testing.Platform test projects (MSTest, xUnit v3, NUnit, TUnit) with per-test results, locations and stack
  traces; VSTest-only projects are shown as unsupported.
- No Crunch tool window: test tree by class, namespace or file; sorting by name, status or run time; Show Failures Only
  and Hide Excluded Tests; a progress bar with pass/fail counts; a details pane with messages and stack traces; and a
  status bar widget.
- Data-driven tests (theories) with their rows nested underneath, including rows only known at run time, with parameter
  names and optional colouring.
- Exclusions: untick projects, classes or single tests to stop them running automatically, saved to
  `<Solution>.nocrunch.user`. Excluded tests can still be run by hand.
- Run selected tests, run everything (including excluded tests), pause and resume, and debug a test or break into the
  first failing one.
- Settings for the delay after a change, parallelism, the `dotnet` path and a test timeout (default 30 seconds) that
  fails a test which stops reporting and stops its test host.
- Results survive restarts: last results show as stale until the engine catches up on what changed while Rider was
  closed.
