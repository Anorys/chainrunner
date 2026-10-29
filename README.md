# ChainRunner

ChainRunner runs chains of steps, one after the other, in a terminal UI or from scripts:

- API calls, each API with its own OAuth2 token (Keycloak or any token endpoint);
- waits on a database: a PostgreSQL query run again and again until a row shows up;
- document downloads, saved as they come or decoded from base64;
- guards that leave out a step when a query finds (or does not find) a row.

Values extracted from a response or a row feed the next steps. Everything is set in `chainrunner.yaml`:
environments, APIs, databases, variables and sequences.

**Documentation:** [anorys.github.io/chainrunner](https://anorys.github.io/chainrunner/) (English and French).

## Install

Download the executable of your platform from the
[latest release](https://github.com/anorys/chainrunner/releases/latest) (Windows x64, Linux x64,
macOS arm64), rename it `chainrunner` (`chainrunner.exe` on Windows) and put it on your `PATH`.
Later versions install themselves:

```shell
chainrunner self-update --check   # is there a newer release?
chainrunner self-update           # download it, check its SHA-256, replace this executable
```

Once a day, ChainRunner also says after a run when a newer release is out
(`--no-update-check` or `CHAINRUNNER_NO_UPDATE_CHECK=1` to turn that off).

## Quick start

```shell
cp chainrunner.example.yaml chainrunner.yaml   # then adapt the environments and the sequences
cp .env.example .env                           # then fill in the secrets; .env is never committed
chainrunner                                    # the terminal UI
chainrunner --run --yes -s generate            # without the UI: scripts and CI, exit code 0/1/2
```

```shell
chainrunner -e acc -s generate-and-download    # another environment, another sequence
chainrunner --var accountIds=ACC1,ACC2         # replaces a variable (a list: commas)
chainrunner --dry-run                          # shows the requests (HTTP and SQL), sends nothing
chainrunner --list                             # the environments and the sequences
```

Keys in the UI: `r` runs the whole sequence, `enter` the selected step alone, `R` from the selected step
on, `x` stops after the current step, `e` switches environment, `v` edits the variables for the session,
`t` asks for tokens now, `?` shows the help, `q` quits. Every run asks for confirmation; on an
environment with `protected: true`, its name must be typed.

## Chaining rules

| Outcome | Effect |
|---|---|
| 2xx (or a status of `expectStatus`) | next step |
| 401 | new token (refresh token, else a new login), the call is made again once; a second 401 stops |
| 5xx | the sequence stops (`stopOnServerError: true`) |
| other 4xx (404...) | the failure is recorded, the sequence goes on (`stopOnClientError: false`) |
| network error, refused token | the sequence stops |
| poll: a row is found | next step, with the extracted columns (`{{docId}}`) |
| poll: no row after `timeout` | `onTimeout: next`: this value fails and its later steps are skipped, the other values go on; `onTimeout: stop`: the sequence stops |
| `skipIf` finds a row / `onlyIf` finds none | the step is *not needed*: not a failure, the next steps go on |

## Configuration in short

```yaml
environments:
  dev:
    auth:                      # defaults every API inherits, field by field; nothing is required
      tokenUrl: https://sso.example.com/realms/example/protocol/openid-connect/token
      clientId: ${DEV_CLIENT_ID}
      username: ${DEV_USERNAME}
      password: ${DEV_PASSWORD:-}          # empty: asked for at the first call
    apis:                      # each with its own token; a step picks one with 'api:'
      orders: { baseUrl: https://orders-dev.example.com }
      document:
        baseUrl: https://document-dev.example.com
        auth: { grantType: client_credentials, clientSecret: ${DEV_DOC_SECRET} }
    database:                  # defaults every database inherits, field by field
      username: ${DEV_DB_USERNAME}
    databases:                 # PostgreSQL, read-only; a step picks one with 'database:'
      orders: { url: "jdbc:postgresql://localhost:5432/orders" }

sequences:
  generate-and-download:
    forEach: { accountId: "{{accountIds}}" }
    steps:
      - { name: "Generate {{accountId}}", api: orders, method: POST, path: /files, query: { id: "{{accountId}}" } }
      - name: "File {{accountId}}"
        type: poll
        database: orders
        sql: select doc_id from export_file where account_id = {{accountId}} and created::date = {{today}}
        extract: { docId: doc_id }
      - { name: "Document {{accountId}}", api: document, path: "/documents/{{docId}}", saveTo: "out/{{docId}}.xml" }
```

[`chainrunner.example.yaml`](chainrunner.example.yaml) shows every option with comments; the
[configuration reference](https://anorys.github.io/chainrunner/reference/configuration/) explains them.

## Build

Java 21 and Maven:

```shell
./mvnw verify                  # tests, then target/chainrunner-<version>-all.jar
java -jar target/chainrunner-*-all.jar
```

The native executable needs GraalVM for JDK 21 (`JAVA_HOME` on it); on Windows, the Visual Studio Build
Tools and an *x64 Native Tools Command Prompt*:

```shell
./mvnw -Pnative -DskipTests package   # target/chainrunner (chainrunner.exe on Windows)
build-native.cmd                      # the same on Windows
```

CI builds and tests every push, and builds the native executables of the three platforms. Each pull
request merged into `main` publishes them as the next GitHub release, with their `SHA256SUMS`: `feat:`
moves the minor version, `feat!:` (a breaking change) the major one, anything else the patch; see
[releasing](https://anorys.github.io/chainrunner/development/releasing/).

Stack: Java 21, [TamboUI](https://tamboui.dev) 0.6 (immediate-mode layers only), JLine 3.30, Jackson YAML
(tree model, no reflection), PostgreSQL JDBC 42.7, GraalVM native image.
