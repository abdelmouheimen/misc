# Paragraphe proposé pour la section 2.2 de l'article

Ce texte fait 482 caractères hors espaces.

> Nous avons mesuré ce coût sur le PoC (Chromium 159, JDK 25, Nimbus 10.9, une seule machine, sans TLS). Dans le navigateur, une preuve se construit en 65 µs (médiane) et alourdit la requête d'environ 690 octets. Côté serveur, sa validation prend environ 0,5 ms sur un cœur, l'essentiel étant la vérification de la signature ES256. De bout en bout, entre 10 et 100 renouvellements par seconde, DPoP ajoute 0,8 à 0,9 ms de latence médiane (+20 à 22 %) et 0,7 à 1,1 ms de CPU par renouvellement, sans aucune erreur. Au-delà, c'est le stockage des jetons du PoC qui sature, avec ou sans DPoP.

## Source de chaque chiffre

| Affirmation | Valeur mesurée | Source |
|---|---|---|
| 65 µs par preuve | médiane de `buildDPopProof()` : 0,065 ms (5 × 2 000 mesures) | `RAPPORT_METHODOLOGIQUE.md` §5.1 |
| ≈ 690 octets par requête | +687 à +688 octets envoyés par requête, en-tête `DPoP` (466 octets) et cookies plus longs (`cnf.jkt`) compris | §5.3 |
| ≈ 0,5 ms de validation | `DpopProofValidator.validate()` : 524 µs avec un store vide, 641 µs à 12 000 entrées ; vérification ES256 seule : 536 µs | §5.2 |
| +0,8 à 0,9 ms, +20 à 22 % | Δ p50 HTTP apparié : +0,76, +0,78 et +0,91 ms ; +20, +22 et +22 % (10, 50, 100 req/s) | §5.3 |
| 0,7 à 1,1 ms de CPU | Δ CPU backend par renouvellement : +1,13, +0,68 et +0,93 ms | §5.3 |
| sans aucune erreur | 18/18 exécutions valides de 10 à 100 req/s, 0 % d'erreurs | §5.3 |
| saturation au-delà, avec ou sans DPoP | 0/12 exécutions valides à 250 et 500 req/s dans les deux modes ; contention sur le verrou de `TokenStore` (JFR) | §6 |

## Précisions écartées du paragraphe faute de place

- **Valeurs arrondies :** les « 0,8 à 0,9 ms » arrondissent la plage +0,76 à +0,91 ms.
- **Débit maximal :** aucun n'est publié. La saturation vient de la table des jetons du PoC, qui n'est jamais purgée et qui est balayée sous un verrou global. Elle ne mesure pas DPoP.
- **Purge anti-rejeu :** dans le PoC, elle est en O(N). La validation monte à 1,3 ms avec 60 000 entrées en mémoire.
- **Latences absolues :** ce sont des allers-retours locaux, sans TLS. Elles ne disent rien d'un déploiement réel, avec TLS et un vrai réseau, qui n'a pas été mesuré.
