package com.example.dpoplab

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Date
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

const val BASE_URL = "http://localhost:8099"

/** Clé DPoP EC P-256 d'un client de test (clé A du client légitime, clé B de l'attaquant). */
fun newDpopKey(): ECKey = ECKeyGenerator(Curve.P_256).generate()

/** Preuve DPoP ES256 valide pour [path], signée par [key] (RFC 9449 §4.2). */
fun dpopProof(key: ECKey, path: String, method: String = "POST", jti: String = UUID.randomUUID().toString()): String {
    val header = JWSHeader.Builder(JWSAlgorithm.ES256)
        .type(JOSEObjectType("dpop+jwt"))
        .jwk(key.toPublicJWK())
        .build()
    val claims = JWTClaimsSet.Builder()
        .jwtID(jti)
        .claim("htm", method)
        .claim("htu", BASE_URL + path)
        .issueTime(Date())
        .build()
    return SignedJWT(header, claims).apply { sign(ECDSASigner(key)) }.serialize()
}

/** Backend complet en mémoire : vrai validateur DPoP, vrai TokenStore, vrai contrôleur. */
class Lab {
    val jwtService = JwtService("dpop-lab-backend", 300, 86400)
    val tokenStore = TokenStore(jwtService)
    val controller = AuthController(DpopProofValidator(BASE_URL), tokenStore, dpopRequired = true)

    fun login(key: ECKey, uid: String = "alice"): ConnectionResponse {
        val request = MockHttpServletRequest("POST", "/login/connection")
        request.addHeader("DPoP", dpopProof(key, "/login/connection"))
        return controller.connection(request, MockHttpServletResponse(), ConnectionRequest(uid))
    }

    fun refresh(proof: String, refreshToken: String): ConnectionResponse {
        val request = MockHttpServletRequest("POST", "/login/refresh")
        request.addHeader("DPoP", proof)
        request.addHeader("X-Refresh-Token", refreshToken)
        return controller.refresh(request, MockHttpServletResponse())
    }

    /** Enregistrement serveur d'un jeton émis (retrouvé par le jti du JWT). */
    fun record(token: String, type: TokenType): TokenRecord {
        val jti = jwtService.verify(token, type)!!.jwtid
        return tokenStore.snapshot().single { it.jti == jti }
    }

    fun activeTokens(userId: String = "alice") = tokenStore.snapshot().filter { it.userId == userId && !it.revoked }
}

/**
 * Lance [tasks] en parallèle derrière une barrière de départ commune, pour maximiser le
 * chevauchement. Toute exception non attendue est propagée (pas de catch générique) et un
 * blocage est détecté par le délai d'attente.
 */
fun <T> runConcurrently(tasks: List<() -> T>, timeoutSeconds: Long = 30): List<T> {
    val pool = Executors.newFixedThreadPool(tasks.size)
    try {
        val ready = CountDownLatch(tasks.size)
        val start = CountDownLatch(1)
        val futures = tasks.map { task ->
            pool.submit(Callable {
                ready.countDown()
                start.await()
                task()
            })
        }
        check(ready.await(timeoutSeconds, TimeUnit.SECONDS)) { "threads not started" }
        start.countDown()
        return futures.map { it.get(timeoutSeconds, TimeUnit.SECONDS) }
    } finally {
        pool.shutdownNow()
        check(pool.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) { "worker threads still running" }
    }
}
