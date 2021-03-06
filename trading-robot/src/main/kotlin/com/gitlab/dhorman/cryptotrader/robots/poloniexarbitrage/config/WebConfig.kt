package com.gitlab.dhorman.cryptotrader.robots.poloniexarbitrage.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.config.CorsRegistry
import org.springframework.web.reactive.config.WebFluxConfigurer

@Configuration
class WebConfig : WebFluxConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns("*")
            .allowCredentials(true)
            .allowedMethods("*")
            .allowedHeaders("*")
            .exposedHeaders()
            .maxAge(1800)
    }
}
