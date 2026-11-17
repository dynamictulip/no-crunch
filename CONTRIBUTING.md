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
- For UI changes, include a before and after screenshot in the pull request.

## Licence

By contributing, you agree that your contributions are licensed under the [MIT Licence](LICENSE).
