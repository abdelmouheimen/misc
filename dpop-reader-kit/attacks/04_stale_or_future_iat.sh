#!/usr/bin/env bash
# Scénario 4 — Preuve trop ancienne ou datée dans le futur.
# iat borne la durée de vie d'une preuve : une preuve pré-générée ou périmée est rejetée.
set -euo pipefail
source "$(dirname "$0")/_common.sh"
require_backend
reset_backend

title "4. Preuve hors de la fenêtre temporelle (iat)"
RT=$(fresh_login)

echo "iat il y a 1 heure :"
PROOF=$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY" --iat-offset -3600)
R1=$(post_refresh "$PROOF" "$RT"); echo "  $R1"; expect_rejected "$R1"

echo
echo "iat dans 1 heure (preuve pré-générée) :"
PROOF=$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY" --iat-offset 3600)
R2=$(post_refresh "$PROOF" "$RT"); echo "  $R2"; expect_rejected "$R2"
note "Écart iat > fenêtre autorisée (60 s) → rejet."
