---
title: Installation
description: Télécharger l'exécutable natif, ou lancer le jar.
---

## Exécutable natif

Chaque [release](https://github.com/anorys/chainrunner/releases/latest) contient un exécutable pour :

| Plateforme | Fichier |
|---|---|
| Windows x64 | `chainrunner-X.Y.Z-windows-x64.exe` |
| Linux x64 | `chainrunner-X.Y.Z-linux-x64` |
| macOS (Apple silicon) | `chainrunner-X.Y.Z-macos-arm64` |

Téléchargez le vôtre, renommez-le `chainrunner.exe` (Windows) ou `chainrunner` (et rendez-le
exécutable avec `chmod +x`), puis placez-le dans un dossier de votre `PATH`. Vérifiez :

```shell
chainrunner --version
```

Sur macOS, un exécutable téléchargé est mis en quarantaine ; autorisez-le une fois avec
`xattr -d com.apple.quarantine chainrunner`.

## Mises à jour

```shell
chainrunner self-update --check    # indique si une version plus récente existe
chainrunner self-update            # l'installe
chainrunner self-update --to 0.2.0 # installe une version donnée, même plus ancienne
```

`self-update` télécharge l'exécutable de votre plateforme, vérifie son SHA-256 avec le `SHA256SUMS` de
la release, le lance une fois, et seulement ensuite le met à la place de celui qui tourne (sous
Windows, l'ancien fichier est supprimé au lancement suivant).

Une fois par jour, ChainRunner cherche la dernière release en arrière-plan et signale après une
exécution qu'une version plus récente est sortie. Il ne cherche pas en CI (`CI` est défini), sans
terminal, avec `--no-update-check` ou avec `CHAINRUNNER_NO_UPDATE_CHECK=1`.

Derrière un proxy, définissez `HTTPS_PROXY=http://proxy.example.com:8080` pour la vérification des
versions et `self-update` ; les étapes utilisent un autre réglage, voir [Proxy](../../reference/command-line/#proxy).
`CHAINRUNNER_UPDATE_URL` pointe vers un miroir de l'API des releases GitHub, et
`CHAINRUNNER_GITHUB_TOKEN` (ou `GH_TOKEN`) lui est envoyé s'il est défini.

## Jar

Avec Java 21, le jar d'une release tourne partout : `java -jar chainrunner-X.Y.Z.jar`. Il ne peut pas se
mettre à jour lui-même ; téléchargez le suivant.
