---
title: Publier une version
description: Comment une version de ChainRunner est publiée.
---

Une version est une release GitHub `vX.Y.Z` avec ces fichiers :

- `chainrunner-X.Y.Z-windows-x64.exe`
- `chainrunner-X.Y.Z-linux-x64`
- `chainrunner-X.Y.Z-macos-arm64`
- `chainrunner-X.Y.Z.jar`
- `SHA256SUMS`

## Après chaque pull request fusionnée

Chaque pull request fusionnée dans `main` publie d'elle-même la version suivante. Son titre, au format
[Conventional Commits](https://www.conventionalcommits.org/fr/), ou ses labels disent de combien la
version avance :

| Pull request | Exemple | Version |
|---|---|---|
| un changement incompatible : `!` après le type, une ligne commençant par `BREAKING CHANGE:` dans la description, ou le label `breaking` | `feat!: rename the step keys` | `1.4.2` → `2.0.0` |
| une fonctionnalité : `feat:`, ou le label `feature` ou `enhancement` | `feat(db): named databases` | `1.4.2` → `1.5.0` |
| tout le reste : un correctif, de la doc, la CI... | `fix: retry on 401 once` | `1.4.2` → `1.4.3` |

Les commits poussés sur `main` sans pull request ne publient rien.

## À la main

Pour en publier une à la main, poussez un tag `vX.Y.Z` sur `main` :

```shell
git tag v0.2.0
git push origin v0.2.0
```

ou lancez le workflow **Release** depuis l'onglet Actions avec la version `X.Y.Z`. Il fixe la version,
lance les tests, compile les trois exécutables et les publie avec des notes de version générées, en
créant le tag si besoin.

:::caution[Noms de fichiers]
`chainrunner self-update` cherche exactement ces noms. Les changer impose de changer
`SelfUpdater.assetName` dans la même version.
:::

Une fois la release créée, chaque lancement de `chainrunner` dans un terminal affiche l'avis de mise à
jour dans la journée, et `chainrunner self-update` l'installe.

## Intégration continue

Chaque push et chaque pull request lancent les tests sous Linux et Windows, puis compilent l'exécutable
natif sous Linux, Windows et macOS et l'exécutent sur la configuration d'exemple. Les exécutables de
chaque exécution sont conservés comme artefacts du workflow.

La documentation est compilée à chaque changement sous `docs/` et publiée sur GitHub Pages depuis `main`.
