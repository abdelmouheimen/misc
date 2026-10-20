package com.example.dpoplab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Invariant de sécurité : un refresh token ne produit jamais plus d'un renouvellement réussi,
 * même lorsque plusieurs requêtes le présentent simultanément.
 *
 * Politique conservée pour la requête perdante : elle présente un jeton déjà consommé, ce qui
 * déclenche la détection de réutilisation et révoque tous les jetons de l'utilisateur, y
 * compris la paire du gagnant (voir attacks/09_refresh_storm.sh).
 */
class RefreshRotationConcurrencyTest {

    private sealed interface Outcome {
        data class Renewed(val pair: TokenPair) : Outcome
        data object ReuseDetected : Outcome
        data object Rejected : Outcome
    }

    /** Appel direct au TokenStore : seule la détection de réutilisation est une issue attendue. */
    private fun TokenStore.tryRefresh(refreshToken: String, jkt: String): Outcome = try {
        refresh(refreshToken, jkt)?.let { Outcome.Renewed(it) } ?: Outcome.Rejected
    } catch (e: TokenReuseException) {
        Outcome.ReuseDetected
    }

    /** Test 1 : deux requêtes HTTP simultanées, deux preuves distinctes et valides signées par A. */
    @RepeatedTest(20)
    fun `two concurrent refreshes with distinct valid proofs renew the token only once`() {
        val lab = Lab()
        val keyA = newDpopKey()
        val rt1 = lab.login(keyA).refreshToken!!
        val proofs = List(2) { dpopProof(keyA, "/login/refresh") }

        val responses = runConcurrently(proofs.map { proof -> { lab.refresh(proof, rt1) } })

        assertThat(responses.count { it.status == "AUTHENTICATED" }).isEqualTo(1)
        assertThat(responses.count { it.reason == "reuse detected: session revoked" }).isEqualTo(1)
        // RT1 consommé une seule fois : une seule paire émise en plus de la paire initiale.
        assertThat(lab.tokenStore.snapshot()).hasSize(4)
        assertThat(lab.record(rt1, TokenType.REFRESH).revoked).isTrue()
        // Politique de réutilisation : la requête perdante révoque toute la session.
        assertThat(lab.activeTokens()).isEmpty()
    }

    /** Test 2 : forte concurrence sur le même refresh token (10 puis 50 requêtes). */
    @ParameterizedTest(name = "{0} requêtes concurrentes × {1} répétitions")
    @CsvSource("10, 100", "50, 20")
    fun `under heavy concurrency a refresh token is renewed at most once`(threads: Int, repetitions: Int) {
        repeat(repetitions) {
            val jwtService = JwtService("dpop-lab-backend", 300, 86400)
            val store = TokenStore(jwtService)
            val jkt = newDpopKey().computeThumbprint().toString()
            val rt1 = store.createTokens("alice", jkt).refreshToken

            val outcomes = runConcurrently(List(threads) { { store.tryRefresh(rt1, jkt) } })

            assertThat(outcomes.filterIsInstance<Outcome.Renewed>()).hasSize(1)
            assertThat(outcomes.count { it == Outcome.ReuseDetected }).isEqualTo(threads - 1)
            assertThat(outcomes).doesNotContain(Outcome.Rejected)
            // Pas de double émission ni de jeton orphelin : paire initiale + paire du gagnant.
            assertThat(store.snapshot()).hasSize(4)
            assertThat(store.snapshot()).allMatch { it.revoked }
        }
    }

    /**
     * Test 3 : régression du scénario mesuré par l'audit (2 renouvellements simultanés,
     * 14 doubles succès sur 60 essais avant correction). Attendu : 0 double succès sur 100.
     */
    @Test
    fun `regression - no double success over 100 rounds of two concurrent refreshes`() {
        var doubleSuccesses = 0
        repeat(100) {
            val lab = Lab()
            val keyA = newDpopKey()
            val rt1 = lab.login(keyA).refreshToken!!
            val proofs = List(2) { dpopProof(keyA, "/login/refresh") }
            val responses = runConcurrently(proofs.map { proof -> { lab.refresh(proof, rt1) } })
            if (responses.count { it.status == "AUTHENTICATED" } > 1) doubleSuccesses++
        }
        assertThat(doubleSuccesses).isZero()
    }

    /**
     * Test 8 : robustesse. Les tâches sont bornées dans le temps (blocage détecté), aucune
     * exception autre que la détection de réutilisation n'est tolérée, et des sessions
     * d'utilisateurs différents tournées en parallèle ne s'influencent pas.
     */
    @Test
    fun `independent sessions rotate concurrently without interference`() {
        val jwtService = JwtService("dpop-lab-backend", 300, 86400)
        val store = TokenStore(jwtService)
        val users = (1..20).map { "user$it" }
        val jkts = users.associateWith { newDpopKey().computeThumbprint().toString() }
        val tokens = users.associateWith { store.createTokens(it, jkts.getValue(it)).refreshToken }

        val outcomes = runConcurrently(users.map { u -> { store.tryRefresh(tokens.getValue(u), jkts.getValue(u)) } })

        assertThat(outcomes).allMatch { it is Outcome.Renewed }
        for (u in users) {
            val active = store.snapshot().filter { it.userId == u && !it.revoked }
            assertThat(active.map { it.type }).containsExactlyInAnyOrder(TokenType.ACCESS, TokenType.REFRESH)
            assertThat(active).allMatch { it.dpopJkt == jkts.getValue(u) }
        }
    }
}
