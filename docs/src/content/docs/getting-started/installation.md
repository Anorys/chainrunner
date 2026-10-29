---
title: Installation
description: Download the native executable, or run the jar.
---

## Native executable

Each [release](https://github.com/anorys/chainrunner/releases/latest) has an executable for:

| Platform | File |
|---|---|
| Windows x64 | `chainrunner-X.Y.Z-windows-x64.exe` |
| Linux x64 | `chainrunner-X.Y.Z-linux-x64` |
| macOS (Apple silicon) | `chainrunner-X.Y.Z-macos-arm64` |

Download yours, rename it `chainrunner.exe` (Windows) or `chainrunner` (and `chmod +x` it), and put it
in a folder of your `PATH`. Check it:

```shell
chainrunner --version
```

On macOS, a downloaded executable is quarantined; allow it once with
`xattr -d com.apple.quarantine chainrunner`.

## Updates

```shell
chainrunner self-update --check    # says whether a newer release exists
chainrunner self-update            # installs it
chainrunner self-update --to 0.2.0 # installs a given version, also an older one
```

`self-update` downloads the executable of your platform, checks its SHA-256 against the release's
`SHA256SUMS`, runs it once, and only then puts it in place of the running one (on Windows, the old
file is removed by the next run).

Once a day, ChainRunner looks up the latest release in the background and says after a run when a
newer one is out. It does not look in CI (`CI` is set), without a terminal, with `--no-update-check`,
or with `CHAINRUNNER_NO_UPDATE_CHECK=1`.

Behind a proxy, set `HTTPS_PROXY=http://proxy.example.com:8080` for the release check and
`self-update`; the steps use another setting, see [Proxy](../../reference/command-line/#proxy).
`CHAINRUNNER_UPDATE_URL` points to a mirror of the GitHub releases API, and
`CHAINRUNNER_GITHUB_TOKEN` (or `GH_TOKEN`) is sent to it when set.

## Jar

With Java 21, the jar of a release runs everywhere: `java -jar chainrunner-X.Y.Z.jar`. It cannot update
itself; download the next one.
