---
title: Quick start
description: From the example configuration to a first run.
---

## 1. A configuration

ChainRunner reads `chainrunner.yaml` in the current folder (or the file given with `-c`). Start from
the example of the repository:

```shell
curl -LO https://raw.githubusercontent.com/anorys/chainrunner/main/chainrunner.example.yaml
mv chainrunner.example.yaml chainrunner.yaml
```

Set your environments' URLs, and keep the secrets out of it: write `${NAME}` in the YAML and the value
in a `.env` file next to it (never commit `.env`):

```properties
DEV_CLIENT_ID=my-client
DEV_USERNAME=me
# DEV_PASSWORD left out: asked for at the first call, kept in memory only
```

A minimal configuration:

```yaml
environments:
  dev:
    apis:
      shop:
        baseUrl: https://shop-dev.example.com
        auth:
          tokenUrl: https://sso-dev.example.com/realms/example/protocol/openid-connect/token
          clientId: ${DEV_CLIENT_ID}
          username: ${DEV_USERNAME}
          password: ${DEV_PASSWORD:-}

variables:
  orderIds: [1001, 1002]

sequences:
  confirm-orders:
    steps:
      - name: "Confirm {{orderId}}"
        method: POST
        path: /api/orders/{{orderId}}/confirm
        forEach: { orderId: "{{orderIds}}" }
```

## 2. Check it

```shell
chainrunner --list       # the environments and the sequences it read
chainrunner --dry-run    # every request, rendered, nothing sent
```

## 3. Run it

```shell
chainrunner              # the terminal UI: r runs the sequence, ? shows the keys
```

![The UI before a run: steps on the left, the selected request on the right](../../../assets/screenshots/tui-ready.png)

Or without the UI, for a script:

```shell
chainrunner --run --yes -e dev -s confirm-orders --var orderIds=1003,1004
```

The exit code is 0 when every step is OK, 1 when one failed or was not run, 2 for a configuration
error.

Next: [several APIs and their tokens](../../guides/apis-and-tokens/), and
[waiting on a database](../../guides/databases/).
