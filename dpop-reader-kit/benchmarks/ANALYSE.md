# Mise en perspective, choix de Google et conclusions

Ce document situe nos mesures par rapport aux publications externes, analyse le choix d'architecture annoncé par Google, puis répond aux questions de fond. Les résultats eux-mêmes sont dans le [README](README.md), la méthode dans le [rapport méthodologique](RAPPORT_METHODOLOGIQUE.md) et les réserves dans l'[audit](AUDIT_SCIENTIFIQUE.md).

Toutes les sources ont été consultées le 25 novembre 2026. Les citations sont reprises mot pour mot.

## 1. Références normatives

| Source | Ce qu'elle établit, et qui sert à interpréter nos mesures |
|---|---|
| **RFC 9449**, *OAuth 2.0 Demonstrating Proof of Possession (DPoP)*. D. Fett, B. Campbell, J. Bradley, T. Lodderstedt, M. Jones, D. Waite. IETF, Standards Track, septembre 2023. <https://www.rfc-editor.org/rfc/rfc9449> | **Une preuve par requête :** « Each HTTP request requires a unique DPoP proof » (§4). Le coût client et la taille de l'en-tête sont donc inhérents au mécanisme. |
| | **Liaison du refresh token :** pour un client public, il « MUST be bound to the respective public key » (§5), et « The implementation details of the binding of the refresh token are at the discretion of the authorization server » (§5). Placer `cnf.jkt` dans un refresh token auto-porteur, comme le fait le PoC, est un **choix d'implémentation**. Un serveur qui conserve la liaison de son côté n'ajoute aucun octet au refresh token. |
| | **Jetons d'accès JWT :** `jkt` est l'empreinte SHA-256 RFC 7638 de la clé publique, encodée en base64url (§6.1). |
| | **Anti-rejeu :** les serveurs « MUST only accept DPoP proofs for a limited time after their creation » ; ils « can store the jti value of each DPoP proof » (§11.1). La RFC reconnaît que ce contrôle « may not always be feasible in practice, e.g., when multiple servers behind a single endpoint have no shared state ». Le store anti-rejeu du PoC, sa purge en O(N) et son absence de partage sont donc des choix d'implémentation, et pas des exigences de DPoP. |
| | **Nonce serveur :** « Server-provided nonces are an effective means for further reducing the chances for successful DPoP proof replay » (§11.1). Il coûte « an extra protocol round trip » à chaque renouvellement de nonce (§8.2). Nous ne l'avons pas mesuré : le PoC n'implémente pas de nonce. |
| **FAPI 2.0 Security Profile**, version finale. D. Fett, D. Tonge, J. Heenan. OpenID Foundation, publiée le 22 février 2025 (approuvée le 19 février 2025 selon la page du groupe de travail). <https://openid.net/specs/fapi-security-profile-2_0-final.html> | **Jetons d'accès :** le serveur d'autorisation « shall only issue sender-constrained access tokens » au moyen de mTLS (RFC 8705) ou de DPoP (RFC 9449) (§5.3.2.1). Le serveur de ressources « shall support and verify sender-constrained access tokens » (§5.3.4). |
| | **Algorithmes :** PS256, ES256 ou EdDSA (§5.4.1). |
| | **Nonce :** côté client, il faut le supporter ; côté serveur d'autorisation, il « may » être utilisé (§5.3.2.1 et §5.3.3.1). |
| | **Périmètre :** FAPI 2.0 ne vise que les clients confidentiels (§5.3.2.1) et interdit en principe la rotation des refresh tokens (§5.3.2.1, note 1). Le PoC, client public avec rotation, sort donc du périmètre de FAPI 2.0 : notre comparaison porte sur le coût du mécanisme DPoP, pas sur la conformité FAPI. |

## 2. Mesures publiques du coût de DPoP

**Recherche menée :** moteurs de recherche, GitHub, arXiv, documentation des éditeurs.

**Constat :** nous n'avons trouvé **aucune publication scientifique ni aucun benchmark contrôlé** comparant de bout en bout un même service avec et sans DPoP. Les mesures publiques disponibles sont des microbenchmarks ou des essais ponctuels, résumés ci-dessous.

| Source | Environnement | Méthode | Résultat publié | Différences avec notre expérimentation et limites de la comparaison |
|---|---|---|---|---|
| H. Tanaka (tanahiro2010), *I Tested Google's DPoP Implementation…*, DEV Community, octobre 2026 (essais datés du 19 octobre 2026). <https://dev.to/tanahiro2010/i-tested-googles-dpop-implementation-the-documentation-told-a-different-story-38i6> | Apple M4 (arm64), Node.js v24.22.0, jose 6.2.12 | « a loop of 20,000 operations ». Échauffement et statistique non précisés. Article déclaré « AI-assisted ». | ES256 : **26,2 µs** par signature, **50,5 µs** par vérification | Autre processeur, autre moteur et autre bibliothèque. On ne sait pas s'il s'agit d'une moyenne ou d'une médiane, et la création du vérificateur n'est pas décrite. **Seul l'ordre de grandeur se compare :** la vérification ES256 y est environ dix fois moins chère que nos 536 µs (JDK 25, SunEC, Nimbus). Ce n'est pas une comparaison de performances. |
| stephnangue, PR #885 *feat(dpop): verify and sign proof-of-possession proofs*, dépôt warden, fusionnée le 8 novembre 2026 (tête `e2f7f60a`, fusion `c0a31a0f`). <https://github.com/stephnangue/warden/pull/885> | Go ; matériel et versions non précisés | Non précisée | « about 40µs and 38 allocations per verify, almost all of it ES256 » | Sans méthode ni matériel, ce chiffre est seulement indicatif. Il confirme deux points : la vérification ES256 domine le coût de la validation, comme dans notre JMH, et son ordre de grandeur hors Java est de quelques dizaines de µs. |
| Turnkey (tkhq), *mcp-dpop-fixtures*, README (aperçu construit depuis `4393c9b`). <https://github.com/tkhq/mcp-dpop-fixtures> | Bun ≥ 1.4, jose 6.1.3, un seul processus sur une machine | 25 appels par variante après 1 appel d'échauffement, percentiles « nearest-rank » | Appel MCP avec DPoP : p50 **1,1 ms**, p95 1,8 ms (WebCrypto, clé non extractible) | Aucune référence sans DPoP : le surcoût de DPoP ne peut pas en être déduit. Échantillon très petit (N = 25). Protocole différent (MCP). |

**Source examinée mais non retenue :**
- **Laquelle :** AuthPlane, *dpop-and-proof-of-possession.md*, sans date ni auteur.
- **Ce qu'elle affirme :** « ~1ms of cryptographic overhead per request » et des preuves d'environ 500 octets.
- **Pourquoi elle est écartée :** aucune méthode, aucun environnement, aucune donnée.

**Ce que ces sources apportent :**
- **Coût dominant :** comme dans notre JMH, c'est la vérification ES256.
- **Ordre de grandeur hors Java :** quelques dizaines de microsecondes par vérification, sur d'autres plateformes.
- **Conséquence :** nos 0,5 ms côté serveur sont un résultat **propre à la pile Java du PoC**, pas à DPoP. Les causes possibles (fournisseur SunEC, Nimbus, construction du vérificateur à chaque appel, matériel) n'ont pas été départagées.

## 3. Le choix d'architecture de Google

**Source :** S. Akulich (Product Manager) et B. Daugherty (Product Solutions Engineer), *Announcing DPoP Support for Google OAuth: Securing Refresh Tokens*, Google Developers Blog, 15 octobre 2026. <https://developers.googleblog.com/announcing-dpop-support-for-google-oauth-securing-refresh-tokens/>

Guide d'adoption cité par l'annonce : <https://developers.google.com/identity/protocols/oauth2/resources/dpop-adoption>. Seule l'annonce a été lue.

### 3.1 Ce que Google publie

| Point | Formulation officielle |
|---|---|
| Périmètre | « Google's OAuth Platform supports DPoP binding specifically for refresh tokens. » |
| Raison de sécurité | « by sender-constraining the long-lived refresh token, we neutralize the primary vector for persistent offline impersonation » ; un refresh token volé : « If exfiltrated, it is useless to an attacker. » |
| Jetons d'accès | « short-lived access tokens remain standard Bearer tokens », « typically valid for 1 hour or less » |
| Raison de performance | « Verifying asymmetric cryptographic signatures (such as ES256) requires significant CPU cycles. » ; sur les API à fort trafic (Gmail, Calendar, Drive), vérifier une preuve à chaque appel rendrait « the compute overhead at the API Gateway layer » « massive, introducing measurable latency and scaling costs » ; d'où « the vast majority of API requests incur zero cryptographic verification overhead » |
| Nature du compromis | « This design choice reflects a pragmatic engineering tradeoff » |
| Architectures visées | Backend-for-Frontend, Token-Mediating Backend, client OAuth dans le navigateur |
| Chiffres | Aucun : ni mesure, ni latence, ni pourcentage |
| Non précisé | caractère obligatoire, calendrier, stockage des clés, nonce `DPoP-Nonce`, traitement des `jti` |

### 3.2 Notre lecture (interprétation, non publiée par Google)

- **Proportionnalité du compromis :** le coût de DPoP est proportionnel au nombre de requêtes protégées. Un refresh token sert une fois par heure environ, alors qu'un jeton d'accès peut servir des milliers de fois. Protéger seulement le renouvellement concentre la vérification sur une fraction infime du trafic.
  - Nos mesures montrent le même mécanisme à petite échelle : le surcoût CPU (≈ +1 ms par renouvellement avec la pile du PoC) se paie par requête protégée.
  - Elles ne disent rien de l'échelle de Google, ni de sa pile cryptographique, ni de ses passerelles.
- **Risque résiduel :** un jeton d'accès volé reste utilisable comme un jeton bearer jusqu'à son expiration, jusqu'à une heure selon Google. DPoP sur le refresh token empêche l'attaquant de prolonger l'accès au-delà, mais ne protège pas la fenêtre de validité du jeton d'accès. Google ne présente pas d'autre mesure d'atténuation que la courte durée de vie.
- **Sessions « riding » :** comme dans le PoC (scénario 07), un code malveillant qui s'exécute chez le client peut utiliser la clé sans l'extraire. DPoP ne couvre pas ce cas, ni chez Google ni dans le PoC.
- **Différences avec le PoC :**

| | Google (annonce) | PoC dpop-reader-kit |
|---|---|---|
| Requêtes qui exigent une preuve | renouvellement du refresh token | connexion (`/login/connection`, pour lier la session) et renouvellement (`/login/refresh`). Le PoC n'expose aucune API de ressource qui vérifie une preuve : c'est le même choix de fait. |
| Jetons d'accès | bearer, ≤ 1 h | portent `cnf.jkt` mais ne sont vérifiés par aucune ressource ; durée de vie de 300 s |
| Liaison du refresh token | non décrite | `cnf.jkt` dans le jeton (JWS imbriqué dans un JWE) **et** enregistrement côté serveur |
| Anti-rejeu | non décrit | store `jti` en mémoire, TTL de 120 s, purge en O(N), non partagé entre instances |
| Échelle, passerelles, matériel | production mondiale | une instance, une machine, sans TLS |

Notre benchmark ne reproduit pas les performances de Google et ne permet pas d'évaluer son compromis à son échelle. Il montre seulement que, sur une pile Java courante, le coût d'une preuve se mesure en fractions de milliseconde de CPU par requête protégée.

## 4. Conclusions

| Question | Réponse fondée sur les mesures |
|---|---|
| **Surcoût cryptographique démontré ?** | **Oui, et il est mesuré.** Client : 65 µs (médiane) par preuve dans Chromium sur un processeur de bureau. Serveur : 524 µs (moyenne) par validation avec la pile Java du PoC, dominés par la vérification ES256. |
| **Surcoût réseau démontré ?** | **Oui.** +474 octets par requête pour l'en-tête DPoP (preuve ES256 de 466 octets), coût inhérent au mécanisme. Le PoC ajoute +215 octets de cookies par requête et +427 octets par réponse, car `cnf.jkt` alourdit des jetons qu'il transporte deux fois. Ce supplément tient au format des jetons du PoC et n'est pas imposé par la RFC 9449. |
| **Impact sur la latence et le CPU ?** | De 10 à 100 renouvellements/s : **+0,76 à +0,91 ms** de latence HTTP médiane (+20 à +22 %), **+0,68 à +1,13 ms** de CPU par renouvellement (+17 à +23 % ; pente de régression : +1,01 ms). Le signe est stable : 9 paires sur 9 sont positives pour le p50 et le CPU. Les percentiles p95 et p99 suivent la même tendance avec une dispersion plus forte. |
| **Part due à notre implémentation ?** | **Établi :** une part importante. Le format des jetons explique 215 octets sur 689 par requête et la totalité du surcoût des réponses. La purge anti-rejeu en O(N) porte la validation de 524 µs à 1 322 µs avec 60 000 `jti`. La saturation au-delà d'environ 200 renouvellements/s vient de la table des jetons et touche aussi le mode sans DPoP. |
| | **Probable, non quantifié :** le choix de la pile cryptographique Java, au vu de l'ordre de grandeur des mesures externes. |
| **Ce qui peut être généralisé** | DPoP impose un coût par requête protégée : une signature côté client, une vérification asymétrique côté serveur et quelques centaines d'octets d'en-tête. Côté serveur, ce coût est dominé par la vérification de la signature. Il est proportionnel au nombre de requêtes protégées, ce qui explique la logique du choix de Google. Le coût du store anti-rejeu dépend entièrement de sa structure. |
| **Ce qui doit rester limité au PoC** | Les valeurs absolues : 0,5 ms de validation, +20 % de latence, +1 ms de CPU, 689 octets. Elles dépendent de la pile Java, du format JWE des jetons, d'une instance unique sans TLS, du réseau local virtuel, d'une seule machine et de débits ≤ 100/s. Aucun débit maximal, ni avec ni sans DPoP, ne peut être tiré de cette campagne. |
