#!/usr/bin/env bash
# Scénario 11 — La liaison ne dépend pas du drapeau global `dpop.required`.
#
# Même serveur tournant en mode bearer (DPOP_REQUIRED=false), un refresh token qui a été
# lié à une clé reste refusé s'il est présenté sans preuve, ou avec la preuve d'une autre clé.
# Sans cette propriété, il suffirait de basculer un drapeau de configuration pour que tous
# les jetons déjà émis redeviennent portables.
set -euo pipefail
source "$(dirname "$0")/_common.sh"
require_backend

title "11. Un refresh token lié reste lié, quel que soit dpop.required"

# Le lab n'expose pas sa configuration : on la déduit du comportement. En mode bearer,
# une connexion sans en-tête DPoP est acceptée ; en mode DPoP exigé, elle est refusée.
PROBE=$(curl -s -X POST "$BASE_URL/login/connection" \
  -H "Content-Type: application/json" -d '{"uid":"probe"}')
MODE=$(echo "$PROBE" | json_field status)

if [ "$MODE" != "AUTHENTICATED" ]; then
  note "Backend en mode DPoP exigé (dpop.required=true) — scénario non applicable."
  note "Pour le rejouer : DPOP_REQUIRED=false docker compose up -d backend, puis relancez ce script."
  exit 0
fi

note "Backend en mode bearer (dpop.required=false) : c'est le mode le plus permissif."
reset_backend

echo
echo "Le client légitime ouvre une session AVEC une preuve : le jeton est lié à sa clé."
note "jkt client = $($GEN --key "$CLIENT_KEY" --print-jkt)"
RT=$(fresh_login)
note "refresh token = $(short_token "$RT"), claims = $(jwt_claims "$RT")"

echo
echo "1) Le jeton est présenté SANS aucune preuve (ce que permettrait le mode bearer) :"
RESP=$(curl -s -X POST "$BASE_URL/login/refresh" -H "Cookie: refresh_token=$RT")
echo "   réponse : $RESP"
expect_rejected "$RESP"
note "La liaison est vérifiée parce que le jeton la porte, pas parce qu'un drapeau l'exige."

echo
echo "2) Le jeton est présenté avec la preuve d'une AUTRE clé :"
PROOF=$($GEN --htu "$BASE_URL/login/refresh" --key "$ATTACKER_KEY")
RESP=$(post_refresh "$PROOF" "$RT")
echo "   réponse : $RESP"
expect_rejected "$RESP"

echo
echo "3) Le client légitime renouvelle avec sa propre clé :"
PROOF=$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY")
RESP=$(post_refresh "$PROOF" "$RT")
RT_NEW=$(echo "$RESP" | json_field refreshToken)
expect_success "$RESP"
note "nouveau refresh = $(short_token "$RT_NEW")"
note "claims = $(jwt_claims "$RT_NEW")"
note "La rotation propage la liaison d'origine : le nouveau jeton porte le même cnf.jkt."
