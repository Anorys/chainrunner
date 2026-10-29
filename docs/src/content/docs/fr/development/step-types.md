---
title: Types d'étapes
description: Comment un type d'étape se branche dans ChainRunner, et comment en ajouter un.
---

Chaque étape d'une séquence a un `type` (`http` s'il est omis). Un type est une classe du paquet
`step`, listée dans `StepKinds`. Le runner ne garde que ce que toutes les étapes partagent ; le reste
appartient au type.

## Pourquoi compilé, et pas chargé

L'exécutable natif ne contient que les classes avec lesquelles il a été construit : un jar posé à côté
ne pourrait pas être chargé. Un nouveau type d'étape est donc une classe ajoutée au code et à la liste
`StepKinds`, livrée avec la release suivante.

## Les pièces

| Pièce | Rôle |
|---|---|
| `StepKind` | un type : son nom (`http`, `poll`) et la lecture de ses propres champs |
| `Step` | une étape lue dans le fichier : rend sa requête, s'exécute, se décrit pour l'écran |
| `StepContext` | ce que le runner prête à une étape : environnement, options, client HTTP et jetons, bases, arrêt, progression |
| `StepFields` | les champs de l'étape dans le YAML, avec les mêmes lecteurs et messages d'erreur que le reste de la configuration |
| `StepKinds` | la liste de tous les types |

Ce que toutes les étapes partagent est lu avant le type et géré par le runner : `name`, `forEach`,
`extract` (le type décide du sens des chemins), `skipIf` / `onlyIf`, `database`, `enabled`.

## Ajouter un type

1. Écrire un record qui implémente `Step`, avec une classe imbriquée `Kind` qui implémente `StepKind`
   et lit ses champs via `StepFields` (un champ manquant lève une erreur avec `fields.error(...)`).
2. `request(...)` rend la requête avec les variables : l'écran l'affiche en aperçu et le journal
   l'écrit. Elle lève `UnknownVariableException` tant qu'une variable n'est pas encore connue.
3. `run(...)` renvoie un `CallResult` : `OK` avec les valeurs extraites, ou `failed(...)` avec `halt`
   quand la séquence doit s'arrêter.
4. `label()`, `summary()`, `details()` et `waiting()` disent comment l'étape apparaît dans la liste et
   dans le panneau de détail.
5. Ajouter le type à `StepKinds`, puis des tests, la référence de configuration et un exemple.
