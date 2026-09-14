#!/usr/bin/env bash
# Scénario 5 — Manipulations de l'en-tête JOSE : alg, jwk, signature.
# La liste blanche stricte (ES256 seulement) et les contrôles du JWK ferment
# les classes d'attaques classiques sur les JWT.
set -euo pipefail
source "$(dirname "$0")/_common.sh"
require_backend
reset_backend

title "5. alg / jwk / signature falsifiés"
RT=$(fresh_login)

run() { # <libellé> <preuve>
  local r; r=$(post_refresh "$2" "$RT")
  printf "  %-28s %s\n" "$1" "$(echo "$r" | json_field reason)"
  echo "$r" | grep -q AUTHENTICATED && echo "    ${C_KO}✗ passé !${C_R}" || echo "    ${C_OK}✓ bloqué${C_R}"
}

run "alg: none (non signé)"      "$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY" --alg none)"
run "typ ≠ dpop+jwt"             "$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY" --typ jwt)"
run "jwk absent"                 "$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY" --no-jwk)"
run "clé privée dans le jwk"     "$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY" --private-in-jwk)"
run "charge utile falsifiée"     "$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY" --tamper)"
note "Tous rejetés au stade de la validation de la preuve."
