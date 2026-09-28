#!/usr/bin/env bash
# Scénario 9 — Tempête de refresh : la détection de réutilisation se retourne contre le client.
# Une SPA dont l'intercepteur 401 lance UN refresh PAR requête en échec envoie, au même
# instant, N refresh avec le même refresh token (preuves DPoP valides, jti distincts).
# Le premier gagne la rotation ; les suivants présentent un jeton déjà révoqué, ce qui
# déclenche la révocation de toute la session — y compris la paire que le gagnant vient
# de recevoir. Aucun attaquant : l'utilisateur est déconnecté par son propre front.
set -euo pipefail
source "$(dirname "$0")/_common.sh"
require_backend
reset_backend
N="${N:-5}"
TMP=$(mktemp -d)

title "9. Tempête de refresh ($N appels parallèles, même refresh token)"
RT=$(fresh_login)
for i in $(seq 1 "$N"); do
  $GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY" > "$TMP/proof_$i"
done
for i in $(seq 1 "$N"); do
  curl -s -X POST "$BASE_URL/login/refresh" -H "DPoP: $(cat "$TMP/proof_$i")" \
       -H "Cookie: refresh_token=$RT" > "$TMP/resp_$i" &
done
wait

WINNER=""
for i in $(seq 1 "$N"); do
  echo "  appel $i : $(json_field status < "$TMP/resp_$i") $(json_field reason < "$TMP/resp_$i" | sed 's/^None$//')"
  if grep -q '"AUTHENTICATED"' "$TMP/resp_$i"; then WINNER=$(json_field refreshToken < "$TMP/resp_$i"); fi
done

if [ -n "$WINNER" ]; then
  echo
  echo "Le front utilise la paire du gagnant pour son prochain refresh :"
  PROOF=$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY")
  RESP=$(post_refresh "$PROOF" "$WINNER"); echo "  $RESP"
  [ "$(echo "$RESP" | json_field status)" != "AUTHENTICATED" ] \
    && echo "${C_KO}⚠ session révoquée par le client lui-même (faux positif REFRESH_TOKEN_REUSE_DETECTED)${C_R}"
fi
rm -rf "$TMP"
note "Correctif côté SPA : mutualiser le refresh en cours (une seule promesse partagée)."
note "Côté serveur : tolérer une courte fenêtre de grâce sur le refresh qui vient d'être tourné"
note "(même jkt, quelques secondes) avant de conclure à un vol."
