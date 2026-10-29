---
title: Configuration
description: Chaque clé de chainrunner.yaml, avec sa valeur par défaut.
---

ChainRunner lit `chainrunner.yaml` dans le dossier courant, ou le fichier donné avec `-c`.
[`chainrunner.example.yaml`](https://github.com/anorys/chainrunner/blob/main/chainrunner.example.yaml)
utilise chaque clé, avec des commentaires.

## Valeurs de l'environnement

`${NAME}` est remplacé par la variable d'environnement `NAME`, puis par la ligne `NAME=valeur` du
fichier `.env` placé à côté de la configuration. `${NAME:-défaut}` donne une valeur par défaut quand la
variable manque ou est vide. Les vraies variables d'environnement l'emportent sur `.env`. Cela se fait
une fois, à la lecture du fichier ; les `{{variables}}` des étapes sont [autre chose](../templates/).

## Premier niveau

| Clé | Défaut | Signification |
|---|---|---|
| `defaultEnvironment` | le premier | l'environnement utilisé sans `-e` |
| `environments` | obligatoire | les environnements, par nom |
| `variables` | | les variables de chaque lancement : valeurs ou listes de valeurs |
| `options` | | comment les étapes sont faites, voir plus bas |
| `sequences` | obligatoire | les séquences, par nom |

## `environments.<nom>`

| Clé | Défaut | Signification |
|---|---|---|
| `label` | le nom | affiché dans l'interface |
| `protected` | `false` | bandeau rouge, et le nom doit être tapé avant chaque lancement |
| `apis.<api>` | | les API de l'environnement, voir plus bas |
| `auth` | | valeurs par défaut héritées par l'`auth` de chaque API, champ par champ ; rien n'est obligatoire |
| `headers` | | en-têtes envoyés à toutes les API de l'environnement |
| `databases.<base>` | | les bases de l'environnement, voir plus bas |
| `database` | | valeurs par défaut héritées par chaque base, champ par champ ; ne définit aucune base à lui seul |
| `variables` | | variables de cet environnement, par-dessus les variables globales |

### `apis.<api>`

| Clé | Défaut | Signification |
|---|---|---|
| `baseUrl` | obligatoire | préfixé au `path` des étapes |
| `auth` | | ses réglages de jeton, posés sur l'`auth` de l'environnement |
| `headers` | | en-têtes de cette API, par-dessus ceux de l'environnement |

Clés de `auth` : `grantType` (`password`, `client_credentials`, `none` ; `password` par défaut),
`tokenUrl` (obligatoire sauf avec `none`), `clientId`, `clientSecret`, `clientAuth` (`body` ou `basic`),
`username`, `password` (vide : demandé), `scope`, `header` (`Authorization` par défaut), `prefix`
(`Bearer ` par défaut). Voir [API et jetons](../../guides/apis-and-tokens/).

### `databases.<base>`

| Clé | Défaut | Signification |
|---|---|---|
| `url` | obligatoire | `jdbc:postgresql://hote:5432/nom` |
| `username` | | le compte |
| `password` | | vide : demandé avant la première requête |

Chaque clé absente ici vient du `database` de l'environnement. Voir
[bases de données, attentes et gardes](../../guides/databases/).

## `options`

| Clé | Défaut | Signification |
|---|---|---|
| `timeoutSeconds` | `120` | délai de connexion et de réponse, aussi pour les jetons et les requêtes SQL |
| `stopOnServerError` | `true` | un 5xx arrête la séquence |
| `stopOnClientError` | `false` | un autre 4xx arrête la séquence |
| `delayMillis` | `0` | pause entre deux étapes |
| `insecureTls` | `false` | accepte n'importe quel certificat (serveurs de test seulement) |
| `logDir` | `logs` | où vont les journaux, relatif à la configuration ; vide pour aucun |
| `maxBodyChars` | `20000` | taille maximale d'une réponse affichée et journalisée |

## `sequences.<nom>`

| Clé | Défaut | Signification |
|---|---|---|
| `description` | | affichée dans l'interface et par `--list` |
| `forEach` | | `variable: "{{liste}}"` : chaque étape pour chaque valeur |
| `order` | `phases` | avec `forEach` : `phases` (chaque étape pour toutes les valeurs, puis l'étape suivante) ou `each` (toutes les étapes pour une valeur, puis la valeur suivante) |
| `steps` (ou `calls`) | obligatoire | les étapes, dans l'ordre |

Une séquence peut aussi s'écrire comme une simple liste d'étapes.

## Étapes

Clés de toutes les étapes :

| Clé | Défaut | Signification |
|---|---|---|
| `name` | méthode et chemin | affiché partout ; peut contenir des `{{variables}}` |
| `type` | `http` | `http`, `poll`, `sql`, `file` ou `command` |
| `forEach` | | répète cette étape pour chaque valeur (chaque combinaison avec plusieurs variables) |
| `extract` | | `variable: où` : des valeurs pour les étapes suivantes |
| `skipIf` / `onlyIf` | | une requête lancée d'abord ; `{ sql, database }` pour interroger une autre base |
| `database` | la seule | la base d'une étape `poll` ou `sql` et de la garde |
| `enabled` | `true` | `false` retire l'étape |

### Étapes HTTP

| Clé | Défaut | Signification |
|---|---|---|
| `api` | la seule | l'API appelée |
| `method` | `GET` | `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS` |
| `path` | | après le `baseUrl` de l'API |
| `url` | | une URL complète au lieu de `path` |
| `query` | | paramètres de requête ; une liste répète le paramètre |
| `headers` | | par-dessus ceux de l'environnement et de l'API |
| `body` | | du YAML envoyé en JSON, ou un texte |
| `expectStatus` | tout 2xx | les statuts qui comptent comme OK |
| `auth` | `true` | `false` n'envoie pas de jeton |
| `extract` | | `/data/id`, `$.data.id`, `data.id` (JSON) ou `header:Location` |
| `saveTo` | | écrit la réponse dans ce fichier (relatif à la configuration) |
| `saveAs` | `raw` | `raw`, ou `base64:/json/pointer` pour un champ base64 d'une réponse JSON |
| `overwrite` | `false` | remplace un fichier existant |
| `stopOnServerError`, `stopOnClientError` | ceux des options | les règles d'enchaînement de cette étape |

### Étapes d'attente (`poll`)

| Clé | Défaut | Signification |
|---|---|---|
| `sql` | obligatoire | un `select` ou un `with ... select` ; les `{{variables}}` deviennent des paramètres |
| `every` | `10s` | entre deux requêtes : `500ms`, `30s`, `2m`, `1h`, ou des secondes |
| `timeout` | `15m` | quand abandonner |
| `onTimeout` | `next` | `next` : cette valeur échoue, les autres continuent ; `stop` : la séquence s'arrête |
| `onManyRows` | `first` | `first` ou `fail` |
| `extract` | toutes les colonnes | `variable: colonne`, casse indifférente |

### Étapes SQL

Une requête lancée une seule fois.

| Clé | Défaut | Signification |
|---|---|---|
| `sql` | obligatoire | un `select` ou un `with ... select` ; les `{{variables}}` deviennent des paramètres |
| `allowEmpty` | `false` | `true` : aucune ligne n'est pas un échec (les listes sont vides, les valeurs simples non définies) |
| `extract` | toutes les colonnes de la première ligne | `variable: colonne` pour la première ligne, `variable: colonne[]` pour la liste de toutes les lignes |

Une liste extraite avec `colonne[]` peut servir dans une requête suivante (`where id in ({{ids}})`), un
paramètre de requête ou un corps.

### Étapes fichier

Lit un fichier.

| Clé | Défaut | Signification |
|---|---|---|
| `path` | obligatoire | relatif à la configuration ; peut contenir des `{{variables}}` |
| `format` | selon l'extension | `json`, `csv` ou `text` (`auto` : `.json`, `.csv`, sinon texte) |
| `delimiter` | `;` ou `,` | pour le CSV : deviné d'après la ligne d'en-tête s'il est omis |
| `extract` | | JSON : un pointeur (`/order/id`). CSV : `colonne` (première ligne) ou `colonne[]` (toutes les lignes). Texte : `content` ou `lines` |

La première ligne du CSV nomme les colonnes ; les champs peuvent être entre guillemets (`"a; b"`, `""`
pour un guillemet).

### Étapes commande

Lance un programme : le moyen de brancher ce que ChainRunner ne fait pas lui-même, dans n'importe quel
langage.

| Clé | Défaut | Signification |
|---|---|---|
| `run` | obligatoire | une liste (`[./notify.sh, "{{id}}"]` : le programme et ses arguments, sans shell) ou un texte (lancé par `sh -c`, `cmd /c` sous Windows) |
| `input` | | écrit sur son entrée standard : du YAML envoyé en JSON, ou un texte |
| `env` | | des variables d'environnement ajoutées pour le programme |
| `dir` | le dossier de la configuration | où il s'exécute |
| `timeout` | celui des options | le programme est arrêté après ce délai |
| `expectExit` | `0` | les codes de sortie qui comptent comme OK |
| `stopOnError` | `false` | `true` : un échec arrête la séquence |
| `extract` | | `stdout` (toute la sortie), `stderr`, `exitCode`, ou un pointeur JSON de la sortie |

Avec un texte, les `{{variables}}` vont dans une ligne de commande shell : préférez la forme liste quand
une valeur vient d'une réponse.

## Règles d'enchaînement

| Résultat | Effet |
|---|---|
| 2xx (ou un statut de `expectStatus`) | étape suivante |
| 401 | nouveau jeton, l'appel est refait une fois ; un second 401 arrête |
| 5xx | arrête la séquence (`stopOnServerError`) |
| autre 4xx | noté, la séquence continue (`stopOnClientError`) |
| erreur réseau, jeton refusé, variable inconnue | arrête la séquence |
| poll : une ligne | étape suivante, avec les valeurs extraites |
| poll : aucune ligne après `timeout` | `onTimeout` |
| base injoignable, requête refusée | arrête la séquence |
| téléchargement : le fichier existe | cette valeur échoue, la séquence continue |
| garde : l'étape est inutile | pas un échec, les étapes suivantes continuent |
| sql : aucune ligne (sans `allowEmpty`), fichier absent ou illisible | cette valeur échoue, la séquence continue |
| commande : code de sortie inattendu, délai dépassé | cette valeur échoue (`stopOnError: true` arrête la séquence) |

Dans un `forEach`, un échec qui n'arrête pas la séquence n'abandonne que les étapes suivantes de cette
valeur.
