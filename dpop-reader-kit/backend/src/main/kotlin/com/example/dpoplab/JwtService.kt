package com.example.dpoplab

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date
import java.util.UUID

data class IssuedToken(val value: String, val jti: String, val expiresAt: Instant)

/**
 * Émission et vérification des JWT (access et refresh), signés en ES256 par le serveur.
 *
 * La liaison DPoP est portée par le claim `cnf.jkt` (RFC 9449 §6.1) : l'empreinte de la
 * clé publique du client qui a présenté la preuve lors de l'émission.
 *
 * La clé de signature est générée au démarrage (lab) ; sa partie publique est exposée sur
 * `/.well-known/jwks.json` pour vérifier les jetons avec n'importe quel outil JOSE.
 * En production, cette clé est persistée (KMS/HSM) et tournée.
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

    private val verifier = ECDSAVerifier(signingKey.toPublicJWK())

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

        // RFC 9068 : un access token JWT est typé "at+jwt".
        val typ = if (type == TokenType.ACCESS) JOSEObjectType("at+jwt") else JOSEObjectType.JWT
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).type(typ).keyID(signingKey.keyID).build()

        val jwt = SignedJWT(header, claims)
        jwt.sign(ECDSASigner(signingKey))
        return IssuedToken(jwt.serialize(), jti, expiresAt)
    }

    /**
     * Vérifie un jeton émis par ce serveur : algorithme, signature, émetteur, expiration et type.
     * Renvoie ses claims, ou null si l'un des contrôles échoue.
     */
    fun verify(token: String, expectedType: TokenType): JWTClaimsSet? = runCatching {
        val jwt = SignedJWT.parse(token)
        if (jwt.header.algorithm != JWSAlgorithm.ES256) return null
        if (!jwt.verify(verifier)) return null

        val claims = jwt.jwtClaimsSet
        if (claims.issuer != issuer) return null
        val exp = claims.expirationTime ?: return null
        if (exp.before(Date())) return null
        if (claims.getStringClaim("token_type") != expectedType.name.lowercase()) return null
        claims
    }.getOrNull()

    /** Empreinte de clé liée au jeton (claim cnf.jkt), ou null si le jeton n'est pas lié. */
    fun boundJkt(claims: JWTClaimsSet): String? =
        runCatching { claims.getJSONObjectClaim("cnf")?.get("jkt") as? String }.getOrNull()

    /** JWKS public (sans la partie privée). */
    fun jwks(): Map<String, Any> = JWKSet(signingKey.toPublicJWK()).toJSONObject()
}
