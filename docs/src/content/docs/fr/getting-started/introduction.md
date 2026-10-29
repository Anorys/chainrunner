---
title: Introduction
description: Ce que fait ChainRunner, et les mots employés dans cette documentation.
---

ChainRunner fait une **séquence** d'**étapes**, l'une après l'autre, sur un **environnement**
(développement, acceptation, production...). Il est fait pour les tâches qui sont aujourd'hui une suite
d'appels manuels : appeler une API pour lancer un traitement, attendre que son résultat apparaisse en
base, télécharger le document produit, et cela pour chaque valeur d'une liste.

## Les mots

Environnement
: Là où les étapes s'exécutent : les URL de base de ses **API**, la façon dont chacune obtient son
  token, ses **bases de données** et ses propres variables. Un environnement marqué `protected`
  demande que l'on tape son nom avant chaque exécution.

API
: Un service appelé par les étapes, avec son `baseUrl`, ses en-têtes et son propre token OAuth2. Une
  étape nomme l'API qu'elle appelle avec `api:`.

Base de données
: Une base PostgreSQL interrogée par les étapes `poll` et les gardes, en lecture seule. Une étape la
  nomme avec `database:`.

Séquence
: Une liste nommée d'étapes. Avec un `forEach`, ses étapes sont faites pour chaque valeur d'une liste.

Étape
: Un appel HTTP (`type: http`, par défaut), éventuellement un téléchargement (`saveTo`), ou une attente
  en base (`type: poll`). Une étape peut **extraire** des valeurs de sa réponse ou de sa ligne, que
  les étapes suivantes utilisent comme `{{variables}}`.

Garde
: Une requête lancée avant une étape : `skipIf` saute l'étape quand elle trouve une ligne, `onlyIf`
  quand elle n'en trouve pas. Une étape sautée ainsi est *non nécessaire*, pas en échec.

## Ce qui se passe pendant une exécution

1. ChainRunner prépare chaque étape avec les variables connues à ce moment : celles de la
   configuration, de l'environnement, celles tapées dans l'interface ou données avec `--var`, et les
   valeurs extraites par les étapes précédentes.
2. Il obtient le token de l'API quand la première étape en a besoin, et le renouvelle quand il expire
   ou quand l'API répond 401.
3. Il applique les [règles d'enchaînement](../../reference/configuration/#règles-denchaînement) : un
   2xx continue, un 5xx arrête la séquence, une attente en base abandonne après son délai.
4. Il écrit un journal de l'exécution (requêtes, réponses, lignes), sans les tokens ni les en-têtes
   secrets.

Ensuite : [installer ChainRunner](../installation/), puis suivre le
[démarrage rapide](../quick-start/).
