#!/usr/bin/env bash
# Scénario 7 — Les limites, démontrées plutôt qu'affirmées.
# DPoP ne protège PAS d'un attaquant qui exécute du code dans le navigateur légitime :
# la clé y est présente et signe des preuves parfaitement valides.
set -euo pipefail
source "$(dirname "$0")/_common.sh"
require_backend
reset_backend

title "7. Ce que DPoP ne protège pas (session riding)"
RT=$(fresh_login)

note "Hypothèse : un XSS s'exécute DANS la page. Il ne peut pas exporter la clé"
note "(extractable:false), mais il peut demander au navigateur de signer pour lui."
echo
echo "Le code injecté forge une preuve avec la clé LÉGITIME (celle de la page) :"
PROOF=$($GEN --htu "$BASE_URL/login/refresh" --key "$CLIENT_KEY")
RESP=$(post_refresh "$PROOF" "$RT"); echo "  $RESP"

STATUS=$(echo "$RESP" | json_field status)
if [ "$STATUS" = "AUTHENTICATED" ]; then
  echo "${C_KO}⚠ L'attaque réussit — et c'est ATTENDU.${C_R}"
  note "DPoP lie le jeton à la clé, pas à une intention. Depuis le navigateur légitime,"
  note "la protection ne joue plus : d'où l'importance des défenses anti-XSS et de la"
  note "portée réduite des jetons. Voir la partie 'Limites' de l'article."
else
  echo "${C_KO}(inattendu dans ce lab)${C_R}"
fi
