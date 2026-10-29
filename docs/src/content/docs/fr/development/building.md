---
title: Compiler
description: Compiler et tester ChainRunner, le jar et l'exécutable natif.
---

## Prérequis

- Java 21 (n'importe quelle distribution) pour le jar et les tests.
- Pour l'exécutable natif : GraalVM pour JDK 21 avec `native-image`, `JAVA_HOME` pointant dessus. Sous
  Windows, les Visual Studio Build Tools (*Développement Desktop en C++*), et une compilation lancée
  depuis une *x64 Native Tools Command Prompt*. Sous Linux, `gcc` et les en-têtes `zlib`.

Le wrapper Maven (`mvnw`, `mvnw.cmd`) télécharge la bonne version de Maven.

## Jar et tests

```shell
./mvnw verify
java -jar target/chainrunner-*-all.jar --help
```

Les tests n'ont besoin ni du réseau ni d'une base : un faux serveur OAuth2 et une fausse API tournent
sur localhost, et la base est simulée. Deux tests s'exécutent contre un vrai PostgreSQL quand
`CHAINRUNNER_TEST_PG_URL`, `CHAINRUNNER_TEST_PG_USER` et `CHAINRUNNER_TEST_PG_PASSWORD` sont définis,
et sont ignorés sinon.

Les tests de l'interface dessinent chaque écran en mémoire ; `-Dchainrunner.screenshots=<dossier>` les
écrit aussi en SVG.

## Exécutable natif

```shell
./mvnw -Pnative package          # target/chainrunner, ou target\chainrunner.exe
build-native.cmd                  # Windows : la même chose, puis chainrunner.exe --version
```

La compilation n'a besoin d'aucune configuration de réflexion propre : la configuration est lue avec le
modèle d'arbre de Jackson, les arguments sont analysés à la main, et les métadonnées du pilote
PostgreSQL viennent du dépôt de métadonnées d'accessibilité de GraalVM. Une substitution dans
`tui.nativeimage` garde l'événement Flight Recorder de TamboUI hors de l'image.

## Organisation

| Package | Contient |
|---|---|
| `config` | le YAML, lu dans des records ; chaque erreur indique la clé fautive |
| `core` | la planification (`forEach`), l'exécution (`Runner`), les jetons, les templates, la base, le journal |
| `step` | les types d'étapes (`http`, `poll`) et leur registre, voir [Types d'étapes](../step-types/) |
| `cli` | `--run` et `self-update` |
| `tui` | l'interface terminal : état, touches, vues et thème |
| `update` | les releases GitHub, la vérification quotidienne, le remplacement de l'exécutable |
