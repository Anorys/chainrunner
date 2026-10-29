---
title: Databases, waits and guards
description: Wait for a row to show up, extract its columns, and leave out the steps already done.
---

An environment lists its PostgreSQL databases under `databases`, the way it lists its APIs. Its
`database` block only holds defaults that every database inherits, field by field.

```yaml
environments:
  dev:
    database:                       # defaults: here, the same account everywhere
      username: ${DEV_DB_USERNAME}
      password: ${DEV_DB_PASSWORD:-}   # empty: asked for before the first query
    databases:
      orders: { url: "jdbc:postgresql://db-dev:5432/orders" }
      archive: { url: "jdbc:postgresql://db-dev:5432/archive", username: archive_reader }
```

A `poll` step or a guard names its database with `database:`; it may leave it out only when the
environment has a single one. Each database is opened at its first query and kept open until the end
of the run.

Connections are **read-only**: the session is opened read-only on the server, each query is rolled
back, and only `select` and `with ... select` queries are accepted. A read-only account is still a
good idea.

## Waiting for a row: `type: poll`

```yaml
- name: "File {{accountId}}"
  type: poll
  database: orders
  sql: |
    select doc_id, created
    from export_file
    where account_id = {{accountId}} and created::date = {{today}}
  every: 10s            # how often the query runs (default 10s)
  timeout: 15m          # when to give up (default 15m)
  onTimeout: next       # next: this value fails, the others go on; stop: the sequence stops
  onManyRows: first     # first: the first row is taken; fail: the step fails
  extract:
    docId: doc_id       # {{docId}} for the next steps; without extract, every column
```

The query runs until it returns a row whose extracted columns are not null. Column names match in any
case (PostgreSQL folds `docID` to `docid`).

### Variables in SQL

Every `{{variable}}` becomes a bound parameter `?`, never text pasted in the query: no quotes to add,
no injection. A list becomes `?, ?, ?`, for an `in (...)`. Filters work as everywhere
(`{{today | date("YYYY") | number}}`), and the value keeps its type: a number is bound as a number.
Quotes written around a variable (`'{{x}}'`) are removed.

## Leaving out a step: `skipIf` and `onlyIf`

Any step can run a query first:

```yaml
- name: "Generate {{accountId}}"
  api: orders
  method: POST
  path: /files
  database: orders
  skipIf: select 1 from export_file where account_id = {{accountId}} and created::date = {{today}}
```

- `skipIf`: when the query returns a row, the step is **not needed** and is not made.
- `onlyIf`: the step is made only when the query returns a row.

A step left out is not a failure: the next steps for the same value go on. A guard runs on the step's
`database`, or on its own: `skipIf: { sql: "select ...", database: archive }`.

## Downloads

An HTTP step with `saveTo` writes its response to a file:

```yaml
- name: "Document {{accountId}}"
  api: document
  path: /documents/{{docId}}
  saveTo: "documents/{{env}}/{{today}}/{{accountId}}-{{docId}}.xml"   # relative to the configuration
  saveAs: raw              # raw: the body as is; base64:/content: a JSON field, decoded
  overwrite: false         # an existing file makes the step fail instead of being replaced
```

## One value at a time, or one phase at a time

With a sequence-level `forEach`, `order: phases` (the default) makes every step for all the values
before the next step: all the generations, then all the waits, then all the downloads. `order: each`
makes all the steps for one value before the next value. Either way, a value extracted for one value
is only seen by that value's steps, and a failure abandons only that value's later steps.
