# Audit scientifique de la campagne de mesures DPoP

| | |
|---|---|
| Objet | campagne du 24-25 novembre 2026 (`results/browser/20261124T213707Z`, `results/jvm/20261124T213730Z`, `results/load/20261125T000047Z`) |
| Date de l'audit | 25 novembre 2026 |
| Posture | relecture critique : chercher les erreurs, les biais et les affirmations non étayées, sans présumer que les conclusions sont justes |

## 1. Verdict

- **Aucune erreur de calcul trouvée.** 84 valeurs publiées ont été recalculées depuis les données brutes versionnées, avec un code distinct de celui de l'analyse (`scripts/verify_numbers.py`). Toutes concordent.
- **Le protocole est sain pour ce qu'il mesure :**
  - même image et mêmes ressources dans les deux modes ;
  - critères de validité fixés avant l'analyse ;
  - exécutions non valides exclues ;
  - aucune somme de percentiles.
- **Corrections apportées :**
  - deux formulations des livrables précédents mélangeaient le coût inhérent à DPoP et le coût propre au PoC (§4, C1 et C2) ;
  - une note de figure contenait des valeurs saisies à la main (C3).
- **Réserves à garder dans toute communication :** voir §5, en particulier R1 (coût serveur propre à la pile Java) et R2 (écart micro/bout en bout non expliqué).

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

## 4. Erreurs et imprécisions trouvées, et corrections

| # | Constat | Gravité | Correction |
|---|---|---|---|
| C1 | Le paragraphe d'article précédent attribuait « environ 690 octets » à la preuve. Or 215 de ces octets viennent du format des jetons du PoC (`cnf.jkt` dans un JWS imbriqué dans un JWE, présent dans deux cookies). La preuve seule fait 474 octets avec son en-tête. | moyenne : mélange du coût inhérent et du coût d'implémentation | nouvelles propositions de paragraphe (`PARAGRAPHE_ARTICLE_2.2.md`) ; figure 4 |
| C2 | « Validation ≈ 0,5 ms » était présenté sans préciser que ce chiffre dépend de la pile Java (JDK 25, fournisseur SunEC, Nimbus). Les mesures externes sur Node et Go donnent un ordre de grandeur dix fois plus faible pour la vérification ES256 (voir `ANALYSE.md`). | moyenne : risque de généralisation | paragraphes et README précisent la pile |
| C3 | Note de la figure 2 : valeurs 524 et 536 µs écrites à la main ; note de la figure 4 : « +107 o » écrit à la main | faible | valeurs lues dans les CSV (`scripts/figures.py`) |
| C4 | Les anciennes figures (150 dpi, PNG seul, palette non vérifiée, saturation non séparée) ne répondaient pas aux exigences de publication | faible | 6 figures et 1 figure annexe, en SVG et PNG 300 dpi, avec une palette validée pour les daltonismes |
| C5 | `jvm/dependency-reduced-pom.xml` et un cache Python avaient failli être versionnés | sans effet sur les mesures | exclus par `.gitignore` avant le premier commit |

Aucune valeur numérique des tableaux de `RESULTS.md` ni du rapport méthodologique n'a dû être corrigée.

## 5. Biais et limites identifiés (réserves)

| # | Biais ou limite | Effet probable | Traitement |
|---|---|---|---|
| R1 | **Coût serveur propre à la pile.** La vérification ES256 coûte 536 µs avec JDK 25 / SunEC / Nimbus sur un cœur P. Les mesures publiques trouvées donnent ≈ 50 µs (Node 24 / jose, Apple M4) et ≈ 40 µs (Go). | Le coût serveur mesuré ne se généralise pas à DPoP. Matériel différent, donc pas de comparaison directe : seul l'ordre de grandeur diverge. | précisé partout ; voir `ANALYSE.md` §2 |
| R2 | **Écart micro / bout en bout non expliqué.** +1,0 ms de CPU par renouvellement de bout en bout, contre 0,52 ms pour `validate()` dans JMH. | Hypothèses non vérifiées : conteneur non épinglé, donc possiblement sur des cœurs E environ deux fois plus lents ; GC de +100 Kio alloués par renouvellement ; jetons plus longs à chiffrer et déchiffrer ; JIT. | présenté comme ouvert ; aucun chiffre n'est déduit de cette différence |
| R3 | **CPU fixe de la JVM.** Le CPU par renouvellement divise tout le CPU du conteneur (GC, JIT, JFR, Tomcat) par le nombre de renouvellements. À 10/s, cette part fixe pèse plus. | Le ratio à 10/s (+23 %) est moins fiable. La pente de la régression CPU = f(débit) sur 9 exécutions par mode donne 4,05 ms (sans) et 5,06 ms (avec), soit **+1,01 ms**, cohérent avec les paires (+0,68 à +1,13). | signalé dans la note de la figure 1 |
| R4 | **Percentiles extrêmes sur peu d'échantillons.** 1 200 échantillons par exécution à 10/s, donc p99 sur ≈ 12 valeurs ; une paire p95 est négative. | p95 et p99 à 10/s fragiles | note de la figure 3 ; le texte s'appuie sur p50 et CPU |
| R5 | **Appariement limité.** Les « paires » regroupent les deux modes d'une même répétition, exécutés dans un ordre aléatoire mais pas l'un juste après l'autre (≈ 45 min par répétition). | Contrôle les dérives lentes, pas le bruit de court terme | décrit comme tel |
| R6 | **Dérive due à la table des jetons.** Même à 100/s, la latence monte d'environ 1 ms au fil d'une exécution (balayage O(N) sous verrou). | Les valeurs absolues dépendent de la durée de l'exécution. L'écart entre modes n'est pas biaisé, car les tables grossissent au même rythme. | figure 6 ; rapport méthodologique §6 |
| R7 | **Générateur sur la même machine.** k6 consomme +0,06 cœur de plus en mode DPoP à 100/s, sur 22 processeurs logiques. | Contamination négligeable mais non nulle | documenté |
| R8 | **Navigateur de bureau.** Chromium sans interface sur des cœurs P d'un Core Ultra 7. Aucun mobile ni appareil d'entrée de gamme. Médiane quantifiée par pas de 5 µs. | Le coût client mesuré est un plancher, pas une valeur typique | précisé |
| R9 | **Anti-rejeu dans JMH.** Les entrées préremplies ne sont jamais expirées : la purge parcourt la table sans rien retirer. | Le coût réel de suppression (≈ 1 entrée par preuve en régime) n'est pas inclus : sous-estimation négligeable | documenté |
| R10 | **Occupation du store anti-rejeu reconstituée.** Elle est calculée à partir des preuves acceptées et du TTL de 120 s, pas lue dans le serveur. | Modèle exact si le code est respecté (`purgeExpiredJtis` à chaque preuve) | libellé « reconstituée » |
| R11 | **Allocations à 10/s.** Un seul GC dans la fenêtre de mesure, donc l'estimation inclut des allocations antérieures. | Valeur non exploitable | exclue des conclusions |
| R12 | **Pas de TLS, réseau virtuel local, machine unique, 3 répétitions** | Valeurs absolues non transposables ; plages min-max plutôt qu'intervalles de confiance | rapport méthodologique §7 |

## 6. Ce qui n'a pas été fait, et pourquoi

- **Aucune nouvelle campagne.** Les écarts relevés ne justifient pas de relancer 4 h 40 de mesures : aucun calcul n'est faux, et les biais R1 à R12 se documentent sans nouvelle mesure.
- **Mesures qui lèveraient les réserves principales :**
  - **R2 :** épingler les conteneurs ;
  - **R1 :** mesurer ES256 avec un autre fournisseur (BouncyCastle) ;
  - **R4 :** allonger les exécutions à 10/s.
- **Pourquoi elles sont exclues de cette mission :** soit elles modifieraient la configuration du PoC, soit elles exigeraient une nouvelle campagne.
