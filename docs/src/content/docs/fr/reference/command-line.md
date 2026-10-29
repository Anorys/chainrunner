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
| `-V`, `--version` | la version |
| `-h`, `--help` | l'aide |

## `self-update`

| Option | Signification |
|---|---|
| `--check` | dit seulement si une version plus récente existe |
| `--to X.Y.Z` | installe cette version, même plus ancienne |
| `--force` | installe même si cette version est déjà la dernière |

## Variables d'environnement

| Variable | Effet |
|---|---|
| `NO_COLOR` | pas de couleurs |
| `CHAINRUNNER_ASCII=1` | symboles ASCII au lieu d'Unicode (anciennes consoles) |
| `CHAINRUNNER_NO_UPDATE_CHECK=1` | pas de vérification quotidienne des versions (aussi désactivée quand `CI` est défini) |
| `CHAINRUNNER_UPDATE_URL` | un miroir de l'API des releases GitHub |
| `CHAINRUNNER_GITHUB_TOKEN`, `GH_TOKEN` | envoyé à l'API des releases s'il est défini |
| `HTTPS_PROXY`, `NO_PROXY` | le proxy pour la vérification des versions et `self-update`, voir [Proxy](#proxy) |

Les `${NOM}` de la configuration sont lus dans l'environnement, puis dans `.env`.

## Proxy

ChainRunner fait deux sortes de requêtes HTTP, qui ne trouvent pas leur proxy de la même façon.

| Requêtes | Proxy |
|---|---|
| la vérification des versions et `self-update` (vers `api.github.com` et les téléchargements GitHub) | `HTTPS_PROXY`, ou les propriétés Java ci-dessous si elles sont définies |
| les étapes et les jetons (vos API, vos points d'accès de jetons) | seulement les propriétés Java ci-dessous ; `HTTPS_PROXY` n'est **pas** utilisé |
| les bases de données (PostgreSQL) | jamais de proxy : connexion directe à l'hôte et au port de l'`url` |

### `HTTPS_PROXY` et `NO_PROXY`

- `HTTPS_PROXY` est l'adresse du proxy : `http://proxy.example.com:8080`, ou simplement
  `proxy.example.com:8080`. Sans port : 80 (443 pour `https://...`). `https_proxy` en minuscules
  fonctionne aussi. `HTTP_PROXY` n'est pas lu : les releases ne sont jointes qu'en HTTPS.
- Un nom d'utilisateur et un mot de passe dans l'adresse (`http://user:secret@proxy:8080`) sont
  ignorés : un proxy qui demande une authentification n'est pas pris en charge.
- `NO_PROXY` liste, séparés par des virgules, les hôtes joints sans le proxy : un nom exact
  (`github.example.com`), un domaine avec tous ses sous-domaines (`.example.com` ou `*.example.com`),
  ou `*` pour tous les hôtes. Les plages d'adresses IP (`10.0.0.0/8`) et les ports ne sont pas compris.
  `localhost`, `127.x.x.x` et `::1` ne passent jamais par le proxy.

```shell
# Linux, macOS
export HTTPS_PROXY=http://proxy.example.com:8080
export NO_PROXY=.example.com,github.example.com
```

```bat
:: Windows, cmd : pour cette fenêtre seulement
set HTTPS_PROXY=http://proxy.example.com:8080
:: Windows : définitivement, pour les terminaux ouverts ensuite
setx HTTPS_PROXY http://proxy.example.com:8080
```

```powershell
# Windows, PowerShell : pour cette fenêtre seulement
$env:HTTPS_PROXY = "http://proxy.example.com:8080"
```

### Propriétés Java

Les propriétés de proxy Java s'appliquent à toutes les requêtes HTTP, étapes comprises, et quand
`https.proxyHost` est défini, `HTTPS_PROXY` est ignoré. Donnez-les avant les autres options, à
l'exécutable natif comme au jar :

```shell
chainrunner -Dhttps.proxyHost=proxy.example.com -Dhttps.proxyPort=8080 -Dhttp.nonProxyHosts="localhost|*.example.com" --run -s generate
java -Dhttps.proxyHost=proxy.example.com -Dhttps.proxyPort=8080 -jar chainrunner-X.Y.Z.jar
```

| Propriété | Pour |
|---|---|
| `https.proxyHost`, `https.proxyPort` | les URL `https://` |
| `http.proxyHost`, `http.proxyPort` | les URL `http://` |
| `http.nonProxyHosts` | les hôtes joints directement, séparés par `\|`, avec `*` comme joker : `localhost\|*.example.com` |

## Codes de sortie

| Code | Signification |
|---|---|
| 0 | chaque étape est OK ou inutile |
| 1 | une étape a échoué ou n'a pas été faite ; `self-update` a échoué |
| 2 | erreur de configuration ou d'utilisation |
