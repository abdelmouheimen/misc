#!/usr/bin/env bash
# Rejoue tous les scénarios d'attaque à la suite.
set -euo pipefail
cd "$(dirname "$0")"
source ./_common.sh
require_backend

for s in 0[1-7]_*.sh; do
  bash "$s"
done

echo
title "Table des jetons après les scénarios (endpoint de debug)"
curl -s "$BASE_URL/debug/tokens" | python3 -m json.tool
