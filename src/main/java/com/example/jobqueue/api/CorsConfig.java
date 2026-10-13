package com.example.jobqueue.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets a browser frontend served from another origin (e.g. a dev server on localhost:5173) call the API.
 * Override with jobqueue.cors.allowed-origins (comma-separated origin patterns), or the
 * JOBQUEUE_CORS_ALLOWED_ORIGINS environment variable.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${jobqueue.cors.allowed-origins:http://localhost:*,http://127.0.0.1:*}")
                      String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Total-Count");
    }
}
