#!/usr/bin/env bash
# Scénario 8 — La détection de réutilisation est un "kill switch"... actionnable sans la clé.
# La vérification "jeton révoqué ?" précède la vérification du jkt. Un attaquant qui ne
# possède qu'un ANCIEN refresh token (logs, sauvegarde, proxy) et aucune clé peut donc
# révoquer la session de la victime. À l'inverse, un refresh COURANT présenté avec une
# mauvaise clé est simplement rejeté, sans révocation ni alerte.
set -euo pipefail
source "$(dirname "$0")/_common.sh"
require_backend
reset_backend

title "8a. Refresh COURANT volé + mauvaise clé"
RT=$(fresh_login)
PROOF=$($GEN --htu "$BASE_URL/login/refresh" --key "$ATTACKER_KEY")
RESP=$(post_refresh "$PROOF" "$RT"); echo "  attaquant : $RESP"
PROOF=$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY")
RESP=$(post_refresh "$PROOF" "$RT"); echo "  client    : $RESP"
note "Rejet silencieux (simple WARN) : la session légitime continue, aucune alerte levée."

reset_backend
title "8b. ANCIEN refresh volé + mauvaise clé"
RT_OLD=$(fresh_login)
PROOF=$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY")
RT_NEW=$(post_refresh "$PROOF" "$RT_OLD" | json_field refreshToken)
note "rotation légitime : $(short_token "$RT_OLD") → $(short_token "$RT_NEW")"

PROOF=$($GEN --htu "$BASE_URL/login/refresh" --key "$ATTACKER_KEY")
RESP=$(post_refresh "$PROOF" "$RT_OLD"); echo "  attaquant (sans la clé) : $RESP"
PROOF=$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY")
RESP=$(post_refresh "$PROOF" "$RT_NEW"); echo "  client (refresh courant) : $RESP"
STATUS=$(echo "$RESP" | json_field status)
[ "$STATUS" != "AUTHENTICATED" ] \
  && echo "${C_KO}⚠ la victime est déconnectée par un attaquant qui n'a jamais eu la clé${C_R}"
note "Choix de conception à assumer : fail-safe (déni de service possible) ou vérifier"
note "le jkt AVANT de déclencher la révocation (et alerter aussi sur un jkt mismatch)."
