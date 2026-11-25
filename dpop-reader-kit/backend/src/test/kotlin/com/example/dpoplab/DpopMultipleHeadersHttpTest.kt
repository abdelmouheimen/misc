package com.example.dpoplab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import java.net.Socket

/**
 * RFC 9449 §4.3, contrôle 1 : une requête ne doit pas porter plus d'un en-tête DPoP.
 *
 * Tests d'intégration HTTP réels : le serveur Tomcat embarqué démarre sur un port aléatoire et
 * les requêtes sont écrites octet par octet sur une socket, pour maîtriser exactement les lignes
 * d'en-tête envoyées (doublons, casse des noms, valeurs combinées) sans normalisation par un
 * client HTTP.
 */
private const val PROPS = "dpop.public-base-url=$BASE_URL"
private const val REJECTED = "DPoP missing or invalid"

data class RawResponse(val raw: String) {
    private fun field(name: String) = Regex("\"$name\":\"([^\"]*)\"").find(raw)?.groupValues?.get(1)
    val status get() = field("status")
    val reason get() = field("reason")
    val refreshToken get() = field("refreshToken")
}

/** POST HTTP/1.1 brut : chaque paire de [headers] devient une ligne d'en-tête, dans l'ordre. */
fun rawPost(port: Int, path: String, headers: List<Pair<String, String>>, body: String = ""): RawResponse {
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        val payload = body.toByteArray()
        val head = buildString {
            append("POST $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n")
            append("Content-Type: application/json\r\nContent-Length: ${payload.size}\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().apply { write(head.toByteArray()); write(payload); flush() }
        return RawResponse(socket.getInputStream().readAllBytes().toString(Charsets.UTF_8))
    }
}

abstract class MultipleHeadersBase {
    @Value("\${local.server.port}")
    var port: Int = 0

    @Autowired
    lateinit var tokenStore: TokenStore

    protected val keyA = newDpopKey()

    @BeforeEach
    fun reset() = tokenStore.reset()

    protected fun connect(vararg dpop: Pair<String, String>) =
        rawPost(port, "/login/connection", dpop.toList(), """{"uid":"alice"}""")

    protected fun refresh(refreshToken: String, vararg dpop: Pair<String, String>) =
        rawPost(port, "/login/refresh", dpop.toList() + ("Cookie" to "refresh_token=$refreshToken"))

    protected fun proof(path: String, key: com.nimbusds.jose.jwk.ECKey = keyA) = dpopProof(key, path)

    protected fun dpop(value: String) = "DPoP" to value

    /** Connexion liée à la clé A, avec une seule preuve : renvoie le refresh token. */
    protected fun boundLogin(): String {
        val r = connect(dpop(proof("/login/connection")))
        assertThat(r.status).isEqualTo("AUTHENTICATED")
        return r.refreshToken!!
    }

    protected fun assertRejectedWithoutSideEffect(response: RawResponse, tokensBefore: Int) {
        assertThat(response.status).isEqualTo("NONE")
        assertThat(response.reason).isEqualTo(REJECTED)
        assertThat(tokenStore.snapshot()).hasSize(tokensBefore)
        assertThat(tokenStore.snapshot()).noneMatch { it.revoked }
    }
}

/** Mode strict (dpop.required=true, configuration par défaut du lab). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [PROPS, "dpop.required=true"])
class DpopMultipleHeadersStrictTest : MultipleHeadersBase() {

    @Test
    fun `a single valid proof is accepted, whatever the case of the header name`() {
        assertThat(connect(dpop(proof("/login/connection"))).status).isEqualTo("AUTHENTICATED")
        assertThat(connect("dpop" to proof("/login/connection")).status).isEqualTo("AUTHENTICATED")
        assertThat(connect("DPOP" to proof("/login/connection")).status).isEqualTo("AUTHENTICATED")
    }

    @Test
    fun `two headers carrying the same proof are rejected`() {
        val p = proof("/login/connection")
        assertRejectedWithoutSideEffect(connect(dpop(p), dpop(p)), 0)
    }

    @Test
    fun `two headers carrying different valid proofs are rejected`() {
        val response = connect(dpop(proof("/login/connection")), dpop(proof("/login/connection", newDpopKey())))
        assertRejectedWithoutSideEffect(response, 0)
    }

    @Test
    fun `a valid and an invalid proof are rejected in both orders`() {
        val wrongContext = proof("/login/refresh") // JWT bien formé, htu d'un autre endpoint
        for (invalid in listOf(wrongContext, "not-a-jwt")) {
            assertRejectedWithoutSideEffect(connect(dpop(proof("/login/connection")), dpop(invalid)), 0)
            assertRejectedWithoutSideEffect(connect(dpop(invalid), dpop(proof("/login/connection"))), 0)
        }
    }

    @Test
    fun `several values combined in a single header line are rejected`() {
        val p = proof("/login/connection")
        assertRejectedWithoutSideEffect(connect(dpop("$p, ${proof("/login/connection")}")), 0)
        assertRejectedWithoutSideEffect(connect(dpop("$p,$p")), 0)
    }

    @Test
    fun `duplicate header names with different cases are rejected`() {
        val response = connect("DPoP" to proof("/login/connection"), "dpop" to proof("/login/connection"))
        assertRejectedWithoutSideEffect(response, 0)
    }

    /** Le rejet a lieu avant la validation : le jti n'est pas consommé dans le cache anti-rejeu. */
    @Test
    fun `a rejected request does not consume the jti of its proofs`() {
        val p = proof("/login/connection")
        assertRejectedWithoutSideEffect(connect(dpop(p), dpop(p)), 0)
        assertRejectedWithoutSideEffect(connect(dpop("$p, $p")), 0)

        // La même preuve, envoyée seule, est acceptée : son jti n'avait pas été enregistré.
        assertThat(connect(dpop(p)).status).isEqualTo("AUTHENTICATED")
        // Et un rejeu ordinaire reste détecté.
        assertThat(connect(dpop(p)).reason).isEqualTo(REJECTED)
    }

    @Test
    fun `refresh with several proofs is rejected without rotation or revocation`() {
        val rt1 = boundLogin()
        val attacker = proof("/login/refresh", newDpopKey())
        val legit = proof("/login/refresh")

        assertRejectedWithoutSideEffect(refresh(rt1, dpop(legit), dpop(legit)), 2)
        assertRejectedWithoutSideEffect(refresh(rt1, dpop(attacker), dpop(legit)), 2)
        assertRejectedWithoutSideEffect(refresh(rt1, dpop(legit), dpop(attacker)), 2)
        assertRejectedWithoutSideEffect(refresh(rt1, dpop("$legit, $attacker")), 2)

        // RT1 reste actif et se renouvelle normalement avec la preuve légitime, envoyée seule.
        assertThat(refresh(rt1, dpop(legit)).status).isEqualTo("AUTHENTICATED")
    }
}

/** Mode de compatibilité (dpop.required=false) : plusieurs preuves ne valent pas absence de preuve. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [PROPS, "dpop.required=false"])
class DpopMultipleHeadersCompatTest : MultipleHeadersBase() {

    @Test
    fun `existing behaviour is preserved with zero or one proof`() {
        assertThat(connect().status).isEqualTo("AUTHENTICATED") // bearer : pas de preuve, jeton non lié
        assertThat(tokenStore.snapshot()).allMatch { it.dpopJkt == null }

        tokenStore.reset()
        boundLogin()
        assertThat(tokenStore.snapshot()).allMatch { it.dpopJkt == keyA.computeThumbprint().toString() }
    }

    @Test
    fun `several proofs on connection are rejected instead of issuing an unbound session`() {
        val p = proof("/login/connection")
        assertRejectedWithoutSideEffect(connect(dpop(p), dpop(p)), 0)
        assertRejectedWithoutSideEffect(connect(dpop(p), "dpop" to proof("/login/connection")), 0)
        assertRejectedWithoutSideEffect(connect(dpop("$p, $p")), 0)
    }

    @Test
    fun `a bound refresh token cannot be refreshed with several proofs`() {
        val rt1 = boundLogin()
        val attacker = proof("/login/refresh", newDpopKey())
        val legit = proof("/login/refresh")

        assertRejectedWithoutSideEffect(refresh(rt1, dpop(attacker), dpop(legit)), 2)
        assertRejectedWithoutSideEffect(refresh(rt1, dpop(legit), dpop(attacker)), 2)
        assertRejectedWithoutSideEffect(refresh(rt1, dpop("$attacker,$legit")), 2)

        assertThat(refresh(rt1, dpop(legit)).status).isEqualTo("AUTHENTICATED")
    }
}
