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
| `-V`, `--version` | the version |
| `-h`, `--help` | the help |

## `self-update`

| Option | Meaning |
|---|---|
| `--check` | only says whether a newer release exists |
| `--to X.Y.Z` | installs this version, also an older one |
| `--force` | installs even when this version is already the latest |

## Environment variables

| Variable | Effect |
|---|---|
| `NO_COLOR` | no colors |
| `CHAINRUNNER_ASCII=1` | ASCII symbols instead of Unicode (old consoles) |
| `CHAINRUNNER_NO_UPDATE_CHECK=1` | no daily release check (also off when `CI` is set) |
| `CHAINRUNNER_UPDATE_URL` | a mirror of the GitHub releases API |
| `CHAINRUNNER_GITHUB_TOKEN`, `GH_TOKEN` | sent to the releases API when set |
| `HTTPS_PROXY`, `NO_PROXY` | the proxy for the release check and `self-update`, see [Proxy](#proxy) |

The `${NAME}` of the configuration are read from the environment, then from `.env`.

## Proxy

ChainRunner makes two kinds of HTTP requests, and they find their proxy differently.

| Requests | Proxy |
|---|---|
| the release check and `self-update` (to `api.github.com` and GitHub's downloads) | `HTTPS_PROXY`, or the Java properties below when they are set |
| the steps and the tokens (your APIs, your token endpoints) | only the Java properties below; `HTTPS_PROXY` is **not** used |
| the databases (PostgreSQL) | never a proxy: a direct connection to the host and port of the `url` |

### `HTTPS_PROXY` and `NO_PROXY`

- `HTTPS_PROXY` is the proxy's address: `http://proxy.example.com:8080`, or just
  `proxy.example.com:8080`. Without a port: 80 (443 for `https://...`). `https_proxy` in lower case
  works too. `HTTP_PROXY` is not read: the releases are only reached over HTTPS.
- A user name and password in the address (`http://user:secret@proxy:8080`) are ignored: a proxy that
  asks for a login is not supported.
- `NO_PROXY` lists the hosts reached without the proxy, with commas between them: an exact name
  (`github.example.com`), a domain with all its subdomains (`.example.com` or `*.example.com`), or
  `*` for every host. IP ranges (`10.0.0.0/8`) and ports are not understood. `localhost`, `127.x.x.x`
  and `::1` never go through the proxy.

```shell
# Linux, macOS
export HTTPS_PROXY=http://proxy.example.com:8080
export NO_PROXY=.example.com,github.example.com
```

```bat
:: Windows, cmd: for this window only
set HTTPS_PROXY=http://proxy.example.com:8080
:: Windows: for good, in the terminals opened afterwards
setx HTTPS_PROXY http://proxy.example.com:8080
```

```powershell
# Windows, PowerShell: for this window only
$env:HTTPS_PROXY = "http://proxy.example.com:8080"
```

### Java properties

The Java proxy properties apply to every HTTP request, the steps included, and when
`https.proxyHost` is set, `HTTPS_PROXY` is ignored. Give them before the other options, to the
native executable as to the jar:

```shell
chainrunner -Dhttps.proxyHost=proxy.example.com -Dhttps.proxyPort=8080 -Dhttp.nonProxyHosts="localhost|*.example.com" --run -s generate
java -Dhttps.proxyHost=proxy.example.com -Dhttps.proxyPort=8080 -jar chainrunner-X.Y.Z.jar
```

| Property | For |
|---|---|
| `https.proxyHost`, `https.proxyPort` | `https://` URLs |
| `http.proxyHost`, `http.proxyPort` | `http://` URLs |
| `http.nonProxyHosts` | hosts reached directly, with `\|` between them, with `*` as a wildcard: `localhost\|*.example.com` |

## Exit codes

| Code | Meaning |
|---|---|
| 0 | every step OK or not needed |
| 1 | a step failed or was not run; `self-update` failed |
| 2 | configuration or usage error |
