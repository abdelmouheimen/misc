# Benchmarks DPoP du dpop-reader-kit

Mesure du coût de DPoP dans le PoC qui accompagne l'article MISC « DPoP en pratique : quand voler un jeton ne suffit plus » :
- génération des clés et des preuves dans le navigateur ;
- validation des preuves côté serveur ;
- surcoût d'un renouvellement de refresh token de bout en bout.

Rien ici ne modifie le PoC. Les benchmarks réutilisent son code de production :
- `frontend/src/dpopService.ts` pour le navigateur ;
- `backend/src/main/kotlin` pour JMH, compilé tel quel ;
- l'image Docker du backend pour la charge HTTP.

Résultats et analyse : [`results/summary/RESULTS.md`](results/summary/RESULTS.md), [`RAPPORT_METHODOLOGIQUE.md`](RAPPORT_METHODOLOGIQUE.md), [`RAPPORT_NON_REGRESSION.md`](RAPPORT_NON_REGRESSION.md) et le paragraphe proposé pour l'article, [`PARAGRAPHE_ARTICLE_2.2.md`](PARAGRAPHE_ARTICLE_2.2.md).

```
benchmarks/
  browser/   Web Crypto dans Chromium (Playwright) : page de mesure + lanceur
  jvm/       Microbenchmarks JMH (module Maven séparé, sources du backend compilées tel quel)
  load/      Charge HTTP k6 : scénario de renouvellement + Compose isolé
  scripts/   Lanceurs, relevé d'environnement, analyse
  results/   Résultats bruts (par campagne horodatée) et synthèse
```

## Prérequis

Tous les outils sont gratuits :

| Outil | Version utilisée | Rôle |
|---|---|---|
| JDK | 25 (`JAVA_HOME`, par défaut `C:\Program Files\Java\jdk-25.0.2`) | JMH, relevé d'environnement, lecture JFR |
| Maven | 3.8+ | Construction du module JMH |
| Node.js | 22.12+ | Benchmark navigateur |
| Docker | Docker Desktop (WSL2) ou Docker Engine | Backend et k6 pour la charge |
| Python | 3.10+ avec `matplotlib` | Analyse et graphiques |
| Bash | Git Bash sous Windows | Scripts |

**Outils téléchargés automatiquement :**
- **Playwright et Chromium :** versions figées par `browser/package-lock.json`.
- **k6 :** binaire officiel `v2.4.0` récupéré sur les releases GitHub, avec vérification de sa somme SHA-256, puis exécuté dans un conteneur `alpine` (voir `scripts/fetch_k6.sh`).

## Lancer les mesures

Depuis `dpop-reader-kit/benchmarks` :

```bash
scripts/run_all.sh        # tout, séquentiellement, puis l'analyse (4 h 40 mesurées ; refuse de démarrer sur batterie)

scripts/run_browser.sh    # Web Crypto dans Chromium            (< 1 min)
scripts/run_jvm.sh        # JMH, configuration complète          (2 h 23)
scripts/run_load.sh       # k6, 5 débits × 2 modes × 3 répétitions (2 h 13)
python3 scripts/analyze.py   # tableaux et graphiques à partir des derniers résultats
python3 scripts/load_timeline.py   # débit et latence pendant chaque exécution k6 (diagnostic)
scripts/jfr_hotspots.sh > results/summary/jfr_hotspots.txt   # contention et points chauds (JFR)
```

**Essais rapides, sans valeur de mesure :**

```bash
scripts/run_browser.sh --series 1 --iterations 200
scripts/run_jvm.sh -f 1 -wi 1 -w 2 -i 2 -r 2 DpopValidationBenchmark.fullValidation
CAMPAIGN=smoke RATES="50" REPS=1 WARMUP=10 DURATION=20 scripts/run_load.sh
```

**Paramètres principaux :**
- **`run_browser.sh` :** `--series`, `--warmup`, `--iterations`, `--batch`.
- **`run_jvm.sh` :** toute option JMH.
- **`run_load.sh` (variables d'environnement) :** `RATES`, `REPS`, `WARMUP`, `DURATION`, `SEED`, `COOLDOWN`.

## Ce que mesure chaque benchmark

### Navigateur (`browser/`)

Chromium sans interface, piloté par Playwright. La page est servie sur `127.0.0.1` avec COOP/COEP, ce qui isole l'origine et donne à `performance.now()` une résolution de 5 µs. Chaque série tourne dans un nouveau contexte de navigation. Le démarrage du navigateur n'est jamais chronométré.

| Opération | Code exécuté |
|---|---|
| Génération de la paire ECDSA P-256 | `crypto.subtle.generateKey`, paramètres de `initializeDPopKeyPair` |
| Export de la clé publique en JWK | `crypto.subtle.exportKey('jwk', …)` |
| `initializeDPopKeyPair()` | fonction du PoC |
| Thumbprint JWK | `computeJkt()` du PoC |
| Signature ECDSA seule | `crypto.subtle.sign` sur une entrée de la taille réelle d'une preuve |
| Preuve complète | `buildDPopProof()` du PoC |
| Taille des preuves | longueur des preuves produites |

Chaque opération est mesurée de deux façons : par appel (moyenne, médiane, min, max, p95, p99, écart-type) et sur un lot d'appels consécutifs (moyenne non limitée par la résolution du timer).

### Serveur (`jvm/`, JMH 1.37)

**Configuration :** 3 forks, 5 × 5 s d'échauffement, 10 × 5 s de mesure, 1 thread, modes `Throughput` et `SampleTime`, profileur GC (allocations par opération), sortie JSON. Les sources Kotlin du backend sont compilées telles quelles dans le module.

**Benchmarks :**
- **Étapes du validateur :** parsing, reconstruction de la clé publique, vérification ES256, thumbprint, extraction des claims, contrôles htm/htu/iat.
- **Anti-rejeu :** `purgeExpiredJtis` puis `putIfAbsent`, pour un store de 0, 1 200, 12 000 et 60 000 entrées.
- **Validation complète :** le vrai `DpopProofValidator.validate()`, pour les mêmes tailles de store.
- **Comparaison :** émission et vérification d'un refresh token, avec et sans `cnf.jkt`.

**Précautions :**
- **Code privé :** les deux méthodes privées du validateur (`sameHttpUri`, `purgeExpiredJtis`) sont appelées par `MethodHandle`, sans copie ni modification de leur visibilité.
- **Données hors mesure :** les preuves de la validation complète sont générées avant chaque itération (iat frais, jti uniques), dans un pool de threads séparé, puis un GC est forcé. Une itération échoue si une seule preuve est rejetée ou si le pool s'épuise : le chemin de rejet n'est jamais mesuré par erreur.
- **Taille du store constante :** le jti inséré est retiré après chaque opération (retrait inclus dans la mesure).
- **Épinglage :** sous Windows, la JVM est épinglée sur les cœurs P (`PIN_MASK`, voir `scripts/cpu_topology.ps1`). Sur un processeur hybride, un fork placé sur un cœur E est environ deux fois plus lent.

### Bout en bout (`load/`, k6 2.4)

| | A. Baseline (`bearer`) | B. DPoP (`dpop`) |
|---|---|---|
| `DPOP_REQUIRED` | `false` | `true` (défaut du PoC) |
| En-tête `DPoP` | absent | preuve fraîche à chaque requête |
| Jetons | JWS ES256 chiffrés en JWE, sans `cnf.jkt` | identiques, avec `cnf.jkt` |
| Endpoints, rotation, détection de réutilisation, cookies | identiques | identiques |
| Image, JVM (`-Xms1g -Xmx1g`, G1, 4 CPU), conteneur (4 CPU, 2 Go), réseau | identiques | identiques |

**Baseline :**
- **Réglage réutilisé :** elle utilise le réglage `dpop.required=false` qui existe déjà dans le PoC (README, `docker-compose.yml`). Aucun mécanisme n'est ajouté.
- **Isolement :** elle n'est activée que dans `load/docker-compose.bench.yml`, sur un réseau Docker interne, sans port publié.
- **Liaisons préservées :** dans ce mode, un jeton lié à une clé reste lié (scénario d'attaque 11).

**Scénario :**
- **Charge :** débit d'arrivée constant de 10, 50, 100, 250 et 500 req/s, avec 120 s d'échauffement puis 120 s de mesure.
- **Répétitions :** 3, dans un ordre tiré au sort à chaque répétition (graine enregistrée).
- **État initial :** chaque exécution part d'un conteneur neuf.
- **Sessions :** chaque VU a sa session, sa clé et ses cookies. Il renvoie toujours le dernier refresh token reçu (rotation) et signe chaque preuve avec un jti unique et un iat courant.

**Relevés :**
- **Latences :** latence HTTP (`timings.duration`, sans la génération de la preuve) et durée totale (preuve + requête, résolution 1 ms).
- **Débit :** débit atteint, erreurs, `dropped_iterations`.
- **CPU :** CPU des conteneurs backend et k6, lu dans les cgroups au début et à la fin de la fenêtre de mesure.
- **JVM :** GC et allocations, depuis l'enregistrement JFR du backend.
- **Réseau :** octets envoyés et reçus par requête.
- **Occupation :** table des jetons et store anti-rejeu.

**Validité :** une exécution n'est valide que si erreurs ≤ 1 %, débit atteint ≥ 95 % du débit demandé et k6 sous 80 % de ses CPU. Les exécutions non valides sont signalées et exclues des comparaisons.

## Résultats

```
results/
  browser/<date>/   raw.json (tous les échantillons), meta.json, environment.json
  jvm/<date>/       jmh.json.gz (sortie JMH complète), jmh.log, environment.json
  load/<campagne>/  plan.csv, parameters.json, environment.json
    rep<N>/<débit>rps_<mode>/
      summary.json            résumé k6 de l'exécution
      measure_samples.csv.gz  latences de chaque renouvellement de la phase de mesure
      run.json                CPU, mémoire, occupation de la table des jetons
      docker_stats.csv        CPU/mémoire des deux conteneurs, ≈ 1 échantillon/s
      k6.csv.gz, backend.jfr  bruts complets (volumineux, non versionnés)
  summary/          tableaux CSV, RESULTS.md, figures/, load_timeline.csv, jfr_hotspots.txt
```

**Données sensibles :** aucun résultat ne contient de clé privée ni de jeton. Les clés de test sont générées en mémoire et jamais écrites. k6 n'exporte que des métriques. L'endpoint `/debug/tokens` n'est interrogé que pour compter les entrées.
