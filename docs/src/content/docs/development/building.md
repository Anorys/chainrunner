---
title: Building
description: Build and test ChainRunner, the jar and the native executable.
---

## Requirements

- Java 21 (any distribution) for the jar and the tests.
- For the native executable: GraalVM for JDK 21 with `native-image`, `JAVA_HOME` on it. On Windows,
  the Visual Studio Build Tools (*Desktop development with C++*), and a build started from an
  *x64 Native Tools Command Prompt*. On Linux, `gcc` and the `zlib` headers.

The Maven wrapper (`mvnw`, `mvnw.cmd`) downloads the right Maven.

## Jar and tests

```shell
./mvnw verify
java -jar target/chainrunner-*-all.jar --help
```

The tests need no network nor database: a fake OAuth2 server and API run on localhost, and the
database is faked. Two tests run against a real PostgreSQL when `CHAINRUNNER_TEST_PG_URL`,
`CHAINRUNNER_TEST_PG_USER` and `CHAINRUNNER_TEST_PG_PASSWORD` are set, and are skipped otherwise.

The UI tests render each screen in memory; `-Dchainrunner.screenshots=<dir>` also writes them as SVG.

## Native executable

```shell
./mvnw -Pnative package          # target/chainrunner, or target\chainrunner.exe
build-native.cmd                  # Windows: the same, then chainrunner.exe --version
```

The build needs no reflection configuration of its own: the configuration is read with Jackson's tree
model, arguments are parsed by hand, and the PostgreSQL driver's metadata comes from the GraalVM
reachability metadata repository. A substitution in `tui.nativeimage` keeps TamboUI's Flight Recorder
event out of the image.

## Layout

| Package | Holds |
|---|---|
| `config` | the YAML, read into records; every error says which key is wrong |
| `core` | planning (`forEach`), running (`Runner`), tokens, templates, the database, the run log |
| `cli` | `--run` and `self-update` |
| `tui` | the terminal UI: state, keys, views and theme |
| `update` | GitHub releases, the daily check, the executable swap |
