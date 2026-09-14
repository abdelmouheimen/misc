#!/usr/bin/env bash
# Scénario 6 — Réutilisation d'un refresh token déjà tourné.
# La rotation transforme le vol en signal : rejouer un ancien jeton révoque la session.
set -euo pipefail
source "$(dirname "$0")/_common.sh"
require_backend
reset_backend

title "6. Détection de réutilisation d'un refresh token"
RT_OLD=$(fresh_login)
note "refresh initial = ${RT_OLD:0:8}…"

echo "Rotation légitime (le client renouvelle avec sa clé) :"
PROOF=$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY")
RESP=$(post_refresh "$PROOF" "$RT_OLD")
RT_NEW=$(echo "$RESP" | json_field refreshToken)
expect_success "$RESP"; note "nouveau refresh = ${RT_NEW:0:8}…, l'ancien est désormais révoqué."

echo
echo "Un attaquant rejoue l'ANCIEN refresh (avec une preuve valide) :"
PROOF=$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY")
RESP=$(post_refresh "$PROOF" "$RT_OLD"); echo "  $RESP"; expect_rejected "$RESP"

echo
echo "Conséquence : même le refresh LÉGITIME courant est maintenant révoqué :"
PROOF=$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY")
RESP=$(post_refresh "$PROOF" "$RT_NEW"); echo "  $RESP"; expect_rejected "$RESP"
note "La réutilisation a déclenché la révocation de toute la session (alerte REFRESH_TOKEN_REUSE_DETECTED côté serveur)."
