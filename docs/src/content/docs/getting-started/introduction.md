---
title: Introduction
description: What ChainRunner does, and the words used in this documentation.
---

ChainRunner makes a **sequence** of **steps**, one after the other, against one **environment**
(development, acceptance, production...). It is made for the jobs that are a series of manual calls
today: call an API to start a job, wait for its result to show up in a database, download the document
it produced, for each of a list of values.

## The words

Environment
: Where the steps run: the base URLs of its **APIs**, how each one gets its token, its **databases**,
  and variables of its own. An environment marked `protected` asks for its name before every run.

API
: A service the steps call, with its `baseUrl`, its headers and its own OAuth2 token. A step names the
  API it calls with `api:`.

Database
: A PostgreSQL database that `poll` steps and guards query, read-only. A step names it with `database:`.

Sequence
: A named list of steps. With a `forEach`, its steps are made for each value of a list.

Step
: An HTTP call (`type: http`, the default), possibly a download (`saveTo`), or a database wait
  (`type: poll`). A step can **extract** values from its response or row, and the next steps use them
  as `{{variables}}`.

Guard
: A query run before a step: `skipIf` leaves the step out when it finds a row, `onlyIf` when it finds
  none. A step left out this way is *not needed*, not failed.

## What happens during a run

1. ChainRunner renders each step with the variables known at that point: the configuration's, the
   environment's, those typed in the UI or given with `--var`, and the values extracted by the
   earlier steps.
2. It gets the API's token when the first step needs it, and renews it when it expires or when the
   API answers 401.
3. It applies the [chaining rules](../../reference/configuration/#chaining-rules): a 2xx goes on, a
   5xx stops the sequence, a database wait gives up after its timeout.
4. It writes a log of the run (requests, responses, rows), without the tokens and secret headers.

Next: [install ChainRunner](../installation/), then follow the [quick start](../quick-start/).
