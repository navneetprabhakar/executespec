package com.navneet.agentteam.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration.
 * CORS is now managed by Spring Security (SecurityConfig#corsConfigurationSource),
 * so this class no longer overrides addCorsMappings.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    // CORS handled by SecurityConfig to work correctly with the JWT filter chain.
}
