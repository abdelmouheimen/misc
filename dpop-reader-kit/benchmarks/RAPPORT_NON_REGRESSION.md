# Rapport de non-régression — benchmarks DPoP

| | |
|---|---|
| Révision testée | `8e8ee37` (`origin/main`, révision publiée citée par l'article) |
| Branche de travail | `bench/dpop-cost`, worktree local `C:\Abdel\sources\misc-bench` |
| Date | 24 novembre 2026 |

**Résultat : le comportement du PoC est inchangé.** Aucun fichier de production n'est modifié. Les 28 tests passent, et les 11 scénarios d'attaque donnent les résultats documentés.

## 1. Périmètre des modifications

Seul le dossier `dpop-reader-kit/benchmarks/` est ajouté. `git status` sur `backend/`, `frontend/`, `attacks/`, `tools/` et `docker-compose.yml` est vide, avant comme après la campagne.

| Exigence | Vérification |
|---|---|
| Comportement normal inchangé | Aucune ligne de production modifiée ; mêmes tests et scénarios qu'avant |
| DPoP jamais désactivé par défaut | `application.yml` (`dpop.required: true`) et `docker-compose.yml` (`DPOP_REQUIRED: "true"`) inchangés |
| Baseline isolée | `DPOP_REQUIRED=false` n'apparaît que dans `benchmarks/load/docker-compose.bench.yml`, sur un réseau `internal: true`, sans port publié |
| Aucun mécanisme de contournement ajouté | La baseline réutilise le réglage `dpop.required`, déjà présent et documenté dans le PoC |
| Sessions liées protégées en mode baseline | Scénario 11 : un jeton lié reste refusé sans preuve ou avec une autre clé quand `dpop.required=false` |
| Services de benchmark non exposés | Backend et k6 sur le réseau Docker interne, aucun `ports:` dans le Compose de benchmark |
| Ni clé privée ni jeton valide publiés | Voir section 4 |
| Données de test uniquement | Utilisateurs `vu-<n>`, clés générées en mémoire pour chaque exécution |

## 2. Tests unitaires et d'intégration du backend

Commande : `mvn test` dans `backend/`, avec JDK 25.0.2.

| Classe | Tests | Échecs | Erreurs |
|---|---:|---:|---:|
| `RefreshFlowTest` | 4 | 0 | 0 |
| `RefreshRotationConcurrencyTest` | 24 | 0 | 0 |
| **Total** | **28** | **0** | **0** |

Résultat identique avant l'ajout des benchmarks et après la campagne (section 5).

## 3. Scénarios d'attaque

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
| 10b Même preuve sur deux instances | 2 instances, ports 8099 et 8101 | Bloqué sur la même instance, accepté sur la voisine, **comme documenté** (anti-rejeu non partagé) |
| 11 Liaison indépendante du drapeau | `dpop.required=false` | Sans preuve : bloqué ; autre clé : bloqué ; clé légitime : accepté, `cnf.jkt` propagé |

Les scénarios 07, 08b, 09 et 10b décrivent des limites assumées du PoC. Leur résultat est celui annoncé par les scripts eux-mêmes.

## 4. Absence de secrets dans les résultats

- **Clés :** générées en mémoire (Web Crypto, k6, JMH) et jamais écrites sur disque. Les clés PEM des scénarios d'attaque sont restées dans un répertoire temporaire hors dépôt.
- **Contenu des fichiers :** k6 n'exporte que des métriques, sans corps de réponse. `run.json` ne contient que des compteurs de la table des jetons.
- **Fichiers non versionnés :** les fichiers JFR et les CSV k6 complets sont exclus par `.gitignore`.
- **Contrôle final :** recherche dans tout `results/` versionné des motifs `eyJ` (JOSE en Base64URL), `"d":`, `PRIVATE KEY` et `BEGIN` (section 5).

## 5. Contrôle après la campagne

Contrôles effectués le 25 novembre 2026, après la campagne complète (navigateur, JMH, 30 exécutions k6) :

| Contrôle | Résultat |
|---|---|
| `mvn test` dans `backend/` | 28 tests, 0 échec, 0 erreur : identique à l'état initial |
| `git diff 8e8ee37` hors `benchmarks/` | vide |
| Valeurs par défaut (`dpop.required: true`, `DPOP_REQUIRED: "true"`) | inchangées |
| Conteneurs et réseau de benchmark | arrêtés et supprimés ; aucun port publié pendant la campagne |
| Recherche de `eyJ`, `"d":`, `PRIVATE KEY` et `BEGIN` dans tous les fichiers versionnés, y compris les archives `.gz` décompressées | aucune occurrence dans les résultats. Seule correspondance : la section 4 de ce rapport, qui cite les motifs |

Le détail figure aussi à la section 8 du rapport méthodologique.
