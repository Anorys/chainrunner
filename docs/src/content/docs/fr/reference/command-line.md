---
title: Ligne de commande
description: Les options de chainrunner et de chainrunner self-update, les variables d'environnement, les codes de sortie.
---

```text
chainrunner [options]
chainrunner self-update [--check] [--to X.Y.Z] [--force]
```

Sans `--run`, `chainrunner` ouvre l'[interface terminal](../../guides/terminal-ui/) ; sans terminal, il
s'exécute comme avec `--run`.

| Option | Signification |
|---|---|
| `-c`, `--config FICHIER` | la configuration (`chainrunner.yaml` par défaut) |
| `-e`, `--env NOM` | l'environnement (`defaultEnvironment` par défaut) |
| `-s`, `--sequence NOM` | la séquence (la première par défaut) |
| `--var NOM=VALEUR` | définit ou remplace une variable ; répétable ; une liste s'écrit `a,b,c` |
| `--run` | lance sans l'interface |
| `--dry-run` | affiche chaque requête, n'envoie rien |
| `-y`, `--yes` | ne demande pas de confirmation (avec `--run`) |
| `--list` | liste les environnements et les séquences |
| `--no-update-check` | ne cherche pas de nouvelle version |
| `--check-terminal` | indique quel support de terminal a été chargé (diagnostic) |
| `--check-tls` | indique d'où viennent les certificats de confiance (diagnostic) |
| `-V`, `--version` | la version |
| `-h`, `--help` | l'aide |

## `self-update`

| Option | Signification |
|---|---|
| `--check` | dit seulement si une version plus récente existe |
| `--to X.Y.Z` | installe cette version, même plus ancienne |
| `--force` | installe même si cette version est déjà la dernière |

## Certificats

Les requêtes HTTPS (étapes, jetons, vérification des versions, `self-update`) font confiance aux
autorités de certification fournies avec Java et, sous Windows, aussi à celles du magasin racine de
Windows (*Autorités de certification racines de confiance*, de la machine et de l'utilisateur). Un
proxy d'entreprise qui déchiffre le HTTPS est donc accepté dès que son certificat est installé dans
Windows, comme pour les navigateurs. `chainrunner --check-tls` montre ce qui a été chargé :

```text
TLS trust: Java (146 certificates) + Windows (63 certificates)
```

Sous Linux et macOS, seule la liste de Java est utilisée ; on peut en donner une autre avec
`-Djavax.net.ssl.trustStore=/chemin/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit`.
`options.insecureTls: true` désactive la vérification pour les étapes (serveurs de test seulement).

## Variables d'environnement

| Variable | Effet |
|---|---|
| `NO_COLOR` | pas de couleurs |
| `CHAINRUNNER_ASCII=1` | symboles ASCII au lieu d'Unicode (anciennes consoles) |
| `CHAINRUNNER_NO_UPDATE_CHECK=1` | pas de vérification quotidienne des versions (aussi désactivée quand `CI` est défini) |
| `CHAINRUNNER_UPDATE_URL` | un miroir de l'API des releases GitHub |
| `CHAINRUNNER_GITHUB_TOKEN`, `GH_TOKEN` | envoyé à l'API des releases s'il est défini |
| `HTTPS_PROXY`, `NO_PROXY` | le proxy pour la vérification des versions et `self-update` |

Les `${NOM}` de la configuration sont lus dans l'environnement, puis dans `.env`.

## Codes de sortie

| Code | Signification |
|---|---|
| 0 | chaque étape est OK ou inutile |
| 1 | une étape a échoué ou n'a pas été faite ; `self-update` a échoué |
| 2 | erreur de configuration ou d'utilisation |
