package com.example.dpoplab

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class IntrospectRequest(val token: String = "")

/**
 * Introspection d'un jeton — OUTIL PÉDAGOGIQUE UNIQUEMENT, à ne jamais exposer en production.
 *
 * Les jetons sont chiffrés (JWE) : ni le client ni un attaquant ne peuvent lire leurs claims.
 * Cet endpoint montre ce que seul le serveur voit après déchiffrement : en-têtes JWE et JWS,
 * et claims (dont la liaison DPoP `cnf.jkt`).
 */
@RestController
class IntrospectController(private val jwtService: JwtService) {

    @PostMapping("/debug/introspect")
    fun introspect(@RequestBody req: IntrospectRequest): ResponseEntity<Map<String, Any?>> {
        val result = jwtService.introspect(req.token)
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "token illisible (déchiffrement ou signature invalide)"))
        return ResponseEntity.ok(result)
    }
}
