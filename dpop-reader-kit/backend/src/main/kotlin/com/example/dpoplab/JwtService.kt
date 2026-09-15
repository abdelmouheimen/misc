package com.example.dpoplab

import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSADecrypter
import com.nimbusds.jose.crypto.RSAEncrypter
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date
import java.util.UUID

data class IssuedToken(val value: String, val jti: String, val expiresAt: Instant)

/**
 * Émission et vérification des jetons (access et refresh) : JWT imbriqués, SIGNÉS puis CHIFFRÉS.
 *
 *  1. JWS ES256 : les claims sont signés par la clé de signature du serveur (intégrité) ;
 *  2. JWE RSA-OAEP-256 + A256GCM : le JWS est chiffré pour la clé de chiffrement du serveur
 *     (confidentialité). Le client ne voit qu'un jeton opaque à 5 parties.
 *
 * La liaison DPoP est portée par le claim `cnf.jkt` (RFC 9449 §6.1), invisible hors du serveur.
 * Ni la signature ni le chiffrement ne disent QUI présente le jeton : c'est le rôle de DPoP.
 *
 * Les deux clés sont générées au démarrage (lab). En production, elles sont persistées
 * (KMS/HSM) et tournées. Seule la clé publique de SIGNATURE est exposée (JWKS).
 */
@Service
class JwtService(
    @Value("\${jwt.issuer:dpop-lab-backend}") private val issuer: String,
    @Value("\${jwt.access-ttl-seconds:300}") private val accessTtlSeconds: Long,
    @Value("\${jwt.refresh-ttl-seconds:86400}") private val refreshTtlSeconds: Long,
) {

    private val signingKey: ECKey = ECKeyGenerator(Curve.P_256)
        .keyUse(KeyUse.SIGNATURE)
        .algorithm(JWSAlgorithm.ES256)
        .keyIDFromThumbprint(true)
        .generate()

    private val encryptionKey: RSAKey = RSAKeyGenerator(2048)
        .keyUse(KeyUse.ENCRYPTION)
        .algorithm(JWEAlgorithm.RSA_OAEP_256)
        .keyIDFromThumbprint(true)
        .generate()

    private val verifier = ECDSAVerifier(signingKey.toPublicJWK())
    private val decrypter = RSADecrypter(encryptionKey)

    fun issue(userId: String, type: TokenType, jkt: String?, sessionCreatedAt: Instant): IssuedToken {
        val now = Instant.now()
        val jti = UUID.randomUUID().toString()
        val expiresAt = now.plusSeconds(if (type == TokenType.ACCESS) accessTtlSeconds else refreshTtlSeconds)

        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(userId)
            .jwtID(jti)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expiresAt))
            .claim("token_type", type.name.lowercase())
            // Début de la session : conservé à chaque rotation (durée de vie absolue).
            .claim("auth_time", sessionCreatedAt.epochSecond)
            .apply { if (jkt != null) claim("cnf", mapOf("jkt" to jkt)) }
            .build()

        // 1. Signature (JWS). RFC 9068 : un access token JWT est typé "at+jwt".
        val typ = if (type == TokenType.ACCESS) JOSEObjectType("at+jwt") else JOSEObjectType.JWT
        val jws = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).type(typ).keyID(signingKey.keyID).build(), claims)
        jws.sign(ECDSASigner(signingKey))

        // 2. Chiffrement (JWE) du JWS signé : cty=JWT signale un JWT imbriqué (RFC 7519 §5.2).
        val jwe = JWEObject(
            JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM)
                .contentType("JWT")
                .keyID(encryptionKey.keyID)
                .build(),
            Payload(jws),
        )
        jwe.encrypt(RSAEncrypter(encryptionKey.toRSAPublicKey()))
        return IssuedToken(jwe.serialize(), jti, expiresAt)
    }

    /**
     * Vérifie un jeton émis par ce serveur : déchiffrement (alg/enc attendus), puis signature,
     * émetteur, expiration et type. Renvoie ses claims, ou null si l'un des contrôles échoue.
     */
    fun verify(token: String, expectedType: TokenType): JWTClaimsSet? {
        val claims = open(token)?.second ?: return null
        if (claims.issuer != issuer) return null
        val exp = claims.expirationTime ?: return null
        if (exp.before(Date())) return null
        if (claims.getStringClaim("token_type") != expectedType.name.lowercase()) return null
        return claims
    }

    /** Empreinte de clé liée au jeton (claim cnf.jkt), ou null si le jeton n'est pas lié. */
    fun boundJkt(claims: JWTClaimsSet): String? =
        runCatching { claims.getJSONObjectClaim("cnf")?.get("jkt") as? String }.getOrNull()

    /**
     * Introspection (endpoint de lab) : en-têtes JWE et JWS et claims déchiffrés, sans contrôle
     * d'expiration ni de type. Null si le jeton ne se déchiffre pas ou si sa signature est invalide.
     */
    fun introspect(token: String): Map<String, Any?>? {
        val (jwe, claims) = open(token) ?: return null
        val jws = jwe.payload.toSignedJWT()
        return mapOf(
            "jweHeader" to jwe.header.toJSONObject(),
            "jwsHeader" to jws.header.toJSONObject(),
            "claims" to claims.toJSONObject(),
        )
    }

    /** JWKS public de la clé de SIGNATURE (la clé de chiffrement reste privée au serveur). */
    fun jwks(): Map<String, Any> = JWKSet(signingKey.toPublicJWK()).toJSONObject()

    /** Déchiffre puis vérifie la signature ; renvoie le JWE et les claims, ou null. */
    private fun open(token: String): Pair<JWEObject, JWTClaimsSet>? = runCatching {
        val jwe = JWEObject.parse(token)
        if (jwe.header.algorithm != JWEAlgorithm.RSA_OAEP_256) return null
        if (jwe.header.encryptionMethod != EncryptionMethod.A256GCM) return null
        jwe.decrypt(decrypter)

        val jws = jwe.payload.toSignedJWT() ?: return null
        if (jws.header.algorithm != JWSAlgorithm.ES256) return null
        if (!jws.verify(verifier)) return null
        jwe to jws.jwtClaimsSet
    }.getOrNull()
}
