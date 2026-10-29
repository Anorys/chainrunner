---
title: Variables et filtres
description: Les {{variables}} des étapes, les variables intégrées, et les filtres.
---

Les chemins, URL, paramètres de requête, en-têtes, corps, noms de fichiers, requêtes SQL et noms
d'étapes peuvent contenir des `{{variables}}`. Elles sont remplacées juste avant l'exécution de l'étape.

## D'où viennent les valeurs

Les suivantes l'emportent :

1. `variables` en haut de la configuration ;
2. les `variables` de l'environnement ;
3. `--var NOM=VALEUR` en ligne de commande, ou `v` dans l'interface ;
4. les valeurs extraites par les étapes précédentes (dans un `forEach`, seulement celles de la même valeur) ;
5. la valeur `forEach` de l'étape.

Intégrées : `{{env}}` (le nom de l'environnement), `{{today}}` (`2026-10-28`) et `{{now}}`
(`2026-10-28T14:05:00`).

Une variable qui forme toute la valeur garde son type : `body: { active: "{{flag}}" }` envoie un booléen
JSON, et une liste dans `query` répète le paramètre. Au milieu d'un texte, une liste s'écrit `a,b,c`.

## Filtres

Les filtres suivent la variable après `|`, et s'enchaînent de gauche à droite.

| Filtre | Exemple | Résultat |
|---|---|---|
| `date("motif")` | `{{today \| date("YYYY")}}` | `2026` |
| | `{{today \| date("MM")}}` | `10` |
| | `{{now \| date("YYYYMMDD-HHmm")}}` | `20261028-1405` |
| `plusDays(n)` | `{{today \| plusDays(-1)}}` | `2026-10-27` |
| `plusMonths(n)` | `{{today \| plusMonths(-1) \| date("MM")}}` | `09` |
| `plusYears(n)` | `{{today \| plusYears(1) \| date("YYYY")}}` | `2027` |
| `number` | `{{month \| number}}` avec `"07"` | `7` |
| `upper`, `lower`, `trim` | `{{code \| upper}}` | `ABC` |

Les motifs de date sont ceux de Java, où `YYYY`, `YY` et `DD` sont lus comme l'année civile et le jour
du mois. Un filtre appliqué à une liste s'applique à chaque valeur.

## Dans le SQL

Dans une requête `poll` ou une garde, chaque `{{variable}}` devient un paramètre lié : pas de guillemets
à écrire, pas d'injection, et un nombre reste un nombre. Une liste devient `?, ?, ?` :

```sql
select doc_id from export_file
where account_id in ({{accountIds}})
  and export_year = {{today | date("YYYY") | number}}
```
