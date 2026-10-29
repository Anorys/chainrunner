---
title: Variables and filters
description: The {{variables}} of the steps, the built-in ones, and the filters.
---

Paths, URLs, query parameters, headers, bodies, file names, SQL and step names can hold
`{{variables}}`. They are rendered just before the step runs.

## Where values come from

Later ones win:

1. `variables` at the top of the configuration;
2. the environment's `variables`;
3. `--var NAME=VALUE` on the command line, or `v` in the UI;
4. the values extracted by the earlier steps (inside a `forEach`, only those of the same value);
5. the `forEach` value of the step.

Built in: `{{env}}` (the environment's name), `{{today}}` (`2026-10-28`) and `{{now}}`
(`2026-10-28T14:05:00`).

A variable that is the whole value keeps its type: `body: { active: "{{flag}}" }` sends a JSON boolean,
and a list in `query` repeats the parameter. Inside a text, a list is written `a,b,c`.

## Filters

Filters follow the variable after `|`, and chain from left to right.

| Filter | Example | Result |
|---|---|---|
| `date("pattern")` | `{{today \| date("YYYY")}}` | `2026` |
| | `{{today \| date("MM")}}` | `10` |
| | `{{now \| date("YYYYMMDD-HHmm")}}` | `20261028-1405` |
| `plusDays(n)` | `{{today \| plusDays(-1)}}` | `2026-10-27` |
| `plusMonths(n)` | `{{today \| plusMonths(-1) \| date("MM")}}` | `09` |
| `plusYears(n)` | `{{today \| plusYears(1) \| date("YYYY")}}` | `2027` |
| `number` | `{{month \| number}}` with `"07"` | `7` |
| `upper`, `lower`, `trim` | `{{code \| upper}}` | `ABC` |

Date patterns are Java's, with `YYYY`, `YY` and `DD` read as the calendar year and day of month.
A filter applied to a list applies to each value.

## In SQL

In a `poll` query or a guard, each `{{variable}}` becomes a bound parameter: no quotes to write, no
injection, and a number stays a number. A list becomes `?, ?, ?`:

```sql
select doc_id from export_file
where account_id in ({{accountIds}})
  and export_year = {{today | date("YYYY") | number}}
```
