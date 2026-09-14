# Fonctions communes aux scénarios d'attaque. Sourcé par chaque script.
# Nécessite : bash, curl, python3 (avec le paquet cryptography).

BASE_URL="${BASE_URL:-http://localhost:8099}"
GEN="python3 $(dirname "${BASH_SOURCE[0]}")/../tools/dpop_proof.py"
CLIENT_KEY="${CLIENT_KEY:-/tmp/dpop_client.pem}"
ATTACKER_KEY="${ATTACKER_KEY:-/tmp/dpop_attacker.pem}"

# Couleurs (désactivées si la sortie n'est pas un terminal)
if [ -t 1 ]; then C_OK=$'\e[32m'; C_KO=$'\e[31m'; C_DIM=$'\e[90m'; C_B=$'\e[1m'; C_R=$'\e[0m'
else C_OK=; C_KO=; C_DIM=; C_B=; C_R=; fi

title() { echo; echo "${C_B}== $* ==${C_R}"; }
note()  { echo "${C_DIM}$*${C_R}"; }

# json_field <clé> : lit un champ de la réponse JSON reçue sur stdin
json_field() { python3 -c "import sys,json;print(json.load(sys.stdin).get('$1',''))"; }

# reset_backend : vide la table des jetons du lab
reset_backend() { curl -s -X POST "$BASE_URL/debug/reset" >/dev/null; }

# fresh_login : ouvre une session avec la clé client et renvoie le refresh token courant
fresh_login() {
  local proof resp
  proof=$($GEN --htu "$BASE_URL/login/connection" --key "$CLIENT_KEY")
  resp=$(curl -s -X POST "$BASE_URL/login/connection" \
    -H "DPoP: $proof" -H "Content-Type: application/json" -d '{"uid":"alice"}')
  echo "$resp" | json_field refreshToken
}

# post_refresh <proof> <refresh_token> : tente un refresh, renvoie le corps JSON
post_refresh() {
  curl -s -X POST "$BASE_URL/login/refresh" -H "DPoP: $1" -H "X-Refresh-Token: $2"
}

# expect_rejected <json> : affiche le verdict attendu (l'attaque doit échouer)
expect_rejected() {
  local status; status=$(echo "$1" | json_field status)
  local reason; reason=$(echo "$1" | json_field reason)
  if [ "$status" = "AUTHENTICATED" ]; then
    echo "${C_KO}✗ ATTAQUE RÉUSSIE (inattendu) — la protection n'a pas joué${C_R}"
  else
    echo "${C_OK}✓ attaque bloquée${C_R} ${C_DIM}(status=$status, reason=$reason)${C_R}"
  fi
}

# expect_success <json> : affiche le verdict attendu (le scénario doit réussir)
expect_success() {
  local status; status=$(echo "$1" | json_field status)
  if [ "$status" = "AUTHENTICATED" ]; then
    echo "${C_OK}✓ succès${C_R} ${C_DIM}(status=$status)${C_R}"
  else
    echo "${C_KO}✗ échec inattendu (status=$status)${C_R}"
  fi
}

require_backend() {
  if ! curl -s -o /dev/null "$BASE_URL/debug/tokens"; then
    echo "${C_KO}Backend injoignable sur $BASE_URL${C_R}"
    echo "Démarrez-le : cd backend && mvn spring-boot:run   (ou docker compose up)"
    exit 1
  fi
}
