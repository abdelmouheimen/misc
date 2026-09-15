#!/usr/bin/env bash
# Scénario 1 — Le refresh token est volé, mais l'attaquant n'a pas la clé.
# C'est LA démonstration centrale de DPoP : un jeton valide ne suffit plus.
set -euo pipefail
source "$(dirname "$0")/_common.sh"
require_backend
reset_backend

title "1. Refresh token volé, présenté avec la clé de l'attaquant"
note "jkt client    = $($GEN --key "$CLIENT_KEY" --print-jkt)"
note "jkt attaquant = $($GEN --key "$ATTACKER_KEY" --print-jkt)"

RT=$(fresh_login)
note "refresh token (dérobé) = $(short_token "$RT")"
note "claims du JWT volé    = $(jwt_claims "$RT")"

echo "L'attaquant forge une preuve valide… avec SA propre clé :"
PROOF=$($GEN --htu "$BASE_URL/login/refresh" --key "$ATTACKER_KEY")
RESP=$(post_refresh "$PROOF" "$RT")
echo "  réponse : $RESP"
expect_rejected "$RESP"
note "Côté serveur : la liaison jkt du jeton ≠ jkt de la preuve → rejet."
