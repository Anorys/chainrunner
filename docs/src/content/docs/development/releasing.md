---
title: Releasing
description: How a ChainRunner release is published.
---

A release is a GitHub release `vX.Y.Z` with these files:

- `chainrunner-X.Y.Z-windows-x64.exe`
- `chainrunner-X.Y.Z-linux-x64`
- `chainrunner-X.Y.Z-macos-arm64`
- `chainrunner-X.Y.Z.jar`
- `SHA256SUMS`

## After each merged pull request

Every pull request merged into `main` publishes the next release on its own. Its title, in the
[Conventional Commits](https://www.conventionalcommits.org/) style, or its labels say how far the
version moves:

| Pull request | Example | Version |
|---|---|---|
| a breaking change: `!` after the type, a line starting `BREAKING CHANGE:` in the description, or the label `breaking` | `feat!: rename the step keys` | `1.4.2` → `2.0.0` |
| a feature: `feat:`, or the label `feature` or `enhancement` | `feat(db): named databases` | `1.4.2` → `1.5.0` |
| anything else: a fix, docs, CI... | `fix: retry on 401 once` | `1.4.2` → `1.4.3` |

Commits pushed to `main` without a pull request publish nothing.

## By hand

To publish one by hand, push a tag `vX.Y.Z` on `main`:

```shell
git tag v0.2.0
git push origin v0.2.0
```

or run the **Release** workflow from the Actions tab with version `X.Y.Z`. It sets the version, runs the
tests, builds the three executables and publishes them with generated release notes, creating the tag
when needed.

:::caution[File names]
`chainrunner self-update` looks for these exact names. Changing them means changing
`SelfUpdater.assetName` in the same release.
:::

Once the release exists, every `chainrunner` run in a terminal shows the update notice within a day,
and `chainrunner self-update` installs it.

## Continuous integration

Every push and pull request runs the tests on Linux and Windows, then builds the native executable on
Linux, Windows and macOS and runs it against the example configuration. The executables of each run are
kept as workflow artifacts.

The documentation is built on every change under `docs/` and published to GitHub Pages from `main`.
