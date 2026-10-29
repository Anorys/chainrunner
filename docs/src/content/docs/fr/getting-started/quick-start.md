---
title: Démarrage rapide
description: De la configuration d'exemple à une première exécution.
---

## 1. Une configuration

ChainRunner lit `chainrunner.yaml` dans le dossier courant (ou le fichier donné avec `-c`). Partez de
l'exemple du dépôt :

```shell
curl -LO https://raw.githubusercontent.com/anorys/chainrunner/main/chainrunner.example.yaml
mv chainrunner.example.yaml chainrunner.yaml
```

Indiquez les URL de vos environnements, et gardez les secrets en dehors : écrivez `${NOM}` dans le
YAML et la valeur dans un fichier `.env` à côté (ne commitez jamais `.env`) :

```properties
DEV_CLIENT_ID=mon-client
DEV_USERNAME=moi
# DEV_PASSWORD absent : demandé au premier appel, gardé en mémoire seulement
```

Une configuration minimale :

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
      - name: "Confirmer {{orderId}}"
        method: POST
        path: /api/orders/{{orderId}}/confirm
        forEach: { orderId: "{{orderIds}}" }
```

## 2. La vérifier

```shell
chainrunner --list       # les environnements et les séquences lus
chainrunner --dry-run    # chaque requête, préparée, rien n'est envoyé
```

## 3. La lancer

```shell
chainrunner              # l'interface : r lance la séquence, ? montre les touches
```

![L'interface avant une exécution : les étapes à gauche, la requête choisie à droite](../../../../assets/screenshots/tui-ready.png)

Ou sans l'interface, pour un script :

```shell
chainrunner --run --yes -e dev -s confirm-orders --var orderIds=1003,1004
```

Le code retour vaut 0 quand toutes les étapes sont OK, 1 quand l'une a échoué ou n'a pas été faite, 2
pour une erreur de configuration.

Ensuite : [plusieurs API et leurs tokens](../../guides/apis-and-tokens/), et
[attendre en base](../../guides/databases/).
