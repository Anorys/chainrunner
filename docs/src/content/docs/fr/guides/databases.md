---
title: Bases de données, attentes et gardes
description: Attendre qu'une ligne apparaisse, extraire ses colonnes, et sauter les étapes déjà faites.
---

Un environnement liste ses bases PostgreSQL sous `databases`, comme il liste ses API. Son bloc
`database` ne contient que des valeurs par défaut que chaque base hérite, champ par champ.

```yaml
environments:
  dev:
    database:                       # valeurs par défaut : ici, le même compte partout
      username: ${DEV_DB_USERNAME}
      password: ${DEV_DB_PASSWORD:-}   # vide : demandé avant la première requête
    databases:
      orders: { url: "jdbc:postgresql://db-dev:5432/orders" }
      archive: { url: "jdbc:postgresql://db-dev:5432/archive", username: archive_reader }
```

Une étape `poll` ou une garde nomme sa base avec `database:` ; elle ne peut l'omettre que si
l'environnement n'en a qu'une. Chaque base est ouverte à sa première requête et reste ouverte jusqu'à
la fin du lancement.

Les connexions sont **en lecture seule** : la session est ouverte en lecture seule sur le serveur,
chaque requête est annulée (rollback), et seules les requêtes `select` et `with ... select` sont
acceptées. Un compte en lecture seule reste une bonne idée.

## Attendre une ligne : `type: poll`

```yaml
- name: "File {{accountId}}"
  type: poll
  database: orders
  sql: |
    select doc_id, created
    from export_file
    where account_id = {{accountId}} and created::date = {{today}}
  every: 10s            # fréquence de la requête (10s par défaut)
  timeout: 15m          # quand abandonner (15m par défaut)
  onTimeout: next       # next : cette valeur échoue, les autres continuent ; stop : la séquence s'arrête
  onManyRows: first     # first : la première ligne est prise ; fail : l'étape échoue
  extract:
    docId: doc_id       # {{docId}} pour les étapes suivantes ; sans extract, toutes les colonnes
```

La requête est relancée jusqu'à ce qu'elle renvoie une ligne dont les colonnes extraites ne sont pas
nulles. Les noms de colonnes sont comparés sans tenir compte de la casse (PostgreSQL transforme `docID`
en `docid`).

### Variables dans le SQL

Chaque `{{variable}}` devient un paramètre lié `?`, jamais du texte collé dans la requête : pas de
guillemets à ajouter, pas d'injection. Une liste devient `?, ?, ?`, pour un `in (...)`. Les filtres
fonctionnent comme partout (`{{today | date("YYYY") | number}}`), et la valeur garde son type : un
nombre est lié comme un nombre. Les guillemets écrits autour d'une variable (`'{{x}}'`) sont retirés.

## Sauter une étape : `skipIf` et `onlyIf`

Toute étape peut d'abord lancer une requête :

```yaml
- name: "Generate {{accountId}}"
  api: orders
  method: POST
  path: /files
  database: orders
  skipIf: select 1 from export_file where account_id = {{accountId}} and created::date = {{today}}
```

- `skipIf` : quand la requête renvoie une ligne, l'étape est **inutile** et n'est pas faite.
- `onlyIf` : l'étape n'est faite que si la requête renvoie une ligne.

Une étape sautée n'est pas un échec : les étapes suivantes pour la même valeur continuent. Une garde
s'exécute sur la `database` de l'étape, ou sur la sienne : `skipIf: { sql: "select ...", database: archive }`.

## Téléchargements

Une étape HTTP avec `saveTo` écrit sa réponse dans un fichier :

```yaml
- name: "Document {{accountId}}"
  api: document
  path: /documents/{{docId}}
  saveTo: "documents/{{env}}/{{today}}/{{accountId}}-{{docId}}.xml"   # relatif à la configuration
  saveAs: raw              # raw : le corps tel quel ; base64:/content : un champ JSON, décodé
  overwrite: false         # un fichier existant fait échouer l'étape au lieu d'être remplacé
```

## Une valeur à la fois, ou une phase à la fois

Avec un `forEach` au niveau de la séquence, `order: phases` (par défaut) fait chaque étape pour toutes
les valeurs avant l'étape suivante : toutes les générations, puis toutes les attentes, puis tous les
téléchargements. `order: each` fait toutes les étapes d'une valeur avant la valeur suivante. Dans les
deux cas, une valeur extraite pour une valeur n'est vue que par les étapes de cette valeur, et un échec
n'abandonne que les étapes suivantes de cette valeur.
