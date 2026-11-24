# Working in this repo

Details live in [README.md](README.md) and [CONTRIBUTING.md](CONTRIBUTING.md). The commands below are the ones that are easy to get wrong.

## Sandbox Rider (to see plugin changes)

- Gradle needs `JAVA_HOME=~/Applications/Rider.app/Contents/jbr/Contents/Home`.
- **Sandbox not running:** start it (in the background, it stays open):
  `cd plugin && ./gradlew runIde -PopenSolution="$PWD/../samples/Sample Solution/Sample.slnx"`
- **Sandbox already running:** do NOT restart it. Hot-reload the new plugin and engine build:
  `cd plugin && ./gradlew prepareSandbox_runIde`
  It picks up the new build within a few seconds. Only a `plugin.xml` change needs a restart.
- "Update the sandbox" means the hot reload. Restart only if asked, or for a `plugin.xml` change.
- The user's own Rider and their installed plugin copy run alongside the sandbox. Never kill those, only the sandbox's gradle process.

## Tests

- Engine: `dotnet test --project engine/NoCrunch.Engine.Tests`
- Plugin: `cd plugin && ./gradlew test`

## Git

- `git commit` needs the sandbox disabled (gpg signing).
- The user often has uncommitted edits in `samples/Sample Solution/` to make tests fail for demos. Never touch or commit them. The engine tests use `git archive HEAD` of the sample, so they don't leak in.
