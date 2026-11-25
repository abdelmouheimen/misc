# Rapport méthodologique — coût de DPoP dans le dpop-reader-kit

| | |
|---|---|
| Révision mesurée | `8e8ee37` (`origin/main`, révision publiée citée par l'article) |
| Campagne | 24-25 novembre 2026, 4 h 40, machine unique |
| Résultats bruts | `results/browser/20261124T213707Z`, `results/jvm/20261124T213730Z`, `results/load/20261125T000047Z` |
| Synthèse générée | `results/summary/` (`RESULTS.md`, CSV, `figures/`) |

## 1. Résumé

Trois niveaux de mesure, gardés séparés : leurs chiffres ne s'additionnent pas.

- **Navigateur :** une preuve DPoP coûte **65 µs** en médiane (`buildDPopProof()`, Chromium 159), p99 155 µs. Elle pèse 468 octets. La paire de clés, générée une fois par session, coûte 50 µs.
- **Serveur, isolé :** `DpopProofValidator.validate()` coûte **524 µs** par preuve sur un thread, avec un store anti-rejeu vide. La vérification de la signature ES256 en représente l'essentiel, les autres étapes coûtent quelques microsecondes chacune. Le coût croît avec la taille du store anti-rejeu, à cause de sa purge en O(N) : 641 µs à 12 000 entrées, 1 322 µs à 60 000.
- **Bout en bout, de 10 à 100 req/s :** sur le renouvellement du refresh token, DPoP ajoute en médiane :
  - **+0,76 à +0,91 ms** de latence HTTP p50 (+20 à +22 %) ;
  - **+0,68 à +1,13 ms** de CPU serveur par renouvellement (+17 à +23 %) ;
  - **+687 octets** par requête et +427 par réponse.
  - Toutes les exécutions sont valides (18/18), sans aucune erreur.
- **À 250 et 500 req/s :** **aucune mesure valide, avec ou sans DPoP.** Le PoC sature à cause de sa table des jetons, qui n'est jamais purgée et qui est balayée sous un verrou global à chaque renouvellement (section 6). Cette limite est propre au PoC et ne dit rien du coût de DPoP. Aucun débit maximal n'est donc publié.

## 2. Objet et périmètre

**Question :** combien coûte DPoP (RFC 9449), tel qu'implémenté dans le PoC, côté client, côté serveur et de bout en bout, par rapport au même PoC sans DPoP ?

**Ce qui est mesuré :**
- le code de production du PoC, sans réimplémentation :
  - `frontend/src/dpopService.ts` ;
  - `backend/src/main/kotlin`, compilé tel quel dans le module JMH ;
  - l'image Docker du backend construite depuis `8e8ee37` ;
- l'opération protégée choisie est le renouvellement du refresh token (`POST /api/login/refresh`), seule opération du PoC qui exige une preuve.

**Hors périmètre :**
- TLS ;
- réseau réel et plusieurs machines ;
- autres navigateurs que Chromium ;
- autres JVM et fournisseurs cryptographiques que SunEC/SunJCE ;
- serveur de ressources distinct ;
- store anti-rejeu partagé entre instances ;
- débits supérieurs à 100 req/s (section 6).

Aucun résultat n'est extrapolé hors de ce périmètre.

## 3. Environnement

| Élément | Valeur |
|---|---|
| Machine | Portable, Intel Core Ultra 7 155H : 16 cœurs, 22 processeurs logiques, hybride P/E/LP-E. 63,5 Go de RAM, sur secteur pendant toute la campagne |
| OS | Windows 11 Famille 10.0.26200 |
| JMH | JDK 25.0.2 (Oracle HotSpot), JMH 1.37, `-Xms1g -Xmx1g -XX:+UseG1GC`, épinglé sur les cœurs P (processeurs logiques 10 à 19) |
| Navigateur | Chromium 159.0.8154.6 sans interface, Playwright 1.65.1, Node 22.14.0, épinglé comme JMH |
| Backend sous charge | Image `dpop-lab-backend:bench-8e8ee37` (`sha256:745e25c5…`), Temurin 25.0.5.1, Spring Boot 4.1.1, Nimbus JOSE+JWT 10.9.1. Conteneur limité à 4 CPU et 2 Go, JVM `-Xms1g -Xmx1g`, G1, `ActiveProcessorCount=4`, JFR (profil `default`) |
| Générateur de charge | k6 v2.4.0 (binaire officiel, SHA-256 vérifié), conteneur `alpine` limité à 4 CPU et 2 Go |
| Conteneurs | Docker Desktop 27.5.1, WSL2 (noyau 5.15), cgroups v1. Réseau Docker `internal`, HTTP/1.1 sans TLS, aucun port publié |
| Fournisseurs JCA | SunEC (ECDSA), SUN (SHA-256), SunJCE (RSA-OAEP), identiques sur l'hôte et dans le conteneur |

Le relevé complet de chaque campagne est dans `environment.json` (`scripts/env_info.sh`).

## 4. Méthode

### 4.1 Navigateur

**Déroulement :**
- La page de mesure importe `initializeDPopKeyPair`, `buildDPopProof` et `computeJkt` du PoC.
- Elle est servie sur `127.0.0.1` avec COOP/COEP. L'origine est isolée, ce qui donne à `performance.now()` une résolution de 5 µs.
- Il y a 5 séries, chacune dans un contexte de navigation neuf. Chaque série fait 200 appels d'échauffement puis 2 000 mesures par opération.
- Chaque opération est aussi chronométrée en lot de 500 appels, pour une moyenne qui ne dépend pas de la résolution du timer.
- La signature seule est mesurée sur une entrée de 381 octets, la taille réelle d'une preuve.

**Limite :** les opérations de l'ordre de 5 µs (thumbprint, export) sont à la limite de la résolution. Leur médiane vaut 0 ou 1 pas du timer, et seule la moyenne en lot est exploitable.

### 4.2 Serveur (JMH)

**Configuration :** 3 forks, 5 × 5 s d'échauffement, 10 × 5 s de mesure, 1 thread, modes `Throughput` et `SampleTime`, profileur GC.

**Code mesuré :**
- Les méthodes privées du validateur (`sameHttpUri`, `purgeExpiredJtis`) sont appelées par `MethodHandle`, sans copie.
- `fullValidation` appelle le vrai `validate()`.

**Mesures valides :**
- Les preuves sont générées avant chaque itération, avec un iat frais et des jti uniques, dans un pool de threads séparé. Un GC est forcé ensuite.
- L'itération échoue si une seule preuve est rejetée ou si le pool s'épuise. Aucun chiffre ne provient donc du chemin de rejet.

**Taille du store anti-rejeu :** maintenue constante (0, 1 200, 12 000 ou 60 000 entrées). Le jti inséré est retiré après chaque opération, et ce retrait est inclus dans la mesure. Les tailles non nulles correspondent à 120 s de trafic (TTL du PoC) à 10, 100 et 500 req/s.

**Variabilité :** sur ce processeur hybride, un fork placé sur un cœur E est environ deux fois plus lent. Un essai préliminaire sans épinglage donnait des écarts d'environ ±25 % entre forks. Une fois la JVM épinglée sur les cœurs P, l'écart entre le fork le plus lent et le plus rapide reste sous 6,5 % du débit moyen pour tous les benchmarks sauf un : l'anti-rejeu à 12 000 entrées, où il atteint 29 %. La cause n'a pas été établie ; les effets de cache sur une table de cette taille sont une hypothèse. Les colonnes « Fork min/max » de `RESULTS.md` donnent ces écarts.

### 4.3 Bout en bout (k6)

**Comparaison :**
- **A, `bearer` :** `DPOP_REQUIRED=false`, réglage déjà présent dans le PoC.
- **B, `dpop` :** `DPOP_REQUIRED=true`, défaut du PoC.

Tout le reste est identique : image, endpoints, rotation, détection de réutilisation, cookies, ressources.

**Scénario :**
- Chaque VU se connecte une fois, puis renouvelle son refresh token en boucle et renvoie toujours le dernier reçu.
- En mode `dpop`, le VU signe chaque requête avec sa clé P-256 non exportable, un jti unique et un iat courant.

**Charge :**
- arrivée constante de 10, 50, 100, 250 et 500 req/s ;
- 120 s d'échauffement puis 120 s de mesure ;
- conteneur backend neuf à chaque exécution ;
- 3 répétitions dans un ordre tiré au sort (graine 20261124, `plan.csv`), soit 30 exécutions.

**Relevés :**
- **Latence HTTP :** `timings.duration`, sans la génération de la preuve.
- **Durée totale :** preuve + requête, mesurée avec `Date.now()`. k6 2.4 n'a pas `performance.now()`, d'où une résolution de 1 ms.
- **CPU des conteneurs :** lu dans les cgroups au début et à la fin de la fenêtre de mesure.
- **GC et allocations :** depuis l'enregistrement JFR du backend, restreint à la fenêtre de mesure.
- **Octets par requête :** `data_sent` / `data_received` divisés par le nombre de requêtes.
- **Store anti-rejeu :** occupation reconstituée à partir des preuves acceptées et du TTL de 120 s.
- **Table des jetons :** comptée en fin d'exécution.

**Validité :**
- Une exécution est valide si les erreurs restent sous 1 %, si au moins 95 % du débit demandé est atteint et si k6 utilise moins de 80 % de ses CPU.
- Les exécutions non valides sont exclues des comparaisons. Elles sont listées dans `RESULTS.md`.

**Comparaison appariée :** le surcoût est calculé par paire : même répétition, même débit, `dpop` contre `bearer`. Le tableau donne la médiane des paires [min–max]. Les percentiles ne sont jamais additionnés entre mesures indépendantes.

## 5. Résultats

### 5.1 Navigateur (`results/summary/figures/browser_webcrypto.png`)

| Opération (code du PoC) | Médiane | Moyenne | p95 | p99 | Fréquence |
|---|---:|---:|---:|---:|---|
| `initializeDPopKeyPair()` (génération + export) | 50 µs | 51 µs | 65 µs | 100 µs | une fois par session |
| `computeJkt()` | ≈ 5 µs (moyenne en lot) | 7 µs | 10 µs | 20 µs | une fois par session |
| `buildDPopProof()` | **65 µs** | 70 µs | 85 µs | 155 µs | à chaque requête protégée |
| dont signature ECDSA seule | 50 µs | 53 µs | 65 µs | 105 µs | |

- **Dispersion entre séries :** les médianes varient de moins de 5 % d'une série à l'autre.
- **Taille :** une preuve fait 468 octets, soit 476 octets pour la ligne d'en-tête `DPoP: …`. Sous k6, elle ne fait que 466 octets, car l'URL `htu` y est plus courte.

### 5.2 Serveur (JMH, 1 thread ; `results/summary/figures/jvm_components.png`, `results/summary/figures/jvm_replay_store.png`)

| Opération | Moyenne (µs) | p50 | p99 | Débit (ops/s) | Alloc. (o/op) |
|---|---:|---:|---:|---:|---:|
| Parsing de la preuve | 4,7 | 4,3 | 8,9 | 216 600 | 10 043 |
| Reconstruction de la clé publique | 1,3 | 1,0 | 2,5 | 1 074 000 | 1 192 |
| **Vérification ES256** | **536** | 489 | 1 133 | 1 981 | 31 796 |
| Thumbprint JWK | 1,2 | 1,1 | 2,2 | 917 500 | 1 800 |
| Extraction des claims | 2,2 | 2,0 | 3,8 | 485 100 | 5 064 |
| Contrôles htm/htu/iat | 0,9 | 0,8 | 1,7 | 1 176 700 | 759 |
| Anti-rejeu, store vide | 0,15 | 0,1 | 0,3 | 14 329 000 | 56 |
| Anti-rejeu, 1 200 entrées | 4,0 | 3,4 | 8,5 | 281 000 | 56 |
| Anti-rejeu, 12 000 entrées | 96 | 90 | 210 | 8 610 | 56 |
| Anti-rejeu, 60 000 entrées | 703 | 673 | 1 272 | 1 468 | 58 |
| **`validate()` complet, store vide** | **524** | 507 | 956 | 1 899 | 86 870 |
| `validate()`, 1 200 entrées | 527 | 509 | 985 | 1 886 | 87 037 |
| `validate()`, 12 000 entrées | 641 | 615 | 1 178 | 1 537 | 95 668 |
| `validate()`, 60 000 entrées | 1 322 | 1 253 | 2 474 | 787 | 139 203 |
| Émission refresh token, sans / avec `cnf.jkt` | 214 / 217 | | | 4 698 / 4 678 | 58 392 / 59 963 |
| Vérification refresh token, sans / avec `cnf.jkt` | 1 411 / 1 442 | | | 735 / 732 | 103 693 / 105 649 |

Les intervalles de confiance à 99,9 % de JMH restent sous 4 % du débit moyen, sauf pour l'anti-rejeu à 12 000 entrées (11 %). En latence (`SampleTime`), ils restent sous 1 % de la moyenne, sauf pour l'anti-rejeu avec un store vide (3,2 %, sur 0,15 µs).

**Lecture :**
- **Ce qui domine :** la vérification ES256. Les étapes de structure (parsing, clé, thumbprint, claims, contrôles) restent sous 5 µs chacune.
- **Pas de somme :** chaque ligne vient d'un benchmark séparé, avec ses propres données et son propre état du compilateur JIT. Elles ne s'additionnent pas pour reconstituer `validate()`. Le benchmark `fullValidation` mesure directement ce total.
- **Purge anti-rejeu :** `purgeExpiredJtis()` parcourt tout le store à chaque validation. Son coût est négligeable jusqu'à 1 200 entrées et devient du même ordre que la signature vers 60 000 entrées. C'est une propriété de l'implémentation du PoC, pas de DPoP : un store indexé par date d'expiration rendrait la purge indépendante de la taille.
- **Coût de `cnf.jkt` :** +1,4 % à l'émission et +2,2 % à la vérification du refresh token.
- **Échelle :** le déchiffrement JWE RSA-OAEP du refresh token (1 411 µs) coûte plus cher que la validation d'une preuve DPoP.

### 5.3 Bout en bout (`results/summary/figures/load_latency.png`, `results/summary/figures/load_cpu_throughput.png`)

**Valeurs absolues :** médiane des 3 répétitions, toutes valides de 10 à 100 req/s, sans aucune erreur.

| Débit | Mode | p50 HTTP | p95 | p99 | CPU backend (ms/renouvellement) | Store anti-rejeu |
|---|---|---:|---:|---:|---:|---:|
| 10 | bearer | 3,74 ms | 5,11 | 6,41 | 5,13 | 0 |
| 10 | dpop | 4,44 ms | 6,05 | 7,73 | 6,08 | 1 200 |
| 50 | bearer | 3,63 ms | 4,86 | 5,98 | 4,05 | 0 |
| 50 | dpop | 4,39 ms | 5,50 | 7,37 | 4,73 | 6 000 |
| 100 | bearer | 4,06 ms | 5,45 | 7,25 | 4,17 | 0 |
| 100 | dpop | 5,01 ms | 6,63 | 8,45 | 5,15 | 12 000 |

**Surcoût apparié :** dpop − bearer, même répétition, médiane des 3 paires [min–max].

| Débit | Δ p50 HTTP | Δ p50 % | Δ p95 % | Δ p99 % | Δ CPU ms/renouv. | Δ CPU % | Δ octets requête | Δ octets réponse |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | +0,76 ms [0,54–0,77] | +20 % [15–21] | +25 % [−11–31] | +27 % [5–27] | +1,13 [0,66–1,24] | +23 % | +687 | +426 |
| 50 | +0,78 ms [0,76–0,80] | +22 % [21–22] | +18 % [10–23] | +30 % [11–48] | +0,68 [0,62–0,80] | +17 % | +688 | +427 |
| 100 | +0,91 ms [0,87–1,09] | +22 % [21–28] | +19 % [18–28] | +17 % [15–43] | +0,93 [0,90–1,16] | +22 % | +688 | +428 |

**Détail des octets :**
- **Requête (+688 octets, +29,6 %) :**
  - en-tête `DPoP` : 466 octets plus le nom de l'en-tête ;
  - cookies : +213 octets, car les deux jetons portent `cnf.jkt` (environ +107 octets chacun après chiffrement JWE).
- **Réponse (+427 octets, +9,1 %) :** les mêmes jetons, plus longs, figurent dans les `Set-Cookie` et dans le corps JSON.

**Mémoire :**
- **Allocations :** à 50 et 100 req/s, le backend alloue environ 307 Kio par renouvellement avec DPoP contre 205 Kio sans, soit +100 Kio (+50 %). C'est cohérent avec les 87 Ko alloués par `validate()` dans JMH.
- **Fiabilité à 10 req/s :** l'estimation n'y est pas exploitable. Un seul GC tombe dans la fenêtre de mesure, et les allocations de fond dominent.
- **Pauses GC :** moins de 110 ms cumulées sur 120 s dans toutes les exécutions valides.

**Générateur de charge :** k6 n'a jamais dépassé 0,42 cœur sur 4.

**Durée totale côté client :** elle inclut la génération de la preuve par k6, environ 260-300 µs en moyenne, soit 4 à 5 fois le coût mesuré dans Chromium. Elle ne représente donc pas un navigateur. Avec sa résolution de 1 ms, son écart (+1 à +2 ms) n'est donné qu'à titre indicatif. Pour le coût côté client, la référence est la mesure navigateur (5.1).

**Micro et bout en bout :** le surcoût CPU de bout en bout (+0,7 à +1,1 ms par renouvellement) dépasse le coût de `validate()` mesuré par JMH (0,52 à 0,64 ms selon la taille du store). Les deux mesures ne portent pas sur le même périmètre. Le bout en bout inclut aussi :
- le traitement d'un en-tête et de cookies plus longs ;
- le chiffrement et le déchiffrement de jetons plus grands ;
- le JIT ;
- la concurrence sur 4 CPU dans un conteneur.

Ce rapport ne tente pas de décomposer cet écart.

## 6. Exécutions non valides : saturation propre au PoC à 250 et 500 req/s

Les 12 exécutions à 250 et 500 req/s sont non valides, **dans les deux modes**. Elles n'ont aucune erreur HTTP, mais n'atteignent pas 95 % du débit demandé :
- **250 req/s :** 235 req/s atteintes en bearer, 217 en dpop ;
- **500 req/s :** 197 en bearer, 175 en dpop.

Aucune comparaison n'est tirée de ces exécutions. La cause a été diagnostiquée.

### 6.1 La capacité baisse au fil de l'exécution (`results/summary/figures/load_timeline.png`, `results/summary/load_timeline.csv`)

- **À 250 req/s :** le débit tient pendant 110 à 180 s, un peu moins longtemps en dpop qu'en bearer, puis s'effondre vers 150-200 req/s pendant que la latence passe de 5 ms à plus de 2 s.
- **À 500 req/s :** le débit décroît régulièrement, de 500 à environ 150 req/s.
- **À 100 req/s :** la latence médiane dérive déjà lentement au fil de l'exécution, d'environ 3,1 à 4,1 ms en bearer.

Quand la charge demandée est constante, une capacité qui décroît signale un coût qui augmente avec l'état du serveur. Une limite de CPU fixe donnerait un débit stable.

### 6.2 La cause est la table des jetons du PoC (`results/summary/jfr_hotspots.txt`)

**Ce que montrent les enregistrements JFR du backend**, à 250 et 500 req/s et dans les deux modes :
- **Contention :** 12 800 à 61 900 événements de contention de moniteur (attentes de plus de 20 ms), presque tous sur `TokenStore.refresh` ligne 87, c'est-à-dire le `synchronized(lock)` global de la rotation.
- **CPU :** la première méthode des échantillons CPU est `TokenStore.revokeAccessForUser`. En mode dpop, c'est son `Intrinsics.areEqual`, appelé depuis cette méthode.
- **Mécanisme :** à chaque renouvellement, cette méthode parcourt la totalité de la table des jetons pour révoquer les access tokens de l'utilisateur.
- **Taille de la table :** elle n'est jamais purgée. Elle compte 116 854 entrées à la fin d'une exécution à 250 req/s.

**Effet :** le temps passé sous le verrou augmente avec le nombre de renouvellements déjà effectués. Le traitement est sérialisé : le backend n'utilise qu'environ 1,4 à 1,9 cœur sur les 4 disponibles.

**Ce qu'on peut en dire :**
- Ce goulot existe aussi sans DPoP. C'est une simplification assumée du PoC (stockage en mémoire, une seule instance), pas un coût de DPoP.
- DPoP s'effondre un peu plus tôt, ce qui est cohérent avec son surcoût CPU par requête. L'écart n'est pas chiffré, faute de mesures valides.
- **Conséquence :** cette campagne ne publie aucun débit maximal, ni avec ni sans DPoP. Pour mesurer le coût de DPoP au-delà de 100 req/s, il faudrait d'abord une table des jetons indexée par utilisateur et purgée, ce qui modifierait le PoC et sort du périmètre de la mission.

**Effet aux débits valides :** la même dérive existe à 100 req/s, en plus faible. Les valeurs absolues dépendent donc de la taille de la table. La comparaison appariée reste équitable : à débit égal, les deux modes effectuent le même nombre de renouvellements et leurs tables grossissent au même rythme (environ 48 000 entrées en fin d'exécution à 100 req/s dans les deux modes).

## 7. Limites et biais

1. **Une seule machine, portable, processeur hybride :**
   - JMH et le navigateur sont épinglés sur les cœurs P ;
   - les conteneurs ne le sont pas : Docker Desktop ne le permet pas sous WSL2, et les débits mesurés restent faibles devant les 22 processeurs logiques ;
   - les valeurs absolues ne se transposent pas à un serveur.
2. **Bruit de fond :**
   - d'autres conteneurs de l'utilisateur tournaient dans la VM Docker, au repos à moins de 1 % de CPU ; ils sont listés dans `environment.json` ;
   - aucun autre travail n'a été lancé pendant la campagne.
3. **Pas de TLS, réseau local virtuel :** les latences absolues et le surcoût relatif sont ceux d'un aller-retour local. Un déploiement avec TLS et un vrai réseau n'a pas été mesuré, et ces pourcentages ne s'y transposent pas.
4. **Deux JVM différentes :** JMH tourne sur l'hôte (Oracle 25.0.2), la charge dans le conteneur (Temurin 25.0.5.1). Les fournisseurs cryptographiques sont les mêmes, mais les deux séries ne se comparent pas directement.
5. **Implémentation du PoC :**
   - purge anti-rejeu en O(N) (5.2) ;
   - table des jetons non purgée, sous un verrou global (6).
   
   Ces deux choix pèsent sur les chiffres absolus et ne représentent pas une implémentation de production.
6. **k6 n'est pas un navigateur :**
   - sa génération de preuve est plus lente que celle de Chromium ;
   - son horloge a une résolution de 1 ms ;
   - c'est pourquoi le coût côté client vient uniquement de la mesure navigateur.
7. **Mesures en conteneur :**
   - les cgroups v1 donnent le CPU au début et à la fin de la fenêtre, sans résolution plus fine ;
   - l'estimation des allocations par JFR se fait par différence de tas entre GC, et reste approximative avec peu de GC (10 req/s).
8. **Variabilité :** seulement 3 répétitions par configuration, d'où des plages [min–max] et non des intervalles de confiance. Les plages de p95 et p99 sont larges ; la médiane de la latence et le CPU par requête sont les indicateurs les plus stables.

## 8. Non-régression après campagne

Contrôles effectués le 25 novembre 2026, après la campagne :

| Contrôle | Résultat |
|---|---|
| `mvn test` dans `backend/` (JDK 25.0.2) | 28 tests, 0 échec, 0 erreur (`RefreshFlowTest` 4, `RefreshRotationConcurrencyTest` 24) |
| `git diff 8e8ee37` hors `benchmarks/` | vide : aucun fichier du PoC modifié |
| Valeurs par défaut | `application.yml` `dpop.required: true`, `docker-compose.yml` `DPOP_REQUIRED: "true"`, inchangés |
| Conteneurs de benchmark | arrêtés et supprimés, réseau `internal` supprimé ; aucun port n'a été publié |
| Recherche de secrets dans les fichiers versionnés (`eyJ`, `"d":`, `PRIVATE KEY`, `BEGIN`, archives `.gz` décompressées) | aucune occurrence dans les résultats. Seule correspondance : la ligne de `RAPPORT_NON_REGRESSION.md` qui cite ces motifs |
| Fichiers non versionnés | `k6.csv.gz`, `backend.jfr`, `jmh.json` non compressé, essais `smoke*` : exclus par `.gitignore` |

## 9. Reproduire

Voir `README.md`. En résumé :
- `scripts/run_all.sh` rejoue toute la campagne (4 h 40 sur cette machine) ;
- `scripts/analyze.py`, `scripts/load_timeline.py` et `scripts/jfr_hotspots.sh` régénèrent la synthèse à partir des résultats bruts.
