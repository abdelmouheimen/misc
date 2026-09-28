#!/usr/bin/env bash
# Scénario 10 — Deux raccourcis d'implémentation courants.
#  a) htu comparé sur le CHEMIN seulement accepterait une preuve signée pour un autre hôte :
#     le lab compare l'URI complète à l'URL publique configurée, la preuve est donc rejetée.
#  b) anti-rejeu jti en mémoire : la même preuve est acceptée une fois PAR instance.
#
# (b) nécessite une seconde instance stricte :
#   java -jar backend/target/dpop-lab-backend-1.0.0.jar --server.port=8101
set -euo pipefail
source "$(dirname "$0")/_common.sh"
SECOND_URL="${SECOND_URL:-http://localhost:8101}"
require_backend
reset_backend

title "10a. Preuve signée pour un AUTRE hôte"
PROOF=$($GEN --htu "https://autre-service.example.org/login/connection" --key "$CLIENT_KEY")
note "htu signé = https://autre-service.example.org/login/connection"
RESP=$(curl -s -X POST "$BASE_URL/login/connection" -H "DPoP: $PROOF" \
       -H "Content-Type: application/json" -d '{"uid":"alice"}')
echo "  $BASE_URL → $RESP"; expect_rejected "$RESP"
note "RFC 9449 §4.3 : htu doit correspondre à l'URI de la requête (schéma, hôte, port ET chemin),"
note "hors query et fragment. Le lab la compare à dpop.public-base-url + chemin, pas au seul chemin."

title "10b. Même preuve rejouée sur deux instances"
if ! curl -s -o /dev/null "$SECOND_URL/debug/tokens"; then
  note "(seconde instance absente sur $SECOND_URL — étape ignorée)"; exit 0
fi
curl -s -X POST "$SECOND_URL/debug/reset" >/dev/null
PROOF=$($GEN --htu "$BASE_URL/login/connection" --key "$CLIENT_KEY")
for url in "$BASE_URL" "$BASE_URL" "$SECOND_URL"; do
  RESP=$(curl -s -X POST "$url/login/connection" -H "DPoP: $PROOF" \
         -H "Content-Type: application/json" -d '{"uid":"alice"}')
  echo "  $url → $(echo "$RESP" | json_field status) $(echo "$RESP" | json_field reason)"
done
note "Le rejeu est bloqué sur la même instance, accepté sur la voisine : derrière un"
note "load-balancer, l'anti-rejeu doit être partagé (Redis : SET jti 1 NX EX 120)."
