#!/usr/bin/env bash
# Diagnostic des exécutions saturées à partir des enregistrements JFR du backend (non versionnés) :
# contention de moniteur (jdk.JavaMonitorEnter, seuil JFR par défaut 20 ms) et méthodes les plus
# échantillonnées (jdk.ExecutionSample, sommet de pile).
#   scripts/jfr_hotspots.sh [CAMPAGNE] [REP]   > results/summary/jfr_hotspots.txt
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CAMP="${1:-$(ls -d "$HERE"/../results/load/*/ | grep -v smoke | tail -1)}"
REP="${2:-1}"
JFR="${JFR_BIN:-${JAVA_HOME:-/c/Program Files/Java/jdk-25.0.2}/bin/jfr}"
echo "Campagne $(basename "$CAMP"), répétition $REP"
for rate in 100 250 500; do for mode in bearer dpop; do
  f="$CAMP/rep$REP/${rate}rps_$mode/backend.jfr"; [ -f "$f" ] || continue
  echo; echo "=== $rate req/s, $mode"
  echo "--- événements (nombre, octets)"
  "$JFR" summary "$f" | grep -E " jdk\.(JavaMonitorEnter|ExecutionSample) "
  echo "--- contention de moniteur : point d'attente (nombre d'événements)"
  "$JFR" print --events jdk.JavaMonitorEnter --stack-depth 1 "$f" | grep "line:" | sed 's/^ *//' | sort | uniq -c | sort -rn | head -3
  echo "--- échantillons CPU : méthode au sommet de la pile (nombre)"
  "$JFR" print --events jdk.ExecutionSample --stack-depth 1 "$f" | grep -A1 "stackTrace = \[" | grep "line:" \
    | sed 's/^ *//;s/ *line:.*//' | sort | uniq -c | sort -rn | head -5
done; done
