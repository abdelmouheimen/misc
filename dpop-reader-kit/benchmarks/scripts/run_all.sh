#!/usr/bin/env bash
# Campagne complète, dans l'ordre : navigateur, JMH, charge HTTP, puis analyse.
# Les trois benchmarks sont séquentiels : aucun ne tourne pendant un autre.
# Durée mesurée sur la machine de référence : 4 h 40 (navigateur < 1 min, JMH 2 h 23, charge 2 h 13).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
check_power() {
  local s; s=$(powershell.exe -NoProfile -Command "(Get-CimInstance Win32_Battery).BatteryStatus" 2>/dev/null | tr -d '\r')
  if [ "$s" = "1" ] && [ "${ALLOW_BATTERY:-0}" != "1" ]; then
    echo "Machine sur batterie : branchez le secteur (ou ALLOW_BATTERY=1)." >&2; exit 1
  fi
}
check_power; "$HERE/run_browser.sh"
check_power; "$HERE/run_jvm.sh"
check_power; "$HERE/run_load.sh"
python3 "$HERE/analyze.py"
python3 "$HERE/load_timeline.py"
"$HERE/jfr_hotspots.sh" > "$HERE/../results/summary/jfr_hotspots.txt"
python3 "$HERE/figures.py"
python3 "$HERE/verify_numbers.py"
