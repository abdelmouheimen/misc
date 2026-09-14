#!/usr/bin/env bash
# Scénario 2 — Rejeu d'une preuve capturée à l'identique.
# Même une preuve parfaitement valide ne peut servir qu'une fois (jti unique).
set -euo pipefail
source "$(dirname "$0")/_common.sh"
require_backend
reset_backend

title "2. Rejeu d'une preuve DPoP capturée"
RT=$(fresh_login)

JTI=$(python3 -c "import uuid;print(uuid.uuid4())")
PROOF=$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY" --jti "$JTI")
note "jti figé pour rejouer la MÊME preuve : $JTI"

echo "Première utilisation :"
R1=$(post_refresh "$PROOF" "$RT"); echo "  $R1"; expect_success "$R1"

echo "Rejeu de la même preuve :"
R2=$(post_refresh "$PROOF" "$RT"); echo "  $R2"; expect_rejected "$R2"
note "Le jti a déjà été vu dans la fenêtre anti-rejeu → rejet."
