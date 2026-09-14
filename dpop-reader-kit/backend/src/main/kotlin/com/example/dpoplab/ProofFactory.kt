package com.example.dpoplab

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Fabrique de preuves DPoP — OUTIL DE LABORATOIRE UNIQUEMENT.
 *
 * En vrai, c'est le CLIENT qui détient la clé et signe la preuve (voir la SPA React).
 * Ici, le serveur détient deux clés fixes ("client" et "attacker") pour que les fichiers
 * .http puissent forger des preuves fraîches en une requête, sans dépendance externe.
 *
 * Reproduit les mêmes options que tools/dpop_proof.py, y compris les preuves
 * volontairement invalides utilisées par les scénarios d'attaque.
 */
class ProofFactory(private val keyPair: KeyPair) {

    private val b64 = Base64.getUrlEncoder().withoutPadding()

    companion object {
        fun generate(): ProofFactory {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            return ProofFactory(kpg.generateKeyPair())
        }
    }

    /** Empreinte SHA-256 de la clé (jkt, RFC 7638). */
    fun jkt(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalJwk().toByteArray())
        return b64.encodeToString(digest)
    }

    fun build(req: ProofRequest): String {
        val header = buildString {
            append("{\"typ\":").append(quote(req.typ)).append(",\"alg\":").append(quote(req.alg))
            when {
                req.noJwk -> {}
                req.privateInJwk -> append(",\"jwk\":").append(privateJwk())
                else -> append(",\"jwk\":").append(publicJwk())
            }
            append("}")
        }
        val payload = buildString {
            append("{\"jti\":").append(quote(req.jti ?: java.util.UUID.randomUUID().toString()))
            append(",\"htm\":").append(quote(req.htm))
            append(",\"htu\":").append(quote(req.htu))
            append(",\"iat\":").append(System.currentTimeMillis() / 1000 + req.iatOffset)
            append("}")
        }

        val signingInput = "${b64.encodeToString(header.toByteArray())}.${b64.encodeToString(payload.toByteArray())}"

        // alg=none : aucune signature.
        if (req.alg.equals("none", ignoreCase = true)) return "$signingInput."

        val signature = sign(signingInput.toByteArray())

        // tamper : on signe, puis on modifie la charge utile -> signature invalide.
        if (req.tamper) {
            val tampered = payload.replace("\"htm\":${quote(req.htm)}", "\"htm\":\"GET\"")
            val tamperedInput = "${b64.encodeToString(header.toByteArray())}.${b64.encodeToString(tampered.toByteArray())}"
            return "$tamperedInput.$signature"
        }

        return "$signingInput.$signature"
    }

    /** Signature ES256 au format JOSE r||s (P1363), directement, sans conversion DER. */
    private fun sign(input: ByteArray): String {
        val signer = Signature.getInstance("SHA256withECDSAinP1363Format")
        signer.initSign(keyPair.private)
        signer.update(input)
        return b64.encodeToString(signer.sign())
    }

    private fun publicJwk(): String {
        val pub = keyPair.public as ECPublicKey
        val x = fixed(pub.w.affineX)
        val y = fixed(pub.w.affineY)
        return "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":${quote(b64.encodeToString(x))},\"y\":${quote(b64.encodeToString(y))}}"
    }

    private fun privateJwk(): String {
        // JWK contenant la partie privée 'd' — le serveur DOIT rejeter (jwk.isPrivate).
        val pub = keyPair.public as ECPublicKey
        val x = fixed(pub.w.affineX)
        val y = fixed(pub.w.affineY)
        val d = fixed((keyPair.private as java.security.interfaces.ECPrivateKey).s)
        return "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":${quote(b64.encodeToString(x))}," +
            "\"y\":${quote(b64.encodeToString(y))},\"d\":${quote(b64.encodeToString(d))}}"
    }

    /** JWK canonique (membres requis, ordre lexicographique) pour le calcul du jkt. */
    private fun canonicalJwk(): String {
        val pub = keyPair.public as ECPublicKey
        val x = b64.encodeToString(fixed(pub.w.affineX))
        val y = b64.encodeToString(fixed(pub.w.affineY))
        return "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"$x\",\"y\":\"$y\"}"
    }

    /** BigInteger -> 32 octets exactement (retire un éventuel octet de signe, complète à gauche). */
    private fun fixed(value: BigInteger): ByteArray {
        var bytes = value.toByteArray()
        if (bytes.size == 33 && bytes[0].toInt() == 0) bytes = bytes.copyOfRange(1, 33)
        if (bytes.size == 32) return bytes
        val out = ByteArray(32)
        System.arraycopy(bytes, 0, out, 32 - bytes.size, bytes.size)
        return out
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
