package com.example.dpoplab

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Clé publique de signature des JWT, au format JWKS.
 * Permet de vérifier un access ou refresh token émis par le lab (jwt.io, jose, nimbus…).
 */
@RestController
class JwksController(private val jwtService: JwtService) {

    @GetMapping("/.well-known/jwks.json")
    fun jwks(): Map<String, Any> = jwtService.jwks()
}
