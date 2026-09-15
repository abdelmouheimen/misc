package com.example.dpoplab

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class TokenType { ACCESS, REFRESH }

/**
 * Une ligne de la « table » des jetons, indexée par le `jti` du JWT. Dans une vraie
 * application c'est une ligne en base ; ici tout est en mémoire pour que le lab tienne
 * dans un `mvn spring-boot:run`.
 *
 * Le JWT porte déjà la liaison (`cnf.jkt`) ; la table reste indispensable pour ce qu'un
 * JWT seul ne sait pas faire : la révocation, la rotation et la détection de réutilisation.
 */
data class TokenRecord(
    val jti: String,
    val type: TokenType,
    val userId: String,
    val dpopJkt: String?,
    val sessionCreatedAt: Instant,
    val expiresAt: Instant,
    var revoked: Boolean = false,
)

class TokenReuseException(message: String) : RuntimeException(message)

data class TokenPair(val accessToken: String, val refreshToken: String)

/**
 * Émission, rotation et révocation des jetons, avec vérification de la liaison DPoP.
 *
 * Reproduit la logique décrite dans l'article :
 *  - rotation : à chaque refresh, l'ancien jeton est révoqué et une nouvelle paire émise ;
 *  - détection de réutilisation : rejouer un refresh déjà révoqué révoque toute la session ;
 *  - liaison DPoP : le jkt du jeton doit correspondre à celui de la preuve présentée.
 */
@Service
class TokenStore(private val jwtService: JwtService) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tokens = ConcurrentHashMap<String, TokenRecord>()

    fun createTokens(userId: String, dpopJkt: String?): TokenPair {
        val now = Instant.now()
        val access = newToken(userId, TokenType.ACCESS, dpopJkt, now)
        val refresh = newToken(userId, TokenType.REFRESH, dpopJkt, now)
        return TokenPair(access, refresh)
    }

    /**
     * Renouvelle une paire de jetons à partir d'un refresh token (JWT).
     * Renvoie null si le JWT est invalide, expiré, inconnu ou d'un mauvais type,
     * ou si la liaison DPoP ne correspond pas.
     */
    fun refresh(refreshToken: String?, dpopJkt: String?, dpopRequired: Boolean): TokenPair? {
        if (refreshToken.isNullOrBlank()) return null

        // Le JWT doit être authentique (signature du serveur), non expiré et de type refresh.
        val claims = jwtService.verify(refreshToken, TokenType.REFRESH) ?: return null
        val existing = tokens[claims.jwtid] ?: return null

        // Détection de réutilisation : un refresh déjà révoqué rejoué = compromission probable.
        if (existing.revoked) {
            log.warn("REFRESH_TOKEN_REUSE_DETECTED userId={} — revoking all tokens", existing.userId)
            revokeAllForUser(existing.userId)
            throw TokenReuseException("Refresh token reuse detected")
        }

        // Vérification de la liaison DPoP : cnf.jkt du jeton = jkt enregistré = jkt de la preuve.
        if (dpopRequired) {
            val tokenJkt = jwtService.boundJkt(claims)
            if (tokenJkt != existing.dpopJkt) {
                log.warn("cnf.jkt does not match stored binding userId={}", existing.userId)
                return null
            }
            if (!tokenJkt.isNullOrEmpty() && tokenJkt != dpopJkt) {
                log.warn("DPoP thumbprint mismatch on refresh userId={}", existing.userId)
                return null
            }
        }

        // Rotation : on révoque l'ancien refresh et les access de l'utilisateur, puis on réémet.
        existing.revoked = true
        revokeAccessForUser(existing.userId)

        val newAccess = newToken(existing.userId, TokenType.ACCESS, dpopJkt, existing.sessionCreatedAt)
        val newRefresh = newToken(existing.userId, TokenType.REFRESH, dpopJkt, existing.sessionCreatedAt)
        return TokenPair(newAccess, newRefresh)
    }

    /** Vue en lecture de la table des jetons (endpoint de debug — lab uniquement). */
    fun snapshot(): List<TokenRecord> = tokens.values.sortedBy { it.sessionCreatedAt }

    fun reset() = tokens.clear()

    private fun newToken(userId: String, type: TokenType, jkt: String?, sessionCreatedAt: Instant): String {
        val issued = jwtService.issue(userId, type, jkt, sessionCreatedAt)
        tokens[issued.jti] = TokenRecord(issued.jti, type, userId, jkt, sessionCreatedAt, issued.expiresAt)
        return issued.value
    }

    private fun revokeAccessForUser(userId: String) {
        tokens.values.filter { it.userId == userId && it.type == TokenType.ACCESS && !it.revoked }
            .forEach { it.revoked = true }
    }

    private fun revokeAllForUser(userId: String) {
        tokens.values.filter { it.userId == userId && !it.revoked }.forEach { it.revoked = true }
    }
}
