package com.navneet.executespec.auth.security;

import java.util.List;

import com.navneet.executespec.auth.oauth.CookieOAuth2AuthorizationRequestRepository;
import com.navneet.executespec.auth.oauth.OAuthRedirectCookieFilter;
import com.navneet.executespec.auth.oauth.OAuthSuccessHandler;
import com.navneet.executespec.config.SecurityProperties;

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
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import lombok.extern.slf4j.Slf4j;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Slf4j
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final com.navneet.executespec.ratelimit.RateLimitFilter rateLimitFilter;
  @Nullable private final OAuthSuccessHandler oAuthSuccessHandler;
  private final CookieOAuth2AuthorizationRequestRepository authorizationRequestRepository;
  private final SecurityProperties securityProperties;
  private final boolean oauthEnabled;
  private final JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint;

  @Value("${app.cors.allowed-origins:http://localhost:5173}")
  private String allowedOrigins;

  @Value("${app.mail.base-url:http://localhost:5173}")
  private String dashboardUrl;

  public SecurityConfig(
      JwtAuthenticationFilter jwtAuthenticationFilter,
      com.navneet.executespec.ratelimit.RateLimitFilter rateLimitFilter,
      @Nullable OAuthSuccessHandler oAuthSuccessHandler,
      CookieOAuth2AuthorizationRequestRepository authorizationRequestRepository,
      SecurityProperties securityProperties,
      JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint,
      @Value("${spring.security.oauth2.client.registration.google.client-id:none}")
          String googleClientId,
      @Value("${spring.security.oauth2.client.registration.github.client-id:none}")
          String githubClientId) {
    this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    this.rateLimitFilter = rateLimitFilter;
    this.oAuthSuccessHandler = oAuthSuccessHandler;
    this.authorizationRequestRepository = authorizationRequestRepository;
    this.securityProperties = securityProperties;
    this.jsonAuthenticationEntryPoint = jsonAuthenticationEntryPoint;

    boolean hasGoogle =
        googleClientId != null && !"none".equals(googleClientId) && !googleClientId.isBlank();
    boolean hasGithub =
        githubClientId != null && !"none".equals(githubClientId) && !githubClientId.isBlank();
    this.oauthEnabled = hasGoogle || hasGithub;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .headers(
            headers -> {
              // Frame options — feature-flagged
              if (securityProperties.getFrameOptions().isEnabled()) {
                String mode = securityProperties.getFrameOptions().getMode();
                headers.frameOptions(
                    frame -> {
                      if ("SAMEORIGIN".equalsIgnoreCase(mode)) {
                        frame.sameOrigin();
                      } else {
                        frame.deny();
                      }
                    });
              } else {
                headers.frameOptions(frame -> frame.disable());
              }

              // Content Security Policy — feature-flagged
              if (securityProperties.getCsp().isEnabled()) {
                headers.contentSecurityPolicy(
                    csp -> csp.policyDirectives(securityProperties.getCsp().getPolicy()));
              }

              // HSTS — feature-flagged
              if (securityProperties.getHsts().isEnabled()) {
                var hstsProps = securityProperties.getHsts();
                headers.httpStrictTransportSecurity(
                    hsts -> {
                      hsts.includeSubDomains(hstsProps.isIncludeSubDomains())
                          .maxAgeInSeconds(hstsProps.getMaxAgeSeconds());
                      // 2026-05-11 — UX audit B29. Emit the `preload`
                      // directive when configured so the domain can be
                      // submitted to hstspreload.org after 30 days of
                      // stable HTTPS. Off by default — safe to enable
                      // pre-submission (browsers ignore unrecognized
                      // domains).
                      if (hstsProps.isPreload()) {
                        hsts.preload(true);
                      }
                    });
              } else {
                headers.httpStrictTransportSecurity(hsts -> hsts.disable());
              }
            })
        // IF_REQUIRED when OAuth2 is enabled (needs sessions for auth code flow),
        // STATELESS otherwise (pure JWT)
        .sessionManagement(
            sm ->
                sm.sessionCreationPolicy(
                    oauthEnabled
                        ? SessionCreationPolicy.IF_REQUIRED
                        : SessionCreationPolicy.STATELESS))
        // Closes adversarial-test-report Finding 8: the default
        // HttpSessionSecurityContextRepository was writing the authenticated
        // SecurityContext back to HttpSession after every JWT call, creating
        // a JSESSIONID even though the auth was stateless. Switching to the
        // request-scoped repository keeps JWT auth truly stateless. OAuth2's
        // own HttpSessionOAuth2AuthorizationRequestRepository is untouched,
        // so the authorization-code flow still works.
        .securityContext(
            ctx ->
                ctx.securityContextRepository(
                    new org.springframework.security.web.context
                        .RequestAttributeSecurityContextRepository()))
        .authorizeHttpRequests(
            auth ->
                auth
                    // Authenticated auth endpoints (must come before /api/auth/**)
                    .requestMatchers("/api/auth/me", "/api/auth/me/**")
                    .authenticated()
                    .requestMatchers("/api/auth/settings/**")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/auth/change-password")
                    .authenticated()
                    // Public auth endpoints
                    .requestMatchers("/api/auth/**")
                    .permitAll()
                    // OAuth2 login flow (Spring Security managed endpoints)
                    .requestMatchers("/api/auth/oauth2/**")
                    .permitAll()
                    .requestMatchers("/oauth2/**", "/login/oauth2/**")
                    .permitAll()
                    // Payment gateway webhooks (secured by signature, not JWT)
                    .requestMatchers(HttpMethod.POST, "/api/webhooks/razorpay")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/webhooks/stripe")
                    .permitAll()
                    // Public health check
                    .requestMatchers("/api/health", "/api/health/**")
                    .permitAll()
                    // Actuator: health public, prometheus for scraping, rest admin-only
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/actuator/prometheus")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .hasRole("SUPER_ADMIN")
                    // Integration webhooks (external — secured by signature, not JWT)
                    .requestMatchers("/api/integrations/*/webhook")
                    .permitAll()
                    // Pilot R5 — GitHub PR webhook (HMAC SHA-256 verified in controller)
                    .requestMatchers(HttpMethod.POST, "/webhooks/github")
                    .permitAll()
                    // v2.6.0 — internal reminder endpoint (shared-secret X-Admin-Reminder-Token,
                    // verified in controller)
                    .requestMatchers(HttpMethod.POST, "/api/internal/reminder/send")
                    .permitAll()
                    // Slack OAuth callback (external redirect — no JWT)
                    .requestMatchers("/api/integrations/slack/oauth/callback")
                    .permitAll()
                    // SSE streaming (authenticated via ?token= query param)
                    .requestMatchers("/api/runs/*/events")
                    .permitAll()
                    // Public shared validation reports (no auth)
                    .requestMatchers(HttpMethod.GET, "/api/reports/shared/*")
                    .permitAll()
                    // V175 — project share-links (no auth; bearer is the URL token, validated in
                    // controller). Covers /api/shared/{token}/meta + per-surface payloads.
                    .requestMatchers(HttpMethod.GET, "/api/shared/**")
                    .permitAll()
                    // V175 — invite preview is public so the recipient can see what they're
                    // accepting before signing in. Accept itself requires auth.
                    .requestMatchers(HttpMethod.GET, "/api/invites/preview")
                    .permitAll()
                    // R-111 — anonymous-trial endpoint. No JWT; rate-limited
                    // by IP. Real LLM dispatch is gated by a feature flag
                    // until the anti-abuse story is locked in Wave 2.
                    .requestMatchers(HttpMethod.POST, "/api/v3/anonymous-runs")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v3/anonymous-runs/*")
                    .permitAll()
                    // R-162 magic-link landing — token IS the credential,
                    // so the lookup is public by token.
                    .requestMatchers(HttpMethod.GET, "/api/v3/referrals/lookup")
                    .permitAll()
                    // Selling-point landing surfaces — read-only,
                    // anonymized aggregates that drive the public
                    // marketing proof (verified-score counter, sample
                    // run gallery, public exec snapshot).
                    .requestMatchers(HttpMethod.GET, "/api/v3/landing/stats")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v3/landing/sample-runs")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v3/public/**")
                    .permitAll()
                    // Static frontend assets (SPA)
                    .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico")
                    .permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    // v2.5.8 — public read-only feature-flag endpoint: any
                    // authenticated user can call it so the frontend can
                    // gate integration Configure CTAs on flag state.
                    .requestMatchers(HttpMethod.GET, "/api/admin/feature-flags/public")
                    .authenticated()
                    // Entity-scoped analytics is under /api/admin for legacy frontend routes, but
                    // the controller has its own org/user RBAC. Keep it before the blanket
                    // SUPER_ADMIN admin matcher so ORG_ADMIN/BILLING_ADMIN can read their own org.
                    .requestMatchers("/api/admin/analytics/**")
                    .authenticated()
                    // Admin endpoints — SUPER_ADMIN only
                    .requestMatchers("/api/admin/**")
                    .hasRole("SUPER_ADMIN")
                    // V2 native-tools diagnostic/test endpoints (test/*TestController) —
                    // operator-only. These drive the real planner/worker/router/reviewer/executor
                    // LLM pipeline, so they must NOT be an unauthenticated cost-burn + disclosure
                    // surface. Was permitAll() "temporarily until LlmRouter lands"; LlmRouter
                    // shipped, so they're now locked to SUPER_ADMIN (RL-2026-05-027). No frontend
                    // / eval-script / test caller relies on anonymous access (verified).
                    .requestMatchers("/api/v2/test/**")
                    .hasRole("SUPER_ADMIN")
                    // v2.5.12 — V2 Phase 9 canary retired; V2 is the only harness and is accessed
                    // via the regular /api/runs surface (RunController -> RunOrchestrator, which
                    // enforces tier / quota / cost-cap / tenant). The retired rollout RunService
                    // behind /api/v2/runs still spins up the REAL planner/executor/reviewer LLM
                    // pipeline with NONE of those gates, so any authenticated user could burn
                    // unlimited platform spend through it. Lock it to operator-only (C2 — audit
                    // 2026-05-29; no frontend / client / eval-script caller — verified).
                    .requestMatchers("/api/v2/runs", "/api/v2/runs/**")
                    .hasRole("SUPER_ADMIN")
                    // All other API endpoints require authentication
                    .requestMatchers("/api/**")
                    .authenticated()
                    // Everything else (SPA routes) — permit
                    .anyRequest()
                    .permitAll())
        .exceptionHandling(ex -> ex.authenticationEntryPoint(jsonAuthenticationEntryPoint))
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);

    // OAuth2 login (Google + GitHub) — only when client IDs are configured
    if (oauthEnabled && oAuthSuccessHandler != null) {
      http.oauth2Login(
          oauth ->
              oauth
                  .authorizationEndpoint(
                      a ->
                          a.baseUri("/api/auth/oauth2/authorize")
                              .authorizationRequestRepository(authorizationRequestRepository))
                  .redirectionEndpoint(r -> r.baseUri("/api/auth/oauth2/callback/*"))
                  .successHandler(oAuthSuccessHandler)
                  .failureHandler(
                      (req, res, ex) -> {
                        log.error("OAuth2 login failed: {}", ex.getMessage(), ex);
                        res.sendRedirect(dashboardUrl + "/login?error=oauth_failed");
                      }));
      // Preserve the CLI / VS Code /cli-login device-auth return URL across the OAuth round-trip:
      // stash ?redirect=/cli-login?... in a short-lived cookie that OAuthSuccessHandler reads back.
      http.addFilterBefore(
          new OAuthRedirectCookieFilter(), OAuth2AuthorizationRequestRedirectFilter.class);
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
  public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
      throws Exception {
    return config.getAuthenticationManager();
  }
}
