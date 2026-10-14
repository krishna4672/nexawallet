package com.nexawallet.platform.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC Configuration for Cross-Origin Resource Sharing (CORS) with Vite React frontend.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${nexa.cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Idempotency-Key", "X-RateLimit-Remaining", "Retry-After")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
