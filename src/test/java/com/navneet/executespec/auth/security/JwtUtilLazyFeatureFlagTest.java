package com.navneet.executespec.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Pins the fix for the 2026-08-09 {@code prod,gcp} boot crash-loop. {@code JwtAuthenticationFilter}
 * is a servlet {@code Filter} — a {@code ServletContextInitializer} — so Spring instantiates it
 * (and its {@code JwtUtil} dependency) during embedded-web-server creation in {@code onRefresh()},
 * BEFORE the shared JPA {@code EntityManagerFactory} singleton is initialized in {@code
 * finishBeanFactoryInitialization()}. {@code JwtUtil} therefore must NOT eagerly resolve {@code
 * FeatureFlagService} (whose {@code @PostConstruct} loads its cache from a Spring Data JPA
 * repository) — doing so dragged the EntityManagerFactory into the filter's construction path and
 * crash-looped every boot. It must resolve the flag lazily, on the request path, where the full
 * context is up.
 *
 * <p>The assertion is behavioral: constructing {@code JwtUtil} and minting tokens must never touch
 * the {@code FeatureFlagService} provider; only {@code isTokenValid} may resolve it. If a future
 * change reintroduces an eager dependency, this fails deterministically in unit CI instead of on
 * the production VM.
 */
class JwtUtilLazyFeatureFlagTest {

  private static final String SECRET = "jwtutil-lazy-flag-regression-secret-at-least-32-bytes";

  @Test
  @SuppressWarnings("unchecked")
  void constructionAndTokenMintingNeverResolveTheFeatureFlagService() {
    ObjectProvider<FeatureFlagService> provider = mock(ObjectProvider.class);
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(provider.getObject()).thenReturn(flags);
    when(flags.getString("force_logout_epoch", null)).thenReturn(null);

    // Construction must not resolve the provider — this is the exact seam that forced JPA init
    // during servlet-filter gathering and crash-looped prod,gcp.
    JwtUtil jwtUtil = new JwtUtil(SECRET, 15, 7, provider);
    verify(provider, never()).getObject();

    // Minting tokens must not resolve it either.
    var user = new com.navneet.executespec.auth.entity.AppUser();
    user.setId("user-1");
    user.setEmail("user@example.com");
    user.setGlobalRole("USER");
    user.setTier("FREE");
    String access = jwtUtil.generateAccessToken(user, null);
    jwtUtil.generateRefreshToken(user);
    verify(provider, never()).getObject();

    // Only the request-path validation resolves the flag — lazily, once the context is fully up.
    assertThat(jwtUtil.isTokenValid(access, null)).isTrue();
    verify(provider, times(1)).getObject();
  }
}
