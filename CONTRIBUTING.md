# Contributing to No Crunch

Thanks for helping. No Crunch is a small project, so the process is light.

## Before you start

- For bugs, open an issue with your Rider version, .NET SDK version, test framework (MSTest, xUnit v3, NUnit or TUnit) and what you saw. A screenshot of the No Crunch tool window and the relevant part of `idea.log` help a lot.
- For new features or visible UI changes, open an issue first so we can agree the approach before you write code.

## Setting up

You need:

- macOS with Rider 2026.2+ installed at `~/Applications/Rider.app`, or set `RIDER_PATH` to another install
- the .NET 10 SDK

The repo has three parts:

```
engine/NoCrunch.Engine/        C# engine (stdio JSON-RPC server)
engine/NoCrunch.Engine.Tests/  engine tests (MSTest on MTP)
plugin/                        Kotlin Rider plugin (bundles the engine)
samples/Sample Solution/       sample solution used by tests and for trying things out
```

## Building and testing

Run both test suites before opening a pull request.

Engine:

```bash
dotnet test --solution NoCrunch.slnx
```

Plugin (also builds the installable zip in `plugin/build/distributions/`):

```bash
cd plugin && JAVA_HOME=~/Applications/Rider.app/Contents/jbr/Contents/Home ./gradlew test buildPlugin
```

## Trying your changes in Rider

Start a sandbox Rider with the sample solution:

```bash
cd plugin && JAVA_HOME=~/Applications/Rider.app/Contents/jbr/Contents/Home ./gradlew runIde -PopenSolution="$PWD/../samples/Sample Solution/Sample.slnx"
```

While it's open, rebuild and hot-reload the plugin and engine without restarting:

```bash
cd plugin && JAVA_HOME=~/Applications/Rider.app/Contents/jbr/Contents/Home ./gradlew prepareSandbox_runIde
```

Changes to `plugin.xml` may still need a sandbox restart.

## Making changes

- Keep pull requests focused: one fix or feature each.
- Add or update tests with your change. Pure logic (tree building, sorting, name splitting, exclusion rules) lives in plain classes so it can be unit-tested without Rider.
- The sample solution should build and pass on `main`. If you need failing tests for a demo, break them locally and don't commit that.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/), e.g. `feat(plugin): …`, `fix(engine): …`, `docs: …`.
- Add a line under **Unreleased** in [CHANGELOG.md](CHANGELOG.md) describing the change for someone using the plugin. CI checks for it on every pull request (Renovate's dependency updates are exempt).
- For UI changes, include a before and after screenshot in the pull request.

## CI and releases

Pull requests run the engine and plugin test suites on GitHub Actions (`.github/workflows/pr.yml`). CI has no Rider
installed, so the plugin builds against the Rider version pinned as `riderVersion` in `plugin/gradle.properties`;
locally it keeps using your installed Rider.

To release, publish a GitHub Release with a new tag such as `v0.3.0` (the notes you type don't matter; they're
replaced). `.github/workflows/release.yml` then moves the **Unreleased** notes in `CHANGELOG.md` under
`## [0.3.0] - <date>`, sets `pluginVersion` in `plugin/gradle.properties`, commits both to `main`, moves the tag onto
that commit, and rewrites the release notes: the changelog section, then GitHub's generated notes with Renovate's
dependency updates listed separately (`.github/release.yml`).

If `main` is protected so that changes need a pull request, the workflow's default token can't push the release
commit. Either let GitHub Actions bypass that rule, or add a `RELEASE_TOKEN` secret (a fine-grained token with
*Contents: read and write* that may bypass it), which the workflow uses when present.

[Renovate](https://docs.renovatebot.com/) keeps dependencies current (`renovate.json`): minor and patch updates merge
themselves once they're 7 days old and CI passes; major updates wait for review.

## Licence

By contributing, you agree that your contributions are licensed under the [MIT Licence](LICENSE).
