# Scénarios d'attaque au format `.http`

Les mêmes attaques que les scripts `.sh`, mais sous forme de requêtes cliquables,
à exécuter dans un IDE (le client HTTP intégré d'**IntelliJ IDEA / WebStorm**) ou en
ligne de commande avec [**httpyac**](https://httpyac.github.io/).

## Pourquoi un endpoint `/debug/proof` ?

Une preuve DPoP est **signée**, avec un `jti` à usage unique et un `iat` valable ~60 s.
Impossible donc de la coder en dur dans un fichier statique. Chaque scénario appelle
d'abord `POST /debug/proof` — un endpoint **de laboratoire uniquement** — qui forge une
preuve fraîche (clé `client` ou `attacker`), capturée dans une variable puis présentée à
l'endpoint attaqué. Tout se chaîne dans le fichier, sans script externe.

> ⚠️ `/debug/proof` fait signer le **serveur**, ce qui va à l'encontre du principe de DPoP
> (le client détient la clé). C'est un raccourci pédagogique réservé à ce lab. La vraie
> signature côté client est dans la SPA React (`frontend/src/dpopService.ts`).

## Pourquoi l'en-tête `X-Refresh-Token` ?

Le client HTTP de l'IDE gère ses propres cookies : il renverrait automatiquement le refresh
token de la dernière réponse et masquerait le jeton « volé » que le scénario veut présenter.
Ces fichiers passent donc le refresh token dans l'en-tête `X-Refresh-Token`, que le backend du
lab accepte **uniquement pour cette raison**.

> Les scripts shell (`attacks/*.sh`), eux, rejouent le jeton volé dans l'en-tête `Cookie`, comme
> le ferait un attaquant. En production, le refresh token ne transite que par le cookie `HttpOnly`.

## Prérequis

- Backend démarré (`docker compose up` ou `mvn spring-boot:run`).
- **IntelliJ / WebStorm** : ouvrez un fichier, choisissez l'environnement `dev`
  (fichier `http-client.env.json`), puis « Run all requests in file ».
- **httpyac** : `npx httpyac send 01_stolen_refresh_wrong_key.http --all --env dev`

## Fichiers

| Fichier | Attaque |
|---|---|
| `01_stolen_refresh_wrong_key.http` | Refresh volé + clé de l'attaquant → rejet (jkt) |
| `02_replay_proof.http` | Rejeu d'une preuve → rejet (jti) |
| `03_wrong_endpoint_or_method.http` | Preuve détournée → rejet (htu / htm) |
| `04_stale_or_future_iat.http` | Preuve périmée ou future → rejet (iat) |
| `05_algorithm_and_jwk_tampering.http` | alg:none, jwk falsifié, signature altérée → rejet |
| `06_refresh_reuse_detection.http` | Réutilisation d'un refresh tourné → session révoquée |
| `07_limits_what_still_works.http` | Signature depuis la page légitime → réussit (limite assumée) |

Chaque fichier contient des assertions (`client.test`) : dans IntelliJ comme avec httpyac,
un scénario conforme se solde par des tests au vert.
