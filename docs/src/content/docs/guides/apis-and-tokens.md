---
title: APIs and tokens
description: Several APIs per environment, each with its own OAuth2 token, and settings they inherit.
---

An environment lists its APIs under `apis`. Each API has its `baseUrl`, its own token, and optionally
its `auth` and `headers`. There is no main API: a step names the one it calls with `api:`, and may
leave it out only when the environment has a single API.

```yaml
environments:
  dev:
    auth:                                   # defaults, inherited field by field
      grantType: password
      tokenUrl: https://sso-dev.example.com/realms/example/protocol/openid-connect/token
      clientId: ${DEV_CLIENT_ID}
      username: ${DEV_USERNAME}
      password: ${DEV_PASSWORD:-}
    apis:
      orders:
        baseUrl: https://orders-dev.example.com   # everything from the environment's auth
      document:
        baseUrl: https://document-dev.example.com
        auth:                               # only these fields change
          grantType: client_credentials
          clientId: ${DEV_DOC_CLIENT_ID}
          clientSecret: ${DEV_DOC_CLIENT_SECRET}
      public:
        baseUrl: https://status.example.com
        auth: { grantType: none }           # no token at all

sequences:
  s:
    steps:
      - { name: Start, api: orders, method: POST, path: /jobs }
      - { name: Fetch, api: document, path: /documents/42 }
```

## How the auth of an API is made

The environment's `auth` is a set of **defaults**: nothing in it is required, and on its own it makes
no token. Each API's `auth` is that set with the API's own fields laid over it, one field at a time.
An API with no auth at all (neither its own nor the environment's) sends no token.

| Field | Default | Meaning |
|---|---|---|
| `grantType` | `password` | `password`, `client_credentials` or `none` |
| `tokenUrl` | | the token endpoint; required unless `grantType: none` |
| `clientId`, `clientSecret` | | the OAuth2 client |
| `clientAuth` | `body` | `body`: client id and secret in the form; `basic`: in an `Authorization: Basic` header |
| `username`, `password` | | for `grantType: password`; an empty password is asked for |
| `scope` | | sent when set |
| `header` | `Authorization` | the header that carries the token |
| `prefix` | `Bearer ` | written before the token |

## Renewal

A token is asked for when the first step of its API needs it, and kept until it expires. A 401 from
the API renews it (with the refresh token when there is one, else a new login) and makes the call
again once; a second 401 stops the sequence. A step with `auth: false` sends no token.

## When a token is refused

The error says what was sent, secrets masked, to find a wrong variable quickly:

```text
token refused: HTTP 401: Invalid client credentials
sent: POST https://sso-dev.example.com/realms/example/protocol/openid-connect/token
  grant_type = password
  client_id = 'my-client'  (in the form)
  client_secret = (empty: variable missing from .env?)
  username = 'me'
  password = 12 characters
```

In the UI, `t` asks every API for a token at once and shows these details for each refusal.
