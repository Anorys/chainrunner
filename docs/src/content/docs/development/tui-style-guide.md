---
title: UI style guide
description: The visual rules of the terminal UI, the Anorys palette in the terminal.
---

Every color and symbol of the UI comes from `com.anorys.chainrunner.tui.theme.Theme`; views never
hard-code one. Change the theme there and the whole UI follows, `NO_COLOR` and the ASCII fallback
included.

## Principles

The UI follows the Anorys visual identity (dark mode): green to act, grey to structure, one color and
one symbol per state.

1. **Status first.** Errors are the only warm saturated color on screen; healthy rows stay quiet.
2. **Grey structures, green acts.** Large areas use the grey neutrals; the greens mark what you can act
   on: the selection, the active tab, the key letters, what is running.
3. **The orange point is rare.** As in the logo, orange marks the one thing happening now: the
   selected row's marker. Never a state color.
4. **Every state has a symbol and a word.** Color is never the only signal.
5. **Keys are always visible.** The footer shows the keys that work on the current screen.

## Palette

The UI paints its own background, so it looks the same in any terminal theme.

| Token | Hex | Use |
|---|---|---|
| `CRUST` | `#171919` | header and footer bars |
| `MANTLE` | `#1B1D1D` | dialogs |
| `BASE` | `#202323` | main background |
| `SURFACE0` | `#163631` | selected row |
| `SURFACE1` | `#303535` | borders, key chips |
| `OVERLAY` | `#6E7875` | muted text: hints, units |
| `SUBTEXT` | `#A2ABA8` | secondary text |
| `TEXT` | `#E3E7E6` | primary text |
| `FACET_DARK`, `FACET`, `FACET_LIGHT` | `#08473F`, `#0E7A6B`, `#17A58F` | logo; the brand gradient |
| `ACCENT` | `#4FC1B1` | focus, active tab, key letters, running steps |
| `SPARK` | `#FF7A1A` | the logo's orange point, the selected row's marker |

## States

| State | Token | Hex | Symbol | ASCII |
|---|---|---|---|---|
| OK, found | `GREEN` | `#5FD39A` | `✔` | `+` |
| Warning, timeout | `AMBER` | `#F0A766` | `▲` | `!` |
| Failed, refused | `RED` | `#F07178` | `✘` | `x` |
| Running, waiting for a row | `ACCENT` | `#4FC1B1` | spinner | `*` |
| Not run yet, skipped, not needed | `OVERLAY` | `#6E7875` | `◌` | `.` |

HTTP methods are colored by kind: reads in `BLUE`, writes in `ACCENT`, `DELETE` in `AMBER`, SQL in
`VIOLET`.

## Components

- **Header** (2 rows, `CRUST`): the faceted hexagon, the wordmark in the brand gradient (one line of
  plain letters below 110 columns), the configuration path, the environment badge and the tokens.
- **Tabs**: the sequences; the active one bold `ACCENT` with a gradient underline.
- **Panels**: rounded borders, the focused one `ACCENT`.
- **Tables**: no inner grid lines; the selected row on `SURFACE0` with a `▌` marker in `SPARK`.
- **Footer**: key chips, the key in `ACCENT` on `SURFACE1`, the action in `SUBTEXT`.
- **Toasts**: one line above the footer, colored by severity, gone after a few seconds.
- **Dialogs**: centered, `ACCENT` border on `MANTLE`, actions as key chips; every run asks first.

## Motion

Animation only says that something runs: spinners, and the wordmark gradient drifting during a run.
Nothing moves when nothing runs.

## Fallbacks

- `CHAINRUNNER_ASCII=1`, or a console that cannot show the symbols, uses the ASCII column above.
- `NO_COLOR` is honored: states stay readable through their symbols and words.
- Below 80 × 24 the UI shows a single "make the terminal larger" message.
