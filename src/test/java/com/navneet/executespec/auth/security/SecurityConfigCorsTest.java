package com.navneet.executespec.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.navneet.executespec.auth.oauth.CookieOAuth2AuthorizationRequestRepository;
import com.navneet.executespec.config.SecurityProperties;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

class SecurityConfigCorsTest {

  @Test
  void corsConfigurationIncludesConcurrentLocalVitePortsAndTrimsOrigins() {
    SecurityConfig config =
        new SecurityConfig(
            mock(JwtAuthenticationFilter.class),
            mock(com.navneet.executespec.ratelimit.RateLimitFilter.class),
            null,
            mock(CookieOAuth2AuthorizationRequestRepository.class),
            new SecurityProperties(),
            mock(JsonAuthenticationEntryPoint.class),
            "none",
            "none");
    ReflectionTestUtils.setField(
        config,
        "allowedOrigins",
        "http://localhost:5173, http://127.0.0.1:5173, http://localhost:5174,"
            + " http://127.0.0.1:5174");

    UrlBasedCorsConfigurationSource source =
        (UrlBasedCorsConfigurationSource) config.corsConfigurationSource();
    CorsConfiguration cors =
        source.getCorsConfiguration(new MockHttpServletRequest("POST", "/api/auth/login"));

    assertThat(cors).isNotNull();
    assertThat(cors.getAllowedOrigins())
        .containsExactly(
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:5174",
            "http://127.0.0.1:5174");
    assertThat(cors.getAllowCredentials()).isTrue();
  }
}
