#!/usr/bin/env bash
# Benchmark Web Crypto dans Chromium (Playwright), épinglé sur les cœurs P sous Windows.
#   scripts/run_browser.sh [--series 5] [--warmup 200] [--iterations 2000] [--batch 500]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
B="$HERE/../browser"
OUT="$HERE/../results/browser/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$OUT"
cd "$B"
[ -d node_modules ] || npm ci --no-audit --no-fund
npx playwright install chromium >/dev/null
"$HERE/env_info.sh" > "$OUT/environment.json"
ARGS="run-browser-bench.mjs --out $(cygpath -w "$OUT" 2>/dev/null || echo "$OUT") ${*:-}"
if command -v powershell.exe >/dev/null && [ -n "${PIN_MASK:-FFC00}" ]; then
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$HERE/pinned.ps1")" \
    -Mask "${PIN_MASK:-FFC00}" -Log "$(cygpath -w "$OUT/run.log")" -Exe node -ArgLine "$ARGS"
  cat "$OUT/run.log"
else
  node $ARGS | tee "$OUT/run.log"
fi
