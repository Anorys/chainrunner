---
title: Command line
description: The options of chainrunner and chainrunner self-update, the environment variables, the exit codes.
---

```text
chainrunner [options]
chainrunner self-update [--check] [--to X.Y.Z] [--force]
```

Without `--run`, `chainrunner` opens the [terminal UI](../../guides/terminal-ui/); without a terminal,
it runs as with `--run`.

| Option | Meaning |
|---|---|
| `-c`, `--config FILE` | the configuration (default `chainrunner.yaml`) |
| `-e`, `--env NAME` | the environment (default `defaultEnvironment`) |
| `-s`, `--sequence NAME` | the sequence (default the first one) |
| `--var NAME=VALUE` | sets or replaces a variable; repeatable; a list is written `a,b,c` |
| `--run` | runs without the UI |
| `--dry-run` | prints every request, sends nothing |
| `-y`, `--yes` | does not ask for confirmation (with `--run`) |
| `--list` | lists the environments and the sequences |
| `--no-update-check` | does not look for a newer release |
| `--check-terminal` | says which terminal support loaded (diagnostics) |
| `--check-tls` | says where the trusted certificates come from (diagnostics) |
| `-V`, `--version` | the version |
| `-h`, `--help` | the help |

## `self-update`

| Option | Meaning |
|---|---|
| `--check` | only says whether a newer release exists |
| `--to X.Y.Z` | installs this version, also an older one |
| `--force` | installs even when this version is already the latest |

## Certificates

HTTPS requests (steps, tokens, release check, `self-update`) trust the certificate authorities that
come with Java and, on Windows, also those of the Windows root store (*Trusted Root Certification
Authorities*, of the machine and of the user). A company proxy that decrypts HTTPS is therefore trusted
as soon as its certificate is installed in Windows, as for the browsers. `chainrunner --check-tls`
shows what was loaded:

```text
TLS trust: Java (146 certificates) + Windows (63 certificates)
```

On Linux and macOS, only the Java list is used; another one can be given with
`-Djavax.net.ssl.trustStore=/path/to/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit`.
`options.insecureTls: true` turns the check off for the steps (test servers only).

## Environment variables

| Variable | Effect |
|---|---|
| `NO_COLOR` | no colors |
| `CHAINRUNNER_ASCII=1` | ASCII symbols instead of Unicode (old consoles) |
| `CHAINRUNNER_NO_UPDATE_CHECK=1` | no daily release check (also off when `CI` is set) |
| `CHAINRUNNER_UPDATE_URL` | a mirror of the GitHub releases API |
| `CHAINRUNNER_GITHUB_TOKEN`, `GH_TOKEN` | sent to the releases API when set |
| `HTTPS_PROXY`, `NO_PROXY` | the proxy for the release check and `self-update` |

The `${NAME}` of the configuration are read from the environment, then from `.env`.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | every step OK or not needed |
| 1 | a step failed or was not run; `self-update` failed |
| 2 | configuration or usage error |
