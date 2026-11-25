#!/usr/bin/env bash
# Microbenchmarks JMH du validateur DPoP et des jetons du PoC.
#   scripts/run_jvm.sh                       # configuration complète (annotations : 3 forks,
#                                            # 5×5 s d'échauffement, 10×5 s de mesure, débit + latence)
#   scripts/run_jvm.sh -f 1 -wi 1 -i 2 ...   # options JMH supplémentaires (essai rapide)
#
# Sous Windows, la JVM (et donc ses forks) est épinglée sur les cœurs P (masque PIN_MASK,
# par défaut 0xFFC00 = processeurs logiques 10 à 19 du Core Ultra 7 155H ; voir cpu_topology.ps1).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
J="$HERE/../jvm"
KIT="$HERE/../.."
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Java/jdk-25.0.2}"
export PATH="$JAVA_HOME/bin:$PATH"
OUT="$HERE/../results/jvm/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$OUT"

# Le module JMH doit utiliser la même version de nimbus-jose-jwt que le backend.
v_backend=$(grep -A1 nimbus-jose-jwt "$KIT/backend/pom.xml" | grep -o '<version>[^<]*' | cut -d'>' -f2)
v_jmh=$(grep -o '<nimbus.version>[^<]*' "$J/pom.xml" | cut -d'>' -f2)
[ "$v_backend" = "$v_jmh" ] || { echo "nimbus-jose-jwt : backend $v_backend ≠ JMH $v_jmh" >&2; exit 1; }

(cd "$J" && mvn -B -q clean package -DskipTests)
"$HERE/env_info.sh" > "$OUT/environment.json"
win() { cygpath -w "$1" 2>/dev/null || echo "$1"; }
ARGS="-jar $(win "$J/target/benchmarks.jar") -foe true -prof gc -rf json -rff $(win "$OUT/jmh.json") ${*:-}"
if command -v powershell.exe >/dev/null; then
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(win "$HERE/pinned.ps1")" \
    -Mask "${PIN_MASK:-FFC00}" -Log "$(win "$OUT/jmh.log")" -Exe "$(win "$JAVA_HOME/bin/java.exe")" -ArgLine "$ARGS"
else
  java $ARGS | tee "$OUT/jmh.log"
fi
gzip -9 -k "$OUT/jmh.json"   # copie compressée, versionnée (jmh.json est exclu par .gitignore)
tail -60 "$OUT/jmh.log"
