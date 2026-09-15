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

### Tout en Docker (une commande)

```bash
docker compose up --build
#   SPA React ...... http://localhost:5173
#   Backend API .... http://localhost:8099
```

Rien d'autre à installer : ni JDK, ni Node. Le premier build compile le backend (Maven)
et la SPA (npm) dans les images ; les lancements suivants sont immédiats.

### …ou sans Docker

```bash
# 1. Backend (DPoP exigé, port 8099)
cd backend && mvn spring-boot:run

# 2. SPA React (port 5173), dans un autre terminal
cd frontend && npm install && npm run dev
#    puis ouvrez http://localhost:5173/
```

> Les scripts d'attaque (`attacks/`) requièrent Python + `cryptography` sur la machine hôte,
> quel que soit le mode de lancement du backend.

Dans la SPA : **Se connecter** signe une preuve DPoP et ouvre une session ; **Renouveler**
déclenche une rotation ; **Tenter d'exporter la clé** démontre que la valeur de la clé privée
ne peut pas être extraite (la `CryptoKey` peut en revanche toujours signer — c'est le sujet du
scénario 7) ; **Table des jetons** affiche l'état côté serveur.

La SPA (`http://localhost:5173`) et le backend (`http://localhost:8099`) sont sur deux origines
distinctes : le backend expose donc du **CORS** autorisant l'en-tête `DPoP` et les credentials
(voir `backend/…/CorsConfig.kt`) — un point facile à oublier lorsqu'on ajoute DPoP.

### `htu` et reverse proxy

Le claim `htu` d'une preuve est comparé à l'**URI complète** de la requête (schéma, hôte, port
et chemin, hors query et fragment — RFC 9449 §4.3). Cette URI est reconstruite à partir de
l'URL publique configurée (`dpop.public-base-url`, variable `DPOP_PUBLIC_BASE_URL`) et du chemin
reçu, et **non** à partir de l'URL vue par le backend : derrière un reverse proxy, celui-ci
reçoit `http://backend:8099/login/refresh` alors que le navigateur a signé
`https://api.example.com/login/refresh`. Comparer le seul chemin pour contourner ce problème
accepterait une preuve destinée à un autre serveur. Si vous exposez le backend sous une autre
adresse, mettez cette variable à jour.

### Rejouer les attaques

Deux formats, au choix :

```bash
# a) Scripts shell (zéro IDE, idéal en CI)
cd attacks && bash 01_stolen_refresh_wrong_key.sh
bash attacks/run_all.sh

# b) Fichiers .http cliquables (IntelliJ / WebStorm, ou httpyac)
npx httpyac send attacks/http/01_stolen_refresh_wrong_key.http --all --env dev
```

Les fichiers `.http` (dossier [`attacks/http/`](attacks/http/)) contiennent des assertions
et s'ouvrent directement dans le client HTTP d'IntelliJ. Voir leur README pour le détail
(et pourquoi ils s'appuient sur un endpoint de laboratoire `/debug/proof`).

## Structure

| Chemin | Rôle |
|---|---|
| `backend/` | Backend Spring Boot : validation DPoP, liaison et rotation des jetons |
| `backend/…/DpopProofValidator.kt` | Les contrôles d'une preuve, dans l'ordre (le cœur du sujet) |
| `backend/…/JwtService.kt` | JWT signés (ES256) puis chiffrés (JWE RSA-OAEP-256 / A256GCM), liaison `cnf.jkt` |
| `backend/…/IntrospectController.kt` | `/debug/introspect` : claims déchiffrés par le serveur (lab uniquement) |
| `backend/…/TokenStore.kt` | Rotation du refresh token et détection de réutilisation |
| `backend/…/AuthController.kt` | Endpoints `/login/connection`, `/login/refresh`, `/debug/tokens` |
| `backend/…/JwksController.kt` | Clé publique de signature des jetons : `/.well-known/jwks.json` |
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
- **Clés de signature et de chiffrement générées au démarrage** : les jetons émis avant un
  redémarrage deviennent invalides. En production, elles sont persistées (KMS/HSM) et tournées.
- **Refresh token accepté aussi dans l'en-tête `X-Refresh-Token`** : uniquement pour que les
  fichiers `.http` restent reproductibles (le client HTTP de l'IDE gère ses propres cookies).
  Les scripts shell rejouent le jeton volé dans l'en-tête `Cookie`, comme le ferait un
  attaquant ; en production, le refresh token ne transite que par le cookie `HttpOnly`.
- **Anti-rejeu `jti` en mémoire** : suffisant en mono-instance ; en cluster, un store partagé
  (Redis) est nécessaire.

## Les jetons émis

Access et refresh token sont des **JWT imbriqués, signés puis chiffrés** :

1. **Signature (JWS ES256)** : le serveur signe les claims, ce qui garantit leur intégrité ;
2. **Chiffrement (JWE `RSA-OAEP-256` + `A256GCM`, `cty: JWT`)** : le JWS signé est chiffré, ce
   qui garantit sa confidentialité. Le client ne reçoit qu'un jeton opaque à 5 parties, dont
   seul l'en-tête est lisible :

```json
{ "alg": "RSA-OAEP-256", "enc": "A256GCM", "cty": "JWT", "kid": "…" }
```

Une fois déchiffré par le serveur, on trouve le JWS et ses claims. La liaison à la clé du
client est portée par `cnf.jkt` (RFC 9449 §6.1) :

```json
{ "typ": "at+jwt", "alg": "ES256", "kid": "…" }
{
  "iss": "dpop-lab-backend", "sub": "alice", "jti": "…",
  "iat": 1789389450, "exp": 1789389750,
  "token_type": "access", "auth_time": 1789389450,
  "cnf": { "jkt": "a4VmJvz0lfljPNV1H4rhchFnribeFrpGJR_wXgJ4o-s" }
}
```

Signature et chiffrement protègent le **contenu** du jeton, mais un jeton volé reste un jeton
valide : ni l'un ni l'autre ne disent **qui** le présente. C'est ce qu'ajoute DPoP.

- `/.well-known/jwks.json` expose la clé publique de **signature** (la clé de chiffrement
  reste privée au serveur, qui est à la fois émetteur et destinataire des jetons) ;
- `/debug/introspect` (lab uniquement) montre ce que seul le serveur voit après déchiffrement :
  la SPA et les scripts d'attaque s'en servent pour afficher les claims et le `jti`, qu'ils ne
  peuvent plus lire eux-mêmes.

> ⚠️ **`/debug/introspect` est un oracle de déchiffrement.** Quiconque détient un jeton peut
> l'envoyer à cet endpoint et lire ses claims : il **annule la confidentialité** apportée par
> le chiffrement. Il n'existe que pour rendre le lab observable, comme `/debug/tokens`.
> Une vraie application ne doit **jamais** exposer un tel endpoint ; si une introspection est
> nécessaire (RFC 7662), elle est réservée aux serveurs de ressources authentifiés.

La table des jetons (indexée par `jti`) reste nécessaire pour ce qu'un JWT seul ne permet
pas : révocation, rotation et détection de réutilisation.

## Licence

[MIT](LICENSE).
