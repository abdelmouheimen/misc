# Avertissement de sécurité

Ce dépôt est un **laboratoire pédagogique** accompagnant un article de la revue MISC.
Il est conçu pour être lu, exécuté et attaqué localement — **pas** pour servir de base
à un déploiement.

Volontairement, pour rester lisible :

- **aucune authentification primaire** : n'importe quel `uid` est accepté, sans mot de passe ;
- **les jetons sont renvoyés en clair** dans le corps des réponses pour faciliter les
  démonstrations (en production, ils ne vivent que dans des cookies `HttpOnly`) ;
- **un endpoint `/debug/tokens`** expose la table des jetons ;
- **un endpoint `/debug/introspect`** déchiffre n'importe quel jeton et renvoie ses claims :
  c'est un oracle de déchiffrement qui annule la confidentialité apportée par le chiffrement
  des JWT (JWE). Il sert uniquement à rendre le lab observable ;
- **un endpoint `/debug/proof`** fait signer des preuves DPoP par le serveur (pour les fichiers
  `.http`), ce qui contredit le principe même de DPoP — c'est un raccourci pédagogique ;
- **le refresh token est aussi accepté dans un en-tête `X-Refresh-Token`**, uniquement pour
  rendre les fichiers `.http` reproductibles ; en production, il ne transite que par le
  cookie `HttpOnly` ;
- **le store est en mémoire**, non partagé entre instances ;
- **les cookies ne sont pas `Secure`** (le lab tourne en HTTP sur `localhost`).

Le scénario `attacks/07_limits_what_still_works.sh` démontre **délibérément** une attaque
qui réussit : DPoP ne protège pas d'un code exécuté dans la page légitime. C'est le sujet,
pas un défaut.

N'exposez jamais ce service sur un réseau accessible et ne réutilisez pas ce code tel quel.
