# Synthèse des résultats

Généré par `scripts/analyze.py` à partir des résultats bruts :

- navigateur : `results\browser\20261124T213707Z`
- JMH : `results\jvm\20261124T213730Z`
- charge : `results\load\20261125T000047Z`

## 1. Navigateur (Web Crypto, Chromium)

Chromium 159.0.8154.6, Playwright 1.65.1, 5 séries × 2000 mesures (+200 d'échauffement), résolution de performance.now() : 5 µs (origine isolée : True).

| Opération | Médiane (ms) | Moyenne | p95 | p99 | Min | Max | Écart-type | Moyenne en lot | CV médianes inter-séries (%) |
|---|---|---|---|---|---|---|---|---|---|
| Référence : await vide | 0 | 3.08e-04 | 0 | 0.005 | 0 | 0.345 | 0.004 | 3.36e-04 | 0 |
| 1. Génération paire ECDSA P-256 | 0.035 | 0.037 | 0.045 | 0.070 | 0.025 | 2.965 | 0.033 | 0.037 | 7.62e-05 |
| 2. Export JWK clé publique | 0.010 | 0.012 | 0.015 | 0.025 | 0.005 | 1.445 | 0.020 | 0.011 | 0 |
| 1+2. initializeDPopKeyPair() (PoC) | 0.050 | 0.051 | 0.065 | 0.100 | 0.035 | 3.780 | 0.046 | 0.050 | 4.563 |
| 3. Thumbprint JWK, computeJkt() (PoC) | 0.005 | 0.007 | 0.010 | 0.020 | 0 | 2.480 | 0.041 | 0.005 | 0 |
| 4. Signature ECDSA seule | 0.050 | 0.053 | 0.065 | 0.105 | 0.040 | 0.380 | 0.014 | 0.053 | 0 |
| 5. Preuve complète, buildDPopProof() (PoC) | 0.065 | 0.070 | 0.085 | 0.155 | 0.050 | 1.810 | 0.058 | 0.071 | 0 |

Taille d'une preuve : 468–468 octets (ligne d'en-tête « DPoP: … » : 476 octets).

## 2. Serveur (JMH)

JMH 1.37, Java HotSpot(TM) 64-Bit Server VM 25.0.2+10-LTS-69, 3 forks, 5×5 s d'échauffement, 10×5 s de mesure, 1 thread.

| Opération | Store | cnf.jkt | Moyenne (µs) | ± IC99,9 | p50 | p95 | p99 | Débit (ops/s) | ± IC99,9 | Fork min | Fork max | Alloc (o/op) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Parsing de la preuve (JWS + en-tête + JWK) |  |  | 4.693 | 0.016 | 4.296 | 5.600 | 8.896 | 216644.487 | 2166.175 | 215140.392 | 219158.615 | 10042.673 |
| Reconstruction de la clé publique |  |  | 1.294 | 0.012 | 1.000 | 1.800 | 2.500 | 1074095.425 | 24269.585 | 1043965.638 | 1095614.222 | 1191.835 |
| Vérification ES256 |  |  | 536.231 | 0.989 | 489.472 | 883.712 | 1132.544 | 1980.591 | 17.545 | 1972.115 | 1990.302 | 31795.671 |
| Thumbprint JWK (RFC 7638) |  |  | 1.198 | 0.009 | 1.100 | 1.400 | 2.200 | 917494.081 | 23352.333 | 897317.779 | 939836.763 | 1800.002 |
| Extraction des claims |  |  | 2.177 | 0.014 | 2.000 | 2.600 | 3.800 | 485098.664 | 6966.416 | 482871.877 | 488975.254 | 5064.003 |
| Contrôles htm / htu / iat |  |  | 0.907 | 0.005 | 0.800 | 1.000 | 1.700 | 1176650.921 | 27339.498 | 1146294.742 | 1205813.889 | 759.067 |
| Anti-rejeu (purge + insertion) | 0 |  | 0.151 | 0.005 | 0.100 | 0.200 | 0.300 | 14329003.110 | 171973.800 | 14181627.895 | 14480669.044 | 56.000 |
| Anti-rejeu (purge + insertion) | 1200 |  | 4.016 | 0.009 | 3.400 | 6.496 | 8.496 | 280987.705 | 4478.182 | 276248.050 | 289552.239 | 56.005 |
| Anti-rejeu (purge + insertion) | 12000 |  | 96.068 | 0.077 | 89.856 | 127.360 | 210.432 | 8609.594 | 950.615 | 7674.644 | 10151.238 | 56.166 |
| Anti-rejeu (purge + insertion) | 60000 |  | 703.086 | 0.906 | 672.768 | 909.312 | 1271.972 | 1468.483 | 48.930 | 1445.261 | 1494.443 | 58.046 |
| Validation complète (DpopProofValidator) | 0 |  | 523.841 | 0.575 | 506.880 | 645.120 | 956.416 | 1899.409 | 22.730 | 1871.543 | 1917.953 | 86869.799 |
| Validation complète (DpopProofValidator) | 1200 |  | 527.343 | 0.601 | 508.928 | 655.360 | 985.088 | 1886.208 | 18.480 | 1859.485 | 1901.211 | 87037.180 |
| Validation complète (DpopProofValidator) | 12000 |  | 640.782 | 0.800 | 615.424 | 812.032 | 1177.600 | 1536.908 | 54.896 | 1475.483 | 1570.376 | 95667.992 |
| Validation complète (DpopProofValidator) | 60000 |  | 1322.371 | 2.715 | 1253.376 | 1800.192 | 2473.984 | 787.448 | 14.611 | 775.294 | 801.695 | 139202.840 |
| Émission refresh token (JWS+JWE) |  | false | 214.147 | 0.266 | 197.120 | 313.344 | 469.504 | 4697.820 | 63.393 | 4632.766 | 4734.300 | 58392.100 |
| Émission refresh token (JWS+JWE) |  | true | 217.095 | 0.277 | 200.192 | 317.952 | 483.328 | 4677.780 | 47.039 | 4653.526 | 4705.418 | 59963.206 |
| Vérification refresh token (JWE+JWS) |  | false | 1410.781 | 3.034 | 1331.200 | 2007.040 | 2621.440 | 734.714 | 5.649 | 732.663 | 737.702 | 103693.127 |
| Vérification refresh token (JWE+JWS) |  | true | 1442.052 | 3.391 | 1353.728 | 2113.536 | 2793.472 | 731.874 | 5.863 | 730.368 | 734.441 | 105649.425 |

## 3. Bout en bout (k6)

### Par configuration (médiane des répétitions valides [min–max])

| Débit | Mode | Valides | Atteint (req/s) | p50 HTTP (ms) | p95 | p99 | Total p50 (ms) | CPU backend (cœurs) | CPU ms/refresh | Alloc KiB/refresh | Pauses GC (ms) | Store anti-rejeu | k6 (cœurs) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | bearer | 3/3 | 10.0 [10.0–10.0] | 3.74 [3.68–3.80] | 5.11 [4.63–6.65] | 6.41 [5.52–7.37] | 4 [4–4] | 0.05 [0.05–0.05] | 5.13 [4.94–5.42] | 522 [521–522] | 19 [16–21] | 0 [0–0] | 0.02 [0.02–0.02] |
| 10 | dpop | 3/3 | 10.0 [10.0–10.0] | 4.44 [4.29–4.57] | 6.05 [5.95–6.40] | 7.73 [6.98–8.15] | 5 [5–5] | 0.06 [0.06–0.06] | 6.08 [6.07–6.38] | 521 [521–521] | 16 [16–18] | 1200 [1200–1200] | 0.02 [0.02–0.02] |
| 50 | bearer | 3/3 | 50.0 [50.0–50.0] | 3.63 [3.55–3.64] | 4.86 [4.62–4.99] | 5.98 [5.65–6.55] | 4 [4–4] | 0.20 [0.20–0.21] | 4.05 [3.92–4.11] | 207 [207–207] | 28 [28–30] | 0 [0–0] | 0.06 [0.05–0.06] |
| 50 | dpop | 3/3 | 50.0 [50.0–50.0] | 4.39 [4.33–4.44] | 5.50 [5.46–5.99] | 7.37 [7.26–8.86] | 5 [5–5] | 0.24 [0.23–0.24] | 4.73 [4.60–4.85] | 309 [309–309] | 42 [42–47] | 6001 [6000–6001] | 0.08 [0.08–0.09] |
| 100 | bearer | 3/3 | 100.0 [100.0–100.0] | 4.06 [3.92–4.21] | 5.45 [5.19–5.70] | 7.25 [6.39–7.31] | 4 [4–4] | 0.42 [0.40–0.43] | 4.17 [3.99–4.29] | 205 [205–205] | 68 [61–71] | 0 [0–0] | 0.09 [0.09–0.10] |
| 100 | dpop | 3/3 | 100.0 [100.0–100.0] | 5.01 [4.97–5.09] | 6.63 [6.46–6.74] | 8.45 [8.41–9.13] | 6 [5–6] | 0.51 [0.51–0.52] | 5.15 [5.10–5.19] | 306 [306–306] | 100 [96–102] | 12000 [11999–12000] | 0.15 [0.14–0.16] |
| 250 | bearer | 0/3 | 234.9 [234.8–235.1] | 6.88 [6.22–42.45] | 2609.08 [2567.84–2617.20] | 2813.99 [2794.50–2893.80] | 7 [6–43] | 1.37 [1.36–1.45] | 5.83 [5.81–6.16] | 262 [241–263] | 121 [109–129] | 0 [0–0] | 0.24 [0.23–0.25] |
| 250 | dpop | 0/3 | 216.9 [216.5–225.7] | 494.88 [41.17–1177.63] | 3073.85 [2901.43–3301.26] | 3523.70 [3195.77–3605.14] | 496 [42–1178] | 1.84 [1.73–1.91] | 8.48 [7.69–8.81] | 308 [298–310] | 126 [121–147] | 28986 [28777–29355] | 0.37 [0.35–0.42] |
| 500 | bearer | 0/3 | 197.5 [195.0–200.2] | 5102.19 [4996.32–5170.17] | 6432.84 [6242.10–6627.21] | 6722.06 [6690.89–7082.87] | 5103 [4996–5170] | 1.36 [1.35–1.37] | 6.86 [6.81–7.03] | 233 [232–237] | 90 [88–95] | 0 [0–0] | 0.21 [0.21–0.22] |
| 500 | dpop | 0/3 | 175.0 [174.3–187.1] | 5973.60 [5626.63–5988.04] | 7157.47 [6742.67–7274.30] | 7700.96 [6846.68–7759.48] | 5974 [5627–5989] | 1.66 [1.63–1.66] | 9.35 [8.86–9.48] | 296 [277–297] | 104 [103–104] | 29863 [29368–31093] | 0.31 [0.29–0.32] |

### Surcoût DPoP apparié (même répétition, même débit ; médiane des paires valides [min–max])

| Débit | Paires | Δ p50 HTTP (ms) | Δ p50 % | Δ p95 % | Δ p99 % | Δ CPU ms/refresh | Δ CPU % | Δ octets envoyés/req | Δ octets reçus/req |
|---|---|---|---|---|---|---|---|---|---|
| 10 | 3 | 0.76 [0.54–0.77] | 20.3 [14.5–20.6] | 25.1 [-10.5–30.6] | 26.5 [4.9–27.2] | 1.13 [0.66–1.24] | 22.9 [12.1–24.2] | 687 [687–687] | 426 [426–426] |
| 50 | 3 | 0.78 [0.76–0.80] | 21.9 [21.0–22.0] | 18.3 [10.3–23.4] | 30.3 [10.7–48.2] | 0.68 [0.62–0.80] | 17.4 [15.0–19.8] | 688 [688–688] | 427 [427–427] |
| 100 | 3 | 0.91 [0.87–1.09] | 22.3 [20.8–27.7] | 18.6 [18.2–27.8] | 16.5 [15.0–42.7] | 0.93 [0.90–1.16] | 22.3 [21.1–29.1] | 688 [688–688] | 428 [428–428] |
| 250 | 0 | – | – | – | – | – | – | – | – |
| 500 | 0 | – | – | – | – | – | – | – | – |

### Exécutions non valides (exclues des comparaisons)

- rep 1, 250 req/s, bearer : débit atteint < 95 % du débit demandé (atteint 234.8 req/s, erreurs 0.00 %, dropped_iterations 1456, k6 0.23 cœurs)
- rep 1, 250 req/s, dpop : débit atteint < 95 % du débit demandé (atteint 225.7 req/s, erreurs 0.00 %, dropped_iterations 2573, k6 0.35 cœurs)
- rep 1, 500 req/s, bearer : débit atteint < 95 % du débit demandé (atteint 200.2 req/s, erreurs 0.00 %, dropped_iterations 36010, k6 0.21 cœurs)
- rep 1, 500 req/s, dpop : débit atteint < 95 % du débit demandé (atteint 187.1 req/s, erreurs 0.00 %, dropped_iterations 37766, k6 0.32 cœurs)
- rep 2, 250 req/s, bearer : débit atteint < 95 % du débit demandé (atteint 234.9 req/s, erreurs 0.00 %, dropped_iterations 1396, k6 0.24 cœurs)
- rep 2, 250 req/s, dpop : débit atteint < 95 % du débit demandé (atteint 216.5 req/s, erreurs 0.00 %, dropped_iterations 3670, k6 0.42 cœurs)
- rep 2, 500 req/s, bearer : débit atteint < 95 % du débit demandé (atteint 197.5 req/s, erreurs 0.00 %, dropped_iterations 36317, k6 0.21 cœurs)
- rep 2, 500 req/s, dpop : débit atteint < 95 % du débit demandé (atteint 174.3 req/s, erreurs 0.00 %, dropped_iterations 39220, k6 0.29 cœurs)
- rep 3, 250 req/s, bearer : débit atteint < 95 % du débit demandé (atteint 235.1 req/s, erreurs 0.00 %, dropped_iterations 1380, k6 0.25 cœurs)
- rep 3, 250 req/s, dpop : débit atteint < 95 % du débit demandé (atteint 216.9 req/s, erreurs 0.00 %, dropped_iterations 3666, k6 0.37 cœurs)
- rep 3, 500 req/s, bearer : débit atteint < 95 % du débit demandé (atteint 195.0 req/s, erreurs 0.00 %, dropped_iterations 36758, k6 0.22 cœurs)
- rep 3, 500 req/s, dpop : débit atteint < 95 % du débit demandé (atteint 175.0 req/s, erreurs 0.00 %, dropped_iterations 39208, k6 0.31 cœurs)
