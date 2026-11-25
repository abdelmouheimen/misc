#!/usr/bin/env bash
# Benchmark HTTP de bout en bout : renouvellement du refresh token, DPoP contre baseline bearer.
#
#   scripts/run_load.sh                         # campagne complète (5 débits × 2 modes × 3 répétitions)
#   RATES="10 50" REPS=1 WARMUP=10 DURATION=20 scripts/run_load.sh   # essai court
#
# Chaque exécution part d'un conteneur backend NEUF (JVM, table des jetons et store anti-rejeu
# vides). L'ordre des exécutions est tiré au sort à chaque répétition (graine fixée et
# enregistrée), pour que dérive thermique ou énergétique ne favorise aucun mode.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BENCH="$(cd "$HERE/.." && pwd)"
KIT="$(cd "$BENCH/.." && pwd)"
LOAD="$BENCH/load"
COMPOSE=(docker compose -f "$(cygpath -w "$LOAD/docker-compose.bench.yml" 2>/dev/null || echo "$LOAD/docker-compose.bench.yml")")

RATES="${RATES:-10 50 100 250 500}"
REPS="${REPS:-3}"
WARMUP="${WARMUP:-120}"       # s ; ≥ 120 s pour que le store anti-rejeu (TTL 120 s) soit en régime
DURATION="${DURATION:-120}"   # s de mesure
SEED="${SEED:-20261124}"
COOLDOWN="${COOLDOWN:-10}"
K6_VERSION="${K6_VERSION:-v2.4.0}"
COMMIT="$(git -C "$KIT" rev-parse --short HEAD)"
CAMPAIGN="${CAMPAIGN:-$(date -u +%Y%m%dT%H%M%SZ)}"
OUT="$BENCH/results/load/$CAMPAIGN"
K6_DIR="$LOAD/.tools/k6-$K6_VERSION-linux-amd64"
export BACKEND_IMAGE="${BACKEND_IMAGE:-dpop-lab-backend:bench-$COMMIT}"
export K6_DIR_WIN; K6_DIR_WIN="$(cygpath -w "$K6_DIR" 2>/dev/null || echo "$K6_DIR")"

winpath() { cygpath -w "$1" 2>/dev/null || echo "$1"; }
log() { echo "[$(date -u +%H:%M:%S)] $*"; }

# --- Préparation ----------------------------------------------------------------------------
[ -f "$K6_DIR/k6" ] || "$HERE/fetch_k6.sh" "$K6_VERSION"
if ! docker image inspect "$BACKEND_IMAGE" >/dev/null 2>&1; then
  log "construction de l'image $BACKEND_IMAGE (backend du commit $COMMIT)"
  docker build -q -t "$BACKEND_IMAGE" "$KIT/backend" >/dev/null
fi
mkdir -p "$OUT"
"$HERE/env_info.sh" > "$OUT/environment.json"
python3 - "$OUT/plan.csv" "$SEED" "$REPS" $RATES <<'EOF'
import csv, random, sys
out, seed, reps, rates = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), [int(r) for r in sys.argv[4:]]
rng = random.Random(seed)
with open(out, 'w', newline='') as f:
    w = csv.writer(f, lineterminator='\n'); w.writerow(['rep', 'order', 'rate', 'mode'])
    for rep in range(1, reps + 1):
        runs = [(r, m) for r in rates for m in ('dpop', 'bearer')]
        rng.shuffle(runs)
        for i, (r, m) in enumerate(runs, 1): w.writerow([rep, i, r, m])
EOF
cat > "$OUT/parameters.json" <<EOF
{"rates": "$RATES", "reps": $REPS, "warmupS": $WARMUP, "durationS": $DURATION, "seed": $SEED,
 "cooldownS": $COOLDOWN, "k6": "$K6_VERSION", "commit": "$COMMIT", "backendImage": "$BACKEND_IMAGE",
 "backendCpus": 4, "backendMem": "2g", "k6Cpus": 4, "k6Mem": "2g",
 "jvmOptions": "-Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=4 (+ JFR default)",
 "network": "réseau Docker interne (bridge, internal), HTTP/1.1 sans TLS, aucun port publié"}
EOF

# CPU consommé par un conteneur depuis son démarrage, en µs (cgroup v2 ou v1).
cpu_usec() {
  MSYS_NO_PATHCONV=1 docker exec "$1" sh -c     'if [ -f /sys/fs/cgroup/cpu.stat ]; then awk "/^usage_usec/ {print \$2}" /sys/fs/cgroup/cpu.stat;
     else echo $(( $(cat /sys/fs/cgroup/cpuacct/cpuacct.usage) / 1000 )); fi' 2>/dev/null
}
mem_bytes() {
  MSYS_NO_PATHCONV=1 docker exec "$1" sh -c     'cat /sys/fs/cgroup/memory.current 2>/dev/null || cat /sys/fs/cgroup/memory/memory.usage_in_bytes' 2>/dev/null
}
now() { date +%s.%N; }

cleanup() { "${COMPOSE[@]}" --profile load down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

run_one() {
  local rep=$1 rate=$2 mode=$3
  local dir="$OUT/rep$rep/${rate}rps_$mode"
  mkdir -p "$dir"
  export RUN_DIR; RUN_DIR="$(winpath "$dir")"
  export K6_DIR="$K6_DIR_WIN"
  export DPOP_REQUIRED; DPOP_REQUIRED=$([ "$mode" = dpop ] && echo true || echo false)

  log "rep $rep : $rate req/s, mode $mode"
  "${COMPOSE[@]}" up -d --force-recreate backend >/dev/null 2>&1
  local backend; backend="$("${COMPOSE[@]}" ps -q backend)"
  for _ in $(seq 1 90); do
    docker logs "$backend" 2>&1 | grep -q 'Started DpopLabApplication' && break
    sleep 1
  done
  docker logs "$backend" 2>&1 | grep -q 'Started DpopLabApplication' || { log "backend non démarré"; return 1; }

  # Échantillonnage continu CPU/mémoire des deux conteneurs (≈ 1 s) pour détecter une saturation.
  docker rm -f dpop-bench-k6 >/dev/null 2>&1 || true
  MSYS_NO_PATHCONV=1 "${COMPOSE[@]}" --profile load run -d --name dpop-bench-k6 \
    -e MODE="$mode" -e RATE="$rate" -e WARMUP="${WARMUP}s" -e DURATION="${DURATION}s" \
    k6 run --quiet --out csv=/out/k6.csv.gz refresh.js >/dev/null
  local t_start; t_start=$(now)
  docker stats --format '{{.Name}};{{.CPUPerc}};{{.MemUsage}}' "$backend" dpop-bench-k6 \
    > "$dir/docker_stats.raw" 2>/dev/null &
  local stats_pid=$!

  # Fenêtre CPU : strictement incluse dans la phase de mesure (marge de 3 s au début, 1 s à la fin).
  sleep $((WARMUP + 3))
  local t0 b0 k0 m0; t0=$(now); b0=$(cpu_usec "$backend"); k0=$(cpu_usec dpop-bench-k6); m0=$(mem_bytes "$backend")
  sleep $((DURATION - 4))
  local t1 b1 k1 m1; t1=$(now); b1=$(cpu_usec "$backend"); k1=$(cpu_usec dpop-bench-k6); m1=$(mem_bytes "$backend")
  docker wait dpop-bench-k6 >/dev/null
  docker logs dpop-bench-k6 > "$dir/k6.log" 2>&1 || true
  docker rm -f dpop-bench-k6 >/dev/null 2>&1 || true
  kill "$stats_pid" 2>/dev/null || true
  # Flux brut de `docker stats` (≈ 1 échantillon/s) : on retire les séquences d'effacement d'écran.
  sed 's/\[[0-9;]*[A-Za-z]//g' "$dir/docker_stats.raw" | grep ';' > "$dir/docker_stats.csv" || true
  rm -f "$dir/docker_stats.raw"

  # Occupation finale de la table des jetons (endpoint de debug, réseau interne uniquement).
  local tokens
  tokens=$(docker run --rm --network dpop-bench_benchnet alpine:latest \
    wget -qO- http://backend:8099/debug/tokens | python3 -c \
    'import json,sys;t=json.load(sys.stdin);print(json.dumps({"total":len(t),"active":sum(1 for x in t if not x["revoked"]),"bound":sum(1 for x in t if x["dpopJkt"])}))')

  "${COMPOSE[@]}" stop -t 30 backend >/dev/null 2>&1   # arrêt propre : JFR écrit backend.jfr
  docker logs "$backend" 2>&1 | grep -E ' (WARN|ERROR) ' | sed 's/.*\] //' | sort | uniq -c | sort -rn | head -20 > "$dir/backend_warnings.txt" || true
  "${COMPOSE[@]}" rm -f backend >/dev/null 2>&1

  cat > "$dir/run.json" <<EOF
{"rep": $rep, "rate": $rate, "mode": "$mode", "k6StartEpoch": $t_start,
 "cpuWindow": {"t0": $t0, "t1": $t1, "backendUsageUsec": [$b0, $b1], "k6UsageUsec": [$k0, $k1],
               "backendMemBytes": [$m0, $m1]},
 "tokenTable": $tokens}
EOF
  sleep "$COOLDOWN"
}

tail -n +2 "$OUT/plan.csv" | sed 's/\r$//' | while IFS=, read -r rep order rate mode; do
  run_one "$rep" "$rate" "$mode" < /dev/null
done
log "campagne terminée : $OUT"
