#!/usr/bin/env bash
# Scénario 3 — Une preuve valide, mais destinée à une autre requête.
# htu et htm lient la preuve à UNE méthode et UNE URL : pas de réutilisation ailleurs.
set -euo pipefail
source "$(dirname "$0")/_common.sh"
require_backend
reset_backend

title "3. Preuve valide détournée vers un autre endpoint / une autre méthode"
RT=$(fresh_login)

echo "Preuve signée pour /login/connection, présentée sur /login/refresh :"
PROOF=$($GEN --htu "$BASE_URL/login/connection" --key "$CLIENT_KEY")
R1=$(post_refresh "$PROOF" "$RT"); echo "  $R1"; expect_rejected "$R1"
note "htu de la preuve ≠ URL de la requête → rejet."

echo
echo "Preuve signée pour GET, présentée sur POST :"
PROOF=$($GEN --htm GET --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY")
R2=$(post_refresh "$PROOF" "$RT"); echo "  $R2"; expect_rejected "$R2"
note "htm de la preuve ≠ méthode de la requête → rejet."
