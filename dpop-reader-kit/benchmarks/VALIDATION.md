# Validation de la campagne de mesures DPoP

| | |
|---|---|
| Campagne | 24-25 novembre 2026 : `results/browser/20261124T213707Z`, `results/jvm/20261124T213730Z`, `results/load/20261125T000047Z` |
| Révision mesurée | `8e8ee37` |
| Contenu | contre-vérification des chiffres, relecture critique du protocole, biais et réserves, non-régression du PoC, correction postérieure à la campagne |

> **Nature de cette validation.** Il s'agit d'une **contre-vérification interne**, menée par l'auteur des mesures avec un code de calcul distinct de celui de l'analyse. Elle détecte les erreurs de calcul et de transcription, et documente les biais connus. **Ce n'est pas une validation scientifique indépendante :** aucun tiers n'a rejoué la campagne ni relu le protocole. Les données brutes et les scripts sont publiés pour permettre cette vérification.

## 1. Synthèse

**Chiffres :** 84 valeurs publiées sont recalculées depuis les données brutes versionnées par `scripts/verify_numbers.py`, avec un code distinct d'`analyze.py`. Aucun écart.

**Protocole :** il est sain pour ce qu'il mesure :
- même image et mêmes ressources dans les deux modes ;
- critères de validité fixés avant l'analyse ;
- exécutions non valides exclues des comparaisons, mais publiées ;
- aucune somme de percentiles.

**Corrections apportées pendant la relecture :** elles portent sur des formulations et des notes de figures (§4). Aucune valeur numérique des résultats n'a dû être corrigée.

**Réserves :** voir §5, en particulier :
- R1 : le coût serveur est propre à la pile Java ;
- R2 : l'écart entre microbenchmark et mesure de bout en bout n'est pas expliqué.

**PoC :** son comportement était identique avant et après la campagne (§6). Une correction de sécurité lui a été apportée **après** la campagne. Elle n'est pas couverte par ces mesures (§7).

## 2. Ce qui a été relu

| Élément | Points examinés |
|---|---|
| `browser/src/bench-page.ts`, `run-browser-bench.mjs` | appel des fonctions réelles du PoC ; échauffement ; résolution du timer ; isolation des séries |
| `jvm/…/DpopValidationBenchmark.java`, `Fixtures.java`, `TokenBenchmark.java` | élimination de code mort ; génération des données hors mesure ; chemin de rejet ; taille du store ; accès aux méthodes privées |
| `load/k6/refresh.js`, `docker-compose.bench.yml`, `scripts/run_load.sh` | équivalence des deux modes ; cookies et rotation ; fenêtre de mesure ; relevé CPU ; isolement réseau |
| `scripts/analyze.py`, `load_timeline.py`, `jfr_hotspots.sh` | percentiles ; débit ; CPU par renouvellement ; octets ; appariement ; critères de validité |
| Données brutes | horodatages ; nombre d'échantillons ; connexions dans la fenêtre ; itérations abandonnées ; tailles de table |

## 3. Vérifications positives

| Vérification | Méthode | Résultat |
|---|---|---|
| Chiffres publiés | `verify_numbers.py` : quantiles navigateur depuis `raw.json`, moyennes JMH depuis les histogrammes de `jmh.json.gz`, p50/p95/p99 et CPU depuis `measure_samples.csv.gz` et `run.json`, surcoûts appariés | 84 valeurs, 0 écart |
| Même code que le PoC | lecture des imports et des appels | `buildDPopProof`, `DpopProofValidator.validate()` et l'image Docker du PoC sont utilisés tels quels |
| Chemin de rejet jamais mesuré | JMH : garde-fou par itération ; k6 : 0 échec sur les 18 exécutions valides | confirmé |
| Fenêtre CPU dans la phase de mesure | comparaison des instants `cpuWindow` au premier et au dernier échantillon de mesure (horodatage k6) | fenêtre entre +123,1 s et +240,2 s après le lancement de k6, phase de mesure entre ≈ 120,4-122,2 s et ≈ 240,4-242,2 s : comprise dedans dans tous les cas, avec 0,2 s de marge en fin de fenêtre pour les exécutions sans DPoP les plus précoces |
| Aucune connexion pendant la mesure | compteur `logins` en phase de mesure | 0 dans les 18 exécutions valides : le CPU mesuré ne contient que des renouvellements |
| Aucune itération abandonnée | `dropped_iterations` | 0 dans les 18 exécutions valides |
| Même état serveur dans les deux modes | taille finale de la table des jetons | identique à quelques entrées près (47 962 à 47 980 à 100/s) |
| Cookies conformes à un navigateur | `setCookie` du PoC : `Path=/`, `HttpOnly`, `SameSite=Strict` | un navigateur enverrait aussi les deux cookies à `/login/refresh`, comme le fait k6 |
| Décomposition des octets | delta des en-têtes `Cookie` et `DPoP` comparé au delta `data_sent` | 474 + 215 = 689 octets, contre 688 mesurés |
| Sens des écarts | signe des 9 paires valides | p50 : 9/9 positifs ; CPU : 9/9 ; p99 : 9/9 ; p95 : 8/9 (une paire négative à 10/s) |
| Variabilité JMH | écart min-max entre forks | < 6,5 % du débit, sauf pour l'anti-rejeu à 12 000 entrées (29 %) |

## 4. Imprécisions relevées pendant la relecture, et corrections

| # | Constat | Gravité | Correction |
|---|---|---|---|
| C1 | Une première synthèse attribuait « environ 690 octets » à la preuve. Or 215 de ces octets viennent du format des jetons du PoC (`cnf.jkt` dans un JWS imbriqué dans un JWE, présent dans deux cookies). La preuve seule fait 474 octets avec son en-tête. | moyenne : mélange du coût inhérent et du coût d'implémentation | décomposition 474 + 215 dans le README (§4) et la figure 4 |
| C2 | « Validation ≈ 0,5 ms » était présenté sans préciser que ce chiffre dépend de la pile Java (JDK 25, fournisseur SunEC, Nimbus). Les mesures externes sur Node et Go donnent un ordre de grandeur dix fois plus faible pour la vérification ES256 ([ANALYSIS.md](ANALYSIS.md) §2). | moyenne : risque de généralisation | le README et [ANALYSIS.md](ANALYSIS.md) précisent la pile |
| C3 | Note de la figure 2 : valeurs 524 et 536 µs écrites à la main ; note de la figure 4 : « +107 o » écrit à la main | faible | valeurs lues dans les CSV par `scripts/figures.py` |
| C4 | Les premières figures (150 dpi, PNG seul, palette non vérifiée, saturation non séparée) ne répondaient pas aux exigences de publication | faible | 6 figures et 1 figure annexe, en SVG et PNG 300 dpi, avec une palette vérifiée pour les daltonismes |

## 5. Biais et limites (réserves)

| # | Biais ou limite | Effet probable | Traitement |
|---|---|---|---|
| R1 | **Coût serveur propre à la pile.** La vérification ES256 coûte 536 µs avec JDK 25 / SunEC / Nimbus sur un cœur P. Les mesures publiques trouvées donnent ≈ 50 µs (Node 24 / jose, Apple M4) et ≈ 40 µs (Go). | Le coût serveur mesuré ne se généralise pas à DPoP. Matériel différent, donc pas de comparaison directe : seul l'ordre de grandeur diverge. | précisé partout ; voir [ANALYSIS.md](ANALYSIS.md) §2 |
| R2 | **Écart micro / bout en bout non expliqué.** +1,0 ms de CPU par renouvellement de bout en bout, contre 0,52 ms pour `validate()` dans JMH. | Hypothèses non vérifiées : conteneur non épinglé, donc possiblement sur des cœurs E environ deux fois plus lents ; GC de +100 Kio alloués par renouvellement ; jetons plus longs à chiffrer et déchiffrer ; JIT. | présenté comme ouvert ; aucun chiffre n'est déduit de cette différence |
| R3 | **CPU fixe de la JVM.** Le CPU par renouvellement divise tout le CPU du conteneur (GC, JIT, JFR, Tomcat) par le nombre de renouvellements. À 10/s, cette part fixe pèse plus. | Le ratio à 10/s (+23 %) est moins fiable. La pente de la régression CPU = f(débit) sur 9 exécutions par mode donne 4,05 ms (sans) et 5,06 ms (avec), soit **+1,01 ms**, cohérent avec les paires (+0,68 à +1,13). | signalé dans la note de la figure 1 |
| R4 | **Percentiles extrêmes sur peu d'échantillons.** 1 200 échantillons par exécution à 10/s, donc p99 sur ≈ 12 valeurs ; une paire p95 est négative. | p95 et p99 à 10/s fragiles | note de la figure 3 ; les conclusions s'appuient sur p50 et CPU |
| R5 | **Appariement limité.** Les « paires » regroupent les deux modes d'une même répétition, exécutés dans un ordre aléatoire mais pas l'un juste après l'autre (≈ 45 min par répétition). | Contrôle les dérives lentes, pas le bruit de court terme | décrit comme tel |
| R6 | **Dérive due à la table des jetons.** Même à 100/s, la latence monte d'environ 1 ms au fil d'une exécution (balayage O(N) sous verrou). | Les valeurs absolues dépendent de la durée de l'exécution. L'écart entre modes n'est pas biaisé, car les tables grossissent au même rythme. | figure 6 ; [METHODOLOGY.md](METHODOLOGY.md) §6 |
| R7 | **Générateur sur la même machine.** k6 consomme +0,06 cœur de plus en mode DPoP à 100/s, sur 22 processeurs logiques. | Contamination négligeable mais non nulle | documenté |
| R8 | **Navigateur de bureau.** Chromium sans interface sur des cœurs P d'un Core Ultra 7. Aucun mobile ni appareil d'entrée de gamme. Médiane quantifiée par pas de 5 µs. | Le coût client mesuré est un plancher, pas une valeur typique | précisé |
| R9 | **Anti-rejeu dans JMH.** Les entrées préremplies ne sont jamais expirées : la purge parcourt la table sans rien retirer. | Le coût réel de suppression (≈ 1 entrée par preuve en régime) n'est pas inclus : sous-estimation négligeable | documenté |
| R10 | **Occupation du store anti-rejeu reconstituée.** Elle est calculée à partir des preuves acceptées et du TTL de 120 s, pas lue dans le serveur. | Modèle exact si le code est respecté (`purgeExpiredJtis` à chaque preuve) | libellé « reconstituée » |
| R11 | **Allocations à 10/s.** Un seul GC dans la fenêtre de mesure, donc l'estimation inclut des allocations antérieures. | Valeur non exploitable | exclue des conclusions |
| R12 | **Pas de TLS, réseau virtuel local, machine unique, 3 répétitions** | Valeurs absolues non transposables ; plages min-max plutôt qu'intervalles de confiance | [METHODOLOGY.md](METHODOLOGY.md) §7 |

**Mesures qui lèveraient les réserves principales :**
- R2 : épingler les conteneurs ;
- R1 : mesurer ES256 avec un autre fournisseur, par exemple BouncyCastle ;
- R4 : allonger les exécutions à 10/s.

Elles n'ont pas été faites. Elles exigeraient une nouvelle campagne ou une modification de la configuration du PoC, alors que les biais R1 à R12 se documentent sans nouvelle mesure.

## 6. Non-régression du PoC pendant la campagne

**Résultat : la campagne n'a pas modifié le comportement du PoC.** Aucun fichier de production n'a été modifié pour mesurer. Les 28 tests passent, et les 11 scénarios d'attaque donnent les résultats documentés, avant comme après la campagne.

### 6.1 Isolement des benchmarks

La campagne n'a ajouté que le dossier `benchmarks/`. `git diff 8e8ee37` sur `backend/`, `frontend/`, `attacks/`, `tools/` et `docker-compose.yml` était vide, avant comme après la campagne.

| Exigence | Vérification |
|---|---|
| Comportement normal inchangé | aucune ligne de production modifiée ; mêmes tests et scénarios qu'avant |
| DPoP jamais désactivé par défaut | `application.yml` (`dpop.required: true`) et `docker-compose.yml` (`DPOP_REQUIRED: "true"`) inchangés |
| Mode sans DPoP isolé | `DPOP_REQUIRED=false` n'apparaît que dans `load/docker-compose.bench.yml`, sur un réseau `internal: true`, sans port publié |
| Aucun mécanisme de contournement ajouté | le mode sans DPoP réutilise le réglage `dpop.required`, déjà présent et documenté dans le PoC |
| Sessions liées protégées en mode sans DPoP | scénario 11 : un jeton lié reste refusé sans preuve ou avec une autre clé quand `dpop.required=false` |
| Services de benchmark non exposés | backend et k6 sur le réseau Docker interne, aucun `ports:` dans le Compose de benchmark |
| Données de test uniquement | utilisateurs `vu-<n>`, clés générées en mémoire pour chaque exécution |

### 6.2 Tests du backend

Commande : `mvn test` dans `backend/`, avec JDK 25.0.2, avant l'ajout des benchmarks et après la campagne.

| Classe | Tests | Échecs | Erreurs |
|---|---:|---:|---:|
| `RefreshFlowTest` | 4 | 0 | 0 |
| `RefreshRotationConcurrencyTest` | 24 | 0 | 0 |
| **Total** | **28** | **0** | **0** |

### 6.3 Scénarios d'attaque

Exécutés contre l'image du backend construite depuis `8e8ee37` et publiée uniquement sur `127.0.0.1`.

| Scénario | Configuration | Résultat |
|---|---|---|
| 01 Refresh token volé + clé de l'attaquant | `dpop.required=true` | Bloqué (`refresh rejected`) |
| 02 Rejeu d'une preuve | idem | Première utilisation acceptée, rejeu bloqué |
| 03 Preuve détournée (endpoint, méthode) | idem | 2/2 bloqués |
| 04 iat hors fenêtre (passé, futur) | idem | 2/2 bloqués |
| 05 alg, jwk et signature falsifiés | idem | 5/5 bloqués |
| 06 Réutilisation d'un refresh token | idem | Réutilisation détectée, session révoquée |
| 07 Session riding (XSS dans la page) | idem | Réussit, **comme documenté** (limite de DPoP) |
| 08a Refresh courant volé + mauvaise clé | idem | Attaquant refusé, client légitime accepté |
| 08b Ancien refresh volé + mauvaise clé | idem | Session révoquée, **comme documenté** (choix fail-safe) |
| 09 Tempête de 5 refresh parallèles | idem | 1 succès puis révocation, **comme documenté** (faux positif côté client) |
| 10a Preuve signée pour un autre hôte | idem | Bloqué |
| 10b Même preuve sur deux instances | 2 instances | Bloqué sur la même instance, accepté sur la voisine, **comme documenté** (anti-rejeu non partagé) |
| 11 Liaison indépendante du drapeau | `dpop.required=false` | Sans preuve : bloqué ; autre clé : bloqué ; clé légitime : accepté, `cnf.jkt` propagé |

Les scénarios 07, 08b, 09 et 10b décrivent des limites assumées du PoC. Leur résultat est celui annoncé par les scripts eux-mêmes.

### 6.4 Absence de secrets dans les résultats

- **Clés :** générées en mémoire (Web Crypto, k6, JMH) et jamais écrites sur disque. Les clés PEM des scénarios d'attaque restent dans un répertoire temporaire hors dépôt.
- **Contenu des fichiers :** k6 n'exporte que des métriques, sans corps de réponse. `run.json` ne contient que des compteurs de la table des jetons.
- **Fichiers non versionnés :** les enregistrements JFR et les CSV k6 complets sont exclus par `.gitignore`.
- **Recherche des motifs** `eyJ` (JOSE en Base64URL), `"d":`, `PRIVATE KEY` et `BEGIN` dans tous les fichiers versionnés de `results/`, y compris les archives `.gz` décompressées : aucune occurrence.

### 6.5 Contrôles après la campagne

| Contrôle | Résultat |
|---|---|
| `mvn test` dans `backend/` | 28 tests, 0 échec, 0 erreur : identique à l'état initial |
| `git diff 8e8ee37` hors `benchmarks/` | vide |
| Valeurs par défaut (`dpop.required: true`, `DPOP_REQUIRED: "true"`) | inchangées |
| Conteneurs et réseau de benchmark | arrêtés et supprimés ; aucun port publié pendant la campagne |
| Fichiers non versionnés | `k6.csv.gz`, `backend.jfr`, `jmh.json` non compressé, essais `smoke*` : exclus par `.gitignore` |

## 7. Correction postérieure à la campagne : rejet des en-têtes DPoP multiples

**Ce qui a changé.** Après la campagne, le PoC rejette les requêtes qui portent plus d'un en-tête `DPoP` (RFC 9449 §4.3, contrôle 1), y compris des valeurs combinées en une seule ligne par un intermédiaire. Le contrôle se trouve dans `AuthController.resolveJkt`. Il passe avant la validation de la preuve, donc avant l'enregistrement du `jti`.

**Ce qui n'a pas changé :** le validateur, le cache anti-rejeu, la rotation et les formats de réponse.

**Traçabilité :** les résultats de ce dossier ont été obtenus sur la révision `8e8ee37`, **antérieure à cette correction**. Ils n'ont pas été remesurés sur la version corrigée, et rien dans ce dossier ne doit être lu comme une mesure de cette version.

**Performances.** Ce contrôle a été ajouté après la campagne de mesures. Son impact sur les performances n’a pas été mesuré.

Le contrôle se trouve hors de `DpopProofValidator`. Les benchmarks JMH, qui appellent directement `validate()`, ne le traversent donc pas.
