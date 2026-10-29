---
title: API et jetons
description: Plusieurs API par environnement, chacune avec son propre jeton OAuth2, et les réglages qu'elles héritent.
---

Un environnement liste ses API sous `apis`. Chaque API a son `baseUrl`, son propre jeton, et
éventuellement son `auth` et ses `headers`. Il n'y a pas d'API principale : une étape nomme celle
qu'elle appelle avec `api:`, et ne peut l'omettre que si l'environnement n'a qu'une seule API.

```yaml
environments:
  dev:
    auth:                                   # valeurs par défaut, héritées champ par champ
      grantType: password
      tokenUrl: https://sso-dev.example.com/realms/example/protocol/openid-connect/token
      clientId: ${DEV_CLIENT_ID}
      username: ${DEV_USERNAME}
      password: ${DEV_PASSWORD:-}
    apis:
      orders:
        baseUrl: https://orders-dev.example.com   # tout vient de l'auth de l'environnement
      document:
        baseUrl: https://document-dev.example.com
        auth:                               # seuls ces champs changent
          grantType: client_credentials
          clientId: ${DEV_DOC_CLIENT_ID}
          clientSecret: ${DEV_DOC_CLIENT_SECRET}
      public:
        baseUrl: https://status.example.com
        auth: { grantType: none }           # aucun jeton

sequences:
  s:
    steps:
      - { name: Start, api: orders, method: POST, path: /jobs }
      - { name: Fetch, api: document, path: /documents/42 }
```

## Comment l'auth d'une API est construite

L'`auth` de l'environnement est un ensemble de **valeurs par défaut** : rien n'y est obligatoire, et à
elle seule elle ne produit aucun jeton. L'`auth` de chaque API est cet ensemble, recouvert par les
champs propres à l'API, un champ à la fois. Une API sans aucune auth (ni la sienne ni celle de
l'environnement) n'envoie pas de jeton.

| Champ | Défaut | Signification |
|---|---|---|
| `grantType` | `password` | `password`, `client_credentials` ou `none` |
| `tokenUrl` | | le point d'accès des jetons ; obligatoire sauf avec `grantType: none` |
| `clientId`, `clientSecret` | | le client OAuth2 |
| `clientAuth` | `body` | `body` : id et secret du client dans le formulaire ; `basic` : dans un en-tête `Authorization: Basic` |
| `username`, `password` | | pour `grantType: password` ; un mot de passe vide est demandé |
| `scope` | | envoyé s'il est renseigné |
| `header` | `Authorization` | l'en-tête qui porte le jeton |
| `prefix` | `Bearer ` | écrit devant le jeton |

## Renouvellement

Un jeton est demandé quand la première étape de son API en a besoin, et gardé jusqu'à son expiration.
Un 401 de l'API le renouvelle (avec le refresh token s'il y en a un, sinon par une nouvelle connexion)
et refait l'appel une fois ; un second 401 arrête la séquence. Une étape avec `auth: false` n'envoie
pas de jeton.

## Quand un jeton est refusé

L'erreur montre ce qui a été envoyé, secrets masqués, pour trouver vite une variable erronée :

```text
token refused: HTTP 401: Invalid client credentials
sent: POST https://sso-dev.example.com/realms/example/protocol/openid-connect/token
  grant_type = password
  client_id = 'my-client'  (in the form)
  client_secret = (empty: variable missing from .env?)
  username = 'me'
  password = 12 characters
```

Dans l'interface, `t` demande un jeton à toutes les API d'un coup et affiche ces détails pour chaque refus.
