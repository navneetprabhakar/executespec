package com.navneet.executespec.auth.security;

import com.navneet.executespec.auth.oauth.OAuthSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final com.navneet.executespec.ratelimit.RateLimitFilter rateLimitFilter;
    @Nullable
    private final OAuthSuccessHandler oAuthSuccessHandler;
    private final boolean oauthEnabled;

    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
            com.navneet.executespec.ratelimit.RateLimitFilter rateLimitFilter,
            @Nullable OAuthSuccessHandler oAuthSuccessHandler,
            @Value("${spring.security.oauth2.client.registration.google.client-id:none}") String googleClientId,
            @Value("${spring.security.oauth2.client.registration.github.client-id:none}") String githubClientId) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.oAuthSuccessHandler = oAuthSuccessHandler;

        boolean hasGoogle = googleClientId != null && !"none".equals(googleClientId) && !googleClientId.isBlank();
        boolean hasGithub = githubClientId != null && !"none".equals(githubClientId) && !githubClientId.isBlank();
        this.oauthEnabled = hasGoogle || hasGithub;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                                        + "img-src 'self' data:; connect-src 'self' wss:; font-src 'self' https://fonts.gstatic.com; "
                                        + "frame-ancestors 'none'"))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)))
                // IF_REQUIRED when OAuth2 is enabled (needs sessions for auth code flow),
                // STATELESS otherwise (pure JWT)
                .sessionManagement(sm -> sm.sessionCreationPolicy(
                        oauthEnabled ? SessionCreationPolicy.IF_REQUIRED : SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public auth endpoints
                        .requestMatchers("/api/auth/**").permitAll()
                        // OAuth2 login flow (Spring Security managed endpoints)
                        .requestMatchers("/api/auth/oauth2/**").permitAll()
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        // Payment gateway webhooks (secured by signature, not JWT)
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/razorpay").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/stripe").permitAll()
                        // Public health check
                        .requestMatchers("/api/health", "/api/health/**").permitAll()
                        // Actuator endpoints (health, metrics, prometheus)
                        .requestMatchers("/actuator/**").permitAll()
                        // Integration webhooks (external — secured by signature, not JWT)
                        .requestMatchers("/api/integrations/*/webhook").permitAll()
                        // Slack OAuth callback (external redirect — no JWT)
                        .requestMatchers("/api/integrations/slack/oauth/callback").permitAll()
                        // SSE streaming (authenticated via ?token= query param)
                        .requestMatchers("/api/runs/*/events").permitAll()
                        // Public shared validation reports (no auth)
                        .requestMatchers(HttpMethod.GET, "/api/reports/shared/*").permitAll()
                        // Static frontend assets (SPA)
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Admin endpoints — SUPER_ADMIN only
                        .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
                        // All other API endpoints require authentication
                        .requestMatchers("/api/**").authenticated()
                        // Everything else (SPA routes) — permit
                        .anyRequest().permitAll())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);

        // OAuth2 login (Google + GitHub) — only when client IDs are configured
        if (oauthEnabled && oAuthSuccessHandler != null) {
            http.oauth2Login(oauth -> oauth
                    .authorizationEndpoint(a -> a.baseUri("/api/auth/oauth2/authorize"))
                    .redirectionEndpoint(r -> r.baseUri("/api/auth/oauth2/callback/*"))
                    .successHandler(oAuthSuccessHandler)
                    .failureHandler((req, res, ex) -> res.sendRedirect("/login?error=oauth_failed")));
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
