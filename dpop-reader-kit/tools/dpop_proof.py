#!/usr/bin/env python3
"""Générateur de preuves DPoP (RFC 9449) — kit de lecteur MISC.

Fabrique une preuve DPoP signée en ES256 (EC P-256) pour une méthode et une URL
données. Toutes les options servent aussi à produire des preuves *volontairement*
invalides, pour les scénarios d'attaque du dossier attacks/.

Dépendance : cryptography  (pip install cryptography)

Exemples :
    # Preuve valide pour un refresh, avec réutilisation de la clé (rotation)
    python dpop_proof.py --htu http://localhost:8099/login/refresh --key client.pem

    # Le jkt (empreinte de la clé) associé à cette clé
    python dpop_proof.py --key client.pem --print-jkt

    # Preuve avec un algorithme interdit (doit être rejetée par le serveur)
    python dpop_proof.py --htu http://localhost:8099/login/refresh --alg none

    # Preuve dont l'iat est dans le passé lointain (hors fenêtre)
    python dpop_proof.py --htu http://localhost:8099/login/refresh --iat-offset -3600
"""
import argparse
import base64
import hashlib
import json
import os
import sys
import time
import uuid

try:
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives import serialization, hashes
    from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
except ImportError:
    sys.exit("Dépendance manquante : pip install cryptography")


def b64u(data: bytes) -> str:
    """base64url sans padding, tel qu'attendu par JOSE."""
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def load_or_create_key(path: str | None) -> ec.EllipticCurvePrivateKey:
    """Charge une clé P-256 depuis un PEM, ou en génère une (persistée si --key)."""
    if path and os.path.exists(path):
        with open(path, "rb") as f:
            return serialization.load_pem_private_key(f.read(), password=None)
    key = ec.generate_private_key(ec.SECP256R1())
    if path:
        with open(path, "wb") as f:
            f.write(key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            ))
    return key


def public_jwk(key: ec.EllipticCurvePrivateKey) -> dict:
    """JWK de la clé publique (les coordonnées x/y sur 32 octets)."""
    nums = key.public_key().public_numbers()
    x = nums.x.to_bytes(32, "big")
    y = nums.y.to_bytes(32, "big")
    return {"crv": "P-256", "kty": "EC", "x": b64u(x), "y": b64u(y)}


def compute_jkt(jwk: dict) -> str:
    """Empreinte SHA-256 de la clé (RFC 7638) : JSON canonique des membres requis."""
    canonical = json.dumps(
        {"crv": jwk["crv"], "kty": jwk["kty"], "x": jwk["x"], "y": jwk["y"]},
        separators=(",", ":"), sort_keys=True,
    )
    return b64u(hashlib.sha256(canonical.encode()).digest())


def es256_signature(key: ec.EllipticCurvePrivateKey, signing_input: bytes) -> bytes:
    """Signe puis convertit la signature DER (OpenSSL) au format JOSE r||s (P1363)."""
    der = key.sign(signing_input, ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der)
    return r.to_bytes(32, "big") + s.to_bytes(32, "big")


def build_proof(args) -> tuple[str, str]:
    key = load_or_create_key(args.key)
    jwk = public_jwk(key)
    jkt = compute_jkt(jwk)

    header = {"typ": args.typ, "alg": args.alg}
    if not args.no_jwk:
        header["jwk"] = jwk
    if args.private_in_jwk:
        # JWK contenant la partie privée 'd' — le serveur DOIT rejeter (attaque).
        d = key.private_numbers().private_value.to_bytes(32, "big")
        header["jwk"] = {**jwk, "d": b64u(d)}

    payload = {
        "jti": args.jti or str(uuid.uuid4()),
        "htm": args.htm,
        "htu": args.htu,
        "iat": int(time.time()) + args.iat_offset,
    }

    signing_input = f"{b64u(json.dumps(header).encode())}.{b64u(json.dumps(payload).encode())}"

    if args.alg == "none":
        # 'none' : pas de signature. Le serveur DOIT rejeter (liste blanche ES256).
        return f"{signing_input}.", jkt
    if args.tamper:
        # Signature valide puis charge utile modifiée après coup : signature invalide.
        sig = es256_signature(key, signing_input.encode())
        bad_payload = {**payload, "htm": "GET"}
        tampered = f"{b64u(json.dumps(header).encode())}.{b64u(json.dumps(bad_payload).encode())}"
        return f"{tampered}.{b64u(sig)}", jkt

    sig = es256_signature(key, signing_input.encode())
    return f"{signing_input}.{b64u(sig)}", jkt


def main():
    ap = argparse.ArgumentParser(description="Générateur de preuves DPoP (RFC 9449)")
    ap.add_argument("--htm", default="POST", help="Méthode HTTP (défaut: POST)")
    ap.add_argument("--htu", default="http://localhost:8099/login/refresh", help="URL cible")
    ap.add_argument("--alg", default="ES256", help="Algorithme d'en-tête (ES256 ; 'none' pour tester le rejet)")
    ap.add_argument("--typ", default="dpop+jwt", help="typ d'en-tête (défaut: dpop+jwt)")
    ap.add_argument("--key", help="Fichier PEM de la clé (créé s'il n'existe pas ; réutilisé sinon)")
    ap.add_argument("--jti", help="jti forcé (défaut: UUID aléatoire ; forcez-le pour tester le rejeu)")
    ap.add_argument("--iat-offset", type=int, default=0, help="Décalage en secondes appliqué à iat")
    ap.add_argument("--no-jwk", action="store_true", help="Omettre le jwk d'en-tête (doit être rejeté)")
    ap.add_argument("--private-in-jwk", action="store_true", help="Inclure la clé privée dans le jwk (doit être rejeté)")
    ap.add_argument("--tamper", action="store_true", help="Modifier la charge utile après signature (signature invalide)")
    ap.add_argument("--print-jkt", action="store_true", help="Afficher uniquement le jkt et quitter")
    args = ap.parse_args()

    if args.print_jkt:
        key = load_or_create_key(args.key)
        print(compute_jkt(public_jwk(key)))
        return

    proof, _ = build_proof(args)
    print(proof)


if __name__ == "__main__":
    main()
