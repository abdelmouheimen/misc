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
    // Modifié sous le verrou de TokenStore ; @Volatile pour les lectures hors verrou (debug).
    @Volatile var revoked: Boolean = false,
)

class TokenReuseException(message: String) : RuntimeException(message)

data class TokenPair(val accessToken: String, val refreshToken: String)

/**
 * Émission, rotation et révocation des jetons, avec vérification de la liaison DPoP.
 *
 * Reproduit la logique décrite dans l'article :
 *  - rotation : à chaque refresh, l'ancien jeton est révoqué et une nouvelle paire émise ;
 *  - détection de réutilisation : rejouer un refresh déjà révoqué révoque toute la session ;
 *  - liaison DPoP : le jkt du jeton doit correspondre à celui de la preuve présentée, et
 *    cette vérification ne dépend pas du drapeau global `dpop.required` ;
 *  - atomicité : un refresh token ne produit jamais plus d'un renouvellement réussi, même
 *    sous requêtes concurrentes (voir [refresh]).
 */
@Service
class TokenStore(private val jwtService: JwtService) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tokens = ConcurrentHashMap<String, TokenRecord>()

    // Toute transition d'état de la table (émission, rotation, révocation) passe par ce verrou.
    // La ConcurrentHashMap protège la structure de la table, pas le couple « lire revoked puis
    // le modifier » : sans ce verrou, deux requêtes pouvaient tourner le même refresh token.
    // Garantie limitée à UNE instance : en cluster, il faut une mise à jour conditionnelle dans
    // un store partagé (UPDATE … WHERE revoked = false, puis vérifier le nombre de lignes).
    private val lock = Any()

    fun createTokens(userId: String, dpopJkt: String?): TokenPair = synchronized(lock) {
        val now = Instant.now()
        val access = newToken(userId, TokenType.ACCESS, dpopJkt, now)
        val refresh = newToken(userId, TokenType.REFRESH, dpopJkt, now)
        TokenPair(access, refresh)
    }

    /**
     * Renouvelle une paire de jetons à partir d'un refresh token (JWT).
     * Renvoie null si le JWT est invalide, expiré, inconnu ou d'un mauvais type,
     * ou si la liaison DPoP ne correspond pas.
     *
     * La liaison ne dépend PAS du drapeau global `dpop.required` : dès qu'un jeton porte
     * une liaison, elle est vérifiée. Un refresh token lié à une clé reste donc refusé
     * lorsqu'il est présenté sans preuve, même si le serveur tourne en mode bearer.
     *
     * Atomicité : la lecture de l'état, les contrôles, la révocation de l'ancien jeton et
     * l'émission de la nouvelle paire forment une seule section critique. Deux requêtes qui
     * présentent le même jeton sont donc sérialisées : la première le tourne, la seconde le
     * trouve révoqué et suit la politique de réutilisation (révocation de la session, y compris
     * la paire que la première vient de recevoir). Aucune ne peut réussir en double.
     */
    fun refresh(refreshToken: String?, dpopJkt: String?): TokenPair? {
        if (refreshToken.isNullOrBlank()) return null

        // Le JWT doit être authentique (signature du serveur), non expiré et de type refresh.
        // Déchiffrement et vérification restent hors verrou : ils ne lisent aucun état partagé.
        val claims = jwtService.verify(refreshToken, TokenType.REFRESH) ?: return null
        val tokenJkt = jwtService.boundJkt(claims)

        synchronized(lock) {
            val existing = tokens[claims.jwtid] ?: return null

            // Détection de réutilisation : un refresh déjà révoqué rejoué = compromission probable.
            // Ce contrôle passe AVANT la liaison : le jkt d'un jeton révoqué n'est jamais comparé.
            if (existing.revoked) {
                log.warn("REFRESH_TOKEN_REUSE_DETECTED userId={} — revoking all tokens", existing.userId)
                revokeAllForUser(existing.userId)
                throw TokenReuseException("Refresh token reuse detected")
            }

            // Le cnf.jkt porté par le JWT doit correspondre à la liaison enregistrée côté serveur.
            if (tokenJkt != existing.dpopJkt) {
                log.warn("cnf.jkt does not match stored binding userId={}", existing.userId)
                return null
            }

            // Liaison DPoP : si le jeton est lié, la preuve présentée doit porter le même jkt.
            val boundJkt = existing.dpopJkt
            if (boundJkt != null && boundJkt != dpopJkt) {
                log.warn("DPoP thumbprint mismatch on refresh userId={}", existing.userId)
                return null
            }

            // Rotation : on révoque l'ancien refresh et les access de l'utilisateur, puis on réémet.
            existing.revoked = true
            revokeAccessForUser(existing.userId)

            // La rotation propage la liaison D'ORIGINE, jamais le jkt de la preuve reçue : une preuve
            // ne peut donc pas déplacer une session existante vers une autre clé.
            val newAccess = newToken(existing.userId, TokenType.ACCESS, boundJkt, existing.sessionCreatedAt)
            val newRefresh = newToken(existing.userId, TokenType.REFRESH, boundJkt, existing.sessionCreatedAt)
            return TokenPair(newAccess, newRefresh)
        }
    }

    /** Vue en lecture de la table des jetons (endpoint de debug — lab uniquement). */
    fun snapshot(): List<TokenRecord> = tokens.values.sortedBy { it.sessionCreatedAt }

    fun reset() = synchronized(lock) { tokens.clear() }

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
