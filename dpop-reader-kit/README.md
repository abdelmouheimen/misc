# DPoP en pratique — kit du lecteur

Laboratoire accompagnant l'article **« DPoP en pratique : quand voler un jeton ne suffit plus »**
(revue *MISC*). DPoP (RFC 9449) lie un access token et un refresh token à une clé
cryptographique détenue par le client : un jeton volé devient inutilisable sans la clé.

Ce dépôt permet de **voir le mécanisme fonctionner, puis de l'attaquer**, exactement dans
l'architecture de l'article : une **SPA React/TypeScript**, un **backend Kotlin / Spring Boot**,
un générateur de preuves DPoP (Python) et une série de scénarios d'attaque rejouables.

> ⚠️ Lab pédagogique, volontairement simplifié et non sécurisé. Lire [SECURITY.md](SECURITY.md).

## Prérequis

- **JDK 21** et **Maven** (ou **Docker**, au choix) pour le backend
- **Node 18+** pour la SPA React
- **Python 3.9+** avec `cryptography` (`pip install cryptography`) pour le générateur et les attaques
- `bash` et `curl` pour les scripts d'attaque (Git Bash sous Windows)

## Démarrage

```bash
# 1. Backend (DPoP exigé, port 8099)
cd backend && mvn spring-boot:run
#    …ou sans Maven :   docker compose up --build

# 2. SPA React (port 5173), dans un autre terminal
cd frontend && npm install && npm run dev
#    puis ouvrez http://localhost:5173/
```

Dans la SPA : **Se connecter** signe une preuve DPoP et ouvre une session ; **Renouveler**
déclenche une rotation ; **Tenter d'exporter la clé** démontre que la clé privée est
inaccessible à JavaScript ; **Table des jetons** affiche l'état côté serveur.

La SPA (`http://localhost:5173`) et le backend (`http://localhost:8099`) sont sur deux origines
distinctes : le backend expose donc du **CORS** autorisant l'en-tête `DPoP` et les credentials
(voir `backend/…/CorsConfig.kt`) — un point facile à oublier lorsqu'on ajoute DPoP.

### Rejouer les attaques

```bash
# Un jeton volé ne suffit pas
cd attacks && bash 01_stolen_refresh_wrong_key.sh
# Tout rejouer
bash attacks/run_all.sh
```

## Structure

| Chemin | Rôle |
|---|---|
| `backend/` | Backend Spring Boot : validation DPoP, liaison et rotation des jetons |
| `backend/…/DpopProofValidator.kt` | Les contrôles d'une preuve, dans l'ordre (le cœur du sujet) |
| `backend/…/TokenStore.kt` | Rotation du refresh token et détection de réutilisation |
| `backend/…/AuthController.kt` | Endpoints `/login/connection`, `/login/refresh`, `/debug/tokens` |
| `backend/…/CorsConfig.kt` | CORS autorisant l'en-tête `DPoP` et les credentials |
| `frontend/` | SPA React/TypeScript |
| `frontend/src/dpopService.ts` | Génération de la clé non exportable et signature des preuves (côté navigateur) |
| `frontend/src/api.ts` | Appels au backend avec preuve DPoP et cookies |
| `tools/dpop_proof.py` | Générateur de preuves DPoP, valides ou volontairement invalides |
| `attacks/` | Sept scénarios rejouables (voir ci-dessous) |

## Le générateur de preuves

`tools/dpop_proof.py` fabrique une preuve DPoP ES256 et l'affiche. Il sert aussi à produire
des preuves **invalides** pour les attaques.

```bash
# Preuve valide pour un refresh (clé persistée -> réutilisable pour la rotation)
python tools/dpop_proof.py --htu http://localhost:8099/login/refresh --key client.pem

# Empreinte (jkt) de la clé — ce que le serveur stocke avec le jeton
python tools/dpop_proof.py --key client.pem --print-jkt
```

Options utiles : `--alg none`, `--no-jwk`, `--private-in-jwk`, `--tamper`, `--iat-offset <s>`,
`--jti <valeur>` (pour rejouer), `--htm`, `--htu`. Chacune cible un contrôle précis du validateur.

## Les scénarios d'attaque

Tous dans `attacks/`, rejouables un par un ou via `run_all.sh`. Chacun affiche la commande,
la réponse HTTP et le verdict.

| # | Script | Ce qu'il montre |
|---|---|---|
| 1 | `01_stolen_refresh_wrong_key.sh` | Refresh volé + clé de l'attaquant → **rejet** (jkt) |
| 2 | `02_replay_proof.sh` | Rejeu d'une preuve → **rejet** (jti déjà vu) |
| 3 | `03_wrong_endpoint_or_method.sh` | Preuve détournée → **rejet** (htu / htm) |
| 4 | `04_stale_or_future_iat.sh` | Preuve périmée ou future → **rejet** (iat) |
| 5 | `05_algorithm_and_jwk_tampering.sh` | `alg:none`, jwk falsifié, signature altérée → **rejet** |
| 6 | `06_refresh_reuse_detection.sh` | Réutilisation d'un refresh tourné → **session révoquée** |
| 7 | `07_limits_what_still_works.sh` | Signature depuis la page légitime → **réussit** (limite assumée) |

Le scénario 7 démontre une attaque qui **réussit** : c'est le propos de la partie « Limites »
de l'article. DPoP lie le jeton à une clé, pas à une intention.

## Ce que le lab simplifie par rapport à l'article

- **Store en mémoire** au lieu de PostgreSQL : le sujet est DPoP, pas la persistance.
  L'endpoint `/debug/tokens` remplace l'inspection de la table `authentication_token`.
- **Jetons opaques** (UUID) plutôt que des JWT signés : la liaison à la clé est identique,
  le code reste focalisé.
- **Comparaison de `htu` sur le chemin** : voir la remarque « reverse proxy » de l'article.
- **Anti-rejeu `jti` en mémoire** : suffisant en mono-instance ; en cluster, un store partagé
  (Redis) est nécessaire.

## Licence

[MIT](LICENSE).
