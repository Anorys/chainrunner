---
title: Step types
description: How a type of step plugs into ChainRunner, and how to add one.
---

Each step of a sequence has a `type` (`http` when left out). A type is a class of the `step` package,
listed in `StepKinds`. The runner only keeps what every step shares; the rest belongs to the type.

## Why compiled in, not loaded

The native executable only holds the classes it was built with: a jar dropped next to it could not be
loaded. A new type of step is therefore a class added to the code and to the `StepKinds` list, and it
ships with the next release.

To add something without a new release, use a `command` step: it runs any program, gives it the
variables on its standard input and reads values from its output.

## The pieces

| Piece | Role |
|---|---|
| `StepKind` | a type: its name (`http`, `poll`, `sql`, `file`, `command`) and the reading of its own fields |
| `Step` | one step as read from the file: renders its request, runs it, describes itself for the screen |
| `StepContext` | what the runner lends to a step: environment, options, HTTP client and tokens, databases, stop, progress |
| `StepFields` | the fields of the step in the YAML, with the same readers and error messages as the rest of the configuration |
| `StepKinds` | the list of every type |

What every step shares is read before the type and handled by the runner: `name`, `forEach`,
`extract` (the type decides what the paths mean), `skipIf` / `onlyIf`, `database`, `enabled`.

## Adding a type

1. Write a record implementing `Step`, with a nested `Kind` implementing `StepKind` that reads its
   fields through `StepFields` (a missing field throws with `fields.error(...)`).
2. `request(...)` renders the request with the variables: the screen shows it as a preview and the log
   writes it. It throws `UnknownVariableException` while a variable is not known yet.
3. `run(...)` returns a `CallResult`: `OK` with the extracted values, or `failed(...)` with `halt` set
   when the sequence must stop.
4. `label()`, `summary()`, `details()`, `waiting()` and `outcome()` say how the step looks in the steps list and the
   detail panel.
5. Add the kind to `StepKinds`, then tests, the configuration reference and an example.
