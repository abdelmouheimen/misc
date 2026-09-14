package com.example.dpoplab

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * CORS pour la SPA React servie par le serveur de dev Vite (origine distincte du backend).
 *
 * Deux points indispensables et faciles à oublier avec DPoP :
 *  - `allowedHeaders` DOIT inclure `DPoP`, sinon le navigateur bloque la requête au preflight ;
 *  - `allowCredentials(true)` est requis pour que le cookie HttpOnly du refresh soit envoyé.
 */
@Configuration
class CorsConfig(
    @Value("\${cors.allowed-origin:http://localhost:5173}") private val allowedOrigin: String,
) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOrigins(allowedOrigin)
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("Content-Type", "DPoP", "X-Refresh-Token")
            .allowCredentials(true)
    }
}
