---
title: Configuration
description: Every key of chainrunner.yaml, with its default.
---

ChainRunner reads `chainrunner.yaml` in the current folder, or the file given with `-c`.
[`chainrunner.example.yaml`](https://github.com/anorys/chainrunner/blob/main/chainrunner.example.yaml)
uses every key, with comments.

## Values from the environment

`${NAME}` is replaced by the environment variable `NAME`, then by the line `NAME=value` of the `.env`
file next to the configuration. `${NAME:-default}` gives a default when the variable is missing or
empty. Real environment variables win over `.env`. This happens once, when the file is read; the
`{{variables}}` of the steps are [another thing](../templates/).

## Top level

| Key | Default | Meaning |
|---|---|---|
| `defaultEnvironment` | the first one | the environment used without `-e` |
| `environments` | required | the environments, by name |
| `variables` | | the variables of every run: values or lists of values |
| `options` | | how the steps are made, see below |
| `sequences` | required | the sequences, by name |

## `environments.<name>`

| Key | Default | Meaning |
|---|---|---|
| `label` | the name | shown in the UI |
| `protected` | `false` | red banner, and the name must be typed before each run |
| `apis.<api>` | | the APIs of the environment, see below |
| `auth` | | defaults every API's `auth` inherits, field by field; nothing is required |
| `headers` | | headers sent to every API of the environment |
| `databases.<db>` | | the databases of the environment, see below |
| `database` | | defaults every database inherits, field by field; it defines no database alone |
| `variables` | | variables of this environment, over the global ones |

### `apis.<api>`

| Key | Default | Meaning |
|---|---|---|
| `baseUrl` | required | prefixed to the `path` of the steps |
| `auth` | | its token settings, laid over the environment's `auth` |
| `headers` | | headers for this API, over the environment's |

`auth` keys: `grantType` (`password`, `client_credentials`, `none`; default `password`), `tokenUrl`
(required unless `none`), `clientId`, `clientSecret`, `clientAuth` (`body` or `basic`), `username`,
`password` (empty: asked for), `scope`, `header` (default `Authorization`), `prefix` (default
`Bearer `). See [APIs and tokens](../../guides/apis-and-tokens/).

### `databases.<db>`

| Key | Default | Meaning |
|---|---|---|
| `url` | required | `jdbc:postgresql://host:5432/name` |
| `username` | | the account |
| `password` | | empty: asked for before the first query |

Each key missing here comes from the environment's `database`. See
[databases, waits and guards](../../guides/databases/).

## `options`

| Key | Default | Meaning |
|---|---|---|
| `timeoutSeconds` | `120` | connection and response timeout, also for tokens and queries |
| `stopOnServerError` | `true` | a 5xx stops the sequence |
| `stopOnClientError` | `false` | another 4xx stops the sequence |
| `delayMillis` | `0` | pause between two steps |
| `insecureTls` | `false` | accepts any certificate (test servers only) |
| `logDir` | `logs` | where the run logs go, relative to the configuration; empty for none |
| `maxBodyChars` | `20000` | longest response shown and logged |

## `sequences.<name>`

| Key | Default | Meaning |
|---|---|---|
| `description` | | shown in the UI and by `--list` |
| `forEach` | | `variable: "{{list}}"`: every step for each value |
| `order` | `phases` | with `forEach`: `phases` (each step for all the values, then the next step) or `each` (all the steps for one value, then the next value) |
| `steps` (or `calls`) | required | the steps, in order |

A sequence can also be written as a plain list of steps.

## Steps

Keys of every step:

| Key | Default | Meaning |
|---|---|---|
| `name` | method and path | shown everywhere; can hold `{{variables}}` |
| `type` | `http` | `http` or `poll` |
| `forEach` | | repeats this step for each value (each combination with several variables) |
| `extract` | | `variable: where`: values for the next steps |
| `skipIf` / `onlyIf` | | a query run first; `{ sql, database }` to query another database |
| `database` | the only one | the database of a `poll` step and of the guard |
| `enabled` | `true` | `false` leaves the step out |
| `stopOnServerError`, `stopOnClientError` | the options' | the chaining rules for this step |

### HTTP steps

| Key | Default | Meaning |
|---|---|---|
| `api` | the only one | the API called |
| `method` | `GET` | `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS` |
| `path` | | after the API's `baseUrl` |
| `url` | | a full URL instead of `path` |
| `query` | | query parameters; a list repeats the parameter |
| `headers` | | over the environment's and the API's |
| `body` | | YAML sent as JSON, or a text |
| `expectStatus` | any 2xx | the statuses that count as OK |
| `auth` | `true` | `false` sends no token |
| `extract` | | `/data/id`, `$.data.id`, `data.id` (JSON) or `header:Location` |
| `saveTo` | | writes the response to this file (relative to the configuration) |
| `saveAs` | `raw` | `raw`, or `base64:/json/pointer` for a base64 field of a JSON response |
| `overwrite` | `false` | replaces an existing file |

### Poll steps

| Key | Default | Meaning |
|---|---|---|
| `sql` | required | a `select` or `with ... select`; `{{variables}}` become parameters |
| `every` | `10s` | between two queries: `500ms`, `30s`, `2m`, `1h`, or seconds |
| `timeout` | `15m` | when to give up |
| `onTimeout` | `next` | `next`: this value fails, the others go on; `stop`: the sequence stops |
| `onManyRows` | `first` | `first` or `fail` |
| `extract` | every column | `variable: column`, any case |

## Chaining rules

| Outcome | Effect |
|---|---|
| 2xx (or a status of `expectStatus`) | next step |
| 401 | new token, the call is made again once; a second 401 stops |
| 5xx | stops the sequence (`stopOnServerError`) |
| other 4xx | recorded, the sequence goes on (`stopOnClientError`) |
| network error, refused token, unknown variable | stops the sequence |
| poll: a row | next step, with the extracted values |
| poll: no row after `timeout` | `onTimeout` |
| database unreachable, query refused | stops the sequence |
| download: the file exists | this value fails, the sequence goes on |
| guard: the step is not needed | not a failure, the next steps go on |

Inside a `forEach`, a failure that does not stop the sequence abandons the later steps of that value
only.
