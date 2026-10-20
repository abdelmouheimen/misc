package com.example.dpoplab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Comportements DPoP et rotation de bout en bout : contrôleur, validateur et TokenStore réels. */
class RefreshFlowTest {

    private val lab = Lab()
    private val keyA = newDpopKey()
    private val jktA = keyA.computeThumbprint().toString()

    /** Test 4 : RT actif lié à A, preuve valide signée par B → refus, sans émission ni révocation. */
    @Test
    fun `active refresh token presented with another key is refused without revocation`() {
        val rt1 = lab.login(keyA).refreshToken!!

        val attacker = lab.refresh(dpopProof(newDpopKey(), "/login/refresh"), rt1)

        assertThat(attacker.status).isEqualTo("NONE")
        assertThat(attacker.reason).isEqualTo("refresh rejected")
        assertThat(lab.tokenStore.snapshot()).hasSize(2)
        assertThat(lab.activeTokens()).hasSize(2)
        // Le mismatch seul ne révoque rien : le client légitime renouvelle ensuite normalement.
        assertThat(lab.refresh(dpopProof(keyA, "/login/refresh"), rt1).status).isEqualTo("AUTHENTICATED")
    }

    /**
     * Test 5 : une preuve rejouée est rejetée sur son jti avant tout examen du refresh token.
     * Le rejeu accompagne RT1, déjà consommé : si TokenStore l'examinait, la détection de
     * réutilisation révoquerait la session. Elle doit rester intacte.
     */
    @Test
    fun `replayed proof is rejected on its jti before the refresh token is examined`() {
        val rt1 = lab.login(keyA).refreshToken!!
        val proof = dpopProof(keyA, "/login/refresh")
        val rt2 = lab.refresh(proof, rt1).refreshToken!!

        val replay = lab.refresh(proof, rt1)

        assertThat(replay.status).isEqualTo("NONE")
        assertThat(replay.reason).isEqualTo("DPoP missing or invalid")
        assertThat(lab.record(rt2, TokenType.REFRESH).revoked).isFalse()
        assertThat(lab.activeTokens()).hasSize(2)
    }

    /** Test 6 : un ancien RT présenté avec une preuve valide déclenche la révocation de la session. */
    @Test
    fun `reused refresh token revokes all tokens of the user`() {
        val rt1 = lab.login(keyA).refreshToken!!
        val rt2 = lab.refresh(dpopProof(keyA, "/login/refresh"), rt1).refreshToken!!

        val reuse = lab.refresh(dpopProof(keyA, "/login/refresh"), rt1)

        assertThat(reuse.reason).isEqualTo("reuse detected: session revoked")
        assertThat(lab.activeTokens()).isEmpty()
        assertThat(lab.refresh(dpopProof(keyA, "/login/refresh"), rt2).reason).isEqualTo("reuse detected: session revoked")
    }

    /** Test 7 : RT1 → RT2 → RT3 conserve la liaison et sessionCreatedAt, seul RT3 reste actif. */
    @Test
    fun `rotation keeps the DPoP binding and the session start`() {
        val rt1 = lab.login(keyA).refreshToken!!
        val rt2 = lab.refresh(dpopProof(keyA, "/login/refresh"), rt1).refreshToken!!
        val rt3 = lab.refresh(dpopProof(keyA, "/login/refresh"), rt2).refreshToken!!

        val records = listOf(rt1, rt2, rt3).map { lab.record(it, TokenType.REFRESH) }
        assertThat(records).allMatch { it.dpopJkt == jktA }
        assertThat(records.map { it.sessionCreatedAt }.distinct()).hasSize(1)
        assertThat(records.map { it.revoked }).containsExactly(true, true, false)
        assertThat(lab.activeTokens().map { it.type }).containsExactlyInAnyOrder(TokenType.ACCESS, TokenType.REFRESH)
    }
}
