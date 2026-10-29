---
title: Scripts et CI
description: Lancer une séquence sans l'interface, et lire le résultat dans le code de sortie et le journal.
---

`--run` fait les étapes sans l'interface, une ligne par étape :

```shell
chainrunner --run --yes -e acc -s generate-and-download --var accountIds=ACC001,ACC002
```

```text
◆ chainrunner  generate-and-download on Acceptance  orders-acc.example.com, document-acc.example.com

✔   1. Generate ACC001  204  312 ms
○   2. Generate ACC002  not needed: skipIf found a row  9 ms
✔   3. File ACC001 in the database  found  0:42  {docId=D-881}
       found at attempt 5 after 0:42
✔   4. Document ACC001  200  95 ms
       file: documents/acc/2026-10-28/ACC001-D-881.xml (12.4 KB)

✔ 3 OK, 1 not needed, 0 failed, 0 not run  log: logs/chainrunner-20261028-140512-acc-generate-and-download.log
```

- Sans terminal, ChainRunner n'attend jamais une touche : `--yes` est obligatoire, et un mot de passe
  absent de la configuration est une erreur (renseignez-le dans l'environnement ou dans `.env`).
- Sur un environnement protégé, `--yes` ne suffit pas quand un terminal est là : il faut taper son nom.
- `--dry-run` affiche chaque requête (HTTP et SQL, avec leurs valeurs) et n'envoie rien.

## Codes de sortie

| Code | Signification |
|---|---|
| 0 | chaque étape est OK ou inutile |
| 1 | une étape a échoué, ou n'a pas été faite |
| 2 | erreur de configuration ou d'utilisation (environnement inconnu, mot de passe manquant sans terminal...) |

## Journaux

Chaque lancement écrit `logs/chainrunner-<date>-<env>-<sequence>.log` à côté de la configuration
(`options.logDir`, vide pour aucun) : chaque requête, réponse, requête SQL, paramètre et tentative. Les
jetons et les en-têtes dont le nom contient `auth`, `key`, `secret`, `token` ou `password` sont écrits `***`.

## Dans un pipeline

```yaml
# GitHub Actions
- name: Generate this month's files
  env:
    ACC_CLIENT_ID: ${{ secrets.ACC_CLIENT_ID }}
    ACC_CLIENT_SECRET: ${{ secrets.ACC_CLIENT_SECRET }}
    ACC_USERNAME: ${{ secrets.ACC_USERNAME }}
    ACC_PASSWORD: ${{ secrets.ACC_PASSWORD }}
  run: ./chainrunner --run --yes -e acc -s generate
```

La vérification des mises à jour est désactivée en CI (`CI` est défini).
