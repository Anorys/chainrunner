---
title: The terminal UI
description: Run a sequence, a step or the rest of a sequence, and read what happened.
---

`chainrunner` without `--run` opens the UI on the default environment and the first sequence
(`-e` and `-s` choose others).

![The UI before a run](../../../assets/screenshots/tui-ready.png)

- **Header**: the environment (red when protected), its API hosts, and one token per API: `✔ orders
  4 min` (valid for 4 more minutes), `◌` (none yet), `✘` (refused).
- **Tabs**: the sequences, `1` to `9` or `tab` to switch.
- **Steps**: one line per step to make, after `forEach`; the result column shows the HTTP status,
  `found` or `#4` (the attempt of a running poll), `unneeded` or `timeout`.
- **Detail**: the selected step's request (or SQL query with its values), then its response or row.
  Before a run it shows a preview; a value that an earlier step will extract is shown as unknown.
- **Variables**: the variables of the run, `✎` marks those changed in this session.

## Keys

| Key | Does |
|---|---|
| `r` | runs the whole sequence |
| `enter` | runs the selected step alone |
| `R` | runs from the selected step to the end |
| `x` | stops after the current step (a running poll stops at once) |
| `e` | switches environment |
| `v` | edits a variable for this session (the file is not changed) |
| `t` | asks every API for a token now, and shows what a refusal sent |
| `↑ ↓` `j k` | selects a step |
| `pgup pgdn` `J K` | scrolls the detail |
| `l` | shows the path of the last log |
| `?` | the help |
| `q` `ctrl-c` | quits (asks first while a run goes on) |

Every run asks for confirmation; on a protected environment its name must be typed.

![The confirmation before a run](../../../assets/screenshots/tui-confirm.png)

## Passwords

A password left empty in the configuration (`password: ${DEV_PASSWORD:-}` with no value in `.env`) is
asked for before the first run that needs it, and kept in memory until the UI closes. A token or a
database connection refused with a typed password asks for it again at the next run.

## When the terminal is small

From 80×24, the detail moves under the steps and the wordmark becomes one line. `CHAINRUNNER_ASCII=1`
replaces the Unicode symbols for old consoles, and `NO_COLOR` turns the colors off.
