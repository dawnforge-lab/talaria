# Contributing to Talaria

Thanks for looking. Talaria is a personal project with one maintainer, shared as it is. Issues
and pull requests are welcome; replies may take a while, and not every idea will be taken. If you'd
like to help maintain it, say so in an issue.

## Before you start

- **Build it** with [docs/building.md](docs/building.md). Push needs a Firebase project and a
  relay of your own, but most changes don't touch push.
- **Open an issue first** for anything bigger than a fix, so we can agree on the shape before you
  spend time on it.
- **Look for what exists.** Talaria follows Hermes' own APIs and the iOS app Hermes Conduit. Before
  designing something new, check how Hermes (its dashboard and gateway) or Conduit already does it,
  and port that.
- **Hermes stays as it is.** A gap is fixed in the app or in the
  [Talaria plugin](https://github.com/dawnforge-lab/talaria-plugin), never by patching Hermes.

## Connected features

Many features have siblings in the plugin (and some in its
[relay](https://github.com/dawnforge-lab/talaria-plugin/tree/main/relay)): a screen here, a route or tool there.
Names the three share (routes, push keys, encryption labels, tool names, links) must change on
every side together. When you change one, find its siblings and say in the PR what happened to
each: done (link the PR), not needed (and why), or left open.

## Pull requests

- Keep each PR to one change, with a title that says what changes for the user.
- Run the checks first; CI runs the same:

  ```sh
  ./gradlew spotlessApply spotlessCheck lint test assembleDebug -PwarningsAsErrors=true
  ```

- Add or change tests for what you change. Most logic lives in plain Kotlin modules
  (`core:model`, `core:net`, `core:gateway`, `core:chat`) and is tested without a device.
- Say how you tested it: which Hermes version, which phone or emulator, and what you tapped.
- Match the code around you: its naming, its comment density, and comments that say why rather
  than what. ktlint settles formatting.

## Reviews

Each pull request gets an automated review: an AI model reads the diff and posts its findings
as one comment, updated on every push. It's advice, not a gate: the maintainer reviews every
PR and does the merging. PRs from forks are reviewed once a maintainer has read them and added
the `run-review` label. `main` only changes through pull requests whose checks pass.

## Good first issues

Issues labelled [good first issue](https://github.com/dawnforge-lab/talaria/labels/good%20first%20issue)
are small and self-contained. Ask in one before starting if you're unsure.

## Security

Please don't open a public issue for a security problem. Use GitHub's private vulnerability
reporting on this repository instead.
