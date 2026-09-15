package com.example.dpoplab

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class ConnectionRequest(val uid: String = "alice")
data class ConnectionResponse(
    val status: String,
    val reason: String? = null,
    // Renvoyés en clair UNIQUEMENT pour faciliter les démonstrations du lab.
    // En production, ces jetons ne vivent que dans des cookies HttpOnly.
    val accessToken: String? = null,
    val refreshToken: String? = null,
)

/**
 * Points d'entrée d'authentification du lab.
 *
 * Il n'y a volontairement aucune vérification de mot de passe : le sujet est DPoP,
 * pas l'authentification primaire. N'importe quel `uid` est accepté.
 */
@RestController
class AuthController(
    private val dpopValidator: DpopProofValidator,
    private val tokenStore: TokenStore,
    @Value("\${dpop.required:true}") private val dpopRequired: Boolean,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/login/connection")
    fun connection(
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestBody(required = false) body: ConnectionRequest?,
    ): ConnectionResponse {
        val jkt = resolveJkt(request) ?: return ConnectionResponse("NONE", "DPoP missing or invalid")
        val uid = body?.uid ?: "alice"

        val pair = tokenStore.createTokens(uid, jkt.ifEmpty { null })
        setCookie(response, "access_token", pair.accessToken)
        setCookie(response, "refresh_token", pair.refreshToken)
        log.info("connection OK uid={} jkt={}", uid, jkt.ifEmpty { "<none>" })
        return ConnectionResponse("AUTHENTICATED", null, pair.accessToken, pair.refreshToken)
    }

    @PostMapping("/login/refresh")
    fun refresh(request: HttpServletRequest, response: HttpServletResponse): ConnectionResponse {
        val jkt = resolveJkt(request) ?: return ConnectionResponse("NONE", "DPoP missing or invalid")

        // Le refresh token arrive normalement par le cookie HttpOnly (navigateur, scripts d'attaque
        // qui rejouent un cookie volé). L'en-tête X-Refresh-Token n'existe QUE pour le lab : il
        // permet aux fichiers .http d'injecter un jeton "volé" sans être masqués par le cookie jar
        // du client HTTP de l'IDE. En production, le refresh token ne transite que par le cookie.
        val refreshToken = request.getHeader("X-Refresh-Token")
            ?: request.cookies?.firstOrNull { it.name == "refresh_token" }?.value

        val pair = try {
            tokenStore.refresh(refreshToken, jkt.ifEmpty { null }, dpopRequired)
        } catch (e: TokenReuseException) {
            return ConnectionResponse("NONE", "reuse detected: session revoked")
        } ?: return ConnectionResponse("NONE", "refresh rejected")

        setCookie(response, "access_token", pair.accessToken)
        setCookie(response, "refresh_token", pair.refreshToken)
        return ConnectionResponse("AUTHENTICATED", null, pair.accessToken, pair.refreshToken)
    }

    /** Table des jetons — outil pédagogique, à ne jamais exposer en production. */
    @GetMapping("/debug/tokens")
    fun debugTokens(): List<Map<String, Any?>> = tokenStore.snapshot().map {
        mapOf(
            "type" to it.type,
            "jti" to it.jti,
            "userId" to it.userId,
            "dpopJkt" to it.dpopJkt,
            "sessionCreatedAt" to it.sessionCreatedAt.toString(),
            "expiresAt" to it.expiresAt.toString(),
            "revoked" to it.revoked,
        )
    }

    @PostMapping("/debug/reset")
    fun reset(): Map<String, String> {
        tokenStore.reset()
        return mapOf("status" to "reset")
    }

    /**
     * Renvoie le jkt si la preuve est valide, "" si DPoP n'est pas requis et absent,
     * ou null si la preuve est requise/présente mais invalide.
     */
    private fun resolveJkt(request: HttpServletRequest): String? {
        val header = request.getHeader("DPoP")
        if (header == null) return if (dpopRequired) null else ""
        // Seul le chemin de la requête est pris ici : le validateur le combine à l'URL publique
        // configurée (dpop.public-base-url) pour comparer l'URI complète au claim htu.
        return when (val r = dpopValidator.validate(header, request.method, request.requestURI)) {
            is DpopResult.Valid -> r.jkt
            is DpopResult.Invalid -> {
                log.warn("DPoP invalid: {}", r.reason)
                null
            }
        }
    }

    private fun setCookie(response: HttpServletResponse, name: String, value: String) {
        val cookie = ResponseCookie.from(name, value)
            .httpOnly(true).secure(false).sameSite("Strict").path("/").build()
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
    }
}
