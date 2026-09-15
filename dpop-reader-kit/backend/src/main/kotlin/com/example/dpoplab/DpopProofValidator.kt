package com.example.dpoplab

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.SignedJWT
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Résultat de la validation d'une preuve DPoP.
 * En cas de succès, [Valid.jkt] est l'empreinte SHA-256 de la clé publique (RFC 7638),
 * qui sert à lier le jeton émis à la clé du client.
 */
sealed class DpopResult {
    data class Valid(val jkt: String) : DpopResult()
    data class Invalid(val reason: String) : DpopResult()
}

/**
 * Valide une preuve DPoP (RFC 9449) présentée dans l'en-tête `DPoP`.
 *
 * Les contrôles sont volontairement effectués dans un ordre précis (voir README) :
 * un attaquant ne doit jamais pouvoir contourner un contrôle en jouant sur un autre.
 *
 * Version pédagogique accompagnant l'article MISC. Le store anti-rejeu est en mémoire
 * (voir la remarque sur le multi-instance dans le README).
 */
@Service
class DpopProofValidator(
    // URL publique du backend, telle que le CLIENT la voit et la signe dans htu.
    // Derrière un reverse proxy, c'est l'URL externe (https://api.example.com), pas celle
    // que reçoit le backend (http://backend:8099).
    @Value("\${dpop.public-base-url:http://localhost:8099}") publicBaseUrl: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val publicBase = publicBaseUrl.trimEnd('/')

    // Anti-rejeu : jti déjà vu -> instant de première utilisation. Purgé à chaque appel.
    private val usedJtis = ConcurrentHashMap<String, Instant>()

    /**
     * @param requestPath chemin de la requête reçue (`HttpServletRequest.requestURI`, sans query).
     */
    fun validate(dpopHeader: String, httpMethod: String, requestPath: String): DpopResult =
        runCatching { doValidate(dpopHeader, httpMethod, requestPath) }
            .getOrElse { e ->
                log.debug("DPoP proof malformed: {}", e.message)
                DpopResult.Invalid("Malformed DPoP proof: ${e.message}")
            }

    private fun doValidate(dpopHeader: String, httpMethod: String, requestPath: String): DpopResult {
        val jwt = SignedJWT.parse(dpopHeader)
        val header = jwt.header

        // 1. En-tête : type et algorithme (liste blanche stricte).
        if (header.type != DPOP_TYP) {
            return DpopResult.Invalid("Invalid typ: expected dpop+jwt, got ${header.type}")
        }
        if (header.algorithm != JWSAlgorithm.ES256) {
            return DpopResult.Invalid("Invalid alg: only ES256 is accepted, got ${header.algorithm}")
        }

        // 2. Clé publique embarquée : présente, EC, sans matériel privé.
        val jwk = header.jwk ?: return DpopResult.Invalid("Missing jwk in DPoP header")
        if (jwk.isPrivate) return DpopResult.Invalid("JWK must not contain private key material")
        val ecKey = jwk as? ECKey ?: return DpopResult.Invalid("JWK must be an EC key")

        // 3. Signature : la preuve est bien signée par la clé qu'elle transporte.
        if (!jwt.verify(ECDSAVerifier(ecKey.toECPublicKey()))) {
            return DpopResult.Invalid("Invalid DPoP proof signature")
        }

        val claims = jwt.jwtClaimsSet

        // 4. Liaison à la requête : méthode HTTP.
        val htm = claims.getStringClaim("htm")
        if (!httpMethod.equals(htm, ignoreCase = true)) {
            return DpopResult.Invalid("htm mismatch: expected $httpMethod, got $htm")
        }

        // 5. Liaison à la requête : URI complète (RFC 9449 §4.3) — schéma, hôte, port et chemin,
        //    hors query et fragment. L'URI attendue est reconstruite à partir de l'URL PUBLIQUE
        //    configurée, jamais à partir de l'URL vue derrière le proxy ni des en-têtes
        //    Host / X-Forwarded-Host fournis par le client.
        val htu = claims.getStringClaim("htu") ?: return DpopResult.Invalid("Missing htu claim")
        val signed = runCatching { URI(htu).normalize() }.getOrNull()
            ?: return DpopResult.Invalid("Malformed htu: $htu")
        val expected = URI(publicBase + requestPath).normalize()
        if (!sameHttpUri(signed, expected)) {
            return DpopResult.Invalid("htu mismatch: expected $expected, got $htu")
        }

        // 6. Fraîcheur : iat dans une fenêtre étroite.
        val iat = claims.issueTime?.toInstant() ?: return DpopResult.Invalid("Missing iat claim")
        val skew = abs(Instant.now().epochSecond - iat.epochSecond)
        if (skew > MAX_CLOCK_SKEW_SECONDS) {
            return DpopResult.Invalid("iat out of window (skew=${skew}s)")
        }

        // 7. Anti-rejeu : jti à usage unique sur la fenêtre de validité.
        val jti = claims.jwtid ?: return DpopResult.Invalid("Missing jti claim")
        purgeExpiredJtis()
        if (usedJtis.putIfAbsent(jti, Instant.now()) != null) {
            return DpopResult.Invalid("Replay detected (jti already used)")
        }

        // Succès : l'empreinte de la clé (jkt) servira à lier le jeton.
        return DpopResult.Valid(ecKey.computeThumbprint().toString())
    }

    /**
     * Comparaison d'URI HTTP après normalisation (RFC 3986 §6.2.2-6.2.3) : schéma et hôte
     * insensibles à la casse, port par défaut explicité, chemin identique. Query et fragment
     * sont ignorés, comme le prévoit la RFC 9449.
     */
    private fun sameHttpUri(a: URI, b: URI): Boolean {
        if (a.scheme == null || a.host == null || b.scheme == null || b.host == null) return false
        return a.scheme.equals(b.scheme, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            effectivePort(a) == effectivePort(b) &&
            (a.rawPath ?: "").ifEmpty { "/" } == (b.rawPath ?: "").ifEmpty { "/" }
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }

    private fun purgeExpiredJtis() {
        val cutoff = Instant.now().minusSeconds(JTI_TTL_SECONDS)
        usedJtis.entries.removeIf { it.value.isBefore(cutoff) }
    }

    companion object {
        private val DPOP_TYP = JOSEObjectType("dpop+jwt")
        private const val MAX_CLOCK_SKEW_SECONDS = 60L
        private const val JTI_TTL_SECONDS = 120L
    }
}
