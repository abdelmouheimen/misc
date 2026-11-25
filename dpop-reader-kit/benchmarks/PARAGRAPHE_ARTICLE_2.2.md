# Propositions de texte pour la section 2.2 de l'article

Les trois versions reposent uniquement sur les mesures valides, de 10 à 100 renouvellements par seconde. Les nombres de caractères sont comptés hors espaces (`len(''.join(texte.split()))`).

## Version courte (238 caractères hors espaces, maximum 300)

> Sur notre PoC (pile Java, une machine, sans TLS), une preuve DPoP coûte 65 µs dans Chromium et sa validation 0,5 ms de CPU serveur. Entre 10 et 100 renouvellements par seconde, DPoP ajoute 0,8 à 0,9 ms de latence médiane (+20 à 22 %), environ 1 ms de CPU et un en-tête de 474 octets par requête.

## Version intermédiaire (417 caractères hors espaces, maximum 500)

> Nous avons mesuré ce coût sur le PoC (Chromium 159, JDK 25, Nimbus 10.9, une machine, sans TLS). Une preuve se construit en 65 µs dans le navigateur et ajoute un en-tête de 474 octets. Sa validation coûte 0,5 ms de CPU serveur, presque entièrement dans la vérification ES256 : ce chiffre dépend fortement de la pile Java. Entre 10 et 100 renouvellements par seconde, la latence médiane augmente de 0,8 à 0,9 ms (+20 à 22 %) et le CPU de 0,7 à 1,1 ms par renouvellement. Au-delà, le PoC sature, avec ou sans DPoP.

## Version détaillée (728 caractères hors espaces, maximum 800)

> Nous avons mesuré ce coût sur le PoC (Chromium 159, JDK 25, Nimbus 10.9, une seule machine, sans TLS). Côté client, une preuve se construit en 65 µs (médiane) et ajoute un en-tête de 474 octets à chaque requête protégée. Côté serveur, sa validation coûte 0,5 ms de CPU, presque entièrement dans la vérification ES256 : ce chiffre dépend de la pile Java, car des mesures publiques sur d'autres plateformes situent cette vérification à quelques dizaines de microsecondes. Entre 10 et 100 renouvellements par seconde, la latence médiane augmente de 0,8 à 0,9 ms (+20 à 22 %) et le CPU de 0,7 à 1,1 ms par renouvellement. Le reste dépend de l'implémentation : nos jetons portant cnf.jkt ajoutent 215 octets par requête, et la purge linéaire du store anti-rejeu porte la validation à 1,3 ms avec 60 000 jti. Au-delà de 100 renouvellements par seconde, le PoC sature, avec ou sans DPoP.

## Phrase sur le compromis de Google (225 caractères hors espaces)

> Google a tranché ce compromis à son échelle : son service OAuth lie par DPoP les seuls refresh tokens et laisse les jetons d'accès, généralement valables une heure au plus, en bearer, afin d'épargner à ses API à fort trafic une vérification asymétrique à chaque requête.

**Référence :** S. Akulich et B. Daugherty, « Announcing DPoP Support for Google OAuth: Securing Refresh Tokens », *Google Developers Blog*, 15 octobre 2026. <https://developers.googleblog.com/announcing-dpop-support-for-google-oauth-securing-refresh-tokens/>

**Ce que dit la source :**
- « Google's OAuth Platform supports DPoP binding specifically for refresh tokens. »
- « short-lived access tokens remain standard Bearer tokens, ensuring that high-QPS resource APIs do not incur the per-request asymmetric cryptographic verification overhead of DPoP »
- Les jetons d'accès sont « typically valid for 1 hour or less ».

**Ce qui relève de notre interprétation :** le mot « compromis » reprend « a pragmatic engineering tradeoff » de l'annonce. L'annonce ne donne aucune mesure de performance.

## Source de chaque chiffre

| Affirmation | Valeur mesurée | Source |
|---|---|---|
| 65 µs par preuve | médiane de `buildDPopProof()`, 5 × 2 000 mesures, Chromium 159 | `results/summary/browser_summary.csv` ; figure 2 |
| en-tête de 474 octets | preuve de 466 octets + `DPoP: ` + fin de ligne | `load_runs.csv` (`dpop_header_bytes_median`) ; figure 4 |
| 0,5 ms de validation, presque entièrement dans ES256 | `validate()` : 524 µs ; vérification ES256 seule : 536 µs (benchmarks distincts) | `jvm_summary.csv` ; figures 2 et S1 |
| quelques dizaines de µs sur d'autres plateformes | 50,5 µs (Node 24, jose, Apple M4) ; ≈ 40 µs (Go) : mesures externes, matériel différent | `ANALYSE.md` §2 |
| +0,8 à 0,9 ms, +20 à 22 % | Δ p50 apparié : +0,76, +0,78 et +0,91 ms ; +20, +22 et +22 % | `load_overhead.csv` ; figure 3 |
| CPU +0,7 à 1,1 ms (« environ 1 ms ») | Δ CPU apparié : +1,13, +0,68 et +0,93 ms ; pente de régression : +1,01 ms | `load_overhead.csv`, `AUDIT_SCIENTIFIQUE.md` R3 ; figure 1 |
| 215 octets dus aux jetons | écart médian de l'en-tête `Cookie` (2 jetons portant `cnf.jkt`) | `load_runs.csv` ; figure 4 |
| 1,3 ms avec 60 000 jti | `validate()` : 1 322 µs | `jvm_summary.csv` ; figure 5 |
| saturation avec ou sans DPoP | 0/12 exécutions valides à 250 et 500/s ; contention sur `TokenStore.refresh` (JFR) | `RESULTS.md`, `jfr_hotspots.txt` ; figure 6 |

`scripts/verify_numbers.py` recalcule ces valeurs depuis les données brutes.

## Formulations à éviter

- **« DPoP coûte 0,5 ms » sans préciser la pile :** le coût serveur dépend de la bibliothèque cryptographique.
- **« DPoP ajoute 690 octets » :** 215 de ces octets viennent du format des jetons du PoC.
- **Tout débit maximal, avec ou sans DPoP :** les mesures au-delà de 100/s ne sont pas valides.
- **Toute transposition à la production ou à l'échelle de Google :** une seule machine, pas de TLS, une seule instance.
