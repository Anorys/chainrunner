---
title: Scripts and CI
description: Run a sequence without the UI, and read the result from the exit code and the log.
---

`--run` makes the steps without the UI, one line per step:

```shell
chainrunner --run --yes -e acc -s generate-and-download --var accountIds=ACC001,ACC002
```

```text
◆ chainrunner  generate-and-download on Acceptance  orders-acc.example.com, document-acc.example.com

✔   1. Generate ACC001  204  312 ms
○   2. Generate ACC002  not needed: skipIf found a row  9 ms
✔   3. File ACC001 in the database  found  0:42  {docId=D-881}
       found at attempt 5 after 0:42
✔   4. Document ACC001  200  95 ms
       file: documents/acc/2026-10-28/ACC001-D-881.xml (12.4 KB)

✔ 3 OK, 1 not needed, 0 failed, 0 not run  log: logs/chainrunner-20261028-140512-acc-generate-and-download.log
```

- Without a terminal, ChainRunner never waits for a key: `--yes` is required, and a password the
  configuration lacks is an error (set it in the environment or in `.env`).
- On a protected environment, `--yes` is not enough when a terminal is there: its name must be typed.
- `--dry-run` prints every request (HTTP and SQL, with their values) and sends nothing.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | every step is OK or not needed |
| 1 | a step failed, or was not run |
| 2 | configuration or usage error (unknown environment, missing password without a terminal...) |

## Logs

Each run writes `logs/chainrunner-<date>-<env>-<sequence>.log` next to the configuration (`options.logDir`,
empty for none): every request, response, query, parameter and attempt. Tokens and headers whose name
contains `auth`, `key`, `secret`, `token` or `password` are written `***`.

## In a pipeline

```yaml
# GitHub Actions
- name: Generate this month's files
  env:
    ACC_CLIENT_ID: ${{ secrets.ACC_CLIENT_ID }}
    ACC_CLIENT_SECRET: ${{ secrets.ACC_CLIENT_SECRET }}
    ACC_USERNAME: ${{ secrets.ACC_USERNAME }}
    ACC_PASSWORD: ${{ secrets.ACC_PASSWORD }}
  run: ./chainrunner --run --yes -e acc -s generate
```

The update check is off in CI (`CI` is set).
