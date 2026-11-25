#!/usr/bin/env bash
# Télécharge le binaire officiel k6 (Linux amd64) depuis les releases GitHub et vérifie sa
# somme SHA-256 contre le fichier de sommes publié. Il s'exécute ensuite dans un conteneur
# alpine (voir load/docker-compose.bench.yml) : aucune image k6 n'est nécessaire.
set -euo pipefail
V="${1:-v2.4.0}"
T="$(cd "$(dirname "$0")/../load" && pwd)/.tools"
mkdir -p "$T" && cd "$T"
curl -sSLO "https://github.com/grafana/k6/releases/download/$V/k6-$V-linux-amd64.tar.gz"
curl -sSLO "https://github.com/grafana/k6/releases/download/$V/k6-$V-checksums.txt"
grep " k6-$V-linux-amd64.tar.gz\$" "k6-$V-checksums.txt" | sha256sum -c -
tar xzf "k6-$V-linux-amd64.tar.gz"
echo "k6 $V prêt dans $T/k6-$V-linux-amd64"
