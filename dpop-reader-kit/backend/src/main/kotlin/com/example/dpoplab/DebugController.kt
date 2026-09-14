package com.example.dpoplab

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class ProofRequest(
    val key: String = "client",
    val htm: String = "POST",
    val htu: String = "http://localhost:8099/login/refresh",
    val alg: String = "ES256",
    val typ: String = "dpop+jwt",
    val iatOffset: Long = 0,
    val jti: String? = null,
    val noJwk: Boolean = false,
    val privateInJwk: Boolean = false,
    val tamper: Boolean = false,
)

/**
 * Endpoints de laboratoire pour forger des preuves DPoP fraîches depuis les fichiers .http.
 *
 * OUTIL PÉDAGOGIQUE UNIQUEMENT — à ne jamais exposer en production : ici le serveur
 * détient les clés, ce qui va à l'encontre du principe même de DPoP. Il ne s'agit que
 * d'un raccourci pour rendre les fichiers .http cliquables (voir attacks/http/).
 */
@RestController
class DebugController {

    // Deux clés fixes le temps de vie du processus : celle du client, celle de l'attaquant.
    private val factories = mapOf(
        "client" to ProofFactory.generate(),
        "attacker" to ProofFactory.generate(),
    )

    @PostMapping("/debug/proof")
    fun proof(@RequestBody req: ProofRequest): Map<String, String> {
        val factory = factories[req.key] ?: factories.getValue("client")
        return mapOf("proof" to factory.build(req), "jkt" to factory.jkt())
    }
}
